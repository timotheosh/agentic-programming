# Implementation Plan: Multi-Agent Development Workflow State Machine

## Overview

This plan implements the design in
`.kiro/specs/orchestrator-state-machine/design.md` (R-1 .. R-18) in Clojure,
building **bottom-up and test-first** with Actions/Calculations/Data separation:

1. Project scaffolding + `deps.edn` (with Datalevin JVM flags).
2. Pure core calculations in `workflow.core` — each design Correctness Property
   encoded as a **failing (RED) property-based test first**, then implemented to
   GREEN.
3. The Datalevin durable store (`workflow.store`) — schema, single-ACID-transaction
   transact helpers, queries, append-only event stream, durable-resume tests.
4. Filesystem observation + R-18 recovery reads (`workflow.fs`).
5. Agent-invocation protocol + argv calculations + fake backend (`workflow.agents`).
6. The orchestrator (`workflow.orchestrator`) — `perform-effect` multimethod, the
   drive loop, two-phase dispatch, capability enforcement, resume/reconcile, the
   R-8.6–8.9 finding-carry, and escalation.
7. Entry point / CLI wiring (`workflow.main`).
8. End-to-end orchestrator tests.

Each property-based test uses `org.clojure/test.check`, runs a **minimum of 100
iterations**, implements a **single** design property, and is tagged with a
comment:
`Feature: orchestrator-state-machine, Property {number}: {property text}`.

Revision is a **monotonic per-iteration counter** (`:iteration/revision`, a
`long`), never a hash/manifest/filesystem-derived value. There is no `:repair`
state and no `workflow.git` namespace. `README.org` is a reference type and MUST
NOT be modified.

## Tasks

- [x] 1. Project scaffolding and dependencies
  - Create the `src/workflow/` and `test/workflow/` directory structure and empty
    namespace stubs matching the design layout (`core`, `store`, `fs`, `agents`,
    `orchestrator`, `main`; and test namespaces `core_test`, `store_test`,
    `fs_test`, `agents_test`, `orchestrator_test`).
  - Author `deps.edn` with `org.clojure/clojure`, `datalevin/datalevin`,
    `babashka/process` (or `clojure.java.shell`), and a `:test` alias with
    `org.clojure/test.check`.
  - Add the Datalevin-required JVM options (`--add-opens` / `--enable-native-access`)
    to an `:aliases`/`:jvm-opts` entry so the store tests can run; add no git
    dependency (the implementation never shells out to git).
  - _Requirements: 4.1, 4.2, 17.4_

