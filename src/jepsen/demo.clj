(ns jepsen.demo
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [jepsen [cli :as cli]
             [control :as ctrl]
             [db :as db]
             [tests :as tests]]
            [jepsen.control.util :as util]
            [jepsen.os.ubuntu :as ubuntu]))

(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(def etcddir "/home/jepsen/etcd")
(def etcdbin (str etcddir "/bin"))
(def etcddata (str etcddir "/data"))
(def etcdwal (str etcddir "/wal"))
(def etcdexec "etcd")

(def logfile (str etcddir "/jepsen-etcd.log"))
(def pidfile (str etcddir "/jepsen-etcd.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" node ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 2380))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 2379))

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
      ;;            (util/install-archive! url "/opt/etcd"))))
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
       :--listen-peer-urls             (peer-url   node)
       :--listen-client-urls           (client-url node)
       :--advertise-client-urls        (client-url node)
       :--initial-cluster-state        :new
       :--initial-advertise-peer-urls  (peer-url node)
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

(defn etcd-test
  "A simple test over an etcd database."
  [opts]
  (-> tests/noop-test
      (merge opts {:name "etcd"
                   :os   ubuntu/os
                   :db   (etcd-db "version.not.used")
                   :pure-generators true})))

(defn -main
  "Main entrance to the test."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test})
                   (cli/serve-cmd))
            args))
