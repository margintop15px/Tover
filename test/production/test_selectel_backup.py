import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch
import urllib.error
import uuid

sys.dont_write_bytecode = True
PROFILE = Path(__file__).resolve().parents[2] / "deployment" / "production"
SPEC = importlib.util.spec_from_file_location("selectel_backup", PROFILE / "selectel-backup.py")
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)
SERVER = 42590
NAME = "tover-42590-20261023T120000Z"
NEW_ID = str(uuid.UUID(int=100))


def backup(number, *, server=SERVER, name=None, status="finished", **values):
    return {"id": str(uuid.UUID(int=number)), "scalet": server,
            "name": name or f"tover-{server}-202610{number:02}T120000Z",
            "status": status, "active": True, "locked": False, "is_deleted": False, **values}


class FakeProvider:
    def __init__(self, snapshots=(), statuses=("creating", "uploading", "finished")):
        self.calls = []
        self.snapshots = list(snapshots)
        self.statuses = list(statuses)
        self.before_delete = {}

    def __call__(self, method, path, token, payload=None, timeout=30):
        self.calls.append((method, path, timeout))
        if method == "POST":
            return {"id": NEW_ID, "scalet": SERVER, "status": "defined"}
        if path == f"/backups/{NEW_ID}":
            status = self.statuses.pop(0) if len(self.statuses) > 1 else self.statuses[0]
            return backup(100, name=NAME, status=status)
        if path == "/backups":
            return self.snapshots
        identifier = path.rsplit("/", 1)[1]
        if method == "GET":
            return self.before_delete.get(identifier, next(item for item in self.snapshots if item["id"] == identifier))
        if method == "DELETE":
            return None
        raise AssertionError((method, path))


