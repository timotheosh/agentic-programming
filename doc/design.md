# Design: Multi-Agent Development Workflow State Machine

This design realizes the requirements in `doc/requirements.md` (R-1 .. R-18). It
implements, in Clojure, the workflow that `README.org` describes as a *type*,
without depending on Claude Code. Orchestration is a **data-driven state
machine** with a **pure transition calculation** at the core; **Datalevin**
holds durable state written as it happens; an **agent-invocation protocol** fires
scoped work at backends (**hermes** first, then **kiro**) with selectable models
defaulting to agentic `auto`.

The design follows the Action / Calculation / Data (ACD) model: decisions are
calculations over immutable data; effects (Datalevin writes, process spawning,
filesystem inspection, the clock, UUID generation) live in thin actions at the
edges.

---

## 1. Domain vocabulary and identity

### 1.1 Run, slice, iteration, step

- **Run** — one end-to-end execution of the workflow for a feature/requirement
  set. Identified by a **run-id** (random UUID).
- **Slice** — one planned unit of behavioral change within a run (the README's
  "planned implementation slice"). Identified by a **slice-id** (UUID), ordered
  within its run.
- **Iteration** — one full pass of `test-design -> RED -> implement -> GREEN ->
  review -> reconcile` for a slice. **Each iteration is traced by a random UUID
  (`iteration-id`).** A slice has one or more iterations. **A review-authorized
  correction creates a new iteration**; test design and implementation *within*
  a pass modify the work of the current iteration without minting a new one.
  Because there is no in-place `:repair` stage (S 2.1, S 5.4a), an authorized
  correction always starts a **new** iteration at the `test-designer`. The
  iteration-id is the correlation key stamped on every
  fact, agent dispatch, review, finding, decision, and reconciliation record
  produced during that pass. This makes the whole history of a pass traceable
  and lets superseded decisions (R-17) be attributed to the exact pass that
  produced them.
  **`trace-id` is another name for `iteration-id`**; the two terms are
  interchangeable throughout this design.
- **Step** — one agent dispatch within an iteration (e.g. "run test-designer").
  Identified by a **step-id** (UUID) and carries the two-phase intent/outcome
  lifecycle (interruption recovery and two-phase dispatch support R-17; R-18
  supplies the iteration identity used to correlate them).

UUIDs are generated in an action (`clojure.core/random-uuid`) and passed into
calculations as data, so the calculations remain deterministic and testable.

### 1.2 Review/approval identity is the iteration (R-8, R-16)

**Reviews and approvals bind to an `iteration-id`, not to any Git-derived
content fingerprint.** There is no git SHA, no dirty fingerprint, and no
`workflow.git` namespace. The work a review judged is exactly the work produced
under that iteration's trace.

- A review records the `iteration-id` it reviewed (`:review/iteration`).
- An approval is valid **only for the iteration it named**. Because a
  review-authorized correction always mints a **new** iteration (S 2.1, S 5.4a),
  such a correction moves the pipeline into a new `iteration-id`, so a prior
  approval cannot attach to it. This is how R-8's "approvals bound to the exact
  work reviewed" is realized without content hashing: **authorized correction =>
  new iteration => prior approval is stale by construction.**
- On resume (interruption recovery, R-17, using iteration identity from R-18),
  an approval is honored only if the slice's **current iteration** equals the
  iteration the approval named; otherwise the work is
  treated as unapproved and must be re-approved. This is a comparison of
  iteration identities — a **calculation** — with no filesystem read required to
  decide approval validity.

The filesystem still holds the *work product* (S 5.4d), but pipeline identity —
"which pass are we in, and what did a review bind to?" — is carried entirely by
the iteration-id.

---

## 2. State machine

### 2.1 States (per slice/iteration)

```
:planning              ; run created, slices recorded, none started
:test-design           ; test-designer dispatched (start of EVERY iteration)
:implement             ; implementer dispatched
:review-correctness    ; correctness-reviewer dispatched (always first, R-7)
:review-structural     ; structural-reviewer dispatched, sees correctness (R-9)
:reconcile             ; reviewers disagree or REQUEST_CHANGES -> plan a correction
:slice-approved        ; both reviewers APPROVE the current iteration (R-16)
:escalated             ; blocked; awaiting human (R-15)
:documenting           ; flow-documenter (once, after all slices approved)
:done                  ; run complete
:failed-closed         ; a fail-closed condition halted the run
```

