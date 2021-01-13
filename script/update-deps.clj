#!/usr/bin/env bb

(def latest-releases-url "https://raw.githubusercontent.com/cognitect-labs/aws-api/master/latest-releases.edn")

(require '[clojure.edn :as edn])
(def latest-releases
  (edn/read-string (slurp latest-releases-url)))

(require '[clojure.string :as str])

(as-> (slurp "deps.template.edn") $
  (str/replace $ "{{latest-releases.edn}}"
               (str/triml
                (str/join "\n"
                          (map (fn [[k v]]
                                 (str "        " k " " v))
                               latest-releases))))
  ;; (edn/read-string $)
  (spit "deps.edn" $))
