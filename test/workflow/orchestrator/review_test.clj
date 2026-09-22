(ns workflow.orchestrator.review-test
  "Unit tests for review rounds, findings, and the approval AND-gate
  (`workflow.orchestrator.review`).

  Exercises each helper against a temp Datalevin store + fake-agent,
  asserting the durable facts the pure core decisions are fed."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [workflow.orchestrator.review :as review]
            [workflow.orchestrator.dispatch :as dispatch]
            [workflow.agents :as agents]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store]]))

(defn- reviewer-ctx
  "A dispatch ctx for a read-only reviewer that produces NO file changes, so its
  Step completes within the read-only boundary (no capability violation)."
  [conn ids]
  (merge ids {:conn conn
              :agent-invoker (agents/fake-agent {:results [{:status :ok :exit 0}]})
              :changes {}}))

;; --- run-review-round!: correctness FIRST, structural AFTER (R-7.1) ----------

(deftest run-review-round-runs-correctness-before-structural
  (testing "the round dispatches the correctness reviewer FIRST, then structural,
            recording a review for each bound to the round's counter (R-7.1)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              fake (agents/fake-agent {:results [{:status :ok :exit 0}
                                                 {:status :ok :exit 0}]})
              round {:iteration-eid iter
                     :revision-counter 3
                     :correctness {:verdict :approve
                                   :ctx (merge ids {:agent-invoker fake :changes {}})}
                     :structural  {:verdict :approve
                                   :ctx (merge ids {:agent-invoker fake :changes {}})}}
              result (review/run-review-round! conn round)]
          ;; ordering surfaced for the caller AND observed on the fake agent
          (is (= [:correctness :structural] (:order result)))
          (is (= [:correctness-reviewer :structural-reviewer]
                 (mapv :role (agents/recorded-tasks fake)))
              "correctness reviewer was dispatched strictly before structural")
          ;; both reviews were recorded bound to the SAME counter value in force
          (let [reviews (store/reviews-for-iteration conn iter)]
            (is (= 2 (count reviews)))
            (is (every? #(= 3 (:review/revision-counter %)) reviews)
                "both reviews judged the same counter value (R-8.1)"))
          ;; neither reviewer Step was a capability violation (read-only, no edits)
          (is (nil? (get-in result [:correctness :dispatched :violation])))
          (is (nil? (get-in result [:structural :dispatched :violation]))))))))

(deftest run-review-round-structural-runs-even-on-correctness-request-changes
  (testing "a correctness REQUEST_CHANGES still runs structural (R-7.2)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              round {:iteration-eid iter
                     :revision-counter 0
                     :correctness {:verdict :request-changes :ctx (reviewer-ctx conn ids)}
                     :structural  {:verdict :approve :ctx (reviewer-ctx conn ids)}}
              _      (review/run-review-round! conn round)
              reviews (store/reviews-for-iteration conn iter)
              by      (into {} (map (juxt :review/reviewer identity)) reviews)]
          (is (= :request-changes (:review/verdict (:correctness by))))
          (is (= :approve (:review/verdict (:structural by)))
              "structural ran and recorded a verdict despite correctness withholding"))))))

(deftest run-review-round-feeds-correctness-findings-to-structural
  (testing "correctness findings recorded before structural runs are fed to the
            structural review via :review/inputs-ref (R-9.1)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              ;; record a correctness review + a valid finding under it FIRST
              c-review (review/record-review! conn {:iteration-eid iter
                                                     :reviewer :correctness
                                                     :verdict :request-changes
                                                     :revision-counter 0})
              _ (review/record-finding! conn {:review-eid (:review-eid c-review)
                                              :owner :implementer
                                              :problem "off-by-one in paginate"
                                              :evidence "test paginate-boundary fails"
                                              :justification "R-2 requires correct bounds"
                                              :required-outcome "clamp offset to [0,n]"})
              ;; the structural inputs reference should now mention that finding
              inputs (review/correctness-findings-input conn iter)]
          (is (str/includes? inputs "off-by-one in paginate")
              "the fed inputs reference the correctness finding's problem")
          (is (str/includes? inputs "clamp offset")
              "the fed inputs reference the finding's required outcome")
          ;; and a full round stamps :review/inputs-ref on the structural review
          (let [round {:iteration-eid iter
                       :revision-counter 0
                       :correctness {:verdict :request-changes :ctx (reviewer-ctx conn ids)}
                       :structural  {:verdict :approve :ctx (reviewer-ctx conn ids)}}
                result (review/run-review-round! conn round)
                s-review (->> (store/reviews-for-iteration conn iter)
                              (filter #(= :structural (:review/reviewer %)))
                              last)]
            (is (some? (:review/inputs-ref s-review))
                "the structural review carries the correctness inputs reference")
            (is (str/includes? (:review/inputs-ref s-review)
                               "off-by-one in paginate"))
            (is (= (:review/inputs-ref s-review)
                   (get-in result [:structural :inputs-ref]))
                "the surfaced inputs-ref matches what was persisted")))))))

