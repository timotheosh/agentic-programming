(ns workflow.core
  "Entry point / CLI wiring for the multi-agent development workflow state
  machine (design, Namespace layout: `src/workflow/core.clj — entry point / CLI
  wiring`).

  This is the outermost ACTION edge: the place a human actually starts (or
  resumes) a Run. It integrates every prior component into one coherent driver,
  with **no orphaned code** — the pieces below are all reachable from `-main`:

    * `workflow.rules.core`   — the pure `transition` table, driven indirectly
                                through `orchestrator/drive`.
    * `workflow.store`        — the durable Datalevin store: opened here
                                (`store/connect`), always closed here
                                (`store/close`), and read/written through the
                                orchestrator's helpers and the small bootstrap
                                transaction below (R-17.3: state is durable as it
                                happens so a halted Run resumes).
    * `workflow.fs`           — filesystem observation and R-18 recovery reads,
                                reached through the orchestrator's effects and
                                resume path.
    * `workflow.agents`       — the `AgentInvoker` backend this entry point
                                selects and constructs: **hermes** by default
                                (`agents/hermes-agent`, confirmed) and **kiro**
                                provisionally (`agents/kiro-agent`), with the
                                model defaulting to agentic `\"auto\"` (R-4.1).
                                A deterministic `agents/fake-agent` backend is
                                selectable so the whole wiring can be driven
                                without a real agent binary (R-4.2).
    * `workflow.orchestrator` — the loop owner: `orchestrator/drive`,
                                `orchestrator/dispatch-step!`,
                                `orchestrator/run-review-round!`,
                                `orchestrator/resume-iteration!`, and the
                                escalation path. This namespace never re-decides
                                anything `orchestrator`/`rules.core` already own;
                                it only parses configuration, opens the store,
                                builds the invoker, and hands control to the
                                orchestrator.

  ACD separation (R-4.1): parsing the run configuration is a pure **calculation**
  (`parse-args` / `run-config`); opening the store, constructing the backend,
  bootstrapping a fresh Run, and driving the loop are thin **actions** at the
  edge. No business rule (transition legality, capability boundaries, Revision
  identity) is duplicated here — those live in `workflow.rules.core` and are
  reached only through the orchestrator.

  Invocation (a `:run` alias is added to `deps.edn`, pinning the Datalevin JVM
  flags the store needs):

      clojure -M:run --db-dir /path/to/db --backend hermes --model auto \\
              --requirements \"…\" --prompt \"…\"      ; fresh run
      clojure -M:run --db-dir /path/to/db --resume --run-id <uuid>   ; resume

  See `usage` for the full option list."
  (:require [clojure.string :as str]
            [datalevin.core :as d]
            [workflow.agents :as agents]
            [workflow.orchestrator :as orch]
            [workflow.orchestrator.resume :as resume]
            [workflow.store :as store])
  (:gen-class))

;; --- run configuration: a PURE calculation over argv (ACD; R-4.1) ------------
;;
;; `parse-args` turns a raw argv vector into a config map WITHOUT touching the
;; world — no store is opened, no backend is built, nothing is spawned. Keeping
;; the parse pure means the CLI surface is unit-testable by passing a vector of
;; strings and inspecting the returned map; the actions below (`open-store!`,
;; `make-invoker`, `bootstrap-run!`, `execute-run!`) consume that data.

