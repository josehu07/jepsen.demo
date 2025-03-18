(ns jepsen.demo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [verschlimmbesserung.core :as vsrg]
            [jepsen
             [cli :as cli]
             [client :as client]
             [control :as ctrl]
             [db :as db]
             [generator :as gen]
             [tests :as tests]
             [checker :as checker]]
            [jepsen.control.util :as util]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.checker.timeline :as timeline]
            [slingshot.slingshot :refer [try+]]
            [knossos.model :as model])
  (:import [knossos.model Model]))

(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(defn str2num
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (parse-long s)))

(def etcddir "/home/jepsen/etcd-v3.1")
(def etcdbin etcddir)
(def etcddata (str etcddir "/data"))
(def etcdwal (str etcddir "/wal"))
(def etcdexec "etcd")

(def logfile (str etcddir "/jepsen-etcd.log"))
(def pidfile (str etcddir "/jepsen-etcd.pid"))

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

(defrecord Client [conn]
  client/Client

  (open! [this test node]
    (assoc this :conn (vsrg/connect (client-url node) {:timeout 5000})))

  (setup! [this test])

  (invoke! [_ test op]
    (case (:f op)
      :read (assoc op
                   :value (str2num (vsrg/get conn "foo"))
                   :type :ok)
      :write (do (vsrg/reset! conn "foo" (:value op))
                 (assoc op
                        :type :ok))
      :cas (try+
            (let [[old new] (:value op)]
              (assoc op
                     :type (if (vsrg/cas! conn "foo" old new) :ok :fail)))
            (catch [:errorCode 100] _
              (assoc op
                     :type :fail
                     :error :not-found)))))

  (teardown! [this test])

  (close! [_ test]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 8)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 8) (rand-int 8)]})

(def generator-params (->> (gen/mix [r w cas])
                           (gen/stagger 1)
                           (gen/nemesis nil)
                           (gen/time-limit 15)))

(def checker-params (checker/compose
                     {:linearizability (checker/linearizable
                                        {:model     (model/cas-register)
                                         :algorithm :linear})
                      :timeline-render (timeline/html)}))

(defn etcd-test
  "A simple test over an etcd database."
  [opts]
  (-> tests/noop-test
      (merge opts {:name      "etcd"
                   :os        ubuntu/os
                   :db        (etcd-db "version.not.used")
                   :client    (Client. nil)
                   :pure-generators true
                   :generator generator-params
                   :checker   checker-params})))

(defn -main
  "Main entrance to the test."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))
