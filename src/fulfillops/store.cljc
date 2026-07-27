(ns fulfillops.store
  "SSoT for the fulfilment actor — what each backer is owed, how far along
  it is, and whether the creator is still talking.

  Directories, all keyed by STRING ids (never keywords):

    campaigns  campaign id -> campaign record. Read for its state; only a
               campaign that reached fulfilment has anything to fulfil.
    lines      pledge id -> fulfilment line (one backer, one pledge).
    surveys    pledge id -> the survey sent to that backer.
    responses  pledge id -> their answers, as REFERENCES.
    updates    campaign id -> {:count n :last-at iso}. The creator's own
               posting cadence, which is what makes 'late' and 'silent'
               separable facts rather than one vague impression.
    disclosures campaign id -> the creator's failure disclosure, if any.

  ## No personal data lives here

  A survey response carries `:response/address-ref` and
  `:response/contact-ref` — opaque references resolved by a system that is
  allowed to hold personal data. This store, like the pure library it sits
  on, gets copied into logs, ledgers, checkpoints and test fixtures; a
  shipping address in any of those is a shipping address in all of them.
  `fulfillops.governor` refuses a proposal that carries a value where a
  reference belongs, so this is enforced rather than merely intended.

  ## No money moves here

  `:refunded` is a real fulfilment state — but reaching it is a MONEY act,
  and money belongs to `cloud-itonami-crowdfunding-payout`. This actor has
  no op that can set it, and the governor treats an attempt as a permanent
  scope exclusion.

  The ledger stays append-only."
  (:require [crowdfunding.campaign :as cf]
            [crowdfunding.fulfillment :as ff]))

(defprotocol Store
  (campaign-of [s id])
  (line-of [s pledge-id])
  (lines-for [s campaign-id])
  (survey-of [s pledge-id])
  (response-of [s pledge-id])
  (updates-for [s campaign-id])
  (disclosure-for [s campaign-id])
  (ledger [s])
  (fulfillment-log [s])
  (commit-record! [s record])
  (append-ledger! [s fact]))

;; ----------------------------- demo data -----------------------------

(defn- a-campaign [id state]
  (assoc (cf/campaign {:id id :creator (str "creator." id) :title (str id)
                       :currency "JPY" :goal-minor 500000 :category :technology
                       :duration-days 45 :launched-at "2026-08-01T00:00:00Z"
                       :deadline "2026-09-15T00:00:00Z"
                       :story "A split keyboard."
                       :risks "Tooling is not finalised; the controller is single-source."
                       :ships-to #{:jp}})
         :campaign/state state))

(defn- a-line [pledge campaign & [state]]
  (cond-> (ff/line {:pledge pledge :backer (str "backer." pledge)
                    :campaign campaign :reward "standard"
                    :estimated-delivery "2027-04-01"})
    state (assoc :fulfillment/state state)))

(defn demo-data
  "Fixtures covering the happy path and each hard check.

    cf-ship   :fulfilling — three backers at different stages
    cf-early  :collecting — nothing to fulfil yet

    fl-1  awaiting survey
    fl-2  survey received, ready to ship
    fl-3  shipped, ready to be marked delivered"
  []
  {:campaigns {"cf-ship"  (a-campaign "cf-ship" :fulfilling)
               "cf-early" (a-campaign "cf-early" :collecting)}
   :lines {"fl-1" (a-line "fl-1" "cf-ship")
           "fl-2" (a-line "fl-2" "cf-ship" :survey-received)
           "fl-3" (assoc (a-line "fl-3" "cf-ship" :shipped)
                         :fulfillment/tracking-ref "YT-0003")
           "fl-early" (a-line "fl-early" "cf-early")}
   :surveys {"fl-2" (ff/survey {:pledge "fl-2" :backer "backer.fl-2"
                                :campaign "cf-ship"
                                :sent-at "2027-01-05T00:00:00Z"})}
   :responses {"fl-2" (ff/survey-response {:pledge "fl-2"
                                           :address-ref "addr:2f1c"
                                           :contact-ref "contact:2f1c"
                                           :options ["iso-jp" "blue"]
                                           :responded-at "2027-01-09T00:00:00Z"})}
   :updates {"cf-ship" {:count 4 :last-at "2027-04-20T00:00:00Z"}}
   :disclosures {}})

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (campaign-of [_ id] (get-in @a [:campaigns id]))
  (line-of [_ id] (get-in @a [:lines id]))
  (lines-for [_ cid] (->> (vals (:lines @a))
                          (filter #(= cid (:fulfillment/campaign %)))
                          (sort-by :fulfillment/pledge)
                          vec))
  (survey-of [_ id] (get-in @a [:surveys id]))
  (response-of [_ id] (get-in @a [:responses id]))
  (updates-for [_ cid] (get-in @a [:updates cid] {:count 0 :last-at nil}))
  (disclosure-for [_ cid] (get-in @a [:disclosures cid]))
  (ledger [_] (:ledger @a))
  (fulfillment-log [_] (:fulfillment-log @a))
  (commit-record! [_ record]
    (swap! a update :fulfillment-log conj record)
    (let [{:keys [op value]} record
          id (:pledge-id value)]
      (case op
        :send-survey
        (swap! a assoc-in [:surveys id] (:survey value))

        ;; A response moves the line only through `ff/advance`, so the
        ;; state table stays the single description of what can follow
        ;; what.
        :record-response
        (swap! a (fn [m]
                   (-> m
                       (assoc-in [:responses id] (:response value))
                       (update-in [:lines id]
                                  #(or (some-> % (ff/advance :survey-received)) %)))))

        :advance-line
        (swap! a update-in [:lines id]
               #(or (some-> % (ff/advance (:to value))) %))

        :ship-line
        (swap! a update-in [:lines id]
               #(or (some-> % (ff/ship (:shipment value))) %))

        :record-undeliverable
        (swap! a update-in [:lines id]
               #(or (some-> % (ff/undeliverable (:failure value))) %))

        :disclose-failure
        (swap! a assoc-in [:disclosures (:campaign-id value)] (:disclosure value))
        nil))
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db []
  (->MemStore (atom (assoc (demo-data) :ledger [] :fulfillment-log []))))

(defn mem-store [m]
  (->MemStore (atom (merge {:campaigns {} :lines {} :surveys {} :responses {}
                            :updates {} :disclosures {}
                            :ledger [] :fulfillment-log []}
                           m))))

;; ----------------------------- derived views -----------------------------

(defn progress
  "Roll this campaign's lines up at `now`."
  [s cid now]
  (ff/progress (lines-for s cid) now))

(defn gate-facts
  "The payout release gates this campaign's fulfilment actually satisfies.

  This is the whole reason the actor exists: `cloud-itonami-crowdfunding-
  payout` releases a tranche against `:on-first-shipment` /
  `:on-fulfillment-complete`, and those are facts about records that
  someone has to write. Nobody did before this actor."
  [s cid now {:keys [collection-closed? production-evidence]}]
  (ff/gate-facts (progress s cid now)
                 {:collection-closed?  collection-closed?
                  :production-evidence production-evidence}))

(defn accountability
  "What the creator owes, as facts. `stale-before` is `now` minus the
  update interval and is supplied by the caller — the library has no clock
  and will not fake date arithmetic on ISO strings."
  [s cid now stale-before]
  (let [{:keys [count last-at]} (updates-for s cid)]
    (ff/accountability {:campaign      cid
                        :lines         (lines-for s cid)
                        :now           now
                        :stale-before  stale-before
                        :last-update-at last-at
                        :updates-count count})))
