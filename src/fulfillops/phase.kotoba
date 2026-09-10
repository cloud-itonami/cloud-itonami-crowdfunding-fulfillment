(ns fulfillops.phase
  "Phase 0->3 staged rollout for the fulfilment actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-survey  -- surveys may be sent and answered, every
                                 write needs human approval.
    Phase 2  assisted-logistics -- adds line transitions, shipments and
                                 delivery failures, still approval-gated.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:send-survey`, `:record-response`,
                                 `:advance-line`, `:ship-line` and
                                 `:record-undeliverable` may auto-commit.

  `:disclose-failure` and `:flag-fulfillment-concern` are deliberately
  ABSENT from every phase's `:auto` set, INCLUDING phase 3.

  Everything auto-committable here records something that already
  happened in the physical world — a survey went out, a parcel shipped, a
  carrier could not deliver. A failure disclosure is different in kind: it
  is a named person stating they cannot deliver what they promised, with a
  remedy from a closed set. No confidence level makes that something an
  actor should say on a creator's behalf.

  Note that `:ship-line` IS auto-committable even though it unlocks a
  payout gate. That is safe only because the governor refuses an untracked
  shipment — the fact being recorded is verifiable by the backer, not
  merely asserted by the creator. Remove that check and this phase table
  becomes wrong."
  (:require [fulfillops.governor :as governor]))

(def read-ops #{})
(def write-ops governor/allowed-ops)

(def phases
  {0 {:label "read-only"       :writes #{}               :auto #{}}
   1 {:label "assisted-survey" :writes #{:send-survey :record-response} :auto #{}}
   2 {:label "assisted-logistics"
      :writes #{:send-survey :record-response :advance-line :ship-line
                :record-undeliverable}
      :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:send-survey :record-response :advance-line :ship-line
              :record-undeliverable}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a FulfillmentGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
