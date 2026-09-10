# Requirements Document

## Introduction

This project is a Clojure implementation of the multi-agent development workflow
that `README.org` describes *as a type*. Instead of depending on Claude Code's
subagent/hook machinery, orchestration is a **data-driven state machine** (pure
transition calculation at the core, effects at the edges), durable state is
stored in **Datalevin** (LMDB-backed, ACID) written as it happens so a halted run
resumes where it left off, and an **agent-invocation protocol** (`defprotocol`)
fires scoped work at backends — **hermes** first, then **kiro** — with a
selectable model defaulting to an agentic `auto` setting.

`README.org` is a reference type only and must not be modified.

Requirements are numbered 1–18. Requirements 1–17 correspond to the original user
stories; requirement 18 captures the durable-recovery refinement. The design in
`doc/design.md` references these by the identifiers `R-1` .. `R-18` (i.e. `R-n`
== requirement n). Stage states referenced below are `NOT_STARTED`,
`IN_PROGRESS`, `BLOCKED`, `COMPLETE`, `APPROVED`, `REQUEST_CHANGES`, plus
`DISPATCHED` for agent-dispatch steps (see requirement 18). Approval is never
inferred from silence, timeout, malformed output, tool failure, or a missing
artifact; those conditions fail closed.

The workflow roles are: `test-designer` (designs behavioral tests, proves RED,
writes no production code), `implementer` (makes RED tests GREEN), the read-only
`correctness-reviewer` and `structural-reviewer` (which form a fail-closed AND
gate), and `flow-documenter` (runs once at the end).

## Requirements

### Requirement 1: Independent, failing test design precedes implementation

**User Story:** As a developer, I want the test-writing agent to write
independently designed tests that fail because the required behavior is missing,
without writing any implementation, so that success is defined before
implementation begins.

#### Acceptance Criteria

1. WHEN a slice iteration begins THEN the state machine SHALL dispatch the
   `test-designer` before any implementation work.
2. WHEN the `test-designer` completes THEN the system SHALL require verified RED
   evidence before advancing to implementation.
3. WHERE a failing test fails due to a syntax, setup, fixture, dependency, or
   unrelated error, the system SHALL treat the RED as invalid and SHALL NOT
   advance to implementation.
4. IF the `test-designer` writes or modifies production implementation THEN the
   system SHALL fail closed and SHALL NOT advance to implementation.
5. WHILE RED has not been verified for the current iteration, the state machine
   SHALL NOT enter the implementation state.

### Requirement 2: Implementation refuses work lacking relevant tests

**User Story:** As a developer, I want the implementation agent to refuse work
that lacks relevant tests, including during repairs, so that every behavioral
change follows a test-first process.

#### Acceptance Criteria

1. WHEN implementation of a behavioral change is requested AND no relevant test
   covers the intended behavior THEN the `implementer` SHALL refuse to proceed.
2. WHEN a repair to behavior is requested AND no relevant test demonstrates the
   defect THEN the `implementer` SHALL refuse to proceed.
3. WHERE a behavioral change is authorized to proceed, the system SHALL confirm
   a relevant test exists before implementation begins.

### Requirement 3: Tests are protected from being weakened to pass

**User Story:** As a developer, I want tests protected from being weakened merely
to make an implementation pass, so that passing tests remain meaningful evidence.

#### Acceptance Criteria

1. The `implementer` SHALL NOT delete, skip, weaken, or rewrite a test in order
   to make an implementation pass.
2. IF the `implementer` believes a test contradicts a requirement THEN it SHALL
   stop and report the conflict AND SHALL NOT alter the test itself.
3. WHEN a test conflict is reported THEN the state machine SHALL route it for
   resolution rather than allowing the implementer to change the test.

### Requirement 4: Clear responsibilities and minimal complexity

**User Story:** As a developer, I want implementations with clear
responsibilities and minimal unnecessary complexity, so that the resulting
software is understandable and maintainable.

#### Acceptance Criteria

1. The implementation SHALL separate actions, calculations, and data (ACD), with
   effects kept at the edges and decisions expressed as calculations.
2. Each component SHALL have a single, clear responsibility.
3. The implementation SHALL avoid speculative abstractions and accidental
   complexity.

### Requirement 5: Correctness review examines requirements, tests, and behavior

**User Story:** As a developer, I want correctness review to examine
requirements, test quality, and actual behavior, so that passing tests cannot
conceal unmet requirements.

#### Acceptance Criteria

1. WHEN correctness review runs THEN it SHALL examine the requirements
   themselves, the quality of the tests, and the actual runtime behavior.
2. WHERE passing tests could succeed while a requirement is still violated, the
   correctness review SHALL flag it.

### Requirement 6: Structural review examines organization and maintainability

**User Story:** As a developer, I want structural review to examine the
organization and maintainability of the work, so that successful behavior does
not conceal avoidable complexity.

#### Acceptance Criteria

