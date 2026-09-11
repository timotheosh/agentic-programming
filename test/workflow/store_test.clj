(ns workflow.store-test
  "Round-trip and durable-resume example tests for `workflow.store` (R-17).

  These are ACTION tests: they open a real Datalevin connection against a TEMP
  database directory, write durable facts, close, REOPEN, and read the facts
  back. They confirm the durability guarantees the design leans on:

    * schema round-trip — what is written under the schema survives close/reopen
      and reads back unchanged (R-17.3);
    * the append-only transition-event stream reconstructs current state via
      `derive-current-state` (delegating to `workflow.core/current-state`), even
      after a reopen (R-17.1);
    * the materialized `:*/state` scalar equals the state DERIVED from the
      highest-`:event/seq` event — because the append and the materialized update
      share one ACID transaction, derived can never diverge from history
      (R-17.1, R-17.4);
    * a superseded decision and its replacement both remain queryable after a
      reopen, and the `:iteration/revision` counter survives reopen (R-17.4).

  Each test uses a fresh temp Datalevin directory, cleaned up after (fixture).
  The `:test` alias carries the Datalevin `--add-opens`/`--enable-native-access`
  JVM opts so `store/connect` can open the LMDB-backed connection. The Property
  13 PBT is a separate downstream task (4.6); nothing property-based lives here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datalevin.core :as d]
            [workflow.core :as core]
            [workflow.store :as store]))

;; --- temp Datalevin directory fixture ----------------------------------------
;;
;; Mirrors the temp-dir fixture in workflow.fs-test: create a fresh directory per
;; test, bind it, and delete it (and everything beneath it) afterwards. Datalevin
;; opens an LMDB database directory here; deleting it recursively cleans up the
;; on-disk store so tests never share durable state.

(def ^:dynamic *tmp-dir* nil)

(defn- create-temp-dir!
  "Create a fresh temp directory and return it as a java.io.File."
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "workflow-store-test"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-recursively!
  "Delete `file` and everything beneath it."
  [file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-recursively! child)))
  (.delete file))

(use-fixtures :each
  (fn [t]
    (let [dir (create-temp-dir!)]
      (binding [*tmp-dir* dir]
        (try (t)
             (finally (delete-recursively! dir)))))))

(defn- db-path
  "The Datalevin database directory path for this test (under *tmp-dir*)."
  []
  (str (java.io.File. *tmp-dir* "db")))

;; --- schema round-trip: open, write, close, reopen, read (R-17.3) ------------

(deftest schema-round-trip-survives-close-reopen
  (testing "entities written under the schema read back unchanged after reopen"
    (let [run-id (random-uuid)
          created (java.util.Date.)
          reqs "verbatim requirements text"]
      ;; open -> write -> close
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:run/id run-id
                              :run/created-at created
                              :run/requirements reqs
                              :run/state :init}])
          (finally (store/close conn))))
      ;; reopen -> read the same durable state back
      (let [conn (store/connect (db-path))]
        (try
          (let [pulled (d/pull (d/db conn)
                               [:run/id :run/created-at :run/requirements :run/state]
                               [:run/id run-id])]
            (is (= run-id (:run/id pulled)) "uuid identity round-trips")
            (is (= created (:run/created-at pulled)) "instant round-trips")
            (is (= reqs (:run/requirements pulled)) "verbatim string round-trips")
            (is (= :init (:run/state pulled)) "keyword state round-trips"))
          (finally (store/close conn)))))))

;; --- append-only events reconstruct current state after reopen (R-17.1) ------

