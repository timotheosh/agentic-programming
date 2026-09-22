(ns workflow.agents
  "Agent-invocation boundary for the multi-agent development workflow.

  This namespace owns the `AgentInvoker` abstraction — the `defprotocol` through
  which every role (test-designer, implementer, correctness-reviewer,
  structural-reviewer) is fired off with scoped work, a selected model, and a
  capability descriptor (design, Agent-invocation protocol). Concrete backends
  (`HermesAgent`, `KiroAgent`), the pure argv calculations that turn a task into
  an argv vector, and a fake/record backend for tests are downstream work; this
  file defines only the protocol and the agent-facing capability-descriptor data
  those pieces build on.

  The capability boundary itself — which produced-change classes a role MAY write
  and the fail-closed violation decision over observed changes — lives entirely
  in `workflow.rules.core` (`capability-descriptors`, `role->capability`,
  `capability-for`, `capability-violation?`). This namespace does NOT re-declare
  those boundary sets or duplicate that logic; the agent-facing descriptor vars
  below reference `workflow.rules.core/capability-descriptors` so the two can never
  drift."
  (:require [workflow.rules.core :as core]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]))

;; --- The agent-invocation protocol (design, Agent-invocation protocol) -------
;;
;; Every role is invoked through this one protocol so the orchestrator can drive
;; hermes (confirmed) and kiro (provisional) backends uniformly, model defaulting
;; to agentic `auto`. The task carries :role and :capability so a backend can add
;; role-appropriate flags and the tightest available write sandbox — but the
;; sandbox is advisory: capability enforcement is verified AFTER the fact by the
;; orchestrator via `workflow.rules.core/capability-violation?`, never trusted here.
;;
;; Agents do NOT inspect workflow state: eligibility, ordering, identity, and
;; capability enforcement are the orchestrator's responsibility. An adapter may
;; retain the iteration/trace association as internal bookkeeping metadata to
;; correlate a result back to its dispatch, but that is not a guard the agent
;; participates in.