1. WHEN structural review runs THEN it SHALL examine organization, duplication,
   complexity, and maintainability.
2. WHERE successful behavior conceals avoidable complexity or duplication, the
   structural review SHALL flag it.

### Requirement 7: Correctness reviews first, Structural afterward

**User Story:** As a developer, I want Correctness to review first and Structural
afterward, even when Correctness requests changes, so that both perspectives are
available before repairs begin.

#### Acceptance Criteria

1. WHEN a review round occurs THEN the correctness review SHALL run before the
   structural review.
2. IF the correctness review returns `REQUEST_CHANGES` THEN the structural review
   SHALL still run afterward.
3. The system SHALL produce both review perspectives before any repair begins.

### Requirement 8: Approvals are bound to the exact work reviewed

**User Story:** As a developer, I want both reviewers to examine the same
unchanged work, and I want approvals tied to the work actually reviewed, so that
findings can be considered together and changed work cannot inherit an old
approval.

#### Acceptance Criteria

1. WHEN both reviewers review a round THEN they SHALL examine the same, unchanged
   revision of the work.
2. The system SHALL NOT allow the work to change between the two reviews of a
   single round.
3. An approval SHALL be bound to the exact revision it reviewed.
4. IF the work changes after an approval, including across an interruption or
   restart, THEN that approval SHALL become stale and the work SHALL require
   re-approval.
5. WHEN a run resumes THEN an approval SHALL be honored only if the current
   revision matches the revision the approval named; otherwise the work SHALL be
   treated as unapproved.
6. WHILE approvals may become stale, the history of accepted decisions SHALL
   remain preserved and relevant.

### Requirement 9: Structural sees Correctness's findings and reasoning

**User Story:** As a developer, I want Structural to see Correctness's findings
and reasoning, so that its recommendations account for concerns already
identified.

#### Acceptance Criteria

1. WHEN the structural review runs THEN it SHALL receive the correctness review's
   findings and reasoning as input.

### Requirement 10: Every requested change is fully justified

**User Story:** As a developer, I want every requested change to explain the
problem, supporting evidence, justification, and required outcome, so that
repairs address demonstrated needs rather than unexplained preferences.

#### Acceptance Criteria

1. WHEN a reviewer requests a change THEN the finding SHALL include the problem,
   the supporting evidence, the justification, and the required outcome.
2. IF a finding is missing any of those four parts THEN the system SHALL treat it
   as not a valid change request.

### Requirement 11: Reviewers report findings but do not repair

**User Story:** As a developer, I want reviewers to report findings without
performing repairs themselves, so that review and implementation remain separate
responsibilities.

#### Acceptance Criteria

1. Reviewers SHALL report findings only.
2. Reviewers SHALL NOT edit files or perform repairs.

### Requirement 12: One repair plan accepted by both reviewers before repairs begin

**User Story:** As a developer, I want one repair plan accepted by both reviewers
before changes begin, so that the repair agents receive a coherent direction.

#### Acceptance Criteria

1. WHEN repairs are needed THEN the system SHALL produce a single repair plan.
2. The system SHALL NOT begin repair changes until both reviewers have accepted
   the same, unchanged repair proposal.
3. IF either reviewer requests an amendment after the other has accepted THEN the
   amended proposal SHALL be treated as a new proposal that both reviewers must
   accept before repairs begin.
4. Acceptance of a superseded proposal SHALL NOT carry forward to its amendment.

### Requirement 13: Agreed outcomes stay binding until explicitly replaced

**User Story:** As a developer, I want later repairs to preserve previously
agreed outcomes, so that satisfying one reviewer does not silently undo work
accepted by the other.

#### Acceptance Criteria

1. A previously agreed outcome SHALL remain binding until a replacement decision
   is explicitly accepted through reconciliation.
2. WHEN a repair would contradict a binding outcome THEN the system SHALL reject
   that repair until the outcome is formally replaced.
3. The system SHALL track agreed outcomes and check subsequent repairs against
   them.

### Requirement 14: Reconsidering an accepted decision requires new evidence

**User Story:** As a developer, I want reconsideration of an accepted decision to
identify that decision and provide new evidence explaining why it should change,
so that mistakes can be corrected without repeatedly reopening settled
preferences.

#### Acceptance Criteria

1. WHEN an agent seeks to reconsider an accepted decision THEN it SHALL identify
   the specific decision AND provide new evidence for the change.
2. IF a reconsideration request lacks a specific decision or new evidence THEN
   the system SHALL treat it as inadmissible.
3. WHERE new evidence is admissible, it SHALL permit reconsideration but SHALL
   NOT by itself authorize reversal; the prior decision SHALL remain binding
   until a replacement decision is explicitly accepted through reconciliation.

### Requirement 15: Bounded, per-disagreement reconciliation before escalation

**User Story:** As a developer, I want unresolved disagreements brought to me
after a bounded reconciliation attempt, so that the pipeline cannot continue an
endless cycle of contradictory repairs.

