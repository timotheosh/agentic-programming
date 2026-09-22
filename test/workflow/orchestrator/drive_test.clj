(ns workflow.orchestrator.drive-test
  "Unit tests for the drive loop (`workflow.orchestrator.drive`)."
  (:require [clojure.test :refer [deftest is testing]]
            [workflow.orchestrator.drive :as drive]
            [workflow.rules.core :as core]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store seed-valid-finding!]]))

(deftest drive-advances-red-verified-into-implement
  (testing "test-design + red-verified advances to :implement, halting for the
            next external dispatch (no autonomous event there)"
    (let [{:keys [state error history]} (drive/drive {} :test-design :red-verified)]
      (is (nil? error))
      (is (= :implement state))
      (is (= 1 (count history)))
      (is (= :implement (:next-state (first history)))))))

(deftest drive-advances-green-into-review-correctness
  (testing "implement + green advances to :review-correctness"
    (let [{:keys [state error]} (drive/drive {} :implement :green)]
      (is (nil? error))
      (is (= :review-correctness state)))))

(deftest drive-feeds-verification-events-back-through-transition
  (testing "a :verify-red effect result's :event is fed back so the machine
            advances from :test-design to :implement in one drive call"
    ;; simulate a transition table that, on entering test-design, verifies RED.
    ;; The stock table does not raise :verify-* effects, so we exercise the
    ;; feedback wiring by having an effect return an :event the loop re-applies.
    (with-redefs [core/transitions
                  (assoc core/transitions
                         [:test-design :start]
                         {:next-state :test-design
                          :effects [{:effect/type :verify-red}]})]
      (let [ctx {:command ["sh" "-c" "exit 1"]} ; non-zero exit => :red-verified
            {:keys [state error]} (drive/drive ctx :test-design :start)]
        (is (nil? error))
        (is (= :implement state) "the fed-back :red-verified advanced to :implement")))))

(deftest drive-fails-closed-on-indeterminate-verification
  (testing "an indeterminate verification (no command) stops the loop fail-closed
            and never advances"
    (with-redefs [core/transitions
                  (assoc core/transitions
                         [:test-design :start]
                         {:next-state :test-design
                          :effects [{:effect/type :verify-red}]})]
      (let [ctx {:command []} ; unrunnable => indeterminate => fail closed
            {:keys [state error]} (drive/drive ctx :test-design :start)]
        (is (= :indeterminate (:code error)))
        (is (= :test-design state) "an indeterminate result never advances the machine")))))

(deftest drive-fails-closed-on-illegal-transition
  (testing "an illegal [state event] pair stops the loop fail-closed"
    (let [{:keys [error]} (drive/drive {} :test-design :no-such-event)]
      (is (= :illegal-transition (:code error))))))

(deftest drive-reconcile-plan-accepted-mints-new-iteration-and-halts
  (testing "reconcile + plan-accepted performs :begin-iteration (commit) and
            halts at :test-design awaiting the next external dispatch"
    (with-store
      (fn [conn ids]
        ;; a valid finding must be recorded before the round may mint (R-8.8)
        (seed-valid-finding! conn (:iteration-eid ids))
        (let [ctx (merge ids {:conn conn})
              {:keys [state error history]} (drive/drive ctx :reconcile :plan-accepted)]
          (is (nil? error))
          (is (= :test-design state))
          ;; the :begin-iteration effect committed a transition into :test-design
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids))))
          (is (some? (:tx (first (:effects (first history)))))))))))