There is **no `:repair` state.** A `REQUEST_CHANGES` verdict is reconciled into
an accepted correction plan (R-12), and that acceptance **mints a new iteration
that begins at `:test-design`** (R-16 test-first; S 5.4a). Correction is not an
in-place patch step; it is a fresh, fully-traced pass.

`:review-correctness` and `:review-structural` are distinct states enforcing
the fixed order of R-7. There is no concurrent-review state.

RED and GREEN verification are **effects**, not states. The orchestrator's
`:verify-red` effect produces the existing `:red-verified` (or `:red-invalid`)
event and the `:verify-green` effect produces the existing `:green` event; those
events drive the transitions below. There are no `:red-verify` /
`:green-verify` states.

### 2.2 Transition table as data (R-4)

The transition function is a pure calculation driven by a data table:

```clojure
;; transition :: state -> event -> Result
;; Result = {:next-state s :effects [...] :emit [...facts...]}
;;        | {:error {:code ... :message ...}}    ; fail-closed
(def transitions
  {[:test-design      :red-verified]        {:next-state :implement}
   ;; behavior-preserving structural correction: existing coverage already
   ;; demonstrates behavior, so RED is not manufactured (R-16).
   [:test-design      :existing-coverage-confirmed] {:next-state :implement}
   [:test-design      :red-invalid]         {:next-state :test-design}   ; retry
   [:test-design      :production-touched]  {:error {:code :test-designer-wrote-production}} ; R-1
   [:implement        :green]               {:next-state :review-correctness}
   [:implement        :test-conflict]       {:next-state :escalated}      ; R-3
   [:review-correctness :approve]           {:next-state :review-structural} ; R-7
   [:review-correctness :request-changes]   {:next-state :review-structural} ; still run structural, R-7
   [:review-structural  :both-approve]      {:next-state :slice-approved}    ; R-16 AND-gate
   [:review-structural  :any-request-changes] {:next-state :reconcile}
   ;; Reconciliation acceptance does NOT go to a :repair state.
   ;; It mints a NEW iteration that begins at :test-design (R-16, S 5.4a).
   [:reconcile        :plan-accepted]       {:next-state :test-design
                                             :effects [{:effect/type :begin-iteration}]}
   [:reconcile        :allowance-exhausted] {:next-state :escalated}        ; R-15
   ...})
```

`transition` never performs I/O. It returns the next state plus a **description
of effects** (agent dispatches, verifications, `:begin-iteration`) and **facts
to persist**. The orchestrator (an action) interprets that description.

Illegal `[state event]` pairs, malformed events, and missing data all resolve to
`{:error ...}` and drive `:failed-closed` — approval is never inferred from
silence, timeout, or malformed output (fail-closed terminology from
requirements).

### 2.3 Multimethod dispatch on effects

Per the project rule (defmulti + all defmethods in the same namespace), effect
interpretation uses a single multimethod in the orchestrator namespace:

```clojure
(defmulti perform-effect (fn [ctx effect] (:effect/type effect)))
(defmethod perform-effect :dispatch-agent    [ctx e] ...)
(defmethod perform-effect :verify-red        [ctx e] ...)  ; -> :red-verified | :red-invalid
(defmethod perform-effect :verify-green      [ctx e] ...)  ; -> :green
(defmethod perform-effect :begin-iteration   [ctx e] ...)  ; new trace at test-design
(defmethod perform-effect :escalate          [ctx e] ...)
```

The multimethod is the boundary where calculations meet actions.

---

## 3. Datalevin schema (durable state: R-17, R-8, R-15, R-18)

One database directory per installation (path configurable). Every meaningful
fact is its own entity, so history is append-friendly and superseded decisions
are retained rather than overwritten. **State-machine progress is stored as an
append-only stream of immutable transition events (S 3.1); current state is
*derived* from the latest event, not mutated in place.**

### 3.1 Transition history as immutable events (R-17)

