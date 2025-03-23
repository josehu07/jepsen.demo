(ns helpers
  (:require [jepsen
             [checker :as checker]
             [independent :as indp]]
            [knossos.model :as model])
  (:import [knossos.model Model]))

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

(defn checker-select
  "Selects the checker to use based on the options."
  [test-opts check-opts]
  (if test-opts

     ; during test
    (when-not (:skip-checker test-opts)
      {:indp-linear (indp/checker
                     (checker/linearizable
                      {:model (model/cas-register)}))})

     ; during check-only
    (if (:rust-checker check-opts)
      nil  ; TODO: add rust checker invocation

      {:indp-linear (indp/checker
                     (checker/linearizable
                      {:model (model/cas-register)}))})))
