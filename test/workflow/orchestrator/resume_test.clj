(ns workflow.orchestrator.resume-test
  "Unit tests for resume / reconcile + resume-staleness
  (`workflow.orchestrator.resume`)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [workflow.orchestrator.resume :as resume]
            [workflow.orchestrator.dispatch :as dispatch]
            [workflow.orchestrator.review :as review]
            [workflow.rules.core :as core]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store temp-dir!
                                                         delete-tree!]]))

(deftest reconcile-step-records-recovered-outcome-and-advances-counter
  (testing "an in-doubt implementer step is reconciled via an injected GREEN
            verification command; its recovered outcome is recorded (:complete)
            and the Revision counter advanced (R-18.2, R-18.3, R-8.4)"
    (with-store
      (fn [conn ids]
        (let [dir       (temp-dir!)
              prod-file "feature.clj"]
          (try
            (spit (io/file dir prod-file) "()")
            ;; PHASE 1 only: an implementer step left :dispatched (crash before outcome)
            (let [iter (:iteration-eid ids)
                  {:keys [step-eid]} (dispatch/record-step-dispatch!
                                      conn {:iteration-eid iter :role :implementer})
                  step (d/pull (d/db conn) [:step/status] step-eid)
                  before (store/current-revision conn iter)]
              ;; it is in doubt: dispatched, no outcome
              (is (= :dispatched (:step/status step)))
              (is (true? (core/step-in-doubt? step)))
              ;; reconcile against observable reality: GREEN (zero exit) + artifact present
              (let [result (resume/reconcile-step!
                            {:conn conn :step-eid step-eid :iteration-eid iter
                             :role :implementer :phase :green
                             :command ["sh" "-c" "exit 0"]
                             :artifact-paths [prod-file] :cwd dir})
                    after  (store/current-revision conn iter)
                    step*  (d/pull (d/db conn) [:step/status :step/outcome-at] step-eid)]
                (is (nil? (:error result)))
                (is (= :green (:event result)) "GREEN recovered from observable reality")
                (is (= :complete (:status result)))
                ;; phase 2 recorded: the SAME step is now :complete with an outcome
                (is (= :complete (:step/status step*)))
                (is (some? (:step/outcome-at step*)))
                (is (false? (core/step-in-doubt? step*)) "no longer in doubt")
                ;; the implementer outcome advanced the counter (R-8.4)
                (is (= 0 before))
                (is (= 1 after) "reconciled implementer outcome advances :iteration/revision")))
            (finally (delete-tree! (io/file dir)))))))))

(deftest reconcile-step-fails-closed-on-indeterminate-verification
  (testing "an unrunnable verification (no command) leaves reality indeterminate;
            the step is NOT recorded and stays in doubt (R-18.5)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              {:keys [step-eid]} (dispatch/record-step-dispatch!
                                  conn {:iteration-eid iter :role :implementer})
              before (store/current-revision conn iter)
              result (resume/reconcile-step!
                      {:conn conn :step-eid step-eid :iteration-eid iter
                       :role :implementer :phase :green
                       :command []}) ; unrunnable => indeterminate
              step   (d/pull (d/db conn) [:step/status :step/outcome-at] step-eid)]
          (is (= :indeterminate (get-in result [:error :code])))
          (is (nil? (:event result)) "no outcome recovered")
          ;; fail closed: the step is not recorded and remains in doubt
          (is (= :dispatched (:step/status step)))
          (is (nil? (:step/outcome-at step)))
          (is (true? (core/step-in-doubt? step)))
          (is (= before (store/current-revision conn iter))
              "an indeterminate reconciliation advances no counter"))))))

(deftest reconcile-step-fails-closed-on-missing-artifact
  (testing "the verification ran but a required produced artifact is absent on
            disk; the step is NOT recorded and stays in doubt (R-18.5)"
    (with-store
      (fn [conn ids]
        (let [dir  (temp-dir!)]
          (try
            (let [iter (:iteration-eid ids)
                  {:keys [step-eid]} (dispatch/record-step-dispatch!
                                      conn {:iteration-eid iter :role :implementer})
                  ;; the verification passes, but the expected artifact was never produced
                  result (resume/reconcile-step!
                          {:conn conn :step-eid step-eid :iteration-eid iter
                           :role :implementer :phase :green
                           :command ["sh" "-c" "exit 0"]
                           :artifact-paths ["never-produced.clj"] :cwd dir})
                  step   (d/pull (d/db conn) [:step/status] step-eid)]
              (is (= :missing-artifact (get-in result [:error :code])))
              (is (= :dispatched (:step/status step)) "left in doubt, not recorded"))
            (finally (delete-tree! (io/file dir)))))))))

