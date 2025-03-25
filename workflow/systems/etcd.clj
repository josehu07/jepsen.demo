(ns systems.etcd
  (:require [helpers :refer [cas checker-select full-test-opts r str-to-long w]]
            [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [verschlimmbesserung.core :as vsrg]
            [jepsen
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
            [jepsen.checker.timeline :as timeline]
            [slingshot.slingshot :refer [try+]]))

; Paths used in tests
(def etcddir "/home/jepsen/etcd-v3.1")
(def etcdbin etcddir)
(def etcddata (str etcddir "/data"))
(def etcdwal (str etcddir "/wal"))
(def etcdexec "etcd")

(def logfile (str etcddir "/jepsen-etcd.log"))
(def pidfile (str etcddir "/jepsen-etcd.pid"))

; Compose etcd URLs
(defn compose-url
  "An HTTP url composed of an address and a port."
  [addr port]
  (str "http://" addr ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (compose-url node 2380))

(defn listen-peer-url
  "The HTTP url to listen on for peers."
  []
  (compose-url "0.0.0.0" 2380))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (compose-url node 2379))

(defn listen-client-url
  "The HTTP url to listen on for clients."
  []
  (compose-url "0.0.0.0" 2379))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str node "=" (peer-url node))))
       (str/join ",")))

; Database setup
(defn etcd-db
  "Etcd database of a particular version."
  [_]
  (reify db/DB
    (setup! [_ test node]
      (info "using etcd at" etcddir)

      (util/start-daemon!
       {:logfile logfile
        :pidfile pidfile
        :chdir etcddir}
       (str etcdbin "/" etcdexec)
       :--log-output                   :stderr
       :--name                         (name node)
       :--data-dir                     etcddata
       :--wal-dir                      etcdwal
       :--listen-peer-urls             (listen-peer-url)
       :--initial-advertise-peer-urls  (peer-url node)
       :--listen-client-urls           (listen-client-url)
       :--advertise-client-urls        (client-url node)
       :--initial-cluster-state        :new
       :--initial-cluster              (initial-cluster test))

      (info "launched etcd server")
      (Thread/sleep 10000))

    (teardown! [_ _ _]
      (info "tearing down running etcd")

      (util/stop-daemon! etcdexec pidfile)

      (ctrl/exec :rm :-rf etcddata)
      (ctrl/exec :rm :-rf etcdwal)
      (ctrl/exec :rm :-rf logfile)
      (ctrl/exec :rm :-rf pidfile))

    db/LogFiles
    (log-files [_ _ _] [logfile])))

; Client implementation
(defrecord Client [conn]
  client/Client

  (open! [this _ node]
    (assoc this :conn (vsrg/connect (client-url node) {:timeout 5000})))

  (setup! [_ _])

  (invoke! [_ test op]
    (let [[k v] (:value op)]
      (try+
       (case (:f op)
         :read (let [value (-> conn
                               (vsrg/get k {:quorum? (:quorum-read test)})
                               str-to-long)]
                 (assoc op :type :ok, :value (indp/tuple k value)))

         :write (do (vsrg/reset! conn k v)
                    (assoc op :type :ok))

         :cas (let [[old new] v]
                (assoc op :type (if (vsrg/cas! conn k old new)
                                  :ok
                                  :fail))))

       (catch java.net.SocketTimeoutException _
         (assoc op
                :type  (if (= :read (:f op)) :fail :info)
                :error :timeout))

       (catch [:errorCode 100] _
         (assoc op :type :fail)))))

  (teardown! [_ _])

  (close! [_ _]))

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
  "A simple test over an etcd database."
  [opts]
  (merge tests/noop-test
         opts
         {:name      (str "etcd"
                          " q=" (:quorum-read opts)
                          " r=" (:op-gen-rate opts)
                          " o=" (:ops-per-key opts)
                          " t=" (:con-per-key opts)
                          " c=" (:concurrency opts)
                          " v=" (:value-range opts)
                          " l=" (:time-limit opts)
                          " f=" (:fault-window opts))
          :os        ubuntu/os
          :db        (etcd-db "version.not.used")
          :client    (Client. nil)
          :pure-generators true
          :generator (generator-params opts)
          :nemesis   (nemesis/partition-random-halves)
          :checker   (checker-params opts)}))

(def cli-opts
  "Extra command line options."
  [["-q" "--quorum-read"
    "Use quorum reads if set."
    :default false]
   ["-r" "--op-gen-rate RATE"
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

(def etcd-test-cmd
  "Input to the Jepsen single-test-cmd for etcd."
  {:test-fn test-fn
   :opt-spec (full-test-opts cli-opts)})