(deftest append-only-events-reconstruct-current-state
  (testing "the event stream, read back after reopen, rebuilds current state"
    (let [run-id (random-uuid)]
      ;; open -> seed a run entity, append a chain of transition events -> close
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:db/id "run" :run/id run-id :run/state :init}])
          ;; append three run-level transitions in order
          (store/append-transition-event
           conn {:run-eid [:run/id run-id] :from-state :init :to-state :planning
                 :trigger :start})
          (store/append-transition-event
           conn {:run-eid [:run/id run-id] :from-state :planning :to-state :slicing
                 :trigger :plan-ready})
          (store/append-transition-event
           conn {:run-eid [:run/id run-id] :from-state :slicing :to-state :executing
                 :trigger :slices-ready})
          (finally (store/close conn))))
      ;; reopen -> read the stream back and rebuild current state from history
      (let [conn (store/connect (db-path))]
        (try
          (let [run-eid* (:db/id (d/pull (d/db conn) [:db/id] [:run/id run-id]))
                stream (store/event-stream conn run-eid*)]
            (is (= 3 (count stream)) "all three appended events survive the reopen")
            (is (= [:planning :slicing :executing]
                   (mapv :event/to-state stream))
                "events read back in :event/seq order")
            (is (= [0 1 2] (mapv :event/seq stream))
                "the monotonic per-run seq is preserved and totally orders the stream")
            ;; current state derived purely from the append-only history
            (is (= :executing
                   (store/derive-current-state conn run-eid* run-eid* :event/run))
                "current state == :event/to-state of the highest-:event/seq event")
            ;; same derivation done purely over the read-back stream
            (is (= :executing
                   (core/current-state stream run-eid* :event/run))
                "core/current-state agrees over the reconstructed stream"))
          (finally (store/close conn)))))))

;; --- derived == materialized, across a reopen (R-17.1, R-17.4) ---------------

(deftest materialized-state-equals-derived-state
  (testing "materialized :run/state equals state derived from the highest-seq event"
    (let [run-id (random-uuid)]
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:db/id "run" :run/id run-id :run/state :init}])
          (store/append-transition-event
           conn {:run-eid [:run/id run-id] :from-state :init :to-state :planning
                 :trigger :start})
          (store/append-transition-event
           conn {:run-eid [:run/id run-id] :from-state :planning :to-state :executing
                 :trigger :go})
          (finally (store/close conn))))
      (let [conn (store/connect (db-path))]
        (try
          (let [run-eid* (:db/id (d/pull (d/db conn) [:db/id] [:run/id run-id]))
                materialized (:run/state (d/pull (d/db conn) [:run/state] run-eid*))
                derived (store/derive-current-state conn run-eid* run-eid* :event/run)]
            (is (= :executing materialized) "materialized :run/state reflects the last transition")
            (is (= derived materialized)
                "derived (from history) == materialized (scalar): one ACID commit keeps them in sync"))
          (finally (store/close conn))))))
  (testing "a slice's materialized :slice/state equals its derived state after reopen"
    (let [run-id (random-uuid)
          slice-id (random-uuid)]
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:db/id "run" :run/id run-id :run/state :executing}
                             {:db/id "slice" :slice/id slice-id :slice/run "run"
                              :slice/order 0 :slice/state :pending}])
          (store/append-transition-event
           conn {:run-eid [:run/id run-id]
                 :slice-eid [:slice/id slice-id]
                 :from-state :pending :to-state :test-design :trigger :begin})
          (store/append-transition-event
           conn {:run-eid [:run/id run-id]
                 :slice-eid [:slice/id slice-id]
                 :from-state :test-design :to-state :implement :trigger :red-verified})
          (finally (store/close conn))))
      (let [conn (store/connect (db-path))]
        (try
          (let [run-eid* (:db/id (d/pull (d/db conn) [:db/id] [:run/id run-id]))
                slice-eid* (:db/id (d/pull (d/db conn) [:db/id] [:slice/id slice-id]))
                materialized (:slice/state (d/pull (d/db conn) [:slice/state] slice-eid*))
                derived (store/derive-current-state conn run-eid* slice-eid*)]
            (is (= :implement materialized))
            (is (= derived materialized)
                "per-slice derived == materialized survives close/reopen"))
          (finally (store/close conn))))))
  (testing "run-level events do not materialize a slice's state (only its own)"
    (let [run-id (random-uuid)
          slice-id (random-uuid)]
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:db/id "run" :run/id run-id :run/state :init}
                             {:db/id "slice" :slice/id slice-id :slice/run "run"
                              :slice/order 0 :slice/state :pending}])
          ;; a run-level transition (no :slice-eid) must not touch :slice/state
          (store/append-transition-event
           conn {:run-eid [:run/id run-id] :from-state :init :to-state :executing
                 :trigger :go})
          (finally (store/close conn))))
      (let [conn (store/connect (db-path))]
        (try
          (let [run-eid* (:db/id (d/pull (d/db conn) [:db/id] [:run/id run-id]))
                slice-eid* (:db/id (d/pull (d/db conn) [:db/id] [:slice/id slice-id]))]
            (is (= :executing (:run/state (d/pull (d/db conn) [:run/state] run-eid*)))
                "run-level materialized state advanced")
            (is (= :pending (:slice/state (d/pull (d/db conn) [:slice/state] slice-eid*)))
                "slice materialized state untouched by a run-level event")
            (is (nil? (store/derive-current-state conn run-eid* slice-eid*))
                "no event belongs to the slice -> derived slice state is nil"))
          (finally (store/close conn)))))))

