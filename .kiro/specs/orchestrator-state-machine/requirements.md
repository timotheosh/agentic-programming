# Requirements Document

## Introduction

This feature is a Clojure implementation of the multi-agent, test-driven development
workflow that `README.org` describes *as a type*. Rather than depending on a
particular agent host's subagent or hook machinery, orchestration is a
**data-driven state machine**: a pure transition calculation at the core with
effects pushed to the edges (Actions / Calculations / Data separation per Eric
Normand's *Grokking Simplicity*). Durable state is stored in **Datalevin**
(Datalog query, LMDB-backed, ACID) and written as each meaningful fact happens,
so a halted run resumes where it left off. An **agent-invocation protocol**
(`defprotocol`) dispatches scoped work to selectable backends — `hermes` first,
then `kiro` — with a selectable model defaulting to an agentic `auto` setting.

The orchestrator drives these workflow roles:

- `test-designer` — designs independent behavioral tests, proves RED, writes no
  production code.
- `implementer` — makes RED tests GREEN using ACD separation and refuses work
  lacking relevant tests.
- `correctness-reviewer` — read-only; judges requirements ↔ tests ↔ behavior.
- `structural-reviewer` — read-only; judges organization, duplication,
  complexity, and maintainability.

The `correctness-reviewer` and `structural-reviewer` form a fail-closed AND gate.
The `flow-documenter` role described in `README.org` is **out of scope** for this
spec.

The source of truth for a Run is exactly three things: the current state of the
filesystem, the state machine managed by the Orchestrator, and the Datalevin
(Datalog) database. Git is NOT a source of truth and is NOT used for
revision or approval identity; neither the Orchestrator nor any agent
(`test-designer`, `implementer`, `correctness-reviewer`, `structural-reviewer`)
commits anything to git.

`README.org` is a reference type only and MUST NOT be modified by the
implementation.

Requirements are numbered 1–18. Requirements 1–17 correspond to the original user
stories; requirement 18 captures the durable-recovery refinement. The design
references these by the identifiers `R-1` .. `R-18` (i.e. `R-n` == requirement n).

Stage states referenced below are `NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`,
`COMPLETE`, `APPROVED`, and `REQUEST_CHANGES`, plus `DISPATCHED` for
agent-dispatch steps (see Requirement 18). Approval is never inferred from
silence, timeout, malformed output, tool failure, or a missing artifact; those
conditions fail closed.

## Glossary

- **Orchestrator** — the data-driven state machine that drives the workflow;
  its core transition logic is a pure calculation and its effects (agent
  dispatch, persistence) occur at the edges.
- **Run** — one end-to-end execution of the workflow for a feature/requirement
  set, identified by a run-id (UUID).
- **Slice** — one planned unit of behavioral change within a Run, ordered within
  its Run and identified by a slice-id (UUID).
- **Iteration** — one full pass of `test-design → RED → implement → GREEN →
  review → reconcile` for a Slice, identified by an iteration-id (UUID). A
  review-authorized correction mints a new Iteration. `trace-id` is a synonym
  for iteration-id.
- **Trace-id** — synonym for iteration-id; a random UUID minted when a reviewer
  authorizes a correction and the Orchestrator re-dispatches to the first agent.
- **Step** — one agent dispatch within an Iteration, identified by a step-id
  (UUID), carrying a two-phase intent/outcome lifecycle.
- **RED** — verified failing-test evidence attributable to missing required
  behavior (not to syntax, setup, fixture, dependency, or unrelated errors).
- **GREEN** — the state in which the relevant test suite passes after
  implementation.
- **ACD separation** — Actions / Calculations / Data separation: effects
  (Actions) are kept at the edges, decisions are expressed as pure Calculations,
  and inert values are modeled as Data.
- **Review round** — one execution of the correctness review followed by the
  structural review against a single, unchanged Revision (the Revision counter
  defined below).
- **Revision** — a monotonically increasing counter, scoped to the current
  Iteration and recorded in Datalevin, incremented each time an `implementer` or
  `test-designer` Step outcome is durably recorded. A Revision carries no file
  information (unless a reviewer is describing why something failed its
  standards): it is not a hash, not a manifest, not derived from or compared
  against the filesystem, and not git. An approval binds to the Revision value in
  force when it was recorded.
- **Repair plan** — a single proposed set of corrective changes that both
  reviewers must accept before repair changes begin.
- **Disagreement** — a reconciliation subject on which reviewers disagree,
  identified by a stable disagreement-id; it carries a bounded allowance.
- **Reconciliation allowance** — the bounded number of proposal attempts
  permitted per Disagreement (one initial proposal plus one revision).
- **Binding decision** — a previously agreed outcome that remains in force until
  explicitly replaced through reconciliation.
- **Superseded decision** — a decision replaced by a newer one; retained and
  still queryable.
- **In-doubt step** — a dispatched Step with no recorded outcome after an
  interruption; reconciled against observable reality (the current filesystem
  state, test results, and produced artifacts on disk) on resume.
- **Fail closed** — when a required condition cannot be confirmed (silence,
  timeout, malformed output, tool failure, missing artifact, indeterminate
  reality), the Orchestrator refuses to advance rather than assuming success.
- **AND gate** — approval requires both the correctness reviewer and the
  structural reviewer to approve; either non-approval blocks.
- **Test ownership** — ALL tests, whether new or changed, are authored
  exclusively by the `test-designer`. Test files are fully read-only to the
  `implementer`: the `implementer` neither creates nor edits any test file and
  may never author, create, add, write, edit, modify, delete, skip, disable,
  weaken, or rewrite any test. Any new or changed test originates from the
  `test-designer`.
- **Production-code ownership** — ALL production/implementation code is authored
  exclusively by the `implementer`. Production/implementation files are fully
  read-only to the `test-designer`: the `test-designer` neither creates nor edits
  any production/implementation file and may never create, write, edit, or modify
  production/implementation code. Any new or changed production/implementation
  file originates from the `implementer`.
- **AgentInvoker** — the `defprotocol` abstraction for agent invocation.
- **Backend** — an implementation of the `AgentInvoker` protocol (`hermes`,
  `kiro`).
- **Model `auto`** — the default agentic model selection passed to a Backend
  when no specific model is chosen.

## Requirements

### Requirement 1: Independent, failing test design precedes implementation

**User Story:** As a developer, I want the test-writing agent to write
independently designed tests that fail because the required behavior is missing,
without writing any implementation, so that success is defined before
implementation begins.

#### Acceptance Criteria

1. WHEN a Slice iteration begins, THE Orchestrator SHALL dispatch the
   `test-designer` before dispatching any implementation work.
2. WHEN the `test-designer` completes, THE Orchestrator SHALL require verified
   RED evidence before advancing to the implementation state.
3. IF a failing test fails due to a syntax, setup, fixture, dependency, or
   unrelated error, THEN THE Orchestrator SHALL treat the RED as invalid and
   remain outside the implementation state.
4. WHILE the `test-designer` is invoked, THE Orchestrator SHALL constrain the
   `test-designer` through a capability boundary that excludes all writes to
   production/implementation files, covering both edits to existing production
   files and creation of new production files.
5. IF the `test-designer` attempts to create, write, edit, or modify any
   production/implementation file, THEN THE Orchestrator SHALL fail closed and
   reject the change and remain outside the implementation state.
6. WHILE RED has not been verified for the current Iteration, THE Orchestrator
   SHALL remain outside the implementation state.

### Requirement 2: Implementation refuses work lacking relevant tests

**User Story:** As a developer, I want the implementation agent to refuse work
that lacks relevant tests, including during repairs, so that every behavioral
change follows a test-first process.

#### Acceptance Criteria

1. IF implementation of a behavioral change is requested AND no relevant test
   covers the intended behavior, THEN THE `implementer` SHALL refuse to proceed.
2. IF a repair to behavior is requested AND no relevant test demonstrates the
   defect, THEN THE `implementer` SHALL refuse to proceed.
3. WHERE a behavioral change is authorized to proceed, THE Orchestrator SHALL
   confirm that a relevant test exists before implementation begins.

### Requirement 3: Tests are authored exclusively by the test-designer and never written or modified by the implementer

**User Story:** As a developer, I want all tests to be authored exclusively by
the `test-designer`, with the `implementer` neither writing new tests nor editing
existing ones, so that passing tests remain meaningful evidence and cannot be
weakened, disabled, rewritten, or fabricated to make an implementation pass.

#### Acceptance Criteria

1. THE `implementer` SHALL NOT edit, modify, delete, skip, disable, weaken, or
   rewrite any existing test under any circumstance, including during repairs.
2. THE `implementer` SHALL NOT author, create, add, or write any test, new or
   otherwise, under any circumstance, including during repairs; test authorship
   belongs exclusively to the `test-designer`.
3. IF the `implementer` determines that a test contradicts a requirement, THEN
   THE `implementer` SHALL stop, report the conflict, and leave the test
   unchanged.
4. WHEN a test conflict is reported, THE Orchestrator SHALL route the conflict to
   the `test-designer` for resolution rather than permitting the `implementer` to
   change the test.
5. WHILE the `implementer` is invoked, THE Orchestrator SHALL constrain the
   `implementer` through a capability boundary that excludes all writes to test
   files, covering both edits to existing test files and creation of new test
   files.
6. IF the `implementer` attempts to author or create a new test, or to edit,
   modify, delete, skip, disable, weaken, or rewrite an existing test, THEN THE
   Orchestrator SHALL fail closed and reject the change.

### Requirement 4: Clear responsibilities and minimal complexity

**User Story:** As a developer, I want implementations with clear
responsibilities and minimal unnecessary complexity, so that the resulting
software is understandable and maintainable.

#### Acceptance Criteria

1. THE implementation SHALL apply ACD separation, keeping effects at the edges
   and expressing decisions as calculations.
2. THE implementation SHALL give each component a single, clear responsibility.
3. THE implementation SHALL exclude speculative abstractions and accidental
   complexity.

### Requirement 5: Correctness review examines requirements, tests, and behavior

**User Story:** As a developer, I want correctness review to examine
requirements, test quality, and actual behavior, so that passing tests cannot
conceal unmet requirements.

#### Acceptance Criteria

1. WHEN the correctness review runs, THE `correctness-reviewer` SHALL examine the
   requirements, the quality of the tests, and the actual runtime behavior.
2. WHERE passing tests could succeed while a requirement remains violated, THE
   `correctness-reviewer` SHALL flag the discrepancy.

### Requirement 6: Structural review examines organization and maintainability

**User Story:** As a developer, I want structural review to examine the
organization and maintainability of the work, so that successful behavior does
not conceal avoidable complexity.

#### Acceptance Criteria

1. WHEN the structural review runs, THE `structural-reviewer` SHALL examine
   organization, duplication, complexity, and maintainability.
2. WHERE successful behavior conceals avoidable complexity or duplication, THE
   `structural-reviewer` SHALL flag the concern.

### Requirement 7: Correctness reviews first, Structural afterward

**User Story:** As a developer, I want Correctness to review first and Structural
afterward, even when Correctness requests changes, so that both perspectives are
available before repairs begin.

#### Acceptance Criteria

1. WHEN a review round occurs, THE Orchestrator SHALL run the correctness review
   before the structural review.
2. IF the correctness review returns `REQUEST_CHANGES`, THEN THE Orchestrator
   SHALL still run the structural review afterward.
3. WHILE a review round is incomplete, THE Orchestrator SHALL withhold any repair
   until both review perspectives are produced.

### Requirement 8: Approvals are bound to the exact work reviewed

**User Story:** As a developer, I want both reviewers to examine the same
unchanged work, and I want approvals tied to the work actually reviewed, so that
findings can be considered together and changed work cannot inherit an old
approval.

#### Acceptance Criteria

1. WHEN both reviewers review a round, THE Orchestrator SHALL present the same,
   unchanged Revision counter value to each reviewer.
2. WHILE a review round is in progress, THE Orchestrator SHALL NOT dispatch any
   `implementer` or `test-designer` Step that would advance the Revision counter.
3. WHEN an approval is recorded, THE Orchestrator SHALL bind the approval to the
   Revision value in force at the time of approval.
4. IF an `implementer` or `test-designer` Step outcome is recorded after an
   approval, including across an interruption or restart, THEN THE Orchestrator
   SHALL advance the Revision counter and mark that approval stale.
5. WHEN a Run resumes, THE Orchestrator SHALL read the current Revision counter
   from Datalevin and SHALL honor an approval only if it matches the Revision the
   approval named, and otherwise SHALL treat the work as unapproved.
6. WHEN a reviewer withholds approval, THE reviewer SHALL state to the
   Orchestrator what failed to meet the specifications and why.
7. WHEN a Run resumes and a prior approval is treated as unapproved due to
   Revision staleness (per the resume Revision-match check in acceptance
   criterion 5), THE Orchestrator SHALL record the staleness as the reason,
   without requiring a reviewer statement.
8. WHEN a reviewer states what failed to meet the specifications and why, THE
   Orchestrator SHALL record that statement in Datalevin as part of the
   reviewer's results as a finding meeting Requirement 10 (problem, evidence,
   justification, required outcome).