(deftest mark-stale-approvals-marks-revision-advanced-without-a-reviewer
  (testing "after a counter advance, an approval bound to the old counter is
            marked :approval/stale? with :approval/stale-reason :revision-advanced,
            recorded by the Orchestrator with NO reviewer statement (R-8.5, R-8.7)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              ;; an approval recorded against the counter in force (0)
              rev (review/record-review! conn {:iteration-eid iter :reviewer :correctness
                                               :verdict :approve :revision-counter 0})
              {:keys [approval-eid]} (review/record-approval!
                                      conn {:review-eid (:review-eid rev)
                                            :iteration-eid iter
                                            :reviewer :correctness :verdict :approve})]
          ;; before any advance the approval still holds => not stale
          (let [pre (resume/mark-stale-approvals! conn iter)]
            (is (empty? (:staled pre)) "a current approval is not stale")
            (is (nil? (:tx pre))))
          ;; advance the counter via an implementer step outcome
          (let [{:keys [step-eid]} (dispatch/record-step-dispatch!
                                    conn {:iteration-eid iter :role :implementer})]
            (store/record-step-outcome conn {:step-eid step-eid :iteration-eid iter
                                             :status :complete :advance-revision? true}))
          ;; on resume the staleness is marked as a non-reviewer fact
          (let [result (resume/mark-stale-approvals! conn iter)
                appr   (d/pull (d/db conn)
                               [:approval/stale? :approval/stale-reason
                                :approval/revision-counter]
                               approval-eid)]
            (is (= 1 (:current-counter result)) "the current counter advanced to 1")
            (is (= [approval-eid] (:staled result)))
            (is (true? (:approval/stale? appr)))
            (is (= :revision-advanced (:approval/stale-reason appr))
                "the Orchestrator recorded the staleness reason itself")
            ;; the approval's bound counter (0) is untouched: staleness is a
            ;; separate non-reviewer marker, not a re-binding or a reviewer verdict
            (is (= 0 (:approval/revision-counter appr)))
            ;; no finding / reviewer statement was written for the staleness
            (is (empty? (store/findings-for-iteration conn iter))
                "resume-staleness records no reviewer finding (R-8.7)")))))))

(deftest resume-iteration-reconciles-and-marks-staleness-on-the-same-trace
  (testing "a bare interruption resumes on the SAME trace: an in-doubt step is
            reconciled, the recovered outcome advances the counter, and an approval
            bound to the old counter is marked stale — no new iteration, no restart
            at test-design (R-18.2, R-18.3, R-8.5, R-8.7)"
    (with-store
      (fn [conn ids]
        (let [dir       (temp-dir!)
              prod-file "feature.clj"]
          (try
            (spit (io/file dir prod-file) "()")
            (let [iter (:iteration-eid ids)
                  ;; an approval bound to the counter in force (0)
                  rev (review/record-review! conn {:iteration-eid iter :reviewer :correctness
                                                    :verdict :approve :revision-counter 0})
                  {:keys [approval-eid]} (review/record-approval!
                                          conn {:review-eid (:review-eid rev)
                                                :iteration-eid iter
                                                :reviewer :correctness :verdict :approve})
                  ;; an implementer step left in doubt (dispatched, no outcome)
                  {:keys [step-eid]} (dispatch/record-step-dispatch!
                                      conn {:iteration-eid iter :role :implementer})
                  result (resume/resume-iteration!
                          conn
                          {:iteration-eid iter
                           :recovery {step-eid {:phase :green
                                                :command ["sh" "-c" "exit 0"]
                                                :artifact-paths [prod-file]
                                                :cwd dir}}})
                  step*  (d/pull (d/db conn) [:step/status] step-eid)
                  appr   (d/pull (d/db conn) [:approval/stale? :approval/stale-reason]
                                 approval-eid)]
              (is (nil? (:error result)) "the resume did not fail closed")
              ;; SAME trace: no new iteration was minted
              (is (= iter (:iteration-eid result)))
              ;; the in-doubt step was reconciled and recorded complete
              (is (= 1 (count (:reconciled result))))
              (is (= :green (:event (first (:reconciled result)))))
              (is (= :complete (:step/status step*)))
              ;; the recovered implementer outcome advanced the counter to 1
              (is (= 1 (get-in result [:staleness :current-counter])))
              ;; the approval bound to counter 0 is now stale for :revision-advanced
              (is (= [approval-eid] (get-in result [:staleness :staled])))
              (is (true? (:approval/stale? appr)))
              (is (= :revision-advanced (:approval/stale-reason appr)))
              ;; the slice was NOT restarted at test-design by the resume: no
              ;; :begin-iteration was performed and no transition event was appended
              (is (= :reconcile
                     (:slice/state (d/pull (d/db conn) [:slice/state] (:slice-eid ids))))
                  "a bare interruption resumes in place; it does not restart at test-design"))
            (finally (delete-tree! (io/file dir)))))))))

(deftest resume-iteration-fails-closed-and-does-not-mark-staleness-when-indeterminate
  (testing "if any in-doubt step reconciliation is indeterminate, the resume stops
            fail-closed BEFORE marking staleness — the step stays in doubt (R-18.5)"
    (with-store
      (fn [conn ids]
        (let [iter (:iteration-eid ids)
              rev (review/record-review! conn {:iteration-eid iter :reviewer :correctness
                                               :verdict :approve :revision-counter 0})
              {:keys [approval-eid]} (review/record-approval!
                                      conn {:review-eid (:review-eid rev)
                                            :iteration-eid iter
                                            :reviewer :correctness :verdict :approve})
              {:keys [step-eid]} (dispatch/record-step-dispatch!
                                  conn {:iteration-eid iter :role :implementer})
              result (resume/resume-iteration!
                      conn
                      {:iteration-eid iter
                       :recovery {step-eid {:phase :green :command []}}}) ; unrunnable
              step   (d/pull (d/db conn) [:step/status] step-eid)
              appr   (d/pull (d/db conn) [:approval/stale?] approval-eid)]
          (is (= :indeterminate (get-in result [:error :code])))
          (is (nil? (:staleness result)) "staleness is not marked over an unresolved step")
          (is (= :dispatched (:step/status step)) "the step stays in doubt")
          (is (not (:approval/stale? appr))
              "no approval was staled because the resume stopped fail-closed"))))))
