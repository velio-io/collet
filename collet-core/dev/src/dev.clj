(ns dev
  (:require
   [malli.dev :as mdev]
   [com.brunobonacci.mulog :as ml]
   [eftest.runner :refer [find-tests run-tests]]
   [portal.api :as p]))


(mdev/start!)


(defn start-publishers []
  (ml/start-publisher!
   {:type       :multi
    :publishers [{:type :console :pretty? true}]}))
                 ;;{:type :elasticsearch :url "http://localhost:9200/"}
                 ;;{:type :zipkin :url "http://localhost:9411"}]}))


(defn test []
  (run-tests
   (find-tests "test")
   {:multithread? :namespaces}))


(comment
 ;; choose one of the following options to start the portal
 (def p (p/open))
 (def p (p/open {:launcher :intellij}))
 (def p (p/open {:launcher :vs-code}))

 (add-tap #'p/submit)

 @p
 (prn @p)
 (p/clear)
 (remove-tap #'p/submit)

 (test)

 (def stop-publisher
   (start-publishers))

 (stop-publisher)

 nil)