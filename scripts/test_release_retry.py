#!/usr/bin/env python3
"""Exercise release tag checks from the workflow, without building or publishing."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = Path(os.environ.get("RELEASE_WORKFLOW", ROOT / ".github/workflows/release.yml"))
GUARD = "recheck release tag preconditions before bumping"
MANIFEST = Path("plugin/build.gradle.kts")
ORIGINAL = 'version = "1.2.3"\n'
BUMP = "bump the version"
CURRENT_REF = "refs/tags/v1.2.3"
TARGET_REF = "refs/tags/v1.2.4"
BASH = shutil.which("bash")
REAL_GIT = shutil.which("git")

GIT_STUB = '''\
import json
import sys
import subprocess
from pathlib import Path

args = sys.argv[1:]
with Path("git.log").open("a") as log:
    log.write(json.dumps(args) + "\\n")
if args[0] == "ls-remote":
    fixture = Path("fixture-remote")
    if fixture.exists():
        args[args.index("origin")] = fixture.read_text()
        sys.exit(subprocess.run([REAL_GIT, *args], timeout=5).returncode)
    state = json.loads(Path("remote.json").read_text())
    ref = args[-1]
    result = state.get(ref, "")
    if isinstance(result, int):
        print("a" * 40 + "\\t" + ref)
        print("remote read failed", file=sys.stderr)
        sys.exit(result)
    if result:
        print(result + "\\t" + ref)
elif Path("late-tag-test").exists() and args[0] in ("config", "tag", "push"):
    if args[0] == "push":
        replacement = Path("remote-after-push")
        if replacement.exists():
            Path("fixture-remote").write_text(replacement.read_text())
        error = Path("error-after-push")
        if error.exists():
            Path("remote.json").write_text(json.dumps({"refs/tags/v1.2.4^{}": int(error.read_text())}))
        sys.exit(int(Path("push-status").read_text()))
elif args != ["diff", "--name-only"]:
    sys.exit("unexpected git invocation: " + repr(args))
'''


class ReleaseRetryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if BASH is None or REAL_GIT is None:
            raise RuntimeError("release tag tests require bash and real Git on PATH")

    def setUp(self):
        scratch = Path(os.environ.get("TMPDIR", ROOT / ".tmp"))
        scratch.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="release-retry-", dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        manifest = self.root / MANIFEST
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(ORIGINAL)
        scripts = self.root / "scripts"
        scripts.mkdir()
        bump = scripts / "bump-version.sh"
        bump.write_text(
            f'#!{BASH}\nset -euo pipefail\n'
            'if [ "${2:-}" = --dry-run ]; then echo 1.2.4; exit 0; fi\n'
            'printf bumped > bump.log\n'
            f"printf 'version = \"1.2.4\"\\n' > {MANIFEST}\n"
        )
        bump.chmod(0o755)
        notes = self.root / "plugin/src/main/resources/META-INF/plugin.xml"
        notes.parent.mkdir(parents=True, exist_ok=True)
        notes.write_text("<change-notes>\n<p>1.2.4 release notes</p>\n</change-notes>\n")
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        git = bin_dir / "git"
        git.write_text(f"#!{sys.executable}\nREAL_GIT = {REAL_GIT!r}\n" + GIT_STUB)
        git.chmod(0o755)
        self.env = {
            **os.environ,
            "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
            "RUNNER_TEMP": str(self.root),
            # A job output or ambient variable must not replace the checkout's version.
            "CURRENT": "9.9.9",
        }
        jobs = yaml.safe_load(WORKFLOW.read_text())["jobs"]
        self.steps = jobs["release"]["steps"]
        self.plan_steps = jobs["plan"]["steps"][2:4]
        self.remote({CURRENT_REF: "a" * 40})

    def remote(self, refs):
        (self.root / "remote.json").write_text(json.dumps(refs))

    def attempt(self):
        outputs = {
            "${{ needs.plan.outputs.version }}": "1.2.4",
            "${{ needs.plan.outputs.current }}": "1.2.3",
            "${{ inputs.bump }}": "patch",
        }
        # Checkout is represented by the original fixture. Run the actual shell bodies,
        # including the bump boundary, but never reach a build, commit, tag or push.
        for step in self.steps[1:]:
            self.assertIn("run", step, "an action was inserted before the bump")
            env = {key: outputs.get(value, value) for key, value in step.get("env", {}).items()}
            result = self.run_step(step, env)
            if result.returncode or step.get("name") == BUMP:
                return result
        self.fail("release job has no bump boundary")

    def run_step(self, step, env=None):
        return subprocess.run(
            [BASH, "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", step["run"]],
            cwd=self.root, env={**self.env, **(env or {})},
            text=True, capture_output=True, timeout=5,
        )

    def plan(self):
        for step in self.plan_steps:
            result = self.run_step(step, {
                "BUMP": "patch", "GITHUB_OUTPUT": str(self.root / "plan-output"),
            })
            if result.returncode:
                return result
        return result

    def fixture_remote(self, refs):
        # Keep real fixture refs under scratch; no test deletes or moves them.
        remote = Path(tempfile.mkdtemp(prefix="release-tag-remote-", dir=self.root.parent))

        def git(*args, text=None):
            result = subprocess.run(
                [REAL_GIT, "-C", str(remote), *args], input=text, text=True,
                capture_output=True, timeout=5,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return result.stdout.strip()

        git("init", "--bare", "--quiet", "--initial-branch=main")
        objects = {}
        for ref, value in refs.items():
            annotated = isinstance(value, tuple)
            sha = git("hash-object", "-w", "--stdin", text=value[0] if annotated else value)
            objects[ref] = sha
            if annotated:
                sha = git("mktag", text=(
                    f"object {sha}\ntype blob\ntag fixture\n"
                    "tagger Release Test <release@example.invalid> 1 +0000\n\nfixture\n"
                ))
            git("update-ref", ref, sha)
        (self.root / "fixture-remote").write_text(str(remote))
        return remote, objects

    def test_exact_ref_git_tail_pattern_really_returns_nested_tag(self):
        alias = "refs/tags/" + CURRENT_REF
        remote, objects = self.fixture_remote({alias: "released"})
        result = subprocess.run(
            [REAL_GIT, "ls-remote", "--refs", "--tags", str(remote), CURRENT_REF],
            capture_output=True, text=True, timeout=5,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"{objects[alias]}\t{alias}\n")
        (remote / "tail-match-proof.log").write_text(result.stdout)
        print(f"real Git tail-match proof ({remote}): {result.stdout.strip()}")

    def test_exact_ref_nested_current_only_is_refused_by_plan_and_retry(self):
        self.fixture_remote({"refs/tags/" + CURRENT_REF: "released"})
        for run in (self.plan, self.attempt):
            with self.subTest(stage=run.__name__):
                result = run()
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("v1.2.3", result.stderr)
                self.assertFalse((self.root / "bump.log").exists())
                self.assertEqual((self.root / MANIFEST).read_text(), ORIGINAL)

    def test_exact_ref_nested_target_only_is_free_for_plan_and_retry(self):
        self.fixture_remote({
            CURRENT_REF: "released", "refs/tags/" + CURRENT_REF: "other",
            "refs/tags/" + TARGET_REF: "other",
        })
        for run in (self.plan, self.attempt):
            with self.subTest(stage=run.__name__):
                result = run()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_exact_ref_mixed_aliases_do_not_hide_exact_target_collision(self):
        self.fixture_remote({
            CURRENT_REF: "released", "refs/tags/" + CURRENT_REF: "other",
            TARGET_REF: "target", "refs/tags/" + TARGET_REF: "other",
        })
        result = self.plan()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("v1.2.4", result.stderr)
        self.assert_refused()

    def test_plan_remote_errors_abort_before_filtering_either_query(self):
        for ref in (CURRENT_REF, TARGET_REF):
            for status in (2, 128):
                with self.subTest(ref=ref, status=status):
                    self.remote({CURRENT_REF: "a" * 40, ref: status})
                    result = self.plan()
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("remote read failed", result.stderr)
                    self.assertFalse((self.root / "bump.log").exists())

    def late_tag(self, release_sha, push_status=0, after_push=None):
        (self.root / "late-tag-test").touch()
        (self.root / "push-status").write_text(str(push_status))
        if after_push is not None:
            (self.root / "remote-after-push").write_text(str(after_push))
        step = next(step for step in self.steps if step.get("name") == "create and push the tag")
        return self.run_step(step, {"VERSION": "1.2.4", "RELEASE_COMMIT": release_sha})

    def late_writes(self):
        return [args for args in self.git_calls() if args[0] in ("tag", "push")]

    def test_exact_ref_late_nested_target_does_not_prevent_tag_creation(self):
        _, objects = self.fixture_remote({"refs/tags/" + TARGET_REF: "other"})
        result = self.late_tag(objects["refs/tags/" + TARGET_REF])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual([args[0] for args in self.late_writes()], ["tag", "push"])

    def test_exact_ref_late_lightweight_and_annotated_idempotence_ignores_aliases(self):
        for annotated in (False, True):
            with self.subTest(annotated=annotated):
                _, objects = self.fixture_remote({
                    TARGET_REF: ("release",) if annotated else "release",
                    "refs/tags/" + TARGET_REF: ("other",),
                })
                result = self.late_tag(objects[TARGET_REF])
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(self.late_writes(), [])

    def test_exact_ref_late_conflicting_exact_tag_is_not_hidden_by_alias(self):
        _, objects = self.fixture_remote({
            TARGET_REF: ("other",), "refs/tags/" + TARGET_REF: ("release",),
        })
        result = self.late_tag(objects["refs/tags/" + TARGET_REF])
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("not the commit this run releases", result.stderr)
        self.assertEqual(self.late_writes(), [])

    def test_exact_ref_late_rejected_push_rereads_only_exact_tag_and_peeled_tag(self):
        for annotated in (False, True):
            with self.subTest(annotated=annotated):
                after, objects = self.fixture_remote({
                    TARGET_REF: ("release",) if annotated else "release",
                    "refs/tags/" + TARGET_REF: ("other",),
                })
                self.fixture_remote({"refs/tags/" + TARGET_REF: "other"})
                result = self.late_tag(objects[TARGET_REF], push_status=1, after_push=after)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertIn("push of v1.2.4 was rejected", result.stdout)

    def test_exact_ref_late_rejected_push_with_alias_only_still_refuses(self):
        remote, objects = self.fixture_remote({"refs/tags/" + TARGET_REF: "release"})
        result = self.late_tag(objects["refs/tags/" + TARGET_REF], push_status=1, after_push=remote)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("remote has no such tag", result.stderr)
        self.assertEqual([args[0] for args in self.late_writes()], ["tag", "push"])

    def test_late_tag_remote_errors_abort_even_after_rejected_push(self):
        for second_read in (False, True):
            for status in (2, 128):
                with self.subTest(second_read=second_read, status=status):
                    # The two-pattern query's last argument is the peeled ref.
                    (self.root / "git.log").write_text("")
                    error = self.root / "error-after-push"
                    if error.exists():
                        error.unlink()
                    self.remote({TARGET_REF + "^{}": status})
                    if second_read:
                        (self.root / "remote.json").write_text(json.dumps({TARGET_REF + "^{}": ""}))
                        # The stub changes to this error state only when push is attempted.
                        (self.root / "error-after-push").write_text(str(status))
                    result = self.late_tag("a" * 40, push_status=1)
                    self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                    self.assertIn("remote read failed", result.stderr)
                    if not second_read:
                        self.assertEqual(self.late_writes(), [])

    def git_calls(self):
        log = self.root / "git.log"
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    def remote_calls(self):
        log = self.root / "git.log"
        calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
        return [args for args in calls if args[0] == "ls-remote"]

    def assert_refused(self):
        result = self.attempt()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / "bump.log").exists(), "stale plan reached the bump")
        self.assertEqual((self.root / MANIFEST).read_text(), ORIGINAL)
        return result

    def test_current_tag_disappears_after_cached_plan(self):
        self.remote({})
        result = self.assert_refused()
        self.assertIn("v1.2.3", result.stderr)

    def test_target_tag_appears_after_cached_plan(self):
        self.remote({CURRENT_REF: "a" * 40, TARGET_REF: "b" * 40})
        result = self.assert_refused()
        self.assertIn("v1.2.4", result.stderr)

    def test_unchanged_remote_allows_bump_and_queries_exact_original_refs(self):
        result = self.attempt()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue((self.root / "bump.log").exists())
        self.assertEqual(self.remote_calls(), [
            ["ls-remote", "--refs", "--tags", "origin", CURRENT_REF],
            ["ls-remote", "--refs", "--tags", "origin", TARGET_REF],
        ])

    def test_remote_errors_fail_closed_for_either_query(self):
        for ref in (CURRENT_REF, TARGET_REF):
            for status in (2, 128):
                with self.subTest(ref=ref, status=status):
                    self.remote({CURRENT_REF: "a" * 40, ref: status})
                    result = self.assert_refused()
                    self.assertIn("remote read failed", result.stderr)

    def test_cached_current_cannot_substitute_for_original_manifest(self):
        original = ORIGINAL.replace("1.2.3", "1.2.2")
        (self.root / MANIFEST).write_text(original)
        # Only the cached current is tagged; the actual checkout version is not.
        result = self.attempt()
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse((self.root / "bump.log").exists())
        self.assertEqual((self.root / MANIFEST).read_text(), original)

    def test_unreadable_or_missing_version_fails_before_remote_or_bump(self):
        manifest = self.root / MANIFEST
        for contents in (None, "no version here\n"):
            with self.subTest(contents=contents):
                if contents is None:
                    manifest.unlink()
                else:
                    manifest.write_text(contents)
                result = self.attempt()
                self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertFalse((self.root / "bump.log").exists())
                self.assertEqual(self.remote_calls(), [])

    def test_each_release_attempt_rechecks_remote(self):
        result = self.attempt()
        self.assertEqual(result.returncode, 0, result.stderr)
        # A release-only retry gets the same checkout and cached output, not a new plan.
        (self.root / MANIFEST).write_text(ORIGINAL)
        (self.root / "bump.log").unlink()
        self.remote({CURRENT_REF: "a" * 40, TARGET_REF: "b" * 40})
        self.assert_refused()
        self.assertEqual(len(self.remote_calls()), 4)

    def test_guard_is_unconditional_between_checkout_and_first_bump(self):
        self.assertTrue(self.steps[0]["uses"].startswith("actions/checkout@"))
        self.assertEqual(self.steps[1]["name"], GUARD)
        self.assertNotIn("if", self.steps[1])
        self.assertEqual(self.steps[1]["env"]["TARGET"], "${{ needs.plan.outputs.version }}")
        self.assertEqual(self.steps[2]["name"], BUMP)
        for step in self.steps[:2]:
            self.assertNotIn("scripts/bump-version.sh", step.get("run", ""))
            self.assertNotIn("git commit", step.get("run", ""))
            self.assertNotIn("git push", step.get("run", ""))


if __name__ == "__main__":
    unittest.main()
