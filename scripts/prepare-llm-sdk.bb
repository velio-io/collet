#!/usr/bin/env bb
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.string :as str])


(def checkout ".dev-deps/clojure-llm-sdk")


(def revision "2f3c4260e0bc2c2a9bb55407db3bdd4579f5336e")


(def patch (str (fs/absolutize "patches/clojure-llm-sdk-0.6.4-lifecycle.patch")))


(when-not (fs/exists? checkout)
  (fs/create-dirs ".dev-deps")
  (shell "git" "clone"
         "--depth" "1"
         "--branch" "v0.6.4"
         "https://github.com/DeadMeme5441/clojure-llm-sdk.git" checkout))


(when-not (= revision (str/trim (:out (shell {:dir checkout :out :string} "git" "rev-parse" "HEAD"))))
  (throw (ex-info "Unexpected SDK checkout revision; preserve it and use a fresh checkout." {})))


(when-not (zero? (:exit (shell {:dir checkout :continue true :out :string :err :string}
                               "git"
                               "apply" "--reverse"
                               "--check" patch)))
  (shell {:dir checkout} "git" "apply" "--check" patch)
  (shell {:dir checkout} "git" "apply" patch))


(shell {:dir checkout} "clojure" "-M:test")


(shell {:dir checkout :extra-env {"RELEASE_VERSION" "0.6.4-collet.1-SNAPSHOT"}}
       "clojure"
       "-T:build"
       "install")


(println "Installed local SDK development snapshot; upstream publication still blocks release.")
