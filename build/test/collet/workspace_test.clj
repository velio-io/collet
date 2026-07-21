(ns collet.workspace-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [collet.workspace :as workspace]))

(def expected-collet-edges
  {'io.velio/collet-core #{}
   'io.velio/collet-action-http #{'io.velio/collet-core}
   'io.velio/collet-action-file #{'io.velio/collet-core
                                  'io.velio/collet-action-http}
   'io.velio/collet-action-odata #{'io.velio/collet-core
                                   'io.velio/collet-action-http}
   'io.velio/collet-action-jdbc #{'io.velio/collet-core}
   'io.velio/collet-action-s3 #{'io.velio/collet-core
                                'io.velio/collet-action-file}
   'io.velio/collet-action-queue #{'io.velio/collet-core}
   'io.velio/collet-action-jslt #{'io.velio/collet-core}
   'io.velio/collet-action-llm #{'io.velio/collet-core}
   'io.velio/collet-action-vega #{'io.velio/collet-core
                                  'io.velio/collet-action-file}
   'io.velio/collet-action-lucene #{'io.velio/collet-core}
   'io.velio/collet-actions
   #{'io.velio/collet-action-http
     'io.velio/collet-action-file
     'io.velio/collet-action-odata
     'io.velio/collet-action-jdbc
     'io.velio/collet-action-s3
     'io.velio/collet-action-queue
     'io.velio/collet-action-jslt
     'io.velio/collet-action-llm
     'io.velio/collet-action-vega
     'io.velio/collet-action-lucene}
   'io.velio/collet-app #{'io.velio/collet-core}
   'io.velio/collet-cli #{'io.velio/collet-app}})

