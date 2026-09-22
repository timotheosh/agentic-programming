(ns workflow.orchestrator.test-support
  "Shared test fixtures for the `workflow.orchestrator.*` test namespaces:
  a temp Datalevin store opened/closed per test, and the minimal
  run/slice/iteration seed every orchestrator test needs a real target to
  append transition events against. Factored out here (rather than
  duplicated across each split test file) when the original monolithic
  `orchestrator_test.clj` was split to mirror the source split under
  `src/workflow/orchestrator/` (user-directed reorganization)."
  (:require [clojure.java.io :as io]
            [datalevin.core :as d]
            [workflow.orchestrator.review :as review]
            [workflow.store :as store]))

(defn delete-tree!
  "Recursively delete `file` (a temp Datalevin dir) after a test."
  [^java.io.File file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-tree! child)))
  (.delete file))

(defn temp-dir!
  "Create and return a fresh temp directory path (string) for a Datalevin store."
  []
  (let [f (java.io.File/createTempFile "orch-test" "")]
    (.delete f)
    (.mkdirs f)
    (.getAbsolutePath f)))

(defn seed-run+slice+iteration!
  "Transact a minimal run/slice/iteration and return their entity ids so the
  transact effects have real targets to append transition events against."
  [conn]
  (let [run-id   (random-uuid)
        slice-id (random-uuid)
        iter-id  (random-uuid)
        report   (d/transact! conn
                              [{:db/id -1 :run/id run-id :run/state :planning}
                               {:db/id -2 :slice/id slice-id :slice/run -1
                                :slice/order 0 :slice/state :reconcile}
                               {:db/id -3 :iteration/id iter-id :iteration/slice -2
                                :iteration/number 0 :iteration/revision 0}])
        tempids  (:tempids report)]
    {:run-eid       (get tempids -1)
     :slice-eid     (get tempids -2)
     :iteration-eid (get tempids -3)}))

(defn seed-valid-finding!
  "Record a REQUEST_CHANGES review and one R-10-compliant `:finding` under
  Iteration `iteration-eid`, so `:begin-iteration` has a durable valid finding to
  carry (R-8.8, R-8.9). Returns the finding entity id."
  [conn iteration-eid]
  (let [{:keys [review-eid]} (review/record-review! conn {:iteration-eid iteration-eid
                                                          :reviewer :correctness
                                                          :verdict :request-changes
                                                          :revision-counter 0})
        {:keys [finding-eid]} (review/record-finding!
                               conn
                               {:review-eid review-eid
                                :owner :implementer
                                :problem "behavior B is missing"
                                :evidence "test T fails on input X"
                                :justification "requirement R-99 mandates B"
                                :required-outcome "implement B so T passes"
                                :revision-counter 0})]
    finding-eid))

(defn with-store
  "Open a temp Datalevin store, seed a run/slice/iteration, and call `f` with
  the connection and the seeded ids map. Always closes + deletes the store."
  [f]
  (let [dir  (temp-dir!)
        conn (store/connect dir)]
    (try
      (f conn (seed-run+slice+iteration! conn))
      (finally
        (store/close conn)
        (delete-tree! (io/file dir))))))
