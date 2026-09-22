(ns workflow.rules.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [workflow.rules.core :as core]))

;; Feature: orchestrator-state-machine, Property 1: Advancement to
;; implementation requires verified RED for the current iteration.
;;
;; For any [state event], a transition into :implement occurs only when RED has
;; been verified (:red-verified) or existing coverage has been confirmed
;; (:existing-coverage-confirmed); an invalid RED (:red-invalid) retries at
;; :test-design; any absent/in-doubt result never enters :implement; and the
;; test-designer (:test-design) always precedes implementation (:implement).
;;
;; Validates: Requirements 1.1, 1.2, 1.3, 1.6, 16.3

(def ^:private all-states
  [:planning :test-design :implement :review-correctness :review-structural
   :reconcile :slice-approved :escalated :documenting :done :failed-closed])

;; A grab-bag of events including the legitimate ones, malformed/absent
;; results, and pure noise, so the property exercises the fail-closed paths.
(def ^:private all-events
  [:red-verified :existing-coverage-confirmed :red-invalid :production-touched
   :green :test-touched :test-conflict :approve :request-changes :reviewer-edited
   :both-approve :any-request-changes :plan-accepted :allowance-exhausted
   ;; absent / in-doubt / malformed
   nil :in-doubt :timeout :unknown-event :approve-maybe])

(defn- entered-implement?
  "True iff the transition Result advances the machine into :implement."
  [result]
  (and (map? result)
       (not (contains? result :error))
       (= :implement (:next-state result))))

(deftest property-1-advance-to-implement-requires-verified-red
  (let [result
        (tc/quick-check
         100
         (prop/for-all [state (gen/elements all-states)
                        event (gen/elements all-events)]
                       (let [r (core/transition state event)]
                         (if (entered-implement? r)
               ;; The ONLY ways to enter :implement:
                           (and (= state :test-design)
                                (contains? #{:red-verified :existing-coverage-confirmed}
                                           event))
               ;; If we did not enter :implement, that is always acceptable;
               ;; but specifically an invalid RED must retry at :test-design.
                           (if (and (= state :test-design) (= event :red-invalid))
                             (= :test-design (:next-state r))
                             true)))))]
    (is (:pass? result) (pr-str result))))

;; Structural assertions on the transition table itself (Property 1 clauses:
;; no :repair / :red-verify / :green-verify state, and the :begin-iteration
;; effect on the reconciliation-accepted correction).

(deftest property-1-table-has-no-repair-or-verify-states
  (let [states (into #{}
                     (mapcat (fn [[[from _] result]]
                               [from (:next-state result)]))
                     core/transitions)]
    (is (not (contains? states :repair)))
    (is (not (contains? states :red-verify)))
    (is (not (contains? states :green-verify)))))