```clojure
;; Append-only. One entity per state transition that ever occurs.
;; Current state = the :event/to-state of the highest :event/seq for the target.
:event/id          {:db/valueType :db.type/uuid    :db/unique :db.unique/identity}
:event/run         {:db/valueType :db.type/ref}
:event/slice       {:db/valueType :db.type/ref}      ; nil for run-level events
:event/iteration   {:db/valueType :db.type/ref}      ; the trace this transition belongs to
:event/seq         {:db/valueType :db.type/long}     ; monotonic per run; total order
:event/from-state  {:db/valueType :db.type/keyword}
:event/to-state    {:db/valueType :db.type/keyword}
:event/trigger     {:db/valueType :db.type/keyword}  ; the event symbol that fired
:event/at          {:db/valueType :db.type/instant}
```

The scalar `:*/state` attributes below are a **materialized convenience** kept
in sync by the same transaction that appends the event; the event stream is the
source of truth and can rebuild them. Nothing overwrites history.

### 3.2 Entities

```clojure
(def schema
  {;; --- run / slice / iteration / step identity ---
   :run/id            {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :run/created-at    {:db/valueType :db.type/instant}
   :run/requirements  {:db/valueType :db.type/string}     ; verbatim / stable ref
   :run/state         {:db/valueType :db.type/keyword}    ; materialized (S 3.1)
   :run/event-seq     {:db/valueType :db.type/long}       ; last allocated :event/seq

   :slice/id          {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :slice/run         {:db/valueType :db.type/ref}
   :slice/order       {:db/valueType :db.type/long}
   :slice/state       {:db/valueType :db.type/keyword}    ; materialized (S 3.1)
   :slice/current-iteration {:db/valueType :db.type/ref}  ; the active trace (R-8 resume)

   :iteration/id      {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :iteration/slice   {:db/valueType :db.type/ref}
   :iteration/number  {:db/valueType :db.type/long}
   :iteration/started-at {:db/valueType :db.type/instant}

   ;; --- agent dispatch step, two-phase lifecycle (R-18 identity, R-17 durability) ---
   :step/id           {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :step/iteration    {:db/valueType :db.type/ref}
   :step/role         {:db/valueType :db.type/keyword}    ; :test-designer ...
   :step/backend      {:db/valueType :db.type/keyword}    ; :hermes | :kiro
   :step/model        {:db/valueType :db.type/string}     ; "auto" default
   :step/status       {:db/valueType :db.type/keyword}    ; :dispatched | :complete | :failed
   :step/dispatched-at {:db/valueType :db.type/instant}   ; intent committed BEFORE run
   :step/outcome-at   {:db/valueType :db.type/instant}    ; committed AFTER return
   :step/result-ref   {:db/valueType :db.type/string}     ; artifact path / summary

   ;; --- reviews & findings (R-5,R-6,R-9,R-10,R-11); bound to ITERATION (S 1.2) ---
   :review/id         {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :review/iteration  {:db/valueType :db.type/ref}        ; the work reviewed (R-8)
   :review/reviewer   {:db/valueType :db.type/keyword}    ; :correctness | :structural
   :review/verdict    {:db/valueType :db.type/keyword}    ; :approve | :request-changes
   :review/inputs-ref {:db/valueType :db.type/string}     ; correctness findings shown to structural (R-9)

   :finding/id        {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :finding/review    {:db/valueType :db.type/ref}
   :finding/owner     {:db/valueType :db.type/keyword}    ; :test-designer|:implementer|:both
   :finding/problem     {:db/valueType :db.type/string}   ; R-10 (1)
   :finding/evidence    {:db/valueType :db.type/string}   ; R-10 (2)
   :finding/justification {:db/valueType :db.type/string} ; R-10 (3)
   :finding/required-outcome {:db/valueType :db.type/string} ; R-10 (4)
   :finding/valid?    {:db/valueType :db.type/boolean}    ; all four present

   ;; --- decisions / agreed outcomes (R-12,R-13,R-14,R-17) ---
   :decision/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :decision/iteration {:db/valueType :db.type/ref}
   :decision/slice    {:db/valueType :db.type/ref}        ; scope (S 3.3)
   :decision/subject  {:db/valueType :db.type/string}     ; descriptive subject
   :decision/statement {:db/valueType :db.type/string}
   :decision/status   {:db/valueType :db.type/keyword}    ; :binding | :superseded
   :decision/accepted-by {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :decision/supersedes {:db/valueType :db.type/ref}      ; prior decision replaced (R-17)
   :decision/new-evidence {:db/valueType :db.type/string} ; required to reconsider (R-14)
   :decision/created-at {:db/valueType :db.type/instant}

   ;; --- repair/correction proposals (R-12) ---
   :proposal/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :proposal/slice    {:db/valueType :db.type/ref}        ; scope (S 3.3)
   :proposal/subject  {:db/valueType :db.type/string}
   :proposal/iteration {:db/valueType :db.type/ref}
   :proposal/body     {:db/valueType :db.type/string}
   :proposal/supersedes {:db/valueType :db.type/ref}      ; amended proposals are new (R-12)
   :proposal/accepted-by {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :proposal/resolution {:db/valueType :db.type/keyword}  ; :accepted | :rejected | :open

   ;; --- reconciliation allowance, per-disagreement (R-15, S 3.3) ---
   :disagreement/id   {:db/valueType :db.type/uuid :db/unique :db.unique/identity} ; stable identity
   :disagreement/slice {:db/valueType :db.type/ref}       ; descriptive scope attribute
   :disagreement/subject {:db/valueType :db.type/string}  ; descriptive subject attribute
   :disagreement/attempts-used {:db/valueType :db.type/long} ; accepted OR rejected count
   :disagreement/status {:db/valueType :db.type/keyword}     ; :open | :resolved | :exhausted
   })
```

