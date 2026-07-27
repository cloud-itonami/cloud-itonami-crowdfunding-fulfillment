(ns fulfillops.governor
  "FulfillmentGovernor — the independent compliance layer standing between
  a proposed fulfilment record and two things that are easy to get wrong
  months after the money moved: a backer's personal data, and the payout
  gates that read these records.

  The advisor has no notion of whether the transition it wants is legal,
  whether the shipment it is recording can be checked by the backer,
  whether the response it is storing contains an address instead of a
  reference, or whether its own `:effect` secretly claims to have
  refunded someone. So this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  ## Why this actor matters more than it looks

  `cloud-itonami-crowdfunding-payout` releases creator money against
  `:on-first-shipment` and `:on-fulfillment-complete`. Those are facts
  about the records THIS actor writes. A line marked shipped here
  unlocks money there — which is exactly why `:shipped` is refused
  without a tracking reference the backer can check, and why nothing in
  this actor can reach `:refunded`.

  ## No money, no addresses

  Two permanent scope exclusions, not policies:

  - **`:refunded` is unreachable.** It is a real fulfilment state, but
    reaching it is a money act and money belongs to the payout actor.
  - **No personal data.** A survey response carries `:address-ref` and
    `:contact-ref`. A proposal carrying an actual address, phone number
    or email is refused — this record ends up in logs, ledgers,
    checkpoints and fixtures, and a value that reaches one reaches all
    of them.

  Seven HARD checks, ALL permanent, un-overridable by any human approval:

    1. Campaign not fulfilling -- nothing to fulfil before the campaign
                                  reached fulfilment.
    2. Illegal transition      -- delegated to
                                  `crowdfunding.fulfillment/transitions`,
                                  so there is one table and not two that
                                  can drift.
    3. Refund attempted        -- permanent scope exclusion (see above).
    4. Untracked shipment      -- `:shipped` unlocks a payout gate; a
                                  shipment the backer cannot verify must
                                  not be assertable by the creator alone.
    5. Personal data           -- a value where a reference belongs.
    6. Effect not :propose     -- a claim to actuate outside governance.
    7. Scope exclusion         -- any claim to have refunded, charged or
                                  paid out, plus any op outside the closed
                                  allowlist.

  Two ESCALATE (SOFT) gates:
    - LLM confidence below the floor.
    - `:disclose-failure` and `:flag-fulfillment-concern` ALWAYS escalate.
      A failure disclosure is a named person saying they cannot deliver;
      no actor makes that statement on someone's behalf."
  (:require [clojure.string :as str]
            [crowdfunding.fulfillment :as ff]
            [fulfillops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist. CRITICAL: no op moves money, and no
  op reaches `:refunded`."
  #{:send-survey :record-response :advance-line :ship-line
    :record-undeliverable :disclose-failure :flag-fulfillment-concern})

(def always-escalate-ops #{:disclose-failure :flag-fulfillment-concern})

(def fulfilling-states
  "Campaign states in which fulfilment records may be written."
  #{:fulfilling :completed})

(def personal-data-keys
  "Keys whose presence means a VALUE was supplied where a REFERENCE
  belongs. Note `:address-ref` and `:contact-ref` are absent — those are
  the correct shape; it is the bare forms that are refused."
  #{:address :addr :street :postcode :postal-code :zip :city :prefecture
    :phone :tel :email :e-mail :full-name :recipient-name :name})

(def scope-excluded-terms
  "Case-insensitive substrings marking a proposal as claiming to have
  moved money.

  CRITICAL: every term is phrased as the COMPLETED act ('refunded the
  backer'), never a bare noun like 'refund' or 'shipment' — a bare noun
  would match inside this actor's own legitimate proposals (whose whole
  job is to talk about shipping and about disclosing that a refund is the
  remedy) and self-block the happy path. See
  `fulfillops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`."
  ["refunded the backer" "refunded the backers" "have refunded"
   "charged the backer" "have charged" "paid out the creator"
   "transferred the funds" "have transferred" "released the funds to"
   "moved the funds" "resolved the payment dispute"
   "返金した" "返金を実行した" "支援者に請求した" "送金した"
   "資金を移動した" "払い出した" "支払い紛争を解決した"])

;; ----------------------------- checks -----------------------------

(defn- campaign-violations
  [proposal st]
  (when-not (= :flag-fulfillment-concern (:op proposal))
    (let [cid (get-in proposal [:value :campaign-id])
          c   (store/campaign-of st cid)]
      (cond
        (nil? c) [{:rule :campaign-unknown :detail (str (or cid "(id missing)") " は存在しない")}]
        (not (contains? fulfilling-states (:campaign/state c)))
        [{:rule :campaign-not-fulfilling
          :detail (str "状態 " (pr-str (:campaign/state c)) " のキャンペーンに履行記録はない")}]))))

(defn- line-target
  "The state a proposal would move a line to, or nil for ops that do not
  move one."
  [proposal]
  (case (:op proposal)
    :advance-line         (get-in proposal [:value :to])
    :ship-line            :shipped
    :record-undeliverable :undeliverable
    :record-response      :survey-received
    nil))

(defn- refund-violations
  "`:refunded` is a money act. This actor has no path to it, and an
  attempt is a permanent scope exclusion rather than a rejected
  transition — the distinction matters in the ledger, because 'that
  transition is not allowed right now' and 'this actor may never do that'
  are different facts."
  [proposal]
  (when (= :refunded (line-target proposal))
    [{:rule :refund-out-of-scope
      :detail "返金は本アクターの永久的な対象外 -- 支払アクター(payout)の人間承認を通る"}]))

(defn- transition-violations
  [proposal st]
  (when-let [to (line-target proposal)]
    (let [id (get-in proposal [:value :pledge-id])
          l  (store/line-of st id)]
      (cond
        (nil? l) [{:rule :line-unknown :detail (str (or id "(id missing)") " の履行記録はない")}]
        (and (not= :refunded to)
             (not (contains? (get ff/transitions (:fulfillment/state l) #{}) to)))
        [{:rule :illegal-transition
          :detail (str (pr-str (:fulfillment/state l)) " -> " (pr-str to) " は許可されていない")}]))))

(defn- shipment-violations
  "A shipment with no tracking reference is a status the creator can
  assert and the backer cannot verify — and it unlocks a payout gate."
  [proposal]
  (when (= :ship-line (:op proposal))
    (when (str/blank? (str (get-in proposal [:value :shipment :tracking-ref])))
      [{:rule :untracked-shipment
        :detail "追跡番号のない発送は支援者が確認できず、かつ支払 gate を解錠してしまう"}])))

(defn- response-violations
  [proposal st]
  (when (= :record-response (:op proposal))
    (let [id (get-in proposal [:value :pledge-id])
          s  (or (store/survey-of st id)
                 (ff/survey {:pledge id :backer "" :campaign ""}))
          r  (get-in proposal [:value :response])]
      (if-not (map? r)
        [{:rule :response-missing :detail "回答がない"}]
        (when-let [errs (seq (ff/response-errors s r))]
          (mapv (fn [e] {:rule (:fulfillment.error/code e)
                         :detail (name (:fulfillment.error/code e))})
                errs))))))

(defn- collect-keys
  "Every map key anywhere inside a value, so a personal-data field cannot
  hide one level deeper than the check looks."
  [v]
  (cond
    (map? v)        (concat (keys v) (mapcat collect-keys (vals v)))
    (sequential? v) (mapcat collect-keys v)
    :else           nil))

(defn- personal-data-violations
  "Refuses a proposal carrying a VALUE where a REFERENCE belongs.

  Checked over the whole `:value` tree rather than a fixed field list: the
  failure mode is someone adding a convenience field, not someone
  deliberately putting an address in `:address-ref`."
  [proposal]
  (let [found (->> (collect-keys (:value proposal))
                   (keep (fn [k] (when (keyword? k)
                                   (let [bare (keyword (name k))]
                                     (when (contains? personal-data-keys bare) bare)))))
                   distinct
                   sort)]
    (when (seq found)
      [{:rule :personal-data-in-record
        :detail (str "参照であるべき箇所に実体が入っている: " (str/join "," (map name found)))}])))

(defn- effect-not-propose-violations [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites]))))

(defn- scope-exclusion-violations [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "返金・請求・送金を実行済みと主張する提案は永久に禁止"}])))

(defn check
  "Censors a FulfillmentAdvisor proposal.

  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
           :high-stakes? bool :hard? bool}."
  [_request _context proposal store]
  (let [hard (into []
                   (concat (campaign-violations proposal store)
                           (refund-violations proposal)
                           (transition-violations proposal store)
                           (shipment-violations proposal)
                           (response-violations proposal store)
                           (personal-data-violations proposal)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (always-escalate-ops (:op proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :campaign-id (:campaign-id request)
   :pledge-id   (:pledge-id request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
