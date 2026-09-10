"""Regression tests against the maintained Python block in hermes.org.

Run with: python -m unittest discover -s tests -v
All agent processes are local fakes; these tests never contact Hermes or a model.
"""

import ast
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


DOCUMENT = Path(__file__).resolve().parents[1] / "hermes.org"


def orchestrator_source():
    match = re.search(
        r"^#\+begin_src python :tangle \.hermes/pipeline/orchestrator\.py\n"
        r"(.*?)^#\+end_src",
        DOCUMENT.read_text(encoding="utf-8"),
        re.MULTILINE | re.DOTALL,
    )
    if match is None:
        raise AssertionError("Maintained orchestrator Python block not found")
    return match.group(1)


def load_actions():
    """Load imports and definitions without CLI/configuration side effects."""
    tree = ast.parse(orchestrator_source(), filename=str(DOCUMENT))
    definitions = ast.Module(
        body=[
            node
            for node in tree.body
            if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef))
        ],
        type_ignores=[],
    )
    namespace = {"__name__": "hermes_orchestrator_under_test"}
    exec(compile(definitions, str(DOCUMENT), "exec"), namespace)
    return namespace


FAKE_HERMES = r'''
import json
import os
from pathlib import Path
import sys

arguments = sys.argv[1:]
prompt_path = arguments[arguments.index("--query-file") + 1]
record = {
    "argv": arguments,
    "cwd": os.getcwd(),
    "prompt": Path(prompt_path).read_text(encoding="utf-8"),
    "prompt_path": prompt_path,
}
if os.environ.get("FAKE_HERMES_RECORD"):
    Path(os.environ["FAKE_HERMES_RECORD"]).write_text(json.dumps(record), encoding="utf-8")
print(json.dumps(record), flush=True)
sys.exit(int(os.environ.get("FAKE_HERMES_EXIT", "0")))
'''

PIPELINE_HERMES = r'''
import json
import os
from pathlib import Path
import re
import sys

arguments = sys.argv[1:]
prompt = Path(arguments[arguments.index("--query-file") + 1]).read_text(encoding="utf-8")
role = re.search(r"ROLE:([a-z-]+)", prompt).group(1)
with Path(os.environ["PIPELINE_CALLS"]).open("a", encoding="utf-8") as stream:
    stream.write(role + "\n")
if os.environ.get("FAIL_ROLE") == role:
    sys.exit(19)
if os.environ.get("FAIL_REPAIR_ROLE") == role and "Repair " in prompt:
    sys.exit(23)
project = Path(arguments[arguments.index("--in") + 1])
revision_match = re.search(r"Expected revision(?: \(copy exactly\))?: ([^\n]+)", prompt)
revision = revision_match.group(1) if revision_match else None
if role == "test-designer":
    (project / ".ai").mkdir(exist_ok=True)
    (project / ".ai/test-plan.md").write_text("fresh plan", encoding="utf-8")
    status = "TESTS_UPDATED" if "Update tests" in prompt else "RED_VERIFIED"
    response = {"status": status, "evidence": "tests executed with expected result", "artifact": ".ai/test-plan.md"}
elif role == "implementer":
    (project / ".ai").mkdir(exist_ok=True)
    (project / ".ai/implementation-report.md").write_text("fresh report", encoding="utf-8")
    response = {"status": "GREEN", "evidence": "complete relevant suite passed", "artifact": ".ai/implementation-report.md"}
elif role in ("correctness-reviewer", "structural-reviewer"):
    request_marker = os.environ.get("REQUEST_ONCE_MARKER")
    if role == "correctness-reviewer" and request_marker and not Path(request_marker).exists():
        Path(request_marker).write_text("requested", encoding="utf-8")
        response = {"verdict": "REQUEST_CHANGES", "revision": revision, "evidence": "defect found",
                    "findings": [{"id": "C1", "owner": "implementer", "requirement": "behavior works",
                                  "location": "source.py:1", "evidence": "test fails", "why": "incorrect",
                                  "required_outcome": "make behavior pass"}]}
    else:
        response = {"verdict": "APPROVE", "revision": revision, "evidence": "reviewed current snapshot", "findings": []}
else:
    output_match = re.search(r"Required output file: ([^\n]+)", prompt)
    output = Path(output_match.group(1))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("<mxfile><diagram/></mxfile>", encoding="utf-8")
    response = {"status": "COMPLETE", "revision": revision, "evidence": "valid Draw.io XML written"}
print("```json")
print(json.dumps(response))
print("```")
'''


