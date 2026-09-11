(ns workflow.orchestrator
  "The orchestrator: where the pure state machine meets the world (design,
  Orchestrator effects and drive loop).

  `workflow.core` decides — `transition` returns a next state plus a *description
  of effects* and facts to persist — but it performs no I/O. This namespace is
  the ACTION edge that INTERPRETS that description: it dispatches agents
  (`workflow.agents`), runs and classifies verifications and observes produced
  changes (`workflow.fs`), and commits durable facts (`workflow.store`). It is
  the single boundary where calculations meet actions.

  Effect interpretation is a single multimethod, per the project rule that a
  `defmulti` and ALL of its `defmethod`s live in one namespace:

      (defmulti perform-effect (fn [ctx effect] (:effect/type effect)))

  `perform-effect` dispatches on the effect's `:effect/type`. The effect
  descriptions come straight out of the `workflow.core/transitions` table
  (e.g. `{:effect/type :begin-iteration}`, `{:effect/type
  :route-conflict-to-test-designer}`) plus the ones the drive loop raises
  itself (`:dispatch-agent`, `:verify-red`, `:verify-green`, `:verify-capability`,
  `:escalate`). RED and GREEN are effects, not states: `:verify-red` produces the
  `:red-verified` / `:red-invalid` event and `:verify-green` produces the
  `:green` event, which the drive loop feeds back into `core/transition`
  (design, Multimethod dispatch on effects).

  Scope of THIS task (8.1): define the multimethod and every `defmethod` as the
  action scaffolding — each method calls the REAL layer fns
  (`agents/invoke`, `fs/observe-changes`, `fs/run-verification`,
  `fs/red-outcome`, `fs/green-outcome`, `core/capability-violation?`,
  `store/append-transition-event`) with the context map threaded in as the
  method argument. The two-phase dispatch/drive loop (8.2), the fail-closed
  capability enforcement wiring (8.4), review handling (8.5), the R-8.6–8.9
  finding-carry (8.8), resume/reconcile (8.9), and the escalation detail (8.10)
  are downstream tasks that BUILD ON these methods; the methods are structured so
  those tasks fill in behavior WITHOUT redefining the multimethod.

  The effect context map `ctx` carries everything a method needs to act:

    {:conn          <datalevin conn>   ; the durable store (workflow.store)
     :agent-invoker <AgentInvoker>     ; a workflow.agents backend (invoke)
     :run-eid       <entity id>        ; owns the :event/seq counter
     :slice-eid     <entity id>        ; the slice this effect belongs to
     :iteration-eid <entity id>        ; the current iteration / trace
     :step-eid      <entity id>        ; the step being recorded (verify/outcome)
     :role          <keyword>          ; the role of the current dispatch
     :capability    <keyword>          ; the capability descriptor to enforce
     :state         <keyword>          ; the current state the transition left us in
     :command       [\"…\" …]           ; injectable verification command
     :artifact-paths [\"…\" …]          ; artifacts a step should have produced
     :changes       {:created […] :edited […]} ; what an invocation reported
     :cwd           <path>}            ; working directory for paths / commands

  Nothing here derives a Revision from files: Revision identity is the Datalevin
  integer counter (R-8), advanced only by `workflow.store/record-step-outcome`."
  (:require [workflow.core :as core]
            [workflow.fs :as fs]
            [workflow.store :as store]
            [workflow.agents :as agents]
            [datalevin.core :as d]))

;; --- perform-effect: the calculations-meet-actions boundary ------------------
;;
;; One multimethod, dispatched on the effect's :effect/type, with the effect
;; context map threaded in as the first argument. Every defmethod below lives in
;; this namespace (project rule: defmulti + all defmethods together).
;;
;; ACD note: each method is a THIN action wrapper. The DECISIONS it leans on are
;; pure calculations living elsewhere — `fs/red-outcome` / `fs/green-outcome`
;; classify a verification result, `core/capability-violation?` decides the
;; boundary — and this namespace only performs the effects around them (spawn,
;; read filesystem, commit Datalevin). No business rule is duplicated here.
;;
;; Return-value contract the drive loop (task 8.2) relies on:
;;
;;   * The verification effects (:verify-red, :verify-green) return an EVENT the
;;     drive loop feeds back into `core/transition`:
;;       {:event :red-verified|:red-invalid|:green}       ; advance the machine
;;       {:error {…}}                                     ; fail closed (R-1.3, R-18.5)
;;   * :verify-capability returns the boundary decision:
;;       {:violation nil    :changes <set>}               ; every change within boundary
;;       {:violation <change> :changes <set>}             ; the first offending change (fail closed)
;;   * :dispatch-agent returns the AgentInvoker result plus the task dispatched:
;;       {:result {…AgentInvoker result…} :task {…}}
;;   * The transact effects (:begin-iteration,
;;     :route-conflict-to-test-designer, :escalate) return the store transaction
;;     report they committed:
;;       {:tx <datalevin tx report>}
;;
;; Downstream tasks refine the bodies (persisting phase-1/phase-2 step facts,
;; enforcing the capability violation as a fail-closed transition, carrying a
;; finding into a newly minted iteration, etc.) WITHOUT redefining the
;; multimethod or changing these dispatch values.

(defmulti perform-effect
  "Interpret one effect description against the world, dispatched on its
  `:effect/type` (design, Multimethod dispatch on effects).

  `ctx` is the effect context map (see the namespace docstring): the durable
  connection, the agent invoker, the run/slice/iteration/step entity ids, the
  role/capability, the verification command, and the working directory. `effect`
  is the effect description, either taken verbatim from the
  `workflow.core/transitions` table (carrying just `:effect/type`) or raised by
  the drive loop with extra keys. Each method returns a plain result map (see the
  return-value contract above) — never performing the transition itself; the
  drive loop feeds a returned `:event` back through `core/transition`.

  This is an ACTION: methods spawn agents, run verifications, read the
  filesystem, and commit Datalevin facts. The `:default` method fails closed on
  an unknown effect type rather than silently succeeding."
  (fn [_ctx effect] (:effect/type effect)))

;; Forward declaration: the `:escalate` method (below) gates on exhaustion via
;; `escalate-disagreement!`, which lives with the per-Disagreement allowance
;; helpers further down (it reuses `ensure-disagreement!` and the pure
;; `core/allowance-remaining`). Declared here so the reference resolves cleanly.
(declare escalate-disagreement!)

(defmethod perform-effect :default
  ;; Fail closed on an unknown effect type: an effect the table/drive loop never
  ;; declared is not silently treated as success (design: fail closed on missing
  ;; data / malformed output). Downstream tasks add methods, never removing this
  ;; safety net.
  [_ctx effect]
  {:error {:code :unknown-effect
           :message "No perform-effect method for the given :effect/type."
           :effect effect}})

(defmethod perform-effect :dispatch-agent
  ;; Dispatch one agent invocation through the `AgentInvoker`, scoped by the
  ;; capability descriptor the role carries (design; R-1.2, R-3.x).
  ;;
  ;; Builds the scoped task from `ctx` — `:role`, the current `:iteration-eid` as
  ;; the bookkeeping trace id, the `:capability` descriptor (`agents/capability-for`
  ;; when `ctx` names none), and the optional `:prompt`/`:model`/`:cwd`/
  ;; `:timeout-ms`/`:extra` — and hands it to `agents/invoke`. The capability on
  ;; the task is advisory to the backend; the AUTHORITATIVE boundary check happens
  ;; AFTER the fact via `:verify-capability`, never trusted here.
  ;;
  ;; Returns {:result <AgentInvoker result> :task <task dispatched>}. Task 8.2
  ;; wraps this in the two-phase lifecycle: it commits the `:dispatched` intent
  ;; BEFORE this call and the outcome AFTER, so this method stays purely the
  ;; invocation.
  [ctx effect]
  (let [{:keys [agent-invoker role iteration-eid capability prompt model cwd
                timeout-ms extra]} ctx
        task (cond-> {:role role
                      :iteration-id iteration-eid
                      :capability (or capability (agents/capability-for role))}
               prompt (assoc :prompt prompt)
               model (assoc :model model)
               cwd (assoc :cwd cwd)
               timeout-ms (assoc :timeout-ms timeout-ms)
               extra (assoc :extra extra)
               ;; the effect may carry per-dispatch task overrides
               (:task effect) (merge (:task effect)))]
    {:result (agents/invoke agent-invoker task)
     :task task}))

(defmethod perform-effect :verify-red
  ;; Run RED verification and produce the event the state machine consumes
  ;; (design: RED/GREEN are effects; R-1.2, R-1.3).
  ;;
  ;; RED is verified failing-test evidence attributable to missing required
  ;; behavior: a NON-zero exit confirms RED, a zero exit means the test passed and
  ;; is not RED (R-1.3). Runs the injectable `:command` in `:cwd` via the action
  ;; `fs/run-verification` and classifies the observed result with the pure
  ;; DECISION `fs/red-outcome`:
  ;;
  ;;   :red-verified — the test failed as RED requires (advance to :implement);
  ;;   :red-invalid  — the test passed, so RED is invalid (retry at :test-design);
  ;;   {:error {:code :indeterminate …}} — the command could not be run, so reality
  ;;                is indeterminate and the orchestrator fails closed (R-18.5).
  ;;
  ;; Returns {:event :red-verified|:red-invalid} on a decided outcome, or {:error …}
  ;; (fail closed). The drive loop (8.2) feeds the :event back through
  ;; `core/transition`; recording the verdict as a step outcome is 8.2's job.
  [ctx _effect]
  (let [{:keys [command cwd]} ctx
        result (fs/run-verification command {:cwd cwd})
        outcome (fs/red-outcome result)]
    (if (:error outcome)
      outcome
      {:event outcome})))

(defmethod perform-effect :verify-green
  ;; Run GREEN verification and produce the event the state machine consumes
  ;; (design: RED/GREEN are effects; R-1.2, GREEN glossary).
  ;;
  ;; GREEN is the state in which the relevant test suite PASSES after
  ;; implementation: a zero exit confirms GREEN. Runs the injectable `:command`
  ;; in `:cwd` via the action `fs/run-verification` and classifies the observed
  ;; result with the pure DECISION `fs/green-outcome`:
  ;;
  ;;   :green        — the suite passes (advance to :review-correctness);
  ;;   :red-verified — the suite still fails, so GREEN did not land and RED still
  ;;                   holds (the orchestrator does not advance to review);
  ;;   {:error {:code :indeterminate …}} — the command could not be run (R-18.5).
  ;;
  ;; Returns {:event :green|:red-verified} on a decided outcome, or {:error …}
  ;; (fail closed). The drive loop (8.2) feeds the :event through `core/transition`.
  [ctx _effect]
  (let [{:keys [command cwd]} ctx
        result (fs/run-verification command {:cwd cwd})
        outcome (fs/green-outcome result)]
    (if (:error outcome)
      outcome
      {:event outcome})))

(defmethod perform-effect :verify-capability
  ;; Observe the changes a just-returned invocation produced and DECIDE the
  ;; capability boundary, fail-closed (design, Post-invocation verification;
  ;; R-1.5, R-3.6, R-11.3).
  ;;
  ;; Reads produced changes from the CURRENT filesystem state via the action
  ;; `fs/observe-changes` (each classified :test or :production) and asks the pure
  ;; DECISION `core/capability-violation?` whether any change falls outside the
  ;; role's allowed set — the test-designer touching production, the implementer
  ;; authoring/modifying any test, or a reviewer editing any file. The `:capability`
  ;; descriptor comes from `ctx`, defaulting to the role's descriptor via
  ;; `agents/capability-for`; an unknown/absent capability allows nothing, so any
  ;; change is a violation (fail closed).
  ;;
  ;; Returns {:violation nil :changes <set>} when every change is within boundary,
  ;; or {:violation <first offending change> :changes <set>} otherwise. This method
  ;; only DECIDES; driving the fail-closed transition a violation implies is task
  ;; 8.4's wiring, which consumes this result.
  [ctx effect]
  (let [{:keys [role capability changes cwd]} ctx
        cap (or capability (agents/capability-for role))
        obs-ctx (assoc (or changes (:changes effect) {}) :cwd cwd)
        observed (fs/observe-changes obs-ctx)]
    {:violation (core/capability-violation? cap observed)
     :changes observed}))

;; --- R-8.6–8.9 finding-carry: a valid finding gates minting a new Iteration --
;;
;; A review round that ends WITHOUT approval (a reviewer withholds approval or a
;; reviewer returns REQUEST_CHANGES) may not mint a new Iteration until the
;; failure has been justified: the Orchestrator MUST have durably recorded an
;; R-10-compliant `:finding` (`core/finding-valid?` true) capturing what failed
;; and why (R-8.6, R-8.8) BEFORE `:begin-iteration` mints the new trace-id, and it
;; carries that recorded failure information into the new pass (R-8.9). If no
;; valid finding exists, the round CANNOT mint — the Orchestrator fails closed
;; rather than proceeding without a justified change request.
;;
;; Recording the finding is `record-finding!` (already committed durably during
;; the review round); the R-10 validity rule is the pure `core/finding-valid?`
;; reused here, never re-derived. `select-carried-finding` is the pure DECISION of
;; WHICH recorded finding carries; `mint-iteration!` is the ACTION that fails
;; closed without one and, in ONE mint transaction, creates the new Iteration at
;; `:iteration/revision` 0 and sets BOTH inverse links.

(defn select-carried-finding
  "Pick the entity id of the valid R-10 finding to carry into the newly minted
  Iteration, or nil (calculation; R-8.8, R-8.9).

  Given `findings` as `[[eid finding-map] …]` pairs (the recorded findings with
  their entity ids) and an optional `finding-eid` naming a specific finding to
  carry, returns the ENTITY ID of the finding to carry forward — or nil when none
  qualifies, which is the fail-closed signal (`mint-iteration!` refuses to mint).
  Only a finding the pure DECISION `core/finding-valid?` accepts (all four R-10
  fields present and non-blank) is eligible; the R-10 rule is reused, never
  re-derived. When `finding-eid` is supplied it must name an eligible finding
  (else nil); otherwise the most-recently-recorded eligible finding is chosen (the
  highest entity id among the valid ones), so the latest justified failure seeds
  the new pass. Pure over the queried pairs; no I/O."
  ([findings] (select-carried-finding findings nil))
  ([findings finding-eid]
   (let [valid-eids (->> findings
                         (filter (fn [[_ m]] (core/finding-valid? m)))
                         (map first))]
     (if finding-eid
       (first (filter #(= finding-eid %) valid-eids))
       (last (sort valid-eids))))))

(defn mint-iteration!
  "Mint a NEW Iteration for the slice, carrying a valid finding forward, or fail
  closed (design, Review round ending without approval; R-8.6, R-8.8, R-8.9,
  R-16.1).

  A review-authorized correction mints a NEW Iteration (a new trace-id) whose
  Revision counter starts fresh at 0. This REQUIRES that an R-10-compliant
  `:finding` (`core/finding-valid?` true) has already been durably recorded for
  the prior round (`record-finding!`); it reads those findings with their entity
  ids and asks the pure `select-carried-finding` which one carries. If none
  qualifies the mint FAILS CLOSED — no Iteration is created and no link is set —
  so a round can never mint a new pass without a justified change request
  (R-8.8, R-8.9).

  When a valid finding IS present, one `d/transact!` — one durable LMDB commit,
  the mint transaction — creates the new `:iteration` entity with
  `:iteration/revision` reset to 0 and `:iteration/number` one past the prior
  iteration, and sets BOTH inverse links together: `:iteration/seeded-from-finding`
  on the new Iteration and `:finding/carried-to-iteration` on the carried finding
  (R-8.9). The slice's `:slice/current-iteration` is repointed to the new trace in
  the same commit so the derived active trace never lags the mint. The
  state-transition event into `:test-design` on the NEW trace is appended
  separately via `store/append-transition-event` (its own ACID commit that
  materializes the slice's `:*/state`).

  `spec` is a plain map:
    {:run-eid       <run entity id>        ; required; owns the :event/seq counter
     :slice-eid     <slice entity id>      ; required; the slice being corrected
     :iteration-eid <iteration entity id>  ; required; the PRIOR round's trace
     :from-state    <keyword>              ; optional; origin state (defaults :reconcile)
     :trigger       <keyword>              ; optional; defaults :plan-accepted
     :finding-eid   <finding entity id>}   ; optional; a specific finding to carry
  Returns, on success,
    {:iteration-eid <new entity id> :iteration-id <uuid> :finding-eid <carried>
     :revision 0 :mint-tx <report> :event-tx <report>};
  on a missing valid finding it returns the fail-closed
    {:error {:code :no-valid-finding …}} — nothing is committed. This is an action:
  it reads durable findings and commits the mint + transition durably on disk."
  [conn {:keys [run-eid slice-eid iteration-eid from-state trigger finding-eid]}]
  (let [findings    (d/q '[:find ?f (pull ?f [:finding/problem :finding/evidence
                                              :finding/justification
                                              :finding/required-outcome])
                           :in $ ?iter
                           :where
                           [?r :review/iteration ?iter]
                           [?f :finding/review ?r]]
                         (d/db conn) iteration-eid)
        carried-eid (select-carried-finding findings finding-eid)]
    (if (nil? carried-eid)
      {:error {:code :no-valid-finding
               :message (str "A review round ending without approval must record an "
                             "R-10-compliant finding before a new Iteration is minted "
                             "(R-8.8, R-8.9); none was found — failing closed.")
               :iteration-eid iteration-eid}}
      (let [prior-number (or (:iteration/number
                              (d/pull (d/db conn) [:iteration/number] iteration-eid))
                             0)
            new-id      (random-uuid)
            ;; The mint transaction: the new Iteration at revision 0 and BOTH
            ;; inverse links set together (R-8.9), repointing the active trace.
            mint-tx     (d/transact!
                         conn
                         [{:db/id -1
                           :iteration/id new-id
                           :iteration/slice slice-eid
                           :iteration/number (inc prior-number)
                           :iteration/revision 0
                           :iteration/started-at (java.util.Date.)
                           :iteration/seeded-from-finding carried-eid}
                          {:db/id carried-eid
                           :finding/carried-to-iteration -1}
                          {:db/id slice-eid
                           :slice/current-iteration -1}])
            new-eid     (get (:tempids mint-tx) -1)
            ;; The transition event enters :test-design on the NEW trace (R-16.1).
            event-tx    (store/append-transition-event
                         conn
                         {:run-eid run-eid
                          :slice-eid slice-eid
                          :iteration-eid new-eid
                          :from-state (or from-state :reconcile)
                          :to-state :test-design
                          :trigger (or trigger :plan-accepted)})]
        {:iteration-eid new-eid
         :iteration-id  new-id
         :finding-eid   carried-eid
         :revision      0
         :mint-tx       mint-tx
         :event-tx      event-tx}))))

(defmethod perform-effect :begin-iteration
  ;; Mint a new Iteration that begins at :test-design, carrying a valid finding
  ;; forward, committing the trace via the durable store (design;
  ;; [:reconcile :plan-accepted] -> :begin-iteration; R-16.1, R-8.6, R-8.8, R-8.9).
  ;;
  ;; A review-authorized correction mints a NEW Iteration (a new trace-id) whose
  ;; Revision counter starts fresh at 0; the new Iteration is linked to the
  ;; R-10-compliant finding whose failure information is carried forward
  ;; (:iteration/seeded-from-finding / :finding/carried-to-iteration). Delegates to
  ;; `mint-iteration!`, which REQUIRES a durably-recorded valid finding
  ;; (`core/finding-valid?` via `select-carried-finding`) BEFORE minting and FAILS
  ;; CLOSED when none exists — the round cannot mint a new pass without a justified
  ;; change request (R-8.8, R-8.9). On success the mint transaction sets both
  ;; inverse links and resets `:iteration/revision` to 0, and the transition event
  ;; into :test-design on the new trace is appended (materializing the slice's
  ;; :*/state).
  ;;
  ;; The effect may carry `:finding-eid` to name a specific finding to carry;
  ;; otherwise the latest valid finding recorded for the prior trace is chosen.
  ;;
  ;; Returns {:tx <transition-event report> :iteration-eid <new> :finding-eid
  ;; <carried> :revision 0}, or the fail-closed {:error {:code :no-valid-finding …}}
  ;; (nothing committed) — the drive loop halts on the error rather than advancing.
  [ctx effect]
  (let [{:keys [conn run-eid slice-eid iteration-eid state]} ctx
        minted (mint-iteration!
                conn
                {:run-eid run-eid
                 :slice-eid slice-eid
                 :iteration-eid iteration-eid
                 :from-state state
                 :trigger (:trigger effect :plan-accepted)
                 :finding-eid (:finding-eid effect)})]
    (if (:error minted)
      minted
      {:tx            (:event-tx minted)
       :iteration-eid (:iteration-eid minted)
       :finding-eid   (:finding-eid minted)
       :revision      (:revision minted)})))

(defmethod perform-effect :route-conflict-to-test-designer
  ;; Route a reported test conflict back to the test-designer on the CURRENT
  ;; Iteration, without minting a new trace-id (design; [:implement :test-conflict]
  ;; -> :route-conflict-to-test-designer; R-3.3, R-3.4).
  ;;
  ;; When the implementer reports a :test-conflict it stops and leaves the test
  ;; unchanged (R-3.3); the Orchestrator routes the conflict to the test-designer
  ;; for resolution rather than letting the implementer change the test (R-3.4).
  ;; This stays on the CURRENT iteration (the current :iteration-eid) and mints NO
  ;; new Iteration — distinct from a review-authorized correction, which mints a
  ;; new Iteration via :begin-iteration. Appends the transition event back to
  ;; :test-design on the current trace via the action `store/append-transition-event`
  ;; (one ACID commit, materializing the slice's :*/state).
  ;;
  ;; Returns {:tx <transaction report>}.
  [ctx _effect]
  (let [{:keys [conn run-eid slice-eid iteration-eid state]} ctx]
    {:tx (store/append-transition-event
          conn
          {:run-eid run-eid
           :slice-eid slice-eid
           :iteration-eid iteration-eid
           :from-state (or state :implement)
           :to-state :test-design
           :trigger :test-conflict})}))

(defmethod perform-effect :escalate
  ;; Escalate a Disagreement whose Reconciliation allowance is exhausted, driving
  ;; the slice to :escalated (design; [:reconcile :allowance-exhausted] ->
  ;; :escalated; R-15.4).
  ;;
  ;; :escalated is reached ONLY via allowance exhaustion — never via a test
  ;; conflict (which routes to the test-designer instead). When a Disagreement's
  ;; bounded allowance is exhausted without resolution the Orchestrator stops
  ;; repairs on that subject and escalates to the human (R-15.4).
  ;;
  ;; The escalation is GATED ON EXHAUSTION and records the durable per-Disagreement
  ;; identity that justified it. When the effect names a Disagreement subject
  ;; (`:subject`, with the slice from `:slice-eid`), it delegates to
  ;; `escalate-disagreement!`, which reuses the pure `core/allowance-remaining` to
  ;; confirm the bounded allowance has reached 0 BEFORE escalating: an
  ;; unexhausted Disagreement FAILS CLOSED with `{:error {:code
  ;; :allowance-not-exhausted …}}` and NO transition is committed, so the slice
  ;; only reaches :escalated once repairs on that subject are genuinely spent
  ;; (R-15.4). On exhaustion it finalizes `:disagreement/status :exhausted`
  ;; durably (the per-`:disagreement/id` fact that justifies the escalation,
  ;; R-15.5) in the same recovery path and then appends the transition. When no
  ;; Disagreement subject is supplied — the drive loop already raised the
  ;; `:allowance-exhausted` event upstream — it wires the transition-event append
  ;; directly.
  ;;
  ;; Returns, on a subject-scoped escalation, {:tx <transition report>
  ;; :disagreement-eid <eid> :disagreement-id <uuid> :attempts-used <long>
  ;; :status :exhausted}, or the fail-closed {:error {:code
  ;; :allowance-not-exhausted …}} when the allowance is not yet spent; on a bare
  ;; escalation, {:tx <transition report>}.
  [ctx effect]
  (let [{:keys [conn run-eid slice-eid iteration-eid state]} ctx
        subject (or (:subject effect) (:subject ctx))]
    (if subject
      (escalate-disagreement! conn {:run-eid       run-eid
                                    :slice-eid     slice-eid
                                    :iteration-eid iteration-eid
                                    :subject       subject
                                    :from-state    state})
      {:tx (store/append-transition-event
            conn
            {:run-eid run-eid
             :slice-eid slice-eid
             :iteration-eid iteration-eid
             :from-state (or state :reconcile)
             :to-state :escalated
             :trigger :allowance-exhausted})})))

;; --- Two-phase Step dispatch (design, Orchestrator loop; R-18.1, R-8.4) ------
;;
;; A Step carries a two-phase intent/outcome lifecycle (R-18.1):
;;
;;   Phase 1 — RECORD INTENT (durable, BEFORE spawning): one ACID commit stamps a
;;     fresh `:step` entity at `:step/status :dispatched` with `:step/dispatched-at`,
;;     the `:step/iteration` trace, the `:step/role`, and the `:step/capability`
;;     descriptor. This intent is committed BEFORE the agent runs, so a crash
;;     mid-invocation leaves the Step observably `:dispatched` with no outcome —
;;     `core/step-in-doubt?` — to be reconciled on resume, never assumed complete
;;     nor assumed untouched (R-18.1, R-18.2).
;;   Phase 2 — RECORD OUTCOME (durable, AFTER return): one ACID commit stamps the
;;     SAME `:step` entity with its terminal `:step/status` (`:complete`|`:failed`),
;;     `:step/outcome-at`, and `:step/result-ref`. For an `implementer` /
;;     `test-designer` Step outcome the owning Iteration's `:iteration/revision`
;;     counter is advanced in the SAME transaction (via `store/record-step-outcome`,
;;     which already does this), so the counter can never lag a recorded outcome
;;     (R-8.4, R-17.4). A reviewer Step advances no counter.
;;
;; Between the phases the invocation is dispatched (`:dispatch-agent`) and its
;; produced changes are verified against the role's capability boundary
;; (`:verify-capability`). The AUTHORITATIVE boundary check is this post-return
;; verification, never the advisory sandbox the backend was handed. Driving the
;; fail-closed transition a violation implies is task 8.4's wiring; this dispatch
;; records the Step lifecycle and surfaces the capability decision for the loop.

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
       `AgentInvoker`, returning {:result … :task …}.
    3. VERIFY CAPABILITY — `perform-effect :verify-capability` observes the
       produced changes (from `:changes` in `ctx`/the effect) and DECIDES the
       role's boundary fail-closed. The decision is surfaced, not yet enforced as
       a transition (task 8.4 wires that).
    4. PHASE 2 — `store/record-step-outcome` commits the terminal outcome AFTER
       the return (R-18.1), advancing `:iteration/revision` in the SAME
       transaction for an `implementer`/`test-designer` Step
       (`revision-advancing-role?`), so the counter never lags the outcome (R-8.4).

  The Step's terminal status is `:complete` on an `:ok` invocation with NO
  capability violation, and `:failed` otherwise (a non-`:ok` invocation OR an
  observed violation) — fail closed: a violation or a failed process is never
  recorded as a completed Step.

  `ctx` is the effect context map (see the namespace docstring); the keys read
  here are `:conn`, `:agent-invoker`, `:role`, `:iteration-eid`, `:capability`,
  the dispatch task inputs (`:prompt`/`:model`/`:cwd`/…), and `:changes` (what the
  invocation reported it produced, for capability verification). `opts` may carry
  `:effect` — extra keys merged into the `:dispatch-agent` effect (e.g. a
  per-dispatch `:task` override).

  Returns a map the drive loop consumes:
    {:step-eid   <entity id>        ; the Step recorded (phase 1)
     :step-id    <uuid>
     :role       <keyword>          ; the role dispatched (for role-appropriate enforcement)
     :result     <AgentInvoker result>
     :task       <task dispatched>
     :violation  <offending change|nil>
     :changes    <observed change set>
     :status     :complete | :failed} ; the terminal Step status committed
  The `:role` is surfaced so `enforce-capability` (task 8.4) can map a surfaced
  `:violation` to the role-appropriate fail-closed event without re-consulting
  `ctx`. This is an action: it commits durable Step facts and dispatches an agent."
  ([ctx] (dispatch-step! ctx {}))
  ([ctx {:keys [effect]}]
   (let [{:keys [conn role iteration-eid capability model backend]} ctx
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
         dispatched (perform-effect ctx* (merge {:effect/type :dispatch-agent} effect))
         result (:result dispatched)
         ;; Verify the produced changes against the role's capability boundary
         ;; (authoritative post-return check; the sandbox is advisory).
         cap (perform-effect ctx* {:effect/type :verify-capability})
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

;; --- Fail-closed capability enforcement (design, Capability boundaries and
;; --- fail-closed enforcement; Property 2; R-1.4, R-1.5, R-3.x, R-11.2, R-11.3,
;; --- R-16.6) ------------------------------------------------------------------
;;
;; `dispatch-step!` DECIDES the boundary and SURFACES a `:violation` (the first
;; offending produced change) — it does not itself drive the transition that a
;; violation implies. This is the wiring that ENFORCES it: on an observed
;; violation the orchestrator maps the role to the violation EVENT the transition
;; table names and feeds it through `core/transition`, which fails closed for
;; every role (design, Post-invocation verification step 3):
;;
;;   test-designer wrote production => :production-touched
;;     => {:error {:code :test-designer-wrote-production}}  ; stays outside :implement (R-1.4, R-1.5)
;;   implementer wrote/authored a test => :test-touched
;;     => {:error {:code :implementer-wrote-test}}          ; change rejected (R-3.6)
;;   reviewer edited any file => :reviewer-edited
;;     => {:error {:code :reviewer-attempted-repair}}       ; change rejected (R-11.3)
;;
;; A violation NEVER advances the pipeline: the fail-closed transition carries an
;; `:error` and no `:next-state`, so the machine records the outcome and stays put
;; (the observation is the action, the boundary decision is the calculation, the
;; fail-closed transition is this action). Enforcement leans entirely on the pure
;; `core/transition` table — no error code or rejection rule is duplicated here.

(def ^:private role->violation-event
  "Pure mapping from a role to the violation EVENT its boundary breach raises
  (design, Post-invocation verification step 3).

  Each event is the one the `workflow.core/transitions` table turns into the
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

;; --- The drive loop (design, Orchestrator loop with two-phase dispatch) ------
;;
;; The drive loop is where the pure state machine actually runs against the
;; world: it feeds a recorded EVENT through `core/transition`, PERFORMS the
;; resulting effects via `perform-effect`, and continues until a terminal state
;; is reached or no further effect advances the machine. RED and GREEN are
;; effects, not states: a verification effect returns an `:event` the loop feeds
;; BACK through `core/transition`, so the machine advances only on durably-decided
;; reality.
;;
;; Fail closed is the loop's governing rule: an ABSENT or IN-DOUBT result never
;; advances the machine. A `core/transition` `{:error …}`, a `perform-effect`
;; `{:error …}` (an indeterminate verification, an unknown effect), or an
;; unresolved (nil) event all STOP the loop rather than inferring progress from
;; silence (R-1.3, R-18.5). The loop threads the current `:state` into every
;; effect `ctx` so the transact effects append transitions from the right
;; origin, and records the event history it walked so a caller (and tasks
;; 8.4/8.5/8.8/8.9/8.10) can extend it.

(def ^:private terminal-states
  "The states at which the drive loop halts: the slice is settled and no event
  advances it further (design state set). `:slice-approved` and `:escalated` are
  terminal outcomes; `:reconcile` awaits an external reconciliation decision
  (plan-accepted / allowance-exhausted) rather than an autonomous step, so the
  autonomous loop halts there too and yields control to the caller."
  #{:slice-approved :escalated})

(defn- effect-error
  "Return the fail-closed `:error` an effect result carries, or nil.

  A `perform-effect` result that carries `{:error …}` (an indeterminate
  verification, an unknown effect) must STOP the loop rather than advance it
  (R-18.5). Pure inspection of the result map."
  [result]
  (:error result))

(defn perform-effects
  "Perform each effect in `effects` in order against `ctx`, threading no state
  change between them (design, effects are described by `core/transition`).

  Every effect is interpreted by `perform-effect` with the shared `ctx`. If any
  effect fails closed (`{:error …}`), performing STOPS at that effect and the
  error result is returned so the caller can halt (R-18.5). Returns
  {:results [<effect result> …] :error <first error|nil> :event <event|nil>},
  where `:event` is the last event an effect produced (a verification effect's
  `:event`) — the value the drive loop feeds back through `core/transition`.
  This is an action: it performs the effects, which may spawn/commit."
  [ctx effects]
  (reduce
   (fn [acc effect]
     (let [result (perform-effect ctx effect)]
       (if-let [err (effect-error result)]
         (reduced (-> acc
                      (update :results conj result)
                      (assoc :error err)))
         (cond-> (update acc :results conj result)
           (:event result) (assoc :event (:event result))))))
   {:results [] :error nil :event nil}
   effects))

(defn drive
  "Drive the state machine from `state` on `event` until it settles, performing
  effects along the way (design, Orchestrator loop with two-phase dispatch).

  The loop, per step:

    1. `core/transition` DECIDES the next state (and any effects) for the current
       [state event] pair. An `{:error …}` (an illegal/malformed pair) STOPS the
       loop fail-closed — approval is never inferred from an undecided transition
       (R-1.3).
    2. `perform-effects` INTERPRETS the transition's described effects against the
       world with the current state threaded into `ctx`. An effect that fails
       closed (`{:error …}` — an indeterminate verification, an unknown effect)
       STOPS the loop rather than advancing (R-18.5).
    3. The machine advances to the transition's `:next-state`. If an effect
       produced an `:event` (a verification effect's `:red-verified` / `:green` /
       …), that event is fed BACK through `core/transition` and the loop
       continues; otherwise the loop halts at the new state, yielding control to
       the caller (there is no autonomous next event to apply).

  The loop also halts at a `terminal-states` state (`:slice-approved`,
  `:escalated`) and at `:reconcile` (which awaits an external reconciliation
  decision). An ABSENT or IN-DOUBT result — a nil event, a transition error, or
  an effect error — never advances the machine (fail closed, R-18.5).

  `ctx` is the effect context map (see the namespace docstring); `drive` threads
  the current `:state` into it for each effect so the transact effects append
  transitions from the correct origin. `opts` may carry `:max-steps` (a safety
  bound on the number of transitions, defaulting to a generous cap) so a
  misconfigured cycle cannot loop unboundedly.

  Returns a summary map:
    {:state   <final state>          ; where the machine settled
     :event   <last event applied>
     :history [{:state s :event e :next-state s' :effects [<result> …]} …]
     :error   <fail-closed error|nil>} ; non-nil when the loop stopped fail-closed
  This is an action: it performs effects (dispatch/verify/commit) along the way."
  ([ctx state event] (drive ctx state event {}))
  ([ctx state event {:keys [max-steps] :or {max-steps 100}}]
   (loop [state state
          event event
          history []
          steps 0]
     (let [{:keys [next-state effects error]} (core/transition state event)]
       (cond
         ;; Fail closed: an illegal/malformed [state event] pair never advances.
         error
         {:state state :event event :history history :error error}

         ;; Safety bound: a misconfigured cycle cannot loop unboundedly.
         (>= steps max-steps)
         {:state state :event event :history history
          :error {:code :max-steps-exceeded
                  :message "Drive loop exceeded its step bound without settling."
                  :state state}}

         :else
         (let [ctx* (assoc ctx :state state)
               performed (perform-effects ctx* (or effects []))
               entry {:state state
                      :event event
                      :next-state next-state
                      :effects (:results performed)}
               history' (conj history entry)]
           (cond
             ;; An effect failed closed: stop at the pre-effect state (R-18.5).
             (:error performed)
             {:state state :event event :history history' :error (:error performed)}

             ;; Settled at a terminal / awaiting-caller state: halt cleanly.
             (contains? terminal-states next-state)
             {:state next-state :event event :history history' :error nil}

             ;; An effect produced a feedback event (a verification result): feed
             ;; it back through the machine and continue.
             (:event performed)
             (recur next-state (:event performed) history' (inc steps))

             ;; No autonomous next event: halt at the new state, yielding control
             ;; to the caller (the next event comes from an external dispatch).
             :else
             {:state next-state :event event :history history' :error nil})))))))

;; --- Review rounds, findings, and reconciliation (design, Review round;
;; --- R-7, R-8.1/8.3, R-9, R-10, R-12, R-13, R-14, R-15, R-16.4) --------------
;;
;; A review round runs the correctness review FIRST and the structural review
;; AFTER, always in that order (R-7.1) — even when correctness returns
;; REQUEST_CHANGES, structural still runs (R-7.2). The correctness review's
;; findings and reasoning are fed to the structural dispatch as its input
;; (`:review/inputs-ref`, R-9.1) so structural accounts for concerns correctness
;; already identified. Both reviews judge the SAME Revision counter value in force
;; (`:review/revision-counter`), and an approval binds to that value
;; (`:approval/revision-counter`, R-8.1, R-8.3); the AND-gate holds only when both
;; reviewers approve the same counter value (`core/both-approved?`, R-16.4).
;;
;; ACD separation, unchanged from the rest of this namespace: every DECISION is a
;; pure calculation already living in `workflow.core` — `core/finding-valid?`,
;; `core/both-approved?`, `core/binding-conflict?`,
;; `core/reconsideration-admissible?`, `core/consume-attempt`,
;; `core/allowance-remaining` — and the helpers below are THIN actions that read
;; the durable store (`workflow.store` queries), commit facts in a single
;; `d/transact!` (one LMDB commit, the same envelope `record-step-dispatch!`
;; uses), and delegate the rule to core. No review rule, no allowance arithmetic,
;; no binding/reconsideration logic is duplicated here.
;;
;; These helpers are deliberately small and composable so tasks 8.8 (finding-carry
;; before minting an iteration), 8.9 (resume/reconcile + staleness), and 8.10
;; (escalation on exhausted allowance) build on them without rework.

;; --- reviews & findings -------------------------------------------------------

(defn record-review!
  "Commit one `:review` entity for the current round, bound to the Revision
  counter value in force (design, Review round; R-8.1, R-9.1).

  In one `d/transact!` — one durable LMDB commit — mints a fresh `:review` entity
  stamping its `:review/iteration` correlation trace, the `:review/reviewer`
  (`:correctness` | `:structural`), the `:review/verdict` (`:approve` |
  `:request-changes`), the judged `:review/revision-counter` (the value in force,
  read by the caller from `store/current-revision`), and — for the structural
  review — the `:review/inputs-ref` carrying the correctness findings shown to it
  (R-9.1). Both reviewers judge the SAME counter value for the round (R-8.1); the
  Orchestrator dispatches no counter-advancing Step mid-round (R-8.2), so it does
  not change here.

  `review` is a plain map:
    {:iteration-eid    <iteration entity id>  ; required; the round's trace
     :reviewer         :correctness | :structural
     :verdict          :approve | :request-changes
     :revision-counter <long>                 ; the value in force this round
     :inputs-ref       <string>}              ; optional; correctness findings for structural
  Returns {:review-eid <entity id> :review-id <uuid> :tx <transaction report>}.
  This is an action: it commits durable state on disk."
  [conn {:keys [iteration-eid reviewer verdict revision-counter inputs-ref]}]
  (let [review-id  (random-uuid)
        review-ent (cond-> {:db/id -1
                            :review/id review-id
                            :review/iteration iteration-eid
                            :review/reviewer reviewer}
                     verdict          (assoc :review/verdict verdict)
                     revision-counter (assoc :review/revision-counter revision-counter)
                     inputs-ref       (assoc :review/inputs-ref inputs-ref))
        report     (d/transact! conn [review-ent])]
    {:review-eid (get (:tempids report) -1)
     :review-id  review-id
     :tx         report}))

(defn record-finding!
  "Commit one R-10 `:finding` under a review, stamping validity via the pure
  DECISION `core/finding-valid?` (design R-10; R-8.8).

  In one `d/transact!` — one durable LMDB commit — mints a `:finding` entity
  linked to its owning `:finding/review`, recording the four R-10 components
  (`:finding/problem`, `:finding/evidence`, `:finding/justification`,
  `:finding/required-outcome`), the `:finding/owner`, and the
  `:finding/revision-counter` the finding was recorded against. The
  `:finding/valid?` flag is NOT judged here: it is the pure calculation
  `core/finding-valid?` over the four components (all present and non-blank,
  R-10.2), committed alongside the finding so a query can trust it. A withheld
  approval / REQUEST_CHANGES round MUST record at least one VALID finding before a
  new Iteration is minted (R-8.8, R-8.9), which task 8.8 enforces on top of this.

  `finding` is a plain map:
    {:review-eid       <review entity id>     ; required; the owning review
     :owner            :test-designer | :implementer | :both
     :problem :evidence :justification :required-outcome  <string>   ; R-10 (1..4)
     :revision-counter <long>}                ; optional; counter judged
  Returns {:finding-eid <entity id> :finding-id <uuid> :valid? <bool> :tx <report>};
  `:valid?` is `core/finding-valid?` over the recorded components so the caller can
  fail closed on an invalid finding. This is an action: it commits durable state."
  [conn {:keys [review-eid owner problem evidence justification required-outcome
                revision-counter]}]
  (let [finding-map (cond-> {}
                      problem          (assoc :finding/problem problem)
                      evidence         (assoc :finding/evidence evidence)
                      justification    (assoc :finding/justification justification)
                      required-outcome (assoc :finding/required-outcome required-outcome))
        valid?      (core/finding-valid? finding-map)
        finding-id  (random-uuid)
        finding-ent (cond-> (assoc finding-map
                                   :db/id -1
                                   :finding/id finding-id
                                   :finding/review review-eid
                                   :finding/valid? valid?)
                      owner            (assoc :finding/owner owner)
                      revision-counter (assoc :finding/revision-counter revision-counter))
        report      (d/transact! conn [finding-ent])]
    {:finding-eid (get (:tempids report) -1)
     :finding-id  finding-id
     :valid?      valid?
     :tx          report}))

(defn correctness-findings-input
  "Summarize the correctness findings recorded this round into the
  `:review/inputs-ref` string fed to the structural review (design R-9.1).

  Reads the findings recorded for Iteration `iteration-eid`
  (`store/findings-for-iteration`) and renders a stable, structural-readable
  reference to them — the problem/required-outcome of each — so the structural
  dispatch's input references the concerns correctness already identified
  (R-9.1, Property 7). Only findings from the CURRENT round's correctness review
  are relevant, so the caller passes the correctness review's counter to scope
  them; a nil/absent set yields an empty reference (structural still runs, R-7.2).

  Returns a string (possibly empty) suitable for `:review/inputs-ref`. This is an
  action: it reads the current db value of `conn`; the rendering it delegates to
  is pure."
  [conn iteration-eid]
  (let [findings (store/findings-for-iteration conn iteration-eid)]
    (pr-str (mapv (fn [f]
                    {:finding/id (:finding/id f)
                     :finding/problem (:finding/problem f)
                     :finding/required-outcome (:finding/required-outcome f)
                     :finding/valid? (:finding/valid? f)})
                  findings))))

(defn run-review-round!
  "Run ONE review round: correctness FIRST, structural AFTER, feeding correctness
  findings to structural (design, Review round; R-7.1, R-7.2, R-9.1).

  Dispatches the two reviewers strictly in order and records a review for each:

    1. CORRECTNESS — `dispatch-step!` runs the `:correctness-reviewer` (read-only;
       any file edit fails closed via `enforce-capability`), then `record-review!`
       records its verdict bound to the round's `:review/revision-counter`. The
       caller supplies each verdict (reviewer judgment is manual, not derived
       here); any R-10 findings are recorded via `record-finding!` before
       structural runs so they can be shown to it.
    2. STRUCTURAL — runs ONLY AFTER correctness is recorded (R-7.1); even a
       correctness REQUEST_CHANGES still runs structural (R-7.2). Its dispatch
       input carries the correctness findings via `:review/inputs-ref`
       (`correctness-findings-input`, R-9.1).

  Both reviews judge the SAME `revision-counter` value in force for the round
  (read by the caller from `store/current-revision`), never advancing it mid-round
  (R-8.1, R-8.2). `round` is a plain map:
    {:iteration-eid    <iteration entity id>  ; required; the round's trace
     :revision-counter <long>                 ; the value in force this round
     :correctness      {:verdict kw :ctx <dispatch ctx>}  ; correctness reviewer inputs
     :structural       {:verdict kw :ctx <dispatch ctx>}} ; structural reviewer inputs
  Each `:ctx` is the effect context map `dispatch-step!` consumes (conn, invoker,
  role, iteration-eid, cwd, changes …); the `:role` is set here to the matching
  reviewer. Returns
    {:correctness {:review-eid … :dispatched <dispatch-step! result> :enforced <enforce-capability>}
     :structural  {:review-eid … :dispatched … :enforced … :inputs-ref <string>}
     :order [:correctness :structural]}
  so callers can assert ordering, the fed inputs, and the recorded reviews. This
  is an action: it dispatches agents and commits durable review facts."
  [conn {:keys [iteration-eid revision-counter correctness structural]}]
  ;; PHASE A — correctness FIRST (R-7.1).
  (let [c-ctx        (assoc (:ctx correctness) :conn conn
                            :role :correctness-reviewer :iteration-eid iteration-eid)
        c-dispatched (enforce-capability :review-correctness (dispatch-step! c-ctx))
        c-review     (record-review! conn {:iteration-eid iteration-eid
                                           :reviewer :correctness
                                           :verdict (:verdict correctness)
                                           :revision-counter revision-counter})
        ;; The correctness findings shown to structural are the ones recorded for
        ;; this iteration by the time structural runs (R-9.1); the caller records
        ;; any findings via record-finding! between phases or up front.
        inputs-ref   (correctness-findings-input conn iteration-eid)
        ;; PHASE B — structural AFTER, seeing correctness's findings (R-7.2, R-9.1).
        s-ctx        (assoc (:ctx structural) :conn conn
                            :role :structural-reviewer :iteration-eid iteration-eid)
        s-dispatched (enforce-capability :review-structural (dispatch-step! s-ctx))
        s-review     (record-review! conn {:iteration-eid iteration-eid
                                           :reviewer :structural
                                           :verdict (:verdict structural)
                                           :revision-counter revision-counter
                                           :inputs-ref inputs-ref})]
    {:correctness {:review-eid (:review-eid c-review)
                   :dispatched c-dispatched}
     :structural  {:review-eid (:review-eid s-review)
                   :dispatched s-dispatched
                   :inputs-ref inputs-ref}
     :order       [:correctness :structural]}))

;; --- approvals & the AND-gate -------------------------------------------------

(defn record-approval!
  "Commit one `:approval` bound to the Revision counter value in force (design
  R-8.3; R-16.4).

  Reads the Iteration's current `:iteration/revision` from Datalevin
  (`store/current-revision`) and, in one `d/transact!` — one durable LMDB commit —
  mints an `:approval` entity linked to its owning `:approval/review`, stamping
  the `:approval/reviewer`, the `:approval/verdict`, and the
  `:approval/revision-counter` set to the counter value in force at approval time
  (R-8.3). Binding to the value read now is what lets a later
  implementer/test-designer Step outcome stale the approval by a pure integer
  comparison (`core/approval-valid?`, R-8.4) — the staleness marking itself is
  task 8.9's resume wiring.

  `approval` is a plain map:
    {:review-eid    <review entity id>       ; required; the owning review
     :iteration-eid <iteration entity id>    ; required; to read the current counter
     :reviewer      :correctness | :structural
     :verdict       :approve | :request-changes}
  Returns {:approval-eid <entity id> :approval-id <uuid> :revision-counter <long>
  :tx <report>}; `:revision-counter` is the bound value, surfaced so the caller
  can assert the binding. This is an action: it reads and commits durable state."
  [conn {:keys [review-eid iteration-eid reviewer verdict]}]
  (let [counter     (or (store/current-revision conn iteration-eid) 0)
        approval-id (random-uuid)
        approval-ent (cond-> {:db/id -1
                              :approval/id approval-id
                              :approval/reviewer reviewer
                              :approval/revision-counter counter
                              :approval/at (java.util.Date.)}
                       review-eid (assoc :approval/review review-eid)
                       verdict    (assoc :approval/verdict verdict))
        report      (d/transact! conn [approval-ent])]
    {:approval-eid     (get (:tempids report) -1)
     :approval-id      approval-id
     :revision-counter counter
     :tx               report}))

(defn- approval-by-reviewer
  "Pick the approval recorded by `reviewer` from `approvals` (calculation).

  Returns the first approval whose `:approval/reviewer` matches, or nil. Pure
  filter over the approvals the store query returned; used to feed the pair into
  `core/both-approved?`."
  [approvals reviewer]
  (first (filter #(= reviewer (:approval/reviewer %)) approvals)))

(defn both-approved?
  "DECIDE the AND-gate for Iteration `iteration-eid`: both reviewers approve the
  SAME current Revision counter value (design R-16.4 AND-gate; R-8.1, R-8.3).

  Reads the approvals recorded under the iteration (`store/approvals-for-iteration`)
  and the current `:iteration/revision` (`store/current-revision`), then delegates
  the decision to the pure `core/both-approved?`: it holds only when BOTH the
  correctness approval and the structural approval carry an `:approve` verdict AND
  both bind to the current counter value (R-16.4). If either reviewer has not
  approved, or either approval bound to a stale counter value (a later
  implementer/test-designer outcome advanced it), the gate does not hold.

  Returns the boolean AND-gate result. This is an action only in reading the
  durable facts; the gate rule is the pure `core/both-approved?`. The
  `revision-counter` may be supplied to gate against a specific round value;
  it defaults to the Iteration's current counter."
  ([conn iteration-eid]
   (both-approved? conn iteration-eid (or (store/current-revision conn iteration-eid) 0)))
  ([conn iteration-eid revision-counter]
   (let [approvals (store/approvals-for-iteration conn iteration-eid)]
     (core/both-approved? (approval-by-reviewer approvals :correctness)
                          (approval-by-reviewer approvals :structural)
                          revision-counter))))

;; --- repair proposals: one plan accepted by both, amendments are new ----------

(defn propose-repair!
  "Commit one repair `:proposal`, rejecting a plan that contradicts a binding
  decision (design R-12.1, R-13.2).

  Before committing, reads the slice's BINDING decisions
  (`store/decisions-for-slice` with `:binding`) and asks the pure DECISION
  `core/binding-conflict?` whether the proposed outcome would contradict any of
  them; if so, the proposal is REJECTED (not committed) and the conflicting
  decision is surfaced — a repair that contradicts a binding decision is refused
  until the outcome is formally replaced (R-13.2, R-13.3). Otherwise, in one
  `d/transact!` — one durable LMDB commit — it mints a `:proposal` entity scoped
  to `:proposal/slice`, stamping `:proposal/subject`, `:proposal/body`,
  `:proposal/iteration`, `:proposal/resolution :open`, and — for an amendment —
  `:proposal/supersedes` linking the superseded plan (a NEW entity; acceptance
  does NOT carry forward, R-12.3, R-12.4).

  `proposal` is a plain map:
    {:slice-eid     <slice entity id>        ; required; scope
     :subject       <string>                 ; the subject the plan acts on
     :body          <string>                 ; the outcome it would produce
     :iteration-eid <iteration entity id>    ; optional; the trace
     :supersedes    <prior proposal entity>} ; optional; amendment link (new entity)
  Returns {:proposal-eid <entity id> :proposal-id <uuid> :tx <report>} on a
  committed plan, or {:conflict <binding decision>} when the plan is rejected
  (nothing committed). This is an action: it reads decisions and commits durable
  state."
  [conn {:keys [slice-eid subject body iteration-eid supersedes]}]
  (let [binding (store/decisions-for-slice conn slice-eid :binding)
        conflict (core/binding-conflict? {:proposal/subject subject
                                          :proposal/body body}
                                         binding)]
    (if conflict
      {:conflict conflict}
      (let [proposal-id  (random-uuid)
            proposal-ent (cond-> {:db/id -1
                                  :proposal/id proposal-id
                                  :proposal/slice slice-eid
                                  :proposal/subject subject
                                  :proposal/body body
                                  :proposal/resolution :open}
                           iteration-eid (assoc :proposal/iteration iteration-eid)
                           supersedes    (assoc :proposal/supersedes supersedes))
            report       (d/transact! conn [proposal-ent])]
        {:proposal-eid (get (:tempids report) -1)
         :proposal-id  proposal-id
         :tx           report}))))

(defn accept-proposal!
  "Record a reviewer's acceptance of a repair `:proposal` (design R-12.2).

  In one `d/transact!` — one durable LMDB commit — adds `reviewer` to the
  proposal's many-valued `:proposal/accepted-by`. Because an amended plan is a
  NEW `:proposal` entity (minted by `propose-repair!` with `:proposal/supersedes`),
  its `:proposal/accepted-by` starts EMPTY and the superseded plan's acceptances
  do NOT carry forward — both reviewers must accept the amendment afresh (R-12.3,
  R-12.4). Whether the plan is thereby authorized (accepted by BOTH reviewers) is
  the pure `repair-authorized?` decision, not recorded here.

  Returns {:proposal-eid <entity id> :tx <report>}. This is an action: it commits
  durable state on disk."
  [conn {:keys [proposal-eid reviewer]}]
  (let [report (d/transact! conn [{:db/id proposal-eid
                                   :proposal/accepted-by reviewer}])]
    {:proposal-eid proposal-eid
     :tx           report}))

(defn repair-authorized?
  "True iff `proposal` is accepted by BOTH reviewers (design R-12.2; Property 9).

  Repair is authorized only by a SINGLE plan accepted by both the correctness and
  structural reviewers — pure check over the proposal's `:proposal/accepted-by`
  set. An amended plan is a new entity whose acceptances start empty (R-12.3), so
  a superseded plan's acceptances never authorize the amendment (R-12.4). Pure
  calculation over the queried proposal map; no I/O."
  [proposal]
  (let [accepted (set (:proposal/accepted-by proposal))]
    (and (contains? accepted :correctness)
         (contains? accepted :structural))))

;; --- reconsideration admissibility (R-14) -------------------------------------

(defn reconsideration-admissible?
  "DECIDE whether a reconsideration `request` is admissible (design R-14.1, R-14.2).

  Thin pass-through to the pure DECISION `core/reconsideration-admissible?`: true
  iff the request identifies a specific decision (`:reconsideration/decision-id`)
  AND supplies non-blank new evidence (`:reconsideration/new-evidence`). Admissible
  is NOT authorized — the prior decision stays binding until a replacement is
  accepted through reconciliation (R-14.3), which the reconciliation flow decides.
  Pure calculation; no I/O. Exposed here so the reconciliation caller enforces
  R-14 without reaching into core directly."
  [request]
  (core/reconsideration-admissible? request))

;; --- per-Disagreement reconciliation allowance --------------------------------

(defn- ensure-disagreement!
  "Find or create the `:disagreement` for `slice-eid` + `subject` (action; R-15.3).

  Locates an existing disagreement by its DESCRIPTIVE slice + subject attributes
  (`store/find-disagreement`) so a reopened concern keeps its intact
  `:disagreement/attempts-used` allowance across rounds and restarts (R-15.3) —
  there is NO normalized/derived key, only the stable `:disagreement/id`. When
  none exists, mints a fresh `:disagreement` at `:attempts-used 0`,
  `:status :open` in one `d/transact!`. Returns the disagreement as a plain map
  carrying its `:db/id`. This is an action: it reads and may commit durable state."
  [conn slice-eid subject]
  (if-let [existing (store/find-disagreement conn slice-eid subject)]
    (let [eid (d/q '[:find ?dg . :in $ ?id :where [?dg :disagreement/id ?id]]
                   (d/db conn) (:disagreement/id existing))]
      (assoc existing :db/id eid))
    (let [dg-id  (random-uuid)
          report (d/transact! conn [{:db/id -1
                                     :disagreement/id dg-id
                                     :disagreement/slice slice-eid
                                     :disagreement/subject subject
                                     :disagreement/attempts-used 0
                                     :disagreement/status :open}])]
      {:db/id (get (:tempids report) -1)
       :disagreement/id dg-id
       :disagreement/slice slice-eid
       :disagreement/subject subject
       :disagreement/attempts-used 0
       :disagreement/status :open})))

(defn consume-disagreement-allowance!
  "Consume one Reconciliation allowance attempt for a Disagreement, durably keyed
  to its identity (design R-15.2, R-15.5; R-15.1).

  Finds (or creates) the Disagreement for `slice-eid` + `subject`
  (`ensure-disagreement!`, R-15.3), applies the pure DECISION `core/consume-attempt`
  (increment `:disagreement/attempts-used` regardless of accept/reject, R-15.2),
  and commits the new count in one `d/transact!` keyed to the stable
  `:disagreement/id` (R-15.5). The remaining allowance is the pure
  `core/allowance-remaining` over the updated count (`(max 0 (- 2 used))`, never
  below 0, never above 2, R-15.1); when it reaches 0 the allowance is exhausted
  and repairs on that subject stop, escalating to the human — task 8.10 wires the
  `:escalate` transition on top of this signal (R-15.4).

  `disagreement` is a plain map:
    {:slice-eid <slice entity id>  ; required; descriptive scope
     :subject   <string>}          ; required; descriptive subject
  Returns {:disagreement-eid <entity id> :disagreement-id <uuid>
  :attempts-used <long> :remaining <long> :exhausted? <bool> :tx <report>}. This
  is an action: it reads, applies the pure decision, and commits durable state."
  [conn {:keys [slice-eid subject]}]
  (let [dg        (ensure-disagreement! conn slice-eid subject)
        consumed  (core/consume-attempt dg)
        used      (:disagreement/attempts-used consumed)
        remaining (core/allowance-remaining consumed)
        exhausted? (zero? remaining)
        report    (d/transact! conn [(cond-> {:db/id (:db/id dg)
                                              :disagreement/attempts-used used}
                                       exhausted? (assoc :disagreement/status :exhausted))])]
    {:disagreement-eid (:db/id dg)
     :disagreement-id  (:disagreement/id dg)
     :attempts-used    used
     :remaining        remaining
     :exhausted?       exhausted?
     :tx               report}))

(defn escalate-disagreement!
  "Escalate a Disagreement to the human ONLY once its bounded Reconciliation
  allowance is exhausted, recording the durable per-`:disagreement/id` status
  that justifies it, or fail closed (design, Reconciliation allowance exhaustion
  -> escalation; R-15.4, R-15.5).

  A Disagreement's bounded allowance is one initial proposal plus one revision
  (`core/reconciliation-allowance`, R-15.1); when it is spent WITHOUT resolution
  the Orchestrator stops repairs on that subject and escalates to the human
  (R-15.4). `:escalated` is reached this way and ONLY this way — never via a
  test conflict, which routes back to the `test-designer` on the current
  Iteration (`:route-conflict-to-test-designer`) and is not a
  reconciliation-allowance event at all.

  Locates (or recognizes) the Disagreement by its descriptive slice + subject
  (`ensure-disagreement!`, R-15.3) so its intact `:disagreement/attempts-used`
  count decides the outcome, then GATES on the pure DECISION
  `core/allowance-remaining` reaching 0 — the same calculation
  `consume-disagreement-allowance!` uses, reused here, never re-derived:

    * remaining > 0 — the allowance is NOT spent, so this is NOT an exhaustion
      escalation: FAIL CLOSED with `{:error {:code :allowance-not-exhausted …}}`,
      committing NOTHING (no `:exhausted` status, no transition). The slice never
      reaches :escalated on a Disagreement that still has repairs left (R-15.4).
    * remaining = 0 — the allowance is exhausted. In one `d/transact!` (one
      durable LMDB commit) the Disagreement's `:disagreement/status` is finalized
      `:exhausted` — the durable per-`:disagreement/id` fact that records the
      spent allowance / Disagreement identity justifying the escalation (R-15.5)
      — and then the transition event into :escalated is appended on the current
      slice via `store/append-transition-event` (its own ACID commit,
      materializing the slice's :*/state) with `:trigger :allowance-exhausted`.

  `escalation` is a plain map:
    {:run-eid       <run entity id>        ; required; owns the :event/seq counter
     :slice-eid     <slice entity id>      ; required; the Disagreement's scope
     :iteration-eid <iteration entity id>  ; required; the current trace
     :subject       <string>               ; required; the Disagreement's subject
     :from-state    <keyword>}             ; optional; origin state (defaults :reconcile)
  Returns, on an exhausted allowance,
    {:tx <transition report> :disagreement-eid <eid> :disagreement-id <uuid>
     :attempts-used <long> :status :exhausted};
  on a not-yet-spent allowance, the fail-closed
    {:error {:code :allowance-not-exhausted …} :disagreement-eid <eid>
     :remaining <long>} — nothing committed. This is an action: it reads the
  durable allowance, applies the pure gate, and commits the status + transition."
  [conn {:keys [run-eid slice-eid iteration-eid subject from-state]}]
  (let [dg        (ensure-disagreement! conn slice-eid subject)
        remaining (core/allowance-remaining dg)]
    (if (pos? remaining)
      ;; The allowance is NOT exhausted: escalation is not warranted. Fail closed
      ;; and commit nothing — repairs on this subject still have attempts left.
      {:error {:code :allowance-not-exhausted
               :message (str "Escalation is reached ONLY when a Disagreement's "
                             "Reconciliation allowance is exhausted (R-15.4); this "
                             "Disagreement still has attempts remaining — failing closed.")
               :subject subject}
       :disagreement-eid (:db/id dg)
       :remaining        remaining}
      ;; The allowance is exhausted: finalize the durable per-id :exhausted status
      ;; (the fact that justifies the escalation, R-15.5), then transition to
      ;; :escalated (R-15.4).
      (let [used      (:disagreement/attempts-used dg)
            _status-tx (d/transact! conn [{:db/id (:db/id dg)
                                           :disagreement/status :exhausted}])
            event-tx  (store/append-transition-event
                       conn
                       {:run-eid run-eid
                        :slice-eid slice-eid
                        :iteration-eid iteration-eid
                        :from-state (or from-state :reconcile)
                        :to-state :escalated
                        :trigger :allowance-exhausted})]
        {:tx               event-tx
         :disagreement-eid (:db/id dg)
         :disagreement-id  (:disagreement/id dg)
         :attempts-used    used
         :status           :exhausted}))))

;; --- Resume / reconcile + resume-staleness (design, Resume (b): a bare
;; --- interruption; R-18.2, R-18.3, R-18.4, R-18.5, R-8.5, R-8.7) -------------
;;
;; A process interruption is NOT a pipeline error and yields no review outcome
;; (design, Resume (b)). On resume the Orchestrator does THREE things, all on the
;; SAME trace-id — it never mints a correction Iteration merely because a process
;; died and never restarts at the `test-designer` (restarting at :test-design is
;; reserved for a review-authorized correction, case (a), via :begin-iteration):
;;
;;   1. RECONCILE in-doubt Steps against OBSERVABLE REALITY (R-18.2, R-18.3). A
;;      Step committed at `:step/status :dispatched` with no recorded outcome is
;;      `core/step-in-doubt?` — neither assumed complete nor assumed untouched. Its
;;      ACTUAL outcome is recovered by re-running the relevant verification and
;;      inspecting produced artifacts on disk (`fs/recover-step-outcome`, which
;;      composes `fs/run-verification` + the pure `fs/red-outcome`/`fs/green-outcome`
;;      decisions + `fs/artifacts-present?`). Because agent effects are
;;      idempotent/verifiable, re-checking is safe (R-18.4). Only AFTER the outcome
;;      is recovered is it recorded (`store/record-step-outcome`, phase 2 of the
;;      two-phase lifecycle), advancing the Revision counter for an
;;      implementer/test-designer Step in the SAME transaction (R-8.4). If reality
;;      is INDETERMINATE — the verification could not be run, or a required
;;      artifact is absent — recovery returns `{:error …}` and the Orchestrator
;;      FAILS CLOSED: no outcome is recorded, the Step stays `:dispatched`, and the
;;      resume halts rather than assuming the Step landed or was untouched (R-18.5).
;;
;;   2. Separately, decide APPROVAL STALENESS by a pure Datalevin-counter
;;      comparison (R-8.5). It reads the Iteration's current `:iteration/revision`
;;      (`store/current-revision`) and each recorded approval's bound
;;      `:approval/revision-counter` (`store/approvals-for-iteration`), and asks the
;;      pure `core/approval-valid?` whether the bound value still equals the current
;;      counter. When an implementer/test-designer Step outcome advanced the counter
;;      beyond the approval's bound value — INCLUDING one just recorded by step 1's
;;      reconciliation — the approval is treated as unapproved and marked
;;      `:approval/stale?` true with `:approval/stale-reason :revision-advanced`. No
;;      filesystem read decides Revision identity; it is an integer comparison over
;;      a Datalevin-read value.
;;
;;   3. RECORD the staleness as a NON-reviewer fact (R-8.7). When resume treats a
;;      prior approval as unapproved PURELY because the Revision counter advanced,
;;      the Orchestrator itself records `:approval/stale-reason :revision-advanced`
;;      WITHOUT requiring any reviewer statement (contrast R-8.6, where a reviewer
;;      WITHHOLDING approval must state what failed and why). The staleness marking
;;      is the Orchestrator's own bookkeeping, not a reviewer verdict, so it commits
;;      no `:finding` and no reviewer statement.
;;
;; ACD separation, unchanged: every DECISION is a pure calculation reused, never
;; re-derived — `core/step-in-doubt?` classifies the Step, `fs/red-outcome` /
;; `fs/green-outcome` classify the recovered verification, `core/approval-valid?`
;; decides staleness by integer comparison. The helpers below are THIN actions
;; that read the durable store, reconcile against the filesystem, and commit facts
;; in single `d/transact!` commits (the same envelope the rest of this namespace
;; uses). No recovery rule, no staleness rule is duplicated here.

(defn- in-doubt-steps
  "Query the in-doubt Steps recorded for Iteration `iteration-eid` (action; R-18.2).

  Reads every `:step` whose `:step/iteration` is `iteration-eid`, projecting the
  fields resume needs (`:step/id`, `:step/role`, `:step/capability`,
  `:step/status`), and keeps only those the pure `core/step-in-doubt?` declares in
  doubt — a Step committed `:dispatched` (intent BEFORE the agent ran) with no
  terminal outcome recorded after it returned. Returns a vector of `[eid step-map]`
  pairs (empty when none), so the caller can reconcile each and record its outcome
  against the SAME `:step/id` entity. The in-doubt rule is the pure predicate,
  reused not re-derived. This is an action: it reads the current db value of
  `conn`; the classification it delegates to is pure."
  [conn iteration-eid]
  (->> (d/q '[:find ?s (pull ?s [:step/id :step/role :step/capability :step/status])
              :in $ ?iter
              :where [?s :step/iteration ?iter]]
            (d/db conn) iteration-eid)
       (filter (fn [[_ step]] (core/step-in-doubt? step)))
       (mapv (fn [[eid step]] [eid step]))))

(defn reconcile-step!
  "Reconcile ONE in-doubt Step against observable reality, then record its
  recovered outcome — or fail closed (design, Resume (b); R-18.2, R-18.3, R-18.4,
  R-18.5).

  Given the in-doubt Step's entity id and role plus the `:phase` (`:red` |
  `:green`) it owed and the recovery `ctx` (`:command`, optional `:artifact-paths`,
  optional `:cwd`), recovers what the interrupted agent actually did to the code
  via `fs/recover-step-outcome` — re-running the verification and inspecting the
  produced artifacts on disk. The recovery returns either the pure
  `fs/red-outcome`/`fs/green-outcome` event keyword or a fail-closed `{:error …}`
  when reality is indeterminate (the command could not be run) or a required
  artifact is absent (R-18.5).

  On an INDETERMINATE recovery the Step is NOT recorded — it stays `:dispatched`
  (still `core/step-in-doubt?`) and the returned `:error` signals the caller to
  halt the resume fail-closed, never assuming the Step complete nor untouched
  (R-18.2, R-18.5). On a RECOVERED outcome the actual result is now observable, so
  phase 2 of the two-phase lifecycle is completed: `store/record-step-outcome`
  stamps the SAME Step entity `:complete` with the recovered event as its
  `:step/result-ref`, advancing the Iteration's Revision counter in the SAME
  transaction for an implementer/test-designer Step (`revision-advancing-role?`,
  R-8.4) so the counter never lags the recovered outcome — which is exactly what
  can stale a prior approval (step 2 below).

  `step` is a plain map:
    {:conn          <datalevin conn>       ; required
     :step-eid      <step entity id>       ; required; the in-doubt Step
     :iteration-eid <iteration entity id>  ; required; the trace + counter owner
     :role          <keyword>              ; required; decides counter advance
     :phase         :red | :green          ; required; which verification it owed
     :command       [\"…\" …]               ; injectable verification command
     :artifact-paths [\"…\" …]              ; optional; artifacts it should have produced
     :cwd           <path>}                ; optional; working dir for paths/command
  Returns, on a recovered outcome,
    {:step-eid <eid> :event <recovered event> :status :complete :tx <report>};
  on an indeterminate/missing-artifact recovery, the fail-closed
    {:step-eid <eid> :error {…}} — nothing recorded, the Step left in doubt. This
  is an action: it reconciles against the filesystem and may commit the outcome."
  [{:keys [conn step-eid iteration-eid role phase command artifact-paths cwd]}]
  (let [recovered (fs/recover-step-outcome phase {:command command
                                                  :artifact-paths artifact-paths
                                                  :cwd cwd})]
    (if (:error recovered)
      ;; Reality is indeterminate / a required artifact is absent: fail closed.
      ;; No outcome is recorded; the Step stays :dispatched (still in doubt).
      {:step-eid step-eid :error (:error recovered)}
      ;; The actual outcome is observable: complete phase 2, advancing the
      ;; Revision counter for an implementer/test-designer Step in the SAME tx.
      (let [tx (store/record-step-outcome
                conn
                {:step-eid          step-eid
                 :iteration-eid     iteration-eid
                 :status            :complete
                 :result-ref        (pr-str recovered)
                 :advance-revision? (revision-advancing-role? role)})]
        {:step-eid step-eid
         :event    recovered
         :status   :complete
         :tx        tx}))))

(defn- stale-approval-tx
  "Datalevin tx-data marking `approval` stale for `:revision-advanced`, or nil
  when it still holds (calculation over the queried approval + current counter).

  Delegates the DECISION to the pure `core/approval-valid?`: an approval whose
  bound `:approval/revision-counter` still equals `current-counter` is honored (no
  tx), while one bound to a superseded value is treated as unapproved and stamped
  `:approval/stale?` true with `:approval/stale-reason :revision-advanced` — the
  Orchestrator's own non-reviewer fact (R-8.7). An approval already marked stale
  needs no re-marking. The entity is addressed by its unique `:approval/id`
  (`:db.unique/identity`), so the stamp upserts onto the existing entity without
  needing its `:db/id`. Pure over the queried approval map; the caller commits the
  returned tx-data."
  [approval current-counter]
  (when (and (not (core/approval-valid? approval current-counter))
             (not (:approval/stale? approval)))
    {:approval/id           (:approval/id approval)
     :approval/stale?       true
     :approval/stale-reason :revision-advanced}))

(defn mark-stale-approvals!
  "Mark every approval whose bound Revision counter is superseded as stale, as a
  non-reviewer fact (design, Resume (b); R-8.5, R-8.7).

  Reads the Iteration's current `:iteration/revision` (`store/current-revision`)
  and every recorded approval (`store/approvals-for-iteration`), then asks the pure
  `core/approval-valid?` whether each still binds to the current counter value. An
  approval bound to a value an implementer/test-designer Step outcome has since
  advanced past — including an outcome just recovered by `reconcile-step!` — is
  treated as unapproved and, in one `d/transact!` (one durable LMDB commit),
  stamped `:approval/stale?` true with `:approval/stale-reason :revision-advanced`.
  This staleness is recorded by the Orchestrator ITSELF, WITHOUT any reviewer
  statement (R-8.7) — it is bookkeeping, not a reviewer verdict — so no `:finding`
  and no reviewer fact is written. The comparison is a pure integer calculation
  over a Datalevin-read counter; NO filesystem read decides Revision identity.
  Approvals that still hold, and approvals already marked stale, are left
  untouched.

  Returns
    {:current-counter <long> :staled [<approval entity id> …] :tx <report|nil>};
  `:staled` lists the entity ids newly marked stale (empty when every approval
  still holds), and `:tx` is the commit report (nil when nothing changed). This is
  an action: it reads durable facts and commits the staleness markers."
  [conn iteration-eid]
  (let [current   (or (store/current-revision conn iteration-eid) 0)
        approvals (store/approvals-for-iteration conn iteration-eid)
        tx-data   (vec (keep #(stale-approval-tx % current) approvals))
        tx        (when (seq tx-data) (d/transact! conn tx-data))
        ;; Resolve the entity ids of the approvals just marked stale (addressed
        ;; in the tx by unique :approval/id) so the caller can name them.
        staled    (mapv (fn [{:keys [approval/id]}]
                          (d/q '[:find ?a . :in $ ?id
                                 :where [?a :approval/id ?id]]
                               (d/db conn) id))
                        tx-data)]
    {:current-counter current
     :staled          staled
     :tx              tx}))

(defn resume-iteration!
  "Resume an interrupted Run on the SAME trace: reconcile in-doubt Steps against
  observable reality, then record resume-staleness — never restarting at
  test-design (design, Resume (b); R-18.2, R-18.3, R-18.4, R-18.5, R-8.5, R-8.7).

  A bare process interruption is not a pipeline error and yields no review outcome
  (design, Resume (b)): this resumes the CURRENT Iteration (`iteration-eid`) in
  place. It does NOT mint a new Iteration and does NOT restart at the
  `test-designer` — restarting at :test-design is reserved for a review-authorized
  correction (case (a), via `:begin-iteration`), never for an interruption. The
  resume proceeds in two ordered phases:

    1. RECONCILE — for every in-doubt Step (`in-doubt-steps`, `core/step-in-doubt?`)
       run `reconcile-step!`, which recovers the Step's actual outcome from
       observable reality (`fs/recover-step-outcome`: re-run RED/GREEN, inspect
       artifacts) and records it (`store/record-step-outcome`, advancing the counter
       for implementer/test-designer). If ANY reconciliation is indeterminate /
       missing an artifact it returns `{:error …}` and the resume STOPS fail-closed
       BEFORE marking staleness — the Step stays in doubt and reality is never
       assumed (R-18.5). The caller supplies each in-doubt Step's recovery inputs
       (phase + command + artifacts + cwd) via `:recovery` keyed by step entity id;
       a Step with no supplied recovery inputs cannot be reconciled and is left in
       doubt (fail closed).
    2. STALE — with reconciled outcomes recorded (and the counter possibly
       advanced), `mark-stale-approvals!` marks any approval bound to a superseded
       counter value `:approval/stale?` / `:revision-advanced`, a non-reviewer fact
       (R-8.5, R-8.7).

  `resume` is a plain map:
    {:iteration-eid <iteration entity id>   ; required; the trace to resume in place
     :recovery      {<step-eid> {:phase kw :command […] :artifact-paths […] :cwd path
                                 :role kw}}} ; per-in-doubt-Step recovery inputs
  For each in-doubt Step the recovery map supplies the `:phase` it owed and the
  `:command`/`:artifact-paths`/`:cwd` to reconcile it; `:role` defaults to the
  recorded `:step/role`. Returns
    {:iteration-eid <same eid>          ; the SAME trace — no new Iteration minted
     :reconciled [<reconcile-step! result> …]  ; one per in-doubt Step attempted
     :staleness  <mark-stale-approvals! result|nil>  ; nil when reconciliation failed closed
     :error      <first reconciliation error|nil>}   ; non-nil => resume stopped fail-closed
  On a fail-closed reconciliation `:staleness` is nil (staleness is not marked
  until every in-doubt Step is reconciled) and `:error` carries the first
  indeterminate/missing-artifact error. This is an action: it reconciles against
  the filesystem and commits recovered outcomes + staleness markers durably."
  [conn {:keys [iteration-eid recovery]}]
  (let [steps      (in-doubt-steps conn iteration-eid)
        ;; Reconcile each in-doubt Step in turn; STOP fail-closed on the first
        ;; indeterminate recovery so staleness is never marked over an unresolved
        ;; Step (R-18.5). A Step with no supplied recovery inputs cannot be
        ;; reconciled and fails closed.
        reconciled (reduce
                    (fn [acc [step-eid step]]
                      (let [inputs (get recovery step-eid)
                            result (if (nil? inputs)
                                     {:step-eid step-eid
                                      :error {:code    :no-recovery-inputs
                                              :message (str "No recovery inputs supplied for an "
                                                            "in-doubt Step; cannot reconcile against "
                                                            "observable reality — failing closed.")
                                              :step-eid step-eid}}
                                     (reconcile-step!
                                      (merge {:conn          conn
                                              :step-eid      step-eid
                                              :iteration-eid iteration-eid
                                              :role          (:step/role step)}
                                             inputs)))]
                        (if (:error result)
                          (reduced (-> acc
                                       (update :results conj result)
                                       (assoc :error (:error result))))
                          (update acc :results conj result))))
                    {:results [] :error nil}
                    steps)]
    (if (:error reconciled)
      {:iteration-eid iteration-eid
       :reconciled    (:results reconciled)
       :staleness     nil
       :error         (:error reconciled)}
      {:iteration-eid iteration-eid
       :reconciled    (:results reconciled)
       :staleness     (mark-stale-approvals! conn iteration-eid)
       :error         nil})))
