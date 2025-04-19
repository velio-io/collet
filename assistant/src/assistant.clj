(ns assistant
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [wkok.openai-clojure.api :as openai]))


(comment
 (def train-file
   (openai/create-file
    {:purpose "fine-tune"
     :file    (io/file "../assistant/collet_finetuning.jsonl")}
    {:api-key (System/getenv "OPENAI_API_KEY")}))


 (def valid-file
   (openai/create-file
    {:purpose "fine-tune"
     :file    (io/file "../assistant/collet_validation.jsonl")}
    {:api-key (System/getenv "OPENAI_API_KEY")}))


 (def job
   (openai/create-fine-tuning-job
    {:model           "gpt-4o-2024-08-06"
     :suffix          "collet"
     :training_file   (:id train-file)
     :validation_file (:id valid-file)}
    {:api-key (System/getenv "OPENAI_API_KEY")}))


 (openai/retrieve-fine-tuning-job
  {:fine_tuning_job_id (:id job)}
  {:api-key (System/getenv "OPENAI_API_KEY")})


 (def docs
   (->> (rest (file-seq (io/as-file "../docs")))
        (map slurp)
        (string/join "\n\n")))

 (spit "../docs/collet-docs.md" docs)


 (openai/create-chat-completion
  {:model    "ft:gpt-4o-2024-08-06:personal:collet:BHeHQbLK"
   :messages [{:role    "system"
               :content "You're an expert in building pipelines with Collet library.
                         You will generate Collet actions, tasks and pipelines in EDN format without additional explanations."}
              {:role    "user"
               :content (str "Take into account this Collet DSL syntax and available Collet actions: " docs)}
              {:role    "user"
               :content "Use the Collet documentation to find the correct action and parameters.
                         Generate a Collet task to fetch recent Hubspot contacts."}]}
  {:api-key (System/getenv "OPENAI_API_KEY")})

 {:name    :fetch-hubspot-contacts
  :actions [{:name   :recent-contacts
             :type   :collet.actions.http/request
             :params {:url          "https://api.hubapi.com/crm/v3/objects/contacts"
                      :as           :json
                      :content-type :json
                      :accept       :json
                      :query-params {:limit 10}
                      :oauth-token  [:config :hubspot-access-token]}
             :return [:body :results]}]})