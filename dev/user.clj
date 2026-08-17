(ns user
  (:require
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [collet.utils :as utils]
   [com.brunobonacci.mulog :as ml]
   [malli.dev :as mdev]
   [portal.api :as p]))


(defonce stop-publishers-fn*
  (atom nil))


(defn stop-publishers []
  (when-let [stop-publishers @stop-publishers-fn*]
    (stop-publishers)))


(defn start-publishers []
  (stop-publishers)
  (let [stop-fn (ml/start-publisher!
                 {:type       :multi
                  :publishers [{:type :console :pretty? true}]})]
    (reset! stop-publishers-fn* stop-fn)))


;;{:type :elasticsearch :url "http://localhost:9200/"}
;;{:type :zipkin :url "http://localhost:9411"}]}))


(defn reload []
  (reload/reload))


(defn sampled-tap
  [x]
  (-> (utils/samplify x)
      (p/submit)))


(def reload-dirs
  (->> (.listFiles (io/file "."))
       (filter #(.isDirectory %))
       (filter #(str/starts-with? (.getName %) "collet-"))
       (map #(io/file % "src"))
       (filter #(.isDirectory %))
       (map #(.getPath %))
       sort
       vec
       (#(conj % "test-fixtures/src"))))


(mdev/start!)


(reload/init
 {:dirs      reload-dirs
  :no-reload '#{user}})


(start-publishers)


(comment
  ;; choose one of the following options to start the portal
  (def p (p/open))
  (def p (p/open {:launcher :intellij}))
  (def p (p/open {:launcher :vs-code}))

  (add-tap #'sampled-tap)

  @p
  (prn @p)
  (p/clear)
  (remove-tap #'sampled-tap)

  (reload)

  (stop-publishers)

  nil)