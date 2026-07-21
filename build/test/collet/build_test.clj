(ns collet.build-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.build.api :as b]
   [collet.build :as build]
   [collet.verify :as verify]))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- thrown-with-message? [message-pattern f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo error
      (boolean (re-find message-pattern (ex-message error))))))

(defn- git! [root & args]
  (let [{:keys [exit err]}
        (apply shell/sh "git" "-C" (str root) args)]
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
    (git! root "add" ".")
    (git! root "-c" "user.name=Collet Test"
          "-c" "user.email=collet@example.test"
          "commit" "-m" message)))

(defn- with-workspace [f]
  (let [root (fs/create-temp-dir {:prefix "collet-build-test-"})]
    (try
      (write-edn!
       (fs/path root "deps.edn")
       {:kmono/workspace {:group 'example
                          :packages "pkg-*/*"}
        :collet/project {:url "https://example.test/collet"
                         :license {:name "Test"
                                   :url "https://example.test/license"}
                         :scm {:url "https://example.test/collet"
                               :connection "scm:git:https://example.test/collet.git"
                               :developer-connection "scm:git:ssh://example.test/collet.git"}}})
      (write-edn!
       (fs/path root "pkg-a" "deps.edn")
       {:paths ["src"]
        :collet/artifact {:description "A"
                          :public-namespaces ['example.a]
                          :publish? true
                          :kind :library}})
      (write-edn!
       (fs/path root "pkg-b" "deps.edn")
       {:paths ["src"]
        :deps {'example/pkg-a {:local/root "../pkg-a"}}
        :collet/artifact {:description "B"
                          :public-namespaces ['example.b]
                          :publish? true
                          :kind :uberjar
                          :main 'example.b
                          :outputs {:uberjar "target/b.jar"}}})
      (write-edn!
       (fs/path root "pkg-cli" "deps.edn")
       {:paths ["src"]
        :deps {'example/pkg-b {:local/root "../pkg-b"}}
        :collet/artifact {:description "CLI"
                          :public-namespaces ['example.cli]
                          :publish? false
                          :kind :distribution
                          :main 'example.cli
                          :outputs {:uberjar "target/cli.jar"
                                    :archive "target/cli.tar.gz"
                                    :root "cli"
                                    :files [{:from "target/cli.jar"
                                             :to "cli.jar"}]}}})
      (git! root "init" "-b" "main")
      (git! root "add" ".")
      (git! root "-c" "user.name=Collet Test"
            "-c" "user.email=collet@example.test"
            "commit" "-m" "feat: initial workspace")
      (f (str root))
      (finally
        (fs/delete-tree root)))))

(deftest resolve-context-bootstraps-every-package-and-attaches-build-metadata
  (with-workspace
    (fn [root]
      (let [resolve-context (ns-resolve 'collet.build 'resolve-context!)]
        (is (some? resolve-context)
            "the root build exposes direct Kmono package planning")
        (when resolve-context
          (let [{:keys [packages project order]} (resolve-context root)]
            (is (= #{'example/pkg-a 'example/pkg-b 'example/pkg-cli}
                   (set (keys packages))))
            (is (= ["0.2.8" "0.2.8" "0.2.8"]
                   (mapv :version (vals packages))))
            (is (= ['example/pkg-a 'example/pkg-b 'example/pkg-cli]
                   order))
            (is (= "https://example.test/collet" (:url project)))
            (is (= :distribution
                   (get-in packages ['example/pkg-cli :artifact :kind])))))))))

(deftest resolve-context-requires-complete-docker-version-overrides
  (with-workspace
    (fn [root]
      (fs/delete-tree (fs/path root ".git"))
      (let [resolve-context (ns-resolve 'collet.build 'resolve-context!)]
        (is (some? resolve-context))
        (when resolve-context
          (is (thrown-with-message?
               #"Complete package version overrides are required"
               #(resolve-context root {:versions {'example/pkg-a "2.1.0"}})))
          (is (thrown-with-message?
               #"Complete package version overrides are required"
               #(resolve-context root {:versions nil})))
          (is (= {'example/pkg-a "2.1.0"
                  'example/pkg-b "3.2.1"
                  'example/pkg-cli "4.0.0"}
                 (into {}
                       (map (juxt key (comp :version val)))
                       (:packages
                        (resolve-context root {:versions {'example/pkg-a "2.1.0"
                                                         'example/pkg-b "3.2.1"
                                                         'example/pkg-cli "4.0.0"}}))))))))))

(deftest resolve-context-uses-kmono-conventional-increments-and-dependent-patches
  (doseq [[message expected]
          [["fix: correct A" ["1.2.4" :patch]]
           ["feat: extend A" ["1.3.0" :minor]]
           ["fix!: replace A API" ["2.0.0" :major]]
           ["chore: compatibility\n\nBREAKING CHANGE: removed old API"
            ["2.0.0" :major]]]]
    (testing message
      (with-workspace
        (fn [root]
          (tag-all! root "1.2.3")
          (change! root :pkg-a message)
          (let [resolve-context (ns-resolve 'collet.build 'resolve-context!)]
            (is (some? resolve-context))
            (when resolve-context
              (let [context (resolve-context root {:changes? true})
                    packages (:packages context)]
                (is (= expected
                       [(:version (packages 'example/pkg-a))
                        (:reason (packages 'example/pkg-a))]))
                (is (= ["1.2.4" :dependency]
                       [(:version (packages 'example/pkg-b))
                        (:reason (packages 'example/pkg-b))]))
                (is (= ['example/pkg-a 'example/pkg-b 'example/pkg-cli]
                       (build/release-packages context nil)))))))))))

(deftest release-packages-omits-nonreleasing-commits
  (with-workspace
    (fn [root]
      (tag-all! root "1.2.3")
      (change! root :pkg-a "docs: explain A")
      (let [resolve-context (ns-resolve 'collet.build 'resolve-context!)
            releases (ns-resolve 'collet.build 'release-packages)]
        (is (some? resolve-context))
        (is (some? releases))
        (when (and resolve-context releases)
          (let [context (resolve-context root {:changes? true})]
            (is (= :unchanged
                   (get-in context [:packages 'example/pkg-a :reason])))
            (is (= [] (releases context nil)))))))))

(deftest resolves-a-completely-versioned-build-context-without-git
  (with-workspace
    (fn [root]
      (fs/delete-tree (fs/path root ".git"))
      (let [versions {'example/pkg-a "2.1.0"
                      'example/pkg-b "3.2.1"
                      'example/pkg-cli "4.0.0"}
            context (build/resolve-context!
                     root {:versions versions})]
        (is (= versions
               (into {} (map (juxt key (comp :version val)))
                     (:packages context))))
        (is (= "example/pkg-b@3.2.1"
               (get-in context [:packages 'example/pkg-b :tag]))))
      (is (thrown-with-message?
           #"Complete package version overrides are required"
           #(build/resolve-context!
             root {:versions {'example/pkg-a "2.1.0"}}))))))

(deftest package-version-prints-the-exact-kmono-version
  (with-workspace
    (fn [root]
      (is (= "0.2.8\n"
             (with-out-str
               (build/package-version {:root root :module :pkg-a})))))))

(deftest package-version-requires-a-known-module
  (with-workspace
    (fn [root]
      (is (thrown-with-message?
           #"Package module is required"
           #(build/package-version {:root root})))
      (is (thrown-with-message?
           #"Unknown or ambiguous workspace package"
           #(build/package-version {:root root :module :missing}))))))

(deftest build-packages-binds-each-selected-package-root-in-dependency-order
  (with-workspace
    (fn [root]
      (b/with-project-root root
        (let [{:keys [packages] :as context} (build/resolve-context! root)
              selected ['example/pkg-a 'example/pkg-b]
              calls (atom [])]
          (with-redefs-fn
            {(ns-resolve 'collet.build 'build-package!)
             (fn [_ package _]
               (swap! calls conj
                      [(:fqn package)
                       (.getCanonicalPath (b/resolve-path "."))])
               {:package (:fqn package)})}
            #(build/build-packages context selected {}))
          (is (= (mapv (fn [fqn]
                         [fqn (str (fs/canonicalize
                                    (get-in packages [fqn :absolute-path])))])
                       selected)
                 @calls)))))))

(deftest chooses-publishable-and-local-build-bases
  (with-workspace
    (fn [root]
      (let [resolve-context (ns-resolve 'collet.build
                                        'resolve-context!)
            package-basis (ns-resolve 'collet.build 'package-basis)]
        (is (some? resolve-context))
        (is (some? package-basis))
        (when (and resolve-context package-basis)
          (let [{:keys [packages] :as context} (resolve-context root)
                package (get packages 'example/pkg-b)]
            (b/with-project-root (:absolute-path package)
              (testing "publishable POMs replace workspace roots with Maven versions"
                (let [basis (package-basis context package :pom {})]
                  (is (= :mvn
                         (get-in basis [:libs 'example/pkg-a :deps/manifest])))
                  (is (= "0.2.8"
                         (get-in basis [:libs 'example/pkg-a :mvn/version])))))
              (testing "uberjars retain the local workspace dependency graph"
                (let [basis (package-basis context package :uberjar {})]
                  (is (= :deps
                         (get-in basis [:libs 'example/pkg-a :deps/manifest])))
                  (is (nil? (get-in basis
                                    [:libs 'example/pkg-a :mvn/version]))))))))))))

(deftest pom-basis-uses-the-exact-independent-dependency-version
  (with-workspace
    (fn [root]
      (fs/delete-tree (fs/path root ".git"))
      (let [versions {'example/pkg-a "1.7.2"
                      'example/pkg-b "4.3.1"
                      'example/pkg-cli "9.0.0"}
            {:keys [packages] :as context}
            (build/resolve-context! root {:versions versions})
            package (get packages 'example/pkg-b)]
        (b/with-project-root (:absolute-path package)
          (let [basis (build/package-basis context package :pom {})]
            (is (= "1.7.2"
                   (get-in basis [:libs 'example/pkg-a :mvn/version])))
            (is (nil? (get-in basis [:libs 'example/pkg-a :local/root])))))))))

(deftest nonpublishable-distribution-pom-uses-exact-independent-dependencies
  (with-workspace
    (fn [root]
      (fs/delete-tree (fs/path root ".git"))
      (let [versions {'example/pkg-a "1.7.2"
                      'example/pkg-b "4.3.1"
                      'example/pkg-cli "9.0.0"}
            {:keys [packages] :as context}
            (build/resolve-context! root {:versions versions})
            package (get packages 'example/pkg-cli)
            write-pom! (ns-resolve 'collet.build 'write-pom!)]
        (b/with-project-root (:absolute-path package)
          (let [basis (build/package-basis context package :pom {})
                pom-file (write-pom! context package {})
                pom (slurp pom-file)]
            (is (= :mvn
                   (get-in basis [:libs 'example/pkg-b :deps/manifest])))
            (is (= "4.3.1"
                   (get-in basis [:libs 'example/pkg-b :mvn/version])))
            (is (nil? (get-in basis [:libs 'example/pkg-b :local/root])))
            (is (true? (verify/verify-pom! context package pom)))))))))

(deftest install-hands-tools-build-a-package-relative-jar-path
  (with-workspace
    (fn [root]
      (let [{:keys [packages] :as context}
            (build/resolve-context! root)
            package (get packages 'example/pkg-a)
            install-opts (atom nil)]
        (with-redefs-fn
          {(ns-resolve 'collet.build 'resolve-context!)
           (fn [] context)
           (ns-resolve 'collet.build 'build-jar!)
           (fn [_ _ _]
             {:jar-file "pkg-a/target/pkg-a-0.2.8.jar"})
           #'b/install
           (fn [opts]
             (reset! install-opts opts))}
          #(build/install {:module :pkg-a}))
        (is (= "target/pkg-a-0.2.8.jar" (:jar-file @install-opts)))
        (is (= (:fqn package) (:lib @install-opts)))))))

(deftest package-specific-version-drives-scm-build-identity-and-jar-name
  (let [package {:fqn 'example/pkg-a
                 :version "2.3.4"
                 :tag "example/pkg-a@2.3.4"}
        context {:root "."
                 :project {:url "https://example.test/collet"
                           :license {:name "Test"
                                     :url "https://example.test/license"}
                           :scm {:url "https://example.test/collet"
                                 :connection "scm:git:https://example.test/collet.git"
                                 :developer-connection "scm:git:ssh://example.test/collet.git"}}}
        pom-data (ns-resolve 'collet.build 'pom-data)
        jar-file (ns-resolve 'collet.build 'jar-file)
        identity (ns-resolve 'collet.build 'build-identity)]
    (is (= "target/pkg-a-2.3.4.jar" (jar-file package)))
    (is (= "example/pkg-a@2.3.4"
           (->> (pom-data context package)
                (filter #(= :scm (first %)))
                first
                (filter #(and (vector? %) (= :tag (first %))))
                first
                second)))
    (is (= {:version "2.3.4" :revision "abc123"}
           (identity package "abc123")))))

(deftest install-rejects-a-nonpublishable-requested-package-before-its-dependencies
  (with-workspace
    (fn [root]
      (let [{:keys [packages] :as context}
            (build/resolve-context! root)
            built (atom [])
            installed (atom [])]
        (with-redefs-fn
          {(ns-resolve 'collet.build 'resolve-context!)
           (fn [] context)
           (ns-resolve 'collet.build 'build-jar!)
           (fn [_ package _]
             (swap! built conj (:fqn package)))
           #'b/install
           (fn [opts]
             (swap! installed conj (:lib opts)))}
          #(is (thrown-with-message?
                #"Package is not a publishable Maven artifact"
                (fn [] (build/install {:module :pkg-cli})))))
        (is (empty? @built))
        (is (empty? @installed))))))