### 3.3 Disagreement identity is the `:disagreement/id` (R-15)

A disagreement's **stable identity is its `:disagreement/id`** (a UUID), and that
identity is what carries the allowance across iterations, review rounds, and
restarts. `:disagreement/slice` and `:disagreement/subject` are **descriptive
attributes** that let the orchestrator and humans recognize *which* concern a
disagreement is about; they are not the identity and are not a uniqueness
constraint.

- The orchestrator resolves "is this the same disagreement we were already
  reconciling?" by locating the existing `:disagreement/id` for the concern
  (using slice + subject descriptively), not by deriving a normalized key. When
  it is the same disagreement, its `:attempts-used` is intact — the allowance
  cannot reset (R-15), even after a restart.
- Two different slices disagreeing about a same-sounding subject are simply two
  different `:disagreement/id` entities, each with its own allowance. No
  composite key, normalized subject, or `disagreement-key` function exists.

Decisions and proposals are likewise scoped by `:*/slice` so their subjects are
interpreted within a slice.

Notes:

- Decisions are never mutated in place; a replacement is a **new**
  `:decision` entity with `:decision/supersedes` pointing at the old one, whose
  `:decision/status` becomes `:superseded`. Both remain queryable (R-13, R-14,
  R-17).
- Amended repair proposals are new `:proposal` entities linked by
  `:proposal/supersedes`; acceptance does not carry forward (R-12).
- Every write is a single `d/transact!` = one durable LMDB commit (R-17). The
  transition-event append and any materialized `:*/state` update happen in the
  **same** transaction, so the derived state can never diverge from history.

---

## 4. Pure core calculations

Namespace `workflow.core` — no I/O, fully unit-testable.

- `(transition state event) -> result` — the transition table lookup (S 2.2),
  including `:test-design :existing-coverage-confirmed -> :implement` and
  `:reconcile :plan-accepted -> :test-design` with a `:begin-iteration` effect
  (no `:repair` state).
- `(finding-valid? finding) -> boolean` — true iff all four R-10 fields are
  present and non-blank.
- `(both-approved? correctness-review structural-review iteration-id) -> boolean`
  — true iff both verdicts are `:approve` **and** both reviews name the same
  `iteration-id` (R-8, R-16 AND-gate). No revision fingerprint involved.
- `(approval-valid-on-resume? approval current-iteration-id) -> boolean` — true
  iff the approval's iteration equals the slice's current iteration (R-8, S 1.2).
