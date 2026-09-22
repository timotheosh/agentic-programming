(ns workflow.rules.core
  "Pure calculations for the multi-agent development workflow state machine.

  This namespace holds only calculations (in the Action/Calculation/Data sense):
  deterministic functions of their explicit inputs with no I/O and no mutation.
  The state machine's knowledge lives in the `transitions` data table; the
  `transition` calculation is a pure lookup over that table and fails closed for
  every pair the table does not name."
  (:require [clojure.string :as str]))

;; --- The transition table as data (design R-4; no :repair state) ------------
;;
;; transition :: state -> event -> Result
;;   Result = {:next-state s}                 ; advance
;;          | {:next-state s :effects [...]}  ; advance + described effects
;;          | {:error {:code ...}}            ; fail-closed
;;
;; RED and GREEN verification are effects, not states, so there are no
;; :red-verify / :green-verify states here. A REQUEST_CHANGES is reconciled into
;; an accepted correction plan that mints a NEW iteration at :test-design via the
;; :begin-iteration effect, so there is no :repair state either.

(def transitions
  "Pure data: the legal [state event] -> Result pairs of the workflow."
  {;; test-design: RED verification gates entry to implementation (R-1).
   [:test-design :red-verified]                 {:next-state :implement}
   ;; Behavior-preserving structural correction: existing coverage already
   ;; demonstrates the behavior, so RED is not manufactured (R-16.3).
   [:test-design :existing-coverage-confirmed]  {:next-state :implement}
   ;; Invalid RED retries at test-design (R-1.3).
   [:test-design :red-invalid]                  {:next-state :test-design}
   ;; The test-designer may not write production code (R-1.5).
   [:test-design :production-touched]           {:error {:code :test-designer-wrote-production}}

   ;; implement: GREEN advances to review; the implementer may not touch tests.
   [:implement :green]                          {:next-state :review-correctness}
   [:implement :test-touched]                   {:error {:code :implementer-wrote-test}}
   ;; A reported test conflict routes back to the test-designer on the CURRENT
   ;; iteration (no new trace-id, no :begin-iteration) (R-3.3, R-3.4).
   [:implement :test-conflict]                  {:next-state :test-design
                                                 :effects [{:effect/type :route-conflict-to-test-designer}]}

   ;; review-correctness always runs first; either verdict proceeds to
   ;; structural review (R-7). A reviewer may not edit any file (R-11.3).
   [:review-correctness :approve]               {:next-state :review-structural}
   [:review-correctness :request-changes]       {:next-state :review-structural}
   [:review-correctness :reviewer-edited]       {:error {:code :reviewer-attempted-repair}}

   ;; review-structural: the AND-gate. Both approve => slice approved; any
   ;; request-changes => reconcile. A reviewer may not edit any file (R-11.3).
   [:review-structural :both-approve]           {:next-state :slice-approved}
   [:review-structural :any-request-changes]    {:next-state :reconcile}
   [:review-structural :reviewer-edited]        {:error {:code :reviewer-attempted-repair}}

   ;; reconcile: an accepted correction plan mints a NEW iteration that begins
   ;; at :test-design (R-16.1); an exhausted allowance escalates (R-15.4).
   [:reconcile :plan-accepted]                  {:next-state :test-design
                                                 :effects [{:effect/type :begin-iteration}]}
   [:reconcile :allowance-exhausted]            {:next-state :escalated}})

(defn transition
  "Pure lookup of the Result for `state` on `event`.

  Returns the table's Result for a legal pair. Illegal [state event] pairs,
  malformed events, and missing data all resolve to {:error ...} (fail closed) —
  approval is never inferred from silence, timeout, tool failure, or malformed
  output. Performs no I/O."
  [state event]
  (or (get transitions [state event])
      {:error {:code :illegal-transition
               :message "No legal transition for the given [state event] pair."
               :state state
               :event event}}))

;; --- Revision-counter calculations (design R-8; Property 6) -----------------
;;
;; A Revision is the identity an approval binds to: a monotonically increasing
;; integer counter scoped to the current Iteration and held in Datalevin. It
;; carries NO file information — it is not a hash, not a manifest, not derived
;; from or compared against the filesystem, and not git. The functions below are
;; pure calculations over integer counter values; every filesystem/Datalevin
;; read that supplies those values is an action living elsewhere.

