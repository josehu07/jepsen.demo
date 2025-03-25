(ns systems.zk
  (:require [helpers :refer [cas checker-select full-test-opts r w]]
            [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [avout.core :as avout]
            [jepsen
             [client :as client]
             [control :as ctrl]
             [db :as db]
             [generator :as gen]
             [independent :as indp]
             [tests :as tests]
             [nemesis :as nemesis]
             [checker :as checker]
             [util :refer [timeout]]]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.checker.timeline :as timeline])
  (:import [ch.qos.logback.classic Level Logger]))

; Paths used in tests
(def zkdir "/home/jepsen/zookeeper-v3.4")
(def zkbin (str zkdir "/bin"))
(def zkconf (str zkdir "/conf"))
(def zkdata (str zkdir "/data"))

(def zkexec (str zkbin "/zkServer.sh"))
(def conffile (str zkconf "/zoo.cfg"))
(def myidfile (str zkdata "/myid"))
(def logfile (str zkdata "/zookeeper.out"))

; Compose zookeeper configs
(defn zk-node-ids
  "The map from node names to node IDs."
  [test]
  (->> test
       :nodes
       (map-indexed (fn [i node] [node i]))
       (into {})))

(defn zk-node-id
  "The node ID for given node name."
  [test node]
  ((zk-node-ids test) node))

(defn zoo-cfg-servers
  "Constructs a zoo.cfg fragment for servers."
  [test]
  (->> (zk-node-ids test)
       (map (fn [[node id]]
              (str "server." id "=" (name node) ":2888:3888")))
       (str/join "\n")))

; Database setup
(defn zookeeper-db
  "ZooKeeper database of a particular version."
  [_]
  (reify db/DB
    (setup! [_ test node]
      (info "using zookeeper at" zkdir)

      (ctrl/exec :mkdir :-p zkdata)
      (ctrl/exec :echo (zk-node-id test node) :> myidfile)
      (ctrl/exec :echo
                 (str "tickTime=2000\n"
                      "initLimit=10\n"
                      "syncLimit=5\n"
                      "dataDir=" zkdata "\n"
                      "clientPort=2181\n"
                      "leaderServes=yes\n"
                      (zoo-cfg-servers test))
                 :> conffile)

      (ctrl/exec zkexec :stop  conffile)
      (ctrl/exec (str "ZOO_LOG_DIR=" zkdata)
                 (str "ZOO_LOG4J_PROP=" "INFO,CONSOLE")
                 zkexec :start conffile)

      (info "launched zookeeper server")
      (Thread/sleep 10000))

    (teardown! [_ _ _]
      (info "tearing down running zookeeper")

      (ctrl/exec zkexec :stop conffile)

      (ctrl/exec :rm :-rf zkdata))

    db/LogFiles
    (log-files [_ _ _] [logfile])))

; Client implementation
(defn client
  "A client for Zookeeper-backed distributed atoms."
  [conn zkatoms]
  (reify client/Client
    (open! [_ _ node]
      (client (avout/connect (name node))
              (atom {})))  ; zkatoms is a clojure atom containing a map

    (setup! [_ _]
      (.setLevel ^Logger  ; the DEBUG logging is so ananoying...
       (org.slf4j.LoggerFactory/getLogger (Logger/ROOT_LOGGER_NAME))
                 Level/INFO))

    (invoke! [_ test op]
      (timeout
       5000
       (assoc op
              :type (if (= :read (:f op)) :fail :info)
              :error :timeout)

       (let [[k v]     (:value op)
             local-key (and (:local-refs test) (zero? (mod k 3)))]
         ; if k not seen before, maybe create a new atom
         (when-not (contains? @zkatoms k)
           (swap! zkatoms
                  assoc k (if local-key
                            (ref nil)
                            (avout/zk-atom conn (str "/jepsen/" k)))))

         (let [zkatom (get @zkatoms k)]
           (case (:f op)
             :read  (let [val @zkatom]
                      (if val
                        (assoc op :type :ok, :value (indp/tuple k val))
                        (assoc op :type :fail)))

             :write (do (if local-key
                          (dosync (ref-set zkatom v))
                          (avout/reset!! zkatom v))
                        (assoc op :type :ok))

             :cas   (let [[old new] v
                          type      (atom :fail)]
                      (if local-key
                        (dosync (if (= @zkatom old)
                                  (do (reset! type :ok)
                                      (ref-set zkatom new))
                                  (reset! type :fail)))
                        (avout/swap!! zkatom
                                      (fn [current]
                                        (if (= current old)
                                          (do (reset! type :ok)
                                              new)
                                          (do (reset! type :fail)
                                              current)))))
                      (assoc op :type @type)))))))

    (teardown! [_ _]
      (.close conn))

    (close! [_ _]
      (.close conn))))

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
                         {:type :info, :f :stop}]))))
           ; and leaves at least 7 secs good time at the end


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
  "A simple test over an zookeeper database."
  [opts]
  (merge tests/noop-test
         opts
         {:name      (str "zk"
                          " r=" (:op-gen-rate opts)
                          " o=" (:ops-per-key opts)
                          " t=" (:con-per-key opts)
                          " c=" (:concurrency opts)
                          " v=" (:value-range opts)
                          " l=" (:time-limit opts)
                          " f=" (:fault-window opts))
          :os        ubuntu/os
          :db        (zookeeper-db "version.not.used")
          :client    (client nil nil)
          :pure-generators true
          :generator (generator-params opts)
          :nemesis   (nemesis/partition-random-halves)
          :checker   (checker-params opts)}))

(def cli-opts
  "Extra command line options."
  [["-e" "--local-refs"
    "Use local-ref to back some keys if set."
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

(def zk-test-cmd
  "Input to the Jepsen single-test-cmd for zookeeper."
  {:test-fn test-fn
   :opt-spec (full-test-opts cli-opts)})

