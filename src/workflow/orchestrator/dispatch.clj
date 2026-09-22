(ns workflow.orchestrator.dispatch
  "Two-phase Step dispatch and fail-closed capability enforcement (design,
  Orchestrator loop; R-18.1, R-8.4, Post-invocation verification; Property 2;
  R-1.4, R-1.5, R-3.x, R-11.2, R-11.3, R-16.6). Split out of the original
  monolithic `workflow.orchestrator` (user-directed reorganization).

  A Step carries a two-phase intent/outcome lifecycle (R-18.1):

    Phase 1 — RECORD INTENT (durable, BEFORE spawning): one ACID commit stamps a
      fresh `:step` entity at `:step/status :dispatched` with `:step/dispatched-at`,
      the `:step/iteration` trace, the `:step/role`, and the `:step/capability`
      descriptor. This intent is committed BEFORE the agent runs, so a crash
      mid-invocation leaves the Step observably `:dispatched` with no outcome —
      `core/step-in-doubt?` — to be reconciled on resume, never assumed complete
      nor assumed untouched (R-18.1, R-18.2).
    Phase 2 — RECORD OUTCOME (durable, AFTER return): one ACID commit stamps the
      SAME `:step` entity with its terminal `:step/status` (`:complete`|`:failed`),
      `:step/outcome-at`, and `:step/result-ref`. For an `implementer` /
      `test-designer` Step outcome the owning Iteration's `:iteration/revision`
      counter is advanced in the SAME transaction (via `store/record-step-outcome`,
      which already does this), so the counter can never lag a recorded outcome
      (R-8.4, R-17.4). A reviewer Step advances no counter.

  Between the phases the invocation is dispatched
  (`workflow.orchestrator.core/perform-effect :dispatch-agent`) and its
  produced changes are verified against the role's capability boundary
  (`:verify-capability`). The AUTHORITATIVE boundary check is this post-return
  verification, never the advisory sandbox the backend was handed.

  `dispatch-step!` DECIDES the boundary and SURFACES a `:violation` (the first
  offending produced change) — it does not itself drive the transition that a
  violation implies. `enforce-capability` is the wiring that ENFORCES it: on an
  observed violation the orchestrator maps the role to the violation EVENT the
  transition table names and feeds it through `core/transition`, which fails
  closed for every role (design, Post-invocation verification step 3):

    test-designer wrote production => :production-touched
      => {:error {:code :test-designer-wrote-production}}  ; stays outside :implement (R-1.4, R-1.5)
    implementer wrote/authored a test => :test-touched
      => {:error {:code :implementer-wrote-test}}          ; change rejected (R-3.6)
    reviewer edited any file => :reviewer-edited
      => {:error {:code :reviewer-attempted-repair}}       ; change rejected (R-11.3)

  A violation NEVER advances the pipeline: the fail-closed transition carries an
  `:error` and no `:next-state`, so the machine records the outcome and stays put.
  Enforcement leans entirely on the pure `core/transition` table — no error code
  or rejection rule is duplicated here."
  (:require [workflow.rules.core :as core]
            [workflow.fs :as fs]
            [workflow.store :as store]
            [workflow.agents :as agents]
            [workflow.orchestrator.core :as effects]
            [datalevin.core :as d]))

