(ns fulfillops.operation-test
  (:require [clojure.test :refer [deftest is testing]]
            [fulfillops.operation :as operation]
            [fulfillops.store :as store]
            [langgraph.graph :as g]))

(def now "2027-05-01T00:00:00Z")
(def ctx {:actor-id "fulfillment-test" :phase 3 :now now})

(defn- run-req!
  ([actor tid request] (run-req! actor tid request ctx))
  ([actor tid request c] (g/run* actor {:request request :context c} {:thread-id tid})))

(deftest a-survey-and-its-response-move-the-line-and-store-only-references
  (let [s (store/seed-db)
        a (operation/build s)]
    (run-req! a "t1a" {:op :send-survey :campaign-id "cf-ship" :pledge-id "fl-1"
                       :patch {:at "2027-01-05T00:00:00Z"}})
    (is (some? (store/survey-of s "fl-1")))
    (run-req! a "t1b" {:op :record-response :campaign-id "cf-ship" :pledge-id "fl-1"
                       :patch {:address-ref "addr:9a3f" :contact-ref "contact:9a3f"
                               :options ["iso-jp"] :at "2027-01-09T00:00:00Z"}})
    (is (= :survey-received (:fulfillment/state (store/line-of s "fl-1"))))
    (is (= "addr:9a3f" (:response/address-ref (store/response-of s "fl-1"))))
    (is (not-any? #{:response/address :response/postcode :response/phone}
                  (keys (store/response-of s "fl-1")))
        "this record ends up in logs, ledgers, checkpoints and fixtures")))

(deftest an-untracked-shipment-never-reaches-the-store
  (let [s (store/seed-db)
        a (operation/build s)
        r (run-req! a "t2" {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-2"
                            :patch {:carrier "yamato" :at now}})]
    (is (= :hold (:disposition (:state r))))
    (is (= :survey-received (:fulfillment/state (store/line-of s "fl-2")))
        "still where it was")
    (is (contains? (set (:basis (last (store/ledger s)))) :untracked-shipment))))

(deftest a-tracked-shipment-commits-and-unlocks-the-first-shipment-gate
  (let [s (store/mem-store (assoc-in (store/demo-data) [:lines "fl-3" :fulfillment/state]
                                     :survey-received))
        a (operation/build s)]
    (is (not (contains? (store/gate-facts s "cf-ship" now {:collection-closed? true})
                        :on-first-shipment))
        "nothing shipped yet")
    (run-req! a "t3" {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-3"
                      :patch {:tracking-ref "YT-0003" :carrier "yamato" :at now}})
    (is (= :shipped (:fulfillment/state (store/line-of s "fl-3"))))
    (is (contains? (store/gate-facts s "cf-ship" now {:collection-closed? true})
                   :on-first-shipment)
        "and this is the fact the payout actor releases a tranche against")))

(deftest completion-requires-every-backer-and-an-unreachable-one-does-not-count
  (let [s (store/mem-store
           (assoc (store/demo-data)
                  :lines {"fl-1" {:fulfillment/pledge "fl-1" :fulfillment/campaign "cf-ship"
                                  :fulfillment/state :delivered}
                          "fl-2" {:fulfillment/pledge "fl-2" :fulfillment/campaign "cf-ship"
                                  :fulfillment/state :undeliverable}}))
        a (operation/build s)]
    (is (not (contains? (store/gate-facts s "cf-ship" now {:collection-closed? true})
                        :on-fulfillment-complete))
        "an unreachable backer is an open obligation, not a rounding error")
    (testing "and once they are reached and delivered, the gate opens"
      (run-req! a "t4a" {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-2"
                         :patch {:to :survey-received}})
      (run-req! a "t4b" {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-2"
                         :patch {:tracking-ref "YT-R2" :at now}})
      (run-req! a "t4c" {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-2"
                         :patch {:to :delivered}})
      (is (contains? (store/gate-facts s "cf-ship" now {:collection-closed? true})
                     :on-fulfillment-complete)))))

(deftest a-refund-attempt-never-reaches-the-interrupt
  (let [s (store/seed-db)
        a (operation/build s)
        r (run-req! a "t5" {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-3"
                            :patch {:to :refunded}})]
    (is (= :hold (:disposition (:state r))))
    (is (not-any? #(= :approval-requested (:t %)) (:audit (:state r)))
        "a human here is not the right human — refunds are the payout actor's")
    (is (= :shipped (:fulfillment/state (store/line-of s "fl-3"))))
    (is (contains? (set (:basis (last (store/ledger s)))) :refund-out-of-scope))))

(deftest a-failure-disclosure-interrupts-and-lands-in-the-creators-name
  (let [s (store/seed-db)
        a (operation/build s)
        held (run-req! a "t6" {:op :disclose-failure :campaign-id "cf-ship"
                               :patch {:stated-by "creator.cf-ship"
                                       :reason :tooling-cost-overrun
                                       :remedy :partial-refund
                                       :detail "60% returned" :at now}})]
    (is (nil? (store/disclosure-for s "cf-ship")))
    (is (some #(= :approval-requested (:t %)) (:audit (:state held))))
    (let [ok (g/run* a {:approval {:status :approved :by "ops-01"}}
                     {:thread-id "t6" :resume? true})
          d  (store/disclosure-for s "cf-ship")]
      (is (= :commit (:disposition (:state ok))))
      (is (= "creator.cf-ship" (:disclosure/stated-by d))
          "the approver is ops-01, but the STATEMENT belongs to the creator")
      (is (= :partial-refund (:disclosure/remedy d)))
      (is (true? (:disclosure/human? d))))))

(deftest accountability-separates-late-from-silent
  (let [s (store/seed-db)]
    (testing "posting recently: late but not silent, so nothing is owed"
      (let [a (store/accountability s "cf-ship" now "2027-02-01T00:00:00Z")]
        (is (true? (:accountability/overdue? a)))
        (is (false? (:accountability/stale? a)))
        (is (false? (:accountability/owes-disclosure? a)))))
    (testing "gone quiet as well: the pattern backers cannot tell from abandonment"
      (let [s2 (store/mem-store (assoc (store/demo-data)
                                       :updates {"cf-ship" {:count 4
                                                            :last-at "2026-11-01T00:00:00Z"}}))
            a  (store/accountability s2 "cf-ship" now "2027-02-01T00:00:00Z")]
        (is (true? (:accountability/stale? a)))
        (is (true? (:accountability/owes-disclosure? a)))
        (is (false? (:accountability/adjudicated? a))
            "observed, not concluded")))))

(deftest phase-zero-writes-nothing
  (let [s (store/seed-db)
        a (operation/build s)
        r (run-req! a "t7" {:op :send-survey :campaign-id "cf-ship" :pledge-id "fl-1"
                            :patch {:at now}}
                    (assoc ctx :phase 0))]
    (is (= :hold (:disposition (:state r))))
    (is (nil? (store/survey-of s "fl-1")))))
