(ns workflow.orchestrator
  "The orchestrator's top-level composition: the entry-point-facing actions
  that drive a full Iteration and gate repairs on dual acceptance. The rest
  of the original monolithic `workflow.orchestrator` (1947 lines) was split,
  user-directed, into the cohesive sub-namespaces this one composes:

    * `workflow.orchestrator.core`         — the `perform-effect` multimethod
                                              (the calculations-meet-actions
                                              boundary; project rule: a
                                              defmulti and all its defmethods
                                              live together).
    * `workflow.orchestrator.dispatch`     — two-phase Step dispatch and
                                              fail-closed capability
                                              enforcement.
    * `workflow.orchestrator.drive`        — the drive loop.
    * `workflow.orchestrator.review`       — review rounds, findings, and the
                                              approval AND-gate.
    * `workflow.orchestrator.repair`       — repair proposals and
                                              reconsideration admissibility.
    * `workflow.orchestrator.disagreement` — per-Disagreement Reconciliation
                                              allowance and escalation
                                              (required by `.core`, not by
                                              this facade directly).
    * `workflow.orchestrator.resume`       — resume / reconcile +
                                              resume-staleness (required by
                                              `workflow.core`, the entry
                                              point, not by this facade).

  This namespace owns only the compositions that reach `-main` directly:
  `run-iteration!` (drive ONE Iteration end-to-end: test-designer ->
  RED/existing-coverage -> implementer -> GREEN -> review round -> the
  AND-gate) and `authorize-repair!` (gate a repair's `:plan-accepted` on dual
  acceptance before it mints a new Iteration). Nothing here re-decides
  anything the sub-namespaces already own; it only sequences them."
  (:require [workflow.rules.core :as core]
            [workflow.orchestrator.core :as effects]
            [workflow.orchestrator.dispatch :as dispatch]
            [workflow.orchestrator.drive :as drive]
            [workflow.orchestrator.review :as review]
            [workflow.orchestrator.repair :as repair]
            [workflow.store :as store]))

;; --- STR-3: wire repair-authorized? into the REAL minting decision (R-12) ---
;;
;; `repair/repair-authorized?` was unit-tested but never called from `src/`
;; before this fix: `perform-effect :begin-iteration` / `mint-iteration!` only
;; ever checked the R-10 finding (`select-carried-finding`/`core/finding-valid?`)
;; before minting a corrective Iteration — nothing stood between a
;; `:plan-accepted` event and a mint. `mint-iteration!`/`:begin-iteration`
;; themselves are left untouched (existing tests exercise them directly,
;; without any proposal in play, and must keep passing); the gate belongs
;; BEFORE the `:plan-accepted` event is ever raised in the real flow — the
;; only place that event comes from is a human/reviewer acceptance decision,
;; which is exactly what `authorize-repair!` gates.

(defn authorize-repair!
  "Drive `[:reconcile :plan-accepted]` (which performs `:begin-iteration` /
  `mint-iteration!`) ONLY when `proposal` is authorized by BOTH reviewers
  (design R-12.1, R-12.2; STR-3).

  `proposal` is the plain map read back for the repair `:proposal` entity
  under consideration (e.g. one of `store/proposals-for-slice`'s results) —
  its `:proposal/accepted-by` set is exactly what `repair/repair-authorized?`
  decides over. WHILE both reviewers have not accepted the SAME, unchanged
  Repair plan, repair changes are withheld (R-12.2): this FAILS CLOSED with
  `{:error {:code :repair-not-authorized …}}` and performs NO effect —
  `drive` is never called, so `:begin-iteration` never runs and no Iteration
  is minted.

  When authorized, delegates to `drive/drive` on `:reconcile`/`:plan-accepted`
  — the SAME path the drive loop already exercises (and existing tests already
  cover) — so `mint-iteration!` runs exactly as it always has; this function
  only adds the authorization gate in front of raising that event.

  `ctx` is the effect context `drive/drive` consumes (`:conn`, run/slice/iteration
  eids, `:state` defaults handled by the transact effects). Returns the
  `drive` result on authorization, or the fail-closed error map otherwise.
  This is an action: it performs no I/O of its own beyond what `drive` does
  when authorized."
  [ctx proposal]
  (if (repair/repair-authorized? proposal)
    (drive/drive ctx :reconcile :plan-accepted)
    {:error {:code :repair-not-authorized
             :message (str "Repair changes are withheld until the SAME, unchanged "
                           "plan is accepted by BOTH reviewers (R-12.1, R-12.2); "
                           "this proposal has not been — failing closed.")
             :proposal-id (:proposal/id proposal)}}))

