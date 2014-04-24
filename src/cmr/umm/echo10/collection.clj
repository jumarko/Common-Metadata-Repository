(ns cmr.umm.echo10.collection
  "Contains functions for parsing and generating the ECHO10 dialect."
  (:require [clojure.data.xml :as x]
            [clojure.java.io :as io]
            [cmr.common.xml :as cx]
            [cmr.umm.collection :as c]
            [cmr.umm.xml-schema-validator :as v]
            [cmr.umm.echo10.collection.temporal :as t]
            [cmr.umm.echo10.collection.product-specific-attribute :as psa]
            [cmr.umm.echo10.core])
  (:import cmr.umm.collection.UmmCollection))

(defn- xml-elem->Product
  "Returns a UMM Product from a parsed Collection Content XML structure"
  [collection-content]
  (c/map->Product {:short-name (cx/string-at-path collection-content [:ShortName])
                   :long-name (cx/string-at-path collection-content [:LongName])
                   :version-id (cx/string-at-path collection-content [:VersionId])}))

(defn- xml-elem->Collection
  "Returns a UMM Product from a parsed Collection XML structure"
  [xml-struct]
  (let [product (xml-elem->Product xml-struct)]
    (c/map->UmmCollection
      {:entry-id (str (:short-name product) "_" (:version-id product))
       :entry-title (cx/string-at-path xml-struct [:DataSetId])
       :product product
       :temporal-coverage (t/xml-elem->TemporalCoverage xml-struct)
       :product-specific-attributes (psa/xml-elem->ProductSpecificAttributes xml-struct)})))

(defn parse-collection
  "Parses ECHO10 XML into a UMM Collection record."
  [xml]
  (xml-elem->Collection (x/parse-str xml)))

(extend-protocol cmr.umm.echo10.core/UmmToEcho10Xml
  UmmCollection
  (umm->echo10-xml
    [collection]
    (let [{{:keys [short-name long-name version-id]} :product
           dataset-id :entry-title} collection]
      (x/emit-str
        (x/element :Collection {}
                   (x/element :ShortName {} short-name)
                   (x/element :VersionId {} version-id)
                   ;; required fields that are not implemented yet are stubbed out.
                   (x/element :InsertTime {} "1999-12-31T19:00:00Z")
                   (x/element :LastUpdate {} "1999-12-31T19:00:00Z")
                   (x/element :LongName {} long-name)
                   (x/element :DataSetId {} dataset-id)
                   (x/element :Description {} "stubbed")
                   (x/element :Orderable {} "true")
                   (x/element :Visible {} "true")
                   (t/generate-temporal (:temporal-coverage collection))
                   (psa/generate-product-specific-attributes
                     (:product-specific-attributes collection)))))))

(defn validate-xml
  "Validates the XML against the ECHO10 schema."
  [xml]
  (v/validate-xml (io/resource "schema/echo10/Collection.xsd") xml))
