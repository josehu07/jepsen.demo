(ns systems.etcd
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [verschlimmbesserung.core :as vsrg]
            [jepsen
             [client :as client]
             [control :as ctrl]
             [db :as db]
             [generator :as gen]
             [independent :as indp]
             [tests :as tests]
             [checker :as checker]
             [nemesis :as nemesis]]
            [jepsen.control.util :as util]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.checker.timeline :as timeline]
            [slingshot.slingshot :refer [try+]]
            [knossos.model :as model])
  (:import [knossos.model Model]))

; Helper functions
(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(defn str-to-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (parse-long s)))

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
  [version]
  (reify db/DB
    (setup! [_ test node]
      ;; (ctrl/su (let [url (format "https://github.com/etcd-io/etcd/releases/download/%s/etcd-%s-linux-amd64.tar.gz" version version)]
      ;;            (util/install-archive! url "/opt/etcd")))
      (info node "using etcd at" etcddir)

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

      (Thread/sleep 10000))

    (teardown! [_ test node]
      (info node "tearing down running etcd")

      (util/stop-daemon! etcdexec pidfile)

      (ctrl/exec :rm :-rf etcddata)
      (ctrl/exec :rm :-rf etcdwal)
      (ctrl/exec :rm :-rf logfile)
      (ctrl/exec :rm :-rf pidfile))

    db/LogFiles
    (log-files [_ test node] [logfile])))

; Client implementation
(defrecord Client [conn]
  client/Client

  (open! [this test node]
    (assoc this :conn (vsrg/connect (client-url node) {:timeout 5000})))

  (setup! [this test])

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
         (assoc op :type :fail, :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 10)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 10) (rand-int 10)]})

; Jepsen generator configuration
(defn generator-params
  "Generator parameters."
  [opts]
  (->> (indp/concurrent-generator
        (:con-per-key opts)
        (range)
        (fn [k]
          (->> (gen/mix [r w cas])
               (gen/stagger (/ (:op-gen-rate opts)))
               (gen/limit (:ops-per-key opts)))))

       (gen/nemesis
        (cycle [(gen/sleep (:fault-window opts))
                {:type :info, :f :start}
                (gen/sleep (:fault-window opts))
                {:type :info, :f :stop}]))

       (gen/time-limit (:time-limit opts))))

; Jepsen linearizability checker
(defn checker-params
  "Checker parameters."
  [opts]
  (checker/compose
   {:perf (checker/perf)

    :independent (indp/checker
                  (checker/compose
                   {:linearizable (checker/linearizable
                                   {:model     (model/cas-register)
                                    :algorithm :linear})

                    :timeline-render (timeline/html)}))}))

; Main entrance to the test
(defn test-fn
  "A simple test over an etcd database."
  [opts]
  (merge tests/noop-test
         opts
         {:name      (str "etcd"
                          " q=" (:quorum-read opts)
                          " r=" (:op-gen-rate opts)
                          " k=" (:ops-per-key opts)
                          " t=" (:con-per-key opts)
                          " c=" (:concurrency opts)
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
   ["-k" "--ops-per-key NUMBER"
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
    :validate [pos? "Must be positive"]]
   ["-f" "--fault-window SECONDS"
    "Length of half of a fault cycle in seconds."
    :default  5
    :parse-fn parse-long
    :validate [pos? "Must be positive"]]])

(def etcd-test-cmd
  "Input to the Jepsen single-test-cmd."
  {:test-fn test-fn
   :opt-spec cli-opts})