- [x] 2. Pure core calculations — state machine and identity (`workflow.core`)
  - [x]* 2.1 Write property test for advancement into `:implement`
    - **Property 1: Advancement to implementation requires verified RED for the current iteration**
    - Encode as a RED property-based test first: for any `[state event]`,
      `transition` enters `:implement` only on `:red-verified` or
      `:existing-coverage-confirmed`; `:red-invalid` retries at `:test-design`;
      absent/in-doubt results never enter `:implement`; test-designer precedes
      implementation.
    - Assert the transition table contains no `:repair`, `:red-verify`, or
      `:green-verify` state, and that `:reconcile :plan-accepted -> :test-design`
      carries a `:begin-iteration` effect.
    - Tag: `Feature: orchestrator-state-machine, Property 1`
    - **Validates: Requirements 1.1, 1.2, 1.3, 1.6, 16.3**
  - [x] 2.2 Implement `transition` as a pure data-table lookup
    - Encode the transition table from the design (no `:repair` state); include
      `:test-design :red-verified -> :implement`,
      `:test-design :existing-coverage-confirmed -> :implement`,
      `:test-design :red-invalid -> :test-design`,
      `:test-design :production-touched -> {:error :test-designer-wrote-production}`,
      `:implement :green -> :review-correctness`,
      `:implement :test-touched -> {:error :implementer-wrote-test}`,
      `:implement :test-conflict -> :test-design` with `:route-conflict-to-test-designer`,
      `:review-correctness :approve/:request-changes -> :review-structural`,
      `:review-correctness/:review-structural :reviewer-edited -> {:error :reviewer-attempted-repair}`,
      `:review-structural :both-approve -> :slice-approved`,
      `:review-structural :any-request-changes -> :reconcile`,
      `:reconcile :plan-accepted -> :test-design` with `:begin-iteration`,
      `:reconcile :allowance-exhausted -> :escalated`.
    - Illegal `[state event]` pairs, malformed events, and missing data resolve to
      `{:error ...}` (fail-closed); `transition` performs no I/O.
    - _Requirements: 1.1, 1.2, 1.3, 1.6, 3.3, 3.4, 3.6, 7.1, 7.2, 11.3, 15.4, 16.3_
  - [x] 2.3 Implement Revision-counter calculations (`advance-revision`, `approval-valid?`, `both-approved?`)
    - `advance-revision` = pure `(inc counter)`; `approval-valid?` = equality of
      the approval's bound counter value with the Iteration's current counter;
      `both-approved?` = both verdicts `:approve` AND both bound to the same
      counter value. No filesystem reads.
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 16.4, 16.5_
  - [x]* 2.4 Write property test for the Revision counter and approval binding
    - **Property 6: Approvals bind to the Revision counter value in force, and a recorded Step outcome advances the counter and stales the approval**
    - RED first: `advance-revision` yields exactly `(inc counter)` (strictly
      monotonic); `approval-valid?` true iff bound counter == current counter;
      `both-approved?` only when both approve the same counter value; a later
      Step-outcome advance stales a prior approval.
    - Tag: `Feature: orchestrator-state-machine, Property 6`
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 16.4, 16.5**
  - [x] 2.5 Implement `finding-valid?`
    - True iff all four R-10 fields (problem, evidence, justification,
      required-outcome) are present and non-blank.
    - _Requirements: 10.1, 10.2_
  - [x]* 2.6 Write property test for finding validity
    - **Property 8: A finding is valid only when fully justified**
    - RED first: `finding-valid?` true iff all four fields present and non-blank;
      missing any one is invalid.
    - Tag: `Feature: orchestrator-state-machine, Property 8`
    - **Validates: Requirements 10.1, 10.2**
  - [x] 2.7 Implement reconciliation-allowance calculations (`allowance-remaining`, `consume-attempt`)
    - `allowance-remaining` = `(max 0 (- 2 attempts-used))`; `consume-attempt`
      increments `:attempts-used` regardless of accept/reject.
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_
  - [x]* 2.8 Write property test for reconciliation allowance
    - **Property 12: Reconciliation allowance is monotonic, bounded, and survives restart** (pure clauses)
    - RED first: `allowance-remaining` == `(max 0 (- 2 attempts-used))` and never
      exceeds 2; `attempts-used` only increases and counts rejected == accepted.
      (Restart/durability clause is covered in `store_test`, task 4.7.)
    - Tag: `Feature: orchestrator-state-machine, Property 12`
    - **Validates: Requirements 15.1, 15.2**
  - [x] 2.9 Implement decision/reconsideration calculations (`binding-conflict?`, `reconsideration-admissible?`)
    - `binding-conflict?` detects a proposed outcome that contradicts a binding
      decision; `reconsideration-admissible?` true iff request identifies a
      specific decision AND supplies new evidence (admissible != authorized).
    - _Requirements: 13.1, 13.2, 13.3, 14.1, 14.2, 14.3_
  - [x]* 2.10 Write property test for binding decisions
    - **Property 10: Agreed outcomes stay binding until an accepted replacement supersedes them** (pure clauses)
    - RED first: any repair contradicting a binding decision is flagged by
      `binding-conflict?`; a decision stays binding until superseded.
    - Tag: `Feature: orchestrator-state-machine, Property 10`
    - **Validates: Requirements 13.1, 13.2, 13.3, 14.3**
  - [x]* 2.11 Write property test for reconsideration admissibility
    - **Property 11: Reconsideration requires a specific decision and new evidence**
    - RED first: `reconsideration-admissible?` true iff both a specific decision
      and new evidence are present; lacking either is inadmissible.
    - Tag: `Feature: orchestrator-state-machine, Property 11`
    - **Validates: Requirements 14.1, 14.2**
  - [x] 2.12 Implement capability calculations (`capability-for`, `capability-violation?`)
    - `capability-for`: `:test-designer -> :test-authoring`,
      `:implementer -> :production-authoring`, reviewers `-> :read-only`.
    - `capability-violation?`: pure decision over classified produced changes
      (test vs. production paths) that returns a violation when a change falls
      outside the role's allowed set.
    - _Requirements: 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 11.3, 16.6_
  - [x] 2.13 Write property test for capability enforcement
    - **Property 2: Any change outside a role's capability fails closed and never advances** (pure clauses)
    - RED first: `capability-violation?` flags exactly the out-of-boundary changes
      for each descriptor (test-designer touching production, implementer
      authoring/modifying a test, reviewer editing any file); boundaries symmetric.
    - Tag: `Feature: orchestrator-state-machine, Property 2`
    - **Validates: Requirements 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 16.6**
  - [x] 2.14 Implement dispatch-eligibility calculations (`dispatch-eligible?`, `step-in-doubt?`, `current-state`)
    - `dispatch-eligible?`: stage-specific prerequisite check over recorded facts
      (Structural eligible once Correctness has a recorded APPROVE or
      REQUEST_CHANGES verdict for the current iteration; absent/in-doubt never
      satisfies a gate; `:implement` requires a recorded relevant test).
    - `step-in-doubt?`: true iff `:step/status = :dispatched` with no outcome.
    - `current-state`: derive from the highest-`:event/seq` transition event.
    - _Requirements: 2.1, 2.2, 2.3, 7.1, 7.2, 7.3, 16.1, 17.1, 18.2_
  - [ ]* 2.15 Write property test for implementation/repair eligibility
    - **Property 3: Implementation and repair are ineligible without a relevant demonstrating test**
    - RED first: `dispatch-eligible?` for `:implement` true only when a relevant
      test (or defect-demonstrating test, for repair) is recorded.
    - Tag: `Feature: orchestrator-state-machine, Property 3`
    - **Validates: Requirements 2.1, 2.2, 2.3, 16.1**
  - [ ]* 2.16 Write property test for review ordering eligibility
    - **Property 5: Structural runs only after a recorded correctness verdict, and no repair precedes both reviews**
    - RED first: `dispatch-eligible?` for `:review-structural` true only after a
      correctness verdict recorded for the current iteration (REQUEST_CHANGES also
      enables structural); no `:begin-iteration` authorized before both reviews.
    - Tag: `Feature: orchestrator-state-machine, Property 5`
    - **Validates: Requirements 7.1, 7.2, 7.3**
  - [x]* 2.17 Write property test for test-conflict routing
    - **Property 4: A reported test conflict is routed to the test-designer, never repaired by the implementer**
    - RED first: `transition` for `:implement :test-conflict` routes to
      `:test-design` with `:route-conflict-to-test-designer` and mints no new
      iteration; the implementer modifies no test.
    - Tag: `Feature: orchestrator-state-machine, Property 4`
    - **Validates: Requirements 3.3, 3.4**
  - [ ]* 2.18 Write unit/example tests for reviewer-judgment criteria
    - Example tests exercising R-4 clarity intent, R-5.2 (passing tests concealing
      a violated requirement is flagged), and R-6.2 (concealed avoidable
      complexity is flagged) as concrete examples; reviewer judgment itself is
      manual, not PBT.
    - _Requirements: 4.1, 4.2, 4.3, 5.2, 6.2_

