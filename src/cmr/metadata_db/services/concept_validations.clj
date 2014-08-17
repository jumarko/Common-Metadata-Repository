(ns cmr.metadata-db.services.concept-validations
  (:require [cmr.metadata-db.services.messages :as msg]
            [cmr.common.concepts :as cc]
            [clojure.set :as set]
            [cmr.common.services.errors :as errors]
            [cmr.common.util :as util]))

(defn concept-type-missing-validation
  [concept]
  (when-not (:concept-type concept)
    [(msg/missing-concept-type)]))

(defn provider-id-missing-validation
  [concept]
  (when-not (:provider-id concept)
    [(msg/missing-provider-id)]))

(defn native-id-missing-validation
  [concept]
  (when-not (:native-id concept)
    [(msg/missing-native-id)]))

(def concept-type->required-extra-fields
  "A map of concept type to the required extra fields"
  {:collection #{:short-name :version-id :entry-title}
   :granule #{:parent-collection-id}})

(defn extra-fields-missing-validation
  "Validates that the concept is provided with extra fields and that all of them are present and not nil."
  [concept]
  (if-let [extra-fields (:extra-fields concept)]
    (map #(msg/missing-extra-field %)
         (set/difference (concept-type->required-extra-fields (:concept-type concept))
                         (set (keys extra-fields))))
    [(msg/missing-extra-fields)]))

(defn nil-fields-validation
  "Validates that none of the fields are nil."
  [concept]
  (reduce-kv (fn [acc field value]
               (if (nil? value)
                 (conj acc (msg/nil-field field))
                 acc))
             []
             concept))

(defn nil-extra-fields-validation
  "Validates that none of the extra fields are nil except delete-time."
  [concept]
  (nil-fields-validation (dissoc (:extra-fields concept) :delete-time)))

(defn concept-id-validation
  [concept]
  (when-let [concept-id (:concept-id concept)]
    (cc/concept-id-validation concept-id)))

(defn concept-id-match-fields-validation
  [concept]
  (when-let [concept-id (:concept-id concept)]
    (when-not (cc/concept-id-validation concept-id)
      (let [{:keys [concept-type provider-id]} (cc/parse-concept-id concept-id)]
        (when-not (and (= concept-type (:concept-type concept))
                       (= provider-id (:provider-id concept)))
          [(msg/invalid-concept-id concept-id (:provider-id concept) (:concept-type concept))])))))

(def concept-validation
  "Validates a concept and returns a list of errors"
  (util/compose-validations [concept-type-missing-validation
                             provider-id-missing-validation
                             native-id-missing-validation
                             concept-id-validation
                             extra-fields-missing-validation
                             nil-fields-validation
                             nil-extra-fields-validation
                             concept-id-match-fields-validation]))

(def validate-concept
  "Validates a concept. Throws an error if invalid."
  (util/build-validator :invalid-data concept-validation))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validations for concept find

(defn concept-types-supported
  [{:keys [concept-type] :as params}]
  (when-not (= :collection concept-type)
    [(msg/find-not-supported concept-type (keys (dissoc params :concept-type)))]))

(def supported-parameter-combinations
  #{#{:short-name :provider-id :version-id}
    #{:entry-title :provider-id}
    ;; Metadata db needs to support retrieving all collections in a provider for reindexing.
    #{:provider-id}})

(defn supported-parameter-combinations-validation
  [{:keys [concept-type] :as params}]
  (let [params (dissoc params :concept-type)]
    (when-not (supported-parameter-combinations
                (set (keys params)))
      [(msg/find-not-supported concept-type (keys params))])))

(def find-params-validation
  "Validates parameters for finding a concept"
  (util/compose-validations [concept-types-supported
                             supported-parameter-combinations-validation]))

(def validate-find-params
  "Validates find parameters. Throws an eror if invalid."
  (util/build-validator :bad-request find-params-validation))
