#!/usr/bin/env bb

(def latest-releases-url "https://raw.githubusercontent.com/cognitect-labs/aws-api/master/latest-releases.edn")

(require '[clojure.edn :as edn])
(def latest-releases
  (edn/read-string (slurp latest-releases-url)))

(require '[clojure.string :as str])

(as-> (slurp "deps.template.edn") $
  (str/replace $ "{{latest-releases.edn}}"
               (->> latest-releases
                    (map (fn [[k v]]
                           (str "        " k " " v)))
                    sort
                    (str/join "\n")
                    str/triml))
  ;; (edn/read-string $)
  (spit "deps.edn" $))