(deftest property-1-reconcile-accept-carries-begin-iteration
  (let [r (core/transition :reconcile :plan-accepted)]
    (is (= :test-design (:next-state r)))
    (is (some #(= :begin-iteration (:effect/type %)) (:effects r)))))

(deftest property-1-test-designer-precedes-implementation
  ;; :implement is reachable only out of :test-design, never any other state.
  (is (every? (fn [state]
                (not (entered-implement? (core/transition state :red-verified))))
              (remove #{:test-design} all-states))))

;; Feature: orchestrator-state-machine, Property 6: Approvals bind to the
;; Revision counter value in force, and a recorded Step outcome advances the
;; counter and stales the approval.
;;
;; For any Revision counter value, `advance-revision` yields exactly its
;; successor ((inc counter)), so the counter is deterministic and strictly
;; monotonic; an approval binds to the counter value in force when it is
;; recorded; on resume the approval is honored (`approval-valid?` true) iff its
;; bound counter value equals the Iteration's current counter, otherwise it is
;; stale and re-approval is required (including across an interruption or
;; restart); `both-approved?` holds only when both reviewers approved the same
;; counter value for the round; and any implementer/test-designer Step outcome
;; recorded after an approval advances the counter and thereby stales that
;; approval.
;;
;; Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 16.4, 16.5

(defn- approval
  "A minimal approval record: a verdict bound to a Revision counter value."
  [verdict counter]
  {:approval/verdict verdict
   :approval/revision-counter counter})

(deftest property-6-revision-counter-and-approval-binding
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [counter (gen/large-integer* {:min 0 :max 1000000})
           ;; a possibly-different "current" counter for the resume comparison
           current (gen/large-integer* {:min 0 :max 1000000})
           ;; verdicts for the two reviewers of a round
           c-verdict (gen/elements [:approve :request-changes])
           s-verdict (gen/elements [:approve :request-changes])
           ;; counter each reviewer bound their approval to
           c-counter (gen/large-integer* {:min 0 :max 1000000})
           s-counter (gen/large-integer* {:min 0 :max 1000000})]
          (let [advanced (core/advance-revision counter)]
            (and
             ;; advance-revision is exactly the successor: deterministic and
             ;; strictly monotonic (R-8.4).
             (= advanced (inc counter))
             (> advanced counter)

             ;; approval-valid? is true iff the bound counter value equals the
             ;; Iteration's current counter, otherwise stale (R-8.3, R-8.5).
             (= (core/approval-valid? (approval :approve counter) current)
                (= counter current))

             ;; An approval bound to the counter in force is valid; after a
             ;; Step outcome advances the counter it is stale (R-8.4, 16.5).
             (core/approval-valid? (approval :approve counter) counter)
             (not (core/approval-valid? (approval :approve counter) advanced))

             ;; both-approved? holds only when BOTH verdicts are :approve AND
             ;; both approvals bind to the SAME round counter value
             ;; (R-8.1, R-16.4).
             (= (core/both-approved? (approval c-verdict c-counter)
                                     (approval s-verdict s-counter)
                                     c-counter)
                (and (= c-verdict :approve)
                     (= s-verdict :approve)
                     (= c-counter s-counter)))))))]
    (is (:pass? result) (pr-str result))))

;; Feature: orchestrator-state-machine, Property 8: A finding is valid only when
;; fully justified.
;;
;; A reviewer's requested change (finding) is valid iff ALL FOUR R-10 fields are
;; present and non-blank: the problem, the supporting evidence, the
;; justification, and the required outcome (:finding/problem,
;; :finding/evidence, :finding/justification, :finding/required-outcome). If any
;; one of the four is missing, nil, or blank (empty or whitespace-only), the
;; finding is an invalid change request and `finding-valid?` returns false.
;;
;; Validates: Requirements 10.1, 10.2

(def ^:private finding-fields
  [:finding/problem :finding/evidence :finding/justification :finding/required-outcome])

;; Generators for present-and-non-blank values vs. absent/blank values.

(def ^:private non-blank-gen
  ;; A string with at least one non-whitespace character.
  (gen/fmap (fn [[a b]] (str a "x" b))
            (gen/tuple gen/string gen/string)))

(def ^:private blank-gen
  ;; nil, the empty string, or whitespace-only strings — all count as absent.
  (gen/elements [nil "" " " "   " "\t" "\n" "  \t\n "]))

(defn- build-finding
  "Build a finding map, drawing each R-10 field from `present?`->value maps.
  `present?-map` maps each field to a boolean saying whether it is non-blank."
  [values]
  (into {} (map (fn [[k v]] [k v]) values)))

(deftest property-8-finding-valid-only-when-fully-justified
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [p-blank? gen/boolean
           e-blank? gen/boolean
           j-blank? gen/boolean
           r-blank? gen/boolean
           p-val non-blank-gen
           e-val non-blank-gen
           j-val non-blank-gen
           r-val non-blank-gen
           bp blank-gen
           be blank-gen
           bj blank-gen
           br blank-gen]
          (let [blanks [p-blank? e-blank? j-blank? r-blank?]
                present-vals [p-val e-val j-val r-val]
                blank-vals [bp be bj br]
                finding (build-finding
                         (map (fn [k blank? pv bv]
                                [k (if blank? bv pv)])
                              finding-fields blanks present-vals blank-vals))
                all-present? (not-any? true? blanks)]
            (= (core/finding-valid? finding) all-present?))))]
    (is (:pass? result) (pr-str result))))

