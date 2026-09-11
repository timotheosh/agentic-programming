(ns workflow.orchestrator-test
  "Unit tests for the `perform-effect` multimethod scaffolding (task 8.1).

  Exercises each defmethod with a deterministic `workflow.agents/fake-agent`
  (no real process spawning) and a temporary Datalevin directory (opened and
  removed per test), asserting the return-value contract the drive loop (8.2)
  relies on. RED/GREEN verification is driven with an injectable shell command
  (`sh -c 'exit N'`) so no real suite is spawned; capability observation is
  driven against real temp files so `fs/observe-changes` confirms them on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [workflow.orchestrator :as orch]
            [workflow.core :as core]
            [workflow.agents :as agents]
            [workflow.store :as store]))

;; --- temp Datalevin dir + seed run/slice/iteration ---------------------------

(defn- delete-tree!
  "Recursively delete `file` (a temp Datalevin dir) after a test."
  [^java.io.File file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-tree! child)))
  (.delete file))

(defn- temp-dir!
  "Create and return a fresh temp directory path (string) for a Datalevin store."
  []
  (let [f (java.io.File/createTempFile "orch-test" "")]
    (.delete f)
    (.mkdirs f)
    (.getAbsolutePath f)))

(defn- seed-run+slice+iteration!
  "Transact a minimal run/slice/iteration and return their entity ids so the
  transact effects have real targets to append transition events against."
  [conn]
  (let [run-id   (random-uuid)
        slice-id (random-uuid)
        iter-id  (random-uuid)
        report   (d/transact! conn
                              [{:db/id -1 :run/id run-id :run/state :planning}
                               {:db/id -2 :slice/id slice-id :slice/run -1
                                :slice/order 0 :slice/state :reconcile}
                               {:db/id -3 :iteration/id iter-id :iteration/slice -2
                                :iteration/number 0 :iteration/revision 0}])
        tempids  (:tempids report)]
    {:run-eid       (get tempids -1)
     :slice-eid     (get tempids -2)
     :iteration-eid (get tempids -3)}))

(defn- seed-valid-finding!
  "Record a REQUEST_CHANGES review and one R-10-compliant `:finding` under
  Iteration `iteration-eid`, so `:begin-iteration` has a durable valid finding to
  carry (R-8.8, R-8.9). Returns the finding entity id."
  [conn iteration-eid]
  (let [{:keys [review-eid]} (orch/record-review! conn {:iteration-eid iteration-eid
                                                        :reviewer :correctness
                                                        :verdict :request-changes
                                                        :revision-counter 0})
        {:keys [finding-eid]} (orch/record-finding!
                               conn
                               {:review-eid review-eid
                                :owner :implementer
                                :problem "behavior B is missing"
                                :evidence "test T fails on input X"
                                :justification "requirement R-99 mandates B"
                                :required-outcome "implement B so T passes"
                                :revision-counter 0})]
    finding-eid))

(defn- with-store
  "Open a temp Datalevin store, seed a run/slice/iteration, and call `f` with
  the connection and the seeded ids map. Always closes + deletes the store."
  [f]
  (let [dir  (temp-dir!)
        conn (store/connect dir)]
    (try
      (f conn (seed-run+slice+iteration! conn))
      (finally
        (store/close conn)
        (delete-tree! (io/file dir))))))

;; --- the multimethod exists with every declared method -----------------------

(deftest all-effect-methods-are-defined
  (testing "defmulti + every declared defmethod live in this one namespace"
    (let [defined (set (keys (methods orch/perform-effect)))]
      (doseq [effect [:dispatch-agent :verify-red :verify-green :verify-capability
                      :begin-iteration :route-conflict-to-test-designer :escalate
                      :default]]
        (is (contains? defined effect)
            (str "perform-effect has a method for " effect))))))

(deftest unknown-effect-fails-closed
  (testing ":default method fails closed on an undeclared effect type"
    (let [result (orch/perform-effect {} {:effect/type :no-such-effect})]
      (is (= :unknown-effect (get-in result [:error :code]))))))

;; --- :dispatch-agent ----------------------------------------------------------

(deftest dispatch-agent-invokes-through-the-invoker
  (testing "builds a capability-scoped task and invokes the AgentInvoker"
    (let [fake (agents/fake-agent {:results [{:status :ok :exit 0 :stdout "done"}]})
          ctx  {:agent-invoker fake
                :role          :test-designer
                :iteration-eid 42
                :prompt        "write a failing test"}
          {:keys [result task]} (orch/perform-effect ctx {:effect/type :dispatch-agent})]
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
    (let [result (orch/perform-effect {:command ["sh" "-c" "exit 1"]}
                                      {:effect/type :verify-red})]
      (is (= :red-verified (:event result))))))

(deftest verify-red-zero-exit-is-red-invalid
  (testing "a passing test (zero exit) is not RED"
    (let [result (orch/perform-effect {:command ["sh" "-c" "exit 0"]}
                                      {:effect/type :verify-red})]
      (is (= :red-invalid (:event result))))))

(deftest verify-red-unrunnable-fails-closed
  (testing "no command => reality indeterminate => fail closed (no :event)"
    (let [result (orch/perform-effect {:command []} {:effect/type :verify-red})]
      (is (nil? (:event result)))
      (is (= :indeterminate (get-in result [:error :code]))))))

;; --- :verify-green ------------------------------------------------------------

(deftest verify-green-zero-exit-is-green
  (testing "a passing suite (zero exit) is GREEN"
    (let [result (orch/perform-effect {:command ["sh" "-c" "exit 0"]}
                                      {:effect/type :verify-green})]
      (is (= :green (:event result))))))

(deftest verify-green-nonzero-exit-still-red
  (testing "a failing suite (non-zero exit) means GREEN did not land"
    (let [result (orch/perform-effect {:command ["sh" "-c" "exit 1"]}
                                      {:effect/type :verify-green})]
      (is (= :red-verified (:event result))))))

(deftest verify-green-unrunnable-fails-closed
  (testing "no command => reality indeterminate => fail closed"
    (let [result (orch/perform-effect {:command []} {:effect/type :verify-green})]
      (is (nil? (:event result)))
      (is (= :indeterminate (get-in result [:error :code]))))))

;; --- :verify-capability -------------------------------------------------------

(deftest verify-capability-within-boundary
  (testing "a test-designer authoring a test file is within boundary"
    (let [dir  (temp-dir!)
          test-file "sample_test.clj"]
      (try
        (spit (io/file dir test-file) "()")
        (let [result (orch/perform-effect
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
        (let [result (orch/perform-effect
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
              result (orch/perform-effect ctx {:effect/type :begin-iteration})]
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
              result (orch/perform-effect ctx {:effect/type :begin-iteration})]
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
        (let [{:keys [review-eid]} (orch/record-review!
                                    conn {:iteration-eid (:iteration-eid ids)
                                          :reviewer :correctness
                                          :verdict :request-changes
                                          :revision-counter 0})]
          ;; a finding missing evidence/justification/required-outcome is invalid
          (orch/record-finding! conn {:review-eid review-eid
                                      :owner :implementer
                                      :problem "something is off"})
          (let [ctx    (merge ids {:conn conn :state :reconcile})
                result (orch/perform-effect ctx {:effect/type :begin-iteration})]
            (is (= :no-valid-finding (get-in result [:error :code])))
            (is (nil? (:iteration-eid result)))))))))

(deftest route-conflict-appends-transition-to-test-design
  (testing ":route-conflict-to-test-designer routes back to :test-design"
    (with-store
      (fn [conn ids]
        (let [ctx    (merge ids {:conn conn :state :implement})
              result (orch/perform-effect ctx {:effect/type :route-conflict-to-test-designer})]
          (is (some? (:tx result)))
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))))))))

(deftest escalate-appends-transition-to-escalated
  (testing ":escalate commits a transition into :escalated"
    (with-store
      (fn [conn ids]
        (let [ctx    (merge ids {:conn conn :state :reconcile})
              result (orch/perform-effect ctx {:effect/type :escalate})]
          (is (some? (:tx result)))
          (is (= :escalated
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))))))))

;; --- Two-phase dispatch (task 8.2): intent BEFORE outcome, counter advance ---

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
                  {:keys [step-eid status violation]} (orch/dispatch-step! ctx)
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
            surfaces the violation (enforcement transition is task 8.4)"
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
                  {:keys [status violation]} (orch/dispatch-step! ctx)]
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
          (orch/dispatch-step! ctx)
          (is (= before (store/current-revision conn (:iteration-eid ids)))
              "a reviewer outcome advances nothing"))))))