- [x] 3. Checkpoint — pure core green
  - Ensure all `core_test` tests pass, ask the user if questions arise.

- [x] 4. Datalevin durable store (`workflow.store`)
  - [x] 4.1 Define the Datalevin schema and connect/close helpers
    - Encode the full schema from the design (run/slice/iteration/step, events,
      reviews/approvals/findings, decisions/proposals, disagreements), including
      `:iteration/revision` (long), `:iteration/seeded-from-finding`,
      `:finding/carried-to-iteration`, `:approval/revision-counter`,
      `:approval/stale?`, `:approval/stale-reason`, `:review/revision-counter`,
      `:disagreement/id`/`:attempts-used`.
    - _Requirements: 17.1, 17.2, 17.3, 17.4_
  - [x] 4.2 Implement single-ACID-transaction transact helpers
    - Each meaningful fact is one `d/transact!` (one LMDB commit); the
      transition-event append and any materialized `:*/state` update happen in the
      same transaction; recording an implementer/test-designer Step outcome and
      advancing `:iteration/revision` (via `core/advance-revision`) happen in the
      same transaction.
    - _Requirements: 8.4, 17.3, 17.4_
  - [x] 4.3 Implement the append-only transition-event stream and queries
    - Allocate monotonic `:event/seq`; append one immutable event entity per
      transition; provide queries to read the event stream and rebuild
      `current-state` (via `core/current-state`); keep materialized `:*/state` in
      sync in the same transaction.
    - _Requirements: 17.1, 17.3_
  - [x] 4.4 Implement queries for reviews, approvals, findings, decisions, proposals, and disagreements
    - Read the current `:iteration/revision`; read approvals with their bound
      counter value; locate an existing `:disagreement/id` by slice+subject
      descriptively (no normalized key); read binding vs. superseded decisions.
    - _Requirements: 8.5, 8.10, 12.4, 13.3, 15.3, 17.2_
  - [x]* 4.5 Write store round-trip and durable-resume tests
    - Open, write, close, reopen, read: schema round-trip; append-only events
      reconstruct current state; materialized `:*/state` equals the state derived
      from the highest-`:event/seq` event.
    - Uses a temp Datalevin dir, cleaned up after.
    - _Requirements: 17.1, 17.3, 17.4_
  - [x] 4.6 Write property test for durable, complete history
    - **Property 13: History is complete, durable, and every decision is binding or explicitly superseded**
    - RED first: recorded facts survive close/reopen; each decision is `:binding`
      or has a `:decision/supersedes` link (both queryable); derived state ==
      materialized state; accepted-decision history preserved even as approvals go
      stale.
    - Tag: `Feature: orchestrator-state-machine, Property 13`
    - **Validates: Requirements 8.10, 17.1, 17.2, 17.3, 17.4**
  - [ ]* 4.7 Write property test for allowance/counter durability across restart
    - **Property 12: Reconciliation allowance is monotonic, bounded, and survives restart** (durable clauses)
    - RED first: per-`:disagreement/id` `:attempts-used` survives reopen without
      reset (incl. reviewers reversing an earlier agreement); same-subject concerns
      in two slices keep independent `:disagreement/id` allowances; the
      `:iteration/revision` counter and recorded `:approval/revision-counter` values
      survive reopen.
    - Tag: `Feature: orchestrator-state-machine, Property 12`
    - **Validates: Requirements 15.3, 15.5**

