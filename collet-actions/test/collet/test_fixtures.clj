(ns collet.test-fixtures
  (:require
   [malli.instrument :as mi]))


(defn instrument! [ns]
  (fn [test]
    (mi/collect! {:ns ns})
    (mi/instrument!)
    (test)))