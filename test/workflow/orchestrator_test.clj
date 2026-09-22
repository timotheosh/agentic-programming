(ns workflow.orchestrator-test
  "Cross-cutting integration and property tests for the orchestrator, spanning
  multiple `workflow.orchestrator.*` sub-namespaces in one driven pass. Tests
  scoped to ONE sub-namespace live beside it under
  `test/workflow/orchestrator/*_test.clj` (mirroring the source split under
  `src/workflow/orchestrator/`, user-directed reorganization); this file is
  what remains after that split — the scenarios that genuinely exercise the
  composition between sub-namespaces (two-phase dispatch + drive together, or
  an end-to-end property over dispatch + drive + review + resume), not any
  single one of them in isolation.

  RED/GREEN verification is driven with an injectable shell command
  (`sh -c 'exit N'`) so no real suite is spawned; capability observation is
  driven against real temp files so `fs/observe-changes` confirms them on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [workflow.rules.core :as core]
            [workflow.agents :as agents]
            [workflow.store :as store]
            [workflow.orchestrator.core :as effects]
            [workflow.orchestrator.dispatch :as dispatch]
            [workflow.orchestrator.drive :as drive]
            [workflow.orchestrator.review :as review]
            [workflow.orchestrator.resume :as resume]
            [workflow.orchestrator.test-support :refer [with-store temp-dir!
                                                         delete-tree!
                                                         seed-run+slice+iteration!]]))

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
                  td   (dispatch/dispatch-step!
                        (merge ids {:conn conn :agent-invoker fake
                                    :role :test-designer :cwd dir
                                    :changes {:created [test-file]}}))
                  rev1 (store/current-revision conn (:iteration-eid ids))
                  ;; verify RED (non-zero exit) and drive test-design -> implement
                  red  (effects/perform-effect {:command ["sh" "-c" "exit 1"] :cwd dir}
                                               {:effect/type :verify-red})
                  d1   (drive/drive {} :test-design (:event red))
                  ;; STEP 2: implementer makes it GREEN (phase 1 + 2)
                  impl (dispatch/dispatch-step!
                        (merge ids {:conn conn :agent-invoker fake
                                    :role :implementer :cwd dir
                                    :changes {:edited [prod-file]}}))
                  rev2 (store/current-revision conn (:iteration-eid ids))
                  ;; verify GREEN (zero exit) and drive implement -> review
                  green (effects/perform-effect {:command ["sh" "-c" "exit 0"] :cwd dir}
                                                {:effect/type :verify-green})
                  d2    (drive/drive {} :implement (:event green))]
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

