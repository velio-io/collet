(ns dev
  (:require
   [malli.dev :as mdev]
   [com.brunobonacci.mulog :as ml]
   [eftest.runner :refer [find-tests run-tests]]))


(mdev/start!)


(defn start-publishers []
  (ml/start-publisher!
   {:type       :multi
    :publishers [{:type :console :pretty? true}
                 {:type :elasticsearch :url "http://localhost:9200/"}
                 {:type :zipkin :url "http://localhost:9411"}]}))


(defn test []
  (run-tests
   (find-tests "test")
   {:multithread? :namespaces}))


(comment

 (test)

 (def stop-publisher
   (start-publishers))

 (stop-publisher)

 nil)