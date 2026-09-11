(ns workflow.fs-test
  "Tests for `workflow.fs/observe-changes` and its path classification.

  `observe-changes` is an ACTION: it reads the CURRENT filesystem state to learn
  what an invocation produced and classifies each produced path as :test vs.
  :production, emitting the {:path :change :class} change shape that
  `workflow.core/capability-violation?` consumes (Property 2). These tests use a
  temp directory, cleaned up after. There is NO Revision computation here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [workflow.core :as core]
            [workflow.fs :as fs]))

;; --- temp-directory fixture --------------------------------------------------

(def ^:dynamic *tmp-dir* nil)

(defn- create-temp-dir!
  "Create a fresh temp directory and return it as a java.io.File."
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "workflow-fs-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    dir))

(defn- delete-recursively!
  "Delete `file` and everything beneath it."
  [file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-recursively! child)))
  (.delete file))

(defn- spit-file!
  "Create the file at `rel` under *tmp-dir* (creating parent dirs) with content."
  [rel]
  (let [f (io/file *tmp-dir* rel)]
    (io/make-parents f)
    (spit f "x")
    f))

(use-fixtures :each
  (fn [t]
    (let [dir (create-temp-dir!)]
      (binding [*tmp-dir* dir]
        (try (t)
             (finally (delete-recursively! dir)))))))

;; --- classify-path / test-path? (pure classification) -----------------------

(deftest classify-path-test
  (testing "test files classify as :test"
    (is (= :test (fs/classify-path "test/foo_test.clj")))
    (is (= :test (fs/classify-path "test/workflow/core_test.clj")))
    (is (= :test (fs/classify-path "tests/test_hermes_orchestrator.py")))
    (is (= :test (fs/classify-path "src/foo.test.js")))
    (is (= :test (fs/classify-path "src/foo.spec.ts"))))
  (testing "production/implementation files classify as :production"
    (is (= :production (fs/classify-path "src/foo.clj")))
    (is (= :production (fs/classify-path "src/workflow/core.clj")))
    (is (= :production (fs/classify-path "README.md")))
    (is (= :production (fs/classify-path "src/latest.clj")))))

;; --- observe-changes ---------------------------------------------------------

