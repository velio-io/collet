(ns dev
  (:require
   [malli.dev :as mdev]
   [eftest.runner :refer [find-tests run-tests]]
   [portal.api :as p]))


(mdev/start!)


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

 nil)