9. WHERE a review round ends without approval (a reviewer withholds approval or
   a `correctness-reviewer` or `structural-reviewer` returns `REQUEST_CHANGES`),
   THE Orchestrator SHALL require a reviewer finding meeting Requirement 10 to be
   durably recorded in Datalevin before minting the new Iteration and trace-id,
   and SHALL carry that recorded failure information into the new Iteration
   together with the new trace-id.
10. WHILE approvals may become stale, THE Orchestrator SHALL preserve the history
   of accepted decisions as queryable facts in Datalevin.

### Requirement 9: Structural sees Correctness's findings and reasoning

**User Story:** As a developer, I want Structural to see Correctness's findings
and reasoning, so that its recommendations account for concerns already
identified.

#### Acceptance Criteria

1. WHEN the structural review runs, THE Orchestrator SHALL provide the
   correctness review's findings and reasoning as input to the
   `structural-reviewer`.

### Requirement 10: Every requested change is fully justified

**User Story:** As a developer, I want every requested change to explain the
problem, supporting evidence, justification, and required outcome, so that
repairs address demonstrated needs rather than unexplained preferences.

#### Acceptance Criteria

1. WHEN a reviewer requests a change, THE reviewer SHALL include the problem, the
   supporting evidence, the justification, and the required outcome in the
   finding.
