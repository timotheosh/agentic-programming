# Grokking Simplicity Refactoring Prompt (Through Chapter 6)

## Core Instruction

Reduce complexity using Eric Normand's Action / Calculation / Data (ACD)
model. Preserve observable behavior unless a change is explicitly approved.
Prefer small, incremental improvements over architectural rewrites or
speculative abstractions.

Use the following hooks when their trigger applies. Do not perform every hook
ceremonially when it is irrelevant.

## Hook: Classify Before Refactoring

**Trigger:** Before changing significant logic.

Classify the relevant code as:

- **Action:** depends on time, execution count, mutable state, I/O, or an
  external system.
- **Calculation:** depends only on explicit inputs, returns the same output for
  the same inputs, and has no observable side effects.
- **Data:** passive facts that can be stored, compared, serialized, or passed.

Identify business rules, decisions, validation, filtering, mapping,
aggregation, and transformations hidden inside actions.

## Hook: Extract a Calculation

**Trigger:** Before modifying an action that contains business logic.

1. List its explicit inputs (arguments) and explicit outputs (return values).
2. List its implicit inputs, including reads of global, shared, mutable,
   environmental, or external state.
3. List its implicit outputs, including mutation, logging, I/O, and external
   calls.
4. Extract the business logic.
5. Convert required implicit inputs into arguments.
6. Convert produced implicit outputs into return values.
7. Leave necessary effects in the calling action.

Verify that the extracted function is actually a calculation. Moving action
code into another function does not make it a calculation.

## Hook: Control Mutation

**Trigger:** When code writes to an array, object, collection, argument, or
shared data structure.

Classify the operation as a read, write, or read-and-write.

- Reads of immutable data can be calculations.
- Reads of mutable data are actions because their results can change.
- Writes make data mutable and are implicit outputs.

Convert a write to copy-on-write when practical:

1. Make a shallow copy immediately.
2. Modify only the copy.
3. Return the copy.

For nested writes, copy every object or collection along the path from the
root to the value being changed. Reuse unchanged branches through structural
sharing. Never mutate caller-owned data from a calculation.

If an operation both reads and writes, either:

- split the read from the write and make the write copy-on-write; or
- return both the requested result and the updated value explicitly.

Do not reject copy-on-write based on assumed performance costs. Optimize only
after measurement identifies a real bottleneck.

## Hook: Improve a Remaining Action

**Trigger:** After extracting all practical calculations from an action.

Minimize the action's remaining implicit inputs and outputs. Convert globals
and hidden dependencies into arguments where practical, even if the function
must remain an action. Fewer implicit dependencies improve reuse, testing, and
safe composition.

Keep effects at system boundaries and make actions thin wrappers around
calculations.

## Hook: Align the Design with the Domain

**Trigger:** When a function's inputs or output do not directly express the
business question it answers.

- Prefer meaningful domain data over incidental intermediate values.
- Reuse existing calculations instead of duplicating derived-value logic.
- Pull apart responsibilities that can vary, be tested, or be reused
  independently.
- Extract a general operation only when it emerges from concrete duplication
  or an established pattern.
- Reject extra indirection that does not clarify or enable reuse.

Changing a signature or domain rule may not be behavior-preserving. Identify
such changes separately and request approval before implementing them.

## Hook: Separate Decisions from Effects

**Trigger:** When business decisions and effects appear in the same function.

Prefer this flow:

```text
data -> calculation -> decision data -> action
```

Keep stable business calculations independent of volatile infrastructure.
Push database access, HTTP, file I/O, logging, clocks, randomness, UI updates,
and shared-state mutation toward the edges.

## Hook: Validate the Result

**Trigger:** After making changes.

Confirm that:

- observable behavior is preserved unless a change was approved;
- extracted calculations have no implicit inputs or outputs;
- calculations do not mutate caller-owned data;
- nested copy-on-write copied every changed level;
- remaining actions have only necessary effects and hidden dependencies;
- abstractions are supported by concrete use; and
- existing tests pass when available.

Report briefly:

1. The original action and its implicit inputs and outputs.
2. The calculations extracted and their explicit interfaces.
3. Any dependencies made explicit or copy-on-write applied.
4. Why complexity decreased and behavior remained unchanged.

## Constraints

- Do not introduce unnecessary abstractions or rewrite the architecture.
- Do not invent APIs, optimize prematurely, or replace idiomatic code with
  functional jargon.
- Prefer immutable data passed between calculations over shared mutable state.
- Represent knowledge as data rather than executable logic when practical.