;; Concrete examples: the fully-justified finding is valid; dropping any single
;; field makes it invalid.

(def ^:private complete-finding
  {:finding/problem "Login accepts an empty password."
   :finding/evidence "Test `empty-password-rejected` passes yet auth succeeds."
   :finding/justification "R-5.2: passing tests must not conceal a violated requirement."
   :finding/required-outcome "Reject empty passwords and add a demonstrating test."})

(deftest property-8-example-complete-finding-is-valid
  (is (core/finding-valid? complete-finding)))

(deftest property-8-example-missing-any-field-is-invalid
  (doseq [k finding-fields]
    (is (not (core/finding-valid? (dissoc complete-finding k)))
        (str "missing " k " should be invalid"))
    (is (not (core/finding-valid? (assoc complete-finding k nil)))
        (str "nil " k " should be invalid"))
    (is (not (core/finding-valid? (assoc complete-finding k "   ")))
        (str "blank " k " should be invalid"))))

;; Feature: orchestrator-state-machine, Property 12: Reconciliation allowance is
;; monotonic, bounded, and survives restart (pure clauses).
;;
;; For any Disagreement (identified by its stable :disagreement/id),
;; `allowance-remaining` equals `(max 0 (- 2 attempts-used))` and never exceeds
;; two; `consume-attempt` increments :attempts-used so `attempts-used` only ever
;; increases; and a rejected proposal is counted identically to an accepted one
;; (consume-attempt ignores the accept/reject resolution). The durable
;; restart/reopen clause is verified in store_test (task 4.7).
;;
;; Validates: Requirements 15.1, 15.2

(defn- disagreement
  "A minimal Disagreement record keyed to its stable :disagreement/id, carrying
  the accepted-OR-rejected :attempts-used count (R-15)."
  [id attempts-used]
  {:disagreement/id id
   :disagreement/attempts-used attempts-used})

(deftest property-12-allowance-monotonic-and-bounded
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [id gen/uuid
           ;; A wide range of already-used counts, including past exhaustion.
           attempts-used (gen/large-integer* {:min 0 :max 1000})
           ;; A proposal resolution should NOT affect consumption (R-15.2). The
           ;; resolution is intentionally NOT threaded into consume-attempt: the
           ;; function takes only the disagreement, so accept vs. reject cannot
           ;; change the increment.
           _resolution (gen/elements [:accepted :rejected])]
          (let [d (disagreement id attempts-used)
                remaining (core/allowance-remaining d)
                consumed (core/consume-attempt d)]
            (and
             ;; allowance-remaining == (max 0 (- 2 attempts-used)) (R-15.1).
             (= remaining (max 0 (- 2 attempts-used)))
             ;; It never exceeds the explicit allowance of two (R-15.1).
             (<= remaining 2)
             ;; It is never negative.
             (>= remaining 0)

             ;; consume-attempt increments :attempts-used, so the count only
             ;; ever increases (monotonic) (R-15.2).
             (= (:disagreement/attempts-used consumed) (inc attempts-used))
             (> (:disagreement/attempts-used consumed) attempts-used)

             ;; A rejected proposal is counted identically to an accepted one:
             ;; consumption ignores the resolution entirely (R-15.2). No matter
             ;; the drawn resolution, consume-attempt yields (inc attempts-used)
             ;; and is idempotent in outcome across independent calls.
             (= (:disagreement/attempts-used consumed)
                (:disagreement/attempts-used (core/consume-attempt d))
                (inc attempts-used))

             ;; The stable :disagreement/id is preserved across consumption.
             (= (:disagreement/id consumed) id)))))]
    (is (:pass? result) (pr-str result))))

;; Concrete examples: allowance is exactly two, then one, then exhausted; a
;; rejected attempt consumes identically to an accepted one.