class SelectelBackupTest(unittest.TestCase):
    def setUp(self):
        self.now = 0
        self.announced = []

    def sleep(self, duration):
        self.now += duration

    def run_backup(self, provider, keep=7):
        return BACKUP.create_backup("private-token", SERVER, NAME, keep, call=provider,
                                    clock=lambda: self.now, sleep=self.sleep,
                                    announce=self.announced.append)

    def test_polls_until_available_and_prints_id_before_polling(self):
        provider = FakeProvider()
        original = provider.__call__

        def call(*args, **kwargs):
            if args[0] == "GET":
                self.assertEqual(self.announced, [NEW_ID])
            return original(*args, **kwargs)

        self.assertEqual(self.run_backup(call), NEW_ID)
        self.assertEqual(self.now, 20)
        self.assertEqual(provider.calls[0][:2], ("POST", f"/scalets/{SERVER}/backup"))
        self.assertEqual(provider.calls[-1][:2], ("GET", "/backups"))

    def test_ambiguous_post_failure_is_never_retried(self):
        calls = []

        def failed(*args, **kwargs):
            calls.append(args)
            raise BACKUP.BackupError("Provider request failed; it was not retried")

        with self.assertRaises(BACKUP.BackupError):
            self.run_backup(failed)
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.announced, [])

    def test_failed_inconsistent_or_unavailable_backup_never_prunes(self):
        invalid = [backup(100, name=NAME, status="failed"),
                   backup(100, name=NAME, active=False),
                   backup(100, name=NAME, locked=True),
                   backup(100, name=NAME, is_deleted=True),
                   backup(100, name=NAME, server=123),
                   backup(100, name="different"),
                   backup(99, name=NAME)]
        for response in invalid:
            with self.subTest(response=response):
                provider = FakeProvider()

                def call(method, path, *args, **kwargs):
                    if method == "GET":
                        provider.calls.append((method, path, 30))
                        return response
                    return provider(method, path, *args, **kwargs)

                with self.assertRaises(BACKUP.BackupError):
                    self.run_backup(call)
                self.assertNotIn(("GET", "/backups", 30), provider.calls)
                self.assertFalse(any(method == "DELETE" for method, _, _ in provider.calls))

    def test_timeout_bounds_polling_and_skips_retention(self):
        provider = FakeProvider(statuses=("creating",))
        with patch.object(BACKUP, "WAIT_SECONDS", 15):
            with self.assertRaisesRegex(BACKUP.BackupError, "Timed out"):
                self.run_backup(provider)
        self.assertEqual(self.now, 15)
        self.assertEqual([timeout for method, path, timeout in provider.calls if method == "GET"], [15, 5])
        self.assertFalse(any(path == "/backups" for _, path, _ in provider.calls))

    def test_retention_keeps_new_and_newest_six_complete_managed_backups(self):
        snapshots = [backup(number) for number in range(1, 10)]
        provider = FakeProvider(snapshots)
        self.run_backup(provider)
        deleted = [path for method, path, _ in provider.calls if method == "DELETE"]
        self.assertEqual(set(deleted), {f"/backups/{str(uuid.UUID(int=number))}" for number in (1, 2, 3)})
        self.assertNotIn(f"/backups/{NEW_ID}", deleted)

    def test_retention_never_deletes_foreign_invalid_or_unavailable_backups(self):
        snapshots = [backup(1), backup(2), backup(3)]
        snapshots.extend([backup(4, server=123, name="tover-42590-20261004T120000Z"),
                          backup(5, name="tover-42590-20261005T120000Z-copy"),
                          backup(6, name="tover-42590-20260230T120000Z"),
                          backup(7, status="creating"), backup(8, locked=True),
                          backup(9, active=False), backup(10, is_deleted=True),
                          backup(11, id="../../bad"), backup(12, scalet=str(SERVER))])
        provider = FakeProvider(snapshots)
        self.run_backup(provider, keep=2)
        deleted = [path for method, path, _ in provider.calls if method == "DELETE"]
        self.assertEqual(set(deleted), {f"/backups/{str(uuid.UUID(int=number))}" for number in (1, 2)})

    def test_retention_rechecks_target_before_deletion(self):
        provider = FakeProvider([backup(1), backup(2)])
        provider.before_delete[str(uuid.UUID(int=1))] = backup(1, locked=True)
        self.run_backup(provider, keep=2)
        self.assertFalse(any(method == "DELETE" for method, _, _ in provider.calls))

    def test_invalid_name_server_retention_and_create_uuid_make_no_further_calls(self):
        provider = FakeProvider()
        for server, name, keep in [(0, NAME, 7), (SERVER, "tover-42591-20261023T120000Z", 7),
                                   (SERVER, "tover-42590-20260230T120000Z", 7), (SERVER, NAME, 1)]:
            with self.assertRaises(BACKUP.BackupError):
                BACKUP.create_backup("token", server, name, keep, call=provider)
        self.assertEqual(provider.calls, [])
        for identifier in (None, "not-a-uuid", "../../secret", "B8895839-52D3-40E1-8C86-20463F38FEC4"):
            with self.assertRaises(BACKUP.BackupError):
                self.run_backup(lambda *args, **kwargs: {"id": identifier, "scalet": SERVER})
        self.assertEqual(self.announced, [])

    def test_token_validation_and_check_are_local_only(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("private-token\n")
            token.chmod(0o600)
            self.assertEqual(BACKUP.read_token(token), "private-token")
            with patch.object(BACKUP, "create_backup", side_effect=AssertionError("network")):
                self.assertEqual(BACKUP.main(["--server-id", str(SERVER), "--token-file", str(token), "--check"]), 0)
            token.chmod(0o644)
            with self.assertRaises(BACKUP.BackupError):
                BACKUP.read_token(token)
            token.chmod(0o600)
            for contents in ("", "private-token\n\n", "private\ntoken", "private token", "token\r\n", "x" * 8193):
                token.write_text(contents)
                with self.assertRaises(BACKUP.BackupError):
                    BACKUP.read_token(token)
            link = Path(directory) / "link"
            link.symlink_to(token)
            with self.assertRaises(BACKUP.BackupError):
                BACKUP.read_token(link)
            with self.assertRaises(BACKUP.BackupError):
                BACKUP.read_token("relative-token")

    def test_http_errors_are_redacted_and_redirects_disabled(self):
        class Opener:
            def open(self, message, timeout):
                self_request = message
                self.assertEqual(self_request.full_url, BACKUP.API + "/backups")
                raise urllib.error.URLError("private-token and response body")

        opener = Opener()
        opener.assertEqual = self.assertEqual
        with patch.object(BACKUP.urllib.request, "build_opener", return_value=opener) as build:
            with self.assertRaises(BACKUP.BackupError) as error:
                BACKUP.request("GET", "/backups", "private-token")
            self.assertNotIn("private-token", str(error.exception))
            self.assertIsInstance(build.call_args.args[0], BACKUP.NoRedirect)
        handler = BACKUP.NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "", {}, "https://foreign.invalid"))

    def test_cli_credential_error_does_not_echo_token_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("sensitive-token\nextra-line")
            token.chmod(0o600)
            output = io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(BACKUP.main(["--server-id", str(SERVER), "--token-file", str(token), "--check"]), 1)
            self.assertNotIn("sensitive-token", output.getvalue())


if __name__ == "__main__":
    unittest.main()
