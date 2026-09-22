(ns workflow.fs
  "Filesystem actions for the multi-agent development workflow.

  Unlike `workflow.rules.core` (pure calculations), this namespace holds ACTIONS: it
  reads the current filesystem state. Its scope is exactly two things
  (design, Filesystem actions):

    1. Capability observation — after an agent invocation returns, read the file
       changes it produced (paths created + edited) from the CURRENT filesystem
       state and classify each path as a test file or a
       production/implementation file, producing the {:path :change :class}
       changes that `workflow.rules.core/capability-violation?` consumes (R-1.5, R-3.6,
       R-11.3).
    2. R-18 interruption-recovery reads (a separate downstream concern, task
       5.2): re-run verification and inspect produced artifacts to reconcile an
       in-doubt Step (R-18.3).

  It has NO role in Revision identity. The Revision counter lives entirely in
  Datalevin and is a monotonically increasing integer (design R-8); nothing here
  hashes files, builds a manifest, consults git, or derives any Revision value
  from the filesystem. These are filesystem reads that recover *what the agent
  did to the code*, never a Revision."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; --- Test vs. production path classification (design R-1, R-3, R-11) ---------
;;
;; The capability boundaries are decided over a change's :class — :test for a
;; test file, :production for a production/implementation file (see
;; `workflow.rules.core/capability-descriptors`). A path is a test file when it lives
;; under a test root directory (e.g. `test/…`, `tests/…`) OR its filename marks
;; it as a test (a `_test`/`-test` segment, as in `foo_test.clj`, or a `.test.`/
;; `.spec.` infix, as in `foo.test.js`). Every other path is a
;; production/implementation file. This classification is a pure CALCULATION over
;; the path string — it reads no file contents and derives no Revision.

(defn- normalize-path
  "Normalize `path` to forward-slash segments for classification, stripping any
  leading `./`. Pure string reshaping; no filesystem access."
  [path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace #"^\./" "")))

(defn test-path?
  "True iff `path` names a test file rather than a production/implementation file.

  A path is classified as a test when either (a) any of its directory segments
  is a test root (`test` or `tests`), or (b) its filename carries a test marker:
  a `_test`/`-test` segment (e.g. `foo_test.clj`, `foo-test.clj`, `test_foo.py`)
  or a `.spec.` infix (e.g. `foo.spec.ts`). All other paths are
  production/implementation files. Pure calculation over the path string; no
  filesystem access and no Revision derivation."
  [path]
  (let [normalized (normalize-path path)
        segments   (str/split normalized #"/")
        dir-segs   (butlast segments)
        filename   (str/lower-case (or (last segments) ""))]
    (boolean
     (or (some #{"test" "tests"} dir-segs)
         (re-find #"(?:^|[._-])tests?[._-]" filename)
         (re-find #"[._-]spec[._-]" filename)))))

(defn classify-path
  "Classify `path` as `:test` or `:production` (design Capability descriptors).

  `:test` for a test file, `:production` for a production/implementation file —
  the `:class` value `workflow.rules.core/capability-violation?` decides boundaries
  over. Pure calculation over the path string; no filesystem access and no
  Revision derivation."
  [path]
  (if (test-path? path) :test :production))

;; --- observe-changes: read + classify an invocation's produced changes -------

(defn- ->change
  "Build a produced-change map for `path` with `change` kind (`:created` or
  `:edited`), attaching the observed `:class`. Shaped exactly as
  `workflow.rules.core/capability-violation?` consumes: {:path :change :class}. Pure
  calculation over the path and kind."
  [path change]
  {:path   (str path)
   :change change
   :class  (classify-path path)})

(defn- exists?
  "True iff `path`, resolved against `cwd` when supplied, exists on disk now.
  The single filesystem read of this namespace's observation action."
  [cwd path]
  (.exists (if cwd
             (io/file (str cwd) (str path))
             (io/file (str path)))))

;; --- Real produced-change observation: directory snapshot/diff (F2) ---------
;;
;; `observe-changes` (below) only CONFIRMS paths it is handed via `:created`/
;; `:edited`; something upstream still has to DECIDE which paths those are for
;; a real invocation (fail-closed review finding F2: a real run never populated
;; `:changes`, so the capability boundary was never actually checked outside
;; tests that hand-construct it). The mechanism is a directory snapshot taken
;; immediately BEFORE an agent invocation and again immediately AFTER: any path
;; absent before and present after is `:created`; any path present in both
;; whose last-modified time changed is `:edited`. `snapshot-dir` is the ACTION
;; (it reads the filesystem); `diff-snapshot` is the pure CALCULATION over two
;; snapshot maps — no filesystem access, deterministic. Neither computes a
;; Revision; this recovers *what the agent did to the code* for capability
;; observation only (R-1.4/1.5, R-3.5/3.6, R-11.2/11.3), exactly like the rest
;; of this namespace.

(defn snapshot-dir
  "ACTION: return `{relative-path last-modified-millis}` for every regular file
  currently under `cwd` (recursively), or `{}` when `cwd` is nil or is not a
  directory. Paths are forward-slash-relative to `cwd`, matching what
  `observe-changes`/`classify-path` expect.

  ponytail: a full recursive `file-seq` walk on every dispatch; fine at the
  scale a single agent invocation's working directory reaches — switch to a
  watched/indexed diff if a cwd ever grows large enough for this to matter."
  [cwd]
  (if (nil? cwd)
    {}
    (let [root (io/file (str cwd))]
      (if (.isDirectory root)
        (let [root-path (.getAbsolutePath root)
              prefix-len (inc (count root-path))]
          (into {}
                (comp (filter (fn [^java.io.File f] (.isFile f)))
                      (map (fn [^java.io.File f]
                             [(-> (subs (.getAbsolutePath f) prefix-len)
                                  (str/replace "\\" "/"))
                              (.lastModified f)])))
                (file-seq root)))
        {}))))

(defn diff-snapshot
  "CALCULATION (pure): compare two `snapshot-dir` maps and classify every
  changed path as `:created` or `:edited`.

  A path absent from `before` but present in `after` is `:created`; a path
  present in both whose last-modified value differs is `:edited`; a path
  whose last-modified value is unchanged is not a produced change (nothing to
  report — a re-read of an untouched file is not a write). Returns
  `{:created […] :edited […]}`, both sorted vectors of path strings, shaped
  for `observe-changes`'s `ctx`. Pure map comparison; no I/O."
  [before after]
  (let [before (or before {})
        after  (or after {})]
    {:created (vec (sort (keys (apply dissoc after (keys before)))))
     :edited  (vec (sort (keep (fn [[path mtime]]
                                  (when (and (contains? before path)
                                             (not= mtime (get before path)))
                                    path))
                                after)))}))

(defn observe-changes
  "ACTION: read the file changes a just-returned invocation produced and classify
  each as a test vs. production/implementation change (design, Post-invocation
  verification step 1; R-1.5, R-3.6, R-11.3, R-18.3).

  `ctx` describes what the invocation reported it did to the code, plus where the
  work happened:

    {:created [\"…\" …]   ; paths the invocation created (new files)
     :edited  [\"…\" …]   ; paths the invocation edited (existing files)
     :cwd     path}      ; optional working directory the paths are relative to

  Each declared path is confirmed against the CURRENT filesystem state — a change
  is reported only for a path that actually exists on disk now, so a phantom or
  rolled-back edit is not treated as a produced change (R-18: reconciled against
  observable reality). Every confirmed path is classified via `classify-path`
  and emitted as {:path p :change :created|:edited :class :test|:production},
  precisely the shape `workflow.rules.core/capability-violation?` consumes. The result
  is a SET of such change maps.

  This reads the filesystem (it is an action) but computes NO Revision: no
  hashing, no manifest, no git, no filesystem-derived Revision value. The
  Revision counter is a Datalevin integer (R-8), never derived here. The pure
  boundary DECISION over the returned changes lives in `workflow.rules.core`; the
  fail-closed transition a violation drives is an action in
  `workflow.orchestrator`."
  [ctx]
  (let [cwd (:cwd ctx)]
    (into #{}
          (comp (mapcat (fn [[change paths]]
                          (map (fn [path] [change path]) paths)))
                (filter (fn [[_change path]] (exists? cwd path)))
                (map (fn [[change path]] (->change path change))))
          [[:created (:created ctx)]
           [:edited  (:edited ctx)]])))

;; --- R-18 interruption-recovery reads (design R-18.3, R-18.4) ----------------
;;
;; When a Run resumes and a Step is found `:dispatched` with no recorded outcome
;; it is `step-in-doubt?` (a pure predicate in `workflow.rules.core`): neither assumed
;; complete nor assumed untouched (R-18.2). To record its ACTUAL outcome the
;; orchestrator reconciles it against OBSERVABLE REALITY (R-18.3) — it re-runs the
;; relevant verification (RED / GREEN) and inspects the artifacts the agent was
;; supposed to produce on disk. The recovered outcome is then recorded as
;; Datalevin facts by the orchestrator.
;;
;; ACD separation is preserved here exactly as elsewhere in this namespace:
;;
;;   * The ACTIONS are `run-verification` (shell out to a verification command)
;;     and `artifacts-present?` (read the filesystem). Both are injectable: the
;;     verification command is supplied by the caller, so a resume can re-run the
;;     real test suite while a test can hand in a fake command that returns a
;;     chosen exit code without spawning a real run (R-18.4: reconciliation is
;;     safe and verifiable).
;;   * The DECISIONS `red-outcome` and `green-outcome` are pure calculations over
;;     the observed verification result — they map an exit code (and whether the
;;     expected artifacts exist) to the very event keyword the `workflow.rules.core`
;;     transition table consumes (`:red-verified` / `:red-invalid` / `:green`),
;;     or to a fail-closed `{:error …}` when reality is indeterminate (R-18.5).
;;
;; As with the rest of `workflow.fs`, these reads recover *what the agent did to
;; the code*; they compute NO Revision — no hashing, no manifest, no git, no
;; filesystem-derived Revision value. Revision identity is a Datalevin integer
;; comparison (R-8) and lives nowhere near here.

(defn run-verification
  "ACTION: run a verification `command` and return its observed result.

  `command` is the injectable verification invocation — a vector of strings, e.g.
  `[\"clojure\" \"-M:test\"]` for a real resume, or a fake such as
  `[\"sh\" \"-c\" \"exit 1\"]` in a test so reconciliation never spawns a real run
  (R-18.4: agent effects are made verifiable so reconciling an in-doubt step is
  safe). `opts` may carry `:cwd` — the working directory the command runs in.

  Returns an observed-result map:

    {:ran? true  :exit int :stdout s :stderr s}   ; the command executed
    {:ran? false :error {…}}                       ; the command could not be run

  The exit code is the observable signal the pure `red-outcome` / `green-outcome`
  decisions read; RED verification expects a NON-zero exit (the required behavior
  is still missing) and GREEN expects a zero exit (the suite passes). Shelling out
  is I/O, hence an action; the classification of the exit code is a separate pure
  calculation. Reads nothing about Revision."
  [command {:keys [cwd] :as _opts}]
  (if (empty? command)
    {:ran? false
     :error {:code    :no-verification-command
             :message "No verification command was supplied to re-run."}}
    (try
      (let [result (apply shell/sh (cond-> (vec command)
                                     cwd (concat [:dir (str cwd)])))]
        {:ran?   true
         :exit   (:exit result)
         :stdout (:out result)
         :stderr (:err result)})
      (catch Exception e
        {:ran?  false
         :error {:code      :verification-unrunnable
                 :message   "The verification command could not be executed."
                 :exception (.getMessage e)}}))))

(defn artifacts-present?
  "ACTION: true iff every path in `artifact-paths` exists on disk now, resolved
  against `cwd` when supplied.

  This inspects the produced artifacts a Step was expected to leave on disk
  (R-18.3), part of the observable reality an in-doubt Step is reconciled against.
  An empty `artifact-paths` vacuously yields true (there is nothing to require).
  Pure filesystem read; derives no Revision. `cwd` may be nil."
  [cwd artifact-paths]
  (every? (fn [path] (exists? cwd path)) artifact-paths))

(defn red-outcome
  "DECISION (pure): recover a RED verification's outcome event from an observed
  `result` (as returned by `run-verification`).

  RED evidence is a test that FAILS because required behavior is missing, so a
  non-zero exit is the confirming signal:

    * ran, non-zero exit -> `:red-verified`  (the test failed as RED requires)
    * ran, zero exit     -> `:red-invalid`   (the test passed, so it is not RED)
    * could not be run   -> `{:error {:code :indeterminate …}}` (fail closed,
                             R-18.5) — reality cannot be determined, so the
                             orchestrator refuses to advance rather than guessing.

  The returned keyword is exactly what the `workflow.rules.core` transition table
  consumes for a `:test-design` step, so a recovered outcome feeds straight back
  into the pure state machine. This distinguishing of RED from not-RED by exit
  code does NOT judge WHY a failure occurred (syntax/fixture/etc., R-1.3); that
  attribution is the reviewer/orchestrator's concern, not this recovery read.
  Pure calculation over the observed result; no I/O and no Revision."
  [result]
  (if (:ran? result)
    (if (zero? (:exit result)) :red-invalid :red-verified)
    {:error {:code    :indeterminate
             :message "RED verification could not be re-run; reality is indeterminate."
             :cause   (:error result)}}))

(defn green-outcome
  "DECISION (pure): recover a GREEN verification's outcome event from an observed
  `result` (as returned by `run-verification`).

  GREEN is the state in which the relevant test suite PASSES after
  implementation, so a zero exit is the confirming signal:

    * ran, zero exit     -> `:green`   (the suite passes)
    * ran, non-zero exit -> `:red-verified`  (the suite still fails: not GREEN —
                             the implementation did not land, so the recovered
                             reality is that RED still holds and implementation is
                             owed; the orchestrator does not advance to review)
    * could not be run   -> `{:error {:code :indeterminate …}}` (fail closed,
                             R-18.5).

  The returned keyword is a `workflow.rules.core` transition event. Pure calculation
  over the observed result; no I/O and no Revision."
  [result]
  (if (:ran? result)
    (if (zero? (:exit result)) :green :red-verified)
    {:error {:code    :indeterminate
             :message "GREEN verification could not be re-run; reality is indeterminate."
             :cause   (:error result)}}))

(defn existing-coverage-outcome
  "DECISION (pure): recover a `:verify-existing-coverage` outcome event from an
  observed `result` (as returned by `run-verification`), given WHICH R-16 case
  `kind` claims.

  R-16.2 and R-16.3 accept EXISTING coverage as valid evidence instead of a
  freshly-manufactured failure, but they require opposite observable
  realities, so `kind` selects the polarity:

    * `:defect-evidence`      (R-16.2) — an existing test already demonstrates
                                the repair's defect, so the confirming signal
                                is that the command CURRENTLY FAILS (non-zero
                                exit); a currently-passing result means there
                                is no defect evidence at all.
    * `:behavior-preserving`  (R-16.3) — a structural refactor retains its
                                existing passing tests, so the confirming
                                signal is that the command CURRENTLY PASSES
                                (zero exit); a currently-failing result means
                                the refactor broke something and owes a real
                                RED/GREEN cycle, not this shortcut.

  Returns:

    * `:existing-coverage-confirmed` — the observed exit matches the polarity
      `kind` requires;
    * `{:error {:code :existing-coverage-not-confirmed …}}` — the observed
      exit does NOT match (fail closed rather than assuming pre-existing
      evidence);
    * `{:error {:code :existing-coverage-kind-required …}}` — `kind` is
      neither `:defect-evidence` nor `:behavior-preserving` (fail closed
      rather than guessing which polarity applies);
    * `{:error {:code :indeterminate …}}` — the command could not be run
      (R-18.5).

  Pure calculation over the observed result and the caller-supplied `kind`;
  no I/O and no Revision."
  [result kind]
  (cond
    (not (:ran? result))
    {:error {:code    :indeterminate
             :message "Existing-coverage verification could not be run; reality is indeterminate."
             :cause   (:error result)}}

    (not (#{:defect-evidence :behavior-preserving} kind))
    {:error {:code    :existing-coverage-kind-required
             :message (str "Existing-coverage verification requires a :kind of "
                            ":defect-evidence (R-16.2) or :behavior-preserving (R-16.3) "
                            "to know which polarity confirms it; none/an unrecognized kind "
                            "was supplied.")}}

    (= kind :defect-evidence)
    (if (zero? (:exit result))
      {:error {:code    :existing-coverage-not-confirmed
               :message (str "The command currently passes, so there is no existing failing "
                              "test demonstrating the defect (R-16.2) — failing closed rather "
                              "than assuming pre-existing evidence.")}}
      :existing-coverage-confirmed)

    :else ;; :behavior-preserving
    (if (zero? (:exit result))
      :existing-coverage-confirmed
      {:error {:code    :existing-coverage-not-confirmed
               :message (str "The command currently fails, so the refactor's existing passing "
                              "tests are not retained (R-16.3) — a real RED/GREEN cycle is owed "
                              "rather than assuming pre-existing evidence.")}})))

(defn recover-step-outcome
  "ACTION: recover an in-doubt Step's actual outcome by reconciling it against
  observable reality (design R-18.3; Resume). Composes the recovery ACTIONS with
  the pure outcome DECISION.

  `phase` is `:red` or `:green` — which verification the interrupted Step owed.
  `ctx` supplies:

    {:command       [\"…\" …]   ; injectable verification command to re-run
     :artifact-paths [\"…\" …]  ; optional artifacts the Step should have produced
     :cwd           path}      ; optional working directory (paths + command)

  The Step is reconciled by (1) re-running the verification command via
  `run-verification` and (2) confirming the expected artifacts exist on disk via
  `artifacts-present?`. The recovered outcome is:

    * a fail-closed `{:error …}` if the verification could not be run (reality is
      indeterminate, R-18.5);
    * a fail-closed `{:error {:code :missing-artifact …}}` if the verification ran
      but a required artifact is absent (the produced work is not observable on
      disk, so its outcome is not confirmable — fail closed rather than assume it
      landed, R-18.2/R-18.5);
    * otherwise the pure `red-outcome`/`green-outcome` event keyword the
      `workflow.rules.core` transition table consumes.

  This recovers *what the agent did to the code*; it records NO outcome itself
  (the orchestrator transacts the recovered outcome as Datalevin facts) and
  computes NO Revision — no hashing, no manifest, no git, no filesystem-derived
  Revision. Never assumes the Step complete nor untouched."
  [phase {:keys [command artifact-paths cwd] :as _ctx}]
  (let [result  (run-verification command {:cwd cwd})
        decide  (case phase
                  :red   red-outcome
                  :green green-outcome)
        outcome (decide result)]
    (cond
      ;; Verification could not be run: reality is indeterminate, fail closed.
      (:error outcome) outcome
      ;; Verification ran but a required artifact is not on disk: the produced
      ;; work is not observable, so its outcome is not confirmable — fail closed.
      (not (artifacts-present? cwd artifact-paths))
      {:error {:code    :missing-artifact
               :message "A required produced artifact is absent on disk; outcome is not confirmable."
               :phase   phase}}
      ;; Reconciled against observable reality: the recovered outcome event.
      :else outcome)))
