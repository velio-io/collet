(ns collet.release-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [clojure.tools.build.api :as b]
   [collet.build :as build]
   [collet.release :as release]
   [k16.kaven.deploy :as kaven.deploy]))

(def packages
  {'example/pkg-a
   {:fqn 'example/pkg-a
    :version "1.2.4"
    :release? true
    :deps-edn {:collet/artifact {:publish? true}}}
   'example/pkg-cli
   {:fqn 'example/pkg-cli
    :version "1.2.4"
    :release? true
    :depends-on #{'example/pkg-a}
    :deps-edn {:collet/artifact {:publish? false}}}})

(defn- exception [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error error)))

(defn- release-var [name]
  (ns-resolve 'collet.release name))

(deftest plan-shows-only-package-version-tag-and-publication
  (let [output (release/format-plan packages)]
    (doseq [text ["PACKAGE" "VERSION" "TAG" "PUBLICATION"
                  "example/pkg-a" "1.2.4" "example/pkg-a@1.2.4"
                  "Maven" "example/pkg-cli" "tag only"]]
      (is (str/includes? output text)))
    (is (not (str/includes? output "REASON")))
    (is (not (str/includes? output "CURRENT")))))

(deftest preflight-requires-clean-synchronized-main
  (is (= "abc123"
         (release/validate-preflight!
          {:branch "main" :status "" :head "abc123" :remote-head "abc123"})))
  (doseq [[state message]
          [[{:branch "topic" :status "" :head "abc123" :remote-head "abc123"}
            "Releases require the main branch"]
           [{:branch "main" :status " M file" :head "abc123" :remote-head "abc123"}
            "Releases require a clean worktree"]
           [{:branch "main" :status "" :head "abc123" :remote-head "other"}
            "Local main must equal origin/main"]]]
    (is (= message (ex-message (exception #(release/validate-preflight! state)))))))

(deftest kaven-deploy-reuses-the-built-jar-and-pom
  (let [called (atom nil)
        package (get packages 'example/pkg-a)
        artifact {:jar-file "/tmp/pkg.jar" :pom-file "/tmp/pom.xml"}]
    (with-redefs [kaven.deploy/deploy #(reset! called %)]
      ((release-var 'deploy!) package artifact))
    (is (= {:jar-path "/tmp/pkg.jar"
            :pom-path "/tmp/pom.xml"
            :repository {:id "clojars" :url "https://repo.clojars.org/"}}
           @called))))

(deftest kaven-deploys-to-a-file-maven-repository
  (let [root (fs/create-temp-dir {:prefix "collet-kaven-test-"})
        classes (fs/path root "classes")
        jar (fs/path root "sample.jar")
        pom (fs/path root "pom.xml")
        repository (fs/path root "repository")
        home (fs/path root "home")]
    (try
      (fs/create-dirs classes)
      (fs/create-dirs (fs/path home ".m2"))
      (spit (str (fs/path home ".m2" "settings.xml"))
            (str "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\">"
                 "<servers><server><id>test</id><username>user</username>"
                 "<password>token</password></server></servers></settings>"))
      (spit (str pom)
            (str "<project><modelVersion>4.0.0</modelVersion>"
                 "<groupId>example</groupId><artifactId>sample</artifactId>"
                 "<version>1.0.0</version></project>"))
      (b/with-project-root (str root)
        (b/jar {:class-dir "classes" :jar-file "sample.jar"}))
      (let [original-home (System/getProperty "user.home")]
        (try
          (System/setProperty "user.home" (str home))
          (kaven.deploy/deploy
           {:jar-path (str jar)
            :pom-path (str pom)
            :repository {:id "test" :url (str (.toUri repository))}})
          (finally
            (System/setProperty "user.home" original-home))))
      (is (fs/regular-file?
           (fs/path repository "example" "sample" "1.0.0" "sample-1.0.0.jar")))
      (is (fs/regular-file?
           (fs/path repository "example" "sample" "1.0.0" "sample-1.0.0.pom")))
      (finally
        (fs/delete-tree root)))))

(deftest release-builds-and-deploys-all-changed-packages-before-tagging
  (let [events (atom [])
        artifacts {'example/pkg-a {:jar-file "/tmp/a.jar" :pom-file "/tmp/a.xml"}
                   'example/pkg-cli {:uber-file "/tmp/cli.jar"}}]
    (with-redefs-fn
      {(release-var 'fetch-tags!) (fn [_] (swap! events conj [:fetch]))
       (release-var 'production-preflight!) (fn [_] "abc123")
       (release-var 'ensure-target-tags-absent!) (fn [& _] nil)
       #'build/load-packages (fn [_] packages)
       (release-var 'quality-gate!) (fn [_ task] (swap! events conj [:quality task]))
       #'build/build (fn [_] (swap! events conj [:build]) {:artifacts artifacts})
       (release-var 'deploy!)
       (fn [package _] (swap! events conj [:deploy (:fqn package)]))
       (release-var 'create-tags!)
       (fn [_ selected revision]
         (swap! events conj [:tag (mapv :fqn (vals selected)) revision]))
       (release-var 'push-tags!)
       (fn [_ selected]
         (swap! events conj [:push (mapv :fqn (vals selected))]))}
      #(release/release {:root "repo"}))
    (is (= [[:fetch]
            [:quality "test"]
            [:quality "verify"]
            [:build]
            [:fetch]
            [:deploy 'example/pkg-a]
            [:tag ['example/pkg-a 'example/pkg-cli] "abc123"]
            [:push ['example/pkg-a 'example/pkg-cli]]]
           @events))))
