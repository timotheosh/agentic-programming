# Design: Multi-Agent Development Workflow State Machine

This design realizes the requirements in
`.kiro/specs/orchestrator-state-machine/requirements.md` (R-1 .. R-18). It
implements, in Clojure, the workflow that `README.org` describes as a *type*,
without depending on a particular agent host's subagent or hook machinery.
Orchestration is a **data-driven state machine** with a **pure transition
calculation** at the core; **Datalevin** holds durable state written as each
meaningful fact happens; an **agent-invocation protocol** fires scoped work at
backends (**hermes** first, then **kiro**) with selectable models defaulting to
agentic `auto`.

The design follows the Action / Calculation / Data (ACD) model from Eric
Normand's *Grokking Simplicity*: decisions are calculations over immutable data;
effects (Datalevin writes, process spawning, filesystem reads, the clock, UUID
generation) live in thin actions at the edges.

**Three sources of truth, and no git.** The source of truth for a Run is exactly
three things: the current state of the filesystem, the state machine managed by
the Orchestrator, and the Datalevin database. **Git is NOT a source of truth and
is NOT used for revision or approval identity.** Neither the Orchestrator nor any
agent commits anything to git. There is no `workflow.git` namespace, no git SHA,
and no worktree anywhere in this design. Revision is a monotonic per-iteration
counter recorded in Datalevin (see below), not a filesystem-derived value.
`README.org` is a reference type only and MUST NOT be modified by the
implementation.

---

## Overview

This design realizes requirements R-1 .. R-18. The system is a data-driven state
machine whose core is a pure `transition` calculation; durable state lives in
Datalevin (LMDB/ACID) and is written as it happens so a halted run resumes from
the last committed point; and agents are invoked through a `defprotocol` with
hermes (confirmed) and kiro (provisional) backends, model defaulting to agentic
`auto`. Decisions are calculations over immutable data (ACD); effects live in
thin actions at the edges.

The sections below are organized as **Architecture** (domain identity, the state
machine, and namespace layout), **Data Models** (the Datalevin schema),
**Components and Interfaces** (the pure core calculations and the edge
actions/protocol, including the Revision-counter calculations and the
capability-boundary enforcement), **Correctness Properties** (per-requirement
enforcement and invariants), **Error Handling** (pipeline errors vs.
interruptions and fail-closed behavior), **Testing Strategy**, **Dependencies**,
and **Open Items**.

## Architecture

### Domain vocabulary and identity

#### Run, slice, iteration, step

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
  Because there is no in-place `:repair` stage, an authorized correction always
  starts a **new** iteration at the `test-designer`. The iteration-id is the
  **correlation key** stamped on every fact, agent dispatch, review, finding,
  decision, and reconciliation record produced during that pass. This makes the
  whole history of a pass traceable and lets superseded decisions (R-17) be
  attributed to the exact pass that produced them.
  **`trace-id` is another name for `iteration-id`**; the two terms are
  interchangeable throughout this design.
- **Step** — one agent dispatch within an iteration (e.g. "run test-designer").
  Identified by a **step-id** (UUID) and carries the two-phase intent/outcome
  lifecycle (interruption recovery and two-phase dispatch, R-18).

UUIDs are generated in an action (`clojure.core/random-uuid`) and passed into
calculations as data, so the calculations remain deterministic and testable.

#### Two identities: the iteration correlates the pass, the Revision counter binds the approval (R-8, R-16)

There are **two distinct identities** in this design, and keeping them separate
is what makes R-8 correct:

1. **`iteration-id` (trace-id) — the correlation key.** It answers "*which pass
   produced this fact?*" It is stamped on every step, review, finding, decision,
   proposal, and reconciliation record so the complete history of a pass is
   traceable and attributable (R-17). It does **not**, by itself, decide whether
   an approval is still valid.

2. **Revision counter — the approval-binding identity.** It answers "*which
   recorded version of the work did this approval judge?*" A **Revision** is a
   **monotonically increasing integer counter**, scoped to the current Iteration
   and stored in Datalevin, that is **incremented each time an `implementer` or
   `test-designer` Step outcome is durably recorded** (R-8.4, R-17.4). A Revision
   carries **no file information** — it is not a hash, not a manifest, not
   derived from or compared against the filesystem, and not a git value. An
   **approval binds to the Revision counter value in force** when the approval
   was recorded (R-8.3), not to the iteration.

Why both are needed: the iteration correlates the pass and gives every fact a
traceable home; the Revision counter pins the approval to the recorded version of
the work it judged. This reconciles cleanly:

- A review records both the `iteration-id` it ran under (`:review/iteration`,
  correlation) **and** the Revision counter value it judged
  (`:review/revision-counter`).
- An approval records the Revision counter value it judged
  (`:approval/revision-counter`). It is valid only for that counter value.
- On resume (R-8.5), the orchestrator **reads the current Revision counter for
  the Iteration from Datalevin** and honors an approval only if the approval's
  bound counter value **equals** the current counter; otherwise the work is
  treated as unapproved and must be re-approved. The read is an **action** (a
  Datalevin query); the equality test is a **calculation** — a pure integer
  comparison, with **no filesystem read** for Revision identity.
- `both-approved?` requires that **both** reviewers approved the **same Revision
  counter value** for the round (R-8.1, R-8.2, R-16.4).

Because recording an `implementer` or `test-designer` Step outcome advances the
counter in the same ACID transaction, any approval that named an earlier counter
value is stale by a pure integer comparison — even across an interruption or
restart (R-8.4, R-8.5). An authorized correction additionally mints a new
iteration, and the new iteration's counter starts fresh, so a prior approval can
never carry forward.

The filesystem holds the *work product*; Datalevin records the Revision counter
that an approval bound to; the iteration-id threads the pass together. Revision
identity never depends on the filesystem.

---

### State machine

#### States (per slice/iteration)

```
:planning              ; run created, slices recorded, none started
:test-design           ; test-designer dispatched (start of EVERY iteration)
:implement             ; implementer dispatched
:review-correctness    ; correctness-reviewer dispatched (always first, R-7)
:review-structural     ; structural-reviewer dispatched, sees correctness (R-9)
:reconcile             ; reviewers disagree or REQUEST_CHANGES -> plan a correction
:slice-approved        ; both reviewers APPROVE the same Revision (R-16)
:escalated             ; blocked; awaiting human — reached ONLY via [:reconcile :allowance-exhausted] (R-15.4); never via a test conflict
:documenting           ; flow-documenter (deferred; see Open Items)
:done                  ; run complete
:failed-closed         ; a fail-closed condition halted the run
```

There is **no `:repair` state.** A `REQUEST_CHANGES` verdict is reconciled into
an accepted correction plan (R-12), and that acceptance **mints a new iteration
that begins at `:test-design`** (R-16 test-first). Correction is not an in-place
patch step; it is a fresh, fully-traced pass.

`:review-correctness` and `:review-structural` are distinct states enforcing the
fixed order of R-7. There is no concurrent-review state.

RED and GREEN verification are **effects**, not states. The orchestrator's
`:verify-red` effect produces the `:red-verified` (or `:red-invalid`) event and
the `:verify-green` effect produces the `:green` event; those events drive the
transitions below. There are no `:red-verify` / `:green-verify` states.

#### Transition table as data (R-4)

The transition function is a pure calculation driven by a data table:

