(ns fulfillops.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [fulfillops.advisor :as advisor]
            [fulfillops.governor :as sut]
            [fulfillops.phase :as phase]
            [fulfillops.store :as store]))

(def ctx {:actor-id "fulfillment-test" :phase 3 :now "2027-05-01T00:00:00Z"})

(defn- verdict [st request]
  (sut/check request ctx (advisor/infer st request) st))

(defn- rules [v] (set (map :rule (:violations v))))

;; ───────────────────────── the happy path ─────────────────────────

(deftest routine-records-commit
  (let [st (store/seed-db)]
    (doseq [req [{:op :send-survey :campaign-id "cf-ship" :pledge-id "fl-1"
                  :patch {:at "2027-01-05T00:00:00Z"}}
                 {:op :record-response :campaign-id "cf-ship" :pledge-id "fl-1"
                  :patch {:address-ref "addr:1" :contact-ref "c:1"
                          :at "2027-01-09T00:00:00Z"}}
                 {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-2"
                  :patch {:tracking-ref "YT-2" :carrier "yamato" :at "2027-05-01T00:00:00Z"}}
                 {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-3"
                  :patch {:to :delivered}}]]
      (let [v (verdict st req)]
        (is (= #{} (rules v)) (str (:op req)))
        (is (= :commit (phase/verdict->disposition v)) (str (:op req)))))))

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (let [st (store/seed-db)]
    (doseq [req [{:op :send-survey :campaign-id "cf-ship" :pledge-id "fl-1"}
                 {:op :record-response :campaign-id "cf-ship" :pledge-id "fl-1"
                  :patch {:address-ref "a" :contact-ref "c"}}
                 {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-3"
                  :patch {:to :delivered}}
                 {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-2"
                  :patch {:tracking-ref "t"}}
                 {:op :record-undeliverable :campaign-id "cf-ship" :pledge-id "fl-3"
                  :patch {:reason :address-incomplete}}
                 {:op :disclose-failure :campaign-id "cf-ship"
                  :patch {:stated-by "creator.cf-ship" :remedy :refund}}
                 {:op :flag-fulfillment-concern :campaign-id "cf-ship"}]]
      (is (not (contains? (rules (verdict st req)) :scope-excluded))
          (str (:op req) " must not block itself")))))

;; ───────────────────────── the two permanent exclusions ─────────────────────────

(deftest refunding-is-permanently-out-of-scope-for-this-actor
  (testing "a real fulfilment state, but reaching it is a money act"
    (let [st (store/seed-db)
          v  (verdict st {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-3"
                          :patch {:to :refunded}})]
      (is (true? (:hard? v)))
      (is (contains? (rules v) :refund-out-of-scope))
      (is (not (contains? (rules v) :illegal-transition))
          "'not allowed right now' and 'never allowed here' are different ledger facts"))))

(deftest a-value-where-a-reference-belongs-is-refused
  (let [st (store/seed-db)]
    (testing "even nested one level deeper than a fixed field list would look"
      (let [v (verdict st {:op :record-response :campaign-id "cf-ship" :pledge-id "fl-1"
                           :patch {:address-ref "addr:1" :contact-ref "c:1"
                                   :options [{:postcode "150-0001" :phone "03-0000-0000"}]}})]
        (is (contains? (rules v) :personal-data-in-record))
        (is (some #(re-find #"phone" (str (:detail %))) (:violations v))
            "and the offending fields are named")))
    (testing "while the correct shape passes"
      (is (= #{} (rules (verdict st {:op :record-response :campaign-id "cf-ship"
                                     :pledge-id "fl-1"
                                     :patch {:address-ref "addr:1" :contact-ref "c:1"
                                             :options ["iso-jp"]}})))))))

;; ───────────────────────── the payout-gate coupling ─────────────────────────

(deftest an-untracked-shipment-cannot-unlock-a-payout-gate
  (let [st (store/seed-db)
        v  (verdict st {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-2"
                        :patch {:carrier "yamato" :at "2027-05-01T00:00:00Z"}})]
    (is (contains? (rules v) :untracked-shipment)
        "a shipment the backer cannot verify must not be assertable alone")))

