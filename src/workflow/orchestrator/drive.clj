(ns workflow.orchestrator.drive
  "The drive loop (design, Orchestrator loop with two-phase dispatch). Split
  out of the original monolithic `workflow.orchestrator` (user-directed
  reorganization).

  The drive loop is where the pure state machine actually runs against the
  world: it feeds a recorded EVENT through `core/transition`, PERFORMS the
  resulting effects via `workflow.orchestrator.core/perform-effect`, and
  continues until a terminal state is reached or no further effect advances
  the machine. RED and GREEN are effects, not states: a verification effect
  returns an `:event` the loop feeds BACK through `core/transition`, so the
  machine advances only on durably-decided reality.

  Fail closed is the loop's governing rule: an ABSENT or IN-DOUBT result never
  advances the machine. A `core/transition` `{:error …}`, a `perform-effect`
  `{:error …}` (an indeterminate verification, an unknown effect), or an
  unresolved (nil) event all STOP the loop rather than inferring progress from
  silence (R-1.3, R-18.5). The loop threads the current `:state` into every
  effect `ctx` so the transact effects append transitions from the right
  origin, and records the event history it walked so a caller can extend it."
  (:require [workflow.rules.core :as core]
            [workflow.orchestrator.core :as effects]))

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

  Every effect is interpreted by `workflow.orchestrator.core/perform-effect`
  with the shared `ctx`. If any effect fails closed (`{:error …}`), performing
  STOPS at that effect and the error result is returned so the caller can halt
  (R-18.5). Returns
  {:results [<effect result> …] :error <first error|nil> :event <event|nil>},
  where `:event` is the last event an effect produced (a verification effect's
  `:event`) — the value the drive loop feeds back through `core/transition`.
  This is an action: it performs the effects, which may spawn/commit."
  [ctx effs]
  (reduce
   (fn [acc effect]
     (let [result (effects/perform-effect ctx effect)]
       (if-let [err (effect-error result)]
         (reduced (-> acc
                      (update :results conj result)
                      (assoc :error err)))
         (cond-> (update acc :results conj result)
           (:event result) (assoc :event (:event result))))))
   {:results [] :error nil :event nil}
   effs))

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

  `ctx` is the effect context map (see `workflow.orchestrator.core`'s
  namespace docstring); `drive` threads the current `:state` into it for each
  effect so the transact effects append transitions from the correct origin.
  `opts` may carry `:max-steps` (a safety bound on the number of transitions,
  defaulting to a generous cap) so a misconfigured cycle cannot loop unboundedly.

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