- [x] 5. Filesystem observation and R-18 recovery reads (`workflow.fs`)
  - [x] 5.1 Implement `observe-changes`
    - Read the file changes a just-returned invocation produced (created + edited
      paths) from the current filesystem state and classify each path as test vs.
      production/implementation, for capability verification and R-18
      reconciliation. Compute NO Revision here.
    - _Requirements: 1.5, 3.6, 11.3, 18.3_
  - [x] 5.2 Implement R-18 recovery reads
    - Re-run the relevant verification (RED/GREEN) and inspect produced artifacts
      on disk to recover an interrupted Step's actual outcome; these reads recover
      what the agent did to the code and never compute a Revision.
    - _Requirements: 18.3, 18.4_
  - [x]* 5.3 Write `fs_test` for observation and recovery
    - `observe-changes` classifies test vs. production paths correctly (supports
      Property 2); R-18 recovery reads recover an interrupted step's outcome
      (re-run RED/GREEN, inspect artifacts); assert no Revision is computed from
      the filesystem. Uses a temp directory, cleaned up after.
    - _Requirements: 1.5, 3.6, 11.3, 18.3, 18.4_

- [x] 6. Agent-invocation protocol and argv calculations (`workflow.agents`)
  - [x] 6.1 Define the `AgentInvoker` defprotocol and capability descriptors
    - `(invoke [this task])` per the design task/result shape; capability
      descriptor data (`:test-authoring`, `:production-authoring`, `:read-only`)
      declaring may/may-not-write sets.
    - _Requirements: 1.4, 3.5, 11.2_
  - [x] 6.2 Implement `hermes-argv` and `kiro-argv` pure calculations
    - `hermes-argv` emits `chat --query-file <path> --oneshot -Q --max-turns <n>`
      plus `--model <model>`/`--provider auto` (default `"auto"`) and isolation
      flags `--ignore-user-config`, `--ignore-rules`, and NEVER `--worktree`.
    - `kiro-argv` provisional behind the protocol, model default `"auto"`, clearly
      marked provisional so it can be corrected without touching the state machine.
    - _Requirements: 4.1_
  - [x] 6.3 Implement `HermesAgent`, provisional `KiroAgent`, and process spawning action
    - `HermesAgent` (confirmed) and `KiroAgent` (provisional) satisfy
      `AgentInvoker`; command construction uses the argv calcs; process spawning is
      an action (`babashka.process` or `clojure.java.shell`); exit 0 = success,
      non-zero fails closed.
    - _Requirements: 4.1, 4.2_
  - [x] 6.4 Implement a fake/record `AgentInvoker` backend for tests
    - Deterministic in-memory backend returning scripted results (no real process
      spawning), used by `agents_test` and `orchestrator_test`.
    - _Requirements: 4.2_
  - [x]* 6.5 Write `agents_test` for argv and protocol
    - `hermes-argv`/`kiro-argv` produce correct argv incl. default `auto`, the
      `chat --query-file <path> --oneshot -Q --max-turns <n>` form, the
      capability-derived isolation flags, and NEVER `--worktree`; protocol
      satisfied by both backends via the fake backend.
    - _Requirements: 4.1, 4.2_