(deftest property-12-example-allowance-counts-down-to-exhaustion
  (let [id (random-uuid)
        d0 (disagreement id 0)
        d1 (core/consume-attempt d0)
        d2 (core/consume-attempt d1)]
    (is (= 2 (core/allowance-remaining d0)))
    (is (= 1 (core/allowance-remaining d1)))
    (is (= 0 (core/allowance-remaining d2)))
    ;; Consuming past exhaustion keeps allowance clamped at zero.
    (is (= 0 (core/allowance-remaining (core/consume-attempt d2))))))

(deftest property-12-example-rejected-consumes-like-accepted
  (let [id (random-uuid)
        d (disagreement id 0)]
    ;; consume-attempt takes only the disagreement; a rejected vs. accepted
    ;; resolution cannot change the increment (R-15.2).
    (is (= 1 (:disagreement/attempts-used (core/consume-attempt d))))))

;; Feature: orchestrator-state-machine, Property 10: Agreed outcomes stay binding
;; until an accepted replacement supersedes them.
;;
;; For any previously agreed outcome, a decision remains binding
;; (:decision/status = :binding) until a replacement decision is accepted through
;; reconciliation (which flips the prior decision to :superseded). Any proposed
;; repair/outcome that would contradict a binding decision on the same
;; :decision/subject is flagged by `binding-conflict?` (it returns the conflicting
;; binding decision), and the repair is rejected until the outcome is formally
;; replaced. A proposed outcome that agrees with a binding decision, that
;; addresses an unrelated subject, or that only "contradicts" a superseded
;; decision is NOT flagged. Every subsequent repair is checked against the whole
;; set of binding decisions.
;;
;; Validates: Requirements 13.1, 13.2, 13.3, 14.3

(defn- decision
  "A minimal decision record keyed to its stable :decision/id, scoped to a
  descriptive :decision/subject and carrying its agreed :decision/statement and
  :decision/status (:binding | :superseded) (R-13, R-17)."
  [subject statement status]
  {:decision/id (random-uuid)
   :decision/subject subject
   :decision/statement statement
   :decision/status status})

(defn- proposed-outcome
  "A minimal proposed repair/outcome: the subject it acts on and the outcome
  (body) it would produce for that subject (R-12, R-13.2)."
  [subject body]
  {:proposal/subject subject
   :proposal/body body})

(deftest property-10-agreed-outcomes-stay-binding-until-superseded
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [subject       gen/string-alphanumeric
           other-subject gen/string-alphanumeric
           statement     (gen/such-that seq gen/string-alphanumeric)
           other-body    (gen/such-that seq gen/string-alphanumeric)
           status        (gen/elements [:binding :superseded])]
          (let [d          (decision subject statement status)
                ;; A proposal on the SAME subject whose body differs from the
                ;; decision's statement contradicts that decision.
                contradicting (proposed-outcome subject (str other-body "!diff"))
                ;; A proposal on the SAME subject that restates the agreed
                ;; outcome does NOT contradict it.
                agreeing      (proposed-outcome subject statement)
                ;; A proposal on a DIFFERENT subject is unrelated.
                unrelated     (proposed-outcome (str other-subject "~other") "anything")
                binding?      (= status :binding)]
            (and
             ;; A contradicting proposal is flagged iff the decision is binding;
             ;; when flagged, the flagged value IS the conflicting decision.
             (if binding?
               (= d (core/binding-conflict? contradicting [d]))
               (nil? (core/binding-conflict? contradicting [d])))

             ;; A proposal that agrees with the decision's statement never
             ;; conflicts, binding or not.
             (nil? (core/binding-conflict? agreeing [d]))

             ;; A proposal on an unrelated subject never conflicts.
             (nil? (core/binding-conflict? unrelated [d]))

             ;; A superseded decision never blocks a repair (R-13.1: only a
             ;; still-binding decision is enforced).
             (if (= status :superseded)
               (nil? (core/binding-conflict? contradicting [d]))
               true)))))]
    (is (:pass? result) (pr-str result))))

;; Concrete examples: a binding decision blocks a contradicting repair and stays
;; binding until a replacement supersedes it; a superseded decision does not
;; block; every subsequent repair is checked against the whole binding set.