- `(allowance-remaining disagreement) -> long` — `(max 0 (- 2 attempts-used))`
  (R-15). `2` is the explicit allowance: initial proposal + one revision.
- `(consume-attempt disagreement) -> disagreement'` — increments
  `:attempts-used` regardless of accept/reject (R-15).
- `(binding-conflict? proposed-outcome binding-decisions) -> maybe-decision` —
  detects a repair that would contradict a binding decision (R-13).
- `(reconsideration-admissible? request) -> boolean` — true iff the request
  identifies a specific decision **and** supplies new evidence (R-14). Note:
  admissible != authorized; authorization still requires an accepted replacement
  decision through reconciliation.
- `(step-in-doubt? step) -> boolean` — true iff `:step/status = :dispatched` and
  no outcome recorded (R-18).
- `(dispatch-eligible? stage trace-facts) -> boolean` — stage-specific
  prerequisite check (S 5.4c); e.g. Structural is eligible once Correctness has
  a recorded verdict (APPROVE or REQUEST_CHANGES) for the *current iteration*,
  and an absent/in-doubt result never satisfies a gate. **The orchestrator owns
  all eligibility checks; agents never inspect workflow state to decide their own
  eligibility.**
- `(current-state event-stream target) -> keyword` — derive current state from
  the highest-`:event/seq` transition event (S 3.1), used to rebuild/verify the
  materialized state.

These calculations take plain maps (pulled from Datalevin or built in tests) and
return plain data/booleans.

---

## 5. Actions and edges

Namespaces `workflow.store` (Datalevin), `workflow.agents` (protocol +
backends), `workflow.orchestrator` (drives the loop). **There is no
`workflow.git` namespace** — pipeline identity is the iteration-id (S 1.2), not a
content fingerprint.

### 5.1 Agent-invocation protocol (all roles; hermes + kiro)

```clojure
(defprotocol AgentInvoker
  "Fire off an agent with scoped work and a selected model."
  (invoke [this task]
    "task: {:role kw :iteration-id uuid :prompt s :model s :cwd path :timeout-ms n :extra {...}}
     returns: {:status :ok|:error :stdout s :stderr s :exit int :raw {...}}"))
```

Model selection: `:model` defaults to `"auto"` (agentic auto). The task also
carries the `:role` so a backend can add role-appropriate flags.

**Agents do not inspect workflow state.** Eligibility, ordering, and identity are
the orchestrator's responsibility (S 4 `dispatch-eligible?`, S 5.4c). The
invocation adapter **retains the iteration/trace association as internal
metadata** so it can label a result and correlate it back to the dispatch, but
this is bookkeeping inside the adapter/orchestrator — it is **not** a guard the
agent participates in, and there is no requirement that an agent echo a trace
back. The orchestrator correlates a returned result to its dispatch via the step
record it created before spawning (S 5.2), because a one-shot agent process maps
one-to-one to the step that spawned it.

**HermesAgent** (confirmed CLI):

- One valid invocation form is used throughout, chosen because the design needs
  both quiet programmatic output (`-Q`) and a turn cap (`--max-turns`):

  ```
  hermes chat --query-file <path> --oneshot -Q --max-turns <n>
  ```

  The prompt is written to a file and passed with `--query-file` (nothing is
  shell-interpreted), `--oneshot` answers and exits, `-Q` suppresses banner/
  spinner/tool previews, and `--max-turns <n>` caps tool iterations.
- Model/provider: `--model <model>`; provider list includes literal `auto`, so
  the default maps to `--provider auto` (and no `-m`), i.e. agentic auto.
- Additional isolation flags used for agent runs: `--ignore-user-config`,
  `--ignore-rules`. **Worktrees are not used** (`--worktree` is deliberately
  omitted): the orchestrator manages working-copy scope itself and iteration
  identity carries pipeline identity, so a separate git worktree per run adds no
  value and is removed from the design.
- Exit code 0 = success; non-zero fails closed.

**KiroAgent** (provisional):

- Same protocol shape (prompt in, result out, model flag), model defaults to
  `"auto"`.
