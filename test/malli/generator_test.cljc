(ns malli.generator-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.test.check.properties :refer [for-all]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [malli.json-schema-test :as json-schema-test]
            [malli.generator :as mg]
            [malli.core :as m]
            [malli.util :as mu]))

(deftest generator-test
  (doseq [[?schema] json-schema-test/expectations
          ;; cljs doesn't have a regex generator :(
          #?@(:cljs [:when (not= (m/type ?schema) :re)])]
    (testing (m/form ?schema)
      (testing "generate"
        (is (= (mg/generate ?schema {:seed 123})
               (mg/generate ?schema {:seed 123})))
        (is (= (mg/generate ?schema {:seed 123, :size 10})
               (mg/generate ?schema {:seed 123, :size 10})))
        (is (m/validate ?schema (mg/generate ?schema {:seed 123}))))
      (testing "sample"
        (is (= (mg/sample ?schema {:seed 123})
               (mg/sample ?schema {:seed 123})))
        (is (= (mg/sample ?schema {:seed 123, :size 10})
               (mg/sample ?schema {:seed 123, :size 10})))
        (doseq [value (mg/sample ?schema {:seed 123})]
          (is (m/validate ?schema value))))))

  (testing "simple schemas"
    (doseq [schema [:any
                    [:string {:min 1, :max 4}]
                    [:int {:min 1, :max 4}]
                    [:double {:min 0.0, :max 1.0}]
                    :boolean
                    :keyword
                    :symbol
                    :qualified-keyword
                    :qualified-symbol]]
      (is (every? (partial m/validate schema) (mg/sample schema {:size 1000})))))

  (testing "map entries"
    (is (= {:korppu "koira"
            :piilomaan "pikku aasi"
            :muuli "mukkelis"}
           (mg/generate [:map {:gen/fmap '#(assoc % :korppu "koira")}
                         [:piilomaan {:gen/fmap '(partial str "pikku ")} [:string {:gen/elements ["aasi"]}]]
                         [:muuli {:gen/elements ["mukkelis"]} [:string {:gen/elements ["???"]}]]]))))

  (testing "ref"
    (testing "recursion"
      (let [schema [:schema {:registry {::cons [:maybe [:tuple int? [:ref ::cons]]]}}
                    ::cons]]
        (is (every? (partial m/validate schema) (mg/sample schema {:size 100})))))
    (testing "mutual recursion"
      (let [schema [:schema
                    {:registry {::ping [:maybe [:tuple [:= "ping"] [:ref ::pong]]]
                                ::pong [:maybe [:tuple [:= "pong"] [:ref ::ping]]]}}
                    ::ping]]
        (is (every? (partial m/validate schema) (mg/sample schema {:size 100})))))
    (testing "recursion limiting"
      (are [schema]
        (is (every? (partial m/validate schema) (mg/sample schema {:size 100})))

        [:schema {:registry {::rec [:maybe [:ref ::rec]]}} ::rec]
        [:schema {:registry {::rec [:map [:rec {:optional true} [:ref ::rec]]]}} ::rec]
        [:schema {:registry {::tuple [:tuple boolean? [:ref ::or]]
                             ::or [:or int? ::tuple]}} ::or]
        [:schema {:registry {::rec [:tuple int? [:vector {:max 2} [:ref ::rec]]]}} ::rec]
        [:schema {:registry {::rec [:tuple int? [:set {:max 2} [:ref ::rec]]]}} ::rec]
        [:schema {:registry {::multi
                             [:multi {:dispatch :type}
                              [:int [:map [:type [:= :int]] [:int int?]]]
                              [:multi [:map [:type [:= :multi]] [:multi {:optional true} [:ref ::multi]]]]]}} ::multi])))

  #?(:clj (testing "regex"
            (let [re #"^\d+ \d+$"]
              (m/validate re (mg/generate re)))

            (let [re-test #"(?=.{8,})" ;; contains unsupported feature
                  elements ["abcdefgh" "01234567"]
                  fmap '(fn [s] (str "prefix_" s))]
              (is (thrown-with-msg? Exception #"Unsupported-feature" (mg/generator [:re re-test])))
              (m/validate #".{8,}" (mg/generate [:re {:gen/elements elements} re-test]))
              (m/validate #"prefix_.{8,}" (mg/generate [:re {:gen/fmap fmap, :gen/elements elements} re-test])))))

  (testing "no generator"
    (is (thrown-with-msg?
          #?(:clj Exception, :cljs js/Error)
          #":malli.generator/no-generator"
          (mg/generate [:fn '(fn [x] (<= 0 x 10))]))))

  (testing "sci not available"
    (let [schema (m/schema [:string {:gen/fmap '(partial str "kikka_")}] {::m/disable-sci true})]
      (is (thrown-with-msg?
            #?(:clj Exception, :cljs js/Error)
            #":malli.core/sci-not-available"
            (mg/generator schema)))
      (is (thrown-with-msg?
            #?(:clj Exception, :cljs js/Error)
            #":malli.core/sci-not-available"
            (mg/generator [:string {:gen/fmap '(partial str "kikka_")}] {::m/disable-sci true})))
      (testing "direct options win"
        (is (mg/generator schema {::m/disable-sci false})))))

  (testing "generator override"
    (testing "without generator"
      (let [schema [:fn {:gen/fmap '(fn [_] (rand-int 10))}
                    '(fn [x] (<= 0 x 10))]
            generator (mg/generator schema)]
        (dotimes [_ 100]
          (m/validate schema (mg/generate generator)))))
    (testing "with generator"
      (is (re-matches #"kikka_\d+" (mg/generate [:and {:gen/fmap '(partial str "kikka_")} pos-int?])))))

  (testing "gen/elements"
    (is (every? #{1 2} (mg/sample [:and {:gen/elements [1 2]} int?] {:size 1000})))
    (is (every? #{"1" "2"} (mg/sample [:and {:gen/elements [1 2], :gen/fmap 'str} int?] {:size 1000}))))

  (testing "gen/gen"
    (is (every? #{1 2} (mg/sample [:and {:gen/gen (gen/elements [1 2])} int?] {:size 1000})))
    (is (every? #{"1" "2"} (mg/sample [:and {:gen/gen (gen/elements [1 2]) :gen/fmap str} int?] {:size 1000})))))

(defn- schema+coll-gen [type children-gen]
  (gen/let [children children-gen]
    (let [schema (into [type] children)]
      (gen/tuple (gen/return schema) (mg/generator schema)))))

(def ^:private seqex-child
  (let [s (gen/elements [string? int? keyword?])]
    (gen/one-of [s (gen/fmap #(vector :* %) s)])))

(defspec cat-test 100
  (for-all [[s coll] (schema+coll-gen :cat (gen/vector seqex-child))]
    (m/validate s coll)))

(defspec cat*-test 100
  (for-all [[s coll] (->> (gen/vector (gen/tuple gen/keyword seqex-child))
                          (gen/such-that (fn [coll] (or (empty? coll) (apply distinct? (map first coll)))))
                          (schema+coll-gen :cat*))]
    (m/validate s coll)))

(defspec alt-test 100
  (for-all [[s coll] (schema+coll-gen :alt (gen/not-empty (gen/vector seqex-child)))]
    (m/validate s coll)))

(defspec alt*-test 100
  (for-all [[s coll] (->> (gen/not-empty (gen/vector (gen/tuple gen/keyword seqex-child)))
                          (gen/such-that (fn [coll] (or (empty? coll) (apply distinct? (map first coll)))))
                          (schema+coll-gen :alt*))]
    (m/validate s coll)))

(defspec ?*+-test 100
  (for-all [[s coll] (gen/let [type (gen/elements [:? :* :+])
                               child seqex-child]
                       (let [schema [type child]]
                         (gen/tuple (gen/return schema) (mg/generator schema))))]
    (m/validate s coll)))

(defspec repeat-test 100
  (for-all [[s coll] (schema+coll-gen :repeat (gen/tuple
                                                (gen/let [min gen/nat
                                                          len gen/nat]
                                                  {:min min, :max (+ min len)})
                                                seqex-child))]
    (m/validate s coll)))

(deftest min-max-test

  (testing "valid properties"
    (are [schema]
      (is (every? #(<= 10 % 20) (map count (mg/sample schema {:size 1000}))))

      [:vector {:min 10, :max 20} int?]
      [:set {:min 10, :max 20} int?]
      [:string {:min 10, :max 20}]

      [:vector {:gen/min 10, :max 20} int?]
      [:set {:gen/min 10, :max 20} int?]
      [:string {:gen/min 10, :max 20}]

      [:vector {:min 10, :gen/max 20} int?]
      [:set {:min 10, :gen/max 20} int?]
      [:string {:gen/min 10, :max 20}]

      [:vector {:min 1, :gen/min 10, :max 100, :gen/max 20} int?]
      [:set {:min 1, :gen/min 10, :max 100, :gen/max 20} int?]
      [:string {:min 1, :gen/min 10, :max 100, :gen/max 20}]))

  (testing "invalid properties"
    (are [schema]
      (is (thrown? #?(:clj Exception, :cljs js/Error) (mg/sample schema {:size 1000})))

      ;; :gen/min less than :min
      [:vector {:min 11, :gen/min 10, :max 100, :gen/max 20} int?]
      [:set {:min 11, :gen/min 10, :max 100, :gen/max 20} int?]
      [:string {:min 11, :gen/min 10, :max 100, :gen/max 20}]

      ;; :gen/max over :max
      [:vector {:min 1, :gen/min 10, :max 100, :gen/max 200} int?]
      [:set {:min 1, :gen/min 10, :max 100, :gen/max 200} int?]
      [:string {:min 1, :gen/min 10, :max 100, :gen/max 200}])))

(deftest protocol-test
  (let [values #{1 2 3 5 8 13}
        schema (reify
                 m/Schema
                 (-type-properties [_])
                 (-properties [_])
                 mg/Generator
                 (-generator [_ _] (gen/elements values)))]
    (is (every? values (mg/sample schema {:size 1000})))))

(deftest util-schemas-test
  (let [registry (merge (m/default-schemas) (mu/schemas))]
    (doseq [schema [[:merge {:title "merge"}
                     [:map [:x int?] [:y int?]]
                     [:map [:z int?]]]
                    [:union {:title "union"}
                     [:map [:x int?] [:y int?]]
                     [:map [:x string?]]]
                    [:select-keys {:title "select-keys"}
                     [:map [:x int?] [:y int?]]
                     [:x]]]
            :let [schema (m/schema schema {:registry registry})]]
      (is (every? (partial m/validate schema) (mg/sample schema {:size 1000}))))))

#?(:clj
   (deftest function-schema-test
     (testing "generates valid functions"

       (let [=> (m/schema [:=> [:cat int? int?] int?])
             input (m/-input-schema =>)
             output (m/-output-schema =>)]
         (is (every? #(m/validate output (apply % (mg/generate input))) (mg/sample => {:size 1000})))))

     (testing "arity meta"
       (let [=> [:or
                 [:=> [:cat int?] int?]
                 [:=> [:cat int? int? int?] int?]]]
         (is (every? (comp #{1 3} :arity meta) (mg/sample => {:size 1000})))))))

(deftest recursive-schema-generation-test-307
  (let [sample (mg/generate [:schema {:registry {::A
                                                 [:cat
                                                  [:= ::a]
                                                  [:vector {:gen/min 2, :gen/max 2} [:ref ::A]]]}}
                             ::A] {:size 1, :seed 1})]
    (is (-> sample flatten count (> 1)))))
