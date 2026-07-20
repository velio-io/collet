(ns collet.actions.jslt-test
  (:require
   [clojure.test :refer :all]
   [collet.core :as collet]
   [collet.action :as action]
   [collet.actions.jslt :as sut]))


(deftest jslt-action-test
  (are [input template expected]
    (let [action-spec {:type   ::sut/apply
                       :params {:template template}}
          {:keys [params]} (action/prep action-spec)
          action-fn   (action/action-fn action-spec)
          result      (action-fn (merge params {:input input :as :string}))]
      (= expected result))

    ;; Test cases
    "{\"key\": \"value\"}" "{\"newKey\": .key}" "{\"newKey\":\"value\"}"
    "{\"key\": \"value\"}" "{\"someKey\": \"value\", \"newKey\": .missingKey}" "{\"someKey\":\"value\"}"
    "{\"key\": {\"nestedKey\": \"nestedValue\"}}" "{\"newKey\": .key.nestedKey}" "{\"newKey\":\"nestedValue\"}"
    "{\"key\": [1, 2, 3]}" "{\"newKey\": .key[1]}" "{\"newKey\":2}")

  (are [input template expected]
    (let [action-spec {:type   ::sut/apply
                       :params {:template template}}
          {:keys [params]} (action/prep action-spec)
          action-fn   (action/action-fn action-spec)
          result      (action-fn (merge params {:input input}))]
      (= expected result))

    ;; Test case for returning value as Clojure data
    "{\"key\": \"value\"}" "{\"newKey\": .key}" {:newKey "value"}
    "{\"key\": {\"nestedKey\": \"value\"}}" "{\"newKey\": .key.nestedKey}" {:newKey "value"}
    "{\"key\": {\"nestedKey\": \"value\"}}" "{\"foo\": {\"bar\": .key.nestedKey}}" {:foo {:bar "value"}}
    "{\"array\": [1, 2, 3]}" "{\"secondItem\": .array[1]}" {:secondItem 2}
    "{\"array\": [1.3, 2.4, 3.5]}" "{\"secondItem\": .array[1]}" {:secondItem 2.4}
    ;; input
    "{
      \"customer_id\": 123456,
      \"customer_status\": 1,
      \"invoice_address_street\": \"McDuck Manor\",
      \"invoice_address_zip_code\": \"1312\",
      \"invoice_address_city\": \"Duckburg\",
      \"delivery_address_street\": \"Webfoot Walk\",
      \"delivery_address_zip_code\": \"1313\",
      \"delivery_address_city\": \"Duckburg\",
      \"attributes\": {
        \"customer_type\": \"C2C\",
        \"customer_class\": \"A\",
        \"last_order\": \"2022-03-10T18:25:43.511Z\"
      }
    }"
    ;; template
    " def map_status(status)
        if ($status == 0)
          \"ACTIVE\"
        else if ($status == 1)
          \"INACTIVE\"
        else
          \"UNDEFINED\"

     {
      \"customer_id\": sha256-hex(.customer_id),
      \"customer_status_code\": map_status(.customer_status),
      \"locations\": [
        {
          \"zip_code\": .invoice_address_zip_code,
          \"city\": .invoice_address_city
        },
        {
          \"zip_code\": .delivery_address_zip_code,
          \"city\": .delivery_address_city
        }
      ],
      \"customer_type\": .attributes.customer_type,
      \"customer_class\": .attributes.customer_class,
      \"producer_team\": \"us.california.burbank.disney\"
    }"
    ;; expected
    {:customer_id          "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92",
     :customer_status_code "INACTIVE",
     :locations            [{:zip_code "1312", :city "Duckburg"}
                            {:zip_code "1313", :city "Duckburg"}],
     :customer_type        "C2C",
     :customer_class       "A",
     :producer_team        "us.california.burbank.disney"}))


(deftest jslt-in-pipeline
  (let [records   ["{
                    \"order\": {
                      \"order_id\": 1,
                      \"customer\": {
                        \"name\": \"Alice\",
                        \"email\": \"alice@example.com\"
                      },
                      \"items\": [
                        {
                          \"product_id\": 101,
                          \"name\": \"Widget A\",
                          \"quantity\": 2,
                          \"price\": 10.0
                        },
                        {
                          \"product_id\": 102,
                          \"name\": \"Widget B\",
                          \"quantity\": 1,
                          \"price\": 20.0
                        }
                      ],
                      \"order_date\": \"2023-10-01\",
                      \"status\": \"shipped\"
                    }
                  }"
                   "{
                     \"order\": {
                       \"order_id\": 4,
                       \"customer\": {
                         \"name\": \"David\",
                         \"email\": \"david@example.com\"
                       },
                       \"items\": [
                         {
                           \"product_id\": 106,
                           \"name\": \"Widget F\",
                           \"quantity\": 1,
                           \"price\": 44.0
                         }
                       ],
                       \"order_date\": \"2023-10-04\",
                       \"status\": \"canceled\"
                       }
                     }"
                   "{
                     \"order\": {
                       \"order_id\": 6,
                       \"customer\": {
                         \"name\": \"Frank\",
                         \"email\": \"frank@example.com\"
                       },
                       \"items\": [
                         {
                           \"product_id\": 108,
                           \"name\": \"Widget H\",
                           \"quantity\": 2,
                           \"price\": 60.0
                         },
                         {
                           \"product_id\": 109,
                           \"name\": \"Widget I\",
                           \"quantity\": 3,
                           \"price\": 70.0
                         }
                       ],
                       \"order_date\": \"2023-10-06\",
                       \"status\": \"pending\"
                       }
                     }
                     "]
        template  "{
                    \"order_summary\": {
                      \"order_id\": .order.order_id,
                      \"customer_name\": .order.customer.name,
                      \"total_items\": size(.order.items),
                      \"total_cost\": sum([for (.order.items) .price * .quantity]),
                      \"status\": if (.order.status == \"shipped\") \"Delivered\" else if (.order.status == \"pending\") \"Processing\" else \"Cancelled\",
                      \"order_date\":.order.order_date
                    }
                  }"
        pipe-spec {:name  :test-jslt-pipeline
                   :tasks [{:name       :json-transform
                            :keep-state true
                            :actions    [{:name      :json-records
                                          :type      :mapper
                                          :selectors {'records [:config :records]}
                                          :params    {:sequence 'records}}
                                         {:name      :transform-json
                                          :type      :collet.actions.jslt/apply
                                          :selectors {'json [:$mapper/item]}
                                          :params    {:template template
                                                      :input    'json}}]
                            :iterator   {:next [:true? [:$mapper/has-next-item]]}}]}
        pipeline  (collet/compile-pipeline pipe-spec)]
    @(pipeline {:records records})

    (is (= [{:order_summary {:customer_name "Alice"
                             :order_date    "2023-10-01"
                             :order_id      1
                             :status        "Delivered"
                             :total_cost    40.0
                             :total_items   2}}
            {:order_summary {:customer_name "David"
                             :order_date    "2023-10-04"
                             :order_id      4
                             :status        "Cancelled"
                             :total_cost    44.0
                             :total_items   1}}
            {:order_summary {:customer_name "Frank"
                             :order_date    "2023-10-06"
                             :order_id      6
                             :status        "Processing"
                             :total_cost    330.0
                             :total_items   2}}]
           (:json-transform pipeline)))))