- [x] 7. Checkpoint — store, fs, agents green
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Orchestrator effects and drive loop (`workflow.orchestrator`)
  - [x] 8.1 Define `perform-effect` multimethod and all defmethods
    - `defmulti perform-effect` plus ALL defmethods in this one namespace:
      `:dispatch-agent`, `:verify-red`, `:verify-green`, `:verify-capability`,
      `:begin-iteration`, `:route-conflict-to-test-designer`, `:escalate`.
    - `:verify-red` emits `:red-verified`/`:red-invalid`; `:verify-green` emits
      `:green`; `:verify-capability` observes changes (`fs/observe-changes`) and
      decides via `core/capability-violation?`.
    - _Requirements: 1.2, 1.3, 3.3, 3.4, 15.4_
  - [x] 8.2 Implement two-phase dispatch and the drive loop
    - Phase 1: transact `:step/status :dispatched`, `:step/dispatched-at`,
      `:step/iteration`, `:step/capability` BEFORE spawning. Invoke agent.
      Verify capability. Phase 2: transact `:step/status :complete|:failed`,
      `:step/outcome-at`, `:step/result-ref` AFTER return; advance
      `:iteration/revision` in the SAME transaction for implementer/test-designer
      Step outcomes. Loop feeds recorded events through `core/transition`.
    - _Requirements: 1.1, 8.2, 8.4, 17.4, 18.1_
  - [x] 8.3 Write property test for two-phase dispatch recoverability
    - **Property 14: Two-phase dispatch makes an interrupted step recoverable and fails closed when reality is indeterminate**
    - RED first: intent persisted before run, outcome only after return; a
      dispatched step with no outcome is `step-in-doubt?` (neither complete nor
      untouched); indeterminate reality fails closed.
    - Tag: `Feature: orchestrator-state-machine, Property 14`
    - **Validates: Requirements 18.1, 18.2, 18.5**
  - [x] 8.4 Implement fail-closed capability enforcement across roles
    - On violation drive the fail-closed transition: test-designer wrote production
      => `:production-touched` (remains outside `:implement`); implementer
      wrote/authored a test => `:test-touched`; reviewer edited => `:reviewer-edited`.
      A violation never advances the pipeline.
    - _Requirements: 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 11.3, 16.6_
  - [x] 8.5 Implement review-round handling, findings, and reconciliation
    - Run correctness before structural (R-7), feed correctness findings into the
      structural dispatch via `:review/inputs-ref` (R-9); record reviews with
      `:review/revision-counter`; bind approvals via `:approval/revision-counter`;
      manage repair proposals (amendments are new `:proposal` entities, acceptances
      do not carry forward) and per-`:disagreement/id` allowance
      (`consume-attempt`); enforce binding decisions (`binding-conflict?`) and
      reconsideration admissibility.
    - _Requirements: 7.1, 7.2, 7.3, 9.1, 12.1, 12.2, 12.3, 12.4, 13.1, 13.2, 13.3, 14.3, 15.1, 15.2, 15.5, 16.4_
  - [ ]* 8.6 Write property test for repair-plan authorization
    - **Property 9: Repair is authorized only by one unchanged plan accepted by both reviewers**
    - RED first: repair authorized iff one proposal accepted by both reviewers;
      amended proposals are new entities both must re-accept; superseded acceptances
      do not carry forward.
    - Tag: `Feature: orchestrator-state-machine, Property 9`
    - **Validates: Requirements 12.1, 12.2, 12.3, 12.4**
  - [ ]* 8.7 Write property test for structural receiving correctness findings
    - **Property 7: Structural review receives the correctness findings**
    - RED first: for any recorded correctness findings, the structural dispatch
      input references those findings.
    - Tag: `Feature: orchestrator-state-machine, Property 7`
    - **Validates: Requirements 9.1**
  - [x] 8.8 Implement R-8.6–8.9 finding-carry before minting a new iteration
    - When a round ends without approval, require a durably recorded R-10-compliant
      `:finding` (`finding-valid?` true) BEFORE `:begin-iteration` mints the new
      Iteration and trace-id; reset `:iteration/revision` to 0; link
      `:iteration/seeded-from-finding` / `:finding/carried-to-iteration`; fail
      closed if no valid finding exists.
    - _Requirements: 8.6, 8.8, 8.9_
  - [x] 8.9 Implement resume/reconcile and resume-staleness recording
    - On resume: reconcile `step-in-doubt?` steps against observable reality
      (`fs` recovery: re-run RED/GREEN, inspect artifacts) before recording
      outcome (fail closed if indeterminate); separately read `:iteration/revision`
      from Datalevin and compare via `core/approval-valid?`, marking
      `:approval/stale?` with `:approval/stale-reason :revision-advanced` (recorded
      by the Orchestrator without a reviewer statement) when advanced; a bare
      interruption resumes on the same trace without restarting at test-design.
    - _Requirements: 8.5, 8.7, 18.2, 18.3, 18.4, 18.5_
  - [x] 8.10 Implement escalation on exhausted allowance
    - `:reconcile :allowance-exhausted -> :escalated` via the `:escalate` effect;
      escalation is reached only via allowance exhaustion, never via a test
      conflict; record durable per-`:disagreement/id` consumption.
    - _Requirements: 15.4, 15.5_

