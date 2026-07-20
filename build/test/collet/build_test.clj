(ns collet.build-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [clojure.tools.build.api :as b]
   [collet.build :as build]
   [k16.kmono.core.graph :as kmono.graph]))

(defn- write-edn! [path value]
  (fs/create-dirs (fs/parent path))
  (spit (str path) (pr-str value)))

(defn- thrown-with-message? [message-pattern f]
  (try
    (f)
    false
    (catch clojure.lang.ExceptionInfo error
      (boolean (re-find message-pattern (ex-message error))))))

(defn- with-workspace [f]
  (let [root (fs/create-temp-dir {:prefix "collet-build-test-"})]
    (try
      (write-edn!
       (fs/path root "deps.edn")
       {:kmono/workspace {:group 'example
                          :packages "pkg-*/*"}
        :collet/project {:version "1.2.3"}})
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
                          :kind :uberjar
                          :main 'example.cli
                          :outputs {:uberjar "target/cli.jar"}}})
      (f (str root))
      (finally
        (fs/delete-tree root)))))

(deftest resolves-workspace-graph-and-artifact-metadata
  (with-workspace
    (fn [root]
      (let [resolve-context (ns-resolve 'collet.build
                                        'resolve-workspace-context!)]
        (is (some? resolve-context)
            "the root build exposes its Kmono workspace resolver")
        (when resolve-context
          (let [{:keys [packages version]} (resolve-context root)]
            (is (= "1.2.3" version))
            (is (= #{'example/pkg-a 'example/pkg-b 'example/pkg-cli}
                   (set (keys packages))))
            (is (= #{'example/pkg-a}
                   (get-in packages ['example/pkg-b :depends-on])))
            (is (= [['example/pkg-a] ['example/pkg-b] ['example/pkg-cli]]
                   (kmono.graph/parallel-topo-sort packages)))
            (is (= :uberjar
                   (get-in packages ['example/pkg-b :artifact :kind])))
            (is (= "1.2.3"
                   (get-in packages ['example/pkg-a :version])))))))))

(deftest chooses-publishable-and-local-build-bases
  (with-workspace
    (fn [root]
      (let [resolve-context (ns-resolve 'collet.build
                                        'resolve-workspace-context!)
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
                  (is (= "1.2.3"
                         (get-in basis [:libs 'example/pkg-a :mvn/version])))))
              (testing "uberjars retain the local workspace dependency graph"
                (let [basis (package-basis context package :uberjar {})]
                  (is (= :deps
                         (get-in basis [:libs 'example/pkg-a :deps/manifest])))
                  (is (nil? (get-in basis
                                    [:libs 'example/pkg-a :mvn/version]))))))))))))

(deftest install-hands-tools-build-a-package-relative-jar-path
  (with-workspace
    (fn [root]
      (let [{:keys [packages] :as context}
            (build/resolve-workspace-context! root)
            package (get packages 'example/pkg-a)
            install-opts (atom nil)]
        (with-redefs-fn
          {(ns-resolve 'collet.build 'resolve-workspace-context!)
           (fn [] context)
           (ns-resolve 'collet.build 'build-jar!)
           (fn [_ _ _]
             {:jar-file "pkg-a/target/pkg-a-1.2.3.jar"})
           #'b/install
           (fn [opts]
             (reset! install-opts opts))}
          #(build/install {:module :pkg-a}))
        (is (= "target/pkg-a-1.2.3.jar" (:jar-file @install-opts)))
        (is (= (:fqn package) (:lib @install-opts)))))))

(deftest install-rejects-a-nonpublishable-requested-package-before-its-dependencies
  (with-workspace
    (fn [root]
      (let [{:keys [packages] :as context}
            (build/resolve-workspace-context! root)
            built (atom [])
            installed (atom [])]
        (with-redefs-fn
          {(ns-resolve 'collet.build 'resolve-workspace-context!)
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
