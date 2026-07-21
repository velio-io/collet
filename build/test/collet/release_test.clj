(ns collet.release-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [collet.release :as release]
   [collet.verify :as verify]))

(def packages
  [{:fqn 'example/pkg-a
    :current-version "1.2.3"
    :version "1.2.4"
    :reason :patch
    :tag "example/pkg-a@1.2.4"
    :publish? true}
   {:fqn 'example/pkg-b
    :current-version "1.2.3"
    :version "1.2.4"
    :reason :dependency
    :tag "example/pkg-b@1.2.4"
    :publish? true}
   {:fqn 'example/pkg-cli
    :current-version "1.2.3"
    :version "1.2.4"
    :reason :dependency
    :tag "example/pkg-cli@1.2.4"
    :publish? false}])

(defn- exception [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(defn- release-var [name]
  (ns-resolve 'collet.release name))

(defn- run-release! [revisions existing-tag events]
  (let [remaining (atom revisions)
        package (first packages)]
    (with-redefs-fn
      {(release-var 'fetch-tags!) (fn [_] nil)
       (release-var 'candidate-plan)
       (fn [_ _] {:context {:packages {(:fqn package) package}}
                   :packages [package]})
       (release-var 'production-preflight!)
       (fn [_]
         (let [revision (first @remaining)]
           (swap! remaining rest)
           (swap! events conj [:preflight revision])
           {:revision revision}))
       #'release/validate-credentials! (fn [& _] nil)
       (release-var 'git-tag-target) (fn [& _] existing-tag)
       (release-var 'quality-gate!)
       (fn [_ task] (swap! events conj [:quality task]))
       (release-var 'build-release!)
       (fn [& _]
         (swap! events conj [:build])
         {:artifacts {(:fqn package) {}}})
       (release-var 'deploy!)
       (fn [& _] (swap! events conj [:deploy]))
       (release-var 'create-tags!)
       (fn [_ _ & [revision]] (swap! events conj [:tag revision]))
       (release-var 'push-tags!)
       (fn [& _] (swap! events conj [:push]))}
      (fn [] (release/release {:root "repo"})))))

(defn- with-cli-artifacts [top-level-pod archived-pod f]
  (let [root (fs/create-temp-dir {:prefix "verify-cli-release-test-"})
        target (fs/path root "target")
        archive-root (fs/path root "archive" "collet-cli")
        archive (fs/path target "collet-cli.tar.gz")]
    (try
      (fs/create-dirs target)
      (fs/create-dirs archive-root)
      (spit (str (fs/path target "collet.pod.jar")) top-level-pod)
      (spit (str (fs/path archive-root "collet.pod.jar")) archived-pod)
      (let [{:keys [exit err]}
            (shell/sh "tar" "-czf" (str archive)
                      "-C" (str (fs/parent archive-root)) "collet-cli")]
        (when-not (zero? exit)
          (throw (ex-info "tar failed" {:error err}))))
      (f (str root))
      (finally
        (fs/delete-tree root)))))

(deftest release-steps-follow-the-fail-fast-pipeline
  (is (= [:quality-gate
          :build
          [:deploy 'example/pkg-a]
          [:deploy 'example/pkg-b]
          :tag
          :push]
         (release/release-steps packages))))

(deftest release-steps-omit-deployment-for-tag-only-packages
  (is (= [:quality-gate :build :tag :push]
         (release/release-steps [(last packages)]))))

(deftest plan-display-shows-package-current-next-reason-tag-and-publication
  (let [output (release/format-plan packages)]
    (doseq [text ["PACKAGE" "CURRENT" "NEXT" "REASON" "TAG" "PUBLICATION"
                  "example/pkg-a" "1.2.3" "1.2.4" "patch"
                  "example/pkg-a@1.2.4" "Maven"
                  "example/pkg-cli" "tag only"]]
      (is (str/includes? output text)))))

(deftest preflight-requires-clean-synchronized-main
  (is (= {:revision "abc123"}
         (release/validate-preflight!
          {:branch "main" :status "" :head "abc123" :remote-head "abc123"})))
  (doseq [[state message]
          [[{:branch "topic" :status "" :head "abc123" :remote-head "abc123"}
            "Releases require the main branch"]
           [{:branch "main" :status " M file" :head "abc123" :remote-head "abc123"}
            "Releases require a clean worktree"]
           [{:branch "main" :status "" :head "abc123" :remote-head "other"}
            "Local main must equal origin/main"]]]
    (is (= message
           (ex-message (exception #(release/validate-preflight! state)))))))

(deftest publishable-plans-require-both-clojars-credentials
  (is (nil? (release/validate-credentials!
             {"CLOJARS_USERNAME" "user" "CLOJARS_PASSWORD" "token"}
             packages)))
  (is (nil? (release/validate-credentials! {} [(last packages)])))
  (let [error (exception #(release/validate-credentials!
                           {"CLOJARS_USERNAME" "user"}
                           packages))]
    (is (= "Clojars credentials are required for publishable packages"
           (ex-message error)))
    (is (= ["CLOJARS_PASSWORD"] (:variables (ex-data error))))))

(deftest nondeployment-environment-removes-publication-credentials
  (is (= {"PATH" "/bin" "OTHER" "kept"}
         (release/nondeployment-env
          {"PATH" "/bin"
           "OTHER" "kept"
           "CLOJARS_USERNAME" "secret"
           "CLOJARS_PASSWORD" "secret"}))))

(deftest release-revalidates-and-tags-the-captured-source-revision
  (let [events (atom [])]
    (run-release! ["abc123" "abc123" "abc123"] nil events)
    (is (= [[:preflight "abc123"]
            [:preflight "abc123"]
            [:preflight "abc123"]]
           (filterv #(= :preflight (first %)) @events)))
    (is (= [:tag "abc123"] (first (filter #(= :tag (first %)) @events))))))

(deftest source-drift-after-build-prevents-deployment-and-tagging
  (let [events (atom [])
        error (exception #(run-release! ["abc123" "abc123" "other"]
                                        nil events))]
    (is (= "Release source revision changed" (ex-message error)))
    (is (not-any? #(#{:deploy :tag :push} (first %)) @events))))

(deftest an-existing-target-tag-stops-before-quality-gates
  (let [events (atom [])
        error (exception #(run-release! ["abc123"] "other-lineage" events))]
    (is (= "Release target tag already exists" (ex-message error)))
    (is (= "example/pkg-a@1.2.4" (:tag (ex-data error))))
    (is (not-any? #(#{:quality :build :deploy :tag :push} (first %)) @events))))

(deftest cli-release-check-reuses-the-public-deployable-contract
  (with-cli-artifacts
    "same-pod" "same-pod"
    (fn [root]
      (let [package {:fqn 'io.velio/collet-cli
                     :version "1.2.4"
                     :tag "io.velio/collet-cli@1.2.4"
                     :absolute-path root}
            context {:tag (:tag package)
                     :revision "abc123"
                     :package package
                     :packages {(:fqn package) package}}
            checked (atom nil)]
        (with-redefs-fn
          {(release-var 'ensure-tag-checkout!) (fn [& _] context)
           (release-var 'verify-jar) (fn [& _] :matching)
           #'verify/verify-deployable!
           (fn [actual-context actual-package]
             (reset! checked [actual-context actual-package])
             true)}
          (fn []
            (with-out-str
              (release/verify-cli {:root root :tag (:tag package)}))))
        (is (= [context package] @checked))))))

(deftest cli-release-check-rejects-a-different-archived-pod
  (with-cli-artifacts
    "top-level-pod" "different-archived-pod"
    (fn [root]
      (let [package {:fqn 'io.velio/collet-cli
                     :version "1.2.4"
                     :tag "io.velio/collet-cli@1.2.4"
                     :absolute-path root}
            context {:tag (:tag package)
                     :revision "abc123"
                     :package package
                     :packages {(:fqn package) package}}
            error (with-redefs-fn
                    {(release-var 'ensure-tag-checkout!) (fn [& _] context)
                     (release-var 'verify-jar) (fn [& _] :matching)
                     #'verify/verify-deployable! (fn [& _] true)}
                    (fn []
                      (exception #(release/verify-cli
                                   {:root root :tag (:tag package)}))))]
        (is (= "Archived CLI pod differs from the top-level pod"
               (ex-message error)))))))

(deftest direct-dependency-rejects-a-conflicting-duplicate-version
  (let [dependency (fn [version]
                     (str "<dependency><groupId>io.velio</groupId>"
                          "<artifactId>collet-core</artifactId>"
                          "<version>" version "</version></dependency>"))
        pom (str "<project><dependencies>"
                 (dependency "1.7.2")
                 (dependency "9.9.9")
                 "</dependencies></project>")
        error (exception #(release/verify-direct-dependency!
                           pom 'io.velio/collet-core "1.7.2"))]
    (is (= "POM dependency must be declared exactly once" (ex-message error)))
    (is (= 2 (:count (ex-data error))))))
