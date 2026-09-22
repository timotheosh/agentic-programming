(ns workflow.core-test
  "Implementer-owned unit tests for the fix-round-1 wiring (F1, F2, F3, STR-1,
  STR-2, STR-3): the new composition in `workflow.orchestrator`
  (`dispatch-implementer!`, `authorize-repair!`, `run-iteration!`, the
  `:verify-existing-coverage` effect) and `workflow.core` (the entry point,
  formerly `workflow.main`: `resolve-requirements`, the `--requirements-file`
  flag, and `fresh-run!` actually driving a pass through
  `orchestrator/run-iteration!`), plus the new real produced-change
  observation in `workflow.fs` (`snapshot-dir` / `diff-snapshot`) that
  `orchestrator/dispatch-step!` now uses when a caller supplies no explicit
  `:changes`.

  These are new tests for NEW wiring this session added; no existing test in
  `test/workflow/*_test.clj` is touched. Kept in one new file rather than
  spread across the existing per-namespace test files."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datalevin.core :as d]
            [workflow.agents :as agents]
            [workflow.core :as core]
            [workflow.fs :as fs]
            [workflow.orchestrator :as orch]
            [workflow.orchestrator.core :as effects]
            [workflow.orchestrator.dispatch :as dispatch]
            [workflow.orchestrator.repair :as repair]
            [workflow.orchestrator.review :as review]
            [workflow.store :as store]))

;; --- shared temp-file / temp-store helpers -----------------------------------

(defn- temp-dir!
  "Create and return a fresh temp directory path (string)."
  []
  (let [f (java.io.File/createTempFile "main-test" "")]
    (.delete f)
    (.mkdirs f)
    (.getAbsolutePath f)))

(defn- delete-tree!
  [^java.io.File file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-tree! child)))
  (.delete file))

(defn- seed-run+slice+iteration!
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

(defn- with-store
  [f]
  (let [dir  (temp-dir!)
        conn (store/connect dir)]
    (try
      (f conn (seed-run+slice+iteration! conn))
      (finally
        (store/close conn)
        (delete-tree! (io/file dir))))))

;; --- F3: --requirements-file (pure parse) ------------------------------------

(deftest parse-args-recognizes-requirements-file
  (testing "--requirements-file is captured as a raw path; parse-args stays pure"
    (let [config (core/parse-args ["--requirements-file" "/tmp/reqs.md"])]
      (is (= "/tmp/reqs.md" (:requirements-file config)))
      (is (nil? (:requirements config)))
      (is (empty? (:errors config))))))

;; --- F3: resolve-requirements (the action that reads the file) --------------

(deftest resolve-requirements-reads-the-file-verbatim
  (testing "a named :requirements-file is slurped into :requirements verbatim"
    (let [dir  (temp-dir!)
          path (str dir "/reqs.md")]
      (try
        (spit path "# Requirements\n\nDo the thing.\n")
        (let [config (core/resolve-requirements {:requirements-file path})]
          (is (nil? (:error config)))
          (is (= "# Requirements\n\nDo the thing.\n" (:requirements config))))
        (finally (delete-tree! (io/file dir)))))))

(deftest resolve-requirements-file-overrides-inline-requirements
  (testing "a :requirements-file takes precedence over inline :requirements text"
    (let [dir  (temp-dir!)
          path (str dir "/reqs.md")]
      (try
        (spit path "from the file")
        (let [config (core/resolve-requirements {:requirements "inline text"
                                                  :requirements-file path})]
          (is (= "from the file" (:requirements config))))
        (finally (delete-tree! (io/file dir)))))))

(deftest resolve-requirements-noop-without-a-file
  (testing "no :requirements-file => config passes through unchanged"
    (let [config {:requirements "inline only"}]
      (is (= config (core/resolve-requirements config))))))

(deftest resolve-requirements-fails-closed-on-unreadable-file
  (testing "a missing/unreadable path is a fail-closed :error, not a swallowed
            I/O failure or a silent fallback to inline text"
    (let [config (core/resolve-requirements
                  {:requirements "inline text"
                   :requirements-file "/no/such/path/reqs.md"})]
      (is (= :requirements-file-unreadable (get-in config [:error :code])))
      (is (= "inline text" (:requirements config))
          "the unreadable file must not silently clobber other config"))))

;; --- F1/STR-2: dispatch-implementer! refuses without a relevant test --------

(deftest dispatch-implementer-refuses-without-a-relevant-test
  (testing "a :red-invalid (or any non-authorizing) event refuses the
            implementer dispatch entirely — no Step is even recorded (R-2)"
    (with-store
      (fn [conn ids]
        (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
              ctx  (merge ids {:conn conn :agent-invoker fake :changes {}})
              result (orch/dispatch-implementer! ctx :red-invalid)]
          (is (= :implementation-refused-no-relevant-test (get-in result [:error :code])))
          (is (empty? (agents/recorded-tasks fake))
              "the implementer agent was never invoked")
          (is (empty? (d/q '[:find ?s :in $ ?iter :where [?s :step/iteration ?iter]]
                           (d/db conn) (:iteration-eid ids)))
              "no Step entity was recorded for the refused dispatch"))))))