(deftest property-10-example-binding-blocks-until-superseded
  (let [binding-d   (decision "empty-password" "Reject empty passwords." :binding)
        contradict  (proposed-outcome "empty-password" "Allow empty passwords.")
        supersede-d (assoc binding-d :decision/status :superseded)]
    ;; A repair contradicting the binding decision is flagged (rejected).
    (is (= binding-d (core/binding-conflict? contradict [binding-d])))
    ;; Once the decision is superseded, the same repair is no longer blocked.
    (is (nil? (core/binding-conflict? contradict [supersede-d])))
    ;; No binding decisions => nothing to conflict with.
    (is (nil? (core/binding-conflict? contradict [])))))

(deftest property-10-example-repair-checked-against-whole-binding-set
  (let [d1 (decision "logging" "Log at INFO." :binding)
        d2 (decision "auth"    "Require MFA."  :binding)
        d3 (decision "cache"   "TTL is 60s."   :superseded)
        decisions [d1 d2 d3]]
    ;; Contradicts a binding decision buried in the set => flags THAT decision.
    (is (= d2 (core/binding-conflict? (proposed-outcome "auth" "Drop MFA.") decisions)))
    ;; Contradicts only a superseded decision => not flagged.
    (is (nil? (core/binding-conflict? (proposed-outcome "cache" "TTL is 5s.") decisions)))
    ;; Agrees with every binding decision it touches => not flagged.
    (is (nil? (core/binding-conflict? (proposed-outcome "logging" "Log at INFO.") decisions)))))

;; Feature: orchestrator-state-machine, Property 11: Reconsideration requires a
;; specific decision and new evidence.
;;
;; A reconsideration request is admissible (`reconsideration-admissible?` true)
;; if and only if it BOTH identifies a specific decision
;; (:reconsideration/decision-id present) AND supplies new evidence
;; (:reconsideration/new-evidence present and non-blank). A request lacking
;; either — no decision identified, or no/blank new evidence — is inadmissible.
;; Admissibility only permits reconsideration; it does not authorize change (the
;; prior decision stays binding until a replacement is accepted).
;;
;; Validates: Requirements 14.1, 14.2

(defn- reconsideration
  "A minimal reconsideration request identifying a specific decision and
  supplying new evidence (R-14.1)."
  [decision-id new-evidence]
  (cond-> {}
    decision-id  (assoc :reconsideration/decision-id decision-id)
    (some? new-evidence) (assoc :reconsideration/new-evidence new-evidence)))

(deftest property-11-reconsideration-requires-decision-and-new-evidence
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [has-decision?  gen/boolean
           evidence-good? gen/boolean
           decision-id    gen/uuid
           good-evidence  non-blank-gen
           bad-evidence   blank-gen]
          (let [request      (reconsideration (when has-decision? decision-id)
                                              (if evidence-good? good-evidence bad-evidence))
                admissible?  (and has-decision? evidence-good?)]
            (= (core/reconsideration-admissible? request) admissible?)))) ]
    (is (:pass? result) (pr-str result))))

;; Concrete examples: both present => admissible; missing either => inadmissible.

(deftest property-11-example-admissible-requires-both
  (let [id (random-uuid)]
    ;; Both a specific decision AND new evidence => admissible.
    (is (core/reconsideration-admissible?
         (reconsideration id "New load test shows the 60s TTL causes stampedes.")))
    ;; New evidence but NO specific decision => inadmissible.
    (is (not (core/reconsideration-admissible?
              (reconsideration nil "New evidence with no target decision."))))
    ;; A specific decision but NO new evidence => inadmissible.
    (is (not (core/reconsideration-admissible? (reconsideration id nil))))
    ;; A specific decision but BLANK new evidence => inadmissible.
    (is (not (core/reconsideration-admissible? (reconsideration id "   "))))
    ;; Neither => inadmissible.
    (is (not (core/reconsideration-admissible? (reconsideration nil nil))))))

