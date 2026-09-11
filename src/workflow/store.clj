(ns workflow.store
  "Datalevin durable store for the multi-agent development workflow (R-17).

  This namespace holds the ACTIONS at the durable edge: the Datalevin schema
  (data), and the connect/close helpers that open and close an LMDB-backed,
  ACID Datalog connection. Reading and writing Datalevin is I/O, so everything
  here is an action in the Action/Calculation/Data sense — the decisions that
  consume the maps stored here live as pure calculations in `workflow.core`.

  Durable state is the third source of truth alongside the filesystem and the
  Orchestrator's state machine; git is NOT a source of truth and Revision
  identity is the monotonic per-iteration counter `:iteration/revision`, never a
  filesystem- or git-derived value.

  Scope note: this file holds the schema, connect/close, and the single-ACID-
  transaction transact helpers. The append-only event-stream queries and the
  entity queries are separate downstream tasks (4.3, 4.4) that build on the
  event-append primitive below."
  (:require [datalevin.core :as d]
            [workflow.core :as wc]))

;; --- The Datalevin schema as data (design Data Models; R-17, R-8, R-15, R-18) -
;;
;; One database directory per installation (path configurable). Every meaningful
;; fact is its own entity, so history is append-friendly and superseded decisions
;; are retained rather than overwritten. State-machine progress is stored as an
;; append-only stream of immutable transition events (`:event/*`); current state
;; is DERIVED from the highest-:event/seq event (see `workflow.core/current-state`),
;; not mutated in place. The scalar `:*/state` attributes are a materialized
;; convenience kept in sync by the same transaction that appends the event.

