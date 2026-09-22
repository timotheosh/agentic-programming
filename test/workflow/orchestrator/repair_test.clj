(ns workflow.orchestrator.repair-test
  "Unit tests for repair proposals and reconsideration admissibility
  (`workflow.orchestrator.repair`)."
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [workflow.orchestrator.repair :as repair]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store]]))

;; --- propose-repair! / accept-proposal!: binding conflict + amendments -------

(deftest propose-repair-rejects-a-plan-conflicting-with-a-binding-decision
  (testing "a repair plan that contradicts a binding decision is rejected, not
            committed, and the conflicting decision is surfaced (R-13.2)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)]
          ;; a binding decision on subject "retry-policy": statement "no retries"
          (d/transact! conn [{:db/id -1
                              :decision/id (random-uuid)
                              :decision/slice slice
                              :decision/subject "retry-policy"
                              :decision/statement "no retries"
                              :decision/status :binding}])
          ;; a repair proposing a DIFFERENT outcome on the same subject conflicts
          (let [rejected (repair/propose-repair! conn {:slice-eid slice
                                                        :subject "retry-policy"
                                                        :body "retry three times"})]
            (is (some? (:conflict rejected)) "the plan was rejected")
            (is (= "retry-policy" (:decision/subject (:conflict rejected))))
            (is (empty? (store/proposals-for-slice conn slice))
                "nothing was committed for a rejected plan"))
          ;; a repair AGREEING with the binding statement is admitted
          (let [ok (repair/propose-repair! conn {:slice-eid slice
                                                  :subject "retry-policy"
                                                  :body "no retries"})]
            (is (some? (:proposal-eid ok)))
            (is (= 1 (count (store/proposals-for-slice conn slice))))))))))

(deftest amendment-is-a-new-proposal-whose-acceptances-do-not-carry-forward
  (testing "an amended plan is a NEW :proposal entity; acceptance of the
            superseded plan does not authorize the amendment (R-12.3, R-12.4)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)
              p0 (repair/propose-repair! conn {:slice-eid slice :subject "s" :body "plan v1"})
              ;; both reviewers accept the first plan => authorized
              _  (repair/accept-proposal! conn {:proposal-eid (:proposal-eid p0) :reviewer :correctness})
              _  (repair/accept-proposal! conn {:proposal-eid (:proposal-eid p0) :reviewer :structural})
              p0* (->> (store/proposals-for-slice conn slice)
                       (filter #(= (:proposal-id p0) (:proposal/id %))) first)
              ;; amend: a NEW entity superseding the first
              p1 (repair/propose-repair! conn {:slice-eid slice :subject "s" :body "plan v2"
                                               :supersedes (:proposal-eid p0)})
              p1* (->> (store/proposals-for-slice conn slice)
                       (filter #(= (:proposal-id p1) (:proposal/id %))) first)]
          (is (repair/repair-authorized? p0*) "the first plan was accepted by both")
          (is (not= (:proposal-eid p0) (:proposal-eid p1)) "the amendment is a NEW entity")
          (is (not (repair/repair-authorized? p1*))
              "the amendment starts with EMPTY acceptances; they do not carry forward")
          ;; both must accept the amendment afresh to authorize it
          (repair/accept-proposal! conn {:proposal-eid (:proposal-eid p1) :reviewer :correctness})
          (repair/accept-proposal! conn {:proposal-eid (:proposal-eid p1) :reviewer :structural})
          (let [p1** (->> (store/proposals-for-slice conn slice)
                          (filter #(= (:proposal-id p1) (:proposal/id %))) first)]
            (is (repair/repair-authorized? p1**)
                "the amendment is authorized only after both accept it afresh")))))))

;; --- reconsideration admissibility (R-14) ------------------------------------

(deftest reconsideration-admissible-requires-decision-id-and-new-evidence
  (testing "admissible iff a specific decision is named AND non-blank new
            evidence is supplied (R-14.1, R-14.2)"
    (is (true? (repair/reconsideration-admissible?
                {:reconsideration/decision-id (random-uuid)
                 :reconsideration/new-evidence "profiler shows the regression"})))
    (is (false? (repair/reconsideration-admissible?
                 {:reconsideration/new-evidence "evidence but no decision named"})))
    (is (false? (repair/reconsideration-admissible?
                 {:reconsideration/decision-id (random-uuid)
                  :reconsideration/new-evidence "   "})))))
