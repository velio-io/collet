(ns dev
  (:require
   [malli.dev :as mdev]
   [eftest.runner :refer [find-tests run-tests]]))


(mdev/start!)


(comment

 (run-tests
  (find-tests "test")
  {:multithread? :namespaces})

 nil)