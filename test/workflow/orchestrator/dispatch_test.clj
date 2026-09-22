(ns workflow.orchestrator.dispatch-test
  "Unit tests for two-phase Step dispatch and fail-closed capability
  enforcement (`workflow.orchestrator.dispatch`)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [workflow.orchestrator.dispatch :as dispatch]
            [workflow.agents :as agents]
            [workflow.store :as store]
            [workflow.orchestrator.test-support :refer [with-store temp-dir!
                                                         delete-tree!]]))

;; --- Two-phase dispatch: intent BEFORE outcome, counter advance ---

(deftest dispatch-step-commits-phase-1-intent-then-phase-2-outcome
  (testing "phase 1 records :dispatched intent; phase 2 records the outcome and
            advances the Revision counter for a test-designer/implementer step"
    (with-store
      (fn [conn ids]
        (let [dir  (temp-dir!)
              test-file "sample_test.clj"]
          (try
            ;; the test-designer authored a test file within its boundary
            (spit (io/file dir test-file) "()")
            (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
                  ctx  (merge ids
                              {:conn          conn
                               :agent-invoker fake
                               :role          :test-designer
                               :cwd           dir
                               :changes       {:created [test-file]}})
                  ;; counter starts at 0 (seeded)
                  before (store/current-revision conn (:iteration-eid ids))
                  {:keys [step-eid status violation]} (dispatch/dispatch-step! ctx)
                  step   (d/pull (d/db conn)
                                 [:step/status :step/role :step/capability
                                  :step/dispatched-at :step/outcome-at]
                                 step-eid)
                  after  (store/current-revision conn (:iteration-eid ids))]
              (is (nil? violation) "a test file authored by test-designer is within boundary")
              (is (= :complete status))
              ;; phase 1 intent was committed (dispatched-at) AND phase 2 outcome
              (is (some? (:step/dispatched-at step)))
              (is (some? (:step/outcome-at step)))
              (is (= :complete (:step/status step)))
              (is (= :test-authoring (:step/capability step)))
              ;; phase 2 advanced the Revision counter for a test-designer step
              (is (= 0 before))
              (is (= 1 after) "implementer/test-designer outcome advances :iteration/revision"))
            (finally (delete-tree! (io/file dir)))))))))

(deftest dispatch-step-fails-closed-on-capability-violation
  (testing "a test-designer touching a production file records a :failed step and
            surfaces the violation (enforcement is enforce-capability's wiring)"
    (with-store
      (fn [conn ids]
        (let [dir  (temp-dir!)
              prod-file "core.clj"]
          (try
            (spit (io/file dir prod-file) "()")
            (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
                  ctx  (merge ids
                              {:conn          conn
                               :agent-invoker fake
                               :role          :test-designer
                               :cwd           dir
                               :changes       {:created [prod-file]}})
                  {:keys [status violation]} (dispatch/dispatch-step! ctx)]
              (is (some? violation))
              (is (= :production (:class violation)))
              (is (= :failed status) "a boundary violation never records a completed step"))
            (finally (delete-tree! (io/file dir)))))))))

(deftest dispatch-step-reviewer-does-not-advance-counter
  (testing "a reviewer step advances no Revision counter (R-8.4)"
    (with-store
      (fn [conn ids]
        (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
              ctx  (merge ids
                          {:conn          conn
                           :agent-invoker fake
                           :role          :correctness-reviewer
                           :changes       {}})
              before (store/current-revision conn (:iteration-eid ids))]
          (dispatch/dispatch-step! ctx)
          (is (= before (store/current-revision conn (:iteration-eid ids)))
              "a reviewer outcome advances nothing"))))))

;; --- Fail-closed capability enforcement ---------------------------------------
;;
;; `dispatch-step!` DECIDES the boundary and surfaces a `:violation`;
;; `enforce-capability` WIRES the fail-closed transition that violation
;; implies. These tests drive each role's boundary breach with a `fake-agent`
;; + real temp files, then feed the surfaced violation through
;; `enforce-capability`, asserting the role-appropriate fail-closed error is
;; produced and the pipeline NEVER advances (no :next-state).

