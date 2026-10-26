import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("production_publish", ROOT / "deployment/production/publish.py")
PUBLISH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLISH)
TAG = "v0.1.0-tester.1"


class Commands:
    def __init__(self, root):
        self.root = root
        self.calls = []
        self.pending = True
        self.head = "a" * 40
        self.detached = False
        self.local_tag = False
        self.remote_tag = False
        self.image_exists = False
        self.release_status = 404
        self.drivers = [["driver-type", "io.containerd.snapshotter.v1"]]
        self.names = ["README.md"]
        self.fail = None
        self.change_after = None
        self.changed = False
        self.bad_manifest = False
        self.added = False
        self.fail_staged_check = False
        self.origin = "git@github.com:owner/Tover.git"

    def __call__(self, args, *, capture=False, check=True, env=None):
        self.calls.append((args, env))
        result = subprocess.CompletedProcess(args, 0, "", "")
        if args[0] == "git":
            command = args[3:]
            operation = command[0]
            if operation == self.fail:
                raise subprocess.CalledProcessError(1, args)
            if command == ["diff", "--cached", "--check"] and self.added and self.fail_staged_check:
                raise subprocess.CalledProcessError(2, args)
            if command == ["rev-parse", "--show-toplevel"]:
                result.stdout = str(self.root) + "\n"
            elif command == ["rev-parse", "HEAD"]:
                result.stdout = self.head + "\n"
            elif operation == "symbolic-ref":
                result.returncode = 1 if self.detached else 0
                result.stdout = "" if self.detached else "main\n"
            elif operation == "show-ref":
                result.returncode = 0 if self.local_tag else 1
            elif operation == "remote":
                result.stdout = self.origin + "\n"
            elif operation == "ls-files":
                result.stdout = "\0".join(self.names) + "\0"
            elif operation == "ls-remote":
                result.stdout = "a" * 40 + "\trefs/tags/" + TAG if self.remote_tag else ""
            elif command == ["diff", "--cached", "--quiet"]:
                result.returncode = 1 if self.pending else 0
            elif operation == "commit":
                self.pending = False
                self.head = "b" * 40
                self.changed = self.change_after == "commit"
            elif operation == "add":
                self.added = True
            elif operation == "status":
                result.stdout = " M README.md\n" if self.pending or self.changed else ""
            elif operation == "tag":
                self.local_tag = True
            elif operation == "push":
                self.remote_tag = True
        elif args[:2] == ["docker", "info"]:
            result.stdout = json.dumps(self.drivers)
        elif args[:3] == ["docker", "image", "inspect"]:
            result.returncode = 0 if self.image_exists else 1
        elif args[:2] == ["gh", "api"]:
            result.returncode = 0 if self.release_status == 200 else 1
            result.stdout = f"HTTP/2.0 {self.release_status} response\n\n{{}}\n"
        elif args[:3] == ["gh", "release", "create"]:
            result.stdout = "https://github.com/owner/Tover/releases/tag/" + TAG + "\n"
        elif len(args) > 2 and Path(args[1]).name == "release.py":
            if args[2] == self.fail:
                raise subprocess.CalledProcessError(1, args)
            if args[2] == "package":
                output = Path(args[3])
                output.mkdir()
                (output / "release.json").write_text(json.dumps({
                    "source": {"revision": self.head, "dirty": self.bad_manifest},
                    "images": {"app": {"reference": "tover:" + TAG}}}))
                (output / "runtime.txt").write_text("Public runtime bundle\n")
                self.changed = self.change_after == "package"
        elif len(args) > 1 and Path(args[1]).name == "rehearse.py":
            if self.fail == "rehearse":
                raise subprocess.CalledProcessError(1, args)
            self.changed = self.change_after == "rehearse"
        return result


class PublishTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.root = self.directory / "checkout"
        self.root.mkdir()
        (self.root / "README.md").write_text("Public changes\n")
        self.output = self.directory / "release"
        self.commands = Commands(self.root)
        self.runner = patch.object(PUBLISH, "run", self.commands)
        self.which = patch.object(PUBLISH.shutil, "which", lambda name: "/tools/" + name)
        self.runner.start()
        self.which.start()

    def tearDown(self):
        self.runner.stop()
        self.which.stop()
        self.temp.cleanup()

    def publish(self):
        return PUBLISH.publish(TAG, "Release all current changes", self.output, root=self.root)

    def git_calls(self):
        return [args[3:] for args, _ in self.commands.calls if args[0] == "git"]

    def release_calls(self):
        return [args for args, _ in self.commands.calls if args[:3] == ["gh", "release", "create"]]

    def assert_no_git_writes(self):
        self.assertFalse(any(call[0] in {"add", "commit", "tag", "push"} for call in self.git_calls()))

    def test_publishes_only_after_commit_atomic_push_bundle_load_and_rehearsal(self):
        with patch.dict(os.environ, {"GH_REPO": "foreign/wrong", "GH_HOST": "foreign.invalid",
                                     "TOVER_REHEARSAL_NETWORK_BASE": "10.254.240.0/22",
                                     "PYTHONOPTIMIZE": "1"}):
            result = self.publish()
        self.assertEqual(result["url"], "https://github.com/owner/Tover/releases/tag/" + TAG)
        self.assertEqual(result["commit"], "b" * 40)
        git = self.git_calls()
        self.assertIn(["add", "-A"], git)
        self.assertIn(["commit", "--message", "Release all current changes"], git)
        self.assertIn(["tag", "--annotate", TAG, "--message", "Release all current changes", "b" * 40], git)
        push = next(call for call in git if call[0] == "push")
        self.assertEqual(push, ["push", "--atomic", "--no-follow-tags", "origin", "b" * 40 + ":refs/heads/main",
                                f"refs/tags/{TAG}:refs/tags/{TAG}"])
        calls = [args for args, _ in self.commands.calls]
        push_index = next(index for index, args in enumerate(calls) if args[0] == "git" and args[3] == "push")
        package_index = next(index for index, args in enumerate(calls) if len(args) > 2 and args[2] == "package")
        load_index = next(index for index, args in enumerate(calls) if len(args) > 2 and args[2] == "load")
        rehearsal_index = next(index for index, args in enumerate(calls) if len(args) > 1 and Path(args[1]).name == "rehearse.py")
        self.assertLess(push_index, package_index)
        self.assertLess(package_index, load_index)
        self.assertLess(load_index, rehearsal_index)
        release = self.release_calls()[0]
        self.assertGreater(calls.index(release), rehearsal_index)
        self.assertEqual(release[release.index("--repo") + 1], "github.com/owner/Tover")
        for flag in ("--verify-tag", "--prerelease", "--generate-notes", "--notes-file"):
            self.assertIn(flag, release)
        self.assertTrue(any(args[:2] == ["gh", "api"] and "repos/owner/Tover/" in args[-1] for args in calls))
        api = next(args for args in calls if args[:2] == ["gh", "api"])
        self.assertEqual(api[api.index("--hostname") + 1], "github.com")
        environment = self.commands.calls[rehearsal_index][1]
        self.assertEqual(environment["TOVER_REHEARSAL_RELEASE"], str(self.output))
        self.assertEqual(environment["TOVER_REHEARSAL_NETWORK_BASE"], "10.254.240.0/22")
        self.assertNotIn("PYTHONOPTIMIZE", environment)
        archive = Path(result["archive"])
        checksum = Path(result["checksum"]).read_text()
        self.assertEqual(checksum, f"{hashlib.sha256(archive.read_bytes()).hexdigest()}  {archive.name}\n")
        with tarfile.open(archive, "r:gz") as bundle:
            self.assertIn("./release.json", bundle.getnames())
            self.assertNotIn("./.env", bundle.getnames())
        self.assertEqual(release[4:7], [str(archive), result["checksum"], str(self.output / "release.json")])

    def test_clean_tree_does_not_create_an_empty_commit(self):
        self.commands.pending = False
        result = self.publish()
        self.assertEqual(result["commit"], "a" * 40)
        self.assertFalse(any(call[0] == "commit" for call in self.git_calls()))

    def test_newly_staged_whitespace_failure_stops_before_commit_tag_or_push(self):
        self.commands.fail_staged_check = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertIn(["add", "-A"], self.git_calls())
        self.assertFalse(any(call[0] in {"commit", "tag", "push"} for call in self.git_calls()))
        self.assertEqual(self.release_calls(), [])

    def test_reused_local_remote_github_or_image_tag_fails_before_staging(self):
        for attribute, value in (("local_tag", True), ("remote_tag", True), ("release_status", 200), ("image_exists", True)):
            with self.subTest(attribute=attribute):
                original = getattr(self.commands, attribute)
                setattr(self.commands, attribute, value)
                self.commands.calls.clear()
                with self.assertRaises(ValueError):
                    self.publish()
                self.assert_no_git_writes()
                self.assertEqual(self.release_calls(), [])
                setattr(self.commands, attribute, original)

    def test_invalid_or_existing_output_and_invalid_tag_do_not_run_commands(self):
        self.output.mkdir()
        with self.assertRaises(ValueError):
            self.publish()
        for output in (Path("relative"), self.root / "release", self.directory / "asset#label"):
            with self.assertRaises(ValueError):
                PUBLISH.publish(TAG, "message", output, root=self.root)
        for tag in ("latest", "../escape", "bad tag", "--flag", "a" * 129):
            with self.assertRaises(ValueError):
                PUBLISH.publish(tag, "message", self.directory / "unused", root=self.root)
        self.assertEqual(self.commands.calls, [])

    def test_detached_branch_classic_docker_bad_origin_and_api_error_fail_before_staging(self):
        for attribute, value in (("detached", True), ("drivers", []), ("origin", "git@example.net:other/repo.git"),
                                 ("release_status", 403)):
            with self.subTest(attribute=attribute):
                original = getattr(self.commands, attribute)
                setattr(self.commands, attribute, value)
                self.commands.calls.clear()
                with self.assertRaises(ValueError):
                    self.publish()
                self.assert_no_git_writes()
                setattr(self.commands, attribute, original)

    def test_private_paths_are_rejected_before_staging(self):
        for name in ("deployment/production/.env", ".data/store", "captures/private/file.json", "selectel.token", "secret.key"):
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("private")
            self.commands.names = [name]
            self.commands.calls.clear()
            with self.assertRaisesRegex(ValueError, "private/generated"):
                self.publish()
            self.assert_no_git_writes()
        PUBLISH.guard_private_paths(self.root, ["deployment/production/.env.example"])

    def test_push_or_validation_failure_never_creates_a_github_release_or_rolls_back(self):
        for stage in ("push", "package", "load", "rehearse"):
            with self.subTest(stage=stage):
                self.commands = Commands(self.root)
                self.commands.fail = stage
                with patch.object(PUBLISH, "run", self.commands):
                    with self.assertRaises(subprocess.CalledProcessError):
                        self.publish()
                self.assertEqual(self.release_calls(), [])
                self.assertFalse(any(call[0] in {"reset", "revert", "clean"} for call in self.git_calls()))
                self.assertTrue(self.commands.local_tag)
                if self.output.exists():
                    for path in self.output.iterdir():
                        path.unlink()
                    self.output.rmdir()

    def test_unexpected_source_changes_or_dirty_manifest_never_publish(self):
        for stage in ("commit", "package", "rehearse", "manifest"):
            with self.subTest(stage=stage):
                self.commands = Commands(self.root)
                self.commands.change_after = stage
                self.commands.bad_manifest = stage == "manifest"
                with patch.object(PUBLISH, "run", self.commands):
                    with self.assertRaises(ValueError):
                        self.publish()
                self.assertEqual(self.release_calls(), [])
                if self.output.exists():
                    for path in self.output.iterdir():
                        path.unlink()
                    self.output.rmdir()


if __name__ == "__main__":
    unittest.main()
