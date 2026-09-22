(ns workflow.agents-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [workflow.agents :as agents]
            [workflow.rules.core :as core]))

;; Feature: orchestrator-state-machine, Task 6.1: the AgentInvoker protocol and
;; the agent-facing capability descriptors.
;;
;; These are structural/example checks over the protocol and descriptor data the
;; agents layer exposes. The concrete backends (6.3), the argv calculations
;; (6.2), and the fake backend (6.4) are downstream; here we only pin that the
;; protocol exists with the right shape and that the agent-facing descriptors
;; reference — never contradict — workflow.rules.core.
;;
;; Validates: Requirements 1.4, 3.5, 11.2

(deftest agent-invoker-protocol-defines-invoke
  ;; The protocol exists and declares a single-argument (plus this) invoke fn.
  (is (some? (:on-interface agents/AgentInvoker))
      "AgentInvoker should be a defprotocol")
  (is (contains? (:sigs agents/AgentInvoker) :invoke)
      "AgentInvoker should declare an `invoke` method"))

(deftest capability-descriptors-reference-core
  ;; The agent-facing descriptors must be the SAME authoritative boundary data
  ;; that workflow.rules.core enforces — referenced, not duplicated.
  (is (= core/capability-descriptors agents/capability-descriptors)
      "agents/capability-descriptors must be core/capability-descriptors")
  ;; The three declared descriptor keys, with their may-write sets (R-1.4 test
  ;; authoring, R-3.5 production authoring, R-11.2 read-only reviewers).
  (is (= #{:test-authoring :production-authoring :read-only}
         agents/capability-keys))
  (is (= #{:test} (:test-authoring agents/capability-descriptors)))
  (is (= #{:production} (:production-authoring agents/capability-descriptors)))
  (is (= #{} (:read-only agents/capability-descriptors))))

(deftest capability-for-maps-roles-to-descriptors
  ;; The agents layer names a role's descriptor via a pass-through to core.
  (is (= :test-authoring (agents/capability-for :test-designer)))
  (is (= :production-authoring (agents/capability-for :implementer)))
  (is (= :read-only (agents/capability-for :correctness-reviewer)))
  (is (= :read-only (agents/capability-for :structural-reviewer)))
  ;; Unknown role authorizes nothing (fail closed).
  (is (nil? (agents/capability-for :unknown-role))))

;; Feature: orchestrator-state-machine, Task 6.5: argv calculations and the
;; AgentInvoker protocol/fake backend.
;;
;; These extend the 6.1 protocol/descriptor checks above. They pin the two pure
;; argv CALCULATIONS (`hermes-argv`, `kiro-argv`) — model defaulting to "auto",
;; the exact confirmed hermes invocation, the capability-derived isolation flags,
;; and CRITICALLY that hermes-argv NEVER emits `--worktree` under ANY input (the
;; airtight "no git" boundary) — and that the protocol is satisfied by both real
;; backends (HermesAgent/KiroAgent) and the fake backend, which returns scripted
;; results and records the tasks it saw.
;;
;; Validates: Requirements 4.1, 4.2

;; --- hermes-argv: exact confirmed form + defaults + isolation ----------------

(deftest hermes-argv-emits-exact-confirmed-form
  ;; The one valid invocation the design fixes on, with an explicit model and
  ;; turn cap. The leading program name is supplied by the spawning action, so
  ;; the calculation returns only the argument vector that follows it.
  (is (= ["chat"
          "--query-file" "/tmp/q.txt"
          "--oneshot"
          "-Q"
          "--max-turns" "7"
          "--model" "sonnet"
          "--provider" "auto"
          "--ignore-user-config"
          "--ignore-rules"]
         (agents/hermes-argv {:query-file "/tmp/q.txt"
                              :max-turns  7
                              :model      "sonnet"}))))

(deftest hermes-argv-defaults-model-to-auto-when-omitted
  ;; Model selection defaults to agentic "auto" when the task names none.
  (let [argv (agents/hermes-argv {:query-file "/tmp/q.txt"})]
    (is (= "auto" (nth argv (inc (.indexOf argv "--model"))))
        "--model should default to auto")))

(deftest hermes-argv-defaults-max-turns-when-omitted
  ;; A one-shot run is always turn-capped even if the caller omits :max-turns.
  (let [argv (agents/hermes-argv {:query-file "/tmp/q.txt"})
        turns (nth argv (inc (.indexOf argv "--max-turns")))]
    (is (re-matches #"\d+" turns)
        "--max-turns should default to a numeric cap")))

(deftest hermes-argv-emits-capability-derived-isolation-flags
  ;; The agent-run isolation flags: ignore user config, ignore rules, and the
  ;; provider pinned to auto (the default model maps to --provider auto).
  (let [argv (agents/hermes-argv {:query-file "/tmp/q.txt"})]
    (is (some #{"--ignore-user-config"} argv))
    (is (some #{"--ignore-rules"} argv))
    (is (= "auto" (nth argv (inc (.indexOf argv "--provider"))))
        "--provider should be auto")
    (is (some #{"--oneshot"} argv))
    (is (some #{"-Q"} argv))))

(deftest hermes-argv-coerces-query-file-to-string
  ;; The query-file path is coerced to a string so a non-string path (e.g. a
  ;; java.io.File the orchestrator wrote) still produces a clean argv.
  (let [f (java.io.File. "/tmp/q.txt")
        argv (agents/hermes-argv {:query-file f})]
    (is (= "/tmp/q.txt" (nth argv (inc (.indexOf argv "--query-file")))))
    (is (every? string? argv) "every argv element must be a string")))

;; --- The airtight "no git" boundary: NEVER --worktree ------------------------
;;
;; The no-git guarantee is that hermes-argv never EMITS a `--worktree` FLAG. It
;; is not enough to grep the whole vector for the string "--worktree", because a
;; hostile task can legitimately land that literal string in a VALUE slot (e.g.
;; `:model "--worktree"` becomes the argument after `--model`, and `:query-file
;; "--worktree"` the argument after `--query-file`). Those are inert values, not
;; flags. `flag-tokens` returns only the tokens sitting in a flag POSITION — it
;; skips the single value following each value-taking flag — so the assertion
;; targets the real boundary: no `--worktree` ever appears as a flag.

(def ^:private value-taking-flags
  #{"--query-file" "--max-turns" "--model" "--provider"})

(defn- flag-tokens
  "Return the set of argv tokens that sit in a flag position, skipping the value
  that follows each value-taking flag. Used to assert on emitted FLAGS rather
  than inert argument values."
  [argv]
  (loop [xs argv, flags #{}]
    (if-let [t (first xs)]
      (if (contains? value-taking-flags t)
        (recur (drop 2 xs) (conj flags t)) ; t is a flag; skip its value
        (recur (rest xs) (conj flags t)))
      flags)))

(deftest hermes-argv-never-emits-worktree-fixed-cases
  ;; Explicit adversarial task maps: even when a task tries to smuggle worktree
  ;; data through arbitrary keys/values (including landing the literal string in
  ;; a value slot), no `--worktree` FLAG is ever emitted.
  (doseq [task [{:query-file "/tmp/q.txt"}
                {:query-file "/tmp/q.txt" :worktree "/some/worktree"}
                {:query-file "/tmp/q.txt" :model "--worktree"}
                {:query-file "--worktree"}
                {:query-file "/tmp/q.txt" :extra {:worktree true}}
                {:query-file "/tmp/q.txt" :max-turns 0 :model ""}
                {}]]
    (is (not (contains? (flag-tokens (agents/hermes-argv task)) "--worktree"))
        (str "hermes-argv must NEVER emit a --worktree flag, task: "
             (pr-str task)))))

(defspec hermes-argv-never-emits-worktree-property 200
  ;; Property: across arbitrary task maps (arbitrary keys, arbitrary string/edn
  ;; values, including a literal :worktree key and "--worktree" strings), the
  ;; hermes argv NEVER emits a `--worktree` FLAG. This is the airtight no-git
  ;; boundary: hostile task data may change the query-file path, model string, or
  ;; turn count (and may even place "--worktree" in a value slot), but can NEVER
  ;; introduce a worktree flag.
  (prop/for-all [task (gen/one-of
                       [(gen/map gen/keyword gen/any-printable)
                        ;; bias toward worktree-flavoured adversarial maps
                        (gen/let [qf   gen/string
                                  wt   gen/string
                                  mdl  gen/string
                                  turn gen/nat]
                          {:query-file qf
                           :worktree   wt
                           :model      mdl
                           :max-turns  turn
                           :--worktree wt})])]
    (not (contains? (flag-tokens (agents/hermes-argv task)) "--worktree"))))

;; --- kiro-argv (PROVISIONAL): model default committed ------------------------

(deftest kiro-argv-defaults-model-to-auto-when-omitted
  ;; PROVISIONAL flags aside, the committed contract is that the model defaults
  ;; to "auto", matching hermes and the protocol's model-selection contract.
  (let [argv (agents/kiro-argv {:query-file "/tmp/q.txt"})]
    (is (= "auto" (nth argv (inc (.indexOf argv "--model"))))
        "kiro-argv --model should default to auto")))

(deftest kiro-argv-honours-explicit-model-and-query-file
  (let [argv (agents/kiro-argv {:query-file "/tmp/q.txt" :model "haiku"})]
    (is (= "/tmp/q.txt" (nth argv (inc (.indexOf argv "--query-file")))))
    (is (= "haiku" (nth argv (inc (.indexOf argv "--model")))))
    (is (every? string? argv) "every argv element must be a string")))

(deftest kiro-argv-never-emits-worktree
  ;; The no-git boundary holds for the provisional kiro argv too: no --worktree
  ;; FLAG, even when the literal string lands in a value slot.
  (doseq [task [{:query-file "/tmp/q.txt"}
                {:query-file "/tmp/q.txt" :worktree "/x"}
                {:query-file "/tmp/q.txt" :model "--worktree"}]]
    (is (not (contains? (flag-tokens (agents/kiro-argv task)) "--worktree"))
        (str "kiro-argv must NEVER emit a --worktree flag, task: "
             (pr-str task)))))

;; --- AgentInvoker protocol satisfied by real + fake backends -----------------

(deftest protocol-satisfied-by-real-and-fake-backends
  ;; Every backend — the confirmed HermesAgent, the provisional KiroAgent, and
  ;; the fake — satisfies AgentInvoker, so the fake is a drop-in for the real
  ;; ones in orchestrator tests.
  (is (satisfies? agents/AgentInvoker (agents/hermes-agent))
      "HermesAgent should satisfy AgentInvoker")
  (is (satisfies? agents/AgentInvoker (agents/kiro-agent))
      "KiroAgent should satisfy AgentInvoker")
  (is (satisfies? agents/AgentInvoker (agents/fake-agent))
      "FakeAgent should satisfy AgentInvoker"))

;; --- Fake backend: scripted results + task recording (no spawning) -----------

(deftest fake-agent-returns-benign-success-by-default
  ;; With nothing scripted the fake returns a benign, fully-shaped success and
  ;; spawns no process.
  (let [fake   (agents/fake-agent)
        result (agents/invoke fake {:role :implementer})]
    (is (= :ok (:status result)))
    (is (= 0 (:exit result)))
    (is (contains? result :stdout))
    (is (contains? result :stderr))
    (is (contains? result :raw))))

(deftest fake-agent-returns-scripted-results-in-order
  ;; A queue of scripted results is consumed one per invocation, in order; each
  ;; partial result is normalized to the full result shape; once the queue is
  ;; exhausted the default (a benign success here) is returned.
  (let [fake (agents/fake-agent {:results [{:status :ok :exit 0}
                                           {:status :error :exit 1}]})
        r1   (agents/invoke fake {:role :test-designer})
        r2   (agents/invoke fake {:role :implementer})
        r3   (agents/invoke fake {:role :correctness-reviewer})]
    (is (= :ok (:status r1)))
    (is (= :error (:status r2)))
    (is (= 1 (:exit r2)))
    ;; normalized: partial scripted maps still carry the full shape.
    (is (contains? r2 :stdout))
    ;; exhausted queue falls back to the benign default success.
    (is (= :ok (:status r3)))))

(deftest fake-agent-result-fn-is-authoritative
  ;; When a result-fn is present it is consulted on every invocation and can key
  ;; the result off the task.
  (let [fake (agents/fake-agent
              {:result-fn (fn [task]
                            (if (= :implementer (:role task))
                              {:status :error :exit 2}
                              {:status :ok :exit 0}))})
        r-impl (agents/invoke fake {:role :implementer})
        r-test (agents/invoke fake {:role :test-designer})]
    (is (= :error (:status r-impl)))
    (is (= 2 (:exit r-impl)))
    (is (= :ok (:status r-test)))))

(deftest fake-agent-records-tasks-in-call-order
  ;; The fake records every task it was invoked with, in call order, so a test
  ;; can assert exactly what the orchestrator dispatched.
  (let [fake  (agents/fake-agent)
        t1    {:role :test-designer :iteration-id 1}
        t2    {:role :implementer :iteration-id 2}
        t3    {:role :correctness-reviewer :iteration-id 3}]
    (agents/invoke fake t1)
    (agents/invoke fake t2)
    (agents/invoke fake t3)
    (is (= [t1 t2 t3] (agents/recorded-tasks fake))
        "recorded-tasks should return the tasks in call order")))
