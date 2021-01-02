#!/usr/bin/env bb

(let [aws-libs (clojure.edn/read-string (slurp "https://raw.githubusercontent.com/cognitect-labs/aws-api/master/latest-releases.edn"))]
  (spit "aws-libs.edn" 
        (clojure.string/join "\n" 
                             (map (fn [[k {:keys [mvn/version]}]]
                                     (str k " " (pr-str {:mvn/version version}))) 
                                  (sort-by (comp name key) aws-libs)))))

