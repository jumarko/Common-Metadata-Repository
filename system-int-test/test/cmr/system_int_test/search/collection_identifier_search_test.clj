(ns cmr.system-int-test.search.collection-identifier-search-test
  "Tests searching for collections using basic collection identifiers"
  (:require [clojure.test :refer :all]
            [clojure.string :as s]
            [clojure.java.shell :as shell]
            [cmr.common.services.messages :as msg]
            [cmr.search.services.messages.common-messages :as smsg]
            [cmr.system-int-test.utils.url-helper :as url]
            [cmr.system-int-test.utils.ingest-util :as ingest]
            [cmr.system-int-test.utils.search-util :as search]
            [cmr.system-int-test.utils.index-util :as index]
            [cmr.system-int-test.data2.collection :as dc]
            [cmr.system-int-test.data2.core :as d]))


(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1" "provguid2" "PROV2"}))


(deftest identifier-search-test

  ;; Create 4 collections in each provider that are identical.
  ;; The first collection will have data:
  ;; {:entry-id "S1_V1", :entry-title "ET1", :short-name "S1", :version-id "V1"}
  (let [[c1-p1 c2-p1 c3-p1 c4-p1
         c1-p2 c2-p2 c3-p2 c4-p2] (for [p ["PROV1" "PROV2"]
                                        n (range 1 5)]
                                    (d/ingest p (dc/collection
                                                  {:short-name (str "S" n)
                                                   :version-id (str "V" n)
                                                   :entry-title (str "ET" n)})))
        all-prov1-colls [c1-p1 c2-p1 c3-p1 c4-p1]
        all-prov2-colls [c1-p2 c2-p2 c3-p2 c4-p2]
        all-colls (concat all-prov1-colls all-prov2-colls)]
    (index/wait-until-indexed)

    (testing "concept id"
      (are [items ids]
           (d/refs-match? items (search/find-refs :collection {:concept-id ids}))

           [c1-p1] (:concept-id c1-p1)
           [c1-p2] (:concept-id c1-p2)
           [c1-p1 c1-p2] [(:concept-id c1-p1) (:concept-id c1-p2)]
           [c1-p1] [(:concept-id c1-p1) "C2200-PROV1"]
           [c1-p1] [(:concept-id c1-p1) "FOO"]
           [] "FOO"))

    (testing "Concept id search using JSON query"
      (are [items search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} search))

           [c1-p1] {:concept-id (:concept-id c1-p1)}
           [c1-p2] {:concept-id (:concept-id c1-p2)}
           [c1-p1 c1-p2] {:or [{:concept-id (:concept-id c1-p1)}
                               {:concept-id (:concept-id c1-p2)}]}
           [c1-p1] {:or [{:concept-id (:concept-id c1-p1)}
                         {:concept-id "C2200-PROV1"}]}
           [c1-p1] {:or [{:concept-id (:concept-id c1-p1)}
                         {:concept-id "FOO"}]}
           [] {:concept-id "FOO"}))

    (testing "provider with parameters"
      (are [items p options]
           (let [params (merge {:provider p}
                               (when options
                                 {"options[provider]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           all-prov1-colls "PROV1" {}
           all-prov2-colls "PROV2" {}
           [] "PROV3" {}

           ;; Multiple values
           all-colls ["PROV1" "PROV2"] {}
           all-prov1-colls ["PROV1" "PROV3"] {}

           ;; Wildcards
           all-colls "PROV*" {:pattern true}
           [] "PROV*" {:pattern false}
           [] "PROV*" {}
           all-prov1-colls "*1" {:pattern true}
           all-prov1-colls "P?OV1" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           all-prov1-colls "pRoV1" {}
           all-prov1-colls "pRoV1" {:ignore-case true}
           [] "prov1" {:ignore-case false}))

    (testing "legacy catalog rest parameter name"
      (is (d/refs-match? all-prov1-colls (search/find-refs :collection {:provider-id "PROV1"}))))

    (testing "provider with aql"
      (are [items provider-ids options]
           (let [data-center-condition (merge {:dataCenterId provider-ids} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [] data-center-condition)))

           all-prov1-colls ["PROV1"] {}
           all-prov1-colls ["'PROV1'"] {}
           all-prov2-colls ["PROV2"] {}
           [] ["PROV3"] {}

           ;; Multiple values
           all-colls ["PROV1" "PROV2"] {}
           all-prov1-colls ["PROV1" "PROV3"] {}
           all-prov1-colls ["'PROV1'" "'PROV3'"] {}

           ;; Ignore case
           [] "pRoV1" {}
           all-prov1-colls "pRoV1" {:ignore-case true}
           [] "prov1" {:ignore-case false}))

    (testing "Provider search using JSON query"
      (are [items json-search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} json-search))

           all-prov1-colls {:provider "PROV1"}
           all-prov2-colls {:provider "PROV2"}
           [] {:provider "PROV3"}

           ;; Multiple values
           all-colls {:or [{:provider "PROV1"}
                           {:provider "PROV2"}]}
           all-prov1-colls {:or [{:provider "PROV1"}
                                 {:provider "PROV3"}]}

           ;; In combination with 'not'
           all-prov2-colls {:not {:provider "PROV1"}}
           all-prov1-colls {:not {:provider "PROV2"}}

           ;; Wildcards
           all-colls {:provider {:value "PROV*" :pattern true}}
           [] {:provider {:value "PROV*" :pattern false}}
           [] {:provider {:value "PROV*"}}
           all-prov1-colls {:provider {:value "*1" :pattern true}}
           all-prov1-colls {:provider {:value "P?OV1" :pattern true}}
           [] {:provider {:value "*Q*" :pattern true}}

           ;; Ignore case
           all-prov1-colls {:provider {:value "pRoV1"}}
           all-prov1-colls {:provider {:value "pRoV1" :ignore-case true}}
           [] {:provider {:value "prov1" :ignore-case false}}
           all-colls {:not {:provider {:value "prov1" :ignore-case false}}}))

    (testing "short name"
      (are [items sn options]
           (let [params (merge {:short-name sn}
                               (when options
                                 {"options[short-name]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p1 c1-p2] "S1" {}
           [] "S44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["S1" "S2"] {}
           [c1-p1 c1-p2] ["S1" "S44"] {}
           [c1-p1 c1-p2 c2-p1 c2-p2] ["S1" "S2"] {:and false}
           [] ["S1" "S2"] {:and true}

           ;; Wildcards
           all-colls "S*" {:pattern true}
           [] "S*" {:pattern false}
           [] "S*" {}
           [c1-p1 c1-p2] "*1" {:pattern true}
           [c1-p1 c1-p2] "?1" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           [c1-p1 c1-p2] "s1" {}
           [c1-p1 c1-p2] "s1" {:ignore-case true}
           [] "s1" {:ignore-case false}))

    (testing "shortName with aql"
      (are [items sn options]
           (let [condition (merge {:shortName sn} options)]
             (d/refs-match? items
                            (search/find-refs-with-aql :collection [condition])))

           [c1-p1 c1-p2] "S1" {}
           [] "S44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["S1" "S2"] {}
           [c1-p1 c1-p2] ["S1" "S44"] {}

           ;; Wildcards
           all-colls "S%" {:pattern true}
           [] "S%" {:pattern false}
           [] "S%" {}
           [c1-p1 c1-p2] "%1" {:pattern true}
           [c1-p1 c1-p2] "_1" {:pattern true}
           all-colls "S%" {:pattern true}
           [c1-p1 c1-p2] "_1" {:pattern true}
           [] "%Q%" {:pattern true}

           ;; Ignore case
           [] "s1" {}
           [c1-p1 c1-p2] "s1" {:ignore-case true}
           [] "s1" {:ignore-case false}))

    (testing "Short name using JSON query"
      (are [items search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} search))

           [c1-p1 c1-p2] {:short-name "S1"}
           [] {:short-name "S44"}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] {:or [{:short-name "S1"} {:short-name "S2"}]}
           [c1-p1 c1-p2] {:or [{:short-name "S1"} {:short-name "S44"}]}
           [] {:and [{:short-name "S1"} {:short-name "S2"}]}

           ;; Wildcards
           all-colls {:short-name {:value "S*" :pattern true}}
           [] {:short-name {:value "S*" :pattern false}}
           [] {:short-name {:value "S*"}}
           [c1-p1 c1-p2] {:short-name {:value "*1" :pattern true}}
           [c1-p1 c1-p2] {:short-name {:value "?1" :pattern true}}
           [] {:short-name {:value "*Q*" :pattern true}}

           ;; Ignore case
           [c1-p1 c1-p2] {:short-name {:value "s1"}}
           [c1-p1 c1-p2] {:short-name {:value "s1" :ignore-case true}}
           [] {:short-name {:value "s1" :ignore-case false}}))

    (testing "version"
      (are [items v options]
           (let [params (merge {:version v}
                               (when options
                                 {"options[version]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p1 c1-p2] "V1" {}
           [] "V44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["V1" "V2"] {}
           [c1-p1 c1-p2] ["V1" "V44"] {}

           ;; Wildcards
           all-colls "V*" {:pattern true}
           [] "V*" {:pattern false}
           [] "V*" {}
           [c1-p1 c1-p2] "*1" {:pattern true}
           [c1-p1 c1-p2] "?1" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           [c1-p1 c1-p2] "v1" {:ignore-case true}
           [] "v1" {:ignore-case false}))

    (testing "versionId with aql"
      (are [items v options]
           (let [condition (merge {:versionId v} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [condition])))

           [c1-p1 c1-p2] "V1" {}
           [] "V44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["V1" "V2"] {}
           [c1-p1 c1-p2] ["V1" "V44"] {}

           ;; Wildcards
           all-colls "V%" {:pattern true}
           [] "V%" {:pattern false}
           [] "V%" {}
           [c1-p1 c1-p2] "%1" {:pattern true}
           [c1-p1 c1-p2] "_1" {:pattern true}
           [] "%Q%" {:pattern true}

           ;; Ignore case
           [c1-p1 c1-p2] "v1" {:ignore-case true}
           [] "v1" {:ignore-case false}))

    (testing "Version using JSON query"
      (are [items search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} search))

           [c1-p1 c1-p2] {:version "V1"}
           [] {:version "V44"}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] {:or [{:version "V1"} {:version "V2"}]}
           [c1-p1 c1-p2] {:or [{:version "V1"} {:version "V44"}]}
           [] {:and [{:version "V1"} {:version "V2"}]}

           ;; Wildcards
           all-colls {:version {:value "V*" :pattern true}}
           [] {:version {:value "V*" :pattern false}}
           [] {:version {:value "V*"}}
           [c1-p1 c1-p2] {:version {:value "*1" :pattern true}}
           [c1-p1 c1-p2] {:version {:value "?1" :pattern true}}
           [] {:version {:value "*Q*" :pattern true}}

           ;; Ignore case
           [c1-p1 c1-p2] {:version {:value "v1" :ignore-case true}}
           [] {:version {:value "v1" :ignore-case false}}))

    (testing "Entry id"
      (are [items ids options]
           (let [params (merge {:entry-id ids}
                               (when options
                                 {"options[entry-id]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p1 c1-p2] "S1_V1" {}
           [] "S44_V44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["S1_V1" "S2_V2"] {}
           [c1-p1 c1-p2] ["S1_V1" "S44_V44"] {}
           [c1-p1 c1-p2 c2-p1 c2-p2] ["S1_V1" "S2_V2"] {:and false}
           [] ["S1_V1" "S2_V2"] {:and true}

           ;; Wildcards
           all-colls "S*_V*" {:pattern true}
           [] "S*_V*" {:pattern false}
           [] "S*_V*" {}
           [c1-p1 c1-p2] "*1" {:pattern true}
           [c1-p1 c1-p2] "S1_?1" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           [c1-p1 c1-p2] "S1_v1" {:ignore-case true}
           [] "S1_v1" {:ignore-case false}))

    (testing "Entry id search using JSON Query"
      (are [items json-search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} json-search))

           [c1-p1 c1-p2] {:entry-id "S1_V1"}
           [] {:entry-id "S44_V44"}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] {:or [{:entry-id "S1_V1"}
                                           {:entry-id "S2_V2"}]}
           [c1-p1 c1-p2] {:or [{:entry-id "S1_V1"}
                               {:entry-id "S44_V44"}]}
           [c1-p1 c1-p2 c2-p1 c2-p2] {:or [{:entry-id "S1_V1"}
                                           {:entry-id "S2_V2"}]}
           [] {:and [{:entry-id "S1_V1"}
                     {:entry-id "S2_V2"}]}

           ;; Not with multiple entry-ids
           [c3-p1 c3-p2 c4-p1 c4-p2] {:not {:or [{:entry-id "S2_V2"}
                                                 {:entry-id "S1_V1"}]}}

           ;; Not with multiple entry-ids and provider
           [c3-p1 c4-p1] {:not {:or [{:entry-id "S2_V2"}
                                     {:entry-id "S1_V1"}
                                     {:provider "PROV2"}]}}

           ;; Wildcards
           all-colls {:entry-id {:value "S*_V*" :pattern true}}
           [] {:entry-id {:value "S*_V*" :pattern false}}
           [] {:entry-id {:value "S*_V*"}}
           [c1-p1 c1-p2] {:entry-id {:value "*1" :pattern true}}
           [c1-p1 c1-p2] {:entry-id {:value "S1_?1" :pattern true}}
           [] {:entry-id {:value "*Q*" :pattern true}}

           ;; Ignore case
           [c1-p1 c1-p2] {:entry-id {:value "S1_v1" :ignore-case true}}
           [] {:entry-id {:value "S1_v1" :ignore-case false}}))

    (testing "Entry title"
      (are [items v options]
           (let [params (merge {:entry-title v}
                               (when options
                                 {"options[entry-title]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p1 c1-p2] "ET1" {}
           [] "ET44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["ET1" "ET2"] {}
           [c1-p1 c1-p2] ["ET1" "ET44"] {}
           [c1-p1 c1-p2 c2-p1 c2-p2] ["ET1" "ET2"] {:and false}
           [] ["ET1" "ET2"] {:and true}

           ;; Wildcards
           all-colls "ET*" {:pattern true}
           [] "ET*" {:pattern false}
           [] "ET*" {}
           [c1-p1 c1-p2] "*1" {:pattern true}
           [c1-p1 c1-p2] "?T1" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           [c1-p1 c1-p2] "et1" {:ignore-case true}
           [] "et1" {:ignore-case false})

      (is (d/refs-match?
            [c1-p1 c1-p2]
            (search/find-refs :collection {:dataset-id "ET1"}))
          "dataset_id should be an alias for entry title."))

    (testing "Entry title search using JSON Query"
      (are [items json-search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} json-search))

           [c1-p1 c1-p2] {:entry-title "ET1"}
           [] {:entry-title "ET44"}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] {:or [{:entry-title "ET1"}
                                           {:entry-title "ET2"}]}
           [c1-p1 c1-p2] {:or [{:entry-title "ET1"}
                               {:entry-title "ET44"}]}
           [c1-p1 c1-p2 c2-p1 c2-p2] {:or [{:entry-title "ET1"}
                                           {:entry-title "ET2"}]}
           [] {:and [{:entry-title "ET1"}
                     {:entry-title "ET2"}]}

           ;; Wildcards
           all-colls {:entry-title {:value "ET*" :pattern true}}
           [] {:entry-title {:value "ET*" :pattern false}}
           [] {:entry-title {:value "ET*"}}
           [c1-p1 c1-p2] {:entry-title {:value "*1" :pattern true}}
           [c1-p1 c1-p2] {:entry-title {:value "?T1" :pattern true}}
           [] {:entry-title {:value "*Q*" :pattern true}}

           ;; Ignore case
           [c1-p1 c1-p2] {:entry-title {:value "et1" :ignore-case true}}
           [] {:entry-title {:value "et1" :ignore-case false}}))

    (testing "dataSetId with aql"
      (are [items v options]
           (let [condition (merge {:dataSetId v} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [condition])))

           [c1-p1 c1-p2] "ET1" {}
           [] "ET44" {}
           ;; Multiple values
           [c1-p1 c1-p2 c2-p1 c2-p2] ["ET1" "ET2"] {}
           [c1-p1 c1-p2] ["ET1" "ET44"] {}

           ;; Wildcards
           all-colls "ET%" {:pattern true}
           [] "ET%" {:pattern false}
           [] "ET%" {}
           [c1-p1 c1-p2] "%1" {:pattern true}
           [c1-p1 c1-p2] "_T1" {:pattern true}
           [] "%Q%" {:pattern true}

           ;; Ignore case
           [c1-p1 c1-p2] "et1" {:ignore-case true}
           [] "et1" {:ignore-case false}))

    (testing "unsupported parameter"
      (is (= {:status 400,
              :errors ["Parameter [unsupported] was not recognized."]}
             (search/find-refs :collection {:unsupported "dummy"})))
      (is (= {:status 400,
              :errors ["Parameter [unsupported] with option was not recognized."]}
             (search/find-refs :collection {"options[unsupported][ignore-case]" true})))
      (is (= {:status 400,
              :errors [(smsg/invalid-opt-for-param :entry-title :unsupported)]}
             (search/find-refs
               :collection
               {:entry-title "dummy" "options[entry-title][unsupported]" "unsupported"}))))

    (testing "empty parameters are ignored"
      (is (d/refs-match? [c1-p1] (search/find-refs :collection {:concept-id (:concept-id c1-p1)
                                                                :short-name ""
                                                                :version "    "
                                                                :entry-title "  \n \t"}))))))

;; Create 2 collection sets of which only 1 set has processing-level-id
(deftest processing-level-search-test
  (let [[c1-p1 c2-p1 c3-p1 c4-p1] (for [n (range 1 5)]
                                    (d/ingest "PROV1" (dc/collection {})))
        ;; include processing level id
        [c1-p2 c2-p2 c3-p2 c4-p2] (for [n (range 1 5)]
                                    (d/ingest "PROV2" (dc/collection {:processing-level-id (str n "B")})))
        all-prov2-colls [c1-p2 c2-p2 c3-p2 c4-p2]]
    (index/wait-until-indexed)
    (testing "processing level search"
      (are [items id options]
           (let [params (merge {:processing-level-id id}
                               (when options
                                 {"options[processing-level-id]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p2] "1B" {}
           [] "1C" {}
           ;; Multiple values
           [c1-p2 c2-p2 c3-p2] ["1B" "2B" "3B"] {}
           [c4-p2] ["4B" "4C"] {}

           ;; Wildcards
           all-prov2-colls "*B" {:pattern true}
           [] "B*" {:pattern false}
           [] "B*" {}
           all-prov2-colls "?B" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           [c2-p2] "2b" {:ignore-case true}
           [] "2b" {:ignore-case false}))

    (testing "search with legacy processing-level"
      (is (d/refs-match? [c1-p2 c2-p2 c3-p2]
                         (search/find-refs :collection {:processing-level ["1B" "2B" "3B"]}))))

    (testing "processing level search with aql"
      (are [items id options]
           (let [condition (merge {:processingLevel id} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [condition])))

           [c1-p2] "1B" {}
           [] "1C" {}
           ;; Multiple values
           [c1-p2 c2-p2 c3-p2] ["1B" "2B" "3B"] {}
           [c4-p2] ["4B" "4C"] {}

           ;; Wildcards
           all-prov2-colls "%B" {:pattern true}
           [] "B%" {:pattern false}
           [] "B%" {}
           all-prov2-colls "_B" {:pattern true}
           [] "%Q%" {:pattern true}

           ;; Ignore case
           [c2-p2] "2b" {:ignore-case true}
           [] "2b" {:ignore-case false}))

    (testing "Processing level id search using JSON Query"
      (are [items search]
           (d/refs-match? items (search/find-refs-with-json-query :collection {} search))

           [c1-p2] {:processing-level-id "1B"}
           [] {:processing-level-id "1C"}
           ;; Multiple values
           [c1-p2 c2-p2 c3-p2] {:or [{:processing-level-id "1B"}
                                     {:processing-level-id "2B"}
                                     {:processing-level-id "3B"}]}
           [c4-p2] {:or [{:processing-level-id "4B"} {:processing-level-id "4C"}]}
           [c1-p1 c2-p1 c3-p1 c4-p1 c4-p2] {:not {:or [{:processing-level-id "1B"}
                                                       {:processing-level-id "2B"}
                                                       {:processing-level-id "3B"}]}}

           ;; Wildcards
           all-prov2-colls {:processing-level-id {:value "*B" :pattern true}}
           [] {:processing-level-id {:value "B*" :pattern false}}
           [] {:processing-level-id {:value "B*"}}
           all-prov2-colls {:processing-level-id {:value "?B" :pattern true}}
           [] {:processing-level-id {:value "*Q*" :pattern true}}

           ;; Ignore case
           [c2-p2] {:processing-level-id {:value "2b" :ignore-case true}}
           [] {:processing-level-id {:value "2b" :ignore-case false}}))))

;; Find collections by echo_collection_id and concept_id params
(deftest echo-coll-id-search-test
  (let [[c1-p1 c2-p1 c3-p1 c4-p1
         c1-p2 c2-p2 c3-p2 c4-p2] (for [p ["PROV1" "PROV2"]
                                        n (range 1 5)]
                                    (d/ingest p (dc/collection {})))
        c1-p1-cid (get-in c1-p1 [:concept-id])
        c2-p1-cid (get-in c2-p1 [:concept-id])
        c3-p2-cid (get-in c3-p2 [:concept-id])
        c4-p2-cid (get-in c4-p2 [:concept-id])
        dummy-cid "D1000000004-PROV2"
        all-prov1-colls [c1-p1 c2-p1 c3-p1 c4-p1]
        all-prov2-colls [c1-p2 c2-p2 c3-p2 c4-p2]
        all-colls (concat all-prov1-colls all-prov2-colls)]
    (index/wait-until-indexed)
    (testing "echo collection id search"
      (are [items cid options]
           (let [params (merge {:echo_collection_id cid}
                               (when options
                                 {"options[echo_collection_id]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p1] c1-p1-cid {}
           [c3-p2] c3-p2-cid {}
           [] dummy-cid {}
           ;; Multiple values
           [c1-p1 c2-p1 c3-p2 c4-p2] [c1-p1-cid c2-p1-cid c3-p2-cid c4-p2-cid dummy-cid] {}))
    (testing "echo collection id search - disallow ignore case"
      (is (= {:status 400
              :errors [(smsg/invalid-opt-for-param :concept-id :ignore-case)]}
             (search/find-refs :granule {:echo_collection_id c2-p1-cid "options[echo_collection_id]" {:ignore_case true}}))))
    (testing "Search with wildcards in echo_collection_id param not supported."
      (is (= {:status 400
              :errors [(smsg/invalid-opt-for-param :concept-id :pattern)]}
             (search/find-refs :granule {:echo_collection_id "C*" "options[echo_collection_id]" {:pattern true}}))))
    (testing "concept id search"
      ;; skipping some test conditions because concept_id search is similar in behavior to above echo_collection_id search
      (are [items cid options]
           (let [params (merge {:concept_id cid}
                               (when options
                                 {"options[concept_id]" options}))]
             (d/refs-match? items (search/find-refs :collection params)))

           [c1-p1] c1-p1-cid {}
           [c3-p2] c3-p2-cid {}
           [] dummy-cid {}
           ;; Multiple values
           [c1-p1 c2-p1 c3-p2 c4-p2] [c1-p1-cid c2-p1-cid c3-p2-cid c4-p2-cid dummy-cid] {}
           [] [c1-p1-cid  c3-p2-cid] {:and true}))
    (testing "Search with wildcards in concept_id param not supported."
      (is (= {:status 400
              :errors [(smsg/invalid-opt-for-param :concept-id :pattern)]}
             (search/find-refs :granule {:concept_id "C*" "options[concept_id]" {:pattern true}}))))

    (testing "echo collection id search with aql"
      (are [items cid options]
           (let [condition (merge {:ECHOCollectionID cid} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [condition])))

           [c1-p1] c1-p1-cid {}
           [c3-p2] c3-p2-cid {}
           [] dummy-cid {}
           ;; Multiple values
           [c1-p1 c2-p1 c3-p2 c4-p2] [c1-p1-cid c2-p1-cid c3-p2-cid c4-p2-cid dummy-cid] {}))))

(deftest dif-entry-id-search-test
  (let [coll1 (d/ingest "PROV1" (dc/collection {:short-name "S1"
                                                :version-id "V1"}))
        coll2 (d/ingest "PROV1" (dc/collection-dif {:entry-id "S2"}) {:format :dif})
        coll3 (d/ingest "PROV2" (dc/collection {:associated-difs ["S3"]}))
        coll4 (d/ingest "PROV2" (dc/collection {:associated-difs ["SL4" "DIF-1"]}))
        coll5 (d/ingest "PROV2" (dc/collection-dif {:entry-id "T2"}) {:format :dif})]
    (index/wait-until-indexed)
    (testing "dif entry id search"
      (are [items id options]
           (d/refs-match? items (search/find-refs :collection {:dif-entry-id id
                                                               "options[dif-entry-id]" options}))

           [coll1] "S1_V1" {}
           [coll2] "S2" {}
           [coll3] "S3" {}
           [] "S1" {}
           ;; Multiple values
           [coll2 coll3] ["S2" "S3"] {}
           [coll4] ["SL4" "DIF-1"] {}
           [coll2 coll3] ["S2" "S3"] {:and false}
           [] ["S2" "S3"] {:and true}

           ;; Wildcards
           [coll1 coll2 coll3 coll4] "S*" {:pattern true}
           [] "S*" {:pattern false}
           [] "S*" {}
           [coll2 coll3] "S?" {:pattern true}
           [] "*Q*" {:pattern true}

           ;; Ignore case
           [coll2] "s2" {:ignore-case true}
           [] "s2" {:ignore-case false}))

    (testing "options on entry-id and dif-entry-id are not interfering with each other."
      (is (d/refs-match? []
                         (search/find-refs :collection
                                           {:entry-id "s2"
                                            "options[entry-id][ignore-case]" "false"
                                            :dif-entry-id "s2"
                                            "options[dif-entry-id][ignore-case]" "true"})))
      (is (d/refs-match? []
                         (search/find-refs :collection
                                           {:entry-id "s2"
                                            "options[entry-id][ignore-case]" "true"
                                            :dif-entry-id "s2"
                                            "options[dif-entry-id][ignore-case]" "false"})))
      (is (d/refs-match? []
                         (search/find-refs :collection
                                           {:entry-id "s2"
                                            "options[entry-id][ignore-case]" "false"
                                            :dif-entry-id "s2"
                                            "options[dif-entry-id][ignore-case]" "false"})))
      (is (d/refs-match? [coll2]
                         (search/find-refs :collection
                                           {:entry-id "s2"
                                            "options[entry-id][ignore-case]" "true"
                                            :dif-entry-id "s2"
                                            "options[dif-entry-id][ignore-case]" "true"})))
      (is (d/refs-match? [coll2]
                         (search/find-refs :collection
                                           {:entry-id "s2"
                                            "options[entry-id][ignore-case]" "true"
                                            :dif-entry-id "s2"
                                            "options[dif-entry-id][ignore-case]" "true"})))
      (is (d/refs-match? [coll2]
                         (search/find-refs :collection
                                           {:entry-id "s2"
                                            "options[entry-id][ignore-case]" "true"
                                            :dif-entry-id "S*"
                                            "options[dif-entry-id][pattern]" "true"}))))

    (testing "dif entry id search with aql"
      (are [items id options]
           (let [condition (merge {:difEntryId id} options)]
             (d/refs-match? items (search/find-refs-with-aql :collection [condition])))

           [coll1] "S1_V1" {}
           [coll2] "S2" {}
           [coll3] "S3" {}
           [] "S1" {}
           ;; Multiple values
           [coll2 coll3] ["S2" "S3"] {}
           [coll4] ["SL4" "DIF-1"] {}

           ;; Wildcards
           [coll1 coll2 coll3 coll4] "S%" {:pattern true}
           [] "S%" {:pattern false}
           [] "S%" {}
           [coll2 coll3] "S_" {:pattern true}
           [] "%Q%" {:pattern true}

           ;; Ignore case
           [coll2] "s2" {:ignore-case true}
           [] "s2" {:ignore-case false}))))


(deftest search-with-slashes-in-dataset-id
  (let [coll1 (d/ingest "PROV1" (dc/collection {:entry-title "Dataset1"}))
        coll2 (d/ingest "PROV1" (dc/collection {:entry-title "Dataset/With/Slashes"}))
        coll3 (d/ingest "PROV1" (dc/collection {:entry-title "Dataset3"}))
        coll4 (d/ingest "PROV1" (dc/collection {:entry-title "Dataset/With/More/Slashes"}))
        coll5 (d/ingest "PROV1" (dc/collection {}))]

    (index/wait-until-indexed)

    (testing "search for dataset with slashes"
      (are [dataset-id items] (d/refs-match? items (search/find-refs :collection {:dataset-id dataset-id}))
           "Dataset/With/Slashes" [coll2]
           "BLAH" []))))

(deftest search-with-invalid-escaped-param
  (testing "CMR-1192: Searching with invalid escaped character returns internal error"
    ;; I am not able to find an easy way to submit a http request with an invalid url to bypass the
    ;; client side checking. So we do it through curl on the command line.
    ;; This depends on curl being installed on the test machine.
    ;; Do not use this unless absolutely necessary.
    (let [{:keys [out]} (shell/sh "curl" "--silent" "-i"
                                  (str (url/search-url :collection) "?entry-title\\[\\]=%"))]
      (is (re-find #"(?s)400 Bad Request.*Invalid URL encoding: Incomplete trailing escape \(%\) pattern.*" out)))))