;; Feature: orchestrator-state-machine, Property 4: A reported test conflict is
;; routed to the test-designer, never repaired by the implementer.
;;
;; For any :test-conflict reported by the implementer, `transition` for
;; [:implement :test-conflict] routes to :test-design carrying the
;; :route-conflict-to-test-designer effect and mints NO new iteration (no
;; :begin-iteration effect); the current iteration is preserved (no new
;; trace-id), so the implementer modifies no test — resolution belongs to the
;; test-designer (R-3.3, R-3.4).
;;
;; Validates: Requirements 3.3, 3.4

(defn- has-effect?
  "True iff `result`'s effects contain an effect of `effect-type`."
  [result effect-type]
  (some #(= effect-type (:effect/type %)) (:effects result)))

(deftest property-4-test-conflict-routes-to-test-designer
  ;; A :test-conflict is only ever meaningful when reported from :implement, so
  ;; the state is fixed; the round-trip event is drawn from a grab-bag that
  ;; includes the legitimate :test-conflict alongside noise and malformed
  ;; events, so the property also pins that routing happens ONLY for the
  ;; conflict event and never mints an iteration for anything reported here.
  (let [result
        (tc/quick-check
         100
         (prop/for-all [event (gen/elements all-events)]
                       (let [r (core/transition :implement event)]
                         (if (= event :test-conflict)
                           (and
                            ;; Routes resolution to the test-designer (R-3.4).
                            (= :test-design (:next-state r))
                            ;; Carries the routing effect naming the handoff.
                            (has-effect? r :route-conflict-to-test-designer)
                            ;; Mints NO new iteration: no :begin-iteration effect,
                            ;; so the CURRENT iteration (trace-id) is preserved —
                            ;; the implementer neither repairs nor rewrites the
                            ;; test (R-3.3).
                            (not (has-effect? r :begin-iteration)))
                           ;; No other event reported from :implement is ever
                           ;; routed to the test-designer via the conflict effect
                           ;; and none of them mint an iteration here either.
                           (and (not (has-effect? r :route-conflict-to-test-designer))
                                (not (has-effect? r :begin-iteration)))))))]
    (is (:pass? result) (pr-str result))))

;; Concrete example: the exact routing contract for the reported conflict.

(deftest property-4-example-conflict-routes-and-preserves-iteration
  (let [r (core/transition :implement :test-conflict)]
    ;; Routed to the test-designer for resolution (R-3.4).
    (is (= :test-design (:next-state r)))
    ;; Carries the :route-conflict-to-test-designer effect.
    (is (has-effect? r :route-conflict-to-test-designer))
    ;; Mints NO new iteration — the current iteration is preserved (R-3.3).
    (is (not (has-effect? r :begin-iteration)))
    ;; Fail-closed: no :error on this legal, expected transition.
    (is (not (contains? r :error)))))

;; Feature: orchestrator-state-machine, Property 2: Any change outside a role's
;; capability fails closed and never advances.
;;
;; For any role capability descriptor (:test-authoring, :production-authoring,
;; :read-only) and any set of produced file changes (edits and new-file
;; creations), `capability-violation?` returns a violation exactly when a change
;; falls outside that role's allowed set — a test-designer (:test-authoring)
;; touching a production/implementation file, an implementer
;; (:production-authoring) authoring or modifying any test file, or a reviewer
;; (:read-only) editing any file — and returns nil when every change is within
;; boundary. The boundaries are symmetric: test authorship belongs exclusively
;; to :test-authoring and production authorship exclusively to
;; :production-authoring. A returned violation drives a fail-closed transition
;; that never advances the pipeline (the transition itself is an action).
;;
;; Validates: Requirements 1.4, 1.5, 3.1, 3.2, 3.5, 3.6, 11.2, 16.6

(def ^:private capabilities
  [:test-authoring :production-authoring :read-only])

;; The set of change classes each descriptor MAY write — the expected boundary
;; this property checks `capability-violation?` against. Symmetric by design:
;; the test-designer owns tests, the implementer owns production, the reviewer
;; owns nothing.
(def ^:private allowed-classes
  {:test-authoring       #{:test}
   :production-authoring #{:production}
   :read-only            #{}})

(def ^:private change-gen
  "A produced change: a path, a :created|:edited kind (both count), and the
  observing action's :test|:production classification."
  (gen/hash-map
   :path   (gen/such-that seq gen/string-alphanumeric)
   :change (gen/elements [:created :edited])
   :class  (gen/elements [:test :production])))

(deftest property-2-capability-violation-fails-closed-outside-boundary
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [capability (gen/elements capabilities)
           changes    (gen/vector change-gen 0 12)]
          (let [allowed    (allowed-classes capability)
                violation  (core/capability-violation? capability changes)
                offending  (filter #(not (contains? allowed (:class %))) changes)]
            (and
             ;; A violation is returned EXACTLY when some change falls outside
             ;; the descriptor's allowed set; nil exactly when all are within.
             (= (some? violation) (boolean (seq offending)))

             ;; When flagged, the returned value IS a produced change that lies
             ;; outside the allowed set — new-file creation and edits both
             ;; count, the decision being over :class (R-1.5, R-3.6, R-11.3).
             (if violation
               (and (not (contains? allowed (:class violation)))
                    (some #(= violation %) changes))
               true)

             ;; Every change within the allowed set produces no violation on its
             ;; own — the boundaries are symmetric over :class.
             (nil? (core/capability-violation?
                    capability
                    (filter #(contains? allowed (:class %)) changes)))))))]
    (is (:pass? result) (pr-str result))))

;; Concrete examples: each descriptor's symmetric boundary, fail-closed.

(def ^:private test-change      {:path "test/foo_test.clj" :change :edited  :class :test})
(def ^:private test-new-change  {:path "test/bar_test.clj" :change :created :class :test})
(def ^:private prod-change      {:path "src/foo.clj"       :change :edited  :class :production})
(def ^:private prod-new-change  {:path "src/bar.clj"       :change :created :class :production})

(deftest property-2-example-test-designer-walled-out-of-production
  ;; :test-authoring may author/edit tests, but touching a production file is a
  ;; violation — new-file creation and edits both (R-1.4, R-1.5).
  (is (nil? (core/capability-violation? :test-authoring [test-change test-new-change])))
  (is (= prod-change
         (core/capability-violation? :test-authoring [test-change prod-change])))
  (is (= prod-new-change
         (core/capability-violation? :test-authoring [prod-new-change]))))

(deftest property-2-example-implementer-walled-out-of-tests
  ;; :production-authoring may author/edit production, but authoring or modifying
  ;; any test is a violation (R-3.1, R-3.2, R-3.5, R-3.6).
  (is (nil? (core/capability-violation? :production-authoring [prod-change prod-new-change])))
  (is (= test-change
         (core/capability-violation? :production-authoring [prod-change test-change])))
  (is (= test-new-change
         (core/capability-violation? :production-authoring [test-new-change]))))

(deftest property-2-example-reviewer-may-write-nothing
  ;; :read-only allows no change class at all — ANY file edit or creation, test
  ;; or production, is a violation (R-11.2, R-11.3).
  (is (nil? (core/capability-violation? :read-only [])))
  (is (= test-change (core/capability-violation? :read-only [test-change])))
  (is (= prod-change (core/capability-violation? :read-only [prod-change]))))

(deftest property-2-example-boundaries-are-symmetric
  ;; Test authorship belongs exclusively to :test-authoring and production
  ;; authorship exclusively to :production-authoring (R-16.6): swap the role and
  ;; the same change flips between allowed and violation.
  (is (nil? (core/capability-violation? :test-authoring [test-change])))
  (is (= test-change (core/capability-violation? :production-authoring [test-change])))
  (is (nil? (core/capability-violation? :production-authoring [prod-change])))
  (is (= prod-change (core/capability-violation? :test-authoring [prod-change]))))

(deftest property-2-example-unknown-capability-allows-nothing
  ;; An unknown/absent capability allows nothing, so any change is a violation
  ;; (fail closed).
  (is (= test-change (core/capability-violation? :no-such-capability [test-change])))
  (is (= prod-change (core/capability-violation? nil [prod-change]))))