;; --- durable resume: superseded decision + Revision counter survive reopen ---

(deftest superseded-decision-and-replacement-both-queryable-after-reopen
  (testing "both the superseded decision and its replacement read back after reopen"
    (let [run-id (random-uuid)
          slice-id (random-uuid)
          old-id (random-uuid)
          new-id (random-uuid)]
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:db/id "run" :run/id run-id :run/state :executing}
                             {:db/id "slice" :slice/id slice-id :slice/run "run"
                              :slice/order 0 :slice/state :implement}])
          ;; record the original decision, then supersede it with a new entity
          (d/transact! conn [{:decision/id old-id
                              :decision/slice [:slice/id slice-id]
                              :decision/subject "naming"
                              :decision/statement "use camelCase"
                              :decision/status :binding
                              :decision/created-at (java.util.Date.)}])
          (d/transact! conn [{:decision/id old-id :decision/status :superseded}
                             {:decision/id new-id
                              :decision/slice [:slice/id slice-id]
                              :decision/subject "naming"
                              :decision/statement "use kebab-case"
                              :decision/status :binding
                              :decision/supersedes [:decision/id old-id]
                              :decision/created-at (java.util.Date.)}])
          (finally (store/close conn))))
      (let [conn (store/connect (db-path))]
        (try
          (let [slice-eid* (:db/id (d/pull (d/db conn) [:db/id] [:slice/id slice-id]))
                all (store/decisions-for-slice conn slice-eid*)
                binding (store/decisions-for-slice conn slice-eid* :binding)
                superseded (store/decisions-for-slice conn slice-eid* :superseded)
                by-id (into {} (map (juxt :decision/id identity)) all)]
            (is (= 2 (count all)) "both decisions retained and queryable after reopen")
            (is (= #{old-id new-id} (set (map :decision/id all))))
            (is (= :superseded (:decision/status (by-id old-id)))
                "the replaced decision is retained as :superseded")
            (is (= :binding (:decision/status (by-id new-id)))
                "the replacement is :binding")
            (is (= [new-id] (map :decision/id binding))
                "status filter narrows to the binding decision")
            (is (= [old-id] (map :decision/id superseded))
                "status filter narrows to the superseded decision")
            (is (some? (:decision/supersedes (by-id new-id)))
                "the replacement links to the decision it supersedes"))
          (finally (store/close conn)))))))

