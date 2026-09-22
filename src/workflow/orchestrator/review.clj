(ns workflow.orchestrator.review
  "Review rounds, findings, and the approval AND-gate (design, Review round;
  R-7, R-8.1/8.3, R-9, R-10, R-16.4). Split out of the original monolithic
  `workflow.orchestrator` (user-directed reorganization).

  A review round runs the correctness review FIRST and the structural review
  AFTER, always in that order (R-7.1) — even when correctness returns
  REQUEST_CHANGES, structural still runs (R-7.2). The correctness review's
  findings and reasoning are fed to the structural dispatch as its input
  (`:review/inputs-ref`, R-9.1) so structural accounts for concerns correctness
  already identified. Both reviews judge the SAME Revision counter value in force
  (`:review/revision-counter`), and an approval binds to that value
  (`:approval/revision-counter`, R-8.1, R-8.3); the AND-gate holds only when both
  reviewers approve the same counter value (`core/both-approved?`, R-16.4).

  ACD separation: every DECISION is a pure calculation already living in
  `workflow.rules.core` — `core/finding-valid?`, `core/both-approved?` — and the
  helpers below are THIN actions that read the durable store
  (`workflow.store` queries), commit facts in a single `d/transact!` (one LMDB
  commit, the same envelope `workflow.orchestrator.dispatch/record-step-dispatch!`
  uses), and delegate the rule to core. No review rule is duplicated here."
  (:require [workflow.rules.core :as core]
            [workflow.store :as store]
            [workflow.orchestrator.dispatch :as dispatch]
            [datalevin.core :as d]))

;; Forward declaration: `run-review-round!` (below) records an `:approval` for
;; an `:approve` verdict via `record-approval!`, which is defined further down
;; with the rest of the approvals/AND-gate helpers (R-16.4, STR-4). Declared
;; here so the reference resolves cleanly.
(declare record-approval!)

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
  new Iteration is minted (R-8.8, R-8.9), which `workflow.orchestrator.core`
  enforces on top of this.

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

    1. CORRECTNESS — `dispatch/dispatch-step!` runs the `:correctness-reviewer`
       (read-only; any file edit fails closed via `dispatch/enforce-capability`),
       then `record-review!` records its verdict bound to the round's
       `:review/revision-counter`. The caller supplies each verdict (reviewer
       judgment is manual, not derived here); any R-10 findings are recorded via
       `record-finding!` before structural runs so they can be shown to it.
    2. STRUCTURAL — runs ONLY AFTER correctness is recorded (R-7.1); even a
       correctness REQUEST_CHANGES still runs structural (R-7.2). Its dispatch
       input carries the correctness findings via `:review/inputs-ref`
       (`correctness-findings-input`, R-9.1).

  An `:approve` verdict is bound to an `:approval` in the SAME round, via
  `record-approval!` (STR-4). A `:request-changes` verdict records no approval,
  leaving the gate unmet.

  Both reviews judge the SAME `revision-counter` value in force for the round
  (read by the caller from `store/current-revision`), never advancing it mid-round
  (R-8.1, R-8.2). `round` is a plain map:
    {:iteration-eid    <iteration entity id>  ; required; the round's trace
     :revision-counter <long>                 ; the value in force this round
     :correctness      {:verdict kw :ctx <dispatch ctx>}  ; correctness reviewer inputs
     :structural       {:verdict kw :ctx <dispatch ctx>}} ; structural reviewer inputs
  Each `:ctx` is the effect context map `dispatch/dispatch-step!` consumes (conn,
  invoker, role, iteration-eid, cwd, changes …); the `:role` is set here to the
  matching reviewer. Returns
    {:correctness {:review-eid … :dispatched <dispatch-step! result> :approval-eid <eid|nil>}
     :structural  {:review-eid … :dispatched … :inputs-ref <string> :approval-eid <eid|nil>}
     :order [:correctness :structural]}
  so callers can assert ordering, the fed inputs, and the recorded reviews +
  approvals. This is an action: it dispatches agents and commits durable
  review/approval facts."
  [conn {:keys [iteration-eid revision-counter correctness structural]}]
  ;; PHASE A — correctness FIRST (R-7.1).
  (let [c-verdict    (:verdict correctness)
        c-ctx        (assoc (:ctx correctness) :conn conn
                            :role :correctness-reviewer :iteration-eid iteration-eid)
        c-dispatched (dispatch/enforce-capability :review-correctness (dispatch/dispatch-step! c-ctx))
        c-review     (record-review! conn {:iteration-eid iteration-eid
                                           :reviewer :correctness
                                           :verdict c-verdict
                                           :revision-counter revision-counter})
        c-approval   (when (= :approve c-verdict)
                       (record-approval! conn {:review-eid (:review-eid c-review)
                                               :iteration-eid iteration-eid
                                               :reviewer :correctness
                                               :verdict c-verdict}))
        ;; The correctness findings shown to structural are the ones recorded for
        ;; this iteration by the time structural runs (R-9.1); the caller records
        ;; any findings via record-finding! between phases or up front.
        inputs-ref   (correctness-findings-input conn iteration-eid)
        ;; PHASE B — structural AFTER, seeing correctness's findings (R-7.2, R-9.1).
        s-verdict    (:verdict structural)
        s-ctx        (assoc (:ctx structural) :conn conn
                            :role :structural-reviewer :iteration-eid iteration-eid)
        s-dispatched (dispatch/enforce-capability :review-structural (dispatch/dispatch-step! s-ctx))
        s-review     (record-review! conn {:iteration-eid iteration-eid
                                           :reviewer :structural
                                           :verdict s-verdict
                                           :revision-counter revision-counter
                                           :inputs-ref inputs-ref})
        s-approval   (when (= :approve s-verdict)
                       (record-approval! conn {:review-eid (:review-eid s-review)
                                               :iteration-eid iteration-eid
                                               :reviewer :structural
                                               :verdict s-verdict}))]
    {:correctness {:review-eid (:review-eid c-review)
                   :dispatched c-dispatched
                   :approval-eid (:approval-eid c-approval)}
     :structural  {:review-eid (:review-eid s-review)
                   :dispatched s-dispatched
                   :inputs-ref inputs-ref
                   :approval-eid (:approval-eid s-approval)}
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
  `workflow.orchestrator.resume`'s wiring.

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