2. IF a finding omits the problem, the evidence, the justification, or the
   required outcome, THEN THE Orchestrator SHALL treat the finding as an invalid
   change request.

### Requirement 11: Reviewers report findings but do not repair

**User Story:** As a developer, I want reviewers to report findings without
performing repairs themselves, so that review and implementation remain separate
responsibilities.

#### Acceptance Criteria

1. WHEN a reviewer runs, THE reviewer SHALL produce findings only.
2. WHILE a review is in progress, THE Orchestrator SHALL invoke each reviewer
   through a read-only capability that excludes file edits and repairs.
3. IF a reviewer (the `correctness-reviewer` or the `structural-reviewer`)
   attempts to edit or modify any file or perform a repair, THEN THE Orchestrator
   SHALL fail closed and reject the change.

### Requirement 12: One repair plan accepted by both reviewers before repairs begin

**User Story:** As a developer, I want one repair plan accepted by both reviewers
before changes begin, so that the repair agents receive a coherent direction.

#### Acceptance Criteria

1. WHEN repairs are needed, THE Orchestrator SHALL produce a single Repair plan.
2. WHILE both reviewers have not accepted the same, unchanged Repair plan, THE
   Orchestrator SHALL withhold repair changes.
3. IF either reviewer requests an amendment after the other has accepted, THEN
   THE Orchestrator SHALL treat the amended proposal as a new Repair plan that
   both reviewers must accept before repairs begin.
