(ns collet.build-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.build.api :as b]
   [collet.build :as build]))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- git! [root & args]
  (let [{:keys [exit err]} (apply shell/sh "git" "-C" (str root) args)]
    (when-not (zero? exit)
      (throw (ex-info "git failed" {:args args :error err})))))

(defn- tag-all! [root version]
  (doseq [package '[example/pkg-a example/pkg-b example/pkg-cli]]
    (git! root "tag" (str package "@" version))))

(defn- change! [root package message]
  (let [path (fs/path root (name package) "src" "example"
                      (str (name package) ".clj"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path) (str ";; " message "\n"))
    (git! root "add" "-A")
    (git! root "-c" "user.name=Collet Test"
          "-c" "user.email=collet@example.test"
          "commit" "-m" message)))

(defn- change-file! [root package relative message]
  (let [path (fs/path root (name package) relative)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) message)
    (git! root "add" "-A")
    (git! root "-c" "user.name=Collet Test"
          "-c" "user.email=collet@example.test"
          "commit" "-m" message)))

(defn- with-workspace [f]
  (let [root (fs/create-temp-dir {:prefix "collet-build-test-"})
        artifact (fn [description kind]
                   {:description description
                    :public-namespaces []
                    :publish? (not= :distribution kind)
                    :kind kind})]
    (try
      (write-edn!
       (fs/path root "deps.edn")
       {:kmono/workspace {:group 'example :packages "pkg-*/*"
                          :ignore-changes ["^test/" ".*\\.md$"]}
        :collet/project {:url "https://example.test/collet"
                         :license {:name "Test" :url "https://example.test/license"}
                         :scm {:url "https://example.test/collet"
                               :connection "scm:git:https://example.test/collet.git"
                               :developer-connection "scm:git:ssh://example.test/collet.git"}}})
      (write-edn! (fs/path root "pkg-a" "deps.edn")
                  {:paths ["src"]
                   :collet/artifact (artifact "A" :library)})
      (write-edn! (fs/path root "pkg-b" "deps.edn")
                  {:paths ["src"]
                   :deps {'example/pkg-a {:local/root "../pkg-a"}}
                   :collet/artifact (assoc (artifact "B" :uberjar)
                                           :main 'example.b
                                           :outputs {:uberjar "target/b.jar"})})
      (write-edn! (fs/path root "pkg-cli" "deps.edn")
                  {:paths ["src"]
                   :deps {'example/pkg-b {:local/root "../pkg-b"}}
                   :collet/artifact (assoc (artifact "CLI" :distribution)
                                           :main 'example.cli
                                           :outputs {:uberjar "target/cli.jar"
                                                     :archive "target/cli.tar.gz"
                                                     :root "cli"
                                                     :files []})})
      (git! root "init" "-b" "main")
      (git! root "add" ".")
      (git! root "-c" "user.name=Collet Test"
            "-c" "user.email=collet@example.test"
            "commit" "-m" "feat: initial workspace")
      (f (str root))
      (finally
        (fs/delete-tree root)))))

(deftest root-workspace-ignores-non-package-change-paths
  (is (= #{"^test/" "^test-resources/" "^dev/" "^configs/" ".*\\.md$"}
         (set (get-in (edn/read-string (slurp "deps.edn"))
                      [:kmono/workspace :ignore-changes])))))

(deftest load-packages-bootstraps-the-full-workspace-at-0-2-8
  (with-workspace
    (fn [root]
      (let [packages (build/load-packages {:dir root :changes? true})]
        (is (= #{'example/pkg-a 'example/pkg-b 'example/pkg-cli}
               (set (keys packages))))
        (is (= #{"0.2.8"} (set (map :version (vals packages)))))
        (is (= (set (keys packages))
               (set (keys (build/release-packages packages)))))
        (is (= :distribution
               (get-in packages ['example/pkg-cli :deps-edn
                                 :collet/artifact :kind])))))))

(deftest load-packages-delegates-conventional-bumps-and-dependent-bumps-to-kmono
  (doseq [[message expected] [["fix: correct A" "1.2.4"]
                              ["feat: extend A" "1.3.0"]
                              ["fix!: replace A API" "2.0.0"]]]
    (testing message
      (with-workspace
        (fn [root]
          (tag-all! root "1.2.3")
          (change! root :pkg-a message)
          (let [packages (build/load-packages {:dir root :changes? true})
                releases (build/release-packages packages)]
            (is (= expected (get-in releases ['example/pkg-a :version])))
            (is (= "1.2.4" (get-in releases ['example/pkg-b :version])))
            (is (= "1.2.4" (get-in releases ['example/pkg-cli :version])))))))))

(deftest runtime-changes-require-a-release-producing-commit
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root :pkg-a "docs: explain A")
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Package changes require a release-producing conventional commit"
           (build/load-packages {:dir root :changes? true}))))))

(deftest ignored-test-and-markdown-files-do-not-release
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change-file! root :pkg-a "test/example/a_test.clj" "fix: stabilize tests")
      (change-file! root :pkg-a "README.md" "fix: correct package docs")
      (is (empty? (build/release-packages
                   (build/load-packages {:dir root :changes? true})))))))

(deftest explicit-versions-support-gitless-docker-builds
  (with-workspace
    (fn [root]
      (fs/delete-tree (fs/path root ".git"))
      (let [versions {'example/pkg-a "2.1.0"
                      'example/pkg-b "3.2.1"
                      'example/pkg-cli "4.0.0"}
            packages (build/load-packages {:dir root :versions versions})]
        (is (= versions (into {} (map (juxt key (comp :version val))) packages)))))))

(deftest module-build-runs-the-requested-dependency-closure-in-order
  (with-workspace
    (fn [root]
      (let [calls (atom [])]
        (with-redefs-fn
          {(ns-resolve 'collet.build 'build-package!)
           (fn [_ package _]
             (swap! calls conj
                    [(:fqn package)
                     (.getCanonicalPath (b/resolve-path "."))])
             {:package (:fqn package)})}
          #(build/build {:dir root :module :pkg-b}))
        (is (= ['example/pkg-a 'example/pkg-b] (mapv first @calls)))
        (is (every? #(str/ends-with? (second %) (name (first %))) @calls))))))
