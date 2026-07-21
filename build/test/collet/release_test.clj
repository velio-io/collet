(ns collet.release-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
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

(defn- git! [root & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-C" (str root) args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:args args :error err})))
    (str/trim out)))

(defn- recording-ops
  ([events] (recording-ops events {}))
  ([events {:keys [local-tags statuses failures preflight-revisions]
            :or {local-tags {}
                 statuses {}
                 failures #{}
                 preflight-revisions [revision revision]}}]
   (let [preflights (atom preflight-revisions)]
   {:fetch-tags! #(swap! events conj :fetch)
    :preflight! (fn []
                  (swap! events conj :preflight)
                  (let [current (or (first @preflights) revision)]
                    (swap! preflights #(if (next %) (vec (next %)) %))
                    {:revision current}))
    :require-credentials! #(swap! events conj :credentials)
    :test! #(swap! events conj :test)
    :verify! #(swap! events conj :verify)
    :build! (fn [release-plan source-revision]
              (swap! events conj [:build (mapv :version release-plan)])
              (is (= revision source-revision))
              (into {}
                    (map (fn [{:keys [fqn]}]
                           [fqn {:jar-file (str (name fqn) ".jar")
                                 :pom-file (str (name fqn) ".pom")}]))
                    release-plan))
    :tag-target! #(get local-tags %)
    :create-tags! (fn [tags target]
                    (swap! events conj [:tags tags target])
                    (when (contains? failures :tag)
                      (throw (ex-info "tag failed" {:tags tags}))))
    :publication-status! (fn [{:keys [fqn]} source-revision]
                           (is (= revision source-revision))
                           (swap! events conj [:status fqn])
                           (get statuses fqn :absent))
    :deploy! (fn [{:keys [fqn]} _]
               (swap! events conj [:deploy fqn])
               (when (contains? failures fqn)
                 (throw (ex-info "deploy failed" {:package fqn}))))
    :push-tags! (fn [tags]
                  (swap! events conj [:push tags])
                  (when (contains? failures :push)
                    (throw (ex-info "push failed" {}))))})))

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

(defn- dependency [group artifact version]
  (str "<dependency><groupId>" group "</groupId>"
       "<artifactId>" artifact "</artifactId>"
       (when version (str "<version>" version "</version>"))
       "</dependency>"))

(defn- app-pom [dependencies]
  (str "<project><modelVersion>4.0.0</modelVersion>"
       "<groupId>io.velio</groupId><artifactId>collet-app</artifactId>"
       "<version>2.4.0</version>"
       "<scm><tag>io.velio/collet-app@2.4.0</tag></scm>"
       dependencies
       "</project>"))

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
  (let [events (atom [])
        error (exception
               #(release/execute-release!
                 plan
                 (recording-ops
                  events
                  {:statuses {'example/pkg-a :matching}})))]
    (is (= "Remote Maven coordinate already exists for a fresh release"
           (ex-message error)))
    (is (empty? (filter #(and (vector? %) (= :deploy (first %)))
                        @events)))))

(deftest partial-local-tag-set-is-rejected-before-quality-gates
  (let [events (atom [])
        error (exception
               #(release/execute-release!
                 plan
                 (recording-ops
                  events
                  {:local-tags {"example/pkg-a@1.2.4" revision}})))]
    (is (= "Local package release tags are incomplete" (ex-message error)))
    (is (= [:fetch :preflight] @events))))

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
    (is (empty? (filter #(and (vector? %) (= :tags (first %))) @events)))
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
        tag-index (first (keep-indexed #(when (and (vector? %2)
                                                   (= :tags (first %2))) %1)
                                       @events))
        deploy-indexes (keep-indexed #(when (and (vector? %2)
                                                 (= :deploy (first %2))) %1)
                                     @events)]
    (is (< tag-index (apply min deploy-indexes)))
    (is (= [[:tags ["example/pkg-a@1.2.4"
                    "example/pkg-b@1.2.4"
                    "example/pkg-cli@1.2.4"] revision]]
           (filterv #(and (vector? %) (= :tags (first %))) @events)))
    (is (= ["example/pkg-a@1.2.4"
            "example/pkg-b@1.2.4"
            "example/pkg-cli@1.2.4"]
           (-> @events last second)))
    (is (= ['example/pkg-a 'example/pkg-b] (:deployed result)))
    (is (= ['example/pkg-cli] (:tag-only result)))))

(deftest production-tag-creation-is-an-all-or-nothing-git-transaction
  (let [root (fs/create-temp-dir {:prefix "collet-atomic-tags-test-"})]
    (try
      (git! root "init" "-b" "main")
      (spit (str (fs/path root "source.txt")) "source")
      (git! root "add" ".")
      (git! root "-c" "user.name=Collet Test"
            "-c" "user.email=collet@example.test"
            "commit" "-m" "feat: source")
      (let [head (git! root "rev-parse" "HEAD")
            existing "example/pkg-b@1.2.4"
            new-tag "example/pkg-a@1.2.4"
            create-tags! (:create-tags! (release/production-ops {:root (str root)}))]
        (git! root "tag" existing)
        (is (= "Command failed"
               (ex-message
                (exception #(create-tags! [new-tag existing] head)))))
        (is (str/blank?
             (:out (shell/sh "git" "-C" (str root)
                             "tag" "--list" new-tag))))
        (is (= existing
               (str/trim
                (:out (shell/sh "git" "-C" (str root)
                                "tag" "--list" existing))))))
      (finally
        (fs/delete-tree root)))))

(deftest source-drift-during-quality-gates-stops-before-build-tag-or-deploy
  (let [events (atom [])
        error (exception
               #(release/execute-release!
                 plan
                 (recording-ops
                  events
                  {:preflight-revisions [revision "changed-after-tests"]})))]
    (is (= "Release source changed during quality gates" (ex-message error)))
    (is (= 2 (count (filter #{:preflight} @events))))
    (is (not-any? #(and (vector? %)
                        (#{:build :tags :deploy} (first %)))
                  @events))))

(deftest source-drift-during-build-stops-before-publication-tag-or-deploy
  (let [events (atom [])
        error (exception
               #(release/execute-release!
                 plan
                 (recording-ops
                  events
                  {:preflight-revisions
                   [revision revision "changed-during-build"]})))]
    (is (= "Release source changed during artifact construction"
           (ex-message error)))
    (is (= 3 (count (filter #{:preflight} @events))))
    (is (some #(and (vector? %) (= :build (first %))) @events))
    (is (not-any? #(and (vector? %)
                        (#{:status :tags :deploy} (first %)))
                  @events))))

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

(deftest direct-pom-dependency-requires-one-exact-core-coordinate
  (let [core (dependency "io.velio" "collet-core" "1.7.2")]
    (is (true? (release/verify-direct-dependency!
                (app-pom (str "<dependencies>" core "</dependencies>"))
                'io.velio/collet-core
                "1.7.2")))
    (doseq [[case pom-text]
            [["missing"
              (app-pom "<dependencies></dependencies>")]
             ["duplicate"
              (app-pom (str "<dependencies>" core core "</dependencies>"))]
             ["wrong version"
              (app-pom
               (str "<dependencies>"
                    (dependency "io.velio" "collet-core" "9.9.9")
                    "</dependencies>"))]
             ["nested only"
              (app-pom
               (str "<dependencyManagement><dependencies>" core
                    "</dependencies></dependencyManagement>"))]
             ["malformed dependency"
              (app-pom
               (str "<dependencies>"
                    (dependency "io.velio" "collet-core" nil)
                    "</dependencies>"))]
             ["malformed XML"
              "<project><dependencies>"]]]
      (testing case
        (is (some? (exception
                    #(release/verify-direct-dependency!
                      pom-text 'io.velio/collet-core "1.7.2"))))))))

(deftest image-jar-verification-includes-the-core-pom-dependency
  (let [root (fs/create-temp-dir {:prefix "collet-image-jar-test-"})
        jar (fs/path root "collet.jar")
        package {:fqn 'io.velio/collet-app
                 :version "2.4.0"
                 :tag "io.velio/collet-app@2.4.0"}
        pom-text (app-pom
                  (str "<dependencies>"
                       (dependency "io.velio" "collet-core" "1.7.2")
                       "</dependencies>"))]
    (try
      (write-jar!
       jar
       {"META-INF/collet/build.edn"
        (pr-str {:version "2.4.0" :revision revision})
        "META-INF/maven/io.velio/collet-app/pom.xml" pom-text
        "META-INF/maven/io.velio/collet-app/pom.properties"
        "groupId=io.velio\nartifactId=collet-app\nversion=2.4.0\n"})
      (is (true? (release/verify-image-jar!
                  package revision "1.7.2" (str jar))))
      (is (= "Application POM does not use the expected core version"
             (ex-message
              (exception #(release/verify-image-jar!
                            package revision "9.9.9" (str jar))))))
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
