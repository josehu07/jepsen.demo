(ns systems.rmq
  (:require [helpers :refer [r
                             w
                             cas
                             checker-select
                             full-test-opts]]
            [clojure.tools.logging :refer [info]]
            [langohr
             [core :as rmq]
             [channel :as lchannel]
             [confirm :as lconfirm]
             [queue :as lqueue]
             [basic :as lbasic]]
            [jepsen
             [core :as jepsen]
             [util :as jutil]
             [codec :as codec]
             [client :as client]
             [control :as ctrl]
             [db :as db]
             [generator :as gen]
             [independent :as indp]
             [tests :as tests]
             [nemesis :as nemesis]
             [checker :as checker]]
            [jepsen.control.util :as util]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.checker.timeline :as timeline])
  (:import (com.rabbitmq.client AlreadyClosedException)))

; Paths used in tests
(def rmqdir "/home/jepsen/rabbitmq-v3.8")
(def rmqdata "/var/lib/rabbitmq/mnesia")
(def rmqlogs "/var/log/rabbitmq")

(def conffile "/etc/rabbitmq/rabbitmq.conf")
(def envsfile "/etc/rabbitmq/rabbitmq-env.conf")

(def rmqcookie "jepsen-rabbitmq")
(def scookiefile "/var/lib/rabbitmq/.erlang.cookie")
(def ccookiefile "/home/jepsen/.erlang.cookie")

; Database setup
(defn rabbitmq-db
  "RabbitMQ database of a particular version."
  [_]
  (reify db/DB
    (setup! [_ test node]
      (info "using rabbitmq at" rmqdir)

      ; set cookie
      (when-not (and
                 (util/exists? scookiefile)
                 (= rmqcookie
                    (ctrl/exec :sudo :cat scookiefile)))
        (info "setting erlang cookie")
        (ctrl/exec :systemctl :stop :rabbitmq-server)
        (ctrl/su (ctrl/exec :echo rmqcookie :> scookiefile))
        (ctrl/su (ctrl/exec :echo rmqcookie :> ccookiefile)))

      ; write envs file
      (ctrl/su (ctrl/exec :echo
                          (str "HOSTNAME=" node "\n")
                          (str "NODENAME=rabbit@" node "\n")
                          "USE_LONGNAME=true\n"
                          :> envsfile))

      ; write conf file
      (ctrl/su (ctrl/exec :echo
                          (str "cluster_name = jepsen-rabbit\n"
                               "cluster_partition_handling = ignore\n")
                          :> conffile))

      ; start rabbitmq-server
      (try (ctrl/exec :sudo :systemctl :status :rabbitmq-server)
           (catch RuntimeException _
             (info "starting rabbitmq server")
             (ctrl/exec :sudo :systemctl :start :rabbitmq-server)))

      ; prepare for join_cluster
      (when-not (= node (first (:nodes test)))
        (ctrl/exec :sudo :rabbitmqctl :stop_app
                   "--erlang-cookie" rmqcookie))
      (jepsen/synchronize test)

      ; do join_cluster through rabbitmqctl
      (let [p (jepsen/primary test)]
        (when-not (= node p)
          (info "rabbitmq joining ->" p)
          (ctrl/exec :sudo :rabbitmqctl :join_cluster
                     "--erlang-cookie" rmqcookie
                     "-l" "-n" (str "rabbit@" node)
                     (str "rabbit@" (name p)))
          (info "rabbitmq joined ")
          (ctrl/exec :sudo :rabbitmqctl :start_app
                     "--erlang-cookie" rmqcookie)))
      (jepsen/synchronize test)

      ; add test client user
      (when (= node (first (:nodes test)))
        (info "creating rabbitmq user for jepsen")
        (ctrl/exec :sudo :rabbitmqctl :add_user
                   "--erlang-cookie" rmqcookie
                   "jepsen" "jepsen")
        (ctrl/exec :sudo :rabbitmqctl :set_user_tags
                   "--erlang-cookie" rmqcookie
                   "jepsen" "administrator")
        (ctrl/exec :sudo :rabbitmqctl :set_permissions
                   "--erlang-cookie" rmqcookie
                   "-p" "/" "jepsen" ".*" ".*" ".*"))

      (info "launched rabbitmq server")
      (Thread/sleep 10000))

    (teardown! [_ _ _]
      (info "tearing down running rabbitmq")

      (jutil/meh (ctrl/exec :sudo :killall :-9 "beam.smp" "epmd"))
      (ctrl/exec :sudo :systemctl :stop :rabbitmq-server)

      (ctrl/exec :sudo :rm :-rf rmqdata))

    ;; db/LogFiles
    ;; (log-files [_ _ _] [rmqlogs])
    ))