(deftest dispatch-implementer-dispatches-when-red-verified
  (testing "a :red-verified event establishes :relevant-test? true, so the
            implementer dispatches normally (R-2.1 satisfied)"
    (with-store
      (fn [conn ids]
        (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}]})
              ctx  (merge ids {:conn conn :agent-invoker fake :changes {}})
              result (orch/dispatch-implementer! ctx :red-verified)]
          (is (nil? (:error result)))
          (is (= :complete (:status result)))
          (is (= 1 (count (agents/recorded-tasks fake))))
          (is (= :implementer (:role (first (agents/recorded-tasks fake))))))))))

;; --- F1/NEW-1: :verify-existing-coverage producer (R-16.2/16.3) -------------
;;
;; R-16.2 (:defect-evidence) and R-16.3 (:behavior-preserving) require
;; OPPOSITE observable realities to confirm, so both polarities — and both
;; wrong-polarity rejections — are covered here (round-2 fix for NEW-1: the
;; original single check was inverted for R-16.2).

(deftest verify-existing-coverage-defect-evidence-confirms-on-a-failing-command
  (testing "R-16.2: a currently-FAILING command confirms defect evidence —
            an existing test already demonstrates the defect"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 1"]
                                       :existing-coverage-kind :defect-evidence}
                                      {:effect/type :verify-existing-coverage})]
      (is (= :existing-coverage-confirmed (:event result))))))

(deftest verify-existing-coverage-defect-evidence-rejects-a-passing-command
  (testing "R-16.2: a currently-PASSING command is NOT confirmed as defect
            evidence — there is no existing failure demonstrating a defect,
            so this fails closed rather than assuming pre-existing evidence"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 0"]
                                       :existing-coverage-kind :defect-evidence}
                                      {:effect/type :verify-existing-coverage})]
      (is (= :existing-coverage-not-confirmed (get-in result [:error :code]))))))

(deftest verify-existing-coverage-behavior-preserving-confirms-on-a-passing-command
  (testing "R-16.3: a currently-PASSING command confirms behavior-preserving
            coverage — the refactor retains its existing passing tests"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 0"]
                                       :existing-coverage-kind :behavior-preserving}
                                      {:effect/type :verify-existing-coverage})]
      (is (= :existing-coverage-confirmed (:event result))))))

(deftest verify-existing-coverage-behavior-preserving-rejects-a-failing-command
  (testing "R-16.3: a currently-FAILING command is NOT confirmed as
            behavior-preserving — the refactor broke something and owes a
            real RED/GREEN cycle, so this fails closed"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 1"]
                                       :existing-coverage-kind :behavior-preserving}
                                      {:effect/type :verify-existing-coverage})]
      (is (= :existing-coverage-not-confirmed (get-in result [:error :code]))))))

(deftest verify-existing-coverage-requires-a-recognized-kind
  (testing "a missing/unrecognized :existing-coverage-kind fails closed rather
            than guessing which R-16 polarity applies (the exact NEW-1 trap:
            a single undifferentiated check silently picked one polarity)"
    (let [result (effects/perform-effect {:command ["sh" "-c" "exit 0"]}
                                      {:effect/type :verify-existing-coverage})]
      (is (= :existing-coverage-kind-required (get-in result [:error :code]))))))

(deftest verify-existing-coverage-unrunnable-fails-closed
  (testing "no command => indeterminate => fail closed, regardless of kind"
    (let [result (effects/perform-effect {:command []
                                       :existing-coverage-kind :defect-evidence}
                                      {:effect/type :verify-existing-coverage})]
      (is (= :indeterminate (get-in result [:error :code]))))))

;; --- STR-3: authorize-repair! gates minting on repair-authorized? -----------

