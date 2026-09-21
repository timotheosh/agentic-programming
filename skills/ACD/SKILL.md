---
name: ACD
description: Refactor code by classifying it into Actions, Calculations, and Data (Eric Normand's Grokking Simplicity), extracting hidden business logic into pure calculations while preserving observable behavior.
---
# Grokking Simplicity Refactoring Prompt — Chapters 3–9

Your goal is to reduce software complexity while preserving observable behavior.

Functional style is a means, not the objective. Prefer the simplest design that makes actions, calculations, and data clear.

## Instruction style

Describe the behavior you want rather than enumerating behavior to avoid.

Prefer concrete positive guidance. Reserve negative constraints for rare, critical cases where a positive formulation would be ambiguous.

## 1. Distinguish actions, calculations, and data

Classify the code that matters to the change:

- **Action** — depends on when or how many times it runs, performs I/O, reads or writes mutable state, uses time/randomness, or interacts with an external system.
- **Calculation** — depends only on explicit inputs, returns the same result for the same inputs, and has no observable side effects.
- **Data** — inert facts represented as values.

Prefer **data over calculations, and calculations over actions**, when doing so makes the design simpler.

Use the classification to guide the design. Produce a classification inventory only when it helps solve the task.

## 2. Pull calculations out of actions

For each significant action, ask:

- What does it read that was not passed as an argument? That is an implicit input.
- What does it change besides its return value? That is an implicit output.
- Which decisions, validations, transformations, filtering, aggregation, or derived values are deterministic?

Move deterministic work into calculations when practical. Replace implicit inputs with arguments and implicit outputs with return values where that improves clarity.

Keep actions focused on obtaining inputs, invoking calculations, and performing the effects the calculations require.

Perform calculations as soon as their inputs are available, and defer actions until their results are needed. Before reading external state, acquiring resources, opening connections, or performing effects, first complete every calculation possible from the data already available. A failing action should not mask an error that could have been discovered from existing data.

## 3. Pull things apart before inventing abstractions

Design is primarily about separating responsibilities so they can be understood independently and recombined cleanly.

When a function mixes input gathering, decisions, transformations, and effects, split those responsibilities along meaningful business or domain concepts.

Introduce an abstraction when the resulting boundary has a clear current purpose. First make the responsibilities explicit; then abstract only what the design benefits from hiding or naming.

## 4. Keep calculation data immutable

Calculations treat their inputs as immutable and return derived values instead of modifying their inputs.

Use the language's native immutable or persistent data structures when available. In mutable languages, use copy-on-write when code you control must produce modified values.

If an operation both reads and writes, look for a clean split between the value being derived and the mutation being performed.

When crossing a boundary into code that may mutate data, isolate that boundary with defensive copying or an equivalent adapter when necessary. Rely on existing immutability guarantees and copy only when the boundary requires it.

## 5. Keep each function at one abstraction level

A function should read at one useful "zoom level."

Keep high-level business workflow expressed in high-level terms. Move low-level iteration, representation details, parsing mechanics, or data-structure operations behind clearly named functions when those details obscure intent.

When a function jumps between distant abstraction levels, extract the lower-level detail into a clearly named function. Names should describe intent at the layer where they are used.

Prefer straightforward implementations over clever ones.

## 6. Use abstraction barriers deliberately

Hide implementation or representation details when a boundary genuinely helps callers ignore them.

Good reasons include:

- several callers repeat the same lower-level mechanics;
- an important domain concept deserves a stable interface;
- callers benefit from independence from a particular representation.

Expose the smallest interface that serves the concept.

Add wrappers, protocols, classes, helper layers, or generic abstractions only when they create a useful boundary for the current design. Keep the abstraction proportional to the problem.

Lower-level reusable code deserves strong direct tests because many higher-level functions may depend on it.

## 7. Refactor incrementally

Trace the real execution path before changing code.

Reuse existing functions, boundaries, idioms, standard-library facilities, and installed dependencies where they already solve the problem.

Make the smallest change that cleanly satisfies the requirement while preserving observable behavior.

Keep abstractions close to concrete domain needs. Add a new boundary only when it simplifies the current design.

Use the language's natural idioms and existing guarantees.

Optimize for clarity, simplicity, and current requirements.

## Review questions

Before finishing, check:

1. Which effects truly have to remain actions?
2. What deterministic work is still buried inside those actions?
3. Could any action be moved later, after more of the decision has been calculated from existing data?
4. Are important inputs and outputs explicit?
5. Do calculations preserve their input values?
6. Does each function stay at a useful abstraction level?
7. Does each abstraction barrier hide something worth hiding, with a minimal interface?
8. Does the refactoring preserve observable behavior?

Explain only the non-obvious design decisions. Let the code and tests carry the routine ACD classification.