(defn advance-revision
  "Advance the Revision counter by one: pure `(inc counter)` (R-8.4).

  This is the monotonic increment applied to the Iteration's Revision counter
  when an implementer or test-designer Step outcome is recorded. The transaction
  that persists the incremented value is an action in `workflow.store`; the
  increment itself is this calculation. Strictly monotonic: the result is always
  the successor of `counter`."
  [counter]
  (inc counter))

(defn approval-valid?
  "True iff `approval` still binds to the Iteration's `current-revision-counter`.

  An approval binds to the Revision counter value in force when it was recorded
  (`:approval/revision-counter`). It is valid only while that bound value equals
  the Iteration's current counter; once an implementer/test-designer Step outcome
  advances the counter, the bound value no longer matches and the approval is
  stale, so re-approval is required (R-8.3, R-8.4, R-8.5).

  This is a pure integer comparison over a counter value read from Datalevin —
  approval validity is decided by the Revision counter value, never by iteration
  identity and never by any file-derived value."
  [approval current-revision-counter]
  (= (:approval/revision-counter approval) current-revision-counter))

(defn both-approved?
  "True iff BOTH reviewers approve the SAME Revision counter value (R-16 AND-gate).

  Holds only when both `correctness-approval` and `structural-approval` carry an
  `:approve` verdict AND both bind to the round's `revision-counter` value
  (R-8.1, R-8.3, R-16.4). If either verdict is not `:approve`, or either approval
  binds to a different counter value, the AND-gate does not hold. Pure comparison
  over recorded facts; no I/O."
  [correctness-approval structural-approval revision-counter]
  (and (= :approve (:approval/verdict correctness-approval))
       (= :approve (:approval/verdict structural-approval))
       (approval-valid? correctness-approval revision-counter)
       (approval-valid? structural-approval revision-counter)))

;; --- Finding validity (design R-10; Property 8) -----------------------------
;;
;; Every requested change (finding) must be fully justified: it explains the
;; problem, supplies supporting evidence, gives the justification, and states
;; the required outcome (R-10.1). A finding that omits any one of those four is
;; an invalid change request (R-10.2). This is a pure decision over the finding
;; map; the persistence of a finding is an action living elsewhere.

(def finding-required-fields
  "The four R-10 fields a valid finding must present and fill (R-10.1)."
  [:finding/problem :finding/evidence :finding/justification :finding/required-outcome])

(defn finding-valid?
  "True iff `finding` is fully justified per R-10.

  Valid iff ALL FOUR R-10 fields — :finding/problem, :finding/evidence,
  :finding/justification, and :finding/required-outcome — are present and
  non-blank. A missing, nil, or blank (empty or whitespace-only) value in any
  one of the four makes the finding an invalid change request (R-10.2). Pure
  calculation over a plain map; no I/O."
  [finding]
  (every? (fn [field]
            (let [v (get finding field)]
              (and (string? v) (not (str/blank? v)))))
          finding-required-fields))

;; --- Reconciliation-allowance calculations (design R-15; Property 12) --------
;;
;; A Disagreement carries a bounded Reconciliation allowance: one initial
;; proposal plus one revision, for two proposal attempts total (R-15.1). The
;; allowance is tracked as an accepted-OR-rejected count on
;; :disagreement/attempts-used, keyed to the Disagreement's stable
;; :disagreement/id. The functions below are pure calculations over that count;
;; the ACID transaction that durably records a consumption (R-15.5) and the
;; per-:disagreement/id preservation across restart (R-15.3) are actions living
;; in `workflow.store`.

(def reconciliation-allowance
  "The explicit per-Disagreement allowance: initial proposal + one revision
  (two proposal attempts total) (R-15.1)."
  2)

(defn allowance-remaining
  "Remaining Reconciliation allowance for `disagreement`: `(max 0 (- 2 used))`
  (R-15.1).

  Reads the accepted-OR-rejected count from :disagreement/attempts-used (nil
  treated as zero) and returns how many of the two allowed proposal attempts
  remain. Clamped at zero so an exhausted or over-consumed allowance never goes
  negative, and it never exceeds the explicit allowance of two. Pure calculation
  over a plain map; no I/O."
  [disagreement]
  (max 0 (- reconciliation-allowance
            (or (:disagreement/attempts-used disagreement) 0))))