;; --- The drive loop (task 8.2) -----------------------------------------------

(deftest drive-advances-red-verified-into-implement
  (testing "test-design + red-verified advances to :implement, halting for the
            next external dispatch (no autonomous event there)"
    (let [{:keys [state error history]} (orch/drive {} :test-design :red-verified)]
      (is (nil? error))
      (is (= :implement state))
      (is (= 1 (count history)))
      (is (= :implement (:next-state (first history)))))))

(deftest drive-advances-green-into-review-correctness
  (testing "implement + green advances to :review-correctness"
    (let [{:keys [state error]} (orch/drive {} :implement :green)]
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
            {:keys [state error]} (orch/drive ctx :test-design :start)]
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
            {:keys [state error]} (orch/drive ctx :test-design :start)]
        (is (= :indeterminate (:code error)))
        (is (= :test-design state) "an indeterminate result never advances the machine")))))

(deftest drive-fails-closed-on-illegal-transition
  (testing "an illegal [state event] pair stops the loop fail-closed"
    (let [{:keys [error]} (orch/drive {} :test-design :no-such-event)]
      (is (= :illegal-transition (:code error))))))

(deftest drive-reconcile-plan-accepted-mints-new-iteration-and-halts
  (testing "reconcile + plan-accepted performs :begin-iteration (commit) and
            halts at :test-design awaiting the next external dispatch"
    (with-store
      (fn [conn ids]
        ;; a valid finding must be recorded before the round may mint (R-8.8)
        (seed-valid-finding! conn (:iteration-eid ids))
        (let [ctx (merge ids {:conn conn})
              {:keys [state error history]} (orch/drive ctx :reconcile :plan-accepted)]
          (is (nil? error))
          (is (= :test-design state))
          ;; the :begin-iteration effect committed a transition into :test-design
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids))))
          (is (some? (:tx (first (:effects (first history)))))))))))

;; --- an end-to-end scripted slice through the two-phase dispatch + drive -----

(deftest scripted-scenario-phase-1-precedes-phase-2-and-advances-revision
  (testing "test-design -> red-verified -> implement -> green: each agent step is
            dispatched two-phase (intent committed before outcome) and the
            implementer/test-designer outcomes advance the Revision counter"
    (with-store
      (fn [conn ids]
        (let [dir       (temp-dir!)
              test-file "feature_test.clj"
              prod-file "feature.clj"]
          (try
            (spit (io/file dir test-file) "()")
            (spit (io/file dir prod-file) "()")
            (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}
                                                     {:status :ok :exit 0}]})
                  rev0 (store/current-revision conn (:iteration-eid ids))
                  ;; STEP 1: test-designer authors the RED test (phase 1 + 2)
                  td   (orch/dispatch-step!
                        (merge ids {:conn conn :agent-invoker fake
                                    :role :test-designer :cwd dir
                                    :changes {:created [test-file]}}))
                  rev1 (store/current-revision conn (:iteration-eid ids))
                  ;; verify RED (non-zero exit) and drive test-design -> implement
                  red  (orch/perform-effect {:command ["sh" "-c" "exit 1"] :cwd dir}
                                            {:effect/type :verify-red})
                  d1   (orch/drive {} :test-design (:event red))
                  ;; STEP 2: implementer makes it GREEN (phase 1 + 2)
                  impl (orch/dispatch-step!
                        (merge ids {:conn conn :agent-invoker fake
                                    :role :implementer :cwd dir
                                    :changes {:edited [prod-file]}}))
                  rev2 (store/current-revision conn (:iteration-eid ids))
                  ;; verify GREEN (zero exit) and drive implement -> review
                  green (orch/perform-effect {:command ["sh" "-c" "exit 0"] :cwd dir}
                                             {:effect/type :verify-green})
                  d2    (orch/drive {} :implement (:event green))]
              ;; both steps completed within boundary
              (is (= :complete (:status td)))
              (is (= :complete (:status impl)))
              (is (nil? (:violation td)))
              (is (nil? (:violation impl)))
              ;; each authoring outcome advanced the counter monotonically
              (is (= 0 rev0))
              (is (= 1 rev1) "test-designer outcome advanced the counter")
              (is (= 2 rev2) "implementer outcome advanced the counter again")
              ;; the drive loop advanced the machine on the verification events
              (is (= :red-verified (:event red)))
              (is (= :implement (:state d1)))
              (is (= :green (:event green)))
              (is (= :review-correctness (:state d2))))
            (finally (delete-tree! (io/file dir)))))))))

;; --- Fail-closed capability enforcement (task 8.4) ---------------------------
;;
;; `dispatch-step!` DECIDES the boundary and surfaces a `:violation`; `8.4` WIRES
;; the fail-closed transition that violation implies. These tests drive each
;; role's boundary breach with a `fake-agent` + real temp files, then feed the
;; surfaced violation through `enforce-capability`, asserting the role-appropriate
;; fail-closed error is produced and the pipeline NEVER advances (no :next-state).

(deftest violation-event-maps-each-role-to-its-fail-closed-event
  (testing "role -> violation event: test-designer/implementer/both reviewers"
    (is (= :production-touched (orch/violation-event-for :test-designer)))
    (is (= :test-touched (orch/violation-event-for :implementer)))
    (is (= :reviewer-edited (orch/violation-event-for :correctness-reviewer)))
    (is (= :reviewer-edited (orch/violation-event-for :structural-reviewer)))
    (is (nil? (orch/violation-event-for :no-such-role))
        "an unrecognized role has no legal violation event")))

(deftest enforce-capability-no-violation-passes-through-unchanged
  (testing "with no observed violation there is nothing to enforce"
    (let [dispatched {:role :test-designer :violation nil :status :complete}
          enforced   (orch/enforce-capability :test-design dispatched)]
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
                  dispatched (orch/dispatch-step! ctx)
                  enforced   (orch/enforce-capability :test-design dispatched)]
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
                  dispatched (orch/dispatch-step! ctx)
                  enforced   (orch/enforce-capability :implement dispatched)]
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
                    dispatched (orch/dispatch-step! ctx)
                    enforced   (orch/enforce-capability state dispatched)]
                (is (some? (:violation dispatched))
                    (str "a reviewer editing a file is a violation for " role))
                (is (= :failed (:status dispatched)))
                (is (true? (:enforced? enforced)))
                (is (= :reviewer-edited (:violation-event enforced)))
                (is (= :reviewer-attempted-repair (get-in enforced [:error :code])))
                (is (nil? (:next-state (:transition enforced))))))
            (finally (delete-tree! (io/file dir)))))))))