;; --- Property 14: Two-phase dispatch makes an interrupted step recoverable and
;;     fails closed when reality is indeterminate (R-18.1, R-18.2, R-18.5) ------
;;
;; Feature: orchestrator-state-machine, Property 14: Two-phase dispatch makes an
;; interrupted step recoverable and fails closed when reality is indeterminate.
;;
;; Where the example tests in `orchestrator.dispatch-test`/`orchestrator.resume-test`
;; above pin down specific two-phase / resume scenarios, this property exercises
;; the SAME guarantees across generated sequences of interrupted Steps. Each
;; iteration opens a FRESH temp Datalevin directory, seeds a run/slice/iteration,
;; and drives a generated sequence of Steps through the two-phase lifecycle,
;; asserting for EVERY generated Step:
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
                         {:keys [step-eid]} (dispatch/record-step-dispatch!
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
                         recon (resume/reconcile-step!
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
                                (= (if (dispatch/revision-advancing-role? role)
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

;; --- Property 2 (end-to-end): capability fail-closed enforcement -------------
;;
;; Feature: orchestrator-state-machine, Property 2: Any change outside a role's
;; capability fails closed and never advances.
;;
;; The pure clause of Property 2 (`core/capability-violation?` flags exactly the
;; out-of-boundary changes) is validated in `test/workflow/rules/core_test.clj`.
;; THIS is the END-TO-END clause: it drives EACH role through the orchestrator's
;; two-phase dispatch (`dispatch/dispatch-step!`) with a `fake-agent` producing an
;; OUT-OF-BOUNDARY change, backed by a REAL temp file on disk so
;; `fs/observe-changes` confirms it, and asserts every violation fails closed via
;; `dispatch/enforce-capability`:
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
                         dispatched  (dispatch/dispatch-step! ctx)
                         enforced    (dispatch/enforce-capability state dispatched)
                         step        (d/pull (d/db conn)
                                             [:step/status :step/role :step/outcome-at]
                                             (:step-eid dispatched))
                         expected-event (dispatch/violation-event-for role)
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

;; --- Property 6 (end-to-end): Revision-counter approval binding --------------
;;
;; Feature: orchestrator-state-machine, Property 6: Approvals bind to the Revision
;; counter value in force, and a recorded Step outcome advances the counter and
;; stales the approval
;;
;; The pure clauses of Property 6 (`core/advance-revision` = (inc counter);
;; `core/approval-valid?` true iff bound counter == current counter;
;; `core/both-approved?` only when both approve the same value) are validated in
;; `test/workflow/rules/core_test.clj`. THIS is the END-TO-END clause, exercising
;; DURABILITY across a simulated restart:
;;
;;   * HAPPY-PATH AND-gate on the SAME counter value: both reviewers approve the
;;     Iteration's CURRENT Revision counter value (in force), and `review/both-approved?`
;;     holds (R-8.1, R-8.3, R-16.4);
;;   * a later implementer/test-designer Step outcome ADVANCES the counter and
;;     STALES the prior approvals — even ACROSS A RESTART: the approvals are
;;     recorded bound to counter N, one or more counter-advancing Step outcomes are
;;     recorded (via the two-phase `record-step-dispatch!` + `store/record-step-outcome`),
;;     then the Datalevin store is CLOSED and REOPENED (store/close + store/connect).
;;     After the restart the reopened store reads the advanced counter, so
;;     `core/approval-valid?` is false for each prior approval, `resume/mark-stale-approvals!`
;;     marks them `:approval/stale?` with `:approval/stale-reason :revision-advanced`
;;     WITHOUT a reviewer statement (R-8.7), and `review/both-approved?` no longer
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
    (let [{:keys [step-eid]} (dispatch/record-step-dispatch!
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
  (let [c-review (review/record-review! conn {:iteration-eid iteration-eid
                                              :reviewer :correctness :verdict :approve
                                              :revision-counter (store/current-revision conn iteration-eid)})
        s-review (review/record-review! conn {:iteration-eid iteration-eid
                                              :reviewer :structural :verdict :approve
                                              :revision-counter (store/current-revision conn iteration-eid)})
        c-appr   (review/record-approval! conn {:review-eid (:review-eid c-review)
                                                :iteration-eid iteration-eid
                                                :reviewer :correctness :verdict :approve})]
    (review/record-approval! conn {:review-eid (:review-eid s-review)
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
                         (true? (review/both-approved? conn iteration-eid))
                         (true? (review/both-approved? conn iteration-eid bound-counter)))
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
                          (and (false? (review/both-approved? conn2 iteration-eid))
                               (false? (review/both-approved? conn2 iteration-eid after-counter))
                               (every? #(not (core/approval-valid? % after-counter)) reopened))
                          ;; mark-stale-approvals! records the staleness as a
                          ;; non-reviewer fact: :revision-advanced, no verdict (R-8.7)
                          staled  (resume/mark-stale-approvals! conn2 iteration-eid)
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
                               (false? (review/both-approved? conn2 iteration-eid)))]
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
;;     without a reviewer ---------------------------------------------------
;;
;; Feature: orchestrator-state-machine, Property 15: A review round ending without
;; approval records a justified finding, carries it forward, and records
;; resume-staleness without a reviewer
;;
;; The pure clauses (`core/finding-valid?` true iff all four R-10 fields present
;; and non-blank; `core/approval-valid?` true iff bound counter == current) are
;; validated in `test/workflow/rules/core_test.clj`. THIS is the END-TO-END clause,
;; over a real durable store, with TWO independent sub-clauses:
;;
;;   * FINDING-CARRY (R-8.6, R-8.8, R-8.9): a REQUEST_CHANGES / withheld-approval
;;     round may not mint a new Iteration until an R-10-compliant `:finding`
;;     (`core/finding-valid?` true) has been durably recorded. When only an INVALID
;;     finding exists (one R-10 field missing), `effects/mint-iteration!` FAILS CLOSED
;;     with `:no-valid-finding` and commits nothing — no new Iteration, no link.
;;     When a VALID finding exists, the mint succeeds: it creates a NEW trace at
;;     `:iteration/revision` 0, links BOTH ways (`:iteration/seeded-from-finding`
;;     on the new Iteration / `:finding/carried-to-iteration` on the carried
;;     finding), and repoints the slice's current iteration — carrying the recorded
;;     failure information into the new pass together with the new trace-id.
;;   * RESUME-STALENESS (R-8.7): a prior approval treated as unapproved on resume
;;     purely because the Revision counter advanced is stamped by the Orchestrator
;;     ITSELF (`resume/mark-stale-approvals!`) with `:approval/stale-reason
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
  (let [{:keys [review-eid]} (review/record-review!
                              conn {:iteration-eid iteration-eid
                                    :reviewer :correctness
                                    :verdict :request-changes
                                    :revision-counter (or (store/current-revision conn iteration-eid) 0)})
        full   {:problem          "behavior B is missing"
                :evidence         "test T fails on input X"
                :justification    "requirement R-99 mandates B"
                :required-outcome "implement B so T passes"}
        fields (cond-> full missing-field (dissoc missing-field))
        {:keys [finding-eid valid?]} (review/record-finding!
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
    (let [{:keys [step-eid]} (dispatch/record-step-dispatch!
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
                    minted (effects/mint-iteration!
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
                    staled   (resume/mark-stale-approvals! conn stale-iter)
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
