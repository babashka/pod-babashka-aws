(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'pod-babashka-aws)
(def version (str/trim (slurp "resources/POD_BABASHKA_AWS_VERSION")))
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn" :aliases [:native]}))
(def uber-file "target/pod-babashka-aws.jar")

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :ns-compile '[pod.babashka.aws]
                  :class-dir class-dir
                  :compile-opts {:direct-linking true}})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis}))