- The exact CLI invocation is **not yet confirmed**; the implementation is
  written behind the protocol and clearly marked provisional so it can be
  corrected without touching the state machine. A `:kiro` backend is selectable
  per step via `:step/backend`.

Process spawning is an action (`babashka.process` or `clojure.java.shell`).
Command construction (turning a task map into an argv vector) is a
**calculation** — `(hermes-argv task) -> [strings]` and `(kiro-argv task) ->
[strings]` — so argument/model wiring is unit-testable without spawning
anything. `hermes-argv` emits `chat --query-file <path> --oneshot -Q
--max-turns <n>` (plus model/provider and isolation flags) and never emits
`--worktree`.

### 5.2 Orchestrator loop with two-phase dispatch (R-18)

For each agent step:

1. **Record intent** (durable): transact `:step/status :dispatched`,
   `:step/dispatched-at`, and the `:step/iteration` trace. Commit *before*
   spawning. This step record is what the orchestrator later uses to correlate
   the returned result to this dispatch.
2. **Invoke** the agent via `AgentInvoker`. The adapter labels the run with the
   step's iteration as internal metadata.
3. **Record outcome** (durable): transact `:step/status :complete|:failed`,
   `:step/outcome-at`, `:step/result-ref` against the same step entity. The
   orchestrator correlates the returned result to this dispatch via the step
   record from phase 1 (one-shot process maps one-to-one to its step).

On resume, the orchestrator queries for steps where `step-in-doubt?` is true
(`:dispatched` with no outcome). Each such step is **reconciled against
observable reality** — re-run the relevant verification (RED/GREEN), inspect
artifacts, confirm the step's `:step/iteration` is still the slice's current
iteration — before its outcome is recorded. It is never assumed complete nor
assumed untouched (R-18). Where practical, agent effects are idempotent/
verifiable so re-checking is safe; if reality cannot be determined, the run
fails closed.

### 5.3 Enforcement mapping (which requirement is enforced where)

| Req | Enforcement point |
|-----|-------------------|
| R-1 | `:test-design` -> `:implement` only on a `:verify-red` effect emitting `:red-verified`; production-write during test-design => error state. |
| R-2 | `:implement`/correction refuse to start unless a relevant failing/covering test exists (effect precondition, R-16). |
| R-3 | Test-conflict event routes to `:escalated`; implementer never edits tests to pass. |
| R-4 | ACD structure of the code itself; structural review (R-6). |
| R-5 | `:review-correctness` inspects requirements <-> tests <-> behavior. |
| R-6 | `:review-structural` inspects organization/complexity/duplication. |
| R-7 | Ordered states: correctness always precedes structural, even on request-changes. |
| R-8 | Reviews/approvals bound to `:review/iteration`; `approval-valid-on-resume?` and `both-approved?` compare iteration identities (S 1.2). |
| R-9 | `:review/inputs-ref` feeds correctness findings into the structural dispatch. |
| R-10| `finding-valid?`; invalid findings are not actionable change requests. |
| R-11| Reviewer backends are invoked read-only; they emit findings, never edits. |
| R-12| `:proposal` accepted-by must contain both reviewers on the *same* proposal; amendments are new proposals. |
| R-13| `binding-conflict?` blocks repairs contradicting binding decisions. |
| R-14| `reconsideration-admissible?` gate + explicit accepted replacement. |
| R-15| `:disagreement/id` stable identity; `allowance-remaining`; exhaustion => `:escalated`. |
| R-16| Correction is a new iteration starting at `:test-design`; behavior-preserving refactor uses `:existing-coverage-confirmed`; re-verify GREEN; both re-approve. |
| R-17| Append-only transition events + append-only decision/finding history; supersede via refs; two-phase step lifecycle durably committed; interruption recovery. |
| R-18| Supplies the iteration identity stamped on steps/reviews used to correlate an interrupted dispatch with recorded progress on resume. |

---

### 5.4 Pipeline errors vs. process interruptions, dispatch eligibility, and authority

`trace-id` is another name for `iteration-id` (S 1.1): a trace is the correlation
key for one pass through the loop. The following four distinctions govern how the
orchestrator reacts to review outcomes, interruptions, dispatch gating, and where
truth lives. They refine — and do not alter — the requirements.

