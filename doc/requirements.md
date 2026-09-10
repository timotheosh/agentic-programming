# Requirements: Multi-Agent Development Workflow State Machine

This is the authoritative requirements source for the project. It is a Clojure
implementation of the multi-agent development workflow described *as a type* in
`README.org`. Instead of depending on Claude Code's subagent/hook machinery, the
orchestration is:

- a **data-driven state machine** written in Clojure (pure transition
  calculation at the core, effects at the edges);
- **durable state stored in Datalevin** (LMDB-backed, ACID), written as it
  happens so a halted run can resume where it left off;
- an **agent-invocation layer** (`defprotocol`) that fires off agents with
  scoped work. First backend is **hermes**; **kiro** is also provided. Each
  implementation can tell the agent which model to use for a task, defaulting to
  an agentic `auto` setting.

`README.org` is a reference type only and must not be modified.

Each requirement has a stable identifier (`R-1` .. `R-18`). `R-1`..`R-17` match
the user stories of the same number. `R-18` (iteration identity) is a
cross-cutting requirement the others reference. Tests reference these
identifiers.

---

## Roles (agents)

- `test-designer` — designs behavioral tests independently, proves RED, writes
  no production implementation.
- `implementer` — makes RED tests GREEN with the smallest coherent
  implementation; must not weaken independent tests.
- `correctness-reviewer` — read-only; judges requirements ↔ tests ↔ behavior.
- `structural-reviewer` — read-only; judges organization, duplication,
  complexity, maintainability.
- `flow-documenter` — runs once at the end (part of the domain).

The two reviewers form a fail-closed AND gate.

---

## Requirements

### R-1 — Independent, failing test design precedes implementation
The `test-designer` translates requirements into executable behavioral tests
that **fail because the required behavior is missing**, and writes **no
production implementation**. Success (RED evidence) is established before
implementation begins.

- A valid RED is a clean assertion/behavior failure attributable to missing
  behavior — not a syntax, setup, fixture, dependency, or unrelated failure.
- The state machine must not advance to implementation until RED is verified.

### R-2 — Implementation refuses work lacking relevant tests
The `implementer` refuses to act on any behavioral change — **including repair
work** — that does not have relevant tests covering the intended behavior. Every
behavioral change follows a test-first process.

### R-3 — Tests are protected from being weakened to pass
Tests may not be deleted, skipped, weakened, or rewritten merely to make an
implementation pass. Passing tests remain meaningful evidence. If the
implementer believes a test contradicts a requirement, it must stop and report
the conflict; it may not alter the test itself.

### R-4 — Implementations have clear responsibilities and minimal complexity
Implementations have clear, single responsibilities and avoid unnecessary
complexity (ACD: actions/calculations/data separated; effects at edges). The
result must be understandable and maintainable.

### R-5 — Correctness review examines requirements, test quality, and behavior
Correctness review examines the requirements themselves, the quality of the
tests, and the actual runtime behavior — so passing tests cannot conceal unmet
requirements.

### R-6 — Structural review examines organization and maintainability
Structural review examines the organization, duplication, complexity, and
maintainability of the work — so successful behavior does not conceal avoidable
complexity.

### R-7 — Correctness reviews first, Structural afterward (even on changes)
Correctness reviews **first**; Structural reviews **afterward**, even when
Correctness requests changes. Both perspectives are produced before any repairs
begin. (This differs from the README template, which runs the two reviewers
concurrently and blind. Here the order is fixed and Structural is informed by
Correctness — see R-9.)

### R-8 — Both reviewers examine the same iteration
Both reviewers examine the **same iteration** (see R-18) of the work, so their
findings can be considered together. Because an iteration is immutable, no work
can change between the two reviews of a single round; any work-changing repair
produces a new iteration (R-18) requiring fresh review.

### R-9 — Structural sees Correctness's findings and reasoning
Structural review receives Correctness's findings and reasoning as input, so its
recommendations account for concerns already identified. (Consequence of R-7's
ordering.)

### R-10 — Every requested change is fully justified
Every requested change explains all of:
1. the **problem**,
2. the **supporting evidence**,
3. the **justification** (why it matters), and
4. the **required outcome**.
Repairs address demonstrated needs, not unexplained preferences. A finding
missing any of these four parts is not a valid change request.

