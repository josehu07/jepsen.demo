(ns helpers
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer :all]
            [jepsen
             [checker :as checker]
             [independent :as indp]
             [store :as store]]
            [knossos.model :as model])
  (:import [knossos.model Model]
           [java.lang Runtime]))

(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(defn str-to-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (parse-long s)))

(defn full-test-opts
  "Appends global, system-oblivious options to the test options."
  [test-opts]
  (concat
   test-opts
   [["-s" "--skip-checker"
     "Skip the linearizability checker if set."
     :default false]]))

(defn external-exec
  "Executes an external command with given arguments. Returns a boolean
   that indicates its exit code success status."
  [& args]
  (let [proc (.exec (Runtime/getRuntime) (into-array String args))
        stdout (slurp (.getInputStream proc))
        stderr (slurp (.getErrorStream proc))
        exit-code (.waitFor proc)]
    (info "Rust checker exit code:" exit-code)
    (info "Rust checker stdout:")
    (println stdout)
    (info "Rust checker stderr:")
    (println stderr)
    (= exit-code 0)))

(def rust-sop-checker
  "Hook to invoke the Rust-implemented SOP checker."
  (reify checker/Checker
    (check [this test history _]
      (if (external-exec
           "cargo" "run" "-r" "--"
           "--test-dir" (.getPath (store/path test)))
        {:valid? true}
        {:valid? false, :msg "Please see the Rust checker output."}))))

(def original-checker
  "The original independent linearizability checker in Clojure."
  (indp/checker
   (checker/linearizable
    {:model (model/cas-register)})))

(defn checker-select
  "Selects the checker to use based on the options."
  [test-opts check-opts]
  (if test-opts

     ; during test
    (when-not (:skip-checker test-opts)
      {:indp-linear original-checker})

     ; during check-only
    (if (:rust-checker check-opts)
      {:sop-checker rust-sop-checker}

      {:indp-linear original-checker})))