;; --- record-finding!: R-10 validity (Property 8) -----------------------------

(deftest record-finding-marks-a-complete-finding-valid
  (testing "a finding with all four R-10 components is valid (R-10.2)"
    (with-store
      (fn [conn ids]
        (let [review (review/record-review! conn {:iteration-eid (:iteration-eid ids)
                                                   :reviewer :correctness
                                                   :verdict :request-changes
                                                   :revision-counter 0})
              {:keys [valid? finding-eid]}
              (review/record-finding! conn {:review-eid (:review-eid review)
                                            :owner :both
                                            :problem "p" :evidence "e"
                                            :justification "j" :required-outcome "o"})]
          (is (true? valid?))
          (is (some? finding-eid)))))))

(deftest record-finding-marks-an-incomplete-finding-invalid
  (testing "a finding missing any R-10 component is invalid (R-10.2)"
    (with-store
      (fn [conn ids]
        (let [review (review/record-review! conn {:iteration-eid (:iteration-eid ids)
                                                   :reviewer :correctness
                                                   :verdict :request-changes
                                                   :revision-counter 0})
              ;; omit :required-outcome
              {:keys [valid?]}
              (review/record-finding! conn {:review-eid (:review-eid review)
                                            :owner :implementer
                                            :problem "p" :evidence "e"
                                            :justification "j"})]
          (is (false? valid?) "a missing R-10 component makes the finding invalid"))))))

;; --- record-approval! + both-approved?: the AND-gate (R-16.4) ----------------

(deftest record-approval-binds-to-the-current-revision-counter
  (testing "an approval binds to the Iteration's current Revision counter (R-8.3)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              review (review/record-review! conn {:iteration-eid iter
                                                   :reviewer :correctness
                                                   :verdict :approve
                                                   :revision-counter 0})
              a0 (review/record-approval! conn {:review-eid (:review-eid review)
                                                :iteration-eid iter
                                                :reviewer :correctness
                                                :verdict :approve})]
          (is (= 0 (:revision-counter a0)) "bound to the counter in force (0)")
          ;; advance the counter via a test-designer step outcome
          (let [{:keys [step-eid]} (dispatch/record-step-dispatch!
                                    conn {:iteration-eid iter :role :test-designer})]
            (store/record-step-outcome conn {:step-eid step-eid
                                             :iteration-eid iter
                                             :status :complete
                                             :advance-revision? true}))
          (let [review2 (review/record-review! conn {:iteration-eid iter
                                                      :reviewer :correctness
                                                      :verdict :approve
                                                      :revision-counter 1})
                a1 (review/record-approval! conn {:review-eid (:review-eid review2)
                                                  :iteration-eid iter
                                                  :reviewer :correctness
                                                  :verdict :approve})]
            (is (= 1 (:revision-counter a1))
                "a later approval binds to the advanced counter value (1)")))))))

(deftest both-approved-holds-only-when-both-approve-the-same-counter
  (testing "the AND-gate holds iff both reviewers approve the SAME current
            counter value; a single approval or a stale bind does not (R-16.4)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              c-review (review/record-review! conn {:iteration-eid iter :reviewer :correctness
                                                     :verdict :approve :revision-counter 0})
              s-review (review/record-review! conn {:iteration-eid iter :reviewer :structural
                                                     :verdict :approve :revision-counter 0})]
          ;; only correctness has approved => gate does NOT hold
          (review/record-approval! conn {:review-eid (:review-eid c-review)
                                         :iteration-eid iter :reviewer :correctness :verdict :approve})
          (is (false? (review/both-approved? conn iter))
              "one approval is not enough")
          ;; both approve the current counter (0) => gate holds
          (review/record-approval! conn {:review-eid (:review-eid s-review)
                                         :iteration-eid iter :reviewer :structural :verdict :approve})
          (is (true? (review/both-approved? conn iter))
              "both reviewers approve the same current counter value")
          ;; advancing the counter stales both approvals => gate no longer holds
          (let [{:keys [step-eid]} (dispatch/record-step-dispatch!
                                    conn {:iteration-eid iter :role :implementer})]
            (store/record-step-outcome conn {:step-eid step-eid :iteration-eid iter
                                             :status :complete :advance-revision? true}))
          (is (false? (review/both-approved? conn iter))
              "a later implementer outcome stales approvals bound to the old counter"))))))