(def schema
  "Pure data: the full Datalevin schema map (attribute -> value-type/options).

  Encodes the design's Data Models section verbatim in intent: run / slice /
  iteration / step identity, the append-only transition-event stream, reviews /
  approvals / findings, decisions / proposals, and the per-Disagreement
  reconciliation allowance. Passed to `d/get-conn` so Datalevin enforces value
  types, uniqueness, cardinality, and ref semantics."
  {;; --- append-only transition history: current state is derived (R-17.1) ---
   ;; One entity per state transition that ever occurs. Current state =
   ;; the :event/to-state of the highest :event/seq for the target (a run or a
   ;; slice), consumed by workflow.core/current-state. Nothing overwrites history.
   :event/id          {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :event/run         {:db/valueType :db.type/ref}
   :event/slice       {:db/valueType :db.type/ref}       ; nil for run-level events
   :event/iteration   {:db/valueType :db.type/ref}       ; the trace this transition belongs to
   :event/seq         {:db/valueType :db.type/long}      ; monotonic per run; total order
   :event/from-state  {:db/valueType :db.type/keyword}
   :event/to-state    {:db/valueType :db.type/keyword}
   :event/trigger     {:db/valueType :db.type/keyword}   ; the event symbol that fired
   :event/at          {:db/valueType :db.type/instant}

   ;; --- run / slice / iteration / step identity ---
   :run/id            {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :run/created-at    {:db/valueType :db.type/instant}
   :run/requirements  {:db/valueType :db.type/string}    ; verbatim / stable ref
   :run/state         {:db/valueType :db.type/keyword}   ; materialized
   :run/event-seq     {:db/valueType :db.type/long}      ; last allocated :event/seq

   :slice/id          {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :slice/run         {:db/valueType :db.type/ref}
   :slice/order       {:db/valueType :db.type/long}
   :slice/state       {:db/valueType :db.type/keyword}   ; materialized
   :slice/current-iteration {:db/valueType :db.type/ref} ; the active trace (correlation)

   :iteration/id      {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :iteration/slice   {:db/valueType :db.type/ref}
   :iteration/number  {:db/valueType :db.type/long}
   :iteration/started-at {:db/valueType :db.type/instant}
   ;; --- Revision: monotonic per-iteration counter (R-8) ---
   ;; The current Revision counter for this Iteration. Starts at 0 when the
   ;; Iteration is minted and is advanced (incremented) IN THE SAME ACID
   ;; transaction that records an implementer/test-designer Step outcome
   ;; (R-8.4, R-17.4). It carries NO file information: it is not a hash, not a
   ;; manifest, not derived from or compared against the filesystem, not git.
   :iteration/revision {:db/valueType :db.type/long}
   ;; When a review round ends without approval, the R-10-compliant finding whose
   ;; failure information is carried into the newly minted Iteration (R-8.9). Set
   ;; on the new Iteration at :begin-iteration time; inverse of
   ;; :finding/carried-to-iteration.
   :iteration/seeded-from-finding {:db/valueType :db.type/ref}

   ;; --- agent dispatch step, two-phase lifecycle (R-18) ---
   :step/id           {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :step/iteration    {:db/valueType :db.type/ref}
   :step/role         {:db/valueType :db.type/keyword}   ; :test-designer ...
   :step/backend      {:db/valueType :db.type/keyword}   ; :hermes | :kiro
   :step/model        {:db/valueType :db.type/string}    ; "auto" default
   :step/capability   {:db/valueType :db.type/keyword}   ; :test-authoring | :production-authoring | :read-only
   :step/status       {:db/valueType :db.type/keyword}   ; :dispatched | :complete | :failed
   :step/dispatched-at {:db/valueType :db.type/instant}  ; intent committed BEFORE run (R-18.1)
   :step/outcome-at   {:db/valueType :db.type/instant}   ; committed AFTER return (R-18.1)
   :step/result-ref   {:db/valueType :db.type/string}    ; artifact path / summary

   ;; --- reviews & findings (R-5,R-6,R-9,R-10,R-11) ---
   :review/id         {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :review/iteration  {:db/valueType :db.type/ref}       ; correlation trace
   :review/revision-counter {:db/valueType :db.type/long} ; Revision counter value judged (R-8.1)
   :review/reviewer   {:db/valueType :db.type/keyword}   ; :correctness | :structural
   :review/verdict    {:db/valueType :db.type/keyword}   ; :approve | :request-changes
   :review/inputs-ref {:db/valueType :db.type/string}    ; correctness findings shown to structural (R-9)
   :review/finding    {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
                                                         ; R-10 findings recorded with this review; a
                                                         ; withhold/REQUEST_CHANGES MUST record at least
                                                         ; one valid finding (R-8.8, R-8.9)

   ;; --- approvals: bound to the Revision counter value in force (R-8.3) ---
   :approval/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :approval/review   {:db/valueType :db.type/ref}
   :approval/reviewer {:db/valueType :db.type/keyword}   ; :correctness | :structural
   :approval/revision-counter {:db/valueType :db.type/long} ; the Revision counter value this approval binds to (R-8.3)
   :approval/verdict  {:db/valueType :db.type/keyword}   ; :approve | :request-changes
   :approval/stale?   {:db/valueType :db.type/boolean}   ; set when a newer counter value has been recorded (R-8.4)
   :approval/stale-reason {:db/valueType :db.type/keyword} ; e.g. :revision-advanced — recorded by the
                                                         ; Orchestrator on resume WITHOUT a reviewer
                                                         ; statement when an approval is treated as
                                                         ; unapproved purely due to Revision staleness (R-8.7)
   :approval/at       {:db/valueType :db.type/instant}

   :finding/id        {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :finding/review    {:db/valueType :db.type/ref}
   :finding/owner     {:db/valueType :db.type/keyword}   ; :test-designer | :implementer | :both
   :finding/problem     {:db/valueType :db.type/string}  ; R-10 (1)
   :finding/evidence    {:db/valueType :db.type/string}  ; R-10 (2)
   :finding/justification {:db/valueType :db.type/string} ; R-10 (3)
   :finding/required-outcome {:db/valueType :db.type/string} ; R-10 (4)
   :finding/valid?    {:db/valueType :db.type/boolean}   ; all four present (R-10.2)
   :finding/revision-counter {:db/valueType :db.type/long} ; Revision counter value the finding was recorded against
   :finding/carried-to-iteration {:db/valueType :db.type/ref} ; the newly minted Iteration this finding's
                                                         ; failure information is carried into (R-8.9);
                                                         ; inverse of :iteration/seeded-from-finding

   ;; --- decisions / agreed outcomes (R-12,R-13,R-14,R-17) ---
   :decision/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :decision/iteration {:db/valueType :db.type/ref}
   :decision/slice    {:db/valueType :db.type/ref}       ; scope
   :decision/subject  {:db/valueType :db.type/string}    ; descriptive subject
   :decision/statement {:db/valueType :db.type/string}
   :decision/status   {:db/valueType :db.type/keyword}   ; :binding | :superseded
   :decision/accepted-by {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :decision/supersedes {:db/valueType :db.type/ref}     ; prior decision replaced (R-17.2)
   :decision/new-evidence {:db/valueType :db.type/string} ; required to reconsider (R-14)
   :decision/created-at {:db/valueType :db.type/instant}

   ;; --- repair/correction proposals (R-12) ---
   :proposal/id       {:db/valueType :db.type/uuid  :db/unique :db.unique/identity}
   :proposal/slice    {:db/valueType :db.type/ref}       ; scope
   :proposal/subject  {:db/valueType :db.type/string}
   :proposal/iteration {:db/valueType :db.type/ref}
   :proposal/body     {:db/valueType :db.type/string}
   :proposal/supersedes {:db/valueType :db.type/ref}     ; amended proposals are new (R-12.3)
   :proposal/accepted-by {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :proposal/resolution {:db/valueType :db.type/keyword} ; :accepted | :rejected | :open

   ;; --- reconciliation allowance, per-disagreement (R-15) ---
   :disagreement/id   {:db/valueType :db.type/uuid :db/unique :db.unique/identity} ; stable identity
   :disagreement/slice {:db/valueType :db.type/ref}      ; descriptive scope attribute
   :disagreement/subject {:db/valueType :db.type/string} ; descriptive subject attribute
   :disagreement/attempts-used {:db/valueType :db.type/long} ; accepted OR rejected count (R-15.2)
   :disagreement/status {:db/valueType :db.type/keyword}}) ; :open | :resolved | :exhausted

;; --- connect / close (actions: open and close the durable connection) --------
;;
;; A Datalevin connection is an LMDB-backed, ACID Datalog store opened against a
;; database directory. Opening and closing it is I/O, so both helpers are
;; actions. Downstream transact helpers, the event stream, and queries operate
;; over the connection returned by `connect`.

(defn connect
  "Open a Datalevin connection at `dir`, installing `schema` (R-17.3).

  `dir` is the database directory path (one database directory per
  installation, path configurable). Returns the connection, which the transact
  helpers and queries operate over. Uses the module `schema` when none is
  supplied. This is an action: it opens LMDB-backed durable storage on disk."
  ([dir] (connect dir schema))
  ([dir schema']
   (d/get-conn dir schema')))

(defn close
  "Close the Datalevin connection `conn`, releasing its LMDB resources.

  This is an action: it flushes and closes the durable connection opened by
  `connect`. After close, the connection must not be reused; reopen with
  `connect` to read the same durable state back."
  [conn]
  (d/close conn))
;; --- single-ACID-transaction transact helpers (actions; R-17.4, R-17.3, R-8.4) -
;;
;; Every meaningful fact is persisted in ONE `d/transact!` — one durable LMDB
;; commit at the moment it happens (R-17.4). The invariants the whole design
;; leans on are enforced HERE, at the transaction boundary:
;;
;;   * A transition event is appended AND the target's materialized `:*/state`
;;     is updated in the SAME transaction, so the state derived from the
;;     append-only stream (workflow.core/current-state) can never diverge from
;;     the materialized `:*/state` (R-17.1, R-17.4).
;;   * Recording an implementer/test-designer Step outcome AND advancing the
;;     Iteration's `:iteration/revision` counter (via workflow.core/advance-revision)
;;     happen in the SAME transaction, so the Revision counter can never lag
;;     behind a recorded Step outcome — even if a crash follows immediately
;;     (R-8.4, R-17.4).
;;
;; These are actions: each reads the current db value and issues a single
;; `d/transact!`. The pure decisions they lean on (the monotonic increment) live
;; as calculations in workflow.core; the tx-data assembly below is the action
;; that commits them. `append-transition-event` is the reusable event-append +
;; materialization primitive the event-stream helpers (task 4.3) build on.

(defn- next-event-seq
  "Read the next monotonic `:event/seq` for the run entity `run-eid` (action).

  `:event/seq` is monotonic per run and gives the events a total order. The last
  allocated value is kept on `:run/event-seq`; the next sequence is its successor
  (0 for a run that has none yet). Reads the current db value of `conn`; the
  allocation is committed by the same `d/transact!` that appends the event, so
  the read-then-write pair stays inside one ACID commit."
  [conn run-eid]
  (inc (or (:run/event-seq (d/pull (d/db conn) [:run/event-seq] run-eid)) -1)))

(defn append-transition-event
  "Append one immutable transition event AND materialize `:*/state` in ONE commit.

  This is the reusable event-append primitive (the event-stream helpers of task
  4.3 build on it). In a single `d/transact!` — one durable LMDB commit — it:

    * allocates the next monotonic `:event/seq` for the run and records it on the
      new `:event` entity together with the last-allocated marker `:run/event-seq`;
    * appends an immutable `:event` entity carrying `:event/id`, `:event/run`,
      the trigger, from/to states, the `:event/iteration` trace, and `:event/at`;
    * updates the target's materialized `:*/state` to the event's `:to-state`.

  Because the append and the materialized update share the transaction, the state
  derived from the highest-`:event/seq` event (workflow.core/current-state) always
  equals the materialized `:*/state`; the derived state can never diverge from
  history (R-17.1, R-17.4).

  `event` is a plain map describing the transition:
    {:run-eid    <run entity id>        ; required; owns the :event/seq counter
     :slice-eid  <slice entity id>      ; optional; nil for run-level events
     :iteration-eid <iteration entity>  ; optional; the trace this belongs to
     :from-state <keyword> :to-state <keyword> :trigger <keyword>
     :at         <java.util.Date>}      ; optional; defaults to now
  When `:slice-eid` is present the slice's `:slice/state` is materialized; the
  run's `:run/state` is materialized otherwise. Returns the Datalevin transaction
  report. This is an action: it commits durable state on disk."
  [conn {:keys [run-eid slice-eid iteration-eid from-state to-state trigger at]}]
  (let [seq'      (next-event-seq conn run-eid)
        event-ent (cond-> {:event/id      (random-uuid)
                           :event/run     run-eid
                           :event/seq     seq'
                           :event/to-state to-state
                           :event/at      (or at (java.util.Date.))}
                    from-state    (assoc :event/from-state from-state)
                    trigger       (assoc :event/trigger trigger)
                    slice-eid     (assoc :event/slice slice-eid)
                    iteration-eid (assoc :event/iteration iteration-eid))
        ;; The append, the :event/seq allocation marker, and the materialized
        ;; :*/state update are ONE transaction — one ACID LMDB commit (R-17.4).
        state-ent (if slice-eid
                    {:db/id slice-eid :slice/state to-state}
                    {:db/id run-eid   :run/state   to-state})]
    (d/transact! conn [event-ent
                       {:db/id run-eid :run/event-seq seq'}
                       state-ent])))

(defn record-step-outcome
  "Record a Step's terminal outcome, advancing the Revision counter in ONE commit.

  A Step carries a two-phase lifecycle (R-18.1): the dispatch intent is committed
  BEFORE the agent runs; this helper commits the outcome AFTER it returns. In a
  single `d/transact!` — one durable LMDB commit — it stamps the existing
  `:step/id` entity with its terminal `:step/status` (`:complete` | `:failed`),
  `:step/outcome-at`, and `:step/result-ref`, AND — for an `implementer` or
  `test-designer` Step — advances the owning Iteration's `:iteration/revision`
  counter via `workflow.core/advance-revision`.

  Recording the outcome and advancing the counter share the transaction, so the
  Revision counter can never lag behind a recorded Step outcome even if a crash
  follows immediately (R-8.4, R-17.4); any approval that named the earlier counter
  value is thereby stale by a pure integer comparison (R-8.4). A reviewer Step
  advances no counter — only an `implementer`/`test-designer` outcome does.

  `outcome` is a plain map:
    {:step-eid   <step entity id>       ; required
     :iteration-eid <iteration entity>  ; required when :advance-revision? is true
     :status     :complete | :failed
     :result-ref <string>               ; optional artifact path / summary
     :at         <java.util.Date>        ; optional; defaults to now
     :advance-revision? <boolean>}      ; true for implementer/test-designer roles
  Returns the Datalevin transaction report. This is an action: it commits durable
  state on disk."
  [conn {:keys [step-eid iteration-eid status result-ref at advance-revision?]}]
  (let [step-ent (cond-> {:db/id          step-eid
                          :step/status    status
                          :step/outcome-at (or at (java.util.Date.))}
                   result-ref (assoc :step/result-ref result-ref))
        tx-data  (if advance-revision?
                   (let [current (or (:iteration/revision
                                      (d/pull (d/db conn) [:iteration/revision] iteration-eid))
                                     0)]
                     ;; Outcome + counter advance in the SAME transaction (R-8.4).
                     [step-ent
                      {:db/id iteration-eid
                       :iteration/revision (wc/advance-revision current)}])
                   [step-ent])]
    (d/transact! conn tx-data)))

;; --- append-only transition-event stream: QUERIES (actions; R-17.1, R-17.3) ---
;;
;; State-machine progress is stored as an append-only stream of immutable
;; transition events; current state is DERIVED from the latest event, not mutated
;; in place (design, Data Models). These are the READ side of that stream: they
;; query events back out of Datalevin in `:event/seq` order and hand the queried
;; event maps to the pure `workflow.core/current-state` so the derived state can
;; be rebuilt/verified against the materialized `:*/state`.
;;
;; They do NOT append — appending (and keeping the materialized `:*/state` in
;; sync in the same transaction) is `append-transition-event` above. These
;; helpers only read: each pulls the recorded events and confirms that the state
;; derived from history equals what the append primitive materialized (R-17.1).
;;
;; These are actions: each reads the current db value via `d/q`/`d/pull`.

(def ^:private event-pull-pattern
  "Pure data: the `:event/*` attributes read back for each transition event.

  The projection `event-stream` pulls for every event of a run: the monotonic
  ordering key `:event/seq`, the derivation inputs (`:event/from-state`,
  `:event/to-state`), the trigger, the timestamp, and the target refs
  (`:event/run`, `:event/slice`, `:event/iteration`) reduced to their entity ids.
  These are exactly the keys `workflow.core/current-state` consumes to derive the
  current state of a run or slice from the stream."
  [:event/id :event/seq :event/from-state :event/to-state :event/trigger :event/at
   {:event/run [:db/id]} {:event/slice [:db/id]} {:event/iteration [:db/id]}])

(defn- flatten-event-refs
  "Reduce an event's pulled ref maps to plain entity ids (calculation).

  `d/pull` returns `:event/run`/`:event/slice`/`:event/iteration` as `{:db/id n}`
  maps; `workflow.core/current-state` compares those target refs by entity id.
  This flattens each present ref to its `:db/id` so the queried event maps carry
  the same shape the core derivation filters on. Pure map transform; the query
  that produced the event is the action."
  [event]
  (reduce (fn [ev k]
            (if-let [ref (get ev k)]
              (assoc ev k (:db/id ref))
              ev))
          event
          [:event/run :event/slice :event/iteration]))

(defn event-stream
  "Read the append-only transition-event stream for run `run-eid`, in order.

  Queries every immutable transition event whose `:event/run` is `run-eid` and
  returns them as plain maps sorted ascending by the monotonic `:event/seq` —
  the total order the append primitive allocated (R-17.1). Each event's target
  refs (`:event/run`, `:event/slice`, `:event/iteration`) are flattened to entity
  ids so the maps feed straight into `workflow.core/current-state`.

  With `slice-eid` supplied, the stream is sliced to just that slice's events
  (`:event/slice` = `slice-eid`) — the run-level slice for deriving a single
  slice's state; without it, the whole run's stream is returned. Returns an empty
  vector when the run (or slice) has no events yet. This is an action: it reads
  the current db value of `conn`."
  ([conn run-eid]
   (->> (d/q '[:find [(pull ?e pattern) ...]
               :in $ ?run pattern
               :where [?e :event/run ?run]]
             (d/db conn) run-eid event-pull-pattern)
        (map flatten-event-refs)
        (sort-by :event/seq)
        vec))
  ([conn run-eid slice-eid]
   (->> (d/q '[:find [(pull ?e pattern) ...]
               :in $ ?run ?slice pattern
               :where
               [?e :event/run ?run]
               [?e :event/slice ?slice]]
             (d/db conn) run-eid slice-eid event-pull-pattern)
        (map flatten-event-refs)
        (sort-by :event/seq)
        vec)))

(defn derive-current-state
  "Derive `target`'s current state from the queried event stream (R-17.1).

  Reads the run's transition-event stream (`event-stream`) and hands it to the
  pure `workflow.core/current-state`, which returns the `:event/to-state` of the
  highest-`:event/seq` event belonging to `target`. `target-attr` selects the
  target ref: `:event/run` derives the run's state, `:event/slice` (the default)
  derives a slice's state; `target` is the corresponding entity id.

  Because the append and the materialized `:*/state` update share one ACID
  transaction (`append-transition-event`), this derived value always equals the
  materialized `:run/state` / `:slice/state` — so it doubles as a verification
  that derived == materialized (R-17.1). Returns nil when no event in the stream
  belongs to `target`. This is an action (it reads via `event-stream`); the
  derivation it delegates to is a pure calculation."
  ([conn run-eid target]
   (derive-current-state conn run-eid target :event/slice))
  ([conn run-eid target target-attr]
   (wc/current-state (event-stream conn run-eid) target target-attr)))

;; --- entity QUERIES: reviews, approvals, findings, decisions, proposals,
;;     disagreements (actions; R-8.5, R-8.10, R-12.4, R-13.3, R-15.3, R-17.2) ---
;;
;; The READ side of the durable store's non-event facts. Where the event-stream
;; queries above rebuild state from the append-only transition stream, these read
;; back the recorded reviews / approvals / findings / decisions / proposals /
;; disagreements so the pure decisions in `workflow.core` can consume them:
;;
;;   * `current-revision` reads the Iteration's monotonic `:iteration/revision`
;;     so the Orchestrator can compare an approval's bound counter value against
;;     it via `workflow.core/approval-valid?` — approval staleness is a pure
;;     integer comparison, never an iteration-identity or file-derived check
;;     (R-8.5, R-8.3).
;;   * `find-disagreement` locates an existing `:disagreement/id` by scanning the
;;     DESCRIPTIVE `:disagreement/slice` + `:disagreement/subject` attributes.
;;     There is NO normalized/composite/derived key — the design forbids one; a
;;     disagreement's identity is its `:disagreement/id`, and slice+subject are
;;     descriptive attributes used to recognize the same concern so its
;;     `:disagreement/attempts-used` allowance survives restarts (R-15.3).
;;   * decisions are read binding-vs-superseded via `:decision/status`; a
;;     replacement is a new entity and the superseded one stays queryable
;;     (R-13.3, R-17.2).
;;
;; These are actions: each reads the current db value via `d/q`/`d/pull`. The
;; staleness/binding decisions they feed are pure calculations in `workflow.core`.

(defn current-revision
  "Read the Iteration `iteration-eid`'s current Revision counter (action; R-8.5).

  Returns the monotonic per-Iteration `:iteration/revision` counter value in
  force, or nil when the Iteration has none recorded. The Orchestrator reads this
  on resume and hands it to the pure `workflow.core/approval-valid?` so an
  approval is honored only while its bound `:approval/revision-counter` still
  equals this value; once an implementer/test-designer Step outcome advanced the
  counter, the approval is stale by a pure integer comparison (R-8.3, R-8.4). The
  counter carries no file information. This is an action: it reads the current db
  value of `conn`."
  [conn iteration-eid]
  (:iteration/revision (d/pull (d/db conn) [:iteration/revision] iteration-eid)))

(def ^:private approval-pull-pattern
  "Pure data: the `:approval/*` attributes read back for each approval.

  Projects the fields the Orchestrator needs to decide staleness: the bound
  counter value `:approval/revision-counter` (compared against `current-revision`
  via `workflow.core/approval-valid?`), the reviewer, the verdict, the recorded
  staleness marker/reason, the timestamp, and the owning `:approval/review`
  reduced to its entity id."
  [:approval/id :approval/reviewer :approval/revision-counter :approval/verdict
   :approval/stale? :approval/stale-reason :approval/at
   {:approval/review [:db/id]}])

(defn approvals-for-iteration
  "Read approvals recorded under Iteration `iteration-eid`, with bound counters.

  Queries every `:approval` whose owning `:approval/review` ran under
  `iteration-eid` (`:review/iteration`) and returns them as plain maps carrying
  their bound `:approval/revision-counter`. The Orchestrator compares each bound
  value against `current-revision` via `workflow.core/approval-valid?` to decide
  whether the approval still holds (R-8.5). Returns an empty vector when the
  Iteration has no approvals. This is an action: it reads the current db value of
  `conn`."
  [conn iteration-eid]
  (->> (d/q '[:find [(pull ?a pattern) ...]
              :in $ ?iter pattern
              :where
              [?r :review/iteration ?iter]
              [?a :approval/review ?r]]
            (d/db conn) iteration-eid approval-pull-pattern)
       vec))

(def ^:private review-pull-pattern
  "Pure data: the `:review/*` attributes read back for each review.

  Projects the review's judged counter value `:review/revision-counter`, the
  reviewer and verdict, the inputs reference, the correlation trace
  `:review/iteration`, and the recorded `:review/finding` refs — each reduced to
  its entity id — so the caller can walk a review to its R-10 findings."
  [:review/id :review/reviewer :review/verdict :review/revision-counter
   :review/inputs-ref
   {:review/iteration [:db/id]} {:review/finding [:db/id]}])

(defn reviews-for-iteration
  "Read the reviews recorded under Iteration `iteration-eid` (action).

  Queries every `:review` whose `:review/iteration` is `iteration-eid` (the
  correlation trace) and returns them as plain maps carrying the judged
  `:review/revision-counter`, verdict, and the `:review/finding` refs. Returns an
  empty vector when the Iteration has no reviews. This is an action: it reads the
  current db value of `conn`."
  [conn iteration-eid]
  (->> (d/q '[:find [(pull ?r pattern) ...]
              :in $ ?iter pattern
              :where [?r :review/iteration ?iter]]
            (d/db conn) iteration-eid review-pull-pattern)
       vec))

(def ^:private finding-pull-pattern
  "Pure data: the `:finding/*` attributes read back for each R-10 finding.

  Projects the four required components (problem, evidence, justification,
  required-outcome), the `:finding/valid?` flag (all four present, R-10.2), the
  owner, the counter value the finding was recorded against, the owning
  `:finding/review`, and any `:finding/carried-to-iteration` link — refs reduced
  to entity ids."
  [:finding/id :finding/owner :finding/problem :finding/evidence
   :finding/justification :finding/required-outcome :finding/valid?
   :finding/revision-counter
   {:finding/review [:db/id]} {:finding/carried-to-iteration [:db/id]}])

(defn findings-for-iteration
  "Read the R-10 findings recorded for Iteration `iteration-eid` (action).

  Queries every `:finding` whose owning `:finding/review` ran under
  `iteration-eid` (`:review/iteration`) and returns them as plain maps carrying
  the four required components and the `:finding/valid?` flag. Returns an empty
  vector when the Iteration has no findings. This is an action: it reads the
  current db value of `conn`."
  [conn iteration-eid]
  (->> (d/q '[:find [(pull ?f pattern) ...]
              :in $ ?iter pattern
              :where
              [?r :review/iteration ?iter]
              [?f :finding/review ?r]]
            (d/db conn) iteration-eid finding-pull-pattern)
       vec))

(def ^:private decision-pull-pattern
  "Pure data: the `:decision/*` attributes read back for each decision.

  Projects the descriptive `:decision/subject`, the statement, the
  binding-vs-superseded `:decision/status`, the accepting reviewers, the
  `:decision/new-evidence` required to reconsider, the creation instant, and the
  scope/trace/supersedes refs reduced to entity ids."
  [:decision/id :decision/subject :decision/statement :decision/status
   :decision/accepted-by :decision/new-evidence :decision/created-at
   {:decision/slice [:db/id]} {:decision/iteration [:db/id]}
   {:decision/supersedes [:db/id]}])

(defn decisions-for-slice
  "Read decisions scoped to slice `slice-eid`, optionally filtered by status.

  With two args, returns every `:decision` whose `:decision/slice` is `slice-eid`
  regardless of status — both `:binding` and `:superseded` decisions are
  queryable (R-13.3, R-17.2), since a replacement is a NEW entity linked by
  `:decision/supersedes` and the superseded one is retained, never overwritten.
  With `status` supplied (`:binding` | `:superseded`), the result is narrowed to
  decisions in that status. Returns an empty vector when none match. This is an
  action: it reads the current db value of `conn`."
  ([conn slice-eid]
   (->> (d/q '[:find [(pull ?d pattern) ...]
               :in $ ?slice pattern
               :where [?d :decision/slice ?slice]]
             (d/db conn) slice-eid decision-pull-pattern)
        vec))
  ([conn slice-eid status]
   (->> (d/q '[:find [(pull ?d pattern) ...]
               :in $ ?slice ?status pattern
               :where
               [?d :decision/slice ?slice]
               [?d :decision/status ?status]]
             (d/db conn) slice-eid status decision-pull-pattern)
        vec)))

(def ^:private proposal-pull-pattern
  "Pure data: the `:proposal/*` attributes read back for each repair proposal.

  Projects the descriptive `:proposal/subject`, the body, the resolution
  (`:accepted` | `:rejected` | `:open`), the accepting reviewers, and the
  scope/trace/supersedes refs reduced to entity ids. Amended proposals are new
  entities linked by `:proposal/supersedes`; acceptance does not carry forward
  (R-12.4)."
  [:proposal/id :proposal/subject :proposal/body :proposal/resolution
   :proposal/accepted-by
   {:proposal/slice [:db/id]} {:proposal/iteration [:db/id]}
   {:proposal/supersedes [:db/id]}])

(defn proposals-for-slice
  "Read repair proposals scoped to slice `slice-eid` (action; R-12.4).

  Returns every `:proposal` whose `:proposal/slice` is `slice-eid` as plain maps
  carrying the resolution, accepting reviewers, and any `:proposal/supersedes`
  link. Because an amended proposal is a NEW entity that supersedes its
  predecessor and acceptance does not carry forward, both the amendment and the
  superseded proposal are queryable here (R-12.4). Returns an empty vector when
  the slice has no proposals. This is an action: it reads the current db value of
  `conn`."
  [conn slice-eid]
  (->> (d/q '[:find [(pull ?p pattern) ...]
              :in $ ?slice pattern
              :where [?p :proposal/slice ?slice]]
            (d/db conn) slice-eid proposal-pull-pattern)
       vec))

(def ^:private disagreement-pull-pattern
  "Pure data: the `:disagreement/*` attributes read back for each disagreement.

  Projects the stable `:disagreement/id`, the consumed-allowance count
  `:disagreement/attempts-used`, the status (`:open` | `:resolved` |
  `:exhausted`), the descriptive `:disagreement/subject`, and the descriptive
  `:disagreement/slice` ref reduced to its entity id."
  [:disagreement/id :disagreement/subject :disagreement/attempts-used
   :disagreement/status {:disagreement/slice [:db/id]}])

(defn find-disagreement
  "Locate an existing disagreement by DESCRIPTIVE slice + subject (action; R-15.3).

  Scans the descriptive attributes `:disagreement/slice` (= `slice-eid`) and
  `:disagreement/subject` (= `subject`) to answer \"is this the same disagreement
  we were already reconciling?\" so its `:disagreement/attempts-used` allowance is
  found intact and cannot reset, even after a restart (R-15.3). This is a
  DESCRIPTIVE scan by design: there is NO normalized/composite/derived key and no
  `disagreement-key` function — a disagreement's identity is its
  `:disagreement/id`, and slice+subject are descriptive attributes only. Two
  different slices with a same-sounding subject are simply two different
  `:disagreement/id` entities.

  Returns the matching disagreement as a plain map, or nil when none matches.
  This is an action: it reads the current db value of `conn`."
  [conn slice-eid subject]
  (d/q '[:find (pull ?dg pattern) .
         :in $ ?slice ?subject pattern
         :where
         [?dg :disagreement/slice ?slice]
         [?dg :disagreement/subject ?subject]]
       (d/db conn) slice-eid subject disagreement-pull-pattern))