(defn consume-attempt
  "Consume one Reconciliation allowance attempt: increment :attempts-used and
  return the updated Disagreement (R-15.2).

  Increments :disagreement/attempts-used by one regardless of whether the
  proposal was accepted or rejected — a rejected proposal consumes allowance
  identically to an accepted one (R-15.2) — so the count only ever increases.
  The Disagreement's stable :disagreement/id is preserved. Pure calculation over
  a plain map; the durable per-id recording of the consumption is an action
  living elsewhere (R-15.5)."
  [disagreement]
  (update disagreement :disagreement/attempts-used (fnil inc 0)))

;; --- Decision / reconsideration calculations (design R-13, R-14; Prop 10, 11) -
;;
;; An agreed outcome is recorded as a Decision entity: a descriptive
;; :decision/subject, the agreed :decision/statement, and a :decision/status of
;; :binding or :superseded. A Decision stays :binding until a replacement is
;; accepted through reconciliation, at which point the prior Decision is flipped
;; to :superseded (a new Decision links back via :decision/supersedes); both
;; remain queryable (R-13.1, R-17.2). A proposed repair/outcome carries the
;; :proposal/subject it acts on and the :proposal/body outcome it would produce.
;; The functions below are pure decisions over those plain maps; the ACID
;; transaction that records a decision or supersession is an action living in
;; `workflow.store`.

(defn binding-conflict?
  "Return the binding Decision a `proposed-outcome` would contradict, or nil.

  Scans `binding-decisions` for a Decision that (a) is still in force
  (:decision/status = :binding — a :superseded Decision never blocks a repair,
  R-13.1) and (b) shares the proposed outcome's :proposal/subject yet whose
  agreed :decision/statement differs from the proposed :proposal/body. Such a
  repair contradicts a Binding decision and must be rejected until the outcome is
  formally replaced (R-13.2); the returned Decision names what it conflicts with.

  Returns nil when the proposal agrees with the binding statement on its subject,
  addresses an unrelated subject, or only overlaps a superseded Decision — i.e.
  no still-binding outcome is contradicted. Every subsequent repair is checked
  against the whole set of binding decisions (R-13.3). Pure calculation over
  plain maps; no I/O."
  [proposed-outcome binding-decisions]
  (let [subject (:proposal/subject proposed-outcome)
        body    (:proposal/body proposed-outcome)]
    (first
     (filter (fn [d]
               (and (= :binding (:decision/status d))
                    (= subject (:decision/subject d))
                    (not= body (:decision/statement d))))
             binding-decisions))))

(defn reconsideration-admissible?
  "True iff a reconsideration `request` identifies a specific decision AND
  supplies new evidence (R-14.1, R-14.2).

  Admissible iff BOTH :reconsideration/decision-id is present (a specific
  accepted Decision is named) AND :reconsideration/new-evidence is present and
  non-blank (empty or whitespace-only evidence does not count). A request lacking
  either is inadmissible. Note: admissible is NOT authorized — admissibility only
  permits reconsideration; the prior Decision stays binding until a replacement
  is accepted through reconciliation (R-14.3), which is decided elsewhere. Pure
  calculation over a plain map; no I/O."
  [request]
  (let [evidence (:reconsideration/new-evidence request)]
    (and (some? (:reconsideration/decision-id request))
         (string? evidence)
         (not (str/blank? evidence)))))

;; --- Capability-boundary calculations (design R-1, R-3, R-11; Property 2) ----
;;
;; Role capability boundaries are symmetric and enforced fail-closed. This
;; namespace holds the two PURE pieces of that mechanism: the role -> capability
;; descriptor mapping, and the violation decision over the produced changes an
;; invocation actually made. Observing those changes from the filesystem and
;; classifying each path as a test file or a production/implementation file is an
;; ACTION living in `workflow.fs`; comparing the classified changes against the
;; role's allowed set is the CALCULATION here. The fail-closed transition an
;; observed violation drives is an action in `workflow.orchestrator`.
;;
;; A produced change is a plain map {:path p :change :created|:edited :class c},
;; where :class is the classification the observing action attached — :test for a
;; test file, :production for a production/implementation file. `capability-for`
;; maps a role to its descriptor keyword; `capability-descriptors` declares, per
;; descriptor, the set of change classes the role MAY write.