```clojure
;; transition :: state -> event -> Result
;; Result = {:next-state s :effects [...] :emit [...facts...]}
;;        | {:error {:code ... :message ...}}    ; fail-closed
(def transitions
  {[:test-design      :red-verified]        {:next-state :implement}
   ;; behavior-preserving structural correction: existing coverage already
   ;; demonstrates behavior, so RED is not manufactured (R-16.3).
   [:test-design      :existing-coverage-confirmed] {:next-state :implement}
   [:test-design      :red-invalid]         {:next-state :test-design}   ; retry (R-1.3)
   [:test-design      :production-touched]  {:error {:code :test-designer-wrote-production}} ; R-1.5
   [:implement        :green]               {:next-state :review-correctness}
   [:implement        :test-touched]        {:error {:code :implementer-wrote-test}}         ; R-3.6
   ;; A test conflict is NOT a reconciliation-allowance event and is NOT a
   ;; review-authorized correction: it routes back to :test-design on the
   ;; CURRENT iteration (current trace-id) so the test-designer resolves it.
   ;; It does NOT mint a new Iteration (no :begin-iteration); per the Iteration
   ;; glossary entry only a review-authorized correction mints a new trace-id.
   [:implement        :test-conflict]       {:next-state :test-design
                                             :effects [{:effect/type :route-conflict-to-test-designer}]} ; R-3.3, R-3.4
   [:review-correctness :approve]           {:next-state :review-structural} ; R-7.1
   [:review-correctness :request-changes]   {:next-state :review-structural} ; still run structural, R-7.2
   [:review-correctness :reviewer-edited]   {:error {:code :reviewer-attempted-repair}}      ; R-11.3
   [:review-structural  :both-approve]      {:next-state :slice-approved}    ; R-16 AND-gate
   [:review-structural  :any-request-changes] {:next-state :reconcile}
   [:review-structural  :reviewer-edited]   {:error {:code :reviewer-attempted-repair}}      ; R-11.3
   ;; Reconciliation acceptance does NOT go to a :repair state.
   ;; It mints a NEW iteration that begins at :test-design (R-16.1).
   [:reconcile        :plan-accepted]       {:next-state :test-design
                                             :effects [{:effect/type :begin-iteration}]}
   [:reconcile        :allowance-exhausted] {:next-state :escalated}        ; R-15.4
   ;; ... remaining pairs elided for brevity ...
   })
```

`transition` never performs I/O. It returns the next state plus a **description
of effects** (agent dispatches, verifications, `:begin-iteration`,
`:route-conflict-to-test-designer`) and **facts to persist**. The orchestrator
(an action) interprets that description.

`:escalated` is reached **only** via `[:reconcile :allowance-exhausted]` (R-15.4),
the single event that signals a Disagreement's reconciliation allowance is
exhausted. A **test conflict is never** a path to `:escalated`: it is not a
reconciliation-allowance event. When the `implementer` reports a `:test-conflict`,
the pipeline routes back to `:test-design` on the **current** Iteration (current
trace-id) via the `:route-conflict-to-test-designer` effect so the `test-designer`
resolves it (R-3.3, R-3.4). This stays on the current iteration and mints **no**
new trace-id — distinct from a review-authorized correction, which mints a **new**
Iteration via `:begin-iteration` (`[:reconcile :plan-accepted]`). Per the
Iteration glossary entry, only a review-authorized correction mints a new
Iteration; re-engaging the `test-designer` on a reported test conflict does not.

Illegal `[state event]` pairs, malformed events, and missing data all resolve to
`{:error ...}` and drive `:failed-closed` — approval is never inferred from
silence, timeout, tool failure, missing artifact, or malformed output.

#### Multimethod dispatch on effects

Per the project rule (defmulti + all defmethods in the same namespace), effect
interpretation uses a single multimethod in the orchestrator namespace:

```clojure
(defmulti perform-effect (fn [ctx effect] (:effect/type effect)))
(defmethod perform-effect :dispatch-agent    [ctx e] ...) ; scoped by capability descriptor
(defmethod perform-effect :verify-red        [ctx e] ...) ; -> :red-verified | :red-invalid
(defmethod perform-effect :verify-green      [ctx e] ...) ; -> :green
(defmethod perform-effect :verify-capability [ctx e] ...) ; post-invocation boundary check (fail-closed)
(defmethod perform-effect :begin-iteration   [ctx e] ...) ; new trace at test-design
(defmethod perform-effect :route-conflict-to-test-designer [ctx e] ...) ; R-3.3/R-3.4: route test conflict to test-designer on current iteration (no new trace-id)
(defmethod perform-effect :escalate          [ctx e] ...)
```

`defmulti perform-effect` and **all** its `defmethod`s live in
`workflow.orchestrator`. The multimethod is the boundary where calculations meet
actions.

---

### Namespace layout

```
deps.edn
src/workflow/core.clj          ; pure calculations: transition table, predicates, Revision-counter calcs, capability decisions
src/workflow/store.clj         ; Datalevin: schema, connect, transact helpers, queries, event stream
src/workflow/fs.clj            ; filesystem actions: observe produced changes; R-18 interruption recovery reads
src/workflow/agents.clj        ; AgentInvoker protocol; HermesAgent; KiroAgent; argv calcs; capability descriptors
src/workflow/orchestrator.clj  ; drives the loop; perform-effect multimethod; resume/reconcile; capability enforcement
src/workflow/main.clj          ; entry point / CLI wiring
test/workflow/core_test.clj
test/workflow/store_test.clj
test/workflow/fs_test.clj
test/workflow/agents_test.clj
test/workflow/orchestrator_test.clj
```

There is **no `workflow.git` namespace**. Filesystem reads used to observe
produced changes (for capability verification) and to recover an interrupted
Step's outcome (R-18) live in `workflow.fs` (actions); the Revision-counter
logic is pure integer arithmetic and comparison in `workflow.core`, over a
counter value read from Datalevin. `workflow.fs` never computes Revision
identity.

---

## Data Models

### Datalevin schema (durable state: R-17, R-8, R-15, R-18)

One database directory per installation (path configurable). Every meaningful
fact is its own entity, so history is append-friendly and superseded decisions
are retained rather than overwritten. **State-machine progress is stored as an
append-only stream of immutable transition events; current state is *derived*
from the latest event, not mutated in place.**

#### Transition history as immutable events (R-17)

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

The scalar `:*/state` attributes below are a **materialized convenience** kept in
sync by the same transaction that appends the event; the event stream is the
source of truth and can rebuild them. Nothing overwrites history.

#### Entities

