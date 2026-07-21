(ns collet.release-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [collet.release :as release]))

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
