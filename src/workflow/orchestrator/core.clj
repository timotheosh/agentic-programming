(ns workflow.orchestrator.core
  "The `perform-effect` multimethod: where the pure state machine meets the
  world (design, Orchestrator effects and drive loop). Split out of the
  original monolithic `workflow.orchestrator` (user-directed reorganization)
  as the namespace the project's own rule requires: a `defmulti` and ALL of
  its `defmethod`s live together in one namespace.

  `workflow.rules.core` decides — `transition` returns a next state plus a
  *description of effects* and facts to persist — but it performs no I/O.
  This namespace is the ACTION edge that INTERPRETS that description: it
  dispatches agents (`workflow.agents`), runs and classifies verifications
  and observes produced changes (`workflow.fs`), and commits durable facts
  (`workflow.store`). It is the single boundary where calculations meet
  actions.

      (defmulti perform-effect (fn [ctx effect] (:effect/type effect)))

  `perform-effect` dispatches on the effect's `:effect/type`. The effect
  descriptions come straight out of the `workflow.rules.core/transitions`
  table (e.g. `{:effect/type :begin-iteration}`, `{:effect/type
  :route-conflict-to-test-designer}`) plus the ones the drive loop
  (`workflow.orchestrator.drive`) raises itself (`:dispatch-agent`,
  `:verify-red`, `:verify-green`, `:verify-capability`, `:escalate`). RED and
  GREEN are effects, not states: `:verify-red` produces the `:red-verified` /
  `:red-invalid` event and `:verify-green` produces the `:green` event, which
  the drive loop feeds back into `core/transition` (design, Multimethod
  dispatch on effects).

  Each method is a THIN action wrapper. The DECISIONS it leans on are pure
  calculations living elsewhere — `fs/red-outcome` / `fs/green-outcome`
  classify a verification result, `core/capability-violation?` decides the
  boundary — and this namespace only performs the effects around them (spawn,
  read filesystem, commit Datalevin). No business rule is duplicated here.

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

  Nothing here derives a Revision from files: Revision identity is the
  Datalevin integer counter (R-8), advanced only by
  `workflow.store/record-step-outcome`.

  Return-value contract `workflow.orchestrator.drive` relies on:

    * The verification effects (:verify-red, :verify-green) return an EVENT the
      drive loop feeds back into `core/transition`:
        {:event :red-verified|:red-invalid|:green}       ; advance the machine
        {:error {…}}                                     ; fail closed (R-1.3, R-18.5)
    * :verify-capability returns the boundary decision:
        {:violation nil    :changes <set>}               ; every change within boundary
        {:violation <change> :changes <set>}             ; the first offending change (fail closed)
    * :dispatch-agent returns the AgentInvoker result plus the task dispatched:
        {:result {…AgentInvoker result…} :task {…}}
    * The transact effects (:begin-iteration,
      :route-conflict-to-test-designer, :escalate) return the store transaction
      report they committed:
        {:tx <datalevin tx report>}"
  (:require [workflow.rules.core :as core]
            [workflow.fs :as fs]
            [workflow.store :as store]
            [workflow.agents :as agents]
            [workflow.orchestrator.disagreement :as disagreement]
            [datalevin.core :as d]))

(defmulti perform-effect
  "Interpret one effect description against the world, dispatched on its
  `:effect/type` (design, Multimethod dispatch on effects).

  `ctx` is the effect context map (see the namespace docstring): the durable
  connection, the agent invoker, the run/slice/iteration/step entity ids, the
  role/capability, the verification command, and the working directory. `effect`
  is the effect description, either taken verbatim from the
  `workflow.rules.core/transitions` table (carrying just `:effect/type`) or raised by
  the drive loop with extra keys. Each method returns a plain result map (see the
  namespace docstring's return-value contract) — never performing the transition
  itself; the drive loop feeds a returned `:event` back through `core/transition`.

  This is an ACTION: methods spawn agents, run verifications, read the
  filesystem, and commit Datalevin facts. The `:default` method fails closed on
  an unknown effect type rather than silently succeeding."
  (fn [_ctx effect] (:effect/type effect)))

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
  ;; Returns {:result <AgentInvoker result> :task <task dispatched>}. The
  ;; two-phase lifecycle (`workflow.orchestrator.dispatch/dispatch-step!`) wraps
  ;; this: it commits the `:dispatched` intent BEFORE this call and the outcome
  ;; AFTER, so this method stays purely the invocation.
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
  ;; (fail closed). The drive loop feeds the :event back through
  ;; `core/transition`; recording the verdict as a step outcome is the
  ;; two-phase dispatch's job.
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
  ;; (fail closed). The drive loop feeds the :event through `core/transition`.
  [ctx _effect]
  (let [{:keys [command cwd]} ctx
        result (fs/run-verification command {:cwd cwd})
        outcome (fs/green-outcome result)]
    (if (:error outcome)
      outcome
      {:event outcome})))

