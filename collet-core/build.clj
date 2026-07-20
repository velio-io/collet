(ns build
  (:refer-clojure :exclude [test])
  (:require [collet.build :as shared]))

(def module :collet-core)

(defn clean [opts] (shared/clean module opts))
(defn pom [opts] (shared/pom module opts))
(defn jar [opts] (shared/jar module opts))
(defn install [opts] (shared/install module opts))
