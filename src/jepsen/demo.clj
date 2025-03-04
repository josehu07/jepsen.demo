(ns jepsen.demo
  (:require [jepsen.cli :as cli]
            [jepsen.tests :as tests]))

(defn dummy-test
  "A dummy test that does nothing."
  [opts]
  (merge tests/noop-test
         {:pure-generators true
          :nodes-file "nodehosts"
          :username "josehu"
          :password ""}
         opts))

(defn -main
  "Main entrance to the test."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn dummy-test})
            args))
