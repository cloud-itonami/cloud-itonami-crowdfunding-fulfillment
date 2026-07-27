(ns fulfillops.advisor
  "FulfillmentAdvisor — the *contained intelligence node* for the
  fulfilment actor.

  It drafts exactly six kinds of proposal from a closed allowlist: sending
  a survey, recording a response, advancing a line, shipping one,
  recording a delivery failure, and recording the creator's failure
  disclosure.

  CRITICAL: it is a smart-but-untrusted advisor. Every proposal's
  `:effect` is always `:propose`. Two things it structurally cannot do:
  move a line to `:refunded` (that is money, and money is the payout
  actor's), and put a backer's actual address anywhere (responses carry
  references). Every output is censored downstream by
  `fulfillops.governor`.

  Note what the advisor does NOT decide. Whether a project has failed is
  not its call: `disclose-failure` records what a NAMED CREATOR stated,
  with a remedy from a closed set. There is no function here that reads
  the evidence and concludes a campaign was abandoned.

  Like every sibling actor's advisor this is a deterministic mock so the
  actor graph runs offline. In production this calls a real LLM with the
  same proposal shape."
  (:require [crowdfunding.fulfillment :as ff]
            [fulfillops.store :as store]))

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn- propose-survey
  [st {:keys [campaign-id pledge-id patch]}]
  (let [l (store/line-of st pledge-id)
        s (ff/survey {:pledge pledge-id
                      :backer (:fulfillment/backer l)
                      :campaign campaign-id
                      :sent-at (:at patch)})]
    {:op          :send-survey
     :campaign-id campaign-id
     :pledge-id   pledge-id
     :summary     (str pledge-id " へのアンケート送付を提案 (" (count (:survey/fields s)) " 項目)")
     :rationale   "配送に必要な項目の照会のみ。回答は参照として保持し、住所そのものは持たない。"
     :cites       [pledge-id]
     :effect      :propose
     :value       {:campaign-id campaign-id :pledge-id pledge-id :survey s}
     :confidence  0.93}))

(defn- propose-response
  [_st {:keys [campaign-id pledge-id patch]}]
  (let [r (ff/survey-response {:pledge pledge-id
                               :address-ref (:address-ref patch)
                               :contact-ref (:contact-ref patch)
                               :options (:options patch)
                               :responded-at (:at patch)})]
    {:op          :record-response
     :campaign-id campaign-id
     :pledge-id   pledge-id
     :summary     (str pledge-id " のアンケート回答を記録 (参照のみ)")
     :rationale   "回答の参照と選択肢の記録のみ。住所・連絡先の実体はここには置かない。"
     :cites       [pledge-id]
     :effect      :propose
     :value       {:campaign-id campaign-id :pledge-id pledge-id :response r}
     :confidence  0.94}))

(defn- propose-advance
  [_st {:keys [campaign-id pledge-id patch]}]
  {:op          :advance-line
   :campaign-id campaign-id
   :pledge-id   pledge-id
   :summary     (str pledge-id " の状態遷移を提案: " (pr-str (:to patch)))
   :rationale   "遷移表で許された状態変更の記録のみ。返金は別アクターの判断。"
   :cites       [pledge-id]
   :effect      :propose
   :value       {:campaign-id campaign-id :pledge-id pledge-id :to (:to patch)}
   :confidence  0.9})

(defn- propose-ship
  [_st {:keys [campaign-id pledge-id patch]}]
  {:op          :ship-line
   :campaign-id campaign-id
   :pledge-id   pledge-id
   :summary     (str pledge-id " の発送を記録: " (or (:tracking-ref patch) "追跡番号なし"))
   :rationale   "支援者自身が確認できる追跡番号を伴う発送事実の記録のみ。"
   :cites       (vec (keep identity [pledge-id (:tracking-ref patch)]))
   :effect      :propose
   :value       {:campaign-id campaign-id :pledge-id pledge-id
                 :shipment {:tracking-ref (:tracking-ref patch)
                            :carrier (:carrier patch)
                            :at (:at patch)}}
   :confidence  0.92})

(defn- propose-undeliverable
  [_st {:keys [campaign-id pledge-id patch]}]
  {:op          :record-undeliverable
   :campaign-id campaign-id
   :pledge-id   pledge-id
   :summary     (str pledge-id " の配送不能を記録: " (pr-str (:reason patch :unstated)))
   :rationale   "配送業者または創作者が述べた理由の記録のみ。判定は行わない。"
   :cites       [pledge-id]
   :effect      :propose
   :value       {:campaign-id campaign-id :pledge-id pledge-id
                 :failure {:reason (:reason patch) :at (:at patch)}}
   :confidence  0.88})

(defn- propose-disclosure
  "Draft the creator's failure disclosure. ALWAYS escalates — it is a
  statement by a named person about not delivering, and no actor gets to
  make one on their behalf."
  [_st {:keys [campaign-id patch]}]
  (let [d (ff/failure-disclosure {:campaign campaign-id
                                  :stated-by (:stated-by patch)
                                  :stated-at (:at patch)
                                  :reason (:reason patch)
                                  :remedy (:remedy patch)
                                  :detail (:detail patch)})]
    {:op          :disclose-failure
     :campaign-id campaign-id
     :summary     (str campaign-id " の履行不能の表明を提案: 対応 "
                       (pr-str (:remedy patch :unstated)))
     :rationale   "創作者本人の表明の記録のみ。履行不能かどうかを本アクターは判定しない。"
     :cites       [campaign-id]
     :effect      :propose
     :value       {:campaign-id campaign-id :disclosure d}
     :confidence  (or (:confidence patch) 0.8)}))

(defn- propose-concern
  [_st {:keys [campaign-id pledge-id patch]}]
  {:op          :flag-fulfillment-concern
   :campaign-id campaign-id
   :pledge-id   pledge-id
   :summary     (str (or pledge-id campaign-id) " に関する履行上の懸念フラグ: "
                     (pr-str (:concern patch "unknown")))
   :rationale   "観察された懸念事実の報告のみ。返金・裁定は行わない。"
   :cites       (vec (keep identity [campaign-id pledge-id]))
   :effect      :propose
   :value       (merge {:campaign-id campaign-id :pledge-id pledge-id} patch)
   :confidence  (or (:confidence patch) 0.78)})

(defn infer
  [st {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :send-survey              (propose-survey st request)
                   :record-response          (propose-response st request)
                   :advance-line             (propose-advance st request)
                   :ship-line                (propose-ship st request)
                   :record-undeliverable     (propose-undeliverable st request)
                   :disclose-failure         (propose-disclosure st request)
                   :flag-fulfillment-concern (propose-concern st request)
                   {})]
    ;; Test hook: inject scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Clear before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str
              " -- actually refunded the backer and transferred the funds")
      proposal)))

(defn trace [_request proposal]
  {:t           :advisor-proposal
   :op          (:op proposal)
   :campaign-id (:campaign-id proposal)
   :pledge-id   (:pledge-id proposal)
   :summary     (:summary proposal)
   :confidence  (:confidence proposal)})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request]
      (infer store request))))