;; --- Review rounds, findings, and reconciliation (task 8.5) ------------------
;;
;; The 8.5 helpers run a review round (correctness FIRST, structural AFTER,
;; feeding correctness findings to structural via :review/inputs-ref, R-7/R-9),
;; record approvals bound to the Revision counter value in force (R-8.3), decide
;; the AND-gate (both reviewers approve the SAME counter, R-16.4), manage repair
;; proposals rejecting a plan that contradicts a binding decision (R-13.2), and
;; consume a per-Disagreement Reconciliation allowance keyed to its stable id
;; (R-15). These tests exercise each against a temp Datalevin store + fake-agent,
;; asserting the durable facts the pure core decisions are fed.

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
              result (orch/run-review-round! conn round)]
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
              _      (orch/run-review-round! conn round)
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
              c-review (orch/record-review! conn {:iteration-eid iter
                                                  :reviewer :correctness
                                                  :verdict :request-changes
                                                  :revision-counter 0})
              _ (orch/record-finding! conn {:review-eid (:review-eid c-review)
                                            :owner :implementer
                                            :problem "off-by-one in paginate"
                                            :evidence "test paginate-boundary fails"
                                            :justification "R-2 requires correct bounds"
                                            :required-outcome "clamp offset to [0,n]"})
              ;; the structural inputs reference should now mention that finding
              inputs (orch/correctness-findings-input conn iter)]
          (is (str/includes? inputs "off-by-one in paginate")
              "the fed inputs reference the correctness finding's problem")
          (is (str/includes? inputs "clamp offset")
              "the fed inputs reference the finding's required outcome")
          ;; and a full round stamps :review/inputs-ref on the structural review
          (let [round {:iteration-eid iter
                       :revision-counter 0
                       :correctness {:verdict :request-changes :ctx (reviewer-ctx conn ids)}
                       :structural  {:verdict :approve :ctx (reviewer-ctx conn ids)}}
                result (orch/run-review-round! conn round)
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
        (let [review (orch/record-review! conn {:iteration-eid (:iteration-eid ids)
                                                :reviewer :correctness
                                                :verdict :request-changes
                                                :revision-counter 0})
              {:keys [valid? finding-eid]}
              (orch/record-finding! conn {:review-eid (:review-eid review)
                                          :owner :both
                                          :problem "p" :evidence "e"
                                          :justification "j" :required-outcome "o"})]
          (is (true? valid?))
          (is (some? finding-eid)))))))

