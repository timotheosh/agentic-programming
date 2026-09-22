(ns workflow.orchestrator.disagreement
  "Per-Disagreement Reconciliation allowance and escalation on exhaustion
  (design R-15). Split out of the original monolithic `workflow.orchestrator`
  (user-directed reorganization).

  ACD separation: every DECISION is a pure calculation already living in
  `workflow.rules.core` — `core/consume-attempt`, `core/allowance-remaining` —
  and the helpers below are THIN actions that read the durable store
  (`workflow.store` queries), commit facts in a single `d/transact!`, and
  delegate the rule to core. No allowance arithmetic is duplicated here.

  `workflow.orchestrator.core`'s `:escalate` effect method calls
  `escalate-disagreement!` below when the effect names a Disagreement subject."
  (:require [workflow.rules.core :as core]
            [workflow.store :as store]
            [datalevin.core :as d]))

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
  and repairs on that subject stop, escalating to the human — `escalate-disagreement!`
  wires the `:escalate` transition on top of this signal (R-15.4).

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
