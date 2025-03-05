(ns jepsen.demo
  (:require [jepsen.cli :as cli]
            [jepsen.tests :as tests]))

(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(defn dummy-test
  "A dummy test that does nothing."
  [opts]
  (-> tests/noop-test
      (assoc :pure-generators true)
      (merge opts)))

(defn -main
  "Main entrance to the test."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn dummy-test})
                   (cli/serve-cmd))
            args))
