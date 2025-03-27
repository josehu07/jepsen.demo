(ns helpers
  (:require [clojure.tools.logging :refer [info]]
            [jepsen
             [checker :as checker]
             [independent :as indp]
             [store :as store]]
            [knossos.model :as model])
  (:import [java.lang Runtime]))

; Utility functions
(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(defn str-to-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (parse-long s)))

; Common definitions for the key-value store (i.e., CAS register) model
(defn r
  [_ _]
  {:type :invoke
   :f :read
   :value nil})

(defn w
  [test _]
  {:type :invoke
   :f :write
   :value (rand-int (:value-range test))})

(defn cas
  [test _]
  {:type :invoke
   :f :cas
   :value [(rand-int (:value-range test)) (rand-int (:value-range test))]})

; Common test options regardless of system
(defn full-test-opts
  "Appends global, system-oblivious options to the test options."
  [test-opts]
  (concat
   test-opts
   [["-s" "--skip-checker"
     "Skip the linearizability checker if set."
     :default false]
    ["-v" "--value-range NUMBER"
     "Total number of distinct values."
     :default  10
     :parse-fn parse-long
     :validate [pos? "Must be a positive integer"]]]))

; Enablers for the hook to the Rust-implemented SOP checker
(defn external-exec
  "Executes an external command with given arguments. Returns a boolean
   that indicates check success status, or ':unknown' to indicate unknown
   result due to checker internal error."
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
    (case exit-code
      0 true
      1 false
      :unknown)))

(def rust-sop-checker
  "Hook to invoke the Rust-implemented SOP checker."
  (reify checker/Checker
    (check [_ test _ _]
      (case (external-exec
             "cargo" "run" "-r" "--"
             "--test-dir" (.getPath (store/path test)))
        true {:valid? true}
        false {:valid? false, :msg "Please see the Rust checker output."}
        :unknown {:valid? :unknown, :msg "Rust checker exits with error."}))))

; Original Jepsen-supplied checkers
(def orig-linear-checker
  "The original independent linearizability checker in Clojure."
  (reify checker/Checker
    (check [_ test history opts]
      ; implemented this way to allow easier printing for debugging
      ; when a violation comes from a pre-initialized value (outside of the ops
      ; history), 'render_analysis!' will fail to produce 'linear.svg'
      ;; (->> (indp/subhistories (indp/history-keys history) history)
      ;;      (run! (fn [[k h]]
      ;;              (pprint k)
      ;;              (pprint (competition/analysis (model/cas-register) h)))))
      (let [ck (indp/checker
                (checker/linearizable
                 {:model (model/cas-register)}))]
        (checker/check ck test history opts)))))

(defn checker-select
  "Selects the checker to use based on the options."
  [test-opts check-opts]
  (if test-opts

     ; during test
    (when-not (:skip-checker test-opts)
      {:indp-linear orig-linear-checker})

     ; during check-only
    (if (:rust-checker check-opts)
      {:sop-checker rust-sop-checker}

      {:indp-linear orig-linear-checker})))
