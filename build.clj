(ns build
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]))

(def lib 'pod-babashka-aws)
(def version (str/trim (slurp "resources/POD_BABASHKA_AWS_VERSION")))
(def class-dir "target/classes")

(def basis (b/create-basis {:project "deps.edn" :aliases [:native]}))
(def basis-unpacked (b/create-basis {:project "deps.edn" :aliases [:unpacked :native]}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn unzip [{:keys [basis verbose]}]
  (let [entries (-> basis :classpath keys)]
    (doseq [entry entries]
      (if (str/ends-with? entry ".jar")
        (do (when verbose (println "Unzipping" entry))
            (b/unzip {:zip-file entry
                      :target-dir class-dir}))
        (do
          (when verbose (println "Copying dir" entry))
          (b/copy-dir {:target-dir class-dir
                       :src-dirs [entry]}))))))

(defn build [_]
  (clean nil)
  (unzip {:basis basis})
  (b/compile-clj {:basis basis-unpacked
                  :ns-compile '[pod.babashka.aws]
                  :src-dirs ["src"]
                  :class-dir class-dir
                  :compile-opts {:direct-linking true}}))