(deftest the-gates-payout-reads-come-from-these-records
  (let [st (store/seed-db)]
    (testing "fl-3 is already shipped, so first-shipment is satisfied"
      (is (contains? (store/gate-facts st "cf-ship" (:now ctx) {:collection-closed? true})
                     :on-first-shipment)))
    (testing "but not everyone is delivered, so completion is not"
      (is (not (contains? (store/gate-facts st "cf-ship" (:now ctx) {:collection-closed? true})
                          :on-fulfillment-complete))))
    (testing "and production is never inferred from fulfilment states"
      (is (not (contains? (store/gate-facts st "cf-ship" (:now ctx) {:collection-closed? true})
                          :on-production-start))))))

;; ───────────────────────── the rest of the hard checks ─────────────────────────

(deftest nothing-is-fulfilled-before-the-campaign-reaches-fulfilment
  (let [st (store/seed-db)]
    (is (contains? (rules (verdict st {:op :send-survey :campaign-id "cf-early"
                                       :pledge-id "fl-early" :patch {:at "x"}}))
                   :campaign-not-fulfilling))))

(deftest illegal-transitions-are-refused-from-the-shared-table
  (let [st (store/seed-db)]
    (is (contains? (rules (verdict st {:op :advance-line :campaign-id "cf-ship"
                                       :pledge-id "fl-1" :patch {:to :delivered}}))
                   :illegal-transition)
        "fl-1 is still awaiting its survey")))

(deftest an-incomplete-response-is-refused-now-not-at-the-warehouse
  (let [st (store/seed-db)
        v  (verdict st {:op :record-response :campaign-id "cf-ship" :pledge-id "fl-2"
                        :patch {:contact-ref "c:1" :at "2027-01-09T00:00:00Z"}})]
    (is (contains? (rules v) :missing-address-ref))))

(deftest an-effect-other-than-propose-is-a-claim-to-actuate
  (let [st (store/seed-db)
        p  (assoc (advisor/infer st {:op :advance-line :campaign-id "cf-ship"
                                     :pledge-id "fl-3" :patch {:to :delivered}})
                  :effect :execute)]
    (is (contains? (rules (sut/check {} ctx p st)) :effect-not-propose))))

(deftest claiming-to-have-refunded-someone-is-permanently-blocked
  (let [st (store/seed-db)
        p  (advisor/infer st {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-3"
                              :patch {:to :delivered} :out-of-scope? true})]
    (is (contains? (rules (sut/check {} ctx p st)) :scope-excluded))))

(deftest no-op-in-the-allowlist-moves-money
  (is (= #{:send-survey :record-response :advance-line :ship-line
           :record-undeliverable :disclose-failure :flag-fulfillment-concern}
         sut/allowed-ops))
  (is (contains? (rules (sut/check {} ctx {:op :issue-refund :effect :propose}
                                   (store/seed-db)))
                 :op-not-allowed)))

;; ───────────────────────── escalation and phases ─────────────────────────

(deftest a-failure-disclosure-always-reaches-a-human
  (let [st (store/seed-db)
        v  (verdict st {:op :disclose-failure :campaign-id "cf-ship"
                        :patch {:stated-by "creator.cf-ship" :remedy :partial-refund
                                :reason :tooling-cost-overrun :at "2027-05-01T00:00:00Z"}})]
    (is (false? (:hard? v)))
    (is (true? (:high-stakes? v)))
    (is (= :escalate (phase/verdict->disposition v)))))

(deftest the-disclosure-ops-are-out-of-every-phase-auto-set
  (doseq [[p {:keys [auto]}] phase/phases
          op sut/always-escalate-ops]
    (is (not (contains? auto op))
        (str op " must never be auto-committable, including phase " p))))

(deftest a-governor-hold-survives-every-phase
  (doseq [p (keys phase/phases)]
    (is (= :hold (:disposition (phase/gate p {:op :ship-line} :hold))))))

(deftest early-phases-disable-writes-rather-than-quietly-allowing-them
  (is (= {:disposition :hold :reason :phase-disabled}
         (phase/gate 0 {:op :send-survey} :commit)))
  (is (= {:disposition :escalate :reason :phase-approval}
         (phase/gate 1 {:op :send-survey} :commit)))
  (is (= {:disposition :hold :reason :phase-disabled}
         (phase/gate 1 {:op :ship-line} :commit)))
  (is (= {:disposition :commit :reason nil}
         (phase/gate 3 {:op :ship-line} :commit))))
