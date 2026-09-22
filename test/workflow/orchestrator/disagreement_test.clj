(ns workflow.orchestrator.disagreement-test
  "Unit tests for the per-Disagreement Reconciliation allowance and escalation
  on exhaustion (`workflow.orchestrator.disagreement`, reached in these tests
  via the `:escalate` effect in `workflow.orchestrator.core`)."
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [workflow.orchestrator.disagreement :as disagreement]
            [workflow.orchestrator.core :as effects]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store]]))

;; --- consume-disagreement-allowance!: per-id allowance (R-15) -----------------

(deftest consume-disagreement-allowance-decrements-per-stable-id
  (testing "each consumption increments attempts-used keyed to the stable
            :disagreement/id; the same slice+subject reuses the SAME disagreement
            so the allowance survives across rounds, exhausting at 0 (R-15)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)
              subj  "should-we-memoize"
              c1 (disagreement/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              c2 (disagreement/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})]
          ;; two attempts total (R-15.1): after 1 => 1 remaining, after 2 => 0
          (is (= 1 (:attempts-used c1)))
          (is (= 1 (:remaining c1)))
          (is (false? (:exhausted? c1)))
          (is (= 2 (:attempts-used c2)))
          (is (= 0 (:remaining c2)))
          (is (true? (:exhausted? c2)) "the allowance is exhausted after two attempts")
          ;; the SAME disagreement id was reused across the two rounds (R-15.3)
          (is (= (:disagreement-id c1) (:disagreement-id c2))
              "slice+subject recognizes the same disagreement, preserving allowance")
          ;; a DIFFERENT subject is a distinct disagreement with a fresh allowance
          (let [other (disagreement/consume-disagreement-allowance!
                       conn {:slice-eid slice :subject "unrelated-question"})]
            (is (not= (:disagreement-id c1) (:disagreement-id other)))
            (is (= 1 (:attempts-used other)) "a distinct disagreement starts fresh")))))))

;; --- escalation on exhausted allowance (R-15.4, R-15.5) -----------------------
;;
;; :escalated is reached ONLY when a Disagreement's bounded Reconciliation
;; allowance is exhausted (allowance-remaining => 0). The escalation records the
;; durable per-:disagreement/id status (:exhausted) that justifies it. A
;; Disagreement that still has attempts left never escalates.

(deftest escalate-drives-to-escalated-only-once-allowance-exhausted
  (testing "consuming a disagreement's allowance to exhaustion then :escalate
            drives the slice to :escalated and finalizes :disagreement/status
            :exhausted (R-15.4, R-15.5)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)
              subj  "should-we-cache-here"
              ;; spend both attempts (R-15.1): one initial proposal + one revision
              _  (disagreement/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              c2 (disagreement/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              _  (is (true? (:exhausted? c2)) "allowance is exhausted after two attempts")
              ctx    (merge ids {:conn conn :state :reconcile})
              result (effects/perform-effect ctx {:effect/type :escalate :subject subj})]
          ;; the transition committed and drove the slice to :escalated (R-15.4)
          (is (some? (:tx result)))
          (is (= :exhausted (:status result)))
          (is (= :escalated
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids))))
          ;; the durable per-:disagreement/id status is finalized :exhausted (R-15.5)
          (is (= :exhausted
                 (:disagreement/status
                  (d/pull (d/db conn) [:disagreement/status]
                          (:disagreement-eid result))))))))))

(deftest escalate-fails-closed-when-allowance-not-exhausted
  (testing "a Disagreement that still has attempts left does NOT escalate:
            :escalate fails closed and the slice never reaches :escalated (R-15.4)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)
              subj  "premature-escalation"
              ;; spend only ONE of the two attempts => allowance not exhausted
              c1 (disagreement/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              _  (is (= 1 (:remaining c1)) "one attempt remains")
              ctx    (merge ids {:conn conn :state :reconcile})
              result (effects/perform-effect ctx {:effect/type :escalate :subject subj})]
          (is (= :allowance-not-exhausted (get-in result [:error :code])))
          (is (nil? (:tx result)) "nothing committed on a non-exhaustion escalation")
          ;; the slice stays at :reconcile — it never escalated
          (is (not= :escalated
                    (store/derive-current-state conn (:run-eid ids) (:slice-eid ids))))
          ;; the durable status is NOT :exhausted — it is left :open
          (is (= :open
                 (:disagreement/status
                  (d/pull (d/db conn) [:disagreement/status]
                          (:disagreement-eid result))))))))))