(defprotocol AgentInvoker
  "Fire off an agent with scoped work, a selected model, and a capability
  descriptor.

  Concrete backends (`HermesAgent`, `KiroAgent`, and a fake/record backend for
  tests) satisfy this protocol; the orchestrator holds an `AgentInvoker` and does
  not care which backend is behind it."
  (invoke [this task]
    "Run one agent invocation for `task` and return a result map.

    `task` is a plain map describing the scoped work:
      {:role         kw    ; :test-designer | :implementer
                           ; | :correctness-reviewer | :structural-reviewer
       :iteration-id uuid  ; the current Iteration/trace-id (bookkeeping only)
       :capability   kw    ; the capability descriptor stamped on the step
                           ; (:test-authoring | :production-authoring | :read-only)
       :prompt       s     ; the scoped work prompt (backends write it to a file)
       :model        s     ; selected model; defaults to \"auto\" (agentic auto)
       :cwd          path  ; working directory the invocation runs in
       :timeout-ms   n     ; wall-clock cap for the invocation
       :extra        {...}}; backend-specific extras

    Returns a result map:
      {:status :ok|:error ; :ok on success (exit 0), :error when it fails closed
       :stdout s          ; captured standard output
       :stderr s          ; captured standard error
       :exit   int        ; process exit code
       :raw    {...}}      ; backend-specific raw detail

    Implementations perform I/O (spawning a process) and MUST NOT inspect
    workflow state to decide eligibility."))

;; --- Agent-facing capability descriptors (design, Capability descriptors) ----
;;
;; The authoritative may-write / may-not-write boundary data lives in
;; `workflow.rules.core/capability-descriptors` (a map from descriptor keyword to the
;; set of produced-change classes a role MAY write). The agents layer needs to
;; NAME those descriptors when stamping :step/capability and building a task, so
;; the vars below reference core's data — not a second copy. Referencing core
;; keeps the boundary defined in exactly one place and guarantees the agent side
;; can never contradict the enforcement side.

(def capability-descriptors
  "The authoritative capability-descriptor data, re-exported from
  `workflow.rules.core` for the agents layer (design, Capability descriptors table).

  This is the SAME map as `workflow.rules.core/capability-descriptors` — a mapping from
  descriptor keyword to the set of produced-change classes a role MAY write
  (`:test-authoring` -> #{:test}, `:production-authoring` -> #{:production},
  `:read-only` -> #{}). It is referenced, not duplicated, so the boundary the
  orchestrator enforces and the descriptor a task carries stay identical (R-1.4,
  R-3.5, R-11.2)."
  core/capability-descriptors)

(def capability-keys
  "The capability-descriptor keywords a task's `:capability` may name.

  Derived from the authoritative `workflow.rules.core/capability-descriptors` so the
  set of legal descriptor keys cannot drift from the boundary data: the
  test-designer's `:test-authoring`, the implementer's `:production-authoring`,
  and each reviewer's `:read-only`."
  (set (keys core/capability-descriptors)))

(defn capability-for
  "Return the capability descriptor keyword for `role` (design, Capability
  descriptors table).

  A thin pass-through to `workflow.rules.core/capability-for` so the agents layer can
  stamp `:step/capability` / build a task's `:capability` without duplicating the
  role -> descriptor mapping: :test-designer -> :test-authoring,
  :implementer -> :production-authoring, and :correctness-reviewer /
  :structural-reviewer -> :read-only. Returns nil for an unknown role (fail
  closed downstream). Pure lookup; no I/O."
  [role]
  (core/capability-for role))

;; --- argv calculations (design, Agent-invocation protocol) -------------------
;;
;; Turning a task map into an argv vector of strings is a CALCULATION: pure, with
;; no I/O and no process spawning (spawning is an action, downstream in task 6.3).
;; Keeping command construction pure makes argument/model/isolation wiring
;; unit-testable without launching anything (design: `(hermes-argv task) ->
;; [strings]`, `(kiro-argv task) -> [strings]`).
;;
;; The prompt is NOT interpolated into argv. An action writes the prompt to a
;; file and the path is passed to hermes via `--query-file`, so nothing is
;; shell-interpreted. `hermes-argv` therefore reads the already-written query
;; file path (and turn cap) from the task rather than the raw prompt.

(def ^:private default-model
  "The default model selection passed to a backend when a task names none:
  agentic `auto` (design; requirements: Model `auto`). Both `hermes-argv` and
  `kiro-argv` fall back to this."
  "auto")

(def ^:private default-max-turns
  "Fallback tool-iteration cap for `hermes-argv`'s `--max-turns <n>` when a task
  does not specify `:max-turns`. A conservative bound so a one-shot agent run is
  always turn-capped even if the caller omits it."
  20)

(defn hermes-argv
  "Return the hermes CLI argv (a vector of strings) for `task` (design,
  Agent-invocation protocol — HermesAgent confirmed CLI). PURE calculation: no
  I/O, no process spawning (that is the HermesAgent record's job, task 6.3).

  The emitted form is exactly the one valid invocation the design fixes on,
  chosen for quiet programmatic output (`-Q`) and a turn cap (`--max-turns`):

      hermes chat --query-file <path> --oneshot -Q --max-turns <n> \\
             --model <model> --provider auto \\
             --ignore-user-config --ignore-rules

  (The leading `hermes` program name is supplied by the spawning action; this
  calculation returns the argument vector that follows it.)

  `task` keys read here:
    :query-file  path  ; file the prompt was written to; passed via --query-file
                       ; (nothing is shell-interpreted). Coerced to a string.
    :max-turns   n     ; tool-iteration cap; defaults to `default-max-turns`.
    :model       s     ; model selection; defaults to \"auto\" (agentic auto).

  Flags always emitted:
    `--oneshot` (answer and exit), `-Q` (suppress banner/spinner/tool previews),
    `--provider auto` (the provider list includes literal `auto`, so the default
    model maps to `--provider auto`), and the agent-run isolation flags
    `--ignore-user-config` and `--ignore-rules`.

  This argv NEVER contains `--worktree` under any input: worktrees are
  deliberately omitted because git is not a source of truth and the orchestrator
  manages working-copy scope itself (design: three sources of truth, and no git).
  Passing arbitrary or hostile task data can change the query-file path, model
  string, or turn count, but can NEVER introduce a `--worktree` flag."
  [task]
  (let [query-file (str (:query-file task))
        max-turns  (:max-turns task default-max-turns)
        model      (:model task default-model)]
    ["chat"
     "--query-file" query-file
     "--oneshot"
     "-Q"
     "--max-turns" (str max-turns)
     "--model" model
     "--provider" "auto"
     "--ignore-user-config"
     "--ignore-rules"]))

(defn kiro-argv
  "Return the kiro CLI argv (a vector of strings) for `task` (design,
  Agent-invocation protocol — KiroAgent PROVISIONAL). PURE calculation: no I/O,
  no process spawning.

  PROVISIONAL: the exact kiro CLI invocation is NOT yet confirmed. This is
  written behind the `AgentInvoker` protocol and marked provisional so it can be
  corrected once the real command/flags are known WITHOUT touching the state
  machine or the orchestrator (design, Open Items: \"update `kiro-argv` only\").
  Treat the concrete flags below as a placeholder, not a confirmed contract.

  What IS committed and must survive any correction: the model defaults to
  \"auto\" (agentic auto), matching hermes and the protocol's model-selection
  contract. `task` keys read: :query-file (prompt file path) and :model.

  Provisional form (subject to change):

      kiro chat --query-file <path> --model <model>"
  [task]
  (let [query-file (str (:query-file task))
        model      (:model task default-model)]
    ;; PROVISIONAL argv — flags unconfirmed; see docstring.
    ["chat"
     "--query-file" query-file
     "--model" model]))
;; --- process spawning (ACTION) + concrete backends (design 6.3) --------------
;;
;; Command construction above is a pure calculation; actually launching the CLI
;; is an ACTION and lives here, kept deliberately thin. The action:
;;
;;   1. writes the task's :prompt to a query file (nothing is interpolated into
;;      argv — the path is passed via --query-file, so nothing is
;;      shell-interpreted). If the task already carries a :query-file, that path
;;      is used as-is and reused for the argv calc.
;;   2. prepends the program name (e.g. "hermes" / "kiro") to the argv the pure
;;      calc returns, and shells out with `clojure.java.shell/sh` in the task's
;;      :cwd.
;;   3. maps the process result fail-closed: exit 0 -> {:status :ok}, ANY
;;      non-zero (or a spawn throw) -> {:status :error} (design: "Exit code 0 =
;;      success; non-zero fails closed"; R-4.x / R-11.x fail-closed).
;;
;; The program name is a field on the backend record rather than a hard-coded
;; string so a test (or task 6.4's fake backend) can inject a harmless program
;; (a shell that exits 0 or non-zero) and exercise the :ok/:error mapping WITHOUT
;; a real hermes/kiro binary on PATH.

(defn- write-query-file!
  "ACTION. Ensure a query file exists for `task` and return its path (a string).

  If the task already names a `:query-file`, that path is returned unchanged
  (the caller wrote it, e.g. the orchestrator's phase-1 record). Otherwise the
  task's `:prompt` is written to a fresh temp file and its path returned. This is
  the only place the raw prompt touches the filesystem; it is never placed on the
  argv (design: prompt in a file, passed via --query-file, nothing
  shell-interpreted)."
  [task]
  (if-let [qf (:query-file task)]
    (str qf)
    (let [tmp (java.io.File/createTempFile "workflow-agent-query" ".txt")]
      (spit tmp (str (:prompt task)))
      (.getAbsolutePath tmp))))

(defn- run-agent!
  "ACTION. Spawn `program` with the argv produced by `argv-fn` for `task`, in the
  task's `:cwd`, and return the AgentInvoker result map.

  `program`  the CLI program name to exec (e.g. \"hermes\"); injectable so a test
             can substitute a harmless program.
  `argv-fn`  the pure argv calculation (`hermes-argv` / `kiro-argv`); receives a
             task whose `:query-file` is guaranteed present.

  Fail-closed contract: exit 0 -> :ok, non-zero -> :error, and any thrown
  exception while spawning is also reported as :error (never :ok). The result
  carries captured `:stdout`/`:stderr`, the `:exit` code, and a `:raw` map for
  backend-specific detail incl. the exact `:command` executed."
  [program argv-fn task]
  (let [query-file (write-query-file! task)
        task*      (assoc task :query-file query-file)
        argv       (into [program] (argv-fn task*))
        cwd        (:cwd task)
        sh-args    (cond-> (vec argv)
                     cwd (conj :dir (io/file cwd)))]
    (try
      (let [{:keys [exit out err] :as raw} (apply shell/sh sh-args)]
        {:status (if (zero? exit) :ok :error)
         :stdout out
         :stderr err
         :exit   exit
         :raw    (assoc raw :command argv :query-file query-file)})
      (catch Exception e
        {:status :error
         :stdout ""
         :stderr (str "spawn failed: " (.getMessage e))
         :exit   -1
         :raw    {:command argv :query-file query-file :exception (str e)}}))))

(defrecord HermesAgent [program]
  ;; CONFIRMED backend. `program` defaults to "hermes" via `hermes-agent`; it is
  ;; a field so verification/tests can inject a harmless stand-in. Command form
  ;; comes from the pure `hermes-argv` calc; spawning is the `run-agent!` action.
  AgentInvoker
  (invoke [_ task]
    (run-agent! (or program "hermes") hermes-argv task)))

(defrecord KiroAgent [program]
  ;; PROVISIONAL backend (design: KiroAgent provisional). Same protocol shape as
  ;; HermesAgent; the concrete argv comes from the PROVISIONAL `kiro-argv` calc,
  ;; which is expected to change once the real kiro CLI is confirmed — no change
  ;; to this record or the state machine will be needed then. `program` defaults
  ;; to "kiro" via `kiro-agent` and is injectable for tests.
  AgentInvoker
  (invoke [_ task]
    (run-agent! (or program "kiro") kiro-argv task)))

(defn hermes-agent
  "Construct the CONFIRMED `HermesAgent` backend.

  With no args the program name defaults to \"hermes\"; pass an explicit program
  name to inject a harmless stand-in (used by verification and by fake-backend
  tests) without a real hermes binary on PATH."
  ([] (->HermesAgent "hermes"))
  ([program] (->HermesAgent program)))

(defn kiro-agent
  "Construct the PROVISIONAL `KiroAgent` backend (design: kiro provisional).

  With no args the program name defaults to \"kiro\"; pass an explicit program
  name to inject a harmless stand-in for verification/tests. The underlying
  `kiro-argv` flags are provisional and may change once the real kiro CLI is
  confirmed."
  ([] (->KiroAgent "kiro"))
  ([program] (->KiroAgent program)))

;; --- fake/record backend for tests (design 6.4; design: agents_test /
;; orchestrator_test use a fake/record backend, no real process spawning) ------
;;
;; A DETERMINISTIC in-memory `AgentInvoker` that spawns nothing. It returns
;; SCRIPTED results so a test can drive the orchestrator through a whole slice
;; without a real hermes/kiro binary, and it RECORDS every task it was invoked
;; with (in call order) so a test can assert what the orchestrator dispatched.
;;
;; Results are scripted one of two ways, checked in this order:
;;
;;   1. `result-fn` — a fn from task -> result map. When present it is authoritative
;;      and consulted on every invocation (the caller owns determinism). This is the
;;      most flexible form: a test can key the result off :role/:iteration-id/etc.
;;   2. `results`   — an atom holding a sequence (queue) of result maps consumed in
;;      order, one per invocation. Each `invoke` pops the head; when the queue is
;;      exhausted `default-result` is returned so an over-long drive never throws.
;;
;; `default-result` is the fallback when neither a `result-fn` is set nor a queued
;; result remains; it defaults to a benign success. Every returned map is passed
;; through `normalize-result` so the fake ALWAYS honours the AgentInvoker result
;; shape ({:status :stdout :stderr :exit :raw}) even if a test scripts a partial
;; map — matching what HermesAgent/KiroAgent guarantee.
;;
;; `calls` is an atom holding the vector of tasks seen, in invocation order;
;; `recorded-tasks` reads it for assertions.

(def ^:private fake-default-result
  "The benign success a `FakeAgent` returns when nothing else is scripted: exit 0,
  `:status :ok`, empty output. Mirrors the shape of a real backend's success so a
  test that does not care about output can still drive the loop."
  {:status :ok :stdout "" :stderr "" :exit 0 :raw {:fake true}})

(defn- normalize-result
  "Coerce a scripted `result` map into the full AgentInvoker result shape.

  Fills any missing key from `fake-default-result` so a test may script just the
  parts it cares about (e.g. `{:status :error :exit 1}`) and still get a map with
  `:status`/`:stdout`/`:stderr`/`:exit`/`:raw`. A non-map `result` (or nil) is
  replaced wholesale by the default. Pure; no I/O."
  [result]
  (if (map? result)
    (merge fake-default-result result)
    fake-default-result))

(defrecord FakeAgent [results result-fn default-result calls]
  ;; DETERMINISTIC in-memory backend (design 6.4). Spawns NOTHING; returns
  ;; scripted results and records every task for assertions. Satisfies
  ;; `AgentInvoker` so it is a drop-in for HermesAgent/KiroAgent in tests.
  AgentInvoker
  (invoke [_ task]
    ;; Record the task first, in call order, so an assertion sees every dispatch
    ;; even if scripting a result were to (it does not) throw.
    (swap! calls conj task)
    (let [dflt (or default-result fake-default-result)
          result (cond
                   ;; (1) result-fn is authoritative when present.
                   (fn? result-fn) (result-fn task)
                   ;; (2) otherwise pop the head of the scripted queue.
                   :else (let [queue @results]
                           (if (seq queue)
                             (let [head (first queue)]
                               (reset! results (rest queue))
                               head)
                             dflt)))]
      (normalize-result result))))

(defn fake-agent
  "Construct a DETERMINISTIC in-memory `FakeAgent` backend for tests (design 6.4).

  Spawns no process; returns SCRIPTED results and records every task it is invoked
  with. Two scripting forms are supported (see the record's docstring):

    (fake-agent)
      Always returns a benign success; useful when only the recorded tasks matter.

    (fake-agent {:results [r1 r2 ...]})
      Returns r1, r2, ... one per invocation, in order; once exhausted returns
      `:default-result` (or a benign success). Each result may be partial — it is
      normalized to the full {:status :stdout :stderr :exit :raw} shape.

    (fake-agent {:result-fn (fn [task] ...)})
      Consults the fn on EVERY invocation; the fn's return is authoritative
      (and normalized). Lets a test key the result off the task (:role, etc.).

    (fake-agent {:default-result {:status :error :exit 1}})
      Overrides the fallback returned when the `:results` queue is exhausted.

  Options map keys (all optional):
    :results        seq of result maps consumed in order (a queue).
    :result-fn      fn task -> result map; authoritative when present.
    :default-result result map returned when the queue is exhausted.

  Inspect what was dispatched with `recorded-tasks`."
  ([] (fake-agent {}))
  ([{:keys [results result-fn default-result]}]
   (->FakeAgent (atom (vec results)) result-fn default-result (atom []))))

(defn recorded-tasks
  "Return the vector of tasks a `FakeAgent` has been invoked with, in call order.

  For test assertions: after driving the orchestrator with a `fake-agent`, read
  back exactly which tasks were dispatched (and in what order) to assert on
  `:role`, `:capability`, `:iteration-id`, etc. Reading does not mutate the fake."
  [fake]
  @(:calls fake))