(defmethod perform-effect :verify-existing-coverage
  ;; Confirm EXISTING coverage already demonstrates/preserves the behavior
  ;; WITHOUT manufacturing a new failing test (design R-16.2, R-16.3; the
  ;; :existing-coverage-confirmed PRODUCER F1/STR-2 needs — grep confirmed
  ;; nothing in `src/` emitted this event before).
  ;;
  ;; R-16.2 and R-16.3 are OPPOSITE observable realities, not the same check:
  ;;   R-16.2 — an existing FAILING test already demonstrates a repair's
  ;;            defect, accepted as defect evidence without a new failure.
  ;;   R-16.3 — a behavior-preserving structural refactor retains its existing
  ;;            PASSING tests, never requiring a manufactured failure.
  ;; `ctx`'s `:existing-coverage-kind` (`:defect-evidence` for R-16.2 or
  ;; `:behavior-preserving` for R-16.3) tells this effect which polarity to
  ;; require; this is a thin action wrapping the pure decision
  ;; `fs/existing-coverage-outcome`, which does the actual (inverted-per-kind)
  ;; classification — this method only runs the injectable `:command` (the
  ;; same relevant suite `:verify-red`/`:verify-green` re-run) via the action
  ;; `fs/run-verification` and hands the observed result + kind to the
  ;; calculation.
  ;;
  ;; Returns {:event :existing-coverage-confirmed} on a decided, confirmed
  ;; outcome, or {:error …} (fail closed — including an unrecognized/missing
  ;; `:existing-coverage-kind`, R-18.5-style: reality about WHICH case applies
  ;; must be known, not assumed). The drive loop feeds the :event through
  ;; `core/transition`, which the table already maps at :test-design to
  ;; :implement, identically to :red-verified.
  [ctx _effect]
  (let [{:keys [command cwd existing-coverage-kind]} ctx
        result  (fs/run-verification command {:cwd cwd})
        outcome (fs/existing-coverage-outcome result existing-coverage-kind)]
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
  ;; only DECIDES; driving the fail-closed transition a violation implies is
  ;; `workflow.orchestrator.dispatch`'s wiring, which consumes this result.
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
;; Recording the finding is `workflow.orchestrator.review/record-finding!`
;; (already committed durably during the review round); the R-10 validity rule
;; is the pure `core/finding-valid?` reused here, never re-derived.
;; `select-carried-finding` is the pure DECISION of WHICH recorded finding
;; carries; `mint-iteration!` is the ACTION that fails closed without one and,
;; in ONE mint transaction, creates the new Iteration at `:iteration/revision`
;; 0 and sets BOTH inverse links.

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
  the prior round (`workflow.orchestrator.review/record-finding!`); it reads
  those findings with their entity ids and asks the pure `select-carried-finding`
  which one carries. If none qualifies the mint FAILS CLOSED — no Iteration is
  created and no link is set — so a round can never mint a new pass without a
  justified change request (R-8.8, R-8.9).

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
  ;; `workflow.orchestrator.disagreement/escalate-disagreement!`, which reuses
  ;; the pure `core/allowance-remaining` to confirm the bounded allowance has
  ;; reached 0 BEFORE escalating: an unexhausted Disagreement FAILS CLOSED with
  ;; `{:error {:code :allowance-not-exhausted …}}` and NO transition is
  ;; committed, so the slice only reaches :escalated once repairs on that
  ;; subject are genuinely spent (R-15.4). On exhaustion it finalizes
  ;; `:disagreement/status :exhausted` durably (the per-`:disagreement/id` fact
  ;; that justifies the escalation, R-15.5) in the same recovery path and then
  ;; appends the transition. When no Disagreement subject is supplied — the
  ;; drive loop already raised the `:allowance-exhausted` event upstream — it
  ;; wires the transition-event append directly.
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
      (disagreement/escalate-disagreement! conn {:run-eid       run-eid
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