```clojure
(def schema
  {;; --- run / slice / iteration / step identity ---
   :run/id            {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :run/created-at    {:db/valueType :db.type/instant}
   :run/requirements  {:db/valueType :db.type/string}     ; verbatim / stable ref
   :run/state         {:db/valueType :db.type/keyword}    ; materialized
   :run/event-seq     {:db/valueType :db.type/long}       ; last allocated :event/seq

   :slice/id          {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :slice/run         {:db/valueType :db.type/ref}
   :slice/order       {:db/valueType :db.type/long}
   :slice/state       {:db/valueType :db.type/keyword}    ; materialized
   :slice/current-iteration {:db/valueType :db.type/ref}  ; the active trace (correlation)

   :iteration/id      {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :iteration/slice   {:db/valueType :db.type/ref}
   :iteration/number  {:db/valueType :db.type/long}
   :iteration/started-at {:db/valueType :db.type/instant}
   ;; --- Revision: monotonic per-iteration counter (R-8) ---
   ;; The current Revision counter for this Iteration. Starts at 0 when the
   ;; Iteration is minted and is advanced (incremented) IN THE SAME ACID
   ;; transaction that records an implementer/test-designer Step outcome
   ;; (R-8.4, R-17.4). It carries NO file information: it is not a hash, not a
   ;; manifest, not derived from or compared against the filesystem, not git.
   :iteration/revision {:db/valueType :db.type/long}
   ;; When a review round ends without approval, the R-10-compliant finding that
   ;; the reason is carried into the newly minted Iteration (R-8.9). This link
   ;; is set on the new Iteration at :begin-iteration time, referencing the
   ;; finding recorded on the prior round before the trace-id was minted.
   :iteration/seeded-from-finding {:db/valueType :db.type/ref}

   ;; --- agent dispatch step, two-phase lifecycle (R-18) ---
   :step/id           {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :step/iteration    {:db/valueType :db.type/ref}
   :step/role         {:db/valueType :db.type/keyword}    ; :test-designer ...
   :step/backend      {:db/valueType :db.type/keyword}    ; :hermes | :kiro
   :step/model        {:db/valueType :db.type/string}     ; "auto" default
   :step/capability   {:db/valueType :db.type/keyword}    ; :test-authoring | :production-authoring | :read-only
   :step/status       {:db/valueType :db.type/keyword}    ; :dispatched | :complete | :failed
   :step/dispatched-at {:db/valueType :db.type/instant}   ; intent committed BEFORE run (R-18.1)
   :step/outcome-at   {:db/valueType :db.type/instant}    ; committed AFTER return (R-18.1)
   :step/result-ref   {:db/valueType :db.type/string}     ; artifact path / summary

   ;; --- reviews & findings (R-5,R-6,R-9,R-10,R-11) ---
   :review/id         {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :review/iteration  {:db/valueType :db.type/ref}        ; correlation trace
   :review/revision-counter {:db/valueType :db.type/long} ; Revision counter value judged (R-8.1)
   :review/reviewer   {:db/valueType :db.type/keyword}    ; :correctness | :structural
   :review/verdict    {:db/valueType :db.type/keyword}    ; :approve | :request-changes
   :review/inputs-ref {:db/valueType :db.type/string}     ; correctness findings shown to structural (R-9)
   :review/finding    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                                                          ; R-10 findings recorded with this review;
                                                          ; a withhold/REQUEST_CHANGES MUST record at
                                                          ; least one valid finding (R-8.8, R-8.9)

   ;; --- approvals: bound to the Revision counter value in force (R-8.3) ---
   :approval/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :approval/review   {:db/valueType :db.type/ref}
   :approval/reviewer {:db/valueType :db.type/keyword}    ; :correctness | :structural
   :approval/revision-counter {:db/valueType :db.type/long} ; the Revision counter value this approval binds to (R-8.3)
   :approval/stale?   {:db/valueType :db.type/boolean}    ; set when a newer counter value has been recorded (R-8.4)
   :approval/stale-reason {:db/valueType :db.type/keyword}  ; e.g. :revision-advanced — recorded by the
                                                          ; Orchestrator on resume WITHOUT a reviewer
                                                          ; statement when an approval is treated as
                                                          ; unapproved purely due to Revision staleness (R-8.7)
   :approval/at       {:db/valueType :db.type/instant}

   :finding/id        {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :finding/review    {:db/valueType :db.type/ref}
   :finding/owner     {:db/valueType :db.type/keyword}    ; :test-designer|:implementer|:both
   :finding/problem     {:db/valueType :db.type/string}   ; R-10 (1)
   :finding/evidence    {:db/valueType :db.type/string}   ; R-10 (2)
   :finding/justification {:db/valueType :db.type/string} ; R-10 (3)
   :finding/required-outcome {:db/valueType :db.type/string} ; R-10 (4)
   :finding/valid?    {:db/valueType :db.type/boolean}    ; all four present (R-10.2)
   :finding/revision-counter {:db/valueType :db.type/long}  ; Revision counter value the finding was
                                                          ; recorded against (the round's counter)
   :finding/carried-to-iteration {:db/valueType :db.type/ref} ; the newly minted Iteration this finding's
                                                          ; failure information is carried into (R-8.9);
                                                          ; inverse of :iteration/seeded-from-finding

   ;; --- decisions / agreed outcomes (R-12,R-13,R-14,R-17) ---
   :decision/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :decision/iteration {:db/valueType :db.type/ref}
   :decision/slice    {:db/valueType :db.type/ref}        ; scope
   :decision/subject  {:db/valueType :db.type/string}     ; descriptive subject
   :decision/statement {:db/valueType :db.type/string}
   :decision/status   {:db/valueType :db.type/keyword}    ; :binding | :superseded
   :decision/accepted-by {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :decision/supersedes {:db/valueType :db.type/ref}      ; prior decision replaced (R-17.2)
   :decision/new-evidence {:db/valueType :db.type/string} ; required to reconsider (R-14)
   :decision/created-at {:db/valueType :db.type/instant}

   ;; --- repair/correction proposals (R-12) ---
   :proposal/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :proposal/slice    {:db/valueType :db.type/ref}        ; scope
   :proposal/subject  {:db/valueType :db.type/string}
   :proposal/iteration {:db/valueType :db.type/ref}
   :proposal/body     {:db/valueType :db.type/string}
   :proposal/supersedes {:db/valueType :db.type/ref}      ; amended proposals are new (R-12.3)
   :proposal/accepted-by {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :proposal/resolution {:db/valueType :db.type/keyword}  ; :accepted | :rejected | :open

   ;; --- reconciliation allowance, per-disagreement (R-15) ---
   :disagreement/id   {:db/valueType :db.type/uuid :db/unique :db.unique/identity} ; stable identity
   :disagreement/slice {:db/valueType :db.type/ref}       ; descriptive scope attribute
   :disagreement/subject {:db/valueType :db.type/string}  ; descriptive subject attribute
   :disagreement/attempts-used {:db/valueType :db.type/long} ; accepted OR rejected count (R-15.2)
   :disagreement/status {:db/valueType :db.type/keyword}     ; :open | :resolved | :exhausted
   })
```

#### Revision identity is a monotonic per-iteration counter (R-8)

A **Revision** is the identity an approval binds to, modeled as the simplest
thing consistent with R-8 and R-17.4: **a monotonically increasing integer
counter scoped to the current Iteration**, held on `:iteration/revision`. It
starts at `0` when the Iteration is minted and is **advanced (incremented) in the
same ACID Datalevin transaction that records an `implementer` or `test-designer`
Step outcome** (R-8.4, R-17.4). It carries **no file information** — it is not a
hash, not a manifest, not derived from or compared against the filesystem, not
git.

Why the counter lives on the Iteration rather than in a separate `:revision`
entity: the append-only transition-event stream (`:event/*`) already records the
full history of *when* the counter advanced and under which trace, so a separate
`:revision` history entity would be redundant. The current counter value for an
Iteration is the single fact an approval needs to bind to, so a single `long` on
the Iteration is the minimal correct model (R-17.4: one ACID transaction advances
the counter and records the Step outcome together).

- A review records the Revision counter value it judged via
  `:review/revision-counter` (R-8.1) as well as the correlation trace via
  `:review/iteration`.