(def ^:private revision-advancing-roles
  "The roles whose Step outcome advances the Iteration's Revision counter
  (R-8.4): only the `implementer` and `test-designer` author code, so only their
  outcomes advance the counter. A reviewer Step advances nothing."
  #{:implementer :test-designer})

(defn revision-advancing-role?
  "True iff a Step outcome for `role` advances the Iteration's Revision counter
  (R-8.4).

  Only `:implementer` and `:test-designer` outcomes advance the counter — they
  are the roles that author code; a reviewer outcome advances nothing. Pure
  lookup over the role keyword; no I/O. The counter carries no file information;
  its advance is the pure `core/advance-revision` committed by
  `store/record-step-outcome` in the same transaction as the outcome."
  [role]
  (contains? revision-advancing-roles role))

(defn record-step-dispatch!
  "PHASE 1. Commit a Step's dispatch INTENT durably BEFORE the agent runs
  (design, Orchestrator loop step 1; R-18.1).

  In one `d/transact!` — one durable LMDB commit — mints a fresh `:step` entity
  at `:step/status :dispatched`, stamping `:step/dispatched-at`, the
  `:step/iteration` trace, the `:step/role`, and the `:step/capability`
  descriptor (defaulting to the role's descriptor via `agents/capability-for`),
  plus the optional `:step/model` / `:step/backend`. Committing the intent BEFORE
  spawning is what makes an interrupted Step observable as `core/step-in-doubt?`
  on resume (R-18.1, R-18.2).

  `dispatch` is a plain map:
    {:iteration-eid <iteration entity id>   ; required; the trace this Step belongs to
     :role          <keyword>               ; required; :test-designer | :implementer | ...
     :capability    <keyword>               ; optional; defaults to (agents/capability-for role)
     :model         <string>                ; optional; \"auto\" default lives on the task
     :backend       <keyword>               ; optional; :hermes | :kiro
     :at            <java.util.Date>}        ; optional; defaults to now
  Returns {:step-eid <entity id> :step-id <uuid> :tx <transaction report>}; the
  `:step-eid` is what phase 2 (`store/record-step-outcome`) stamps the outcome
  against, correlating the returned result to this dispatch (one-shot process maps
  one-to-one to its Step). This is an action: it commits durable state on disk."
  [conn {:keys [iteration-eid role capability model backend at]}]
  (let [step-id (random-uuid)
        step-ent (cond-> {:db/id -1
                          :step/id step-id
                          :step/role role
                          :step/capability (or capability (agents/capability-for role))
                          :step/status :dispatched
                          :step/dispatched-at (or at (java.util.Date.))}
                   iteration-eid (assoc :step/iteration iteration-eid)
                   model (assoc :step/model model)
                   backend (assoc :step/backend backend))
        report (d/transact! conn [step-ent])]
    {:step-eid (get (:tempids report) -1)
     :step-id step-id
     :tx report}))

(defn dispatch-step!
  "Run ONE agent Step through the full two-phase lifecycle (design, Orchestrator
  loop; R-18.1, R-1.5, R-8.4).

  Wraps the `perform-effect` methods in the durable two-phase envelope:

    1. PHASE 1 — `record-step-dispatch!` commits the `:dispatched` intent BEFORE
       spawning (R-18.1), threading the resulting `:step-eid` into `ctx` so the
       invocation and outcome are correlated to this Step.
    2. INVOKE — `perform-effect :dispatch-agent` fires the agent through the
       `AgentInvoker`, returning {:result … :task …}. When `ctx` carries NO
       explicit `:changes` (a real invocation never populates one — F2), this
       step is bracketed by a real `fs/snapshot-dir` of `:cwd` taken
       immediately BEFORE and AFTER the invocation, and the
       `fs/diff-snapshot` between them becomes the `:changes` fed to phase 3 —
       the AUTHORITATIVE observation of what the invocation actually produced
       on disk, never trusted from the agent's self-reported result. A caller
       that DOES supply explicit `:changes` (every existing test) is
       unaffected — that value passes straight through unchanged.
    3. VERIFY CAPABILITY — `perform-effect :verify-capability` observes the
       produced changes (from `:changes` in `ctx`/the effect) and DECIDES the
       role's boundary fail-closed. The decision is surfaced, not yet enforced as
       a transition (`enforce-capability` wires that).
    4. PHASE 2 — `store/record-step-outcome` commits the terminal outcome AFTER
       the return (R-18.1), advancing `:iteration/revision` in the SAME
       transaction for an `implementer`/`test-designer` Step
       (`revision-advancing-role?`), so the counter never lags the outcome (R-8.4).

  The Step's terminal status is `:complete` on an `:ok` invocation with NO
  capability violation, and `:failed` otherwise (a non-`:ok` invocation OR an
  observed violation) — fail closed: a violation or a failed process is never
  recorded as a completed Step.

  `ctx` is the effect context map (see `workflow.orchestrator.core`'s
  namespace docstring); the keys read here are `:conn`, `:agent-invoker`,
  `:role`, `:iteration-eid`, `:capability`, the dispatch task inputs
  (`:prompt`/`:model`/`:cwd`/…), and `:changes` (what the invocation reported
  it produced, for capability verification). `opts` may carry `:effect` —
  extra keys merged into the `:dispatch-agent` effect (e.g. a per-dispatch
  `:task` override).

  Returns a map the drive loop consumes:
    {:step-eid   <entity id>        ; the Step recorded (phase 1)
     :step-id    <uuid>
     :role       <keyword>          ; the role dispatched (for role-appropriate enforcement)
     :result     <AgentInvoker result>
     :task       <task dispatched>
     :violation  <offending change|nil>
     :changes    <observed change set>
     :status     :complete | :failed} ; the terminal Step status committed
  The `:role` is surfaced so `enforce-capability` can map a surfaced
  `:violation` to the role-appropriate fail-closed event without re-consulting
  `ctx`. This is an action: it commits durable Step facts and dispatches an agent."
  ([ctx] (dispatch-step! ctx {}))
  ([ctx {:keys [effect]}]
   (let [{:keys [conn role iteration-eid capability model backend cwd]} ctx
         ;; F2: a caller supplying NO :changes at all (every real dispatch —
         ;; main.clj's effect-context never sets one) gets the REAL produced
         ;; changes derived from a before/after directory snapshot diff around
         ;; the invocation, never trusted from the agent. A caller that DOES
         ;; supply :changes (every existing test) is unaffected: that value is
         ;; used as-is, exactly as before this change.
         explicit-changes? (contains? ctx :changes)
         before-snapshot (when (and (not explicit-changes?) cwd) (fs/snapshot-dir cwd))
         ;; Phase 1: intent committed BEFORE the agent runs (R-18.1).
         {:keys [step-eid step-id]} (record-step-dispatch!
                                     conn
                                     {:iteration-eid iteration-eid
                                      :role role
                                      :capability capability
                                      :model model
                                      :backend backend})
         ctx* (assoc ctx :step-eid step-eid)
         ;; Invoke the agent through the AgentInvoker.
         dispatched (effects/perform-effect ctx* (merge {:effect/type :dispatch-agent} effect))
         result (:result dispatched)
         ;; The REAL observed changes: the caller's explicit :changes when
         ;; supplied, otherwise the snapshot diff taken around the invocation.
         observed-changes (if explicit-changes?
                             (:changes ctx)
                             (when cwd (fs/diff-snapshot before-snapshot (fs/snapshot-dir cwd))))
         ctx** (assoc ctx* :changes observed-changes)
         ;; Verify the produced changes against the role's capability boundary
         ;; (authoritative post-return check; the sandbox is advisory).
         cap (effects/perform-effect ctx** {:effect/type :verify-capability})
         violation (:violation cap)
         ok? (and (= :ok (:status result)) (nil? violation))
         status (if ok? :complete :failed)]
     ;; Phase 2: outcome committed AFTER the return, advancing the Revision
     ;; counter in the SAME transaction for implementer/test-designer (R-8.4).
     (store/record-step-outcome
      conn
      {:step-eid step-eid
       :iteration-eid iteration-eid
       :status status
       :result-ref (some-> result :raw :command pr-str)
       :advance-revision? (revision-advancing-role? role)})
     {:step-eid step-eid
      :step-id step-id
      :role role
      :result result
      :task (:task dispatched)
      :violation violation
      :changes (:changes cap)
      :status status})))

(def ^:private role->violation-event
  "Pure mapping from a role to the violation EVENT its boundary breach raises
  (design, Post-invocation verification step 3).

  Each event is the one the `workflow.rules.core/transitions` table turns into the
  role-appropriate fail-closed error: the `test-designer` touching production
  raises `:production-touched` (=> `:test-designer-wrote-production`), the
  `implementer` authoring/modifying a test raises `:test-touched` (=>
  `:implementer-wrote-test`), and either reviewer editing any file raises
  `:reviewer-edited` (=> `:reviewer-attempted-repair`). The boundaries are
  symmetric, so both reviewers map to the same event."
  {:test-designer        :production-touched
   :implementer          :test-touched
   :correctness-reviewer :reviewer-edited
   :structural-reviewer  :reviewer-edited})

(defn violation-event-for
  "Return the fail-closed violation EVENT `role` raises on a boundary breach, or
  nil for an unrecognized role (design, Property 2).

  Pure lookup over `role->violation-event`: `:test-designer` ->
  `:production-touched`, `:implementer` -> `:test-touched`, and both reviewers ->
  `:reviewer-edited`. Returns nil for a role the mapping does not name, so an
  unrecognized role has no legal violation event and any attempt to enforce it
  fails closed downstream (`core/transition` rejects the nil-derived pair). Pure
  calculation; no I/O."
  [role]
  (get role->violation-event role))

(defn enforce-capability
  "Drive the fail-closed transition a `dispatch-step!` `:violation` implies,
  WITHOUT advancing the pipeline (design, Post-invocation verification step 3;
  Property 2; R-1.4, R-1.5, R-3.6, R-11.3, R-16.6).

  `state` is the state the pipeline is in (where the just-dispatched Step ran);
  `dispatched` is the map `dispatch-step!` returned (carrying `:role`,
  `:violation`, `:status`, …). When `dispatch-step!` observed NO violation there
  is nothing to enforce, so this returns `dispatched` unchanged and the caller
  advances the machine on the ordinary event. When a violation WAS observed it
  maps the role to its violation event (`violation-event-for`) and feeds that
  event through the pure `core/transition` at `state`; because every violation
  event resolves to an `{:error …}` in the table, the returned transition carries
  the role-appropriate fail-closed error and NO `:next-state` — the pipeline does
  not advance (the `test-designer` violation stays outside `:implement`).

  Returns the input `dispatched` map augmented with:
    {:enforced?  <bool>              ; true iff a violation was enforced
     :violation-event <event|nil>    ; the event fed through core/transition
     :transition <core/transition result>  ; the fail-closed {:error …} (or nil when none)
     :error      <fail-closed error|nil>}   ; the transition's :error, surfaced for the caller
  A recognized role with a violation always yields a non-nil `:error`; an
  unrecognized role (no violation event) still fails closed — `core/transition`
  rejects the `[state nil]` pair — so a breach is never silently allowed.

  This is an action only in that it drives the pure transition and shapes the
  result the drive loop / caller halts on; it performs no I/O itself. Recording
  the failed Step outcome already happened in `dispatch-step!` (phase 2, status
  `:failed`); this wires the machine-level fail-closed transition on top."
  [state dispatched]
  (let [{:keys [role violation]} dispatched]
    (if (nil? violation)
      dispatched
      (let [event      (violation-event-for role)
            transition (core/transition state event)]
        (assoc dispatched
               :enforced? true
               :violation-event event
               :transition transition
               :error (:error transition))))))
