(ns workflow.orchestrator.core-test
  "Unit tests for the `perform-effect` multimethod scaffolding
  (`workflow.orchestrator.core`).

  Exercises each defmethod with a deterministic `workflow.agents/fake-agent`
  (no real process spawning) and a temporary Datalevin directory (opened and
  removed per test), asserting the return-value contract the drive loop
  relies on. RED/GREEN verification is driven with an injectable shell command
  (`sh -c 'exit N'`) so no real suite is spawned; capability observation is
  driven against real temp files so `fs/observe-changes` confirms them on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [workflow.orchestrator.core :as effects]
            [workflow.orchestrator.review :as review]
            [workflow.agents :as agents]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store temp-dir!
                                                         delete-tree!
                                                         seed-valid-finding!]]))

;; --- the multimethod exists with every declared method -----------------------

(deftest all-effect-methods-are-defined
  (testing "defmulti + every declared defmethod live in this one namespace"
    (let [defined (set (keys (methods effects/perform-effect)))]
      (doseq [effect [:dispatch-agent :verify-red :verify-green :verify-capability
                      :begin-iteration :route-conflict-to-test-designer :escalate
                      :default]]
        (is (contains? defined effect)
            (str "perform-effect has a method for " effect))))))

(deftest unknown-effect-fails-closed
  (testing ":default method fails closed on an undeclared effect type"
    (let [result (effects/perform-effect {} {:effect/type :no-such-effect})]
      (is (= :unknown-effect (get-in result [:error :code]))))))

;; --- :dispatch-agent ----------------------------------------------------------

(deftest dispatch-agent-invokes-through-the-invoker
  (testing "builds a capability-scoped task and invokes the AgentInvoker"
    (let [fake (agents/fake-agent {:results [{:status :ok :exit 0 :stdout "done"}]})
          ctx  {:agent-invoker fake
                :role          :test-designer
                :iteration-eid 42
                :prompt        "write a failing test"}
          {:keys [result task]} (effects/perform-effect ctx {:effect/type :dispatch-agent})]
      (is (= :ok (:status result)))
      (is (= :test-designer (:role task)))
      ;; capability defaults to the role's descriptor
      (is (= :test-authoring (:capability task)))
      (is (= 42 (:iteration-id task)))
      ;; the fake recorded exactly the task dispatched
      (is (= [task] (agents/recorded-tasks fake))))))

;; --- :verify-red --------------------------------------------------------------

(deftest verify-red-nonzero-exit-is-red-verified
  (testing "a failing test (non-zero exit) confirms RED"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 1"]}
                                         {:effect/type :verify-red})]
      (is (= :red-verified (:event result))))))

(deftest verify-red-zero-exit-is-red-invalid
  (testing "a passing test (zero exit) is not RED"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 0"]}
                                         {:effect/type :verify-red})]
      (is (= :red-invalid (:event result))))))

(deftest verify-red-unrunnable-fails-closed
  (testing "no command => reality indeterminate => fail closed (no :event)"
    (let [result (effects/perform-effect {:command []} {:effect/type :verify-red})]
      (is (nil? (:event result)))
      (is (= :indeterminate (get-in result [:error :code]))))))

;; --- :verify-green ------------------------------------------------------------

(deftest verify-green-zero-exit-is-green
  (testing "a passing suite (zero exit) is GREEN"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 0"]}
                                         {:effect/type :verify-green})]
      (is (= :green (:event result))))))

(deftest verify-green-nonzero-exit-still-red
  (testing "a failing suite (non-zero exit) means GREEN did not land"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 1"]}
                                         {:effect/type :verify-green})]
      (is (= :red-verified (:event result))))))

(deftest verify-green-unrunnable-fails-closed
  (testing "no command => reality indeterminate => fail closed"
    (let [result (effects/perform-effect {:command []} {:effect/type :verify-green})]
      (is (nil? (:event result)))
      (is (= :indeterminate (get-in result [:error :code]))))))

;; --- :verify-capability -------------------------------------------------------

(deftest verify-capability-within-boundary
  (testing "a test-designer authoring a test file is within boundary"
    (let [dir  (temp-dir!)
          test-file "sample_test.clj"]
      (try
        (spit (io/file dir test-file) "()")
        (let [result (effects/perform-effect
                      {:role :test-designer
                       :cwd  dir
                       :changes {:created [test-file]}}
                      {:effect/type :verify-capability})]
          (is (nil? (:violation result)))
          (is (seq (:changes result))))
        (finally (delete-tree! (io/file dir)))))))