(def default-db-dir
  "Default Datalevin database directory when `--db-dir` is not supplied. One
  database directory per installation (design, Datalevin schema: \"path
  configurable\")."
  ".workflow-db")

(def backends
  "The selectable `AgentInvoker` backends, keyed by the `--backend` value. The
  default is `:hermes` (confirmed); `:kiro` is provisional (design: hermes first,
  then kiro); `:fake` is the deterministic in-memory backend used to drive the
  wiring without a real agent binary (R-4.2)."
  #{:hermes :kiro :fake})

(def default-model
  "The default model selection when `--model` is not supplied: agentic `\"auto\"`
  (design; requirements: model `auto` when none is chosen; R-4.1). Matches the
  default the agent backends apply per task, kept here so the CLI surfaces the
  same default explicitly on the parsed config."
  "auto")

(defn- parse-flag-pairs
  "Fold a `--flag value` / boolean-`--flag` argv into a raw string-keyed map.

  Pure helper for `parse-args`: a token beginning with `--` starts an option; the
  NEXT token is its value unless it is itself a `--flag` or absent, in which case
  the flag is a boolean `true` (e.g. `--resume`). Unrecognized/positional tokens
  are collected under `:_args`. No I/O."
  [args]
  (loop [tokens (seq args)
         acc    {:_args []}]
    (if-let [tok (first tokens)]
      (if (str/starts-with? tok "--")
        (let [k    (subs tok 2)
              nxt  (second tokens)]
          (if (and nxt (not (str/starts-with? nxt "--")))
            (recur (nnext tokens) (assoc acc k nxt))
            (recur (next tokens) (assoc acc k true))))
        (recur (next tokens) (update acc :_args conj tok)))
      acc)))

(defn parse-args
  "Parse a raw CLI argv vector into a run-configuration map (PURE calculation;
  R-4.1).

  Recognized options:
    --db-dir <path>        Datalevin database directory (default `default-db-dir`)
    --backend <name>       agent backend: hermes (default) | kiro | fake
    --model <name>         model selection (default \"auto\", agentic auto; R-4.1)
    --requirements <text>  the run's requirements, as a literal inline string
                            (fresh run)
    --requirements-file <path>  the run's requirements, read from a file (fresh
                            run); takes precedence over --requirements when both
                            are given (F3: a requirements DOCUMENT does not fit
                            on a command line — this is a raw path only, parsed
                            here without touching the filesystem; the ACTION
                            that reads it is `resolve-requirements`, called by
                            `fresh-run!`, so `parse-args` itself stays pure)
    --prompt <text>        the initial scoped-work prompt (fresh run)
    --cwd <path>           working directory agents/verifications run in
    --resume               resume an interrupted run instead of starting fresh
    --run-id <uuid>        the run to resume (with --resume)
    --help                 show usage and exit

  Returns a config map:
    {:db-dir       <string>
     :backend      <:hermes|:kiro|:fake>
     :model        <string>            ; \"auto\" default
     :mode         <:fresh|:resume>
     :requirements <string|nil>
     :requirements-file <string|nil>   ; a raw path, not yet read (F3)
     :prompt       <string|nil>
     :cwd          <string|nil>
     :run-id       <string|nil>        ; supplied with --resume
     :help?        <boolean>
     :errors       [<string> …]}       ; non-empty => invalid config, do not run

  Validation is pure and fail-closed: an unknown `--backend` and a `--resume`
  without `--run-id` are reported in `:errors` rather than guessed, so `-main`
  can refuse to open the store on a malformed configuration. No I/O."
  [args]
  (let [raw     (parse-flag-pairs args)
        help?   (boolean (get raw "help"))
        resume? (boolean (get raw "resume"))
        backend (keyword (get raw "backend" "hermes"))
        model   (let [m (get raw "model")]
                  (if (string? m) m default-model))
        run-id  (let [r (get raw "run-id")] (when (string? r) r))
        base    {:db-dir       (let [d (get raw "db-dir")]
                                 (if (string? d) d default-db-dir))
                 :backend      backend
                 :model        model
                 :mode         (if resume? :resume :fresh)
                 :requirements (let [x (get raw "requirements")] (when (string? x) x))
                 :requirements-file (let [x (get raw "requirements-file")] (when (string? x) x))
                 :prompt       (let [x (get raw "prompt")] (when (string? x) x))
                 :cwd          (let [x (get raw "cwd")] (when (string? x) x))
                 :run-id       run-id
                 :help?        help?}
        errors  (cond-> []
                  (not (contains? backends backend))
                  (conj (str "Unknown --backend " (name backend)
                             "; expected one of " (str/join ", " (map name (sort backends))) "."))
                  (and resume? (not run-id))
                  (conj "--resume requires --run-id <uuid> naming the run to resume."))]
    (assoc base :errors errors)))

(defn run-config
  "Alias for `parse-args`: build the run-configuration map from argv (PURE; R-4.1).

  Provided under the name the task/design use for the config-parse fn; delegates
  to `parse-args` so there is exactly one parser."
  [args]
  (parse-args args))

(def usage
  "Human-readable CLI usage string, printed on --help or an invalid configuration."
  (str/join
   \newline
   ["workflow.core — multi-agent development workflow state machine"
    ""
    "Usage:"
    "  clojure -M:run --db-dir <path> --backend hermes --model auto \\"
    "          --requirements \"…\" --prompt \"…\"        start a fresh run"
    "  clojure -M:run --db-dir <path> --resume --run-id <uuid>   resume a run"
    ""
    "Options:"
    "  --db-dir <path>        Datalevin database directory (default .workflow-db)"
    "  --backend <name>       hermes (default) | kiro (provisional) | fake"
    "  --model <name>         model selection (default \"auto\")"
    "  --requirements <text>  the run's requirements, as an inline string (fresh run)"
    "  --requirements-file <path>  the run's requirements, read from a file (fresh"
    "                          run); takes precedence over --requirements"
    "  --prompt <text>        the initial scoped-work prompt (fresh run)"
    "  --cwd <path>           working directory agents/verifications run in"
    "  --resume               resume an interrupted run instead of starting fresh"
    "  --run-id <uuid>        the run to resume (with --resume)"
    "  --help                 show this help"]))

;; --- edge actions: build the backend, open the store, bootstrap, drive -------
;;
;; Each fn below is a thin action. `make-invoker` is the one place the CLI
;; SELECTS a backend (hermes default, kiro provisional, fake for driving the
;; wiring) — the orchestrator only ever sees an `AgentInvoker`, never a concrete
;; record (R-4.2). `open-store!`/`close-store!` bracket the durable store so it
;; is ALWAYS closed. `bootstrap-run!` mints the initial run/slice/iteration and
;; the very first transition into `:test-design`, matching how the design's
;; lifecycle starts a fresh pass at the test-designer.

(defn make-invoker
  "Construct the selected `AgentInvoker` backend from `config` (ACTION; R-4.2).

  `:hermes` (default) builds the CONFIRMED `agents/hermes-agent`; `:kiro` builds
  the PROVISIONAL `agents/kiro-agent` (design: hermes first, then kiro, model
  defaulting to \"auto\"); `:fake` builds a deterministic `agents/fake-agent` that
  spawns nothing, so a human — or the verification below — can drive the whole
  wiring without a real agent binary on PATH (R-4.2). The optional
  `:fake-results` / `:fake-result-fn` in `config` script the fake backend.

  The orchestrator holds only the returned `AgentInvoker`; the model default
  \"auto\" travels on the per-step task, not on the backend record."
  [{:keys [backend fake-results fake-result-fn]}]
  (case backend
    :hermes (agents/hermes-agent)
    :kiro   (agents/kiro-agent)
    :fake   (agents/fake-agent (cond-> {}
                                 fake-results   (assoc :results fake-results)
                                 fake-result-fn (assoc :result-fn fake-result-fn)))
    ;; Fail closed on an unrecognized backend rather than defaulting silently:
    ;; `parse-args` already reports this in :errors, but a direct caller is
    ;; refused here too.
    (throw (ex-info "Unknown agent backend" {:backend backend :expected backends}))))

(defn open-store!
  "Open the durable Datalevin store at `config`'s `:db-dir` (ACTION; R-17.3).

  Thin wrapper over `store/connect` so the entry point has one obvious place the
  store is opened. The caller MUST pair this with `close-store!` (or the
  `execute-run!` wrapper below) so LMDB resources are released; state written through the
  orchestrator is durable as each fact happens (R-17.3), so a halted Run resumes."
  [{:keys [db-dir]}]
  (store/connect db-dir))

(defn close-store!
  "Close the durable store opened by `open-store!` (ACTION), releasing LMDB
  resources. After close the connection must not be reused."
  [conn]
  (store/close conn))

(defn bootstrap-run!
  "Mint a fresh Run — run/slice/iteration entities and the first transition into
  `:test-design` — and return their entity ids (ACTION; R-17.3, R-17.4).

  A fresh Run starts a pass at the `test-designer` (design, State machine:
  `:test-design` is the start of EVERY iteration). This commits, durably:

    1. one `d/transact!` minting the `:run` (at `:planning`, carrying the verbatim
       `:run/requirements`), its first `:slice` (order 0), and the slice's first
       `:iteration` (number 0, `:iteration/revision` 0, set as
       `:slice/current-iteration`);
    2. the initial transition event `:planning -> :test-design` on that trace via
       `store/append-transition-event`, which materializes the slice's
       `:slice/state` in the same ACID commit (R-17.4).

  `config` supplies the verbatim `:requirements`. Returns
    {:run-eid <eid> :run-id <uuid> :slice-eid <eid> :iteration-eid <eid>
     :iteration-id <uuid> :state :test-design}
  — the ids the orchestrator's `ctx` needs and the state `drive` begins from. This
  is an action: it commits durable state on disk."
  [conn {:keys [requirements]}]
  (let [run-id   (random-uuid)
        slice-id (random-uuid)
        iter-id  (random-uuid)
        report   (d/transact!
                  conn
                  [(cond-> {:db/id -1 :run/id run-id :run/state :planning
                            :run/created-at (java.util.Date.)}
                     requirements (assoc :run/requirements requirements))
                   {:db/id -2 :slice/id slice-id :slice/run -1
                    :slice/order 0 :slice/state :planning
                    :slice/current-iteration -3}
                   {:db/id -3 :iteration/id iter-id :iteration/slice -2
                    :iteration/number 0 :iteration/revision 0
                    :iteration/started-at (java.util.Date.)}])
        tempids  (:tempids report)
        run-eid   (get tempids -1)
        slice-eid (get tempids -2)
        iter-eid  (get tempids -3)]
    ;; The first transition enters :test-design on the fresh trace (R-16.1).
    (store/append-transition-event
     conn
     {:run-eid       run-eid
      :slice-eid     slice-eid
      :iteration-eid iter-eid
      :from-state    :planning
      :to-state      :test-design
      :trigger       :run-started})
    {:run-eid       run-eid
     :run-id        run-id
     :slice-eid     slice-eid
     :iteration-eid iter-eid
     :iteration-id  iter-id
     :state         :test-design}))

;; --- run lifecycle: fresh run vs. resume, driving the orchestrator loop ------
;;
;; The orchestrator OWNS the loop; these fns only build the effect context `ctx`
;; the orchestrator consumes and hand control to `orchestrator/drive` /
;; `orchestrator/resume-iteration!`. The `ctx` map is exactly the effect context
;; the orchestrator documents (conn, agent-invoker, run/slice/iteration eids,
;; role/capability, cwd, injectable verification command). Nothing here decides a
;; transition; `drive` calls `core/transition` for that.

(defn effect-context
  "Assemble the orchestrator effect-context map `ctx` from a store connection, an
  `AgentInvoker`, seeded entity ids, and the run `config` (calculation over the
  supplied values; no I/O).

  This is the single place the entry point wires the durable store, the selected
  backend, and the per-run inputs (`:cwd`, `:model`, `:prompt`) into the shape
  the orchestrator's effects read (see `workflow.orchestrator` docstring). Extra
  per-dispatch inputs (`:command`, `:changes`, `:role`, `:capability`) are added
  by the orchestrator/caller for a specific Step."
  [conn invoker {:keys [run-eid slice-eid iteration-eid]} {:keys [model prompt cwd backend]}]
  (cond-> {:conn          conn
           :agent-invoker invoker
           :run-eid       run-eid
           :slice-eid     slice-eid
           :iteration-eid iteration-eid
           :model         model
           :backend       backend}
    prompt (assoc :prompt prompt)
    cwd    (assoc :cwd cwd)))

(defn resolve-requirements
  "ACTION: when `config` names a `:requirements-file`, read it and return
  `config` with `:requirements` set to the file's VERBATIM contents — the
  SAME pure `:requirements` config field `parse-args` already defines,
  overriding any inline `--requirements` text (F3).

  This is the one place the requirements FILE is actually read: `parse-args`
  stays pure (it only captures the raw path string), and this thin action does
  the `slurp`. Requirements documents do not fit on a command line and risk
  exceeding `ARG_MAX`; this is how a real requirements doc
  (`.kiro/specs/.../requirements.md`-shaped) reaches a fresh Run.

  Returns `config` unchanged when no `:requirements-file` is set. On a read
  failure (missing/unreadable file) returns `config` with an `:error` map
  describing the failure and `:requirements` untouched — the caller (
  `fresh-run!`) fails closed rather than starting a run with a swallowed I/O
  error or silently falling back to whatever inline text happened to also be
  present."
  [{:keys [requirements-file] :as config}]
  (if-not requirements-file
    config
    (try
      (assoc config :requirements (slurp requirements-file))
      (catch Exception e
        (assoc config :error
               {:code    :requirements-file-unreadable
                :message (str "Could not read --requirements-file " requirements-file
                              ": " (.getMessage e))
                :path    requirements-file})))))

(defn fresh-run!
  "Start a FRESH Run and drive it through its first Iteration (ACTION; STR-1).

  Resolves a `:requirements-file` if named (`resolve-requirements`, F3),
  failing closed without bootstrapping anything if the file cannot be read.
  Otherwise bootstraps the run/slice/iteration (`bootstrap-run!`), which
  durably commits the first transition into `:test-design` — the start of
  EVERY iteration (design, State machine) — assembles the orchestrator effect
  context (`ctx`), and HANDS CONTROL TO THE ORCHESTRATOR: `orch/run-iteration!`
  composes `dispatch-step!`, `drive`, and `run-review-round!` into the real
  test-design -> RED -> implement -> GREEN -> review round -> AND-gate pass
  (STR-1 — this is what makes the namespace docstring's \"no orphaned code,
  reachable from `-main`\" claim true for a fresh Run; `orchestrator/drive` was
  previously invoked nowhere in `src/`, only in tests).

  `config` supplies `run-iteration!`'s `spec` via `:red-command`,
  `:green-command`, `:existing-coverage-kind`, `:correctness-verdict`,
  `:structural-verdict` (all optional; verdicts default to `:request-changes`,
  never inferring approval, R-8.6). This entry point never re-decides
  anything `orchestrator`/`core` already own — it only resolves config,
  bootstraps, and hands control off.

  Returns
    {:mode :fresh :run-id <uuid> :ids <seeded ids> :ctx <effect context>
     :iteration <orch/run-iteration! result>
     :drive {:state <where the pass settled> :error <fail-closed error|nil>}}
  or, on an unreadable requirements file, `{:mode :fresh :error {…}}` (nothing
  bootstrapped)."
  [conn invoker config]
  (let [config* (resolve-requirements config)]
    (if (:error config*)
      {:mode :fresh :error (:error config*)}
      (let [ids       (bootstrap-run! conn config*)
            ctx       (effect-context conn invoker ids config*)
            iteration (orch/run-iteration!
                       ctx
                       (select-keys config* [:red-command :green-command
                                              :existing-coverage-kind
                                              :correctness-verdict :structural-verdict]))]
        {:mode      :fresh
         :run-id    (:run-id ids)
         :ids       ids
         :ctx       ctx
         :iteration iteration
         :error     (:error iteration)
         :drive     {:state (:state iteration) :error (:error iteration)}}))))

(defn- run-eid-by-id
  "Look up the run entity id for a `:run/id` UUID string (ACTION), or nil."
  [conn run-id-str]
  (let [uuid (parse-uuid run-id-str)]
    (ffirst (d/q '[:find ?e :in $ ?rid :where [?e :run/id ?rid]]
                 (d/db conn) uuid))))

(defn- current-iteration-eid
  "Read the run's first slice's current iteration entity id (ACTION), or nil.

  A resume operates on the SAME trace (design, Resume): this locates the active
  Iteration whose in-doubt Steps the orchestrator reconciles."
  [conn run-eid]
  (let [slice-eid (ffirst (d/q '[:find ?s :in $ ?r
                                 :where [?s :slice/run ?r]]
                               (d/db conn) run-eid))]
    (when slice-eid
      (:db/id (:slice/current-iteration
               (d/pull (d/db conn) [{:slice/current-iteration [:db/id]}] slice-eid))))))

(defn resume-run!
  "RESUME an interrupted Run on the SAME trace (ACTION; R-18, R-8.5).

  Locates the run (`--run-id`) and its active Iteration, then hands control to
  `orchestrator/resume-iteration!`, which reconciles any in-doubt Step against
  observable reality (`workflow.fs` recovery: re-run RED/GREEN, inspect
  artifacts) before recording its outcome — failing closed if reality is
  indeterminate — and records resume-staleness for any approval whose Revision
  counter advanced. This entry point never restarts at `:test-design`; that is
  reserved for a review-authorized correction, which the orchestrator mints
  itself.

  `config` supplies `:run-id`; `:recovery` (optional) is the per-in-doubt-Step
  recovery-inputs map `orchestrator/resume-iteration!` consumes. Returns
    {:mode :resume :run-eid <eid> :iteration-eid <eid>
     :resume <orchestrator/resume-iteration! result>}
  or {:mode :resume :error {…}} when the run/iteration cannot be located (fail
  closed)."
  [conn {:keys [run-id recovery] :as _config}]
  (if-let [run-eid (run-eid-by-id conn run-id)]
    (if-let [iter-eid (current-iteration-eid conn run-eid)]
      {:mode          :resume
       :run-eid       run-eid
       :iteration-eid iter-eid
       :resume        (resume/resume-iteration! conn {:iteration-eid iter-eid
                                                       :recovery (or recovery {})})}
      {:mode  :resume
       :error {:code :no-active-iteration
               :message "The run has no active iteration to resume."
               :run-id run-id}})
    {:mode  :resume
     :error {:code :run-not-found
             :message "No run found for the supplied --run-id."
             :run-id run-id}}))

(defn execute-run!
  "Drive a Run for `config`, opening and ALWAYS closing the durable store
  (ACTION; R-4.2, R-17.3).

  The top-level orchestration action a caller (or `-main`) uses once it has a
  valid `config`: it opens the store (`open-store!`), dispatches to `fresh-run!`
  or `resume-run!` per `:mode`, and closes the store in a `finally` so LMDB
  resources are released even on failure.

  The selected `AgentInvoker` is built ONLY for a fresh run (`make-invoker`) —
  a resume dispatches no new agent (it reconciles in-doubt Steps against
  observable reality), so it needs no backend and never constructs one. An
  optional pre-built `:invoker` in `config` (or a passed `invoker`) overrides
  `make-invoker` for a fresh run — the seam that lets a `fake-agent` and a temp
  store drive the wiring without a real binary.

  Returns the `fresh-run!` / `resume-run!` result map (with the store already
  closed)."
  ([config] (execute-run! config nil))
  ([config invoker]
   (let [conn (open-store! config)]
     (try
       (case (:mode config)
         :resume (resume-run! conn config)
         (fresh-run! conn
                     (or invoker (:invoker config) (make-invoker config))
                     config))
       (finally
         (close-store! conn))))))

(defn -main
  "CLI entry point (ACTION): parse argv, then open the store, build the backend,
  and drive the orchestrator loop for a fresh run or a resume.

  On `--help` or an invalid configuration (`parse-args` reported `:errors`) prints
  `usage` and returns without touching the world — fail closed on a malformed
  configuration rather than opening a store or spawning anything. Otherwise
  delegates to `execute-run!`, which opens/closes the store and drives the orchestrator.

  Keeps I/O at the edge: the parse is pure, and this fn only prints and calls the
  edge actions."
  [& args]
  (let [config (parse-args args)]
    (cond
      (:help? config)
      (println usage)

      (seq (:errors config))
      (do (binding [*out* *err*]
            (doseq [e (:errors config)] (println "error:" e))
            (println)
            (println usage))
          config)

      :else
      (let [result (execute-run! config)]
        (println "Run" (name (:mode result))
                 (if (:error result)
                   (str "failed closed: " (get-in result [:error :message]))
                   (str "settled"
                        (when-let [rid (:run-id result)] (str " (run-id " rid ")"))
                        (when-let [st (get-in result [:drive :state])] (str " at " (name st))))))
        result))))
