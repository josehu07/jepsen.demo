(ns workflow
  (:require [helpers :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [clojure.tools.cli :as cljcli]
            [dom-top.core :refer [assert+]]
            [jepsen
             [core :as jepsen]
             [cli :as cli]
             [store :as store]
             [checker :as checker]]
            [systems
             [etcd :as etcd]]))

(defn unknown-system-help
  "Prints help message when system name unknown"
  [system]
  (println "Unknown system name:" system)
  (println "Usage:")
  (println "  just test <system> [args ...]")
  (println "  just check <index> [args ...]")
  (println)
  (throw (IllegalArgumentException. "Unknown system name")))

(defn check-run!
  "Overwrites the 'analyze' subcommand of single-test-cmd to allow ignoring
   the '-s' option and to choose different checkers."
  [args]
  (let [{:keys [options arguments summary errors]}
        (cljcli/parse-opts
         args
         [["-h" "--help"
           "Display help message for custom check command."]
          ["-w" "--which-test INDEX_OR_PATH"
           "Index (e.g. -1 for the most recent) or path to a Jepsen test file."
           :default  -1
           :parse-fn (fn [s] (if (re-find #"^-?\d+$" s) (parse-long s) s))]
          ["-r" "--rust-checker"
           "Use our Rust SOP checker implementation if set."
           :default false]])

        check-fn
        (fn [opts]
          (let [stored-test (store/test (:which-test opts))
                _ (info "Analyzing" (.getPath (store/path stored-test)))
                argv (:argv stored-test)
                _ (info "CLI args were" argv)
                _ (assert+ (#{"test" "test-all"} (first argv))
                           IllegalArgumentException
                           (str "Cannot reconstruct from " (str/join " " argv)))
                _ (assert+ (map? stored-test)
                           IllegalStateException
                           "Unable to load stored test")
                test (assoc (dissoc stored-test :results)
                            :checker (checker/compose (checker-select nil opts)))]

            (binding [*print-length* 32]
              (info "Combined test:\n"
                    (-> test
                        (update :history (partial take 5))
                        (update :history vector)
                        (update :history conj '...)
                        pprint
                        with-out-str)))

            (store/with-handle [test test]
              (jepsen/log-results (jepsen/analyze! test)))))]

    (when (:help options)
      (println "Usage: just check <index> [args ...]")
      (println)
      (println summary)
      (System/exit 0))

    (when (seq errors)
      (dorun (map println errors))
      (System/exit 254))

    (check-fn options)
    (System/exit 0)))

(defn -main
  "Main entrance to the workflow.
   
   Examples:
       just test etcd [args ...]
       just check -1 [args ...]
       just serve
   "
  [& args]
  (let [system-name (first args)

        jepsen-args (case system-name
                      "serve" args
                      "check" args
                      (rest args))

        test-cmd-def (case system-name
                       "serve" nil
                       "check" nil
                       "etcd" etcd/etcd-test-cmd
                       (unknown-system-help system-name))]

    (case system-name
      "serve" (cli/run! (cli/serve-cmd) jepsen-args)
      "check" (check-run! jepsen-args)  ; use hijacked hook
      (cli/run! (cli/single-test-cmd test-cmd-def) jepsen-args))))