class TemporaryProject(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.project = self.root / "project with spaces"
        self.project.mkdir()
        self.prompts = self.project / ".hermes/pipeline/prompts"
        self.prompts.mkdir(parents=True)
        (self.prompts / "test-designer.md").write_text("Role instructions", encoding="utf-8")
        (self.prompts.parent / "config.yaml").write_text("agents: {}\n", encoding="utf-8")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fake = self.bin / "hermes"
        fake.write_text(f"#!{sys.executable}\n" + FAKE_HERMES, encoding="utf-8")
        fake.chmod(0o755)

    def environment(self):
        environment = os.environ.copy()
        for name in ("PIPELINE_REQUIREMENTS_FILE", "PIPELINE_PROJECT", "PIPELINE_FEATURE",
                     "FAKE_HERMES_RECORD", "FAKE_HERMES_EXIT"):
            environment.pop(name, None)
        environment["PATH"] = str(self.bin) + os.pathsep + environment.get("PATH", "")
        return environment


class AgentRunnerTests(TemporaryProject):
    def setUp(self):
        super().setUp()
        self.actions = load_actions()
        self.actions.update(PROJECT=str(self.project), FEATURE="feature", AGENTS={}, SETTINGS={},
                            HERMES_EXECUTABLE=str(self.bin / "hermes"))

    def test_success_preserves_literal_arguments_and_prompt_and_removes_tempfile(self):
        marker = self.root / "shell-was-evaluated"
        model = f"model$(touch {marker})"
        self.actions["AGENTS"] = {
            "test-designer": {
                "model": model,
                "provider": "provider with spaces",
                "max_turns": 7,
                "run_budget": 30,
                "reasoning": "medium",
                "toolsets": "read,terminal",
            }
        }
        with mock.patch.dict(os.environ, self.environment(), clear=True):
            result = self.actions["run_agent"](
                "test-designer", "Requirements: café; `literal`", "Extra context", worktree=True
            )

        self.assertTrue(result["success"])
        self.assertFalse(result["timeout"])
        self.assertEqual(result["exit_code"], 0)
        self.assertEqual(result["stderr"], "")
        record = json.loads(result["stdout"])
        arguments = record["argv"]
        self.assertEqual(arguments[:1], ["chat"])
        for flag, expected in (
            ("--model", model), ("--provider", "provider with spaces"),
            ("--in", str(self.project)), ("--max-turns", "7"),
            ("--run-budget", "30"), ("--reasoning", "medium"),
            ("--toolsets", "read,terminal"),
        ):
            self.assertEqual(arguments[arguments.index(flag) + 1], expected)
        self.assertIn("--worktree", arguments)
        self.assertEqual(record["cwd"], str(self.project))
        self.assertIn("Requirements: café; `literal`", record["prompt"])
        self.assertIn("Extra context", record["prompt"])
        self.assertFalse(marker.exists(), "Shell evaluated an argument as a command")
        self.assertFalse(Path(record["prompt_path"]).exists())

    def run_with_process_outcome(self, outcome):
        prompt_paths = []

        def execute(arguments, **kwargs):
            prompt_path = Path(arguments[arguments.index("--query-file") + 1])
            prompt_paths.append(prompt_path)
            self.assertTrue(prompt_path.is_file())
            if isinstance(outcome, BaseException):
                raise outcome
            return outcome

        with mock.patch.object(self.actions["subprocess"], "run", side_effect=execute):
            result = self.actions["run_agent"]("test-designer", "Requirements")
        self.assertEqual(len(prompt_paths), 1)
        self.assertFalse(prompt_paths[0].exists(), "Temporary prompt leaked")
        return result

    def test_nonzero_exit_preserves_output_without_claiming_timeout(self):
        result = self.run_with_process_outcome(
            subprocess.CompletedProcess([], 124, stdout="partial report", stderr="agent failure")
        )
        self.assertFalse(result["success"])
        self.assertFalse(result["timeout"])
        self.assertEqual(result["exit_code"], 124)
        self.assertEqual(result["stdout"], "partial report")
        self.assertEqual(result["stderr"], "agent failure")

    def test_spawn_failure_returns_failure_and_removes_tempfile(self):
        result = self.run_with_process_outcome(FileNotFoundError("hermes executable missing"))
        self.assertFalse(result["success"])
        self.assertFalse(result["timeout"])
        self.assertIsNone(result["exit_code"])
        self.assertEqual(result["stdout"], "")
        self.assertIn("hermes executable missing", result["stderr"])

    def test_timeout_preserves_partial_byte_output_and_removes_tempfile(self):
        result = self.run_with_process_outcome(
            subprocess.TimeoutExpired([], 1, output=b"partial \xff", stderr=b"error \xff")
        )
        self.assertFalse(result["success"])
        self.assertTrue(result["timeout"])
        self.assertEqual(result["exit_code"], 124)
        self.assertEqual(result["stdout"], "partial \ufffd")
        self.assertEqual(result["stderr"], "error \ufffd")

    def test_timeout_without_partial_output_returns_empty_strings(self):
        result = self.run_with_process_outcome(subprocess.TimeoutExpired([], 1))
        self.assertFalse(result["success"])
        self.assertTrue(result["timeout"])
        self.assertEqual(result["stdout"], "")
        self.assertEqual(result["stderr"], "")


class PipelineContractTests(unittest.TestCase):
    def setUp(self):
        self.actions = load_actions()

    def test_approval_requires_evidence_and_empty_findings(self):
        validate = self.actions["validate_verdict"]
        revision = "snapshot:abc"
        self.assertFalse(validate({"verdict": "APPROVE", "revision": revision}, revision)["valid"])
        self.assertFalse(validate({
            "verdict": "APPROVE", "revision": revision, "evidence": "checked",
            "findings": [{"id": "C1", "owner": "implementer"}],
        }, revision)["valid"])

    def test_non_object_verdict_fails_closed_without_crashing(self):
        result = self.actions["validate_verdict"](42, "snapshot:abc")
        self.assertFalse(result["valid"])

    def test_multiple_json_verdicts_are_ambiguous(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "review.md"
            artifact.write_text(
                '```json\n{"verdict":"APPROVE"}\n```\n'
                '```json\n{"verdict":"REQUEST_CHANGES"}\n```\n',
                encoding="utf-8",
            )
            self.assertIsNone(self.actions["parse_review_json"](artifact))

    def test_directory_is_not_an_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(self.actions["StageFailed"]):
                self.actions["verify_artifact"](directory, "test plan")


@unittest.skipUnless(importlib.util.find_spec("yaml"), "CLI requires installed PyYAML")
class CommandLineTests(TemporaryProject):
    def setUp(self):
        super().setUp()
        self.script = self.root / "orchestrator.py"
        self.script.write_text(orchestrator_source(), encoding="utf-8")
        self.record = self.root / "agent-invocation.json"

    def launch(self, *arguments, **environment_overrides):
        environment = self.environment()
        environment.update(FAKE_HERMES_RECORD=str(self.record), FAKE_HERMES_EXIT="17")
        environment.update(environment_overrides)
        return subprocess.run(
            [sys.executable, str(self.script), *arguments],
            cwd=self.root, env=environment, capture_output=True, text=True, timeout=10,
        )

    def test_requirements_must_be_passed_positionally_even_with_environment_variable(self):
        requirements = self.project / "requirements.md"
        requirements.write_text("Requirements", encoding="utf-8")
        result = self.launch(PIPELINE_REQUIREMENTS_FILE=str(requirements))
        self.assertEqual(result.returncode, 2)
        self.assertIn("requirements_file", result.stderr)
        self.assertIn("required", result.stderr)
        self.assertFalse(self.record.exists())

    def test_missing_requirements_file_is_reported_before_any_agent_starts(self):
        result = self.launch("missing.md", "--project", str(self.project))
        self.assertEqual(result.returncode, 2)
        self.assertIn("cannot read requirements file", result.stderr)
        self.assertIn(str(self.project / "missing.md"), result.stderr)
        self.assertFalse(self.record.exists())

    def test_directory_cannot_be_used_as_a_requirements_document(self):
        result = self.launch(".", "--project", str(self.project))
        self.assertEqual(result.returncode, 2)
        self.assertIn("cannot read requirements file", result.stderr)
        self.assertFalse(self.record.exists())

    def test_whitespace_only_requirements_are_rejected_before_any_agent_starts(self):
        requirements = self.project / "empty.md"
        requirements.write_text(" \n\t", encoding="utf-8")
        result = self.launch("empty.md", "--project", str(self.project))
        self.assertEqual(result.returncode, 2)
        self.assertIn("requirements file is empty", result.stderr)
        self.assertFalse(self.record.exists())

    def test_project_relative_requirements_and_explicit_feature_reach_agent(self):
        docs = self.project / "docs"
        docs.mkdir()
        (docs / "spec.md").write_text("Project-relative café requirements", encoding="utf-8")
        result = self.launch(
            "docs/spec.md", "--project", str(self.project), "--feature", "explicit-feature",
            PIPELINE_PROJECT=str(self.root / "wrong-project"), PIPELINE_FEATURE="wrong-feature",
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("test-designer failed", result.stdout)
        record = json.loads(self.record.read_text(encoding="utf-8"))
        self.assertEqual(record["cwd"], str(self.project))
        self.assertIn("Project-relative café requirements", record["prompt"])
        self.assertIn("Feature: explicit-feature", record["prompt"])
        self.assertFalse(Path(record["prompt_path"]).exists())

    def test_absolute_requirements_path_and_filename_feature_default(self):
        requirements = self.root / "external-spec.md"
        requirements.write_text("External requirements", encoding="utf-8")
        result = self.launch(str(requirements), "--project", str(self.project))
        self.assertEqual(result.returncode, 1, result.stderr)
        record = json.loads(self.record.read_text(encoding="utf-8"))
        self.assertIn("External requirements", record["prompt"])
        self.assertIn("Feature: external-spec", record["prompt"])

    def test_explicit_hermes_path_works_without_path_lookup(self):
        requirements = self.project / "requirements.md"
        requirements.write_text("Requirements", encoding="utf-8")
        environment = self.environment()
        environment["PATH"] = os.pathsep.join(
            part for part in environment.get("PATH", "").split(os.pathsep)
            if part != str(self.bin)
        )
        environment.update(FAKE_HERMES_RECORD=str(self.record), FAKE_HERMES_EXIT="17")
        result = subprocess.run(
            [sys.executable, str(self.script), "requirements.md", "--project", str(self.project),
             "--hermes", str(self.bin / "hermes")],
            cwd=self.root, env=environment, capture_output=True, text=True, timeout=10,
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertTrue(self.record.is_file())

    def test_missing_explicit_hermes_path_fails_before_agents_start(self):
        requirements = self.project / "requirements.md"
        requirements.write_text("Requirements", encoding="utf-8")
        result = self.launch(
            "requirements.md", "--project", str(self.project),
            "--hermes", str(self.root / "missing-hermes"),
        )
        self.assertEqual(result.returncode, 2)
        self.assertIn("Hermes executable is not executable or was not found", result.stderr)
        self.assertFalse(self.record.exists())


@unittest.skipUnless(importlib.util.find_spec("yaml"), "CLI requires installed PyYAML")
class EndToEndPipelineTests(TemporaryProject):
    def setUp(self):
        super().setUp()
        self.script = self.root / "orchestrator.py"
        self.script.write_text(orchestrator_source(), encoding="utf-8")
        fake = self.bin / "hermes"
        fake.write_text(f"#!{sys.executable}\n" + PIPELINE_HERMES, encoding="utf-8")
        fake.chmod(0o755)
        for role in ("test-designer", "implementer", "correctness-reviewer",
                     "structural-reviewer", "flow-documenter"):
            (self.prompts / f"{role}.md").write_text(f"ROLE:{role}", encoding="utf-8")
        (self.project / "requirements.md").write_text("Build the behavior", encoding="utf-8")
        subprocess.run(["git", "init", "-q"], cwd=self.project, check=True)
        subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=self.project, check=True)
        subprocess.run(["git", "config", "user.name", "Test"], cwd=self.project, check=True)
        subprocess.run(["git", "config", "commit.gpgsign", "false"], cwd=self.project, check=True)
        subprocess.run(["git", "add", "."], cwd=self.project, check=True)
        subprocess.run(["git", "commit", "-qm", "fixture"], cwd=self.project, check=True)
        self.calls = self.root / "calls.txt"

    def launch(self, *extra, **environment_overrides):
        environment = self.environment()
        environment["PIPELINE_CALLS"] = str(self.calls)
        environment.update(environment_overrides)
        return subprocess.run(
            [sys.executable, str(self.script), "requirements.md", "--project", str(self.project), *extra],
            cwd=self.root, env=environment, capture_output=True, text=True, timeout=20,
        )

    def test_complete_pipeline_persists_reviews_state_and_final_documentation(self):
        result = self.launch("--slice", "first", "--slice", "second")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls.count("test-designer"), 2)
        self.assertEqual(calls.count("implementer"), 2)
        self.assertEqual(calls.count("correctness-reviewer"), 2)
        self.assertEqual(calls.count("structural-reviewer"), 2)
        self.assertEqual(calls.count("flow-documenter"), 1)
        state = json.loads((self.project / ".ai/workflow/requirements.json").read_text())
        self.assertEqual(state["status"], "COMPLETE")
        self.assertTrue(all(item["status"] == "APPROVED" for item in state["slices"]))
        self.assertTrue((self.project / ".ai/reviews/correctness.md").is_file())
        self.assertTrue((self.project / "docs/requirements-flow.drawio").is_file())
        calls_before = list(calls)
        repeated = self.launch("--slice", "first", "--slice", "second")
        self.assertEqual(repeated.returncode, 0, repeated.stdout + repeated.stderr)
        self.assertEqual(self.calls.read_text().splitlines(), calls_before)

    def test_resume_skips_completed_test_design_after_implementation_failure(self):
        failed = self.launch(FAIL_ROLE="implementer")
        self.assertEqual(failed.returncode, 1)
        resumed = self.launch()
        self.assertEqual(resumed.returncode, 0, resumed.stdout + resumed.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls.count("test-designer"), 1)
        self.assertEqual(calls.count("implementer"), 2)

    def test_failed_reviewer_cannot_approve_from_preexisting_artifacts(self):
        reviews = self.project / ".ai/reviews"
        reviews.mkdir(parents=True)
        stale = '```json\n{"verdict":"APPROVE","revision":"stale","evidence":"old","findings":[]}\n```\n'
        (reviews / "correctness.md").write_text(stale, encoding="utf-8")
        (reviews / "structural.md").write_text(stale, encoding="utf-8")
        result = self.launch(FAIL_ROLE="correctness-reviewer")
        self.assertEqual(result.returncode, 1)
        state = json.loads((self.project / ".ai/workflow/requirements.json").read_text())
        self.assertEqual(state["status"], "BLOCKED")
        self.assertNotEqual(state["status"], "COMPLETE")

    def test_review_identity_contains_full_head_and_worktree_digest(self):
        result = self.launch()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        state = json.loads((self.project / ".ai/workflow/requirements.json").read_text())
        revision = state["slices"][0]["revision"]
        head, digest = revision.split(":")
        self.assertEqual(len(head), 40)
        self.assertEqual(len(digest), 64)

    def test_change_after_completion_requires_new_review_and_documentation(self):
        first = self.launch()
        self.assertEqual(first.returncode, 0, first.stdout + first.stderr)
        (self.project / "new-source.py").write_text("value = 1\n", encoding="utf-8")
        second = self.launch()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls.count("test-designer"), 1)
        self.assertEqual(calls.count("implementer"), 1)
        self.assertEqual(calls.count("correctness-reviewer"), 2)
        self.assertEqual(calls.count("structural-reviewer"), 2)
        self.assertEqual(calls.count("flow-documenter"), 2)

    def test_review_findings_trigger_checked_repair_and_fresh_dual_review(self):
        marker = self.root / "request-once"
        result = self.launch(REQUEST_ONCE_MARKER=str(marker))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls.count("implementer"), 2)
        self.assertEqual(calls.count("correctness-reviewer"), 2)
        self.assertEqual(calls.count("structural-reviewer"), 2)

    def test_failed_repair_blocks_before_another_review(self):
        marker = self.root / "request-once"
        result = self.launch(REQUEST_ONCE_MARKER=str(marker), FAIL_REPAIR_ROLE="implementer")
        self.assertEqual(result.returncode, 1)
        calls = self.calls.read_text().splitlines()
        self.assertEqual(calls.count("implementer"), 2)
        self.assertEqual(calls.count("correctness-reviewer"), 1)
        self.assertEqual(calls.count("structural-reviewer"), 1)


if __name__ == "__main__":
    unittest.main()