#### Acceptance Criteria

1. The reconciliation allowance SHALL be bounded per disagreement, identified by
   its subject, and SHALL be one initial proposal plus one revision — two
   proposal attempts total.
2. A rejected proposal SHALL consume allowance exactly like an accepted one.
3. Reopening the same disagreement SHALL NOT reset its allowance, including when
   reviewers agreed on a repair in one round and reversed it later, and including
   across an interruption or restart.
4. WHEN a disagreement's allowance is exhausted without resolution THEN the
   system SHALL stop repairs on that subject and escalate the disagreement to the
   human.
5. Allowance consumption SHALL be a durably recorded fact keyed to the
   disagreement's identity.

### Requirement 16: Test-first repair, then re-verification and re-approval

**User Story:** As a developer, I want repaired work to pass its tests and
receive approval from both reviewers again, so that agreement on a repair plan is
followed by verification of the actual result.

#### Acceptance Criteria

1. WHEN a behavioral repair begins THEN a relevant test SHALL already demonstrate
   the defect.
2. WHERE an existing failing test demonstrates the defect, it SHALL satisfy the
   defect-evidence requirement without a new failing test.
3. WHERE a repair is a behavior-preserving structural refactor, its passing tests
   SHALL be retained AND the system SHALL NOT manufacture a failure to satisfy
   the test-first rule.
4. WHEN a repair's implementation completes THEN the tests SHALL pass AND both
   reviewers SHALL approve the resulting revision.
5. WHEN the work changes THEN prior approvals SHALL be stale and both reviewers
   SHALL approve the new revision (per requirement 8).

### Requirement 17: Durable, complete history including superseded decisions

**User Story:** As a developer, I want progress, findings, justifications,
decisions, and resolutions preserved — including decisions later replaced — so
that everyone can understand the current direction and how it was reached.

#### Acceptance Criteria

1. The system SHALL preserve progress, findings, justifications, decisions, and
   resolutions.
2. WHEN a decision is replaced THEN both the replacement and the superseded
   decision SHALL remain queryable.
3. The system SHALL write state durably as it happens so a halted run resumes
   where it left off.
4. WHEN state is recorded THEN each meaningful fact SHALL be committed durably at
   the moment it happens (a single ACID commit).

### Requirement 18: Reconcile recorded progress with an interrupted agent's actual outcome

**User Story:** As a developer, I want durable recovery to distinguish recorded
progress from an interrupted agent's actual outcome, so that a completion or file
change made just before a crash is neither lost nor wrongly assumed.

#### Acceptance Criteria

1. WHEN a step dispatches an agent THEN the system SHALL commit the dispatch
   intent durably before the agent runs AND SHALL commit the outcome durably
   after the agent returns.
2. WHEN a run resumes AND a step is found dispatched with no recorded outcome
   THEN the system SHALL treat that step as in doubt, neither assumed complete
   nor assumed untouched.
3. WHEN reconciling an in-doubt step THEN the system SHALL reconcile it against
   observable reality (for example re-inspecting the working tree, test results,
   or produced artifacts) before recording its outcome.
4. WHERE practical, agent effects SHALL be idempotent or verifiable so that
   reconciling an in-doubt step is safe.
5. IF observable reality cannot be determined THEN the system SHALL fail closed.

## Glossary

- **Run** — one end-to-end execution of the workflow for a feature/requirement
  set, identified by a run-id (UUID).
- **Slice** — one planned unit of behavioral change within a run, ordered within
  its run and identified by a slice-id (UUID).
- **Iteration (trace)** — one full pass of `test-design → RED → implement →
  GREEN → review → reconcile` for a slice, identified by an iteration-id (UUID).
  A review-authorized correction mints a new iteration. `trace-id` is a synonym
  for iteration-id.
- **Step** — one agent dispatch within an iteration, identified by a step-id
  (UUID), carrying a two-phase intent/outcome lifecycle.
- **RED / GREEN** — RED is verified failing-test evidence for missing behavior;
  GREEN is the passing state after implementation.
- **Disagreement** — a reconciliation subject on which reviewers disagree,
  identified by a stable disagreement-id; it carries a bounded allowance.
- **Binding decision** — a previously agreed outcome that remains in force until
  explicitly replaced through reconciliation.
- **Superseded decision** — a decision replaced by a newer one; retained and
  still queryable.
- **In-doubt step** — a dispatched step with no recorded outcome after an
  interruption; reconciled against observable reality on resume.
- **Fail closed** — when a required condition cannot be confirmed (silence,
  timeout, malformed output, tool failure, missing artifact, indeterminate
  reality), the system refuses to advance rather than assuming success.
- **AND gate** — approval requires both the correctness and structural reviewers
  to approve; either non-approval blocks.
- **Backend** — an agent-invocation implementation of the `AgentInvoker`
  protocol (`hermes`, `kiro`).
- **Model `auto`** — the default agentic model selection passed to a backend
  when no specific model is chosen.