- An approval records `:approval/revision-counter` (R-8.3). The approval is valid
  only for that counter value.
- On resume, the Orchestrator reads the Iteration's current
  `:iteration/revision` from Datalevin and compares it against the approval's
  `:approval/revision-counter`; an unequal value marks the approval
  `:approval/stale?` (with `:approval/stale-reason :revision-advanced`) and forces
  re-approval (R-8.4, R-8.5, R-8.7). The comparison is a pure integer calculation
  over a Datalevin-read value — **no filesystem read** for Revision identity.

##### Reviewer failure statements are carried into the new Iteration (R-8.6–8.9)

When a review round ends without approval — a reviewer withholds approval or a
`correctness-reviewer`/`structural-reviewer` returns `REQUEST_CHANGES` — the
reviewer states what failed to meet the specifications and why (R-8.6), and the
Orchestrator records that statement as an R-10-compliant `:finding` (problem,
evidence, justification, required-outcome; `:finding/valid?` true) durably in
Datalevin as part of the review's results (R-8.8). The finding is linked to the
review via `:review/finding` and stamped with the round's
`:finding/revision-counter`.

That valid finding MUST be durably recorded **before** `:begin-iteration` mints
the new Iteration and its trace-id (R-8.9). When the new Iteration is minted, the
Orchestrator carries the failure information forward by linking the new Iteration
to the finding — `:iteration/seeded-from-finding` on the new Iteration and
`:finding/carried-to-iteration` on the finding (inverse links) — so the new pass
begins already knowing what failed and why.

When resume treats a prior approval as unapproved purely because the Revision
counter advanced (R-8.5), the staleness is recorded by the Orchestrator itself as
`:approval/stale-reason :revision-advanced` — a non-reviewer fact — without
requiring any reviewer statement (R-8.7).

#### Disagreement identity is the `:disagreement/id` (R-15)

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
  cannot reset (R-15.3), even after a restart.
- Two different slices disagreeing about a same-sounding subject are simply two
  different `:disagreement/id` entities, each with its own allowance. No
  composite key, normalized subject, or `disagreement-key` function exists.

Decisions and proposals are likewise scoped by `:*/slice` so their subjects are
interpreted within a slice.

Notes:

- Decisions are never mutated in place; a replacement is a **new** `:decision`
  entity with `:decision/supersedes` pointing at the old one, whose
  `:decision/status` becomes `:superseded`. Both remain queryable (R-13, R-14,
  R-17).
- Amended repair proposals are new `:proposal` entities linked by
  `:proposal/supersedes`; acceptance does not carry forward (R-12.3, R-12.4).
- Every write is a single `d/transact!` = one durable LMDB commit (R-17.4). The
  transition-event append and any materialized `:*/state` update happen in the
  **same** transaction, so the derived state can never diverge from history.
- Recording an `implementer` or `test-designer` Step outcome and advancing
  `:iteration/revision` (incrementing the counter) happen in the **same** ACID
  transaction, so the Revision counter can never lag behind a recorded Step
  outcome — even if a crash follows immediately (R-8.4, R-17.4).

---

## Components and Interfaces

### Pure core calculations

Namespace `workflow.core` — no I/O, fully unit-testable.

- `(transition state event) -> result` — the transition table lookup, including
  `:test-design :existing-coverage-confirmed -> :implement` and
  `:reconcile :plan-accepted -> :test-design` with a `:begin-iteration` effect
  (no `:repair` state).
- `(finding-valid? finding) -> boolean` — true iff all four R-10 fields
  (problem, evidence, justification, required-outcome) are present and non-blank
  (R-10.2).
- `(advance-revision counter) -> counter'` — **pure** `(inc counter)`: the
  monotonic increment applied to the Iteration's Revision counter when an
  `implementer` or `test-designer` Step outcome is recorded (R-8.4). The
  transaction that persists the incremented value is an action in
  `workflow.store`; the increment itself is a calculation.
- `(approval-valid? approval current-revision-counter) -> boolean` — true iff the
  approval's bound counter value (`:approval/revision-counter`) equals the
  Iteration's current Revision counter; otherwise the approval is stale and
  re-approval is required (R-8.4, R-8.5). On resume this is a **pure integer
  comparison** over a counter value **read from Datalevin** — never a filesystem
  read. **Approval validity is decided by the Revision counter value, not by
  iteration identity and not by any file-derived value.**
- `(both-approved? correctness-approval structural-approval revision-counter) -> boolean`
  — true iff both verdicts are `:approve` **and** both approvals bind to the same
  Revision counter value (`revision-counter`) for the round (R-8.1, R-8.3, R-16.4
  AND-gate).
- `(allowance-remaining disagreement) -> long` — `(max 0 (- 2 attempts-used))`
  (R-15.1). `2` is the explicit allowance: initial proposal + one revision.
- `(consume-attempt disagreement) -> disagreement'` — increments `:attempts-used`
  regardless of accept/reject (R-15.2).
- `(binding-conflict? proposed-outcome binding-decisions) -> maybe-decision` —
  detects a repair that would contradict a binding decision (R-13.2).
- `(reconsideration-admissible? request) -> boolean` — true iff the request
  identifies a specific decision **and** supplies new evidence (R-14.2). Note:
  admissible != authorized; authorization still requires an accepted replacement
  decision through reconciliation (R-14.3).
- `(capability-for role) -> capability` — pure mapping from role to its capability
  descriptor (below). `:test-designer -> :test-authoring`,
  `:implementer -> :production-authoring`, reviewers `-> :read-only`.
- `(capability-violation? capability observed-changes) -> maybe-violation` — pure
  decision that inspects the set of produced file changes (paths created/edited,
  classified as test vs. production) and returns a violation if any change falls
  outside the capability's allowed set (R-1.5, R-3.6, R-11.3). The *observation*
  of produced changes is an action (`workflow.fs`); this classification/decision
  is a calculation.
- `(step-in-doubt? step) -> boolean` — true iff `:step/status = :dispatched` and
  no outcome recorded (R-18.2).
- `(dispatch-eligible? stage trace-facts) -> boolean` — stage-specific
  prerequisite check; e.g. Structural is eligible once Correctness has a recorded
  verdict (APPROVE or REQUEST_CHANGES) for the *current iteration*, and an
  absent/in-doubt result never satisfies a gate (R-7.1, R-18.2). **The
  orchestrator owns all eligibility checks; agents never inspect workflow state to
  decide their own eligibility.**
- `(current-state event-stream target) -> keyword` — derive current state from the
  highest-`:event/seq` transition event, used to rebuild/verify the materialized
  state (R-17.1).

These calculations take plain maps (pulled from Datalevin, or built in tests) and
return plain data/booleans.

### Capability boundaries and fail-closed enforcement (R-1, R-3, R-11)

Role capability boundaries are **symmetric** and enforced **fail-closed** through
a two-part mechanism: a **capability descriptor** passed into the invocation, and
a **post-invocation verification** computed from the file changes the agent
actually produced. The descriptor governs the invocation; the verification is the
enforcement point — decision is a calculation, observation is an action.

**Capability descriptors** (data, one per role) declare what a role may write:

| Role | Descriptor | May write | May NOT write |
|------|-----------|-----------|---------------|
| `test-designer` | `:test-authoring` | test files (new + edits) | ALL production/implementation files — edits AND new-file creation (R-1.4) |
| `implementer` | `:production-authoring` | production/implementation files (new + edits) | ALL test files — edits AND new-file creation; authors NO tests ever, never modifies existing tests (R-3.1, R-3.2, R-3.5) |
| `correctness-reviewer` | `:read-only` | nothing | ANY file edit or repair (R-11.2) |
| `structural-reviewer` | `:read-only` | nothing | ANY file edit or repair (R-11.2) |

The boundaries are **symmetric**: the `test-designer` owns test authorship and is
walled out of production, and the `implementer` owns production authorship and is
walled out of tests. All test authorship — new tests and edits to existing tests
— belongs exclusively to the `test-designer`; the `implementer` authors no tests
ever and never modifies existing tests (R-3.1, R-3.2). When the `implementer`
believes a test is wrong, it stops and reports a `:test-conflict`, which the
orchestrator routes to the `test-designer` (R-3.3, R-3.4) — it does not edit the
test.

The descriptor is stamped on the step (`:step/capability`) and passed to the
`AgentInvoker` so a backend can apply the tightest sandbox flags it supports.
Because a sandbox flag alone is advisory, enforcement does **not** rely on it.

**Post-invocation verification** (the enforcement point):

1. **Observe** (action, `workflow.fs`): after the agent returns, read the set of
   file changes the invocation produced (created + edited paths) from the current
   filesystem state, and classify each path as a test file or a
   production/implementation file.
2. **Decide** (calculation, `workflow.core/capability-violation?`): compare the
   observed, classified changes against the role's capability descriptor.
3. **Enforce** (action, `workflow.orchestrator`): if a violation is detected, the
   orchestrator **fails closed** — it rejects the change and drives the
   appropriate transition:
   - `test-designer` wrote production => `:production-touched` event =>
     `:test-designer-wrote-production` error; the pipeline **remains outside the
     implementation state** (R-1.4, R-1.5).
   - `implementer` wrote/authored/modified a test => `:test-touched` event =>
     `:implementer-wrote-test` error; the change is rejected (R-3.6).
   - a reviewer edited any file or performed a repair => `:reviewer-edited` event
     => `:reviewer-attempted-repair` error; the change is rejected (R-11.3).

Observation is the action; the violation decision is a calculation; the
fail-closed transition is an action driven by the transition table. A violation
never advances the pipeline.

### Actions and edges

Namespaces `workflow.store` (Datalevin), `workflow.fs` (filesystem reads),
`workflow.agents` (protocol + backends), `workflow.orchestrator` (drives the
loop). There is no `workflow.git` namespace — approval identity is the
Revision counter recorded in Datalevin (R-8), not a git value and not a
filesystem-derived value.

#### Filesystem actions (`workflow.fs`)

`workflow.fs` is scoped to **capability observation** and **interruption
recovery** only. It has **no role in Revision identity** — the Revision counter
lives entirely in Datalevin and is never read from or compared against the
filesystem.

- `(observe-changes ctx) -> #{ {:path p :change :created|:edited} ... }` — read
  the file changes a just-returned invocation produced, classified as test vs.
  production paths, for capability verification (R-1.5, R-3.6, R-11.3) and for
  R-18 reconciliation of an in-doubt Step.
- R-18 recovery reads: re-run the relevant verification (RED/GREEN) and inspect
  produced artifacts on disk to recover an interrupted Step's actual outcome
  (R-18.3). These reads recover *what the agent did to the code*; they never
  compute a Revision.

#### Agent-invocation protocol (all roles; hermes + kiro)

```clojure
(defprotocol AgentInvoker
  "Fire off an agent with scoped work, a selected model, and a capability descriptor."
  (invoke [this task]
    "task: {:role kw :iteration-id uuid :capability kw :prompt s :model s
            :cwd path :timeout-ms n :extra {...}}
     returns: {:status :ok|:error :stdout s :stderr s :exit int :raw {...}}"))
```

Model selection: `:model` defaults to `"auto"` (agentic auto). The task carries
`:role` and `:capability` so a backend can add role-appropriate flags and the
tightest available write sandbox. Enforcement of the boundary does **not** trust
that sandbox; it is verified after the fact (see Capability boundaries above).

**Agents do not inspect workflow state.** Eligibility, ordering, identity, and
capability enforcement are the orchestrator's responsibility. The invocation
adapter **retains the iteration/trace association as internal metadata** so it can
label a result and correlate it back to the dispatch, but this is bookkeeping —
not a guard the agent participates in, and there is no requirement that an agent
echo a trace back. The orchestrator correlates a returned result to its dispatch
via the step record it created before spawning, because a one-shot agent process
maps one-to-one to the step that spawned it.

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
  omitted): the orchestrator manages working-copy scope itself, git is not a
  source of truth, and the Revision counter plus iteration-id carry pipeline
  identity, so a separate git worktree per run adds no value and is removed from
  the design.
- Exit code 0 = success; non-zero fails closed.

**KiroAgent** (provisional):

- Same protocol shape (prompt in, result out, model flag, capability descriptor),
  model defaults to `"auto"`.
- The exact CLI invocation is **not yet confirmed**; the implementation is written
  behind the protocol and clearly marked provisional so it can be corrected
  without touching the state machine. A `:kiro` backend is selectable per step via
  `:step/backend`.

Process spawning is an action (`babashka.process` or `clojure.java.shell`).
Command construction (turning a task map into an argv vector) is a
**calculation** — `(hermes-argv task) -> [strings]` and `(kiro-argv task) ->
[strings]` — so argument/model wiring is unit-testable without spawning anything.
`hermes-argv` emits `chat --query-file <path> --oneshot -Q --max-turns <n>` (plus
model/provider and isolation flags) and never emits `--worktree`.

#### Orchestrator loop with two-phase dispatch (R-18)

For each agent step:

1. **Record intent** (durable): transact `:step/status :dispatched`,
   `:step/dispatched-at`, the `:step/iteration` trace, and `:step/capability`.
   Commit *before* spawning (R-18.1). This step record is what the orchestrator
   later uses to correlate the returned result to this dispatch.
2. **Invoke** the agent via `AgentInvoker`, passing the capability descriptor. The
   adapter labels the run with the step's iteration as internal metadata.
3. **Verify capability** (action + calculation): observe produced changes and run
   `capability-violation?`; on violation, fail closed (see above).
4. **Record outcome** (durable): transact `:step/status :complete|:failed`,
   `:step/outcome-at`, `:step/result-ref` against the same step entity (R-18.1).
   The orchestrator correlates the returned result to this dispatch via the step
   record from phase 1 (one-shot process maps one-to-one to its step).

The Revision counter is **not** captured by a separate effect. It is advanced
(incremented via `core/advance-revision`) in the **same** ACID transaction that
records an `implementer` or `test-designer` Step outcome (phase 4 above), so the
counter value in force at review time is simply the Iteration's current
`:iteration/revision`. A review records that value on `:review/revision-counter`
and an approval binds to it on `:approval/revision-counter` (R-8.1, R-8.3). While
a review round is in progress, the Orchestrator dispatches no `implementer` or
`test-designer` Step, so the counter cannot advance mid-round (R-8.2).

##### Review round ending without approval (R-8.6–8.9)

