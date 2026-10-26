import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

PROFILE = Path(__file__).resolve().parents[2] / "deployment" / "production"
SPEC = importlib.util.spec_from_file_location("scheduled_backup", PROFILE / "scheduled-backup.py")
JOB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(JOB)


class ScheduledBackupTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.root.chmod(0o700)
        self.environment = {"SELECTEL_SERVER_ID": "123", "SELECTEL_TOKEN_FILE": "/private/test-token",
                            "TOVER_BACKUP_ROOT": str(self.root), "TOVER_BACKUP_KEEP_LOCAL": "2"}
        self.calls = []

    def tearDown(self):
        self.temporary.cleanup()

    def backup(self, name):
        directory = self.root / name
        directory.mkdir()
        (directory / "COMPLETE").touch()
        return directory

    def run_command(self, arguments, **options):
        self.calls.append(arguments)
        if "backup" in arguments:
            self.backup(Path(arguments[-1]).name)

    def run_job(self, callback=None):
        with patch.dict(os.environ, self.environment, clear=True), \
                patch.object(JOB.subprocess, "run", side_effect=callback or self.run_command):
            JOB.main()

    def test_consistent_backup_precedes_provider_and_pruning_is_scoped(self):
        oldest = self.backup("tover-123-20200101T000000Z")
        self.backup("tover-123-20200102T000000Z")
        foreign = self.backup("tover-456-20200101T000000Z")
        incomplete = self.root / "tover-123-20200103T000000Z"
        incomplete.mkdir()
        outside = self.root / "unrelated"
        outside.mkdir()
        linked = self.root / "tover-123-20190101T000000Z"
        linked.symlink_to(outside, target_is_directory=True)
        self.run_job()
        self.assertIn("--check", self.calls[0])
        self.assertIn("backup", self.calls[1])
        self.assertIn("--name", self.calls[2])
        self.assertFalse(oldest.exists())
        for path in (foreign, incomplete, outside, linked):
            self.assertTrue(path.exists())

    def test_provider_failure_does_not_prune_local_backups(self):
        old = self.backup("tover-123-20200101T000000Z")
        self.backup("tover-123-20200102T000000Z")

        def fail_provider(arguments, **options):
            self.run_command(arguments, **options)
            if "--name" in arguments:
                raise subprocess.CalledProcessError(1, arguments)

        with self.assertRaises(subprocess.CalledProcessError):
            self.run_job(fail_provider)
        self.assertTrue(old.exists())

    def test_local_failure_never_requests_provider_backup(self):
        def fail_local(arguments, **options):
            self.calls.append(arguments)
            if "backup" in arguments:
                raise subprocess.CalledProcessError(1, arguments)

        with self.assertRaises(subprocess.CalledProcessError):
            self.run_job(fail_local)
        self.assertFalse(any("--name" in arguments for arguments in self.calls))

    def test_refuses_unsafe_configuration_before_stopping_services(self):
        for key, value in (("SELECTEL_SERVER_ID", "../123"), ("SELECTEL_TOKEN_FILE", "relative"),
                           ("TOVER_BACKUP_ROOT", "relative"), ("TOVER_BACKUP_KEEP_LOCAL", "1")):
            with self.subTest(key=key), patch.dict(self.environment, {key: value}):
                with self.assertRaises(ValueError):
                    self.run_job()
        self.assertFalse(self.calls)


if __name__ == "__main__":
    unittest.main()