**(a) A `REQUEST_CHANGES` verdict is a pipeline error, handled on the current
trace before a correction trace is minted.**

A reviewer returning `REQUEST_CHANGES` is a *pipeline error* — an expected,
recorded workflow outcome, not a crash. When it occurs the orchestrator:

1. **records the verdict on the current trace** (the current `iteration-id`);
2. **completes the remaining review required by R-7** — Structural still runs
   after Correctness even when Correctness returned `REQUEST_CHANGES` (S 2.2,
   the `:review-correctness :request-changes -> :review-structural` edge);
3. **reconciles the findings** and obtains **both reviewers' acceptance of one
   repair plan** (R-12);
4. **only then mints a new trace** (a new `iteration-id`) and **begins the
   correction iteration at the `test-designer`** (R-16 test-first). There is no
   in-place `:repair` step: acceptance fires `:begin-iteration` (S 2.2).

So the correction iteration is a *new* trace, but the review completion and
reconciliation that authorize it all happen on the *current* trace first.

**(b) A process interruption is NOT a pipeline error and yields no review
outcome.**

If the orchestrator (or an agent) simply dies, that is not a verdict and
produces no `APPROVE`/`REQUEST_CHANGES`. On resume the orchestrator:

- **retains the same trace** (`iteration-id`) — it does not mint a correction
  trace merely because a process died;
- **reconciles or resumes the interrupted stage** using the two-phase step
  lifecycle (S 5.2, R-18): an in-doubt step is reconciled against observable
  reality, then its outcome is recorded, and the stage continues;
- **does not restart at the `test-designer`.** Restarting at test-design is
  reserved for a genuine correction iteration authorized by a completed review +
  reconciliation (case (a)), never for a bare interruption.

This distinction is why an absent result must never be read as a verdict: a
missing outcome is an interruption to reconcile (case (b)), not a
`REQUEST_CHANGES` to route (case (a)). Process lifecycle (alive/dead/resumed) is
tracked separately from pipeline outcomes (APPROVE / REQUEST_CHANGES); neither is
ever inferred from the other.

**(c) Dispatch eligibility uses stage-specific prerequisites, not "all prior
results succeeded."**

Whether a stage may be dispatched is decided by that stage's own prerequisites,
expressed as a calculation over recorded facts — not by requiring every
preceding result to be *successful*:

- `:review-structural` is eligible once Correctness has **recorded a verdict of
  either `APPROVE` or `REQUEST_CHANGES`** for the trace's current iteration
  (R-7). A Correctness `REQUEST_CHANGES` still satisfies the gate to run
  Structural.
- An **absent result from an interrupted invocation does not satisfy any gate.**
  Eligibility checks require a *recorded* prerequisite fact; a missing/in-doubt
  outcome (case (b)) is reconciled first, never treated as a satisfied
  prerequisite.

Concretely, `workflow.core` has a stage-eligibility predicate
`(dispatch-eligible? stage trace-facts) -> boolean`, whose Structural clause is
"Correctness verdict recorded for this iteration (APPROVE or REQUEST_CHANGES)".
This is a calculation over pulled facts and is unit-tested per stage.

**(d) Authority split: Datalevin for workflow state, filesystem for the work
product.**

- **Datalevin is authoritative for workflow state, pipeline identity, and
  dispatch eligibility** — runs, slices, iterations/traces, the transition-event
  stream, step lifecycle, reviews, verdicts, findings, decisions, proposals,
  disagreement allowances. Eligibility (case (c)) and approval validity (S 1.2)
  are decided only from Datalevin facts (iteration identities), with no content
  fingerprint.
- **The filesystem is authoritative for the work product** that an interrupted
  invocation may have affected — source files, test files, artifacts. R-18
  reconciliation of an in-doubt step therefore reads the *filesystem* (re-run
  RED/GREEN, inspect artifacts) to learn what actually happened to the work
  product, then records the reconciled outcome as a *Datalevin* fact.

The two never compete: Datalevin answers "where is the pipeline, which iteration
are we in, and what may run next?"; the filesystem answers "what did the agent
actually do to the code?". Resume consults Datalevin for the former and the
filesystem for the latter, and writes the reconciliation back to Datalevin.