(def capability-descriptors
  "Per-capability data: the set of produced-change classes a role MAY write.

  The boundaries are symmetric (design R-1, R-3): the test-designer owns test
  authorship and is walled out of production; the implementer owns production
  authorship and is walled out of tests; a reviewer may write nothing at all.
  Both new-file creation and edits of an out-of-set class are violations — the
  decision is over the change's :class, not its :change kind."
  {;; test-designer: may author/edit test files; NO production/implementation
   ;; files, neither edits nor new-file creation (R-1.4, R-1.5).
   :test-authoring       #{:test}
   ;; implementer: may author/edit production files; authors NO tests ever and
   ;; never modifies existing tests (R-3.1, R-3.2, R-3.5, R-3.6).
   :production-authoring #{:production}
   ;; correctness-reviewer / structural-reviewer: read-only; ANY file edit or
   ;; repair is a violation (R-11.2, R-11.3).
   :read-only            #{}})

(def role->capability
  "Pure mapping from workflow role to its capability descriptor keyword.

  test-designer -> :test-authoring, implementer -> :production-authoring, and
  both reviewers -> :read-only (design Capability descriptors table)."
  {:test-designer        :test-authoring
   :implementer          :production-authoring
   :correctness-reviewer :read-only
   :structural-reviewer  :read-only})

(defn capability-for
  "Pure mapping from `role` to its capability descriptor keyword (R-1.4, R-3.1,
  R-11.2).

  :test-designer -> :test-authoring, :implementer -> :production-authoring, and
  :correctness-reviewer / :structural-reviewer -> :read-only. Returns nil for an
  unknown role, so an unrecognized role authorizes nothing (fail closed: it has
  no allowed change classes downstream). Pure lookup; no I/O."
  [role]
  (get role->capability role))

(defn capability-violation?
  "Return the first produced change that falls outside `capability`'s allowed
  set, or nil when every change is within boundary (design Property 2).

  `capability` is a descriptor keyword (`:test-authoring`,
  `:production-authoring`, `:read-only`); `observed-changes` is the collection of
  produced changes an invocation made, each a map {:path p :change
  :created|:edited :class :test|:production} classified by the observing action.
  A change is a violation when its :class is NOT in the descriptor's allowed set
  — a test-designer touching a production/implementation file, an implementer
  authoring or modifying any test file, or a reviewer editing any file (R-1.5,
  R-3.6, R-11.3). Both new-file creation and edits count; the decision is over
  the change's :class, so the boundaries are symmetric (R-16.6).

  An unknown/absent capability allows nothing, so any change is a violation
  (fail closed). Returns nil only when there is no offending change. Pure
  calculation over plain maps; the observation of produced changes and the
  fail-closed transition a returned violation drives are actions living
  elsewhere."
  [capability observed-changes]
  (let [allowed (get capability-descriptors capability #{})]
    (first
     (filter (fn [change]
               (not (contains? allowed (:class change))))
             observed-changes))))

;; --- Two-phase Step lifecycle: in-doubt detection (design R-18; Property 15) --
;;
;; A Step carries a two-phase intent/outcome lifecycle (R-18.1): the dispatch
;; intent is committed durably BEFORE the agent runs (:step/status :dispatched)
;; and the outcome is committed durably AFTER it returns (:step/status :complete
;; or :failed). A Step found still at :dispatched with no recorded outcome is "in
;; doubt": on resume it is neither assumed complete nor assumed untouched, and it
;; must be reconciled against observable reality before an outcome is recorded
;; (R-18.2, R-18.3). This is a pure decision over the recorded Step map; the
;; Datalevin read that supplies the Step and the filesystem/verification reads
;; that reconcile it are actions living elsewhere.

(defn step-in-doubt?
  "True iff `step` is dispatched with no outcome recorded (R-18.2).

  Holds exactly when :step/status is :dispatched — the intent was committed
  before the agent ran but no terminal outcome (:complete or :failed) was ever
  committed after it returned. Such a Step is in doubt on resume: it must be
  reconciled against observable reality before its outcome is recorded, never
  assumed complete and never assumed untouched (R-18.2, R-18.3). Any terminal
  status, or a missing/other status, is not in doubt. Pure calculation over the
  recorded Step map; no I/O."
  [step]
  (= :dispatched (:step/status step)))

;; --- Dispatch eligibility: the Orchestrator's stage prerequisites -----------
;;
;; The Orchestrator owns ALL eligibility checks; agents never inspect workflow
;; state to decide their own eligibility (design, Pure core calculations). Each
;; stage has a prerequisite over the facts recorded for the CURRENT iteration
;; (trace-id):
;;
;;   :implement          — a relevant test that covers the intended behavior
;;                         (or demonstrates the defect, for a repair) is recorded
;;                         (R-2.1, R-2.2, R-2.3, R-16.1, R-16.2).
;;   :review-structural  — a correctness verdict (APPROVE or REQUEST_CHANGES) is
;;                         recorded for the current iteration; a correctness
;;                         REQUEST_CHANGES still enables structural (R-7.1, R-7.2).
;;
;; An absent or in-doubt result NEVER satisfies a gate: eligibility is inferred
;; only from a durably-recorded fact, never from silence, a dispatched-but-
;; unfinished Step, or a missing artifact (R-7.3, R-18.2). Stages without a
;; recorded prerequisite here are not gated by this calculation and are governed
;; by the transition table instead, so this predicate is conservative — an
;; unknown stage is not declared eligible.
;;
;; `trace-facts` is a plain map of the facts recorded for the current iteration,
;; pulled from Datalevin (or built in tests):
;;   {:relevant-test?           bool  ; a relevant test is recorded (R-2)
;;    :correctness-verdict      kw    ; :approve | :request-changes | nil/absent
;;    :correctness-in-doubt?    bool} ; the correctness Step is dispatched, no outcome

(defn- correctness-verdict-recorded?
  "True iff a correctness verdict is durably recorded for the current iteration.

  A verdict counts only when it is one of the two terminal review verdicts
  (:approve or :request-changes) AND the correctness Step is not in doubt — an
  absent verdict or a dispatched-but-unfinished correctness Step never counts
  (R-7.1, R-18.2)."
  [{:keys [correctness-verdict correctness-in-doubt?]}]
  (and (not correctness-in-doubt?)
       (contains? #{:approve :request-changes} correctness-verdict)))

(defn dispatch-eligible?
  "True iff `stage` may be dispatched given the current iteration's `trace-facts`.

  Stage-specific prerequisite check over the facts recorded for the current
  iteration (trace-id). The Orchestrator owns this check; agents never decide
  their own eligibility.

  - :implement — eligible only when a relevant test that covers the intended
    behavior (or demonstrates the defect, for a repair) is recorded
    (:relevant-test? true); absent such a test, implementation is refused
    (R-2.1, R-2.2, R-2.3, R-16.1).
  - :review-structural — eligible only once a correctness verdict (:approve OR
    :request-changes) is recorded for the current iteration; a correctness
    REQUEST_CHANGES still enables structural (R-7.1, R-7.2).

  An absent or in-doubt result NEVER satisfies a gate — eligibility is inferred
  only from a durably-recorded fact, never from silence, a dispatched-but-
  unfinished Step, timeout, or a missing artifact (R-7.3, R-18.2). Any other
  stage is not gated by this calculation and returns false (conservative:
  eligibility is never inferred for a stage this predicate does not name). Pure
  calculation over a plain facts map; no I/O."
  [stage trace-facts]
  (case stage
    :implement          (boolean (:relevant-test? trace-facts))
    :review-structural  (correctness-verdict-recorded? trace-facts)
    false))

;; --- Deriving current state from the append-only event stream (R-17.1) -------
;;
;; State-machine progress is stored as an append-only stream of immutable
;; transition events; current state is DERIVED from the latest event, not mutated
;; in place (design, Data Models). The current state of a target (a run or a
;; slice) is the :event/to-state of that target's highest-:event/seq transition
;; event — :event/seq is monotonic per run and gives a total order. This lets the
;; materialized :*/state be rebuilt/verified from history so the derived state
;; can never diverge from the event stream. This is a pure calculation over an
;; event list; the Datalevin query that supplies the events is an action.

(defn current-state
  "Derive the current state of `target` from `event-stream` (R-17.1).

  Returns the :event/to-state of the highest-:event/seq transition event that
  belongs to `target` — the target being the ref value carried on the event's
  target attribute. Because :event/seq is monotonic per run and totally orders
  the stream, the highest-seq event's destination state is the current state,
  reconstructed purely from history (so the materialized :*/state can be rebuilt
  and verified against it). Events not belonging to `target` are ignored. Returns
  nil when no event in the stream belongs to `target`.

  `event-stream` is a seq of transition-event maps, each carrying :event/seq,
  :event/to-state, and a target ref. `target-attr` selects which ref identifies
  the target (:event/run for a run, :event/slice for a slice); it defaults to
  :event/slice, the per-slice state these events most often track. Pure
  calculation over an event list; the query that supplies it is an action."
  ([event-stream target]
   (current-state event-stream target :event/slice))
  ([event-stream target target-attr]
   (some->> event-stream
            (filter #(= target (get % target-attr)))
            seq
            (apply max-key :event/seq)
            :event/to-state)))
