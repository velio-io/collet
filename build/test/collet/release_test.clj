(ns collet.release-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [collet.release :as release]
   [collet.workspace :as workspace])
  (:import
   (java.util.zip ZipEntry ZipOutputStream)))

(def revision "abc123")

(def packages
  {'example/pkg-a {:fqn 'example/pkg-a
                   :current-version "1.2.3"
                   :version "1.2.4"
                   :reason :patch
                   :tag "example/pkg-a@1.2.4"
                   :publish? true
                   :release? true
                   :depends-on #{}
                   :dependents #{'example/pkg-b}}
   'example/pkg-b {:fqn 'example/pkg-b
                   :current-version "1.2.3"
                   :version "1.2.4"
                   :reason :dependency
                   :tag "example/pkg-b@1.2.4"
                   :publish? true
                   :release? true
                   :depends-on #{'example/pkg-a}
                   :dependents #{'example/pkg-cli}}
   'example/pkg-cli {:fqn 'example/pkg-cli
                     :current-version "1.2.3"
                     :version "1.2.4"
                     :reason :dependency
                     :tag "example/pkg-cli@1.2.4"
                     :publish? false
                     :release? true
                     :depends-on #{'example/pkg-b}
                     :dependents #{}}})

(def plan
  {:root "/tmp/example"
   :packages packages
   :selected ['example/pkg-a 'example/pkg-b 'example/pkg-cli]})

(defn- exception [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- recording-ops
  ([events] (recording-ops events {}))
  ([events {:keys [local-tags statuses failures]
            :or {local-tags {}
                 statuses {}
                 failures #{}}}]
   {:fetch-tags! #(swap! events conj :fetch)
    :preflight! (fn []
                  (swap! events conj :preflight)
                  {:revision revision})
    :require-credentials! #(swap! events conj :credentials)
    :test! #(swap! events conj :test)
    :verify! #(swap! events conj :verify)
    :build! (fn [release-plan]
              (swap! events conj [:build (mapv :version release-plan)])
              (into {}
                    (map (fn [{:keys [fqn]}]
                           [fqn {:jar-file (str (name fqn) ".jar")
                                 :pom-file (str (name fqn) ".pom")}]))
                    release-plan))
    :tag-target! #(get local-tags %)
    :create-tag! (fn [tag target]
                   (swap! events conj [:tag tag target])
                   (when (contains? failures :tag)
                     (throw (ex-info "tag failed" {:tag tag}))))
    :publication-status! (fn [{:keys [fqn]}]
                           (get statuses fqn :absent))
    :deploy! (fn [{:keys [fqn]} _]
               (swap! events conj [:deploy fqn])
               (when (contains? failures fqn)
                 (throw (ex-info "deploy failed" {:package fqn}))))
    :push-tags! (fn [tags]
                  (swap! events conj [:push tags])
                  (when (contains? failures :push)
                    (throw (ex-info "push failed" {}))))}))

(defn- write-jar! [path entries]
  (with-open [output (ZipOutputStream. (io/output-stream (str path)))]
    (doseq [[entry content] entries]
      (.putNextEntry output (ZipEntry. entry))
      (.write output (.getBytes content "UTF-8"))
      (.closeEntry output))))

(defn- pom [version tag]
  (str "<project><modelVersion>4.0.0</modelVersion>"
       "<groupId>example</groupId><artifactId>pkg-a</artifactId>"
       "<version>" version "</version><scm><tag>" tag
       "</tag></scm></project>"))

(deftest plan-display-shows-package-current-next-reason-tag-and-publication
  (let [output (release/format-plan plan)]
    (doseq [text ["PACKAGE" "CURRENT" "NEXT" "REASON" "TAG" "PUBLICATION"
                  "example/pkg-a" "1.2.3" "1.2.4" "patch"
                  "example/pkg-a@1.2.4" "Maven"
                  "example/pkg-cli" "tag only"]]
      (is (str/includes? output text)))))

(deftest fresh-tag-collision-stops-before-quality-gates
  (let [events (atom [])
        error (exception
               #(release/execute-release!
                 plan
                 (recording-ops
                  events
                  {:local-tags {"example/pkg-a@1.2.4" "other"}})))]
    (is (= "Package release tag points to a different revision"
           (ex-message error)))
    (is (= [:fetch :preflight] @events))))

(deftest partial-or-mismatched-publication-stops-before-any-deploy
  (doseq [status [:partial :mismatch]]
    (testing (name status)
      (let [events (atom [])
            error (exception
                   #(release/execute-release!
                     plan
                     (recording-ops
                      events
                      {:statuses {'example/pkg-b status}})))]
        (is (= "Remote Maven publication is partial or mismatched"
               (ex-message error)))
        (is (empty? (filter #(and (vector? %) (= :deploy (first %)))
                            @events)))))))

(deftest matching-coordinate-is-a-collision-without-a-complete-local-tag-resume
  (doseq [local-tags
          [{}
           {"example/pkg-a@1.2.4" revision}]]
    (let [events (atom [])
          error (exception
                 #(release/execute-release!
                   plan
                   (recording-ops
                    events
                    {:local-tags local-tags
                     :statuses {'example/pkg-a :matching}})))]
      (is (= "Remote Maven coordinate already exists for a fresh release"
             (ex-message error)))
      (is (empty? (filter #(and (vector? %) (= :deploy (first %)))
                          @events))))))

(deftest resume-rebuilds-tagged-versions-and-skips-matching-publications
  (let [events (atom [])
        tags (into {} (map (juxt :tag (constantly revision))) (vals packages))
        result (release/execute-release!
                plan
                (recording-ops
                 events
                 {:local-tags tags
                  :statuses {'example/pkg-a :matching
                             'example/pkg-b :matching}}))]
    (is (= [:build ["1.2.4" "1.2.4" "1.2.4"]]
           (some #(when (and (vector? %) (= :build (first %))) %) @events)))
    (is (empty? (filter #(and (vector? %) (= :tag (first %))) @events)))
    (is (empty? (filter #(and (vector? %) (= :deploy (first %))) @events)))
    (is (= ['example/pkg-a 'example/pkg-b] (:skipped result)))))

(deftest tag-push-failure-happens-after-all-deploys-and-remains-resumable
  (let [events (atom [])
        error (exception
               #(release/execute-release!
                 plan
                 (recording-ops events {:failures #{:push}})))]
    (is (= "push failed" (ex-message error)))
    (is (= [[:deploy 'example/pkg-a]
            [:deploy 'example/pkg-b]]
           (filterv #(and (vector? %) (= :deploy (first %))) @events)))
    (is (= :push (-> @events last first)))))

(deftest successful-release-tags-before-deploys-and-atomically-pushes-all-tags
  (let [events (atom [])
        result (release/execute-release! plan (recording-ops events))
        tag-indexes (keep-indexed #(when (and (vector? %2)
                                              (= :tag (first %2))) %1)
                                  @events)
        deploy-indexes (keep-indexed #(when (and (vector? %2)
                                                 (= :deploy (first %2))) %1)
                                     @events)]
    (is (< (apply max tag-indexes) (apply min deploy-indexes)))
    (is (= ["example/pkg-a@1.2.4"
            "example/pkg-b@1.2.4"
            "example/pkg-cli@1.2.4"]
           (-> @events last second)))
    (is (= ['example/pkg-a 'example/pkg-b] (:deployed result)))
    (is (= ['example/pkg-cli] (:tag-only result)))))

(deftest tag-only-selection-does-not-require-publication-credentials
  (let [events (atom [])
        cli-plan (assoc plan :selected ['example/pkg-cli])]
    (release/execute-release! cli-plan (recording-ops events))
    (is (not-any? #{:credentials} @events))))

(deftest quality-gate-environment-strips-clojars-secrets
  (is (= {"PATH" "/bin" "OTHER" "kept"}
         (release/nondeployment-env
          {"PATH" "/bin"
           "OTHER" "kept"
           "CLOJARS_USERNAME" "secret"
           "CLOJARS_PASSWORD" "secret"}))))

(deftest publication-verification-checks-direct-pom-scm-and-embedded-jar-identity
  (let [root (fs/create-temp-dir {:prefix "collet-publication-test-"})
        jar (fs/path root "pkg-a-1.2.4.jar")
        good-pom (pom "1.2.4" "example/pkg-a@1.2.4")
        package (get packages 'example/pkg-a)]
    (try
      (write-jar!
       jar
       {"META-INF/collet/build.edn"
        (pr-str {:version "1.2.4" :revision revision})
        "META-INF/maven/example/pkg-a/pom.xml" good-pom
        "META-INF/maven/example/pkg-a/pom.properties"
        "groupId=example\nartifactId=pkg-a\nversion=1.2.4\n"})
      (is (= :matching
             (release/verify-publication package revision good-pom (str jar))))
      (is (= :mismatch
             (release/verify-publication
              package revision
              (pom "1.2.4" "example/pkg-a@9.9.9")
              (str jar))))
      (is (= :mismatch
             (release/verify-publication package "other" good-pom (str jar))))
      (is (= :mismatch
             (release/verify-publication
              package revision
              (str "<project><modelVersion>4.0.0</modelVersion>"
                   "<parent><groupId>example</groupId>"
                   "<artifactId>pkg-a</artifactId><version>1.2.4</version>"
                   "</parent><scm><tag>example/pkg-a@1.2.4</tag></scm>"
                   "</project>")
              (str jar))))
      (finally
        (fs/delete-tree root)))))

(deftest preflight-requires-clean-synchronized-main
  (is (= {:revision revision}
         (release/validate-preflight!
          {:branch "main" :status "" :head revision :remote-head revision})))
  (doseq [[state message]
          [[{:branch "topic" :status "" :head revision :remote-head revision}
            "Releases require the main branch"]
           [{:branch "main" :status " M file" :head revision :remote-head revision}
            "Releases require a clean worktree"]
           [{:branch "main" :status "" :head revision :remote-head "other"}
            "Local main must equal origin/main"]]]
    (is (= message
           (ex-message (exception #(release/validate-preflight! state)))))))

(deftest command-plan-falls-back-to-package-tags-at-head-for-resume
  (let [calls (atom [])
        fresh (assoc plan :selected [])
        pending (assoc plan :selected ['example/pkg-a])]
    (with-redefs [workspace/resolve-release-plan!
                  (fn [root opts]
                    (swap! calls conj [:fresh root opts])
                    fresh)
                  workspace/resolve-pending-release-plan!
                  (fn [root opts]
                    (swap! calls conj [:pending root opts])
                    pending)]
      (is (= pending
             (release/resolve-command-plan! "/repo" {:module :pkg-a})))
      (is (= [[:fresh "/repo" {:module :pkg-a}]
              [:pending "/repo" {:module :pkg-a}]]
             @calls)))))