- [x] 9. Entry point / CLI wiring (`workflow.main`)
  - [x] 9.1 Wire the entry point and CLI
    - Parse run configuration, open the Datalevin store, construct the selected
      `AgentInvoker` backend (hermes default, kiro provisional) with model default
      `"auto"`, and drive the orchestrator loop (fresh run or resume). No orphaned
      code — every prior component is integrated here.
    - _Requirements: 4.1, 4.2, 17.3_

- [x] 10. End-to-end orchestrator tests (`orchestrator_test`)
  - [x] 10.1 Write end-to-end property test for capability fail-closed enforcement
    - **Property 2: Any change outside a role's capability fails closed and never advances** (end-to-end clauses)
    - RED first: drive each role through the loop with a fake backend producing
      out-of-boundary changes; assert each violation fails closed and does not
      advance (test-designer violation stays outside `:implement`).
    - Tag: `Feature: orchestrator-state-machine, Property 2`
    - **Validates: Requirements 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 11.3, 16.6**
  - [x] 10.2 Write end-to-end property test for the Revision-counter approval binding
    - **Property 6: Approvals bind to the Revision counter value in force, and a recorded Step outcome advances the counter and stales the approval** (end-to-end clauses)
    - RED first: happy-path AND-gate on the same counter value; a later
      implementer/test-designer Step outcome advances the counter and stales a
      prior approval across a simulated restart.
    - Tag: `Feature: orchestrator-state-machine, Property 6`
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 16.4, 16.5**
  - [x] 10.3 Write end-to-end property test for finding-carry and resume-staleness
    - **Property 15: A review round ending without approval records a justified finding, carries it forward, and records resume-staleness without a reviewer**
    - RED first: a REQUEST_CHANGES / withheld approval requires a durable
      R-10-compliant finding before the new iteration is minted and carries it via
      `:iteration/seeded-from-finding`; resume-staleness records
      `:approval/stale-reason :revision-advanced` with no reviewer statement.
    - Tag: `Feature: orchestrator-state-machine, Property 15`
    - **Validates: Requirements 8.6, 8.7, 8.8, 8.9**
  - [ ]* 10.4 Write end-to-end example/integration tests for edge cases
    - Correctness-before-structural ordering (R-7); AND-gate happy path (R-16);
      reconcile exhaustion -> escalate with per-disagreement allowance (R-15);
      two-phase in-doubt step reconciled on resume (R-18.3 sequencing); a
      REQUEST_CHANGES completes the remaining review then mints a NEW iteration at
      test-design (never in-place repair); a bare interruption resumes on the same
      trace; R-16.2 existing-failing-test accepted as defect evidence without a new
      failing test; R-5/R-6 read-only reviewer dispatch. Uses a fake `AgentInvoker`
      and a temp store.
    - _Requirements: 5.1, 6.1, 7.1, 7.2, 7.3, 16.2, 16.3, 18.3_