---

## 6. Namespace layout

```
deps.edn
src/workflow/core.clj          ; pure calculations: transition table, predicates
src/workflow/store.clj         ; Datalevin: schema, connect, transact helpers, queries, event stream
src/workflow/agents.clj        ; AgentInvoker protocol; HermesAgent; KiroAgent; argv calcs
src/workflow/orchestrator.clj  ; drives the loop; perform-effect multimethod; resume/reconcile
src/workflow/main.clj          ; entry point / CLI wiring
test/workflow/core_test.clj
test/workflow/store_test.clj
test/workflow/agents_test.clj
test/workflow/orchestrator_test.clj
```

`defmulti perform-effect` and all its `defmethod`s live in
`workflow.orchestrator` (project rule: multimethod + all methods in one ns).
There is no `workflow.git` namespace.

---

## 7. Testing strategy (test-first, dogfooding the workflow)

Tests are written and shown RED before implementation (R-1 discipline applied to
this project itself):

- **core_test** — transition table correctness (incl.
  `:test-design :existing-coverage-confirmed -> :implement`,
  `:reconcile :plan-accepted -> :test-design` with `:begin-iteration`, and the
  absence of any `:repair`, `:red-verify`, or `:green-verify` state);
  `finding-valid?`; `both-approved?` matching on iteration-id;
  `approval-valid-on-resume?` on iteration identity;
  `allowance-remaining`/`consume-attempt` counting rejected attempts;
  `binding-conflict?`; `reconsideration-admissible?`; `step-in-doubt?`;
  `dispatch-eligible?` per stage (incl. Structural eligible after a Correctness
  REQUEST_CHANGES, and an absent result never eligible); `current-state` derived
  from the event stream. Pure, fast, no I/O.
- **store_test** — schema round-trip; durable resume (open, write, close,
  reopen, read); append-only transition events reconstruct current state (R-17);
  per-disagreement allowance survives reopen and does not reset on reopen (R-15);
  same-subject concerns in two slices keep independent `:disagreement/id`
  entities and allowances (S 3.3); superseded decision retained (R-17). Uses a temp Datalevin dir, cleaned up after.
- **agents_test** — `hermes-argv`/`kiro-argv` produce correct argv incl.
  default `auto`, the `chat --query-file <path> --oneshot -Q --max-turns <n>`
  form, and **never include `--worktree`**; `AgentInvoker` via a fake/record
  backend (no real process spawning in tests); protocol satisfied by both
  backends.
- **orchestrator_test** — full slice happy path; correctness-before-structural
  ordering (R-7); AND-gate on the same iteration (R-16); reconcile exhaustion ->
  escalate with per-disagreement allowance (R-15); two-phase dispatch leaves a
  recoverable in-doubt step that resume reconciles (R-18); a `REQUEST_CHANGES`
  completes the remaining review then mints a **new iteration at test-design**
  (S 5.4a) — never an in-place repair; a bare interruption resumes on the same
  trace without restarting at test-design (S 5.4b); a stale approval is invalid
  because the slice moved to a new iteration (R-8, S 1.2). Uses a fake
  `AgentInvoker` and a temp store.

Property-based tests (where useful): allowance never goes negative and never
resets across reopen; every persisted decision is either binding or has a
superseding decision; current state derived from the event stream always equals
the materialized `:*/state`.

---

## 8. Dependencies (deps.edn)

- `org.clojure/clojure`
- `datalevin/datalevin` (LMDB-backed durable Datalog).
- `babashka/process` (or `clojure.java.shell`) for spawning agent CLIs.
- test: `org.clojure/test.check` for property tests.

Datalevin requires specific JVM `--add-opens`/`--enable-native-access` flags;
these go in an `:aliases`/`:jvm-opts` entry in `deps.edn` and are validated when
the store tests first run.

---

## 9. Open items to confirm during implementation

1. **Kiro CLI invocation** is provisional; confirm the real command/flags and
   update `kiro-argv` only (state machine untouched).
2. **Datalevin version pin** and exact JVM `--add-opens` flags — validate on
   first `store_test` run.