; Client implementation
(defmacro with-ch
  "Opens a channel on 'conn for body, binds it to the provided symbol 'ch,
   and ensures the channel is closed after body returns."
  {:clj-kondo/ignore [:unresolved-symbol]}
  [[ch conn] & body]
  `(let [~ch (lchannel/open ~conn)]
     (try ~@body
          (finally
            (try (rmq/close ~ch)
                 (catch AlreadyClosedException _#))))))

(defn queue-name
  [producer consumer]
  (str "q-" producer "-" consumer))

(defn rand-entry
  [queue-map]
  (let [peers (keys queue-map)
        peer (rand-nth peers)]
    [peer (get queue-map peer)]))

(defn pull-state
  "Try to pull an updated state from a queue and apply."
  [ch queue my-state]
  (jutil/timeout
   5000
   false

   (let [[meta data] (lbasic/get ch queue)
         state       (codec/decode data)]
     (when-not (nil? meta)  ; overwrite local view
       (reset! my-state state))

     true)))

(defn push-state
  "Try to push an updated state to a queue and apply."
  [ch queue my-state k v]
  (jutil/timeout
   5000
   false

   (let [state (assoc @my-state k v)]
     (lconfirm/select ch)
     (lbasic/publish ch "" queue  ; "" means the default exchange
                     (codec/encode state)
                     {:content-type "application/edn"
                      :mandatory true
                      :persistent true})

     (if (lconfirm/wait-for-confirms ch)
       (do
         (reset! my-state state)
         true)
       false))))

(defrecord Client [conn queue-> queue<- my-state]
  client/Client

  (open! [this test node]
    (assoc this
           :conn (rmq/connect {:host node
                               :username "jepsen"
                               :password "jepsen"})
           :queue-> (into {} (for [peer (:nodes test)]
                               (when (not= peer node)
                                 [peer (queue-name node peer)])))
           :queue<- (into {} (for [peer (:nodes test)]
                               (when (not= peer node)
                                 [peer (queue-name peer node)])))
           :my-state (atom {})))  ; my-state is a local view of the state

  (setup! [_ test]
    (doseq [node (:nodes test)]
      (doseq [peer (:nodes test)]
        (when (not= peer node)
          (with-ch [ch conn]
            (lqueue/declare ch (queue-name node peer)
                            {:durable     true
                             :auto-delete false
                             :exclusive   false}))))))

  (invoke! [_ _ op]
    (with-ch [ch conn]
      (let [[k v] (:value op)]
        (case (:f op)
          :read (let [[_ q] (rand-entry queue<-)]
                  (if (pull-state ch q my-state)
                    (assoc op
                           :type :ok,
                           :value (indp/tuple k (get @my-state k)))
                    (assoc op :type :fail, :error :timeout)))

          :write (let [[_ q] (rand-entry queue->)]
                   (if (push-state ch q my-state k v)
                     (assoc op :type :ok)
                     (assoc op :type :fail, :error :timeout)))

          :cas (let [[_ q] (rand-entry queue->)]
                 (if (pull-state ch q my-state)
                   (let [[old new] v]

                     (if (= (get @my-state k) old)
                       (if (push-state ch q my-state k new)
                         (assoc op :type :ok)
                         (assoc op :type :fail, :error :timeout))
                       (assoc op :type :fail)))

                   (assoc op :type :fail, :error :timeout)))))))

  (teardown! [_ _]
    (doseq [node (:nodes test)]
      (doseq [peer (:nodes test)]
        (when (not= peer node)
          (jutil/meh (with-ch [ch conn]
                       (lqueue/purge ch (queue-name node peer))))))))

  (close! [_ _]
    (jutil/meh (rmq/close conn))))

; Jepsen generator configuration
(defn generator-params
  "Generator parameters."
  [opts]
  (->> (indp/concurrent-generator
        (:con-per-key opts)
        (range)
        (fn [_]
          (->> (gen/mix [r w cas])
               (gen/stagger (/ (:op-gen-rate opts)))
               (gen/limit (* (+ (rand 0.1) 0.9)  ; randomize a bit
                             (:ops-per-key opts))))))

       (gen/nemesis
        (let [reps (long (/ (- (:time-limit opts) 10)
                            (* 2 (:fault-window opts))))]
          (gen/phases
           [(gen/sleep 3)]  ; extra 3 secs sleep at the beginning
           (repeat reps [(gen/sleep (:fault-window opts))
                         {:type :info, :f :start}
                         (gen/sleep (:fault-window opts))
                         {:type :info, :f :stop}])
                  ; and leaves at least 7 secs good time at the end
           )))

       (gen/time-limit (:time-limit opts))))

; Jepsen linearizability checker
(defn checker-params
  "Checker parameters."
  [opts]
  (checker/compose
   (merge {:performance  (checker/perf)
           :timeline-vis (timeline/html)}

          (checker-select opts nil))))

; Main entrance to the test
(defn test-fn
  "A simple test over an rabbitmq database."
  [opts]
  (merge tests/noop-test
         opts
         {:name      (str "rabbitmq"
                          " r=" (:op-gen-rate opts)
                          " o=" (:ops-per-key opts)
                          " t=" (:con-per-key opts)
                          " c=" (:concurrency opts)
                          " v=" (:value-range opts)
                          " l=" (:time-limit opts)
                          " f=" (:fault-window opts))
          :os        ubuntu/os
          :db        (rabbitmq-db "version.not.used")
          :client    (Client. nil {} {} nil)
          :pure-generators true
          :generator (generator-params opts)
          :nemesis   (nemesis/partition-random-halves)
          :checker   (checker-params opts)}))

(def cli-opts
  "Extra command line options."
  [["-r" "--op-gen-rate RATE"
    "Operations per second rate."
    :default  10
    :parse-fn read-string
    :validate [#(and (number? %) (pos? %)) "Must be a positive number"]]
   ["-o" "--ops-per-key NUMBER"
    "Number of operations per key."
    :default  100
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]
   ["-t" "--con-per-key NUMBER"
    "Threads concurrency per key."
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be a positive integer"]]
   ["-c" "--concurrency NUMBER"
    (str "Client worker threads, optionally followed by n (e.g. 3n) to"
         " multiply by #nodes.")
    :default  "50"
    :validate [(partial re-find #"^\d+n?$")
               "Must be an integer, optionally followed by n."]]
   ["-l" "--time-limit SECONDS"
    "Length of a test run in seconds."
    :default  40
    :parse-fn parse-long
    :validate [#(>= % 10) "Must be greater than or equal to 10"]]
   ["-f" "--fault-window SECONDS"
    "Length of half of a fault cycle in seconds."
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be positive"]]])

(def rmq-test-cmd
  "Input to the Jepsen single-test-cmd for rabbitmq."
  {:test-fn test-fn
   :opt-spec (full-test-opts cli-opts)})
