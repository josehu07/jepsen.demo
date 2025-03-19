(ns workflow
  (:require [jepsen.cli :as cli]
            [systems
             [etcd :as etcd]]
            [helpers :refer :all]))

(defn unknown-system-help
  "Prints help message when system name unknown"
  [system]
  (println "Unknown system name:" system)
  (println "Usage: just test <system> [args ...]")
  (println)
  (throw (IllegalArgumentException. "Unknown system name")))

(defn -main
  "Main entrance to the workflow.
   
   Examples:
       just test etcd [args ...]
       just serve
   "
  [& args]
  (let [jepsen-args (if (= (first args) "serve")
                      args
                      (rest args))

        test-cmd-input (case (first args)
                         "serve" nil
                         "etcd" etcd/etcd-test-cmd
                         (unknown-system-help (first args)))]

    (cli/run! (merge (cli/single-test-cmd test-cmd-input)
                     (cli/serve-cmd))
              jepsen-args)))
