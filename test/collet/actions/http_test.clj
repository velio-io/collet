(ns collet.actions.http-test
  (:require
   [clojure.test :refer :all]
   [collet.actions.http :as sut]))


;; API docs https://musicbrainz.org/doc/MusicBrainz_API

(deftest http-request-test
  (testing "simple http request"
    (let [actual (sut/request {:url "https://musicbrainz.org/ws/2/genre/all?limit=10"})]
      (is (map? actual))
      (is (every? #{:opts :body :headers :status} (keys actual)))))

  (testing "convert body to Clojure data structure"
    (let [actual (-> (sut/request {:url    "https://musicbrainz.org/ws/2/genre/all?limit=10"
                                   :accept :json
                                   :as     :json})
                     :body)]
      (is (map? actual))
      (is (number? (:genre-count actual)))
      (is (vector? (:genres actual)))))

  (testing "pass query params to the request"
    (let [actual (-> (sut/request {:url    "https://musicbrainz.org/ws/2/artist"
                                   :accept :json
                                   :as     :json
                                   :query-params
                                   {:limit 5
                                    :query "artist:fred%20AND%20type:group%20AND%20country:US"}})
                     :body)]
      (is (= (-> actual :artists count) 5)))))