(deftest verify-capability-violation-fails-closed
  (testing "a test-designer touching a production file is a violation"
    (let [dir  (temp-dir!)
          prod-file "core.clj"]
      (try
        (spit (io/file dir prod-file) "()")
        (let [result (effects/perform-effect
                      {:role :test-designer
                       :cwd  dir
                       :changes {:created [prod-file]}}
                      {:effect/type :verify-capability})]
          (is (some? (:violation result)))
          (is (= :production (:class (:violation result)))))
        (finally (delete-tree! (io/file dir)))))))

;; --- transact effects: :begin-iteration / :route-conflict / :escalate --------

(deftest begin-iteration-carries-valid-finding-and-mints-at-revision-0
  (testing ":begin-iteration mints a new iteration (revision 0) carrying a valid
            finding and links it both ways (R-8.8, R-8.9)"
    (with-store
      (fn [conn ids]
        (let [finding-eid (seed-valid-finding! conn (:iteration-eid ids))
              ctx    (merge ids {:conn conn :state :reconcile})
              result (effects/perform-effect ctx {:effect/type :begin-iteration})]
          (is (some? (:tx result)))
          ;; a NEW iteration was minted, distinct from the prior trace
          (is (some? (:iteration-eid result)))
          (is (not= (:iteration-eid ids) (:iteration-eid result)))
          ;; the new iteration starts fresh at revision 0
          (is (= 0 (:revision result)))
          (is (= 0 (store/current-revision conn (:iteration-eid result))))
          ;; both inverse links are set in the mint transaction
          (is (= finding-eid (:finding-eid result)))
          (is (= finding-eid
                 (:db/id (:iteration/seeded-from-finding
                          (d/pull (d/db conn)
                                  [{:iteration/seeded-from-finding [:db/id]}]
                                  (:iteration-eid result))))))
          (is (= (:iteration-eid result)
                 (:db/id (:finding/carried-to-iteration
                          (d/pull (d/db conn)
                                  [{:finding/carried-to-iteration [:db/id]}]
                                  finding-eid)))))
          ;; the slice's materialized state is now :test-design on the new trace
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))))))))

(deftest begin-iteration-fails-closed-without-a-valid-finding
  (testing ":begin-iteration fails closed (mints nothing) when no valid finding
            was recorded before the mint (R-8.8, R-8.9)"
    (with-store
      (fn [conn ids]
        (let [ctx    (merge ids {:conn conn :state :reconcile})
              result (effects/perform-effect ctx {:effect/type :begin-iteration})]
          (is (= :no-valid-finding (get-in result [:error :code])))
          ;; nothing was committed: no new iteration, slice state unchanged
          (is (nil? (:iteration-eid result)))
          (is (= :reconcile (:slice/state
                             (d/pull (d/db conn) [:slice/state] (:slice-eid ids))))))))))

(deftest begin-iteration-fails-closed-when-only-invalid-findings-exist
  (testing "an INVALID finding (missing R-10 fields) does not satisfy the gate;
            :begin-iteration still fails closed (R-8.8, R-8.9, R-10.2)"
    (with-store
      (fn [conn ids]
        (let [{:keys [review-eid]} (review/record-review!
                                    conn {:iteration-eid (:iteration-eid ids)
                                          :reviewer :correctness
                                          :verdict :request-changes
                                          :revision-counter 0})]
          ;; a finding missing evidence/justification/required-outcome is invalid
          (review/record-finding! conn {:review-eid review-eid
                                        :owner :implementer
                                        :problem "something is off"})
          (let [ctx    (merge ids {:conn conn :state :reconcile})
                result (effects/perform-effect ctx {:effect/type :begin-iteration})]
            (is (= :no-valid-finding (get-in result [:error :code])))
            (is (nil? (:iteration-eid result)))))))))

(deftest route-conflict-appends-transition-to-test-design
  (testing ":route-conflict-to-test-designer routes back to :test-design"
    (with-store
      (fn [conn ids]
        (let [ctx    (merge ids {:conn conn :state :implement})
              result (effects/perform-effect ctx {:effect/type :route-conflict-to-test-designer})]
          (is (some? (:tx result)))
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))))))))

(deftest escalate-appends-transition-to-escalated
  (testing ":escalate commits a transition into :escalated"
    (with-store
      (fn [conn ids]
        (let [ctx    (merge ids {:conn conn :state :reconcile})
              result (effects/perform-effect ctx {:effect/type :escalate})]
          (is (some? (:tx result)))
          (is (= :escalated
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))))))))

(deftest test-conflict-never-reaches-escalated
  (testing "a reported test conflict routes back to :test-design on the current
            iteration and is NEVER a path to :escalated (R-3.3, R-3.4, R-15.4)"
    (with-store
      (fn [conn ids]
        (let [ctx (merge ids {:conn conn :state :implement})]
          (effects/perform-effect ctx {:effect/type :route-conflict-to-test-designer})
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))
              "a test conflict routes to :test-design, not :escalated"))))))
