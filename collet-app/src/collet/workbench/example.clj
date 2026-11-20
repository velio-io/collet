(ns collet.workbench.example
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))


(def config {:failure-mode :once})


(def pipeline
  (edn/read-string (slurp (io/resource "collet/workbench/examples/orders.edn"))))