4. WHEN a Repair plan is superseded by an amendment, THE Orchestrator SHALL
   exclude the superseded plan's acceptance from the amended plan.

### Requirement 13: Agreed outcomes stay binding until explicitly replaced

**User Story:** As a developer, I want later repairs to preserve previously
agreed outcomes, so that satisfying one reviewer does not silently undo work
accepted by the other.

#### Acceptance Criteria

1. WHILE a replacement decision has not been accepted through reconciliation, THE
   Orchestrator SHALL keep each previously agreed outcome binding.
2. IF a repair would contradict a Binding decision, THEN THE Orchestrator SHALL
   reject that repair until the outcome is formally replaced.
3. THE Orchestrator SHALL track agreed outcomes and check each subsequent repair
   against them.

### Requirement 14: Reconsidering an accepted decision requires new evidence

**User Story:** As a developer, I want reconsideration of an accepted decision to
identify that decision and provide new evidence explaining why it should change,
so that mistakes can be corrected without repeatedly reopening settled
preferences.

#### Acceptance Criteria

1. WHEN an agent seeks to reconsider an accepted decision, THE agent SHALL
   identify the specific decision and provide new evidence for the change.
2. IF a reconsideration request lacks a specific decision or new evidence, THEN
   THE Orchestrator SHALL treat the request as inadmissible.
3. WHERE new evidence is admissible, THE Orchestrator SHALL permit reconsideration
   while keeping the prior decision binding until a replacement decision is
   accepted through reconciliation.

