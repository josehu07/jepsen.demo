(ns helpers)

(defn spy> [val msg] (prn msg val) val)
(defn spy>> [msg val] (spy> val msg))

(defn str-to-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (parse-long s)))