(deftest observe-changes-classifies-and-shapes
  (testing "created + edited paths are read from the fs and classified"
    (spit-file! "test/foo_test.clj")
    (spit-file! "src/foo.clj")
    (let [changes (fs/observe-changes {:cwd   (str *tmp-dir*)
                                       :created ["test/foo_test.clj"]
                                       :edited  ["src/foo.clj"]})]
      (is (set? changes))
      (is (= #{{:path "test/foo_test.clj" :change :created :class :test}
               {:path "src/foo.clj"       :change :edited  :class :production}}
             changes))
      ;; The shape must be exactly what capability-violation? consumes.
      (is (some? (core/capability-violation? :test-authoring changes))
          "an implementation edit is a violation for a test-designer")
      (is (nil? (core/capability-violation? :production-authoring #{{:path "src/foo.clj" :change :edited :class :production}}))
          "an implementation edit is within an implementer's boundary"))))

(deftest observe-changes-ignores-phantom-paths
  (testing "a declared path that does not exist on disk is not reported"
    (spit-file! "src/real.clj")
    (let [changes (fs/observe-changes {:cwd    (str *tmp-dir*)
                                       :created ["src/real.clj"]
                                       :edited  ["src/never-written.clj"]})]
      (is (= #{{:path "src/real.clj" :change :created :class :production}}
             changes)))))

(deftest observe-changes-empty-when-nothing-produced
  (is (= #{} (fs/observe-changes {:cwd (str *tmp-dir*)})))
  (is (= #{} (fs/observe-changes {:cwd (str *tmp-dir*) :created [] :edited []}))))

;; --- R-18 interruption-recovery reads (design R-18.3, R-18.4) ----------------
;;
;; When a Run resumes and a Step is found in-doubt, its ACTUAL outcome is
;; recovered by reconciling against OBSERVABLE REALITY (R-18.3): re-run the
;; relevant verification (RED / GREEN) and inspect the artifacts the agent was
;; supposed to produce on disk. The verification command is INJECTED so a test
;; never spawns a real suite — a fake `["sh" "-c" "exit N"]` returns a chosen
;; exit code (R-18.4). Reconciliation fails closed to `:indeterminate` when the
;; command cannot run and to `:missing-artifact` when a required artifact is
;; absent (R-18.5). No Revision is ever derived from these filesystem reads.

;; A fake verification command that exits with the given code without spawning a
;; real run — the injection point R-18.4 relies on.
(defn- fake-cmd [exit-code]
  ["sh" "-c" (str "exit " exit-code)])

;; --- red-outcome / green-outcome (pure DECISIONS over an observed result) ----

(deftest red-outcome-maps-exit-to-event
  (testing "RED confirmed by a failing (non-zero) verification -> :red-verified"
    (is (= :red-verified (fs/red-outcome {:ran? true :exit 1}))))
  (testing "RED disproved by a passing (zero) verification -> :red-invalid"
    (is (= :red-invalid (fs/red-outcome {:ran? true :exit 0}))))
  (testing "verification that could not run fails closed to :indeterminate"
    (let [outcome (fs/red-outcome {:ran? false :error {:code :verification-unrunnable}})]
      (is (= :indeterminate (get-in outcome [:error :code]))
          "reality is indeterminate; refuse to advance rather than guess"))))

(deftest green-outcome-maps-exit-to-event
  (testing "GREEN confirmed by a passing (zero) verification -> :green"
    (is (= :green (fs/green-outcome {:ran? true :exit 0}))))
  (testing "GREEN not reached: a still-failing suite recovers as :red-verified"
    (is (= :red-verified (fs/green-outcome {:ran? true :exit 1}))))
  (testing "verification that could not run fails closed to :indeterminate"
    (let [outcome (fs/green-outcome {:ran? false :error {:code :no-verification-command}})]
      (is (= :indeterminate (get-in outcome [:error :code]))))))

;; --- run-verification (ACTION: injected fake command, no real suite) ---------

(deftest run-verification-runs-injected-command
  (testing "a fake command's exit code is the observed signal"
    (is (= {:ran? true :exit 0} (select-keys (fs/run-verification (fake-cmd 0) {}) [:ran? :exit])))
    (is (= {:ran? true :exit 3} (select-keys (fs/run-verification (fake-cmd 3) {}) [:ran? :exit]))))
  (testing "an empty command cannot run -> fail closed, :no-verification-command"
    (let [result (fs/run-verification [] {})]
      (is (false? (:ran? result)))
      (is (= :no-verification-command (get-in result [:error :code]))))))

;; --- recover-step-outcome (composes ACTIONS with the pure DECISION) ----------

(deftest recover-step-outcome-red-both-cases
  (testing "RED recovery: injected failing command + present artifact -> :red-verified"
    (spit-file! "test/foo_test.clj")
    (is (= :red-verified
           (fs/recover-step-outcome :red {:command        (fake-cmd 1)
                                          :artifact-paths ["test/foo_test.clj"]
                                          :cwd            (str *tmp-dir*)}))))
  (testing "RED recovery: injected passing command + present artifact -> :red-invalid"
    (spit-file! "test/foo_test.clj")
    (is (= :red-invalid
           (fs/recover-step-outcome :red {:command        (fake-cmd 0)
                                          :artifact-paths ["test/foo_test.clj"]
                                          :cwd            (str *tmp-dir*)})))))

(deftest recover-step-outcome-green-both-cases
  (testing "GREEN recovery: injected passing command + present artifact -> :green"
    (spit-file! "src/foo.clj")
    (is (= :green
           (fs/recover-step-outcome :green {:command        (fake-cmd 0)
                                            :artifact-paths ["src/foo.clj"]
                                            :cwd            (str *tmp-dir*)}))))
  (testing "GREEN recovery: injected failing command + present artifact -> :red-verified"
    (spit-file! "src/foo.clj")
    (is (= :red-verified
           (fs/recover-step-outcome :green {:command        (fake-cmd 1)
                                            :artifact-paths ["src/foo.clj"]
                                            :cwd            (str *tmp-dir*)})))))

(deftest recover-step-outcome-fails-closed-when-indeterminate
  (testing "verification that cannot run -> fail closed, :indeterminate (never guess)"
    (spit-file! "src/foo.clj")
    (let [outcome (fs/recover-step-outcome :red {:command        []
                                                 :artifact-paths ["src/foo.clj"]
                                                 :cwd            (str *tmp-dir*)})]
      (is (= :indeterminate (get-in outcome [:error :code]))))))

(deftest recover-step-outcome-fails-closed-on-missing-artifact
  (testing "verification ran but a required artifact is absent -> :missing-artifact"
    (let [outcome (fs/recover-step-outcome :red {:command        (fake-cmd 1)
                                                 :artifact-paths ["test/never-written_test.clj"]
                                                 :cwd            (str *tmp-dir*)})]
      (is (= :missing-artifact (get-in outcome [:error :code]))
          "produced work not observable on disk -> outcome not confirmable")))
  (testing "an empty artifact list is vacuously satisfied; outcome is the event"
    (is (= :red-verified
           (fs/recover-step-outcome :red {:command        (fake-cmd 1)
                                          :artifact-paths []
                                          :cwd            (str *tmp-dir*)})))))

;; --- No Revision is derived from the filesystem (design R-8, R-18) -----------

(deftest recovery-derives-no-revision
  (testing "recovered outcomes are transition-event keywords, never a Revision"
    (spit-file! "src/foo.clj")
    (let [red   (fs/recover-step-outcome :red {:command        (fake-cmd 1)
                                               :artifact-paths ["src/foo.clj"]
                                               :cwd            (str *tmp-dir*)})
          green (fs/recover-step-outcome :green {:command        (fake-cmd 0)
                                                 :artifact-paths ["src/foo.clj"]
                                                 :cwd            (str *tmp-dir*)})]
      ;; A transition event is a keyword the core state machine consumes; it is
      ;; never a number/manifest/hash — nothing here derives Revision identity.
      (is (keyword? red))
      (is (keyword? green))
      (is (contains? #{:red-verified :red-invalid :green} red))
      (is (contains? #{:red-verified :red-invalid :green} green))
      (is (not (number? red)))
      (is (not (number? green))))))