- [x] 11. Final checkpoint — ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a
  faster MVP; core implementation tasks are never optional.
- Each task references specific requirements (R-n.m) and/or design properties for
  traceability.
- Each design Correctness Property is encoded RED first (a failing property-based
  test) then implemented to GREEN, and is implemented by a SINGLE property-based
  test at minimum 100 iterations, tagged
  `Feature: orchestrator-state-machine, Property {number}: {property text}`.
- Property test file organization follows the design: `core_test` hosts the pure
  clauses of Properties 1–12 and 14; `store_test` hosts Property 13 and the
  durable clauses of Property 12; `fs_test` hosts observe-changes + R-18 recovery;
  `agents_test` hosts argv + protocol; `orchestrator_test` hosts end-to-end
  Properties 2, 6, 15 and integration edge cases.
- Reviewer-judgment criteria (R-4, R-5.2, R-6.2) are exercised by example/unit
  tests, not PBT.
- The `flow-documenter` role and `:documenting` state are OUT OF SCOPE (deferred);
  the state machine leaves room for it as a later final step but no task
  implements it. `README.org` is a reference type and MUST NOT be modified.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["2.3", "2.5", "2.7", "2.9", "2.12", "2.14", "2.17"] },
    { "id": 3, "tasks": ["2.4", "2.6", "2.8", "2.10", "2.11", "2.13", "2.15", "2.16", "2.18"] },
    { "id": 4, "tasks": ["4.1", "5.1", "6.1"] },
    { "id": 5, "tasks": ["4.2", "4.3", "5.2", "6.2"] },
    { "id": 6, "tasks": ["4.4", "5.3", "6.3", "6.4"] },
    { "id": 7, "tasks": ["4.5", "4.6", "4.7", "6.5"] },
    { "id": 8, "tasks": ["8.1"] },
    { "id": 9, "tasks": ["8.2", "8.4"] },
    { "id": 10, "tasks": ["8.5", "8.8", "8.9", "8.10", "8.3"] },
    { "id": 11, "tasks": ["9.1", "8.6", "8.7"] },
    { "id": 12, "tasks": ["10.1", "10.2", "10.3", "10.4"] }
  ]
}
```
