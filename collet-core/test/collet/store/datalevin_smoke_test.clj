(ns collet.store.datalevin-smoke-test
  (:require
   [clojure.test :refer [deftest is]]
   [datalevin.core :as d])
  (:import
   [java.nio.file FileVisitOption Files Path]
   [java.nio.file LinkOption]
   [java.nio.file.attribute FileAttribute]))


(def ^:private smoke-schema
  {:smoke/id    {:db/valueType :db.type/keyword
                 :db/unique    :db.unique/identity}
   :smoke/value {:db/valueType :db.type/long}})


(defn- temporary-store-path
  ^Path
  []
  (Files/createTempDirectory
   "collet-datalevin-smoke-"
   (make-array FileAttribute 0)))


(defn- delete-store! [^Path path]
  (with-open [paths (Files/walk path (make-array FileVisitOption 0))]
    (doseq [entry (sort-by str #(compare %2 %1)
                          (iterator-seq (.iterator paths)))]
      (Files/deleteIfExists ^Path entry))))


(deftest datalevin-1-0-loads-on-supported-jvm
  (is (fn? d/create-conn))
  (is (fn? d/close)))


(deftest embedded-store-persists-one-datom-across-close-reopen
  (let [store-path (temporary-store-path)
        path       (str store-path)]
    (try
      (let [conn (d/create-conn path smoke-schema)]
        (try
          (d/transact! conn [{:smoke/id :one
                              :smoke/value 42}])
          (finally
            (d/close conn))))
      (let [reopened (d/get-conn path smoke-schema)]
        (try
          (is (= 42
                 (d/q '[:find ?value .
                        :in $ ?id
                        :where
                        [?entity :smoke/id ?id]
                        [?entity :smoke/value ?value]]
                      (d/db reopened)
                      :one)))
          (finally
            (d/close reopened))))
        (finally
          (delete-store! store-path)))
    (is (not (Files/exists store-path
                           (make-array LinkOption 0))))))