(deftest authorize-repair-refuses-an-unauthorized-proposal
  (testing "an unaccepted (or partially accepted) proposal never drives
            :plan-accepted — no new Iteration is minted (R-12.1, R-12.2)"
    (with-store
      (fn [conn ids]
        (let [{:keys [proposal-eid proposal-id]} (repair/propose-repair!
                                                   conn {:slice-eid (:slice-eid ids)
                                                         :subject "s" :body "plan v1"})
              _ (repair/accept-proposal! conn {:proposal-eid proposal-eid :reviewer :correctness})
              proposal (->> (store/proposals-for-slice conn (:slice-eid ids))
                           (filter #(= proposal-id (:proposal/id %)))
                           first)
              ctx (merge ids {:conn conn})
              result (orch/authorize-repair! ctx proposal)]
          (is (= :repair-not-authorized (get-in result [:error :code])))
          (is (= :reconcile (:slice/state (d/pull (d/db conn) [:slice/state] (:slice-eid ids))))
              "the slice never advanced"))))))

(deftest authorize-repair-mints-a-new-iteration-once-both-accept
  (testing "a proposal accepted by BOTH reviewers authorizes :plan-accepted,
            which mints a new Iteration exactly as the existing :begin-iteration
            path already does (R-12.1, R-12.2)"
    (with-store
      (fn [conn ids]
        ;; :begin-iteration requires a durably-recorded valid finding (R-8.8/8.9).
        (let [{:keys [review-eid]} (review/record-review!
                                    conn {:iteration-eid (:iteration-eid ids)
                                          :reviewer :correctness
                                          :verdict :request-changes
                                          :revision-counter 0})
              _ (review/record-finding! conn {:review-eid review-eid
                                            :owner :implementer
                                            :problem "p" :evidence "e"
                                            :justification "j" :required-outcome "o"})
              {:keys [proposal-eid proposal-id]} (repair/propose-repair!
                                                  conn {:slice-eid (:slice-eid ids)
                                                        :subject "s" :body "plan v1"})
              _ (repair/accept-proposal! conn {:proposal-eid proposal-eid :reviewer :correctness})
              _ (repair/accept-proposal! conn {:proposal-eid proposal-eid :reviewer :structural})
              proposal (->> (store/proposals-for-slice conn (:slice-eid ids))
                           (filter #(= proposal-id (:proposal/id %)))
                           first)
              ctx (merge ids {:conn conn})
              result (orch/authorize-repair! ctx proposal)]
          (is (nil? (:error result)))
          (is (= :test-design (:state result)))
          (is (= :test-design
                 (store/derive-current-state conn (:run-eid ids) (:slice-eid ids)))))))))

;; --- F2: fs/snapshot-dir + fs/diff-snapshot -----------------------------------

(deftest snapshot-dir-and-diff-snapshot-detect-created-and-edited-paths
  (testing "a real before/after directory snapshot diff classifies created vs.
            edited paths, and an untouched file is neither"
    (let [dir (temp-dir!)]
      (try
        (spit (io/file dir "untouched.clj") "()")
        (spit (io/file dir "will-edit.clj") "()")
        (let [before (fs/snapshot-dir dir)]
          (Thread/sleep 5)
          (spit (io/file dir "will-edit.clj") "(edited)")
          (spit (io/file dir "new-file.clj") "()")
          (let [after (fs/snapshot-dir dir)
                diff  (fs/diff-snapshot before after)]
            (is (= ["new-file.clj"] (:created diff)))
            (is (= ["will-edit.clj"] (:edited diff)))))
        (finally (delete-tree! (io/file dir)))))))

(deftest snapshot-dir-empty-for-nil-or-missing-cwd
  (is (= {} (fs/snapshot-dir nil)))
  (is (= {} (fs/snapshot-dir "/no/such/directory/at/all"))))

;; --- F2: dispatch-step! auto-detects real changes with no explicit :changes -

(deftest dispatch-step-observes-real-changes-when-none-supplied
  (testing "with NO :changes key in ctx (a real invocation's shape), the
            produced change is derived from a real snapshot diff around the
            invocation, and an out-of-boundary write is still caught (F2)"
    (with-store
      (fn [conn ids]
        (let [dir (temp-dir!)]
          (try
            (let [fake (agents/fake-agent
                        {:result-fn (fn [_task]
                                      (spit (io/file dir "sneaky.clj") "()")
                                      {:status :ok :exit 0})})
                  ctx  (merge ids {:conn conn :agent-invoker fake
                                   :role :test-designer :cwd dir})
                  dispatched (dispatch/dispatch-step! ctx)]
              (is (not (contains? ctx :changes)) "ctx supplied no explicit :changes")
              (is (some? (:violation dispatched))
                  "the real produced file was observed via the snapshot diff")
              (is (= :production (:class (:violation dispatched)))))
            (finally (delete-tree! (io/file dir)))))))))

;; --- STR-1: fresh-run! actually drives a pass through run-iteration! --------

(deftest fresh-run-drives-a-fake-backed-pass-to-slice-approved
  (testing "a fresh run, via core/execute-run! with the :fake backend, actually
            dispatches -> drives -> runs a review round -> reaches the AND-gate
            — orchestrator/drive is reachable from the real entry point (STR-1),
            never trusting the implementer without a relevant test (F1) and
            observing real produced changes (F2)"
    (let [db-dir (temp-dir!)
          cwd    (temp-dir!)]
      (try
        (let [result-fn (fn [task]
                          (case (:role task)
                            :test-designer (spit (io/file cwd "feature_test.clj") "()")
                            :implementer   (spit (io/file cwd "feature.clj") "()")
                            nil)
                          {:status :ok :exit 0})
              config {:db-dir db-dir
                      :backend :fake
                      :mode :fresh
                      :fake-result-fn result-fn
                      :cwd cwd
                      :requirements "do the thing"
                      :red-command ["sh" "-c" "exit 1"]
                      :green-command ["sh" "-c" "exit 0"]
                      :correctness-verdict :approve
                      :structural-verdict :approve}
              result (core/execute-run! config)]
          (is (nil? (:error result)))
          (is (= :review (get-in result [:iteration :stage])))
          (is (true? (get-in result [:iteration :approved?])))
          (is (= :slice-approved (get-in result [:drive :state]))))
        (finally
          (delete-tree! (io/file db-dir))
          (delete-tree! (io/file cwd)))))))

(deftest fresh-run-halts-at-reconcile-when-a-reviewer-withholds-approval
  (testing "a REQUEST_CHANGES verdict never fabricates an approval — the pass
            settles at :reconcile, not :slice-approved (R-8.6 fail-closed default)"
    (let [db-dir (temp-dir!)
          cwd    (temp-dir!)]
      (try
        (let [result-fn (fn [task]
                          (case (:role task)
                            :test-designer (spit (io/file cwd "feature_test.clj") "()")
                            :implementer   (spit (io/file cwd "feature.clj") "()")
                            nil)
                          {:status :ok :exit 0})
              config {:db-dir db-dir
                      :backend :fake
                      :mode :fresh
                      :fake-result-fn result-fn
                      :cwd cwd
                      :requirements "do the thing"
                      :red-command ["sh" "-c" "exit 1"]
                      :green-command ["sh" "-c" "exit 0"]
                      :correctness-verdict :approve}
                      ;; :structural-verdict omitted => defaults :request-changes
              result (core/execute-run! config)]
          (is (false? (get-in result [:iteration :approved?])))
          (is (= :reconcile (get-in result [:drive :state]))))
        (finally
          (delete-tree! (io/file db-dir))
          (delete-tree! (io/file cwd)))))))

;; --- F3 + STR-1: a fresh run can start from a requirements FILE -------------

(deftest fresh-run-reads-requirements-from-a-file
  (testing "--requirements-file reaches the bootstrapped :run/requirements
            verbatim, via execute-run! end to end (no :red-command is supplied,
            so the driven pass itself halts fail-closed at :test-design — this
            test only cares that the requirements FILE was read and bootstrapped
            before that halt, not that the whole pipeline completed)"
    (let [db-dir (temp-dir!)
          cwd    (temp-dir!)
          reqs-path (str cwd "/requirements.md")]
      (try
        (spit reqs-path "# Requirements\n\nverbatim contents\n")
        (let [config {:db-dir db-dir
                      :backend :fake
                      :mode :fresh
                      :cwd cwd
                      :requirements-file reqs-path}
              result (core/execute-run! config)
              conn   (store/connect db-dir)]
          (try
            (is (some? (get-in result [:ids :run-eid])) "the run was bootstrapped")
            (is (= "# Requirements\n\nverbatim contents\n"
                   (:run/requirements
                    (d/pull (d/db conn) [:run/requirements]
                            (get-in result [:ids :run-eid])))))
            (finally (store/close conn))))
        (finally
          (delete-tree! (io/file db-dir))
          (delete-tree! (io/file cwd)))))))