### R-11 — Reviewers report findings but do not perform repairs
Reviewers report findings only. They never edit files or perform repairs
themselves. Review and implementation remain separate responsibilities.

### R-12 — One repair plan accepted by both reviewers before repairs begin
A single repair plan is produced and **accepted by both reviewers** before any
repair changes begin, so repair agents receive one coherent direction. The
accepted repair plan is recorded against the iteration (R-18) it applies to.

### R-13 — Later repairs preserve previously agreed outcomes
Later repairs preserve previously agreed outcomes. Satisfying one reviewer must
not silently undo work accepted by the other. Agreed outcomes are tracked and
checked against subsequent repairs.

### R-14 — Reconsidering an accepted decision requires new evidence
To reconsider an accepted decision, an agent must (a) **identify the specific
decision** and (b) **provide new evidence** explaining why it should change.
Settled preferences cannot be reopened without new evidence, so mistakes can be
corrected without endless churn.

### R-15 — Unresolved disagreement escalates to the human after bounded attempts
Reconciliation of reviewer disagreement is **bounded** (a maximum number of
iterations, R-18). After the bound is reached without resolution, the
disagreement is brought to the human. The pipeline cannot continue an endless
cycle of contradictory repairs.

### R-16 — Repaired work is re-verified and re-approved
After repair, the resulting new iteration (R-18) must pass its tests **and**
receive approval from **both** reviewers again. Agreement on a repair plan is
followed by verification of the actual result (re-establish RED when behavioral
tests changed, then GREEN, then both reviews).

### R-17 — Durable, complete history including superseded decisions
Progress, findings, justifications, decisions, and resolutions are preserved —
**including decisions later replaced** — so the current direction and how it was
reached are both recoverable. Superseded decisions are retained (not deleted),
linked to the decision that replaced them. State is written durably as it
happens so a halted run resumes where it left off.

### R-18 — Revision identity is pipeline-iteration identity
The unit of review, approval, dispatch, and recovery reconciliation is a
**pipeline iteration**: the immutable work product produced at one point in the
pipeline.

- Every iteration has a **stable, durable identifier** assigned when the
  iteration is created and never reused.
- An iteration is **immutable**: once created it is the fixed target that
  reviews, approvals, and repair plans refer to.
- Any **work-changing repair advances the pipeline to a new iteration** with a
  new identifier. Approvals do not carry forward: an approval names exactly one
  iteration, and a new iteration requires fresh approval (see R-8, R-16).
- Git state (HEAD commit, dirty working tree, generated artifacts) is an
  **incidental attribute captured on the iteration**, not the source of its
  identity. The orchestrator never requires or encourages agents to create Git
  commits to establish identity.
- All revision-referencing requirements (R-8, R-12, R-15, R-16, R-17) refer to
  this iteration identity.

---

## Cross-cutting mechanisms

- **Iteration identity (R-18):** the durable iteration ID is the single
  reference used by approvals, review rounds, dispatches, repair plans, and
  recovery reconciliation. Reconciliation bounds (R-15) count iterations.
- **Durability / resume (R-17, and the halt-and-restart requirement):** every
  state transition, iteration, and recorded fact is committed to Datalevin
  (LMDB/ACID) at the moment it happens. Reopening the same database recovers the
  full history and the current iteration, so a restart continues from the last
  committed point.
- **Pure core (R-4):** the transition function is a calculation:
  `(transition current-state event) -> next-state-or-error`. Datalevin writes
  and agent process invocation are actions performed at the edges by the
  orchestrator.
- **Agent invocation (all roles):** a `defprotocol` describes how to run an
  agent with a scoped task and a selected model. `hermes` and `kiro`
  implementations satisfy it. Model defaults to agentic `auto`.

## Terminology for stage states

`NOT_STARTED`, `IN_PROGRESS`, `BLOCKED`, `COMPLETE`, `APPROVED`,
`REQUEST_CHANGES`. Approval is never inferred from silence, timeout, malformed
output, tool failure, or a missing artifact; those conditions fail closed.
