(ns workflow.orchestrator.repair
  "Repair proposals: one plan accepted by both reviewers, amendments start
  fresh, reconsideration admissibility (design R-12, R-13.2, R-14). Split out
  of the original monolithic `workflow.orchestrator` (user-directed
  reorganization).

  ACD separation: every DECISION is a pure calculation already living in
  `workflow.rules.core` — `core/binding-conflict?`, `core/reconsideration-admissible?`
  — and the helpers below are THIN actions that read the durable store
  (`workflow.store` queries) and commit facts in a single `d/transact!`. No
  binding/reconsideration logic is duplicated here."
  (:require [workflow.rules.core :as core]
            [workflow.store :as store]
            [datalevin.core :as d]))

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