(deftest real-workspace-graph-has-the-supported-edges-and-closures
  (let [packages (:packages (workspace/resolve-release-plan! nil))
        order (workspace/package-order packages)
        positions (zipmap order (range))]
    (is (= expected-collet-edges
           (into {} (map (fn [[fqn package]]
                           [fqn (:depends-on package)]))
                 packages)))
    (is (= (set (keys expected-collet-edges)) (set order)))
    (doseq [[package dependencies] expected-collet-edges
            dependency dependencies]
      (is (< (positions dependency) (positions package))
          (str dependency " must precede " package)))
    (is (= (set (keys (dissoc expected-collet-edges
                              'io.velio/collet-actions
                              'io.velio/collet-app
                              'io.velio/collet-cli)))
           (workspace/dependency-closure packages
                                         'io.velio/collet-actions)))
    (is (= #{'io.velio/collet-action-file
             'io.velio/collet-action-odata
             'io.velio/collet-action-s3
             'io.velio/collet-action-vega
             'io.velio/collet-actions}
           (workspace/dependent-closure packages
                                        'io.velio/collet-action-http)))))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- git! [root & args]
  (let [{:keys [exit out err]}
        (apply shell/sh "git" "-C" (str root) args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:args args :error err})))
    (.trim out)))

(defn- commit! [root message]
  (git! root "add" ".")
  (git! root "-c" "user.name=Collet Test"
        "-c" "user.email=collet@example.test"
        "commit" "-m" message))

(defn- with-workspace [f]
  (let [root (fs/create-temp-dir {:prefix "collet-workspace-test-"})]
    (try
      (write-edn!
       (fs/path root "deps.edn")
       {:kmono/workspace {:group 'example
                          :packages "pkg-*/*"
                          :ignore-changes ["^test/" "^dev/" ".*\\.md$"]}
        :collet/project {:url "https://example.test/collet"
                         :license {:name "Test" :url "https://example.test/license"}
                         :scm {:url "https://example.test/collet"
                               :connection "scm:git:https://example.test/collet.git"
                               :developer-connection "scm:git:ssh://example.test/collet.git"}}})
      (doseq [[dir artifact deps]
              [["pkg-a" {:description "A" :kind :library :publish? true} {}]
               ["pkg-b" {:description "B" :kind :library :publish? true}
                {'example/pkg-a {:local/root "../pkg-a"}}]
               ["pkg-cli" {:description "CLI" :kind :uberjar :publish? false
                            :main 'example.cli
                            :outputs {:uberjar "target/cli.jar"}}
                {'example/pkg-b {:local/root "../pkg-b"}
                 'example/pkg-c {:local/root "../pkg-c"}}]
               ["pkg-c" {:description "C" :kind :library :publish? true} {}]]]
        (write-edn! (fs/path root dir "deps.edn")
                    {:paths ["src"]
                     :deps deps
                     :collet/artifact artifact})
        (let [source (fs/path root dir "src" "example" (str (subs dir 4) ".clj"))]
          (fs/create-dirs (fs/parent source))
          (spit (str source) (str "(ns example." (subs dir 4) ")\n"))))
      (git! root "init" "-b" "main")
      (git! root "config" "user.name" "Collet Test")
      (git! root "config" "user.email" "collet@example.test")
      (commit! root "feat: initial workspace")
      (f root)
      (finally
        (fs/delete-tree root)))))

(defn- tag-all! [root version]
  (doseq [package '[example/pkg-a example/pkg-b example/pkg-cli example/pkg-c]]
    (git! root "tag" (str package "@" version))))

(defn- change! [root package path message content]
  (let [file (fs/path root (name package) path)]
    (fs/create-dirs (fs/parent file))
    (spit (str file) content)
    (commit! root message)))

(defn- package [plan fqn]
  (get-in plan [:packages fqn]))

(deftest bootstraps-every-package-at-0-2-8-when-no-package-tags-exist
  (with-workspace
    (fn [root]
      (let [plan (workspace/resolve-release-plan! (str root))]
        (is (= #{'example/pkg-a 'example/pkg-b 'example/pkg-cli 'example/pkg-c}
               (set (:selected plan))))
        (doseq [[fqn package] (:packages plan)]
          (testing (str fqn)
            (is (= "0.2.8" (:current-version package)))
            (is (= "0.2.8" (:version package)))
            (is (= :bootstrap (:reason package)))
            (is (= (str fqn "@0.2.8") (:tag package)))
            (is (:release? package))))
        (is (false? (:publish? (package plan 'example/pkg-cli))))))))

(deftest resolves-current-tags-and-conventional-patch-minor-and-major-bumps
  (doseq [[message expected reason]
          [["fix: correct A" "1.2.4" :patch]
           ["feat: extend A" "1.3.0" :minor]
           ["fix!: replace A API" "2.0.0" :major]
           ["chore: replace A API\n\nBREAKING CHANGE: removed old API"
            "2.0.0" :major]]]
    (testing message
      (with-workspace
        (fn [root]
          (tag-all! root "1.2.3")
          (change! root "pkg-a" "src/example/a.clj" message
                   (str "(ns example.a)\n;; " message "\n"))
          (let [plan (workspace/resolve-release-plan! (str root))
                direct (package plan 'example/pkg-a)
                dependent (package plan 'example/pkg-b)]
            (is (= "1.2.3" (:current-version direct)))
            (is (= expected (:version direct)))
            (is (= reason (:reason direct)))
            (is (= "1.2.4" (:version dependent)))
            (is (= :dependency (:reason dependent)))))))))

(deftest normal-test-doc-and-dev-only-changes-remain-ignored
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (is (empty? (:selected (workspace/resolve-release-plan! (str root)))))
      (change! root "pkg-a" "test/example/a_test.clj" "test: cover A" "test only")
      (change! root "pkg-a" "dev/scratch.clj" "chore: experiment" "dev only")
      (change! root "pkg-a" "notes.md" "docs: explain A" "markdown only")
      (is (empty? (:selected (workspace/resolve-release-plan! (str root))))))))

(deftest deleted-runtime-source-retains-the-commit-and-produces-a-patch
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (fs/delete (fs/path root "pkg-a" "src" "example" "a.clj"))
      (commit! root "fix: remove obsolete A runtime source")
      (let [plan (workspace/resolve-release-plan! (str root))]
        (is (= :patch (:reason (package plan 'example/pkg-a))))
        (is (= "1.2.4" (:version (package plan 'example/pkg-a))))
        (is (= :dependency (:reason (package plan 'example/pkg-b))))))))

(deftest deleted-runtime-source-without-release-commit-is-actionable
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (fs/delete (fs/path root "pkg-a" "src" "example" "a.clj"))
      (commit! root "chore: remove obsolete A runtime source")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Use fix:, feat:, !, BREAKING CHANGE:, or fix the squash PR title"
           (workspace/resolve-release-plan! (str root)))))))

(deftest runtime-source-renamed-into-an-ignored-path-still-produces-a-release
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (fs/create-dirs (fs/path root "pkg-a" "test" "example"))
      (git! root "mv"
            "pkg-a/src/example/a.clj"
            "pkg-a/test/example/a_test.clj")
      (commit! root "fix: retire A runtime source into a regression fixture")
      (let [plan (workspace/resolve-release-plan! (str root))]
        (is (= :patch (:reason (package plan 'example/pkg-a))))
        (is (= "1.2.4" (:version (package plan 'example/pkg-a))))))))

(deftest runtime-change-introduced-by-a-merge-commit-produces-a-release
  (with-workspace
    (fn [root]
      (git! root "switch" "-c" "topic")
      (change! root "pkg-a" "notes.md" "docs: prepare A merge" "topic notes")
      (git! root "switch" "main")
      (tag-all! root "1.2.3")
      (git! root "merge" "--no-ff" "--no-commit" "topic")
      (spit (str (fs/path root "pkg-a" "src" "example" "a.clj"))
            "(ns example.a)\n;; merge resolution\n")
      (commit! root "fix: integrate A runtime merge resolution")
      (let [plan (workspace/resolve-release-plan! (str root))]
        (is (= :patch (:reason (package plan 'example/pkg-a))))
        (is (= "1.2.4" (:version (package plan 'example/pkg-a))))
        (is (= :dependency (:reason (package plan 'example/pkg-b))))))))

(deftest rejects-meaningful-package-changes-without-a-version-bump
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root "pkg-a" "src/example/a.clj" "chore: rearrange A"
               "(ns example.a)\n;; meaningful\n")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Use fix:, feat:, !, BREAKING CHANGE:, or fix the squash PR title"
           (workspace/resolve-release-plan! (str root)))))))

(deftest module-filter-keeps-required-changed-dependency-and-dependent-closure
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root "pkg-a" "src/example/a.clj" "fix: correct A"
               "(ns example.a)\n;; fixed\n")
      (let [plan (workspace/resolve-release-plan! (str root) {:module :pkg-b})]
        (is (= #{'example/pkg-a 'example/pkg-b 'example/pkg-cli}
               (set (:selected plan))))
        (is (not (contains? (set (:selected plan)) 'example/pkg-c)))))))

(deftest module-filter-reaches-a-fixed-point-across-a-changed-diamond
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root "pkg-b" "src/example/b.clj" "fix: correct B"
               "(ns example.b)\n;; fixed\n")
      (change! root "pkg-c" "src/example/c.clj" "feat: extend C"
               "(ns example.c)\n;; feature\n")
      (let [plan (workspace/resolve-release-plan! (str root) {:module :pkg-b})]
        (is (= #{'example/pkg-b 'example/pkg-c 'example/pkg-cli}
               (set (:selected plan))))
        (is (= "1.2.4" (:version (package plan 'example/pkg-b))))
        (is (= "1.3.0" (:version (package plan 'example/pkg-c))))
        (is (= "1.2.4" (:version (package plan 'example/pkg-cli))))))))

(deftest plan-retains-current-candidate-reason-tag-and-publication-metadata
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root "pkg-c" "src/example/c.clj" "feat: extend C"
               "(ns example.c)\n;; feature\n")
      (is (= {:current-version "1.2.3"
              :version "1.3.0"
              :reason :minor
              :tag "example/pkg-c@1.3.0"
              :publish? true
              :release? true}
             (select-keys (package (workspace/resolve-release-plan! (str root))
                                   'example/pkg-c)
                          [:current-version :version :reason :tag
                           :publish? :release?]))))))

(deftest resolves-package-tags-at-head-as-resumable-exact-versions
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root "pkg-a" "src/example/a.clj" "fix: correct A"
               "(ns example.a)\n;; fixed\n")
      (git! root "tag" "example/pkg-a@1.2.4")
      (git! root "tag" "example/pkg-b@2.0.0")
      (git! root "tag" "example/pkg-cli@2.0.1")
      (let [plan (workspace/resolve-pending-release-plan! (str root))]
        (is (= ['example/pkg-a 'example/pkg-b 'example/pkg-cli]
               (:selected plan)))
        (is (= {:version "1.2.4"
                :reason :resume
                :tag "example/pkg-a@1.2.4"
                :release? true}
               (select-keys (package plan 'example/pkg-a)
                            [:version :reason :tag :release?])))
        (is (= "2.0.0" (:version (package plan 'example/pkg-b))))
        (is (= {:version "1.2.3"
                :current-version "1.2.3"
                :reason :unchanged
                :release? false}
               (select-keys (package plan 'example/pkg-c)
                            [:version :current-version :reason :release?])))))))

(deftest rejects-a-partial-pending-dependent-tag-set
  (with-workspace
    (fn [root]
      (git! root "tag" "example/pkg-a@1.2.4")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Pending package tags are incomplete"
           (workspace/resolve-pending-release-plan! (str root)))))))