(deftest revision-counter-survives-reopen
  (testing "the :iteration/revision counter reads back at the same value after reopen"
    (let [run-id (random-uuid)
          slice-id (random-uuid)
          iter-id (random-uuid)
          step-id (random-uuid)]
      (let [conn (store/connect (db-path))]
        (try
          (d/transact! conn [{:db/id "run" :run/id run-id :run/state :executing}
                             {:db/id "slice" :slice/id slice-id :slice/run "run"
                              :slice/order 0 :slice/state :implement}
                             {:db/id "iter" :iteration/id iter-id
                              :iteration/slice "slice" :iteration/number 1
                              :iteration/revision 0}
                             {:step/id step-id :step/iteration "iter"
                              :step/role :implementer :step/status :dispatched
                              :step/dispatched-at (java.util.Date.)}])
          ;; record an implementer Step outcome, advancing the counter in one commit
          (store/record-step-outcome
           conn {:step-eid [:step/id step-id]
                 :iteration-eid [:iteration/id iter-id]
                 :status :complete
                 :result-ref "artifact"
                 :advance-revision? true})
          ;; confirm the advance before close
          (is (= 1 (store/current-revision conn [:iteration/id iter-id]))
              "counter advanced to 1 in the outcome transaction")
          (finally (store/close conn))))
      (let [conn (store/connect (db-path))]
        (try
          (let [iter-eid* (:db/id (d/pull (d/db conn) [:db/id] [:iteration/id iter-id]))]
            (is (= 1 (store/current-revision conn iter-eid*))
                "the advanced Revision counter value survives the reopen")
            (let [pulled-step (d/pull (d/db conn)
                                      [:step/status :step/result-ref]
                                      [:step/id step-id])]
              (is (= :complete (:step/status pulled-step))
                  "the recorded Step outcome survives the reopen")
              (is (= "artifact" (:step/result-ref pulled-step))
                  "the Step result-ref survives the reopen")))
          (finally (store/close conn)))))))

;; --- Property 13: History is complete, durable, and every decision is binding
;;     or explicitly superseded (R-8.10, R-17.1, R-17.2, R-17.3, R-17.4) --------
;;
;; Feature: orchestrator-state-machine, Property 13: History is complete,
;; durable, and every decision is binding or explicitly superseded.
;;
;; Where the example tests above pin down specific hand-written durability
;; scenarios, this property exercises the SAME durability guarantees across
;; generated sequences of recorded facts. Each iteration opens a FRESH temp
;; Datalevin directory, writes a generated stream of transition events and a
;; generated chain of decisions (each new decision superseding the prior one),
;; CLOSES and REOPENS the store, and only then reads everything back to assert:
;;
;;   (a) every recorded fact survives close/reopen (R-17.3): all events and all
;;       decisions read back with the same count and content;
;;   (b) each persisted decision is `:binding` or carries a `:decision/supersedes`
;;       link, and BOTH the replacement and the superseded decision stay
;;       queryable (R-17.2) — queried by status via `decisions-for-slice`;
;;   (c) the state derived from the append-only event stream
;;       (`derive-current-state`) equals the materialized `:*/state`, because the
;;       append and the materialized update share one ACID transaction
;;       (R-17.1, R-17.4);
;;   (d) the accepted-decision history is preserved and queryable even as
;;       approvals go stale — an implementer Step outcome advances the Revision
;;       counter (making the recorded approval stale by `core/approval-valid?`),
;;       yet the full accepted-decision history remains queryable (R-8.10).
;;
;; Datalevin conns are real LMDB directories, so opening/closing 100 times is
;; I/O-heavy; generated sequences are kept modest (1-8 events, 1-8 decisions) so
;; the run stays reasonable. The `:test` alias supplies the Datalevin JVM opts.

(defn- fresh-db-dir!
  "Create a fresh, unique Datalevin db directory under a new temp dir (action).

  Each property iteration needs its own on-disk store so iterations never share
  durable state. Returns a `[dir db-path]` pair: `dir` is the temp directory to
  delete afterwards, `db-path` is the Datalevin directory beneath it."
  []
  (let [dir (create-temp-dir!)]
    [dir (str (java.io.File. dir "db"))]))

(def ^:private state-gen
  "A workflow state keyword used as a transition destination."
  (gen/elements [:init :planning :slicing :executing :test-design :implement
                 :review :repair :done]))

