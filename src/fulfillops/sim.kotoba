(ns fulfillops.sim
  "Offline demo: survey a backer, ship with a tracking reference, watch an
  untracked shipment and a smuggled address get refused, watch a refund
  attempt hit a permanent scope exclusion, and see the payout gates this
  actor's records unlock. `clojure -M:dev:run`."
  (:require [fulfillops.operation :as operation]
            [fulfillops.store :as store]
            [langgraph.graph :as g]))

(def ^:private now "2027-05-01T00:00:00Z")
(def ^:private ctx {:actor-id "fulfillment-demo" :phase 3 :now now})

(defn- run-req! [actor tid request]
  (g/run* actor {:request request :context ctx} {:thread-id tid}))

(defn -main [& _]
  (let [s (store/seed-db)
        actor (operation/build s)]

    (println "\n=== 1. アンケート送付と回答（住所は参照のみ）===")
    (run-req! actor "sim-1a" {:op :send-survey :campaign-id "cf-ship" :pledge-id "fl-1"
                              :patch {:at "2027-01-05T00:00:00Z"}})
    (let [r (run-req! actor "sim-1b" {:op :record-response :campaign-id "cf-ship"
                                      :pledge-id "fl-1"
                                      :patch {:address-ref "addr:9a3f"
                                              :contact-ref "contact:9a3f"
                                              :options ["iso-jp"]
                                              :at "2027-01-09T00:00:00Z"}})]
      (println "  status  :" (:status r))
      (println "  状態     :" (:fulfillment/state (store/line-of s "fl-1")))
      (println "  保持形式 :" (keys (store/response-of s "fl-1"))))

    (println "\n=== 2. 住所そのものを持ち込む提案は拒否（記録は台帳にも残る）===")
    (let [r (run-req! actor "sim-2" {:op :record-response :campaign-id "cf-ship"
                                     :pledge-id "fl-2"
                                     :patch {:address-ref "addr:1" :contact-ref "c:1"
                                             :options [{:postcode "150-0001"
                                                        :phone "03-0000-0000"}]
                                             :at "2027-01-09T00:00:00Z"}})]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 3. 追跡番号のない発送は拒否（支援者が確認できないため）===")
    (let [r (run-req! actor "sim-3a" {:op :ship-line :campaign-id "cf-ship"
                                      :pledge-id "fl-2"
                                      :patch {:carrier "yamato" :at now}})]
      (println "  追跡なし   :" (:status r)
               (mapv :rule (:violations (last (store/ledger s))))))
    (run-req! actor "sim-3b" {:op :ship-line :campaign-id "cf-ship" :pledge-id "fl-2"
                              :patch {:tracking-ref "YT-0002" :carrier "yamato" :at now}})
    (println "  追跡あり   :" (:fulfillment/state (store/line-of s "fl-2"))
             (:fulfillment/tracking-ref (store/line-of s "fl-2")))

    (println "\n=== 4. 返金は本アクターの永久的な対象外 ===")
    (let [r (run-req! actor "sim-4" {:op :advance-line :campaign-id "cf-ship"
                                     :pledge-id "fl-3" :patch {:to :refunded}})]
      (println "  status     :" (:status r))
      (println "  violations :" (mapv :rule (:violations (last (store/ledger s))))))

    (println "\n=== 5. 支払 gate は履行記録から導出される（この面が payout を解錠する）===")
    (run-req! actor "sim-5" {:op :advance-line :campaign-id "cf-ship" :pledge-id "fl-3"
                             :patch {:to :delivered}})
    (let [p (store/progress s "cf-ship" now)]
      (println "  進捗       :" (:fulfillment/by-state p))
      (println "  完了率     :" (:fulfillment/completion-bps p) "bp／完了:"
               (:fulfillment/complete? p))
      (println "  gate       :" (store/gate-facts s "cf-ship" now {:collection-closed? true})))

    (println "\n=== 6. 履行不能の表明は必ず創作者本人の名前で、人間の承認を通る ===")
    (let [held (run-req! actor "sim-6" {:op :disclose-failure :campaign-id "cf-ship"
                                        :patch {:stated-by "creator.cf-ship"
                                                :reason :tooling-cost-overrun
                                                :remedy :partial-refund
                                                :detail "60% を返金し残りは代替品で対応"
                                                :at now}})]
      (println "  status  :" (:status held) "（承認前）")
      (let [ok (g/run* actor {:approval {:status :approved :by "ops-01"}}
                       {:thread-id "sim-6" :resume? true})
            d  (store/disclosure-for s "cf-ship")]
        (println "  status  :" (:status ok))
        (println "  表明者   :" (:disclosure/stated-by d) "／対応:" (:disclosure/remedy d))))

    (println "\n=== 7. 遅延と沈黙は別の事実（両方揃って初めて説明責任が生じる）===")
    (let [a (store/accountability s "cf-ship" now "2027-02-01T00:00:00Z")]
      (println "  遅延      :" (:accountability/overdue? a))
      (println "  沈黙      :" (:accountability/stale? a))
      (println "  要説明    :" (:accountability/owes-disclosure? a))
      (println "  裁定済み  :" (:accountability/adjudicated? a)))

    (println "\n=== 監査台帳 ===")
    (doseq [f (store/ledger s)]
      (println " " (:t f) (:op f) (or (:basis f) "")))))
