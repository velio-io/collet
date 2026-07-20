(ns collet.workspace-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [collet.workspace :as workspace]))

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

(deftest returns-no-release-for-an-unchanged-or-ignored-package
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (is (empty? (:selected (workspace/resolve-release-plan! (str root)))))
      (change! root "pkg-a" "test/example/a_test.clj" "test: cover A" "test only")
      (change! root "pkg-a" "dev/scratch.clj" "chore: experiment" "dev only")
      (change! root "pkg-a" "notes.md" "docs: explain A" "markdown only")
      (is (empty? (:selected (workspace/resolve-release-plan! (str root))))))))

(deftest rejects-meaningful-package-changes-without-a-version-bump
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root "pkg-a" "src/example/a.clj" "chore: rearrange A"
               "(ns example.a)\n;; meaningful\n")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Meaningful package changes require a version bump"
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