(def ^:private transition-gen
  "One generated run-level transition: a destination state and a trigger."
  (gen/hash-map :to-state state-gen
                :trigger (gen/elements [:start :advance :go :retry :ready])))

(def ^:private decision-step-gen
  "One generated decision link in a supersede chain: subject + statement."
  (gen/hash-map :subject (gen/such-that seq gen/string-alphanumeric)
                :statement (gen/such-that seq gen/string-alphanumeric)))

(defn- write-facts!
  "Write the generated events + decision chain into a fresh store (action).

  Seeds a run, a slice, an iteration bound to a recorded approval, and a
  dispatched implementer Step; appends the `transitions` as run-level transition
  events (each materializing `:run/state` in the same ACID commit); records the
  `decision-steps` as a supersede chain (each new decision `:binding`, the prior
  flipped to `:superseded` and linked via `:decision/supersedes`); then records
  the Step outcome, advancing the Revision counter so the earlier approval goes
  stale. Returns the identity map the reopen phase reads back by."
  [conn transitions decision-steps]
  (let [run-id (random-uuid)
        slice-id (random-uuid)
        iter-id (random-uuid)
        step-id (random-uuid)
        review-id (random-uuid)
        approval-id (random-uuid)]
    (d/transact! conn [{:db/id "run" :run/id run-id :run/state :init}
                       {:db/id "slice" :slice/id slice-id :slice/run "run"
                        :slice/order 0 :slice/state :pending}
                       {:db/id "iter" :iteration/id iter-id
                        :iteration/slice "slice" :iteration/number 1
                        :iteration/revision 0}
                       {:db/id "review" :review/id review-id
                        :review/iteration "iter" :review/revision-counter 0
                        :review/reviewer :correctness :review/verdict :approve}
                       ;; an approval bound to Revision counter value 0
                       {:approval/id approval-id :approval/review "review"
                        :approval/reviewer :correctness
                        :approval/revision-counter 0 :approval/verdict :approve
                        :approval/at (java.util.Date.)}
                       {:step/id step-id :step/iteration "iter"
                        :step/role :implementer :step/status :dispatched
                        :step/dispatched-at (java.util.Date.)}])
    ;; append the generated run-level transition events, in order
    (let [from-states (cons :init (map :to-state transitions))]
      (doseq [[tr from-state] (map vector transitions from-states)]
        (store/append-transition-event
         conn {:run-eid [:run/id run-id]
               :from-state from-state
               :to-state (:to-state tr)
               :trigger (:trigger tr)})))
    ;; record the decisions as a supersede chain; every new decision is
    ;; :binding, the immediately-prior one becomes :superseded and is linked
    (loop [steps decision-steps prev-id nil ids []]
      (if-let [step (first steps)]
        (let [id (random-uuid)]
          (d/transact!
           conn (cond-> [{:decision/id id
                          :decision/slice [:slice/id slice-id]
                          :decision/iteration [:iteration/id iter-id]
                          :decision/subject (:subject step)
                          :decision/statement (:statement step)
                          :decision/status :binding
                          :decision/accepted-by [:correctness :structural]
                          :decision/created-at (java.util.Date.)}]
                  prev-id (conj {:decision/id prev-id :decision/status :superseded})
                  prev-id (conj {:decision/id id
                                 :decision/supersedes [:decision/id prev-id]})))
          (recur (rest steps) id (conj ids id)))
        ;; finally: record the implementer Step outcome, advancing the counter
        (do
          (store/record-step-outcome
           conn {:step-eid [:step/id step-id]
                 :iteration-eid [:iteration/id iter-id]
                 :status :complete :result-ref "artifact"
                 :advance-revision? true})
          {:run-id run-id :slice-id slice-id :iter-id iter-id
           :decision-ids ids :approval-id approval-id
           :expected-final-state (:to-state (last transitions))})))))

