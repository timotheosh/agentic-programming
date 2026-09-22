(ns workflow.orchestrator.resume
  "Resume / reconcile + resume-staleness (design, Resume (b): a bare
  interruption; R-18.2, R-18.3, R-18.4, R-18.5, R-8.5, R-8.7). Split out of
  the original monolithic `workflow.orchestrator` (user-directed
  reorganization).

  A process interruption is NOT a pipeline error and yields no review outcome
  (design, Resume (b)). On resume the Orchestrator does THREE things, all on the
  SAME trace-id — it never mints a correction Iteration merely because a process
  died and never restarts at the `test-designer` (restarting at :test-design is
  reserved for a review-authorized correction, case (a), via :begin-iteration):

    1. RECONCILE in-doubt Steps against OBSERVABLE REALITY (R-18.2, R-18.3). A
       Step committed at `:step/status :dispatched` with no recorded outcome is
       `core/step-in-doubt?` — neither assumed complete nor assumed untouched. Its
       ACTUAL outcome is recovered by re-running the relevant verification and
       inspecting produced artifacts on disk (`fs/recover-step-outcome`, which
       composes `fs/run-verification` + the pure `fs/red-outcome`/`fs/green-outcome`
       decisions + `fs/artifacts-present?`). Because agent effects are
       idempotent/verifiable, re-checking is safe (R-18.4). Only AFTER the outcome
       is recovered is it recorded (`store/record-step-outcome`, phase 2 of the
       two-phase lifecycle), advancing the Revision counter for an
       implementer/test-designer Step in the SAME transaction (R-8.4). If reality
       is INDETERMINATE — the verification could not be run, or a required
       artifact is absent — recovery returns `{:error …}` and the Orchestrator
       FAILS CLOSED: no outcome is recorded, the Step stays `:dispatched`, and the
       resume halts rather than assuming the Step landed or was untouched (R-18.5).

    2. Separately, decide APPROVAL STALENESS by a pure Datalevin-counter
       comparison (R-8.5). It reads the Iteration's current `:iteration/revision`
       (`store/current-revision`) and each recorded approval's bound
       `:approval/revision-counter` (`store/approvals-for-iteration`), and asks the
       pure `core/approval-valid?` whether the bound value still equals the current
       counter. When an implementer/test-designer Step outcome advanced the counter
       beyond the approval's bound value — INCLUDING one just recorded by step 1's
       reconciliation — the approval is treated as unapproved and marked
       `:approval/stale?` true with `:approval/stale-reason :revision-advanced`. No
       filesystem read decides Revision identity; it is an integer comparison over
       a Datalevin-read value.

    3. RECORD the staleness as a NON-reviewer fact (R-8.7). When resume treats a
       prior approval as unapproved PURELY because the Revision counter advanced,
       the Orchestrator itself records `:approval/stale-reason :revision-advanced`
       WITHOUT requiring any reviewer statement (contrast R-8.6, where a reviewer
       WITHHOLDING approval must state what failed and why). The staleness marking
       is the Orchestrator's own bookkeeping, not a reviewer verdict, so it commits
       no `:finding` and no reviewer statement.

  ACD separation: every DECISION is a pure calculation reused, never
  re-derived — `core/step-in-doubt?` classifies the Step, `fs/red-outcome` /
  `fs/green-outcome` classify the recovered verification, `core/approval-valid?`
  decides staleness by integer comparison. The helpers below are THIN actions
  that read the durable store, reconcile against the filesystem, and commit facts
  in single `d/transact!` commits. No recovery rule, no staleness rule is
  duplicated here."
  (:require [workflow.rules.core :as core]
            [workflow.fs :as fs]
            [workflow.store :as store]
            [workflow.orchestrator.dispatch :as dispatch]
            [datalevin.core :as d]))

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
  transaction for an implementer/test-designer Step
  (`workflow.orchestrator.dispatch/revision-advancing-role?`, R-8.4) so the
  counter never lags the recovered outcome — which is exactly what can stale a
  prior approval (step 2 below).

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
                 :advance-revision? (dispatch/revision-advancing-role? role)})]
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
