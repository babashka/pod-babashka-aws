#!/usr/bin/env bb

(def latest-releases-url "https://raw.githubusercontent.com/cognitect-labs/aws-api/master/latest-releases.edn")

(let [aws-libs (sort-by (comp name key)
                        (-> (clojure.edn/read-string (slurp latest-releases-url))
                            (dissoc 'com.cognitect.aws/api
                                    'com.cognitect.aws/endpoints)))]
  (spit "aws-libs.edn"
        (clojure.string/join "\n"
                             (map (fn [[k {:keys [mvn/version]}]]
                                    (str k " " (pr-str {:mvn/version version})))
                                  aws-libs)))
  (spit "resources/aws-services.edn" (with-out-str (clojure.pprint/pprint (mapv (comp keyword name) (keys aws-libs))))))