;; --- F1/STR-2: wire dispatch-eligible? into the REAL :implement decision ----
;;
;; `core/dispatch-eligible?` (R-2, R-7) was a correct, pure predicate never
;; called from `src/`. The real gate for R-2 ("the implementer refuses work
;; lacking a relevant test") belongs exactly where the orchestrator decides to
;; dispatch the implementer: `dispatch-implementer!` below is that decision
;; point, reusable independently of the full `run-iteration!` composition.
;;
;; `:relevant-test?` becomes true from the SAME verification event
;; (`:red-verified` from `:verify-red`, or `:existing-coverage-confirmed` from
;; the new `:verify-existing-coverage`, R-16.2/R-16.3) that the caller just
;; produced and is about to feed through `core/transition` to reach
;; `:implement` — the evidence IS the fact that authorized entry to
;; `:implement` in the first place, so no separate durable query is needed to
;; answer the same question a second time.
;;
;; ponytail: trace-facts here is derived from the just-computed event rather
;; than a durable per-iteration query, which is sufficient because
;; `dispatch-implementer!` is always called immediately after that
;; verification within the same driving pass (see `run-iteration!`). If
;; `:implement` is ever dispatched from a separately-resumed context that
;; does not have the triggering event in hand, upgrade this to a durable
;; `trace-facts` query over the recorded Steps/events for the iteration.

(defn dispatch-implementer!
  "Dispatch the implementer Step for `ctx`'s iteration, REFUSING (fail closed,
  R-2.1, R-2.2, R-2.3, R-16.1) unless `event` — the verification event that
  just authorized entry to `:implement` — establishes a relevant test.

  `event` is `:red-verified` or `:existing-coverage-confirmed`: exactly the
  two events `core/transitions` maps from `:test-design` to `:implement`.
  Builds `trace-facts` from it (`{:relevant-test? …}`) and asks the pure
  `core/dispatch-eligible? :implement`; any other event means no relevant test
  is established, so THE IMPLEMENTER REFUSES TO PROCEED — no Step is even
  dispatched (refusing means never starting, not starting and then failing).

  On eligibility, delegates to `dispatch/dispatch-step!` (via
  `dispatch/enforce-capability`, so the returned map is already
  boundary-enforced) with `:role :implementer` merged onto `ctx`, unchanged
  from the existing two-phase dispatch.

  Returns the (enforced) `dispatch-step!` result on eligibility, or the
  fail-closed `{:error {:code :implementation-refused-no-relevant-test …}}`
  on refusal — nothing is dispatched or recorded in that case. This is an
  action: it may dispatch an agent and commit durable Step facts."
  [ctx event]
  (let [trace-facts {:relevant-test? (contains? #{:red-verified :existing-coverage-confirmed} event)}]
    (if (core/dispatch-eligible? :implement trace-facts)
      (dispatch/enforce-capability :implement (dispatch/dispatch-step! (assoc ctx :role :implementer)))
      {:error {:code :implementation-refused-no-relevant-test
               :message (str "The implementer refuses to proceed: no relevant test is "
                             "recorded for the current iteration (R-2.1, R-2.2, R-2.3).")
               :event event}})))

;; --- STR-1: compose the real driving path main.clj hands control to --------
;;
;; `workflow.core`'s docstring claims "no orphaned code — reachable from
;; -main," but `fresh-run!` previously only bootstrapped a Run and stopped:
;; `drive` was invoked nowhere in `src/`, only from tests. `run-iteration!` is
;; the missing composition: it wires the SAME pieces the "scripted-scenario"
;; test in `orchestrator_test.clj` already proves work together
;; (`dispatch-step!` + `enforce-capability` + the `:verify-*` effects + `drive`
;; + `run-review-round!` + `both-approved?`) into one reusable action, so a
;; real (or fake-backed) Run actually proceeds dispatch -> drive -> review
;; round -> the AND-gate, instead of stopping after bootstrap.
;;
;; This does not automate reviewer-verdict derivation from agent output (out
;; of scope for these findings — no parsing of that shape exists anywhere in
;; this codebase yet) or the repair-proposal negotiation loop
;; (`propose-repair!`/`accept-proposal!`/`authorize-repair!` remain
;; independently callable, human-in-the-loop actions): verdicts are supplied
;; explicitly via `spec` and default to `:request-changes` — approval is never
;; inferred (R-8.6) — so a caller that supplies no verdicts safely lands the
;; pass at `:reconcile` rather than silently approving anything.

(defn- dispatch-and-enforce!
  "Dispatch a Step for `role` at `state` and enforce its capability boundary
  in one call (`dispatch/dispatch-step!` + `dispatch/enforce-capability`); the
  small composition `run-iteration!` repeats for the test-designer and each
  reviewer."
  [ctx role state]
  (dispatch/enforce-capability state (dispatch/dispatch-step! (assoc ctx :role role))))

(defn run-iteration!
  "Drive ONE Iteration end-to-end: test-designer -> RED/existing-coverage ->
  (eligible) implementer -> GREEN -> review round -> the AND-gate (design,
  Orchestrator loop; STR-1).

  `ctx` is the effect context (`:conn`, `:agent-invoker`, run/slice/iteration
  eids, `:cwd`, …). `spec` is a plain map:
    {:red-command            [\"…\" …] ; RED (or existing-coverage) verification command
     :green-command          [\"…\" …] ; GREEN verification command; defaults to :red-command
                                       ;   (a real run re-runs the SAME suite twice)
     :existing-coverage-kind kw       ; :defect-evidence (R-16.2, confirms on a currently
                                       ;   FAILING command — an existing test already proves
                                       ;   the defect) or :behavior-preserving (R-16.3, confirms
                                       ;   on a currently PASSING command — a refactor retains
                                       ;   its existing passing tests); nil (default) => require
                                       ;   a fresh RED via :verify-red instead of this shortcut
     :correctness-verdict :approve|:request-changes  ; defaults :request-changes (fail closed)
     :structural-verdict  :approve|:request-changes} ; defaults :request-changes (fail closed)

  Halts and returns at the FIRST point that does not advance: a dispatch/
  capability-boundary error, an indeterminate or invalid verification, an
  implementer refusal (`dispatch-implementer!`, F1/STR-2), or — having reached
  a review round — the AND-gate's outcome (`:slice-approved` when both
  verdicts are `:approve` and bind to the same counter, `:reconcile`
  otherwise). Returns `{:stage kw :state kw :error {…}|nil …}` plus whichever
  of `:dispatched`/`:verify`/`:drive`/`:review`/`:approved?` this pass reached,
  so a caller can inspect exactly where and why it stopped. This is an
  action: it dispatches agents, runs verifications, and commits durable facts
  via the functions it composes."
  [{:keys [conn iteration-eid] :as ctx}
   {:keys [red-command green-command existing-coverage-kind
           correctness-verdict structural-verdict]
    :or   {correctness-verdict :request-changes
           structural-verdict  :request-changes}}]
  (let [td (dispatch-and-enforce! ctx :test-designer :test-design)]
    (if (:error td)
      {:stage :test-design :state :test-design :dispatched td :error (:error td)}
      (let [red-effect (if existing-coverage-kind :verify-existing-coverage :verify-red)
            red        (effects/perform-effect (assoc ctx
                                                       :command red-command
                                                       :existing-coverage-kind existing-coverage-kind)
                                               {:effect/type red-effect})]
        (if (:error red)
          {:stage :test-design :state :test-design :dispatched td :verify red :error (:error red)}
          (let [d1 (drive/drive ctx :test-design (:event red))]
            (if (not= :implement (:state d1))
              {:stage :test-design :state (:state d1) :dispatched td :verify red :drive d1
               :error (:error d1)}
              (let [impl (dispatch-implementer! ctx (:event red))]
                (if (:error impl)
                  {:stage :implement :state :implement :drive d1 :dispatched impl :error (:error impl)}
                  (let [green (effects/perform-effect (assoc ctx :command (or green-command red-command))
                                                      {:effect/type :verify-green})]
                    (if (:error green)
                      {:stage :implement :state :implement :drive d1 :dispatched impl
                       :verify green :error (:error green)}
                      (let [d2 (drive/drive ctx :implement (:event green))]
                        (if (not= :review-correctness (:state d2))
                          {:stage :implement :state (:state d2) :drive d2 :dispatched impl
                           :verify green :error (:error d2)}
                          (let [counter (or (store/current-revision conn iteration-eid) 0)
                                round   {:iteration-eid iteration-eid
                                         :revision-counter counter
                                         :correctness {:verdict correctness-verdict :ctx ctx}
                                         :structural  {:verdict structural-verdict :ctx ctx}}
                                reviewd  (review/run-review-round! conn round)
                                approved? (review/both-approved? conn iteration-eid counter)]
                            {:stage :review :drive d2 :review reviewd :approved? approved?
                             :state (if approved? :slice-approved :reconcile) :error nil}))))))))))))))