When a reviewer withholds approval or returns `REQUEST_CHANGES`, before
`:begin-iteration` mints the new Iteration and trace-id the Orchestrator MUST have
durably recorded an R-10-compliant `:finding` (`:finding/valid?` true) capturing
what failed and why (R-8.6, R-8.8, R-8.9). `:begin-iteration` then mints the new
Iteration with `:iteration/revision` reset to `0`, links it to that finding
(`:iteration/seeded-from-finding` / `:finding/carried-to-iteration`), and carries
the recorded failure information into the new pass together with the new trace-id.
If no valid finding is recorded, the round cannot mint a new Iteration — the
Orchestrator fails closed rather than proceeding without a justified change
request.

##### Resume (R-8.5, R-8.7)

On resume, the orchestrator queries for steps where `step-in-doubt?` is true
(`:dispatched` with no outcome). Each such step is **reconciled against observable
reality** — re-run the relevant verification (RED/GREEN) and inspect artifacts on
disk to recover its actual outcome — before its outcome is recorded. It is never
assumed complete nor assumed untouched (R-18.2, R-18.3). Where practical, agent
effects are idempotent/verifiable so re-checking is safe (R-18.4); if reality
cannot be determined, the run fails closed (R-18.5). This recovery recovers *what
the agent did to the code*; it is separate from Revision identity and does not
recompute a Revision.

Separately, to decide whether a recorded approval still holds, the Orchestrator
**reads the Iteration's current `:iteration/revision` counter from Datalevin** and
compares it (via `core/approval-valid?`) to the approval's
`:approval/revision-counter` — a pure integer comparison, no filesystem read
(R-8.5). If the counter has advanced (because an `implementer`/`test-designer`
Step outcome was recorded after the approval, including across the interruption),
the approval is marked `:approval/stale?` with `:approval/stale-reason
:revision-advanced`, recorded by the Orchestrator itself without any reviewer
statement (R-8.4, R-8.7), and re-approval is required.

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all
valid executions of a system — essentially, a formal statement about what the
system should do. Properties serve as the bridge between human-readable
specifications and machine-verifiable correctness guarantees.*

These properties are derived from the acceptance-criteria prework (redundant
criteria consolidated). Each is universally quantified and validated by
property-based tests (`org.clojure/test.check`) plus example tests where noted in
the Testing Strategy.

### Property 1: Advancement to implementation requires verified RED for the current iteration

*For any* state and event, a transition into `:implement` occurs only when RED
has been verified (`:red-verified`) or existing coverage has been confirmed
(`:existing-coverage-confirmed`) for the current iteration; an invalid RED
(`:red-invalid`) or any absent/in-doubt result never enters `:implement`, and the
`test-designer` is always dispatched before any implementation work.

**Validates: Requirements 1.1, 1.2, 1.3, 1.6, 16.3**

### Property 2: Any change outside a role's capability fails closed and never advances

*For any* role capability descriptor (`:test-authoring`, `:production-authoring`,
`:read-only`) and *any* set of produced file changes (edits and new-file
creations), `capability-violation?` returns a violation exactly when a change
falls outside that role's allowed set — a `test-designer` touching a
production/implementation file, an `implementer` authoring or modifying any test
file, or a reviewer editing any file — and every such violation drives a
fail-closed transition that rejects the change and does not advance the pipeline
(the `test-designer` violation remains outside `:implement`). The boundaries are
symmetric: test authorship belongs exclusively to the `test-designer` and
production authorship exclusively to the `implementer`.

**Validates: Requirements 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 11.3, 16.6**

### Property 3: Implementation and repair are ineligible without a relevant demonstrating test

*For any* implementation or repair context, `dispatch-eligible?` for `:implement`
is true only when a relevant test that covers the intended behavior (or
demonstrates the defect, for a repair) is recorded; absent such a test,
implementation is refused.

**Validates: Requirements 2.1, 2.2, 2.3, 16.1**

### Property 4: A reported test conflict is routed to the test-designer, never repaired by the implementer

*For any* `:test-conflict` reported by the `implementer`, the orchestrator routes
resolution to the `test-designer` and no test file is modified by the
`implementer`; the implementer leaves the test unchanged.

**Validates: Requirements 3.3, 3.4**

### Property 5: Structural runs only after a recorded correctness verdict, and no repair precedes both reviews

*For any* review round, `dispatch-eligible?` for `:review-structural` is true only
after a correctness verdict (APPROVE or REQUEST_CHANGES) is recorded for the
current iteration — a correctness REQUEST_CHANGES still enables structural — and
no repair (`:begin-iteration`) is authorized before both the correctness and
structural reviews are recorded.

**Validates: Requirements 7.1, 7.2, 7.3**

### Property 6: Approvals bind to the Revision counter value in force, and a recorded Step outcome advances the counter and stales the approval

*For any* Revision counter value, `advance-revision` yields exactly its successor
(`(inc counter)`), so the counter is deterministic and strictly monotonic; an
approval binds to the counter value in force when it is recorded; on resume the
approval is honored (`approval-valid?` true) if and only if its bound counter
value equals the Iteration's current counter read from Datalevin, otherwise it is
stale and re-approval is required (including across an interruption or restart);
`both-approved?` holds only when both reviewers approved the same counter value
for the round; and any `implementer`/`test-designer` Step outcome recorded after
an approval advances the counter and thereby stales that approval.

**Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 16.4, 16.5**

### Property 7: Structural review receives the correctness findings

*For any* recorded correctness findings and reasoning, the structural review's
dispatch input references those findings, so structural recommendations account
for concerns already identified.

**Validates: Requirements 9.1**

### Property 8: A finding is valid only when fully justified

*For any* finding, `finding-valid?` is true if and only if all four fields —
problem, evidence, justification, and required outcome — are present and non-blank;
a finding missing any one is treated as an invalid change request.

**Validates: Requirements 10.1, 10.2**

### Property 9: Repair is authorized only by one unchanged plan accepted by both reviewers

*For any* set of repair proposals, repair is authorized if and only if a single
proposal is accepted by both reviewers; an amended proposal is a new entity
(linked by `:proposal/supersedes`) that both reviewers must accept afresh, and the
superseded plan's acceptances do not carry forward to the amended plan.

**Validates: Requirements 12.1, 12.2, 12.3, 12.4**

### Property 10: Agreed outcomes stay binding until an accepted replacement supersedes them

*For any* previously agreed outcome, the decision remains `:binding` until a
replacement decision is accepted through reconciliation; any repair that would
contradict a binding decision is rejected until the outcome is formally replaced;
and every subsequent repair is checked against the set of binding decisions.

**Validates: Requirements 13.1, 13.2, 13.3, 14.3**

### Property 11: Reconsideration requires a specific decision and new evidence

*For any* reconsideration request, `reconsideration-admissible?` is true if and
only if the request identifies a specific decision and supplies new evidence; a
request lacking either is inadmissible. Admissibility permits reconsideration but
does not by itself authorize change — the prior decision stays binding until a
replacement is accepted.

**Validates: Requirements 14.1, 14.2**

### Property 12: Reconciliation allowance is monotonic, bounded, and survives restart

*For any* Disagreement (identified by its stable `:disagreement/id`),
`allowance-remaining` equals `(max 0 (- 2 attempts-used))` and never exceeds two;
`attempts-used` only ever increases and counts a rejected proposal identically to
an accepted one; the count is preserved without reset when the Disagreement is
reopened, including across an interruption or restart; when the allowance is
exhausted the orchestrator escalates the Disagreement; and each consumption is a
durable fact keyed to the Disagreement's identity.

**Validates: Requirements 15.1, 15.2, 15.3, 15.4, 15.5**