(deftest record-finding-marks-an-incomplete-finding-invalid
  (testing "a finding missing any R-10 component is invalid (R-10.2)"
    (with-store
      (fn [conn ids]
        (let [review (orch/record-review! conn {:iteration-eid (:iteration-eid ids)
                                                :reviewer :correctness
                                                :verdict :request-changes
                                                :revision-counter 0})
              ;; omit :required-outcome
              {:keys [valid?]}
              (orch/record-finding! conn {:review-eid (:review-eid review)
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
              review (orch/record-review! conn {:iteration-eid iter
                                                :reviewer :correctness
                                                :verdict :approve
                                                :revision-counter 0})
              a0 (orch/record-approval! conn {:review-eid (:review-eid review)
                                              :iteration-eid iter
                                              :reviewer :correctness
                                              :verdict :approve})]
          (is (= 0 (:revision-counter a0)) "bound to the counter in force (0)")
          ;; advance the counter via a test-designer step outcome
          (let [{:keys [step-eid]} (orch/record-step-dispatch!
                                    conn {:iteration-eid iter :role :test-designer})]
            (store/record-step-outcome conn {:step-eid step-eid
                                             :iteration-eid iter
                                             :status :complete
                                             :advance-revision? true}))
          (let [review2 (orch/record-review! conn {:iteration-eid iter
                                                   :reviewer :correctness
                                                   :verdict :approve
                                                   :revision-counter 1})
                a1 (orch/record-approval! conn {:review-eid (:review-eid review2)
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
              c-review (orch/record-review! conn {:iteration-eid iter :reviewer :correctness
                                                  :verdict :approve :revision-counter 0})
              s-review (orch/record-review! conn {:iteration-eid iter :reviewer :structural
                                                  :verdict :approve :revision-counter 0})]
          ;; only correctness has approved => gate does NOT hold
          (orch/record-approval! conn {:review-eid (:review-eid c-review)
                                       :iteration-eid iter :reviewer :correctness :verdict :approve})
          (is (false? (orch/both-approved? conn iter))
              "one approval is not enough")
          ;; both approve the current counter (0) => gate holds
          (orch/record-approval! conn {:review-eid (:review-eid s-review)
                                       :iteration-eid iter :reviewer :structural :verdict :approve})
          (is (true? (orch/both-approved? conn iter))
              "both reviewers approve the same current counter value")
          ;; advancing the counter stales both approvals => gate no longer holds
          (let [{:keys [step-eid]} (orch/record-step-dispatch!
                                    conn {:iteration-eid iter :role :implementer})]
            (store/record-step-outcome conn {:step-eid step-eid :iteration-eid iter
                                             :status :complete :advance-revision? true}))
          (is (false? (orch/both-approved? conn iter))
              "a later implementer outcome stales approvals bound to the old counter"))))))

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
          (let [rejected (orch/propose-repair! conn {:slice-eid slice
                                                     :subject "retry-policy"
                                                     :body "retry three times"})]
            (is (some? (:conflict rejected)) "the plan was rejected")
            (is (= "retry-policy" (:decision/subject (:conflict rejected))))
            (is (empty? (store/proposals-for-slice conn slice))
                "nothing was committed for a rejected plan"))
          ;; a repair AGREEING with the binding statement is admitted
          (let [ok (orch/propose-repair! conn {:slice-eid slice
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
              p0 (orch/propose-repair! conn {:slice-eid slice :subject "s" :body "plan v1"})
              ;; both reviewers accept the first plan => authorized
              _  (orch/accept-proposal! conn {:proposal-eid (:proposal-eid p0) :reviewer :correctness})
              _  (orch/accept-proposal! conn {:proposal-eid (:proposal-eid p0) :reviewer :structural})
              p0* (->> (store/proposals-for-slice conn slice)
                       (filter #(= (:proposal-id p0) (:proposal/id %))) first)
              ;; amend: a NEW entity superseding the first
              p1 (orch/propose-repair! conn {:slice-eid slice :subject "s" :body "plan v2"
                                             :supersedes (:proposal-eid p0)})
              p1* (->> (store/proposals-for-slice conn slice)
                       (filter #(= (:proposal-id p1) (:proposal/id %))) first)]
          (is (orch/repair-authorized? p0*) "the first plan was accepted by both")
          (is (not= (:proposal-eid p0) (:proposal-eid p1)) "the amendment is a NEW entity")
          (is (not (orch/repair-authorized? p1*))
              "the amendment starts with EMPTY acceptances; they do not carry forward")
          ;; both must accept the amendment afresh to authorize it
          (orch/accept-proposal! conn {:proposal-eid (:proposal-eid p1) :reviewer :correctness})
          (orch/accept-proposal! conn {:proposal-eid (:proposal-eid p1) :reviewer :structural})
          (let [p1** (->> (store/proposals-for-slice conn slice)
                          (filter #(= (:proposal-id p1) (:proposal/id %))) first)]
            (is (orch/repair-authorized? p1**)
                "the amendment is authorized only after both accept it afresh")))))))

;; --- reconsideration admissibility (R-14) ------------------------------------

(deftest reconsideration-admissible-requires-decision-id-and-new-evidence
  (testing "admissible iff a specific decision is named AND non-blank new
            evidence is supplied (R-14.1, R-14.2)"
    (is (true? (orch/reconsideration-admissible?
                {:reconsideration/decision-id (random-uuid)
                 :reconsideration/new-evidence "profiler shows the regression"})))
    (is (false? (orch/reconsideration-admissible?
                 {:reconsideration/new-evidence "evidence but no decision named"})))
    (is (false? (orch/reconsideration-admissible?
                 {:reconsideration/decision-id (random-uuid)
                  :reconsideration/new-evidence "   "})))))

;; --- consume-disagreement-allowance!: per-id allowance (R-15) -----------------

(deftest consume-disagreement-allowance-decrements-per-stable-id
  (testing "each consumption increments attempts-used keyed to the stable
            :disagreement/id; the same slice+subject reuses the SAME disagreement
            so the allowance survives across rounds, exhausting at 0 (R-15)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)
              subj  "should-we-memoize"
              c1 (orch/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              c2 (orch/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})]
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
          (let [other (orch/consume-disagreement-allowance!
                       conn {:slice-eid slice :subject "unrelated-question"})]
            (is (not= (:disagreement-id c1) (:disagreement-id other)))
            (is (= 1 (:attempts-used other)) "a distinct disagreement starts fresh")))))))

;; --- escalation on exhausted allowance (task 8.10; R-15.4, R-15.5) -----------
;;
;; :escalated is reached ONLY when a Disagreement's bounded Reconciliation
;; allowance is exhausted (allowance-remaining => 0). The escalation records the
;; durable per-:disagreement/id status (:exhausted) that justifies it. A
;; Disagreement that still has attempts left never escalates, and a test conflict
;; is NEVER a path to :escalated.

(deftest escalate-drives-to-escalated-only-once-allowance-exhausted
  (testing "consuming a disagreement's allowance to exhaustion then :escalate
            drives the slice to :escalated and finalizes :disagreement/status
            :exhausted (R-15.4, R-15.5)"
    (with-store
      (fn [conn ids]
        (let [slice (:slice-eid ids)
              subj  "should-we-cache-here"
              ;; spend both attempts (R-15.1): one initial proposal + one revision
              _  (orch/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              c2 (orch/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              _  (is (true? (:exhausted? c2)) "allowance is exhausted after two attempts")
              ctx    (merge ids {:conn conn :state :reconcile})
              result (orch/perform-effect ctx {:effect/type :escalate :subject subj})]
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
              c1 (orch/consume-disagreement-allowance! conn {:slice-eid slice :subject subj})
              _  (is (= 1 (:remaining c1)) "one attempt remains")
              ctx    (merge ids {:conn conn :state :reconcile})
              result (orch/perform-effect ctx {:effect/type :escalate :subject subj})]
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

(deftest test-conflict-never-reaches-escalated
  (testing "a reported test conflict routes back to :test-design on the current
            iteration and is NEVER a path to :escalated (R-3.3, R-3.4, R-15.4)"
    (with-store
      (fn [conn ids]
        (let [ctx (merge ids {:conn conn :state :implement})]
          (orch/perform-effect ctx {:effect/type :route-conflict-to-test-designer})
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))
              "a test conflict routes to :test-design, not :escalated"))))))

;; --- Resume / reconcile + resume-staleness (task 8.9) ------------------------
;;
;; A bare process interruption is not a pipeline error: on resume the Orchestrator
;; reconciles in-doubt Steps (`step-in-doubt?`) against OBSERVABLE REALITY
;; (`fs/recover-step-outcome`: re-run RED/GREEN, inspect artifacts) before
;; recording their outcome — failing closed when reality is indeterminate — then
;; SEPARATELY marks any approval bound to a superseded Revision counter value
;; `:approval/stale?` / `:revision-advanced`, recorded by the Orchestrator WITHOUT
;; a reviewer statement (R-8.7). It stays on the SAME trace and never restarts at
;; test-design. RED/GREEN reconciliation is driven with an injectable shell
;; command (`sh -c 'exit N'`); artifacts are real temp files so
;; `fs/artifacts-present?` confirms them on disk.

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
                  {:keys [step-eid]} (orch/record-step-dispatch!
                                      conn {:iteration-eid iter :role :implementer})
                  step (d/pull (d/db conn) [:step/status] step-eid)
                  before (store/current-revision conn iter)]
              ;; it is in doubt: dispatched, no outcome
              (is (= :dispatched (:step/status step)))
              (is (true? (core/step-in-doubt? step)))
              ;; reconcile against observable reality: GREEN (zero exit) + artifact present
              (let [result (orch/reconcile-step!
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
              {:keys [step-eid]} (orch/record-step-dispatch!
                                  conn {:iteration-eid iter :role :implementer})
              before (store/current-revision conn iter)
              result (orch/reconcile-step!
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
                  {:keys [step-eid]} (orch/record-step-dispatch!
                                      conn {:iteration-eid iter :role :implementer})
                  ;; the verification passes, but the expected artifact was never produced
                  result (orch/reconcile-step!
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
              review (orch/record-review! conn {:iteration-eid iter :reviewer :correctness
                                                :verdict :approve :revision-counter 0})
              {:keys [approval-eid]} (orch/record-approval!
                                      conn {:review-eid (:review-eid review)
                                            :iteration-eid iter
                                            :reviewer :correctness :verdict :approve})]
          ;; before any advance the approval still holds => not stale
          (let [pre (orch/mark-stale-approvals! conn iter)]
            (is (empty? (:staled pre)) "a current approval is not stale")
            (is (nil? (:tx pre))))
          ;; advance the counter via an implementer step outcome
          (let [{:keys [step-eid]} (orch/record-step-dispatch!
                                    conn {:iteration-eid iter :role :implementer})]
            (store/record-step-outcome conn {:step-eid step-eid :iteration-eid iter
                                             :status :complete :advance-revision? true}))
          ;; on resume the staleness is marked as a non-reviewer fact
          (let [result (orch/mark-stale-approvals! conn iter)
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
                  review (orch/record-review! conn {:iteration-eid iter :reviewer :correctness
                                                    :verdict :approve :revision-counter 0})
                  {:keys [approval-eid]} (orch/record-approval!
                                          conn {:review-eid (:review-eid review)
                                                :iteration-eid iter
                                                :reviewer :correctness :verdict :approve})
                  ;; an implementer step left in doubt (dispatched, no outcome)
                  {:keys [step-eid]} (orch/record-step-dispatch!
                                      conn {:iteration-eid iter :role :implementer})
                  result (orch/resume-iteration!
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
              review (orch/record-review! conn {:iteration-eid iter :reviewer :correctness
                                                :verdict :approve :revision-counter 0})
              {:keys [approval-eid]} (orch/record-approval!
                                      conn {:review-eid (:review-eid review)
                                            :iteration-eid iter
                                            :reviewer :correctness :verdict :approve})
              {:keys [step-eid]} (orch/record-step-dispatch!
                                  conn {:iteration-eid iter :role :implementer})
              result (orch/resume-iteration!
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

;; --- Property 14: Two-phase dispatch makes an interrupted step recoverable and
;;     fails closed when reality is indeterminate (R-18.1, R-18.2, R-18.5) ------
;;
;; Feature: orchestrator-state-machine, Property 14: Two-phase dispatch makes an
;; interrupted step recoverable and fails closed when reality is indeterminate.
;;
;; Where the example tests above pin down specific two-phase / resume scenarios,
;; this property exercises the SAME guarantees across generated sequences of
;; interrupted Steps. Each iteration opens a FRESH temp Datalevin directory,
;; seeds a run/slice/iteration, and drives a generated sequence of Steps through
;; the two-phase lifecycle, asserting for EVERY generated Step:
;;
;;   (1) INTENT-BEFORE-OUTCOME (R-18.1): after phase 1 (`record-step-dispatch!`,
;;       committed BEFORE the agent runs) the Step is durably `:dispatched` with
;;       a `:step/dispatched-at` stamp and NO `:step/outcome-at`; the outcome is
;;       only recorded AFTER reconciliation returns. The counter never advances on
;;       phase 1 alone.
;;   (2) IN-DOUBT (R-18.2): a dispatched Step with no recorded outcome is exactly
;;       what `core/step-in-doubt?` declares in doubt — neither assumed complete
;;       nor assumed untouched.
;;   (3) RECOVERABLE: reconciling an in-doubt Step against a DETERMINATE observable
;;       reality (an injectable `sh -c 'exit N'` command with its required
;;       artifacts on disk) recovers the actual outcome event and completes phase
;;       2, so the Step is no longer in doubt and the Revision counter advances iff
;;       the role authors code (`revision-advancing-role?`).
;;   (4) FAIL-CLOSED (R-18.5): when observable reality is INDETERMINATE (an empty
;;       verification command) or a required artifact is ABSENT, reconciliation
;;       fails closed — NO outcome is recorded, the Step STAYS `:dispatched` (still
;;       in doubt), and the counter does NOT advance.
;;
;; Each iteration opens a real LMDB directory (I/O-heavy, like store Property 13),
;; so generated sequences are kept modest (1-6 Steps). The `:test` alias supplies
;; the Datalevin JVM opts.

(def ^:private p14-role-gen
  "A role to dispatch a Step for, spanning revision-advancing authors and
  non-advancing reviewers so the counter-advance clause is exercised both ways."
  (gen/elements [:test-designer :implementer
                 :correctness-reviewer :structural-reviewer]))

(def ^:private p14-recovery-gen
  "One generated recovery scenario for an in-doubt Step: the phase it owed, the
  verification exit code, and whether its required artifact is present on disk.

  `:reality` selects the observable-reality shape:
    :determinate  — a runnable `sh -c 'exit N'` command with its artifact present;
                    reconciliation recovers an outcome (recoverable case).
    :indeterminate — an empty command; reality cannot be determined (fail closed).
    :missing-artifact — a runnable command but a required artifact absent on disk
                    (produced work not observable; fail closed)."
  (gen/hash-map
   :phase   (gen/elements [:red :green])
   :exit    (gen/elements [0 1 2 127])
   :reality (gen/elements [:determinate :determinate :determinate
                           :indeterminate :missing-artifact])))

(def ^:private p14-step-gen
  "One generated interrupted Step: the role dispatched plus its recovery scenario."
  (gen/hash-map :role p14-role-gen :recovery p14-recovery-gen))

(defn- p14-expected-event
  "The pure recovered outcome event a determinate reconciliation of `phase` with
  exit `exit` yields (mirrors `fs/red-outcome`/`fs/green-outcome`): a RED step is
  verified on a non-zero exit and invalid on zero; a GREEN step is green on zero
  and falls back to red-verified on non-zero."
  [phase exit]
  (case phase
    :red   (if (zero? exit) :red-invalid :red-verified)
    :green (if (zero? exit) :green :red-verified)))

(deftest property-14-two-phase-dispatch-recoverable-and-fails-closed-when-indeterminate
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [steps (gen/vector p14-step-gen 1 6)]
          (let [dir     (temp-dir!)       ; a scratch cwd for injectable artifacts
                db-dir  (temp-dir!)
                conn    (store/connect db-dir)]
            (try
              (let [{:keys [iteration-eid]} (seed-run+slice+iteration! conn)]
                (every?
                 (fn [[idx {:keys [role recovery]}]]
                   (let [{:keys [phase exit reality]} recovery
                         artifact  (str "produced_" idx ".txt")
                         ;; PHASE 1 — commit the dispatch intent BEFORE the agent
                         ;; runs. No outcome is recorded yet (R-18.1).
                         rev-before (store/current-revision conn iteration-eid)
                         {:keys [step-eid]} (orch/record-step-dispatch!
                                             conn {:iteration-eid iteration-eid
                                                   :role role})
                         after-phase1 (d/pull (d/db conn)
                                               [:step/status :step/role
                                                :step/dispatched-at :step/outcome-at]
                                               step-eid)
                         ;; The interrupted Step is now observably in doubt: it is
                         ;; :dispatched with intent stamped and NO outcome (R-18.2).
                         intent-before-outcome?
                         (and (= :dispatched (:step/status after-phase1))
                              (some? (:step/dispatched-at after-phase1))
                              (nil? (:step/outcome-at after-phase1))
                              (core/step-in-doubt? after-phase1)
                              ;; phase 1 alone never advances the counter
                              (= rev-before (store/current-revision conn iteration-eid)))
                         ;; Assemble the recovery inputs for this reality shape.
                         determinate?  (= :determinate reality)
                         ;; a determinate or missing-artifact reality is runnable;
                         ;; only :indeterminate supplies an empty (unrunnable) command
                         command       (if (= :indeterminate reality)
                                         []
                                         ["sh" "-c" (str "exit " exit)])
                         ;; a determinate reality has its produced artifact on disk;
                         ;; :missing-artifact requires an artifact never written
                         _             (when determinate?
                                         (spit (io/file dir artifact) "produced"))
                         artifact-paths [artifact]
                         recon (orch/reconcile-step!
                                {:conn           conn
                                 :step-eid       step-eid
                                 :iteration-eid  iteration-eid
                                 :role           role
                                 :phase          phase
                                 :command        command
                                 :artifact-paths artifact-paths
                                 :cwd            dir})
                         after-recon (d/pull (d/db conn)
                                             [:step/status :step/outcome-at]
                                             step-eid)
                         rev-after (store/current-revision conn iteration-eid)
                         clause-ok?
                         (if determinate?
                           ;; (3) RECOVERABLE: the actual outcome is observable, so
                           ;; phase 2 completes — the Step is :complete, no longer
                           ;; in doubt, and the counter advanced iff the role authors
                           ;; code (revision-advancing-role?).
                           (and (nil? (:error recon))
                                (= :complete (:status recon))
                                (= (p14-expected-event phase exit) (:event recon))
                                (= :complete (:step/status after-recon))
                                (some? (:step/outcome-at after-recon))
                                (not (core/step-in-doubt? after-recon))
                                (= (if (orch/revision-advancing-role? role)
                                     (inc rev-before)
                                     rev-before)
                                   rev-after))
                           ;; (4) FAIL-CLOSED: reality is indeterminate (empty
                           ;; command) or a required artifact is absent — nothing is
                           ;; recorded, the Step stays :dispatched (still in doubt),
                           ;; and the counter does not advance (R-18.5).
                           (and (some? (:error recon))
                                (contains? #{:indeterminate :missing-artifact}
                                           (get-in recon [:error :code]))
                                (nil? (:status recon))
                                (= :dispatched (:step/status after-recon))
                                (nil? (:step/outcome-at after-recon))
                                (core/step-in-doubt? after-recon)
                                (= rev-before rev-after)))]
                     (and intent-before-outcome? clause-ok?)))
                 (map-indexed vector steps)))
              (finally
                (store/close conn)
                (delete-tree! (io/file db-dir))
                (delete-tree! (io/file dir)))))))]
    (is (:pass? result)
        (str "Property 14 failed with: " (pr-str (:shrunk result))))
    (is (= 100 (:num-tests result))
        "ran the minimum 100 iterations")))

;; --- Property 2 (end-to-end): capability fail-closed enforcement (task 10.1) --
;;
;; Feature: orchestrator-state-machine, Property 2: Any change outside a role's
;; capability fails closed and never advances.
;;
;; The pure clause of Property 2 (`core/capability-violation?` flags exactly the
;; out-of-boundary changes) is validated in core_test. THIS is the END-TO-END
;; clause: it drives EACH role through the orchestrator's two-phase dispatch
;; (`dispatch-step!`) with a `fake-agent` producing an OUT-OF-BOUNDARY change,
;; backed by a REAL temp file on disk so `fs/observe-changes` confirms it, and
;; asserts every violation fails closed via `enforce-capability`:
;;
;;   * `dispatch-step!` SURFACES the violation and records a :failed Step (a
;;     boundary breach is never recorded as :complete);
;;   * `enforce-capability` maps the role to its fail-closed EVENT
;;     (`violation-event-for`) and feeds it through `core/transition`, yielding
;;     the role-appropriate fail-closed :error with NO :next-state — the pipeline
;;     does NOT advance:
;;       test-designer  wrote production => :production-touched
;;                      => :test-designer-wrote-production (stays outside :implement, R-1.4/1.5);
;;       implementer    authored/modified a test => :test-touched
;;                      => :implementer-wrote-test (stays outside review, R-3.5/3.6/16.6);
;;       reviewer       edited any file => :reviewer-edited
;;                      => :reviewer-attempted-repair (does not advance, R-11.2/11.3).
;;
;; Generates over ROLES and OUT-OF-BOUNDARY change kinds (a produced :production
;; change for the test-designer, a :test change for the implementer, and either
;; class for a reviewer), crossed with :created vs. :edited — because the boundary
;; decision is over a change's :class, both kinds are violations (R-16.6). Each
;; iteration opens a real LMDB directory (I/O-heavy, like Property 14), so the
;; generated sequence is kept modest (1-5 breaches). The `:test` alias supplies
;; the Datalevin JVM opts.
;;
;; Validates: Requirements 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 11.3, 16.6

(def ^:private p2-role-state
  "Each role paired with the pipeline STATE it runs in — the state the
  fail-closed transition is driven at, so the returned transition has no
  :next-state (the machine stays put)."
  {:test-designer        :test-design
   :implementer          :implement
   :correctness-reviewer :review-correctness
   :structural-reviewer  :review-structural})

(defn- p2-out-of-boundary-class
  "The produced-change :class that lies OUTSIDE `role`'s capability boundary:
  the test-designer is walled out of :production, the implementer out of :test,
  and a reviewer out of everything (either class is a violation)."
  [role class-choice]
  (case role
    :test-designer :production
    :implementer   :test
    ;; a reviewer may write nothing, so either class breaches its boundary
    (:correctness-reviewer :structural-reviewer) class-choice))

(defn- p2-filename
  "A filename that `fs/classify-path` will classify as `class` (:test or
  :production), made unique with `idx` so per-iteration temp files never collide.
  A `_test` marker classifies as a test; a bare name classifies as production."
  [class idx]
  (case class
    :test       (str "feature_" idx "_test.clj")
    :production (str "feature_" idx ".clj")))

(def ^:private p2-breach-gen
  "One generated capability breach: a role, the change kind (:created |
  :edited), and — for a reviewer, whose boundary excludes everything — which
  class of file it touched (irrelevant for test-designer/implementer, whose
  out-of-boundary class is fixed)."
  (gen/hash-map
   :role         (gen/elements (keys p2-role-state))
   :change-kind  (gen/elements [:created :edited])
   :class-choice (gen/elements [:test :production])))

(deftest property-2-end-to-end-capability-fails-closed-and-never-advances
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [breaches (gen/vector p2-breach-gen 1 5)]
          (let [dir     (temp-dir!)     ; scratch cwd for the real produced files
                db-dir  (temp-dir!)
                conn    (store/connect db-dir)]
            (try
              (let [{:keys [iteration-eid]} (seed-run+slice+iteration! conn)]
                (every?
                 (fn [[idx {:keys [role change-kind class-choice]}]]
                   (let [state       (get p2-role-state role)
                         breach-cls  (p2-out-of-boundary-class role class-choice)
                         filename    (p2-filename breach-cls idx)
                         ;; a REAL temp file on disk so fs/observe-changes CONFIRMS
                         ;; the produced change (a phantom edit would not count)
                         _           (spit (io/file dir filename) "()")
                         changes     {change-kind [filename]}
                         ;; a benign :ok invocation — the violation must be caught
                         ;; by the AUTHORITATIVE post-return boundary check, never
                         ;; excused by the agent reporting success
                         fake        (agents/fake-agent {:results [{:status :ok :exit 0}]})
                         ctx         (merge {:conn          conn
                                             :agent-invoker fake
                                             :iteration-eid iteration-eid
                                             :role          role
                                             :cwd           dir
                                             :changes       changes})
                         ;; drive the role through the orchestrator's two-phase
                         ;; dispatch, then enforce the surfaced violation
                         dispatched  (orch/dispatch-step! ctx)
                         enforced    (orch/enforce-capability state dispatched)
                         step        (d/pull (d/db conn)
                                             [:step/status :step/role :step/outcome-at]
                                             (:step-eid dispatched))
                         expected-event (orch/violation-event-for role)
                         expected-code  (case role
                                          :test-designer :test-designer-wrote-production
                                          :implementer   :implementer-wrote-test
                                          (:correctness-reviewer :structural-reviewer)
                                          :reviewer-attempted-repair)]
                     (and
                      ;; dispatch-step! SURFACES the out-of-boundary change ...
                      (some? (:violation dispatched))
                      (= breach-cls (:class (:violation dispatched)))
                      ;; ... and records a :failed step (never :complete)
                      (= :failed (:status dispatched))
                      (= :failed (:step/status step))
                      (some? (:step/outcome-at step))
                      ;; enforce-capability drives the role-appropriate fail-closed
                      ;; transition ...
                      (true? (:enforced? enforced))
                      (= expected-event (:violation-event enforced))
                      (= expected-code (get-in enforced [:error :code]))
                      ;; ... which carries an :error and NO :next-state, so the
                      ;; pipeline does NOT advance (stays put at `state`)
                      (some? (:error enforced))
                      (nil? (:next-state (:transition enforced)))
                      ;; the fail-closed transition is exactly what core/transition
                      ;; yields for [state violation-event] (no rule duplicated)
                      (= (core/transition state expected-event) (:transition enforced)))))
                 (map-indexed vector breaches)))
              (finally
                (store/close conn)
                (delete-tree! (io/file db-dir))
                (delete-tree! (io/file dir)))))))]
    (is (:pass? result)
        (str "Property 2 (end-to-end) failed with: " (pr-str (:shrunk result))))
    (is (= 100 (:num-tests result))
        "ran the minimum 100 iterations")))

;; --- Property 6 (end-to-end): Revision-counter approval binding (task 10.2) ---
;;
;; Feature: orchestrator-state-machine, Property 6: Approvals bind to the Revision
;; counter value in force, and a recorded Step outcome advances the counter and
;; stales the approval
;;
;; The pure clauses of Property 6 (`core/advance-revision` = (inc counter);
;; `core/approval-valid?` true iff bound counter == current counter;
;; `core/both-approved?` only when both approve the same value) are validated in
;; core_test. THIS is the END-TO-END clause, exercising DURABILITY across a
;; simulated restart:
;;
;;   * HAPPY-PATH AND-gate on the SAME counter value: both reviewers approve the
;;     Iteration's CURRENT Revision counter value (in force), and `orch/both-approved?`
;;     holds (R-8.1, R-8.3, R-16.4);
;;   * a later implementer/test-designer Step outcome ADVANCES the counter and
;;     STALES the prior approvals — even ACROSS A RESTART: the approvals are
;;     recorded bound to counter N, one or more counter-advancing Step outcomes are
;;     recorded (via the two-phase `record-step-dispatch!` + `store/record-step-outcome`),
;;     then the Datalevin store is CLOSED and REOPENED (store/close + store/connect).
;;     After the restart the reopened store reads the advanced counter, so
;;     `core/approval-valid?` is false for each prior approval, `orch/mark-stale-approvals!`
;;     marks them `:approval/stale?` with `:approval/stale-reason :revision-advanced`
;;     WITHOUT a reviewer statement (R-8.7), and `orch/both-approved?` no longer
;;     holds (R-8.4, R-8.5, R-16.5).
;;
;; Generates over the counter START value (reached by pre-advancing via recorded
;; Step outcomes), WHICH role advances (implementer | test-designer, both authoring
;; roles that advance the counter), and the NUMBER of later advances. Entity ids are
;; stable in Datalevin, so the seeded iteration-eid names the same Iteration after
;; the reopen. Each iteration opens a real LMDB directory (I/O-heavy), so generated
;; values are kept modest; connect/close are managed EXPLICITLY within the iteration
;; (like the store durability tests) rather than via `with-store`, to exercise the
;; restart. The `:test` alias supplies the Datalevin JVM opts.
;;
;; Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 16.4, 16.5

(defn- p6-advance-counter!
  "Advance Iteration `iteration-eid`'s Revision counter by `n` via the two-phase
  Step lifecycle: for each advance, record the dispatch intent
  (`record-step-dispatch!`) then a durable outcome (`store/record-step-outcome`
  with `:advance-revision? true`) for an authoring `role`. Returns the counter
  value after the advances (an action; mirrors how the orchestrator advances it)."
  [conn iteration-eid role n]
  (dotimes [_ n]
    (let [{:keys [step-eid]} (orch/record-step-dispatch!
                              conn {:iteration-eid iteration-eid :role role})]
      (store/record-step-outcome conn {:step-eid          step-eid
                                       :iteration-eid     iteration-eid
                                       :status            :complete
                                       :advance-revision? true})))
  (store/current-revision conn iteration-eid))

(defn- p6-record-both-approvals!
  "Record a correctness review + approval and a structural review + approval, both
  bound to the Iteration's current Revision counter value in force (R-8.3). Returns
  the counter value both approvals bound to."
  [conn iteration-eid]
  (let [c-review (orch/record-review! conn {:iteration-eid iteration-eid
                                            :reviewer :correctness :verdict :approve
                                            :revision-counter (store/current-revision conn iteration-eid)})
        s-review (orch/record-review! conn {:iteration-eid iteration-eid
                                            :reviewer :structural :verdict :approve
                                            :revision-counter (store/current-revision conn iteration-eid)})
        c-appr   (orch/record-approval! conn {:review-eid (:review-eid c-review)
                                              :iteration-eid iteration-eid
                                              :reviewer :correctness :verdict :approve})]
    (orch/record-approval! conn {:review-eid (:review-eid s-review)
                                 :iteration-eid iteration-eid
                                 :reviewer :structural :verdict :approve})
    (:revision-counter c-appr)))

(def ^:private p6-scenario-gen
  "One generated Property 6 scenario: the counter START value both approvals bind
  to (reached by pre-advancing), WHICH authoring role advances the counter after
  the approvals, and HOW MANY later advances stale them."
  (gen/hash-map
   :start        (gen/choose 0 3)
   :advance-role (gen/elements [:implementer :test-designer])
   :advances     (gen/choose 1 3)))

(deftest property-6-end-to-end-approval-binds-to-revision-and-stales-across-restart
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [{:keys [start advance-role advances]} p6-scenario-gen]
          (let [db-dir (temp-dir!)
                conn   (store/connect db-dir)]
            (try
              (let [{:keys [iteration-eid]} (seed-run+slice+iteration! conn)
                    ;; Reach the generated START counter value by recording
                    ;; authoring Step outcomes (the counter carries no file info;
                    ;; each authoring outcome advances it by one, R-8.4).
                    start-counter (p6-advance-counter! conn iteration-eid advance-role start)
                    ;; HAPPY PATH: both reviewers approve the SAME counter value in
                    ;; force; the AND-gate holds bound to that value (R-8.1, R-16.4).
                    bound-counter (p6-record-both-approvals! conn iteration-eid)
                    approvals     (store/approvals-for-iteration conn iteration-eid)
                    happy-ok?
                    (and (= start start-counter)
                         (= start-counter bound-counter)
                         ;; both approvals bound to the counter in force
                         (every? #(= bound-counter (:approval/revision-counter %)) approvals)
                         ;; each approval is valid against the current counter ...
                         (every? #(core/approval-valid? % bound-counter) approvals)
                         ;; ... and the AND-gate holds on that same counter value
                         (true? (orch/both-approved? conn iteration-eid))
                         (true? (orch/both-approved? conn iteration-eid bound-counter)))
                    ;; A later authoring Step outcome ADVANCES the counter, staling
                    ;; the prior approvals by a pure integer comparison (R-8.4).
                    advanced-counter (p6-advance-counter! conn iteration-eid advance-role advances)]
                ;; SIMULATED RESTART: close and reopen the durable store, then read
                ;; the advanced counter and the approvals back (R-8.5).
                (store/close conn)
                (let [conn2 (store/connect db-dir)]
                  (try
                    (let [after-counter (store/current-revision conn2 iteration-eid)
                          reopened      (store/approvals-for-iteration conn2 iteration-eid)
                          ;; strictly monotonic: each advance is exactly (inc prev)
                          monotonic?    (= (+ bound-counter advances) advanced-counter after-counter)
                          ;; gate no longer holds against the advanced counter, and
                          ;; the prior approvals are stale (bound value != current)
                          gate-broken?
                          (and (false? (orch/both-approved? conn2 iteration-eid))
                               (false? (orch/both-approved? conn2 iteration-eid after-counter))
                               (every? #(not (core/approval-valid? % after-counter)) reopened))
                          ;; mark-stale-approvals! records the staleness as a
                          ;; non-reviewer fact: :revision-advanced, no verdict (R-8.7)
                          staled  (orch/mark-stale-approvals! conn2 iteration-eid)
                          stamped (store/approvals-for-iteration conn2 iteration-eid)
                          stale-ok?
                          (and (= after-counter (:current-counter staled))
                               (= 2 (count (:staled staled)))
                               (every? (fn [a]
                                         (and (true? (:approval/stale? a))
                                              (= :revision-advanced (:approval/stale-reason a))
                                              ;; a NON-reviewer fact: the reviewer's
                                              ;; verdict is untouched (still :approve);
                                              ;; staleness is bookkeeping, not a
                                              ;; withdrawn approval (R-8.7)
                                              (= :approve (:approval/verdict a))))
                                       stamped)
                               ;; the gate still does not hold after staleness is stamped
                               (false? (orch/both-approved? conn2 iteration-eid)))]
                      (and happy-ok? monotonic? gate-broken? stale-ok?))
                    (finally
                      (store/close conn2)))))
              (finally
                ;; conn is already closed above on the happy path; guard the cleanup
                (try (store/close conn) (catch Exception _))
                (delete-tree! (io/file db-dir)))))))]
    (is (:pass? result)
        (str "Property 6 (end-to-end) failed with: " (pr-str (:shrunk result))))
    (is (= 100 (:num-tests result))
        "ran the minimum 100 iterations")))

;; --- Property 15 (end-to-end): a review round ending without approval records a
;;     justified finding, carries it forward, and records resume-staleness
;;     without a reviewer (task 10.3) ------------------------------------------
;;
;; Feature: orchestrator-state-machine, Property 15: A review round ending without
;; approval records a justified finding, carries it forward, and records
;; resume-staleness without a reviewer
;;
;; The pure clauses (`core/finding-valid?` true iff all four R-10 fields present
;; and non-blank; `core/approval-valid?` true iff bound counter == current) are
;; validated in core_test. THIS is the END-TO-END clause, over a real durable
;; store, with TWO independent sub-clauses:
;;
;;   * FINDING-CARRY (R-8.6, R-8.8, R-8.9): a REQUEST_CHANGES / withheld-approval
;;     round may not mint a new Iteration until an R-10-compliant `:finding`
;;     (`core/finding-valid?` true) has been durably recorded. When only an INVALID
;;     finding exists (one R-10 field missing), `orch/mint-iteration!` FAILS CLOSED
;;     with `:no-valid-finding` and commits nothing — no new Iteration, no link.
;;     When a VALID finding exists, the mint succeeds: it creates a NEW trace at
;;     `:iteration/revision` 0, links BOTH ways (`:iteration/seeded-from-finding`
;;     on the new Iteration / `:finding/carried-to-iteration` on the carried
;;     finding), and repoints the slice's current iteration — carrying the recorded
;;     failure information into the new pass together with the new trace-id.
;;   * RESUME-STALENESS (R-8.7): a prior approval treated as unapproved on resume
;;     purely because the Revision counter advanced is stamped by the Orchestrator
;;     ITSELF (`orch/mark-stale-approvals!`) with `:approval/stale-reason
;;     :revision-advanced` and NO reviewer statement — no `:finding` and no
;;     `:review`/verdict is written for the staleness; the reviewer's original
;;     `:approve` verdict is untouched (staleness is bookkeeping, not a withdrawal).
;;
;; Generates over: finding VALIDITY (valid vs. an invalid finding missing one R-10
;; field), WHICH R-10 field is missing for the invalid case (so every field is
;; exercised as the sole omission), the counter START value both approvals bind to
;; (reached by pre-advancing via recorded authoring Step outcomes), and the NUMBER
;; of later advances that stale them. Each iteration opens a real LMDB directory
;; (I/O-heavy, like Property 6/14), so generated values are kept modest and
;; connect/close are managed within `with-store`. The `:test` alias supplies the
;; Datalevin JVM opts.
;;
;; Validates: Requirements 8.6, 8.7, 8.8, 8.9

(def ^:private p15-r10-fields
  "The four R-10 finding components; each is exercised as the SOLE omission in the
  invalid case so the finding-carry gate rejects a finding missing ANY one."
  [:problem :evidence :justification :required-outcome])

(defn- p15-record-finding!
  "Record a REQUEST_CHANGES review and one `:finding` under `iteration-eid`. When
  `missing-field` is nil the finding carries all four R-10 fields (valid); when it
  names one of `p15-r10-fields` that field is omitted (invalid, R-10.2). Returns
  {:review-eid … :finding-eid … :valid? bool}."
  [conn iteration-eid missing-field]
  (let [{:keys [review-eid]} (orch/record-review!
                              conn {:iteration-eid iteration-eid
                                    :reviewer :correctness
                                    :verdict :request-changes
                                    :revision-counter (or (store/current-revision conn iteration-eid) 0)})
        full   {:problem          "behavior B is missing"
                :evidence         "test T fails on input X"
                :justification    "requirement R-99 mandates B"
                :required-outcome "implement B so T passes"}
        fields (cond-> full missing-field (dissoc missing-field))
        {:keys [finding-eid valid?]} (orch/record-finding!
                                      conn (merge {:review-eid review-eid
                                                   :owner :implementer
                                                   :revision-counter 0}
                                                  fields))]
    {:review-eid review-eid :finding-eid finding-eid :valid? valid?}))

(defn- p15-advance-counter!
  "Advance Iteration `iteration-eid`'s Revision counter by `n` via the two-phase
  Step lifecycle for authoring `role` (record-step-dispatch! + record-step-outcome
  with :advance-revision? true). Returns the counter value after the advances."
  [conn iteration-eid role n]
  (dotimes [_ n]
    (let [{:keys [step-eid]} (orch/record-step-dispatch!
                              conn {:iteration-eid iteration-eid :role role})]
      (store/record-step-outcome conn {:step-eid          step-eid
                                       :iteration-eid     iteration-eid
                                       :status            :complete
                                       :advance-revision? true})))
  (store/current-revision conn iteration-eid))

(defn- p15-count-findings-and-reviews
  "Return {:findings <n> :reviews <n>} recorded under `iteration-eid`, so the
  staleness clause can assert NO finding/review is written for resume-staleness."
  [conn iteration-eid]
  {:findings (count (store/findings-for-iteration conn iteration-eid))
   :reviews  (count (store/reviews-for-iteration conn iteration-eid))})

(def ^:private p15-scenario-gen
  "One generated Property 15 scenario: whether the recorded finding is VALID (vs.
  invalid missing one R-10 field), WHICH field is missing when invalid, the
  authoring role that advances the counter, the counter START value the approvals
  bind to, and the NUMBER of later advances that stale them."
  (gen/hash-map
   :valid?        gen/boolean
   :missing-field (gen/elements p15-r10-fields)
   :advance-role  (gen/elements [:implementer :test-designer])
   :start         (gen/choose 0 3)
   :advances      (gen/choose 1 3)))

(deftest property-15-round-without-approval-carries-finding-and-resume-staleness
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [{:keys [valid? missing-field advance-role start advances]} p15-scenario-gen]
          (let [dir  (temp-dir!)
                conn (store/connect dir)]
            (try
              (let [{:keys [run-eid slice-eid iteration-eid]} (seed-run+slice+iteration! conn)
                    ;; The invalid case omits exactly ONE R-10 field; the valid case
                    ;; omits none. This models a review round ending without approval.
                    miss   (when-not valid? missing-field)
                    {:keys [finding-eid] recorded-valid? :valid?}
                    (p15-record-finding! conn iteration-eid miss)
                    ;; the store-recorded :finding/valid? matches core/finding-valid?
                    stored-valid? (:finding/valid?
                                   (d/pull (d/db conn) [:finding/valid?] finding-eid))
                    validity-ok? (and (= valid? recorded-valid?)
                                      (= valid? stored-valid?))
                    ;; ---- FINDING-CARRY sub-clause (R-8.6, R-8.8, R-8.9) ----
                    slice-before (:slice/current-iteration
                                  (d/pull (d/db conn)
                                          [{:slice/current-iteration [:db/id]}] slice-eid))
                    minted (orch/mint-iteration!
                            conn {:run-eid run-eid :slice-eid slice-eid
                                  :iteration-eid iteration-eid :from-state :reconcile})
                    slice-after (:slice/current-iteration
                                 (d/pull (d/db conn)
                                         [{:slice/current-iteration [:db/id]}] slice-eid))
                    carry-ok?
                    (if valid?
                      ;; a VALID finding gates the mint OPEN: new trace at revision
                      ;; 0, both inverse links set, slice repointed (R-8.8, R-8.9).
                      (let [new-eid (:iteration-eid minted)]
                        (and (nil? (:error minted))
                             (some? new-eid)
                             (not= iteration-eid new-eid)
                             (= 0 (:revision minted))
                             (= 0 (store/current-revision conn new-eid))
                             (= finding-eid (:finding-eid minted))
                             ;; :iteration/seeded-from-finding on the new trace
                             (= finding-eid
                                (:db/id (:iteration/seeded-from-finding
                                         (d/pull (d/db conn)
                                                 [{:iteration/seeded-from-finding [:db/id]}]
                                                 new-eid))))
                             ;; :finding/carried-to-iteration on the carried finding
                             (= new-eid
                                (:db/id (:finding/carried-to-iteration
                                         (d/pull (d/db conn)
                                                 [{:finding/carried-to-iteration [:db/id]}]
                                                 finding-eid))))
                             ;; the slice's current iteration was repointed to the
                             ;; new trace by the mint (the seed sets none, so it
                             ;; moves from unset to the newly minted trace)
                             (nil? (:db/id slice-before))
                             (= new-eid (:db/id slice-after))
                             ;; and the new trace begins at :test-design (R-16.1)
                             (= :test-design
                                (store/derive-current-state conn run-eid slice-eid))))
                      ;; an INVALID finding does NOT satisfy the gate: the mint FAILS
                      ;; CLOSED and commits nothing (R-8.8, R-8.9, R-10.2).
                      (and (= :no-valid-finding (get-in minted [:error :code]))
                           (nil? (:iteration-eid minted))
                           ;; nothing minted: the slice's current iteration is
                           ;; unchanged and the finding carries nowhere
                           (= (:db/id slice-before) (:db/id slice-after))
                           (nil? (:finding/carried-to-iteration
                                  (d/pull (d/db conn)
                                          [{:finding/carried-to-iteration [:db/id]}]
                                          finding-eid)))))
                    ;; ---- RESUME-STALENESS sub-clause (R-8.7) ----
                    ;; Fresh iteration so the staleness clause is independent of the
                    ;; mint above; both approvals bind to the counter in force.
                    {stale-iter :iteration-eid} (seed-run+slice+iteration! conn)
                    start-counter (p15-advance-counter! conn stale-iter advance-role start)
                    bound-counter (p6-record-both-approvals! conn stale-iter)
                    ;; snapshot findings/reviews BEFORE the counter advance + stale
                    before-counts (p15-count-findings-and-reviews conn stale-iter)
                    ;; a later authoring Step outcome advances the counter; on resume
                    ;; the prior approvals no longer match and are stale (R-8.4/8.5).
                    advanced (p15-advance-counter! conn stale-iter advance-role advances)
                    staled   (orch/mark-stale-approvals! conn stale-iter)
                    stamped  (store/approvals-for-iteration conn stale-iter)
                    after-counts (p15-count-findings-and-reviews conn stale-iter)
                    stale-ok?
                    (and (= start start-counter)
                         (= start-counter bound-counter)
                         (= (+ bound-counter advances) advanced)
                         (= advanced (:current-counter staled))
                         ;; BOTH prior approvals are stamped stale for the
                         ;; Orchestrator's own reason, NOT a reviewer verdict (R-8.7)
                         (= 2 (count (:staled staled)))
                         (every? (fn [a]
                                   (and (true? (:approval/stale? a))
                                        (= :revision-advanced (:approval/stale-reason a))
                                        ;; the reviewer's verdict is untouched: the
                                        ;; staleness is bookkeeping, no withdrawal
                                        (= :approve (:approval/verdict a))))
                                 stamped)
                         ;; NO reviewer statement was written for the staleness:
                         ;; the finding/review counts are unchanged by staling
                         (= before-counts after-counts))]
                (and validity-ok? carry-ok? stale-ok?))
              (finally
                (store/close conn)
                (delete-tree! (io/file dir)))))))]
    (is (:pass? result)
        (str "Property 15 (end-to-end) failed with: " (pr-str (:shrunk result))))
    (is (= 100 (:num-tests result))
        "ran the minimum 100 iterations")))