(deftest violation-event-maps-each-role-to-its-fail-closed-event
  (testing "role -> violation event: test-designer/implementer/both reviewers"
    (is (= :production-touched (dispatch/violation-event-for :test-designer)))
    (is (= :test-touched (dispatch/violation-event-for :implementer)))
    (is (= :reviewer-edited (dispatch/violation-event-for :correctness-reviewer)))
    (is (= :reviewer-edited (dispatch/violation-event-for :structural-reviewer)))
    (is (nil? (dispatch/violation-event-for :no-such-role))
        "an unrecognized role has no legal violation event")))

(deftest enforce-capability-no-violation-passes-through-unchanged
  (testing "with no observed violation there is nothing to enforce"
    (let [dispatched {:role :test-designer :violation nil :status :complete}
          enforced   (dispatch/enforce-capability :test-design dispatched)]
      (is (= dispatched enforced) "the dispatch map is returned unchanged")
      (is (nil? (:error enforced)))
      (is (not (:enforced? enforced))))))

(deftest test-designer-writing-production-fails-closed-and-does-not-advance
  (testing "test-designer touches a production file => :production-touched =>
            :test-designer-wrote-production; the machine stays outside :implement"
    (with-store
      (fn [conn ids]
        (let [dir       (temp-dir!)
              prod-file "core.clj"]
          (try
            (spit (io/file dir prod-file) "()")
            (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
                  ctx  (merge ids {:conn conn :agent-invoker fake
                                   :role :test-designer :cwd dir
                                   :changes {:created [prod-file]}})
                  dispatched (dispatch/dispatch-step! ctx)
                  enforced   (dispatch/enforce-capability :test-design dispatched)]
              (is (some? (:violation dispatched)) "the boundary breach was surfaced")
              (is (= :failed (:status dispatched)))
              (is (true? (:enforced? enforced)))
              (is (= :production-touched (:violation-event enforced)))
              (is (= :test-designer-wrote-production (get-in enforced [:error :code])))
              ;; fail closed: the fail-closed transition carries no :next-state
              (is (nil? (:next-state (:transition enforced)))
                  "a violation never advances the pipeline"))
            (finally (delete-tree! (io/file dir)))))))))

(deftest implementer-writing-test-fails-closed-and-does-not-advance
  (testing "implementer authors a test file => :test-touched =>
            :implementer-wrote-test; the machine stays outside review"
    (with-store
      (fn [conn ids]
        (let [dir       (temp-dir!)
              test-file "sample_test.clj"]
          (try
            (spit (io/file dir test-file) "()")
            (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
                  ctx  (merge ids {:conn conn :agent-invoker fake
                                   :role :implementer :cwd dir
                                   :changes {:created [test-file]}})
                  dispatched (dispatch/dispatch-step! ctx)
                  enforced   (dispatch/enforce-capability :implement dispatched)]
              (is (some? (:violation dispatched)))
              (is (= :failed (:status dispatched)))
              (is (true? (:enforced? enforced)))
              (is (= :test-touched (:violation-event enforced)))
              (is (= :implementer-wrote-test (get-in enforced [:error :code])))
              (is (nil? (:next-state (:transition enforced)))))
            (finally (delete-tree! (io/file dir)))))))))

(deftest reviewer-editing-any-file-fails-closed-and-does-not-advance
  (testing "either reviewer editing any file => :reviewer-edited =>
            :reviewer-attempted-repair; the machine does not advance"
    (with-store
      (fn [conn ids]
        (let [dir       (temp-dir!)
              some-file "notes.clj"]
          (try
            (spit (io/file dir some-file) "()")
            (doseq [[role state] [[:correctness-reviewer :review-correctness]
                                  [:structural-reviewer :review-structural]]]
              (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
                    ctx  (merge ids {:conn conn :agent-invoker fake
                                     :role role :cwd dir
                                     :changes {:edited [some-file]}})
                    dispatched (dispatch/dispatch-step! ctx)
                    enforced   (dispatch/enforce-capability state dispatched)]
                (is (some? (:violation dispatched))
                    (str "a reviewer editing a file is a violation for " role))
                (is (= :failed (:status dispatched)))
                (is (true? (:enforced? enforced)))
                (is (= :reviewer-edited (:violation-event enforced)))
                (is (= :reviewer-attempted-repair (get-in enforced [:error :code])))
                (is (nil? (:next-state (:transition enforced))))))
            (finally (delete-tree! (io/file dir)))))))))