### Requirement 15: Bounded, per-disagreement reconciliation before escalation

**User Story:** As a developer, I want unresolved disagreements brought to me
after a bounded reconciliation attempt, so that the pipeline cannot continue an
endless cycle of contradictory repairs.

#### Acceptance Criteria

1. THE Orchestrator SHALL bound the Reconciliation allowance per Disagreement,
   identified by its subject, to one initial proposal plus one revision (two
   proposal attempts total).
2. WHEN a proposal is rejected, THE Orchestrator SHALL consume Reconciliation
   allowance identically to an accepted proposal.
3. WHEN the same Disagreement is reopened, THE Orchestrator SHALL preserve its
   remaining Reconciliation allowance without resetting it, including when
   reviewers agreed on a repair in one round and reversed it later, and including
   across an interruption or restart.
4. WHEN a Disagreement's Reconciliation allowance is exhausted without
   resolution, THE Orchestrator SHALL stop repairs on that subject and escalate
   the Disagreement to the human.
5. WHEN Reconciliation allowance is consumed, THE Orchestrator SHALL record the
   consumption as a durable fact keyed to the Disagreement's identity.

### Requirement 16: Test-first repair, then re-verification and re-approval

**User Story:** As a developer, I want repaired work to pass its tests and
receive approval from both reviewers again, so that agreement on a repair plan is
followed by verification of the actual result.

#### Acceptance Criteria

1. WHEN a behavioral repair begins, THE Orchestrator SHALL require that a
   relevant test already demonstrates the defect.
2. WHERE an existing failing test demonstrates the defect, THE Orchestrator SHALL
   accept it as the defect evidence without requiring a new failing test.
3. WHERE a repair is a behavior-preserving structural refactor, THE Orchestrator
   SHALL retain the repair's passing tests and SHALL NOT require a manufactured
   failure.
4. WHEN a repair's implementation completes, THE Orchestrator SHALL require that
   the tests pass and that both reviewers approve the resulting Revision.
5. WHEN the work changes, THE Orchestrator SHALL mark prior approvals stale and
   require both reviewers to approve the new Revision (per Requirement 8).
6. WHILE a repair is in progress, THE Orchestrator SHALL require any new or
   changed test to originate from the `test-designer` and SHALL NOT permit the
   `implementer` to edit, modify, delete, skip, disable, weaken, or rewrite any
   existing test (per Requirement 3).

### Requirement 17: Durable, complete history including superseded decisions

**User Story:** As a developer, I want progress, findings, justifications,
decisions, and resolutions preserved — including decisions later replaced — so
that everyone can understand the current direction and how it was reached.

#### Acceptance Criteria

1. THE Orchestrator SHALL preserve progress, findings, justifications, decisions,
   and resolutions in Datalevin.
2. WHEN a decision is replaced, THE Orchestrator SHALL keep both the replacement
   and the Superseded decision queryable.
3. THE Orchestrator SHALL write state durably as each fact happens so that a
   halted Run resumes where it left off.
4. WHEN a meaningful fact is recorded, THE Orchestrator SHALL persist it durably
   in a single ACID Datalevin database transaction at the moment it happens.

### Requirement 18: Reconcile recorded progress with an interrupted agent's actual outcome

**User Story:** As a developer, I want durable recovery to distinguish recorded
progress from an interrupted agent's actual outcome, so that a completion or file
change made just before a crash is neither lost nor wrongly assumed.

#### Acceptance Criteria

1. WHEN a Step dispatches an agent, THE Orchestrator SHALL persist the dispatch
   intent durably to Datalevin before the agent runs and SHALL persist the
   outcome durably to Datalevin after the agent returns (consistent with the
   general durability principle in R-17.3 and R-17.4).
2. WHEN a Run resumes AND a Step is found dispatched with no recorded outcome,
   THE Orchestrator SHALL treat that Step as in doubt, neither assumed complete
   nor assumed untouched.
3. WHEN reconciling an In-doubt step, THE Orchestrator SHALL reconcile it against
   observable reality (for example the current filesystem state, test results, or
   produced artifacts on disk) before recording its outcome.
4. WHERE practical, THE Orchestrator SHALL make agent effects idempotent or
   verifiable so that reconciling an In-doubt step is safe.
5. IF observable reality cannot be determined, THEN THE Orchestrator SHALL fail
   closed.