### Property 13: History is complete, durable, and every decision is binding or explicitly superseded

*For any* recorded fact (progress, findings, justifications, decisions,
resolutions, accepted decisions), the fact survives closing and reopening the
Datalevin store; each persisted decision is either `:binding` or has a superseding
decision linked by `:decision/supersedes`, with both the replacement and the
superseded decision remaining queryable; and the current state derived from the
highest-`:event/seq` transition event always equals the materialized `:*/state`
because the event append and the materialized update occur in the same ACID
transaction, so a halted run resumes exactly where it left off; and even as
approvals may become stale, the history of accepted decisions is preserved as
queryable facts in Datalevin.

**Validates: Requirements 8.10, 17.1, 17.2, 17.3, 17.4**

### Property 14: Two-phase dispatch makes an interrupted step recoverable and fails closed when reality is indeterminate

*For any* agent step, the dispatch intent is persisted durably before the agent
runs and the outcome is persisted durably only after the agent returns; a step
found dispatched with no recorded outcome is `step-in-doubt?` and is treated as
neither complete nor untouched; and if observable reality cannot be determined
during reconciliation, the orchestrator fails closed rather than advancing.

**Validates: Requirements 18.1, 18.2, 18.5**

### Property 15: A review round ending without approval records a justified finding, carries it forward, and records resume-staleness without a reviewer

*For any* review round that ends without approval (a reviewer withholds approval
or a `correctness-reviewer`/`structural-reviewer` returns `REQUEST_CHANGES`), an
R-10-compliant finding (`finding-valid?` true) is durably recorded in Datalevin
before a new Iteration and trace-id are minted, and the newly minted Iteration is
linked to that finding (`:iteration/seeded-from-finding` /
`:finding/carried-to-iteration`) so the recorded failure information is carried
into the new pass together with the new trace-id; and *for any* approval treated
as unapproved on resume purely because the Revision counter advanced, the
Orchestrator records the staleness reason itself (`:approval/stale-reason
:revision-advanced`) without requiring any reviewer statement.

**Validates: Requirements 8.6, 8.7, 8.8, 8.9**

---

### Per-requirement enforcement mapping

| Req | Enforcement point |
|-----|-------------------|
| R-1 | `:test-design -> :implement` only on `:red-verified`/`:existing-coverage-confirmed`; `:red-invalid` retries; production write during test-design => `:production-touched` error (Property 1, 2). |
| R-2 | `dispatch-eligible?` for `:implement` requires a recorded relevant test (Property 3). |
| R-3 | `:production-authoring` capability excludes ALL test writes (edits + new); violation => `:test-touched` error; `:test-conflict` routes to test-designer (Property 2, 4). |
| R-4 | ACD structure of the code itself (`core` is pure, no I/O); confirmed by structural review (not PBT). |
| R-5 | `:review-correctness` dispatched read-only with requirements/tests/behavior inputs (example test; reviewer judgment is manual). |
| R-6 | `:review-structural` dispatched read-only over organization/duplication/complexity (example test; reviewer judgment is manual). |
| R-7 | Ordered states; `dispatch-eligible?` requires a recorded correctness verdict before structural; no repair before both reviews (Property 5). |
| R-8 | Monotonic per-iteration Revision counter (`:iteration/revision`); `:review/revision-counter`, `:approval/revision-counter`; `advance-revision` on each implementer/test-designer Step outcome; `approval-valid?` compares counter values read from Datalevin (no filesystem); `both-approved?` on the same counter value; a review round ending without approval records an R-10 finding carried into the new Iteration (`:iteration/seeded-from-finding`), and resume-staleness is recorded by the Orchestrator via `:approval/stale-reason` without a reviewer statement (Property 6, 15). |
| R-9 | `:review/inputs-ref` feeds correctness findings into the structural dispatch (Property 7). |
| R-10 | `finding-valid?`; invalid findings are not actionable change requests (Property 8). |
| R-11 | `:read-only` capability; any reviewer change => `:reviewer-edited` error (Property 2). |
| R-12 | Repair authorized iff one proposal has both reviewers in `:proposal/accepted-by`; amendments are new proposals; acceptances do not carry forward (Property 9). |
| R-13 | `binding-conflict?` blocks repairs contradicting binding decisions; decisions binding until superseded (Property 10). |
| R-14 | `reconsideration-admissible?` gate; prior stays binding until an accepted replacement (Property 10, 11). |
| R-15 | `:disagreement/id` stable identity; `allowance-remaining`/`consume-attempt`; exhaustion => `:escalated`; durable per-id consumption (Property 12). |
| R-16 | Correction is a new iteration at `:test-design`; `:existing-coverage-confirmed` for behavior-preserving refactor; re-verify GREEN; the new iteration's counter starts fresh so both must re-approve the new Revision counter value (Property 1, 2, 3, 6). |
| R-17 | Append-only transition events + append-only decision/finding history; supersede via refs; single ACID transaction per fact (incl. counter advance with Step outcome); derived == materialized; accepted-decision history preserved and queryable even as approvals go stale, R-8.10 (Property 13). |
| R-18 | Two-phase step lifecycle durably committed; `step-in-doubt?`; reconcile against observable reality (filesystem/tests/artifacts) to recover the interrupted Step's outcome — separate from Revision identity, which is a Datalevin counter comparison; fail closed if indeterminate (Property 14). |

---

## Error Handling

Fail-closed is the default: illegal `[state event]` pairs, malformed events,
missing data, silence, timeouts, tool failures, missing artifacts, and
indeterminate reality all resolve to an error result that drives `:failed-closed`
or `:escalated` rather than an inferred approval. The following subsection details
how pipeline errors (an expected `REQUEST_CHANGES` verdict) differ from process
interruptions (a bare crash with no verdict), how dispatch eligibility is decided,
and where truth lives.

#### Pipeline errors vs. process interruptions, dispatch eligibility, and authority

`trace-id` is another name for `iteration-id`: a trace is the correlation key for
one pass through the loop. The following four distinctions govern how the
orchestrator reacts to review outcomes, interruptions, dispatch gating, and where
truth lives. They refine — and do not alter — the requirements.

**(a) A `REQUEST_CHANGES` verdict is a pipeline error, handled on the current
trace before a correction trace is minted.**

A reviewer returning `REQUEST_CHANGES` is a *pipeline error* — an expected,
recorded workflow outcome, not a crash. When it occurs the orchestrator:

1. **records the verdict on the current trace** (the current `iteration-id`);
2. **completes the remaining review required by R-7** — Structural still runs
   after Correctness even when Correctness returned `REQUEST_CHANGES` (the
   `:review-correctness :request-changes -> :review-structural` edge);
3. **reconciles the findings** and obtains **both reviewers' acceptance of one
   repair plan** (R-12);
4. **only then mints a new trace** (a new `iteration-id`) and **begins the
   correction iteration at the `test-designer`** (R-16 test-first). There is no
   in-place `:repair` step: acceptance fires `:begin-iteration`.

So the correction iteration is a *new* trace, but the review completion and
reconciliation that authorize it all happen on the *current* trace first.

**(b) A process interruption is NOT a pipeline error and yields no review
outcome.**

If the orchestrator (or an agent) simply dies, that is not a verdict and produces
no `APPROVE`/`REQUEST_CHANGES`. On resume the orchestrator:

- **retains the same trace** (`iteration-id`) — it does not mint a correction
  trace merely because a process died;
