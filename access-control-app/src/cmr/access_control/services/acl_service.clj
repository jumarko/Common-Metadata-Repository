(ns cmr.access-control.services.acl-service
  (:require [clojure.string :as str]
            [cmr.access-control.services.acl-service-messages :as msg]
            [cmr.common.log :refer [info]]
            [cmr.common.util :as u]
            [cmr.common.mime-types :as mt]
            [cmr.common.services.errors :as errors]
            [cmr.transmit.echo.tokens :as tokens]
            [cmr.transmit.metadata-db2 :as mdb]
            [cmr.common-app.services.search :as cs]
            [cmr.common-app.services.search.params :as cp]
            [cmr.common-app.services.search.parameter-validation :as cpv]
            [cmr.common-app.services.search.query-model :as common-qm]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [cmr.common.concepts :as concepts]))

(def acl-provider-id
  "The provider ID for all ACLs. Since ACLs are not owned by individual
  providers, they fall under the CMR system provider ID."
  "CMR")

(defn acl-native-id
  "Returns a native ID to uniquely identify a given ACL."
  [acl]
  (str/lower-case
    (let [{:keys [system-identity provider-identity single-instance-identity catalog-item-identity]} acl]
      (cond
        system-identity          (str "system:" (:target system-identity))
        single-instance-identity (format "single-instance:%s:%s"
                                         (:target-id single-instance-identity)
                                         (:target single-instance-identity))
        provider-identity        (format "provider:%s:%s"
                                         (:provider-id provider-identity)
                                         (:target provider-identity))
        catalog-item-identity    (format "catalog-item:%s:%s"
                                         (:provider-id catalog-item-identity)
                                         (:name catalog-item-identity))
        :else                    (errors/throw-service-error
                                   :bad-request "malformed ACL")))))

(defn- fetch-acl-concept
  "Fetches the latest version of ACL concept by concept id. Handles unknown concept ids by
  throwing a service error."
  [context concept-id]
  (let [{:keys [concept-type provider-id]} (concepts/parse-concept-id concept-id)]
    (when (not= :acl concept-type)
      (errors/throw-service-error :bad-request (msg/bad-acl-concept-id concept-id))))

  (if-let [concept (mdb/get-latest-concept context concept-id false)]
    (if (:deleted concept)
      (errors/throw-service-error :not-found (msg/acl-deleted concept-id))
      concept)
    (errors/throw-service-error :not-found (msg/acl-does-not-exist concept-id))))

(defn save-updated-acl-concept
  [context concept acl]
  (errors/throw-service-error :bad-request "Sorry, you can't update this ACL yet"))

(defn acl->new-concept
  "Returns a concept map appropriate for saving the given ACL record in Metadata DB."
  [context acl]
  {:concept-type :acl
   :revision-id 1
   :metadata (pr-str acl)
   :format mt/edn
   :provider-id acl-provider-id
   :native-id (acl-native-id acl)
   :user-id (tokens/get-user-id context (:token context))})

(defn create-acl
  "Save a new ACL to Metadata DB. Returns map with concept and revision id of created acl."
  [context acl]
  ;; Check if the acl already exists - lower case the name to prevent duplicates.(CMR-2466)
  (let [native-id (acl-native-id acl)]
    (if-let [concept-id (mdb/get-concept-id context :acl acl-provider-id (acl-native-id acl))]

      ;; The acl exists. Check if its latest revision is a tombstone
      (let [concept (mdb/get-latest-concept context concept-id)]
        (if (:deleted concept)
          ;; The acl exists but was previously deleted.
          (save-updated-acl-concept context concept acl)

          ;; The acl exists and was not deleted. Reject this.
          (errors/throw-service-error :conflict "ACL already exists")))

      ;; The acl doesn't exist
      (mdb/save-concept context (acl->new-concept context acl)))))

(defn update-acl
  "Update the ACL with the given concept-id in Metadata DB. Returns map with concept and revision id of updated acl."
  [context concept-id acl]
  ;; This fetch acl call also validates if the ACL with the concept id does not exist or is deleted
  (let [existing-concept (fetch-acl-concept context concept-id)
        existing-native-id (:native-id existing-concept)
        native-id (acl-native-id acl)]
    (if (= existing-native-id native-id)
      (let [existing-legacy-guid (:legacy-guid (edn/read-string (:metadata existing-concept)))
            legacy-guid (:legacy-guid acl)]
        (if (= existing-legacy-guid legacy-guid)
          (mdb/save-concept context (dissoc (acl->new-concept context acl) :revision-id))
          (errors/throw-service-error
            :invalid-data (format "ACL legacy guid cannot be updated, was [%s] and now [%s]"
                                  existing-legacy-guid legacy-guid))))
      ;; We want to allow ACL update to change the unique identifiers that made up the native id
      ;; of an ACL in the future as documented in CMR-3163
      (errors/throw-service-error
        :invalid-data (format "ACL native id cannot be updated, was [%s] and now [%s]"
                              existing-native-id native-id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Search functions

(defmethod cpv/params-config :acl
  [_]
  (cpv/merge-params-config
    cpv/basic-params-config
    {:single-value #{}
     :multiple-value #{:permitted-group}
     :always-case-sensitive #{}
     :disallow-pattern #{}
     :allow-or #{}}))

(defmethod cpv/valid-parameter-options :acl
  [_]
  {:permitted-group cpv/string-param-options})

(defn- valid-permitted-group?
  "Returns true if the given permitted group is valid, i.e. guest, registered or conforms to
  access group id format."
  [group]
  (or (.equalsIgnoreCase "guest" group)
      (.equalsIgnoreCase "registered" group)
      (some? (re-find #"[Aa][Gg]\d+-.+" group))))

(defn- permitted-group-validation
  "Validates permitted group parameters."
  [context params]
  (let [permitted-groups (:permitted-group params)
        permitted-groups (if (sequential? permitted-groups)
                           permitted-groups
                           (when permitted-groups [permitted-groups]))]
    (when-let [invalid-groups (seq (remove valid-permitted-group? permitted-groups))]
      [(format "Parameter permitted_group has invalid values [%s]. Only 'guest', 'registered' or a group concept id can be specified."
               (str/join ", " invalid-groups))])))

(defn validate-acl-search-params
  "Validates the parameters for an ACL search. Returns the parameters or throws an error if invalid."
  [context params]
  (let [[safe-params type-errors] (cpv/apply-type-validations
                                    params
                                    [(partial cpv/validate-map [:options])
                                     (partial cpv/validate-map [:options :permitted-group])])]
    (cpv/validate-parameters
      :acl safe-params
      (concat cpv/common-validations
              [permitted-group-validation])
      type-errors))
  params)

(defmethod common-qm/default-sort-keys :acl
  [_]
  [{:field :display-name :order :asc}])

(defmethod cp/param-mappings :acl
  [_]
  {:permitted-group :string})

(defn search-for-acls
  [context params]
  (let [[query-creation-time query] (u/time-execution
                                      (->> params
                                           cp/sanitize-params
                                           (validate-acl-search-params :acl)
                                           (cp/parse-parameter-query :acl)))
        [find-concepts-time results] (u/time-execution
                                       (cs/find-concepts context :acl query))
        total-took (+ query-creation-time find-concepts-time)]
    (info (format "Found %d acls in %d ms in format %s with params %s."
                  (:hits results) total-took (:result-format query) (pr-str params)))
    (assoc results :took total-took)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Member functions

(defn get-acl
  "Returns the parsed metadata of the latest revision of the ACL concept by id."
  [context concept-id]
  (edn/read-string (:metadata (fetch-acl-concept context concept-id))))