(deftest property-13-history-complete-durable-decisions-binding-or-superseded
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [transitions (gen/vector transition-gen 1 8)
           decision-steps (gen/vector decision-step-gen 1 8)]
          (let [[dir db-path] (fresh-db-dir!)]
            (try
              ;; write -> close, then REOPEN and read everything back
              (let [ids (let [conn (store/connect db-path)]
                          (try (write-facts! conn transitions decision-steps)
                               (finally (store/close conn))))
                    conn (store/connect db-path)]
                (try
                  (let [run-eid* (:db/id (d/pull (d/db conn) [:db/id] [:run/id (:run-id ids)]))
                        slice-eid* (:db/id (d/pull (d/db conn) [:db/id] [:slice/id (:slice-id ids)]))
                        iter-eid* (:db/id (d/pull (d/db conn) [:db/id] [:iteration/id (:iter-id ids)]))
                        stream (store/event-stream conn run-eid*)
                        all-dec (store/decisions-for-slice conn slice-eid*)
                        binding (store/decisions-for-slice conn slice-eid* :binding)
                        superseded (store/decisions-for-slice conn slice-eid* :superseded)
                        by-id (into {} (map (juxt :decision/id identity)) all-dec)
                        materialized (:run/state (d/pull (d/db conn) [:run/state] run-eid*))
                        derived (store/derive-current-state conn run-eid* run-eid* :event/run)
                        approvals (store/approvals-for-iteration conn iter-eid*)
                        current-rev (store/current-revision conn iter-eid*)
                        n-dec (count decision-steps)]
                    (and
                       ;; (a) recorded facts survive close/reopen (R-17.3)
                     (= (count transitions) (count stream))
                     (= (mapv :to-state transitions) (mapv :event/to-state stream))
                     (= (vec (range (count transitions))) (mapv :event/seq stream))
                     (= n-dec (count all-dec))
                     (= (set (:decision-ids ids)) (set (map :decision/id all-dec)))
                       ;; (b) every persisted decision is :binding OR
                       ;; :superseded — a decision retained under an explicit
                       ;; supersede link never sits in any other status
                       ;; (R-17.2). Both the replacement and the superseded
                       ;; decision remain queryable via decisions-for-slice.
                     (every? #(#{:binding :superseded} (:decision/status %)) all-dec)
                       ;; exactly the newest decision is :binding; every older
                       ;; one is :superseded (a replacement is a NEW entity, the
                       ;; predecessor is retained, never overwritten).
                     (= 1 (count binding))
                     (= (dec n-dec) (count superseded))
                     (= (last (:decision-ids ids)) (:decision/id (first binding)))
                     (every? #(= :binding (:decision/status %)) binding)
                     (every? #(= :superseded (:decision/status %)) superseded)
                       ;; every REPLACEMENT (each decision after the first)
                       ;; carries an explicit outgoing :decision/supersedes link
                       ;; to the decision it replaced; the oldest decision, which
                       ;; replaced nothing, carries no such link (R-17.2).
                     (= (dec n-dec)
                        (count (filter #(some? (:decision/supersedes %)) all-dec)))
                       ;; (c) derived (from append-only history) == materialized
                       ;; :*/state (R-17.1, R-17.4)
                     (= (:expected-final-state ids) derived)
                     (= derived materialized)
                       ;; (d) history preserved even as approvals go stale
                       ;; (R-8.10): the Step outcome advanced the counter to 1,
                       ;; so the approval bound to 0 is now stale, yet the full
                       ;; accepted-decision history is still queryable
                     (= 1 current-rev)
                     (= 1 (count approvals))
                     (not (core/approval-valid? (first approvals) current-rev))
                     (every? #(= #{:correctness :structural}
                                 (set (:decision/accepted-by (by-id %))))
                             (:decision-ids ids))))
                  (finally (store/close conn))))
              (finally (delete-recursively! dir))))))]
    (is (:pass? result)
        (str "Property 13 failed with: " (pr-str (:shrunk result))))
    (is (= 100 (:num-tests result))
        "ran the minimum 100 iterations")))