- **reconciles or resumes the interrupted stage** using the two-phase step
  lifecycle (R-18): an in-doubt step is reconciled against observable reality
  (re-run RED/GREEN, inspect artifacts) to recover its outcome, then its outcome
  is recorded, and the stage continues. Separately, whether a recorded approval
  still holds is decided by reading the Iteration's Revision counter from
  Datalevin and comparing it to the approval's bound counter value (R-8.5) — a
  Datalevin read, not a filesystem recompute;
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
expressed as a calculation over recorded facts — not by requiring every preceding
result to be *successful*:

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

**(d) Authority split: three sources of truth, no git.**

The three sources of truth are the **current filesystem state**, the
**Orchestrator state machine**, and **Datalevin**. Git is not among them.

- **Datalevin is authoritative for workflow state, pipeline identity, Revision
  identity, approval validity, and dispatch eligibility** — runs, slices,
  iterations/traces, the transition-event stream, step lifecycle, reviews,
  verdicts, findings, decisions, proposals, disagreement allowances, and the
  Revision counter (`:iteration/revision`) plus the counter values reviews and
  approvals bound to. Approval validity is a Datalevin-read integer comparison
  (`approval-valid?`). Eligibility (case (c)) is decided from Datalevin facts.
- **The filesystem is authoritative for the work product only.** It is read for
  two purposes and neither is a Revision computation: (a) capability
  post-invocation verification — observe the changes an agent produced and
  classify them (R-1.5, R-3.6, R-11.3); and (b) R-18 reconciliation of an in-doubt
  step — re-run RED/GREEN and inspect produced artifacts to recover what actually
  happened, then record the reconciled outcome as Datalevin facts. The Revision
  counter is never derived from or compared against the filesystem.

The two never compete: Datalevin answers "where is the pipeline, which iteration
are we in, what did an approval bind to (which counter value), and what may run
next?"; the filesystem answers "what did the agent actually do to the code?".
Approval validity is decided by comparing the approval's bound Revision counter
value against the Iteration's current Revision counter, both read from Datalevin.

---

## Testing Strategy

### Test-first, dogfooding the workflow

Tests are written and shown RED before implementation (R-1 discipline applied to
this project itself). Correctness properties are implemented with
`org.clojure/test.check`; each property test runs a **minimum of 100 iterations**
and is tagged with a comment referencing its design property:
**`Feature: orchestrator-state-machine, Property {number}: {property text}`**.
Each correctness property is implemented by a **single** property-based test.
Unit/example tests cover concrete examples, integration points, and edge cases;
reviewer-judgment criteria (R-4 clarity, R-5.2, R-6.2) are exercised by example
tests and confirmed by the reviewers themselves, not by PBT.

- **core_test** — transition table correctness (incl.
  `:test-design :existing-coverage-confirmed -> :implement`,
  `:reconcile :plan-accepted -> :test-design` with `:begin-iteration`, and the
  absence of any `:repair`, `:red-verify`, or `:green-verify` state);
  `finding-valid?`; `advance-revision` increments the counter (strictly
  monotonic); `approval-valid?` true on an equal counter value and false on an
  unequal one; `both-approved?` on a shared counter value;
  `allowance-remaining`/`consume-attempt` counting rejected
  attempts; `binding-conflict?`; `reconsideration-admissible?`;
  `capability-for`/`capability-violation?` for all three descriptors;
  `step-in-doubt?`; `dispatch-eligible?` per stage (incl. Structural eligible
  after a Correctness REQUEST_CHANGES, and an absent result never eligible);
  `current-state` derived from the event stream. Pure, fast, no I/O. Hosts
  Properties 1–12 and 14's pure clauses.
- **fs_test** — `observe-changes` classifies test vs. production paths correctly
  for capability verification (Property 2); R-18 recovery reads (re-run RED/GREEN,
  inspect artifacts) recover an interrupted step's outcome. Uses a temp directory,
  cleaned up after. There is no Revision computation here — Revision identity is a
  Datalevin counter, not a filesystem-derived value.
- **store_test** — schema round-trip; durable resume (open, write, close, reopen,
  read); append-only transition events reconstruct current state (R-17);
  per-disagreement allowance survives reopen and does not reset (R-15);
  same-subject concerns in two slices keep independent `:disagreement/id` entities
  and allowances; superseded decision retained and queryable (R-17); the Revision
  counter (`:iteration/revision`) and recorded `:approval/revision-counter` values
  survive reopen (Property 12, 13). Uses a temp Datalevin dir, cleaned up after.
- **agents_test** — `hermes-argv`/`kiro-argv` produce correct argv incl. default
  `auto`, the `chat --query-file <path> --oneshot -Q --max-turns <n>` form, the
  capability-derived isolation flags, and **never include `--worktree`**;
  `AgentInvoker` via a fake/record backend (no real process spawning in tests);
  protocol satisfied by both backends.
- **orchestrator_test** — full slice happy path; correctness-before-structural
  ordering (R-7, Property 5); AND-gate on the same Revision counter value (R-16,
  Property 6); capability violations fail closed for each role (Property 2);
  reconcile exhaustion -> escalate with per-disagreement allowance (R-15, Property
  12); two-phase dispatch leaves a recoverable in-doubt step that resume
  reconciles against the filesystem (re-run RED/GREEN, inspect artifacts) (R-18,
  Property 14); a `REQUEST_CHANGES` completes the remaining review then mints a
  **new iteration at test-design** — never an in-place repair; a bare interruption
  resumes on the same trace without restarting at test-design; a stale approval is
  invalid because a later `implementer`/`test-designer` Step outcome advanced the
  Iteration's Revision counter beyond the approval's bound value (R-8, Property 6);
  a `REQUEST_CHANGES`/withheld approval requires an R-10-compliant finding to be
  durably recorded before the new iteration is minted and carries that failure
  information into the new iteration (R-8.6–8.9, Property 15); and resume-staleness
  records `:approval/stale-reason :revision-advanced` without any reviewer
  statement (R-8.7, Property 15). Uses a fake `AgentInvoker` and a temp store.

**Dual testing approach**: property tests verify universal correctness across
generated inputs; unit/example tests verify specific examples, integration points,
and edge cases (e.g. R-16.2 existing-failing-test evidence; R-18.3 reconcile
sequencing; R-5/R-6 read-only reviewer dispatch). Avoid over-writing unit tests
where a property already covers the input space.

---

## Dependencies

### deps.edn

- `org.clojure/clojure`
- `datalevin/datalevin` (LMDB-backed durable Datalog).
- `babashka/process` (or `clojure.java.shell`) for spawning agent CLIs.
- test: `org.clojure/test.check` for property-based tests.

Datalevin requires specific JVM `--add-opens`/`--enable-native-access` flags;
these go in an `:aliases`/`:jvm-opts` entry in `deps.edn` and are validated when
the store tests first run. There is no git dependency — the implementation never
shells out to git.

---

## Open Items to Confirm During Implementation

1. **Kiro CLI invocation** is provisional; confirm the real command/flags and
   update `kiro-argv` only (state machine untouched).
2. **Datalevin version pin** and exact JVM `--add-opens` flags — validate on
   first `store_test` run.
3. **`flow-documenter`** is **out of scope** for this spec (the `:documenting`
   state and README's final documentation step are **deferred**); it is noted only
   so the state machine leaves room for it as a later final step. `README.org`
   remains a reference type and MUST NOT be modified.
