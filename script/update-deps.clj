#!/usr/bin/env bb

(def latest-releases-url "https://raw.githubusercontent.com/cognitect-labs/aws-api/master/latest-releases.edn")

(require '[clojure.edn :as edn]
         '[clojure.pprint :refer [cl-format]])

(def latest-releases
  (edn/read-string (slurp latest-releases-url)))

(defn ^:private leftpad
  "If S is shorter than LEN, pad it with CH on the left."
  ([s len]
   (leftpad s len " "))
  ([s len ch]
   (cl-format nil
              (str "~" len ",'" ch "d")
              (str s))))

(let [edn-template (slurp "deps.template.edn")
      deps-lines (->> latest-releases
                      (map (fn [[k v]]
                             (let [{:keys [aws/serviceFullName
                                           mvn/version]} v]
                               [k version serviceFullName]))))
      max-column-size (->> deps-lines
                           (map (fn [[k v n]]
                                  (-> k str count)))
                           (apply max))
      available-services (->> latest-releases
                              (map (comp keyword name key))
                              (sort)
                              (vec))]
  (spit "resources/aws-services.edn" (with-out-str (clojure.pprint/pprint available-services)))

  (as-> edn-template  $
    (str/replace $ "{{latest-releases.edn}}"
                 (->> (for [[api ver svc-name] (sort-by first deps-lines)
                            :let [gap (- max-column-size (-> api str count))]]
                        (format "        %s %s {:mvn/version \"%s\" :aws/serviceFullName \"%s\"}"
                                api
                                (leftpad " " gap)
                                ver
                                (if svc-name svc-name "")))
                      (str/join "\n")
                      str/triml))
    (spit "deps.edn" $)))
