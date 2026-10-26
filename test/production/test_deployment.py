import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
PROFILE = ROOT / "deployment" / "production"
SPEC = importlib.util.spec_from_file_location("production_config", PROFILE / "config.py")
CONFIG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONFIG)


class ProductionConfigurationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.environment = self.directory / ".env"
        CONFIG.generate(self.environment, PROFILE / ".env.example")
        self.replace("TOVER_DOMAIN", "inventory.tover-test.net")
        self.replace("TOVER_ORIGIN", "https://inventory.tover-test.net")
        self.replace("SMTP_HOST", "smtp.tover-test.net")
        self.replace("SMTP_USER", "test-account")
        self.replace("SMTP_PASS", "'test$password # with spaces'")

    def tearDown(self):
        self.temp.cleanup()

    def replace(self, key, value):
        self.environment.write_text("\n".join(
            f"{key}={value}" if line.startswith(key + "=") else line
            for line in self.environment.read_text().splitlines()) + "\n")

    def test_init_is_private_and_refuses_overwrite(self):
        self.assertEqual(stat.S_IMODE(self.environment.stat().st_mode), 0o600)
        original = self.environment.read_bytes()
        with self.assertRaises(FileExistsError):
            CONFIG.generate(self.environment, PROFILE / ".env.example")
        self.assertEqual(original, self.environment.read_bytes())

    def test_origin_smtp_keys_and_permissions(self):
        self.assertEqual(CONFIG.validate(self.environment)["SMTP_PASS"], "test$password # with spaces")
        cases = [("TOVER_ORIGIN", "http://inventory.tover-test.net"),
                 ("TOVER_ORIGIN", "https://different.tover-test.net"),
                 ("TOVER_ORIGIN", "https://inventory.tover-test.net/"),
                 ("SMTP_HOST", "mailpit"), ("SMTP_PASS", ""),
                 ("ANON_KEY", "invalid"), ("SESSION_ENCRYPTION_KEY", "bad")]
        contents = self.environment.read_text()
        for key, value in cases:
            with self.subTest(key=key, value=value):
                self.environment.write_text(contents)
                self.replace(key, value)
                with self.assertRaises(ValueError):
                    CONFIG.validate(self.environment)
        self.environment.write_text(contents)
        self.environment.chmod(0o644)
        with self.assertRaises(ValueError):
            CONFIG.validate(self.environment)

    def docker_stub(self, failing_dump=False, failing_ps=False, failing_start=False,
                    existing_container=False, failing_volume_ls=False, volume_names=(), blocking_dump=False):
        executable = self.directory / "docker"
        executable.write_text("""#!/usr/bin/env python3
import json, os, sys, time
with open(os.environ['DOCKER_CALLS'], 'a') as output: output.write(json.dumps(sys.argv[1:]) + '\\n')
args = sys.argv[1:]
if args[:2] == ['volume', 'ls']:
    if os.environ.get('FAILING_VOLUME_LS'): sys.exit(1)
    print(os.environ.get('VOLUME_NAMES', ''))
if 'ps' in args:
    if os.environ.get('FAILING_PS'): sys.exit(1)
    if '--services' in args: print('db\\nauth\\nrest\\napp\\nproxy')
    elif '--quiet' in args and os.environ.get('EXISTING_CONTAINER'): print('existing-container-id')
if 'pg_dump' in args and os.environ.get('FAILING_DUMP'): sys.exit(1)
if 'pg_dump' in args and os.environ.get('BLOCKING_DUMP_MARKER'):
    with open(os.environ['BLOCKING_DUMP_MARKER'], 'w') as marker: marker.write('ready')
    while True: time.sleep(1)
if 'start' in args and os.environ.get('FAILING_START'): sys.exit(1)
""")
        executable.chmod(0o755)
        return {**os.environ, "PATH": str(self.directory) + os.pathsep + os.environ["PATH"],
                "TOVER_ENV_FILE": str(self.environment), "DOCKER_CALLS": str(self.directory / "calls"),
                "FAILING_DUMP": "1" if failing_dump else "",
                "FAILING_PS": "1" if failing_ps else "",
                "FAILING_START": "1" if failing_start else "",
                "FAILING_VOLUME_LS": "1" if failing_volume_ls else "",
                "EXISTING_CONTAINER": "1" if existing_container else "",
                "VOLUME_NAMES": "\n".join(volume_names),
                "BLOCKING_DUMP_MARKER": str(self.directory / "dump-blocked") if blocking_dump else ""}

    def bundle_fixture(self, missing_image=False):
        root = (self.directory / "bundle").resolve()
        profile = root / "deployment" / "production"
        profile.mkdir(parents=True)
        for name in ("production.sh", "config.py", "compose.yaml", ".env.example"):
            shutil.copy2(PROFILE / name, profile / name)
        migrations = root / "supabase" / "migrations"
        migrations.mkdir(parents=True)
        for name in ("033_identity_and_sessions.sql", "034_workspace_actor_labels.sql"):
            shutil.copy2(ROOT / "supabase" / "migrations" / name, migrations / name)
        (root / "release.json").write_text(json.dumps({"format": 1, "images": {"app": {
            "reference": CONFIG.read_env(self.environment)["TOVER_APP_IMAGE"], "id": "sha256:" + "a" * 64}}}) + "\n")
        (root / "source-revision").write_text("a" * 40 + "\n")
        # The release helper's own tests cover manifest/image verification.
        (profile / "release.py").write_text("""import json, os, sys
with open(os.environ['RELEASE_CALLS'], 'a') as output: output.write(json.dumps(sys.argv[1:]) + '\\n')
if sys.argv[1] == 'check' and os.environ.get('MISSING_BUNDLE_IMAGE'):
    sys.exit('A bundle image is missing; load-images first')
""")
        environment = {**self.docker_stub(), "RELEASE_CALLS": str(self.directory / "release-calls"),
                       "MISSING_BUNDLE_IMAGE": "1" if missing_image else ""}
        git = self.directory / "git"
        git.write_text("#!/bin/sh\necho 'Git must not run in a release bundle' >&2\nexit 1\n")
        git.chmod(0o755)
        return root, profile, environment

    def restore_fixture(self):
        backup = self.directory / "backup"
        backup.mkdir()
        (backup / "COMPLETE").touch()
        (backup / "SHA256SUMS").touch()
        (backup / ".env").write_bytes(self.environment.read_bytes())
        (backup / "compose.yaml").write_bytes((PROFILE / "compose.yaml").read_bytes())
        return backup

    def test_bundle_install_and_up_only_use_loaded_images(self):
        root, profile, environment = self.bundle_fixture()
        for operation in ("install-fresh", "up"):
            result = subprocess.run([str(profile / "production.sh"), operation],
                                    env=environment, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertFalse(any(command[0] in ("build", "pull") for command in commands))
        starts = [command for command in commands if command[0] == "up"]
        self.assertEqual(len(starts), 4)
        for command in starts:
            self.assertEqual(command[1:4], ["--no-build", "--pull", "never"])
        self.assertEqual(len([command for command in commands if "psql" in command]), 2)
        release_calls = [json.loads(line) for line in (self.directory / "release-calls").read_text().splitlines()]
        expected = ["check", str(root), "--env-file", str(self.environment)]
        self.assertEqual(release_calls, [expected, expected])

    def test_bundle_missing_image_refuses_install_before_mutation(self):
        root, profile, environment = self.bundle_fixture(missing_image=True)
        result = subprocess.run([str(profile / "production.sh"), "install-fresh"],
                                env=environment, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"load-images", result.stderr)
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertEqual(commands, [["config", "--quiet"]])
        self.assertEqual(json.loads((self.directory / "release-calls").read_text()),
                         ["check", str(root), "--env-file", str(self.environment)])

    def test_bundle_load_images_needs_no_private_configuration_and_refuses_build(self):
        root, profile, environment = self.bundle_fixture()
        environment["TOVER_ENV_FILE"] = str(self.directory / "not-created.env")
        result = subprocess.run([str(profile / "production.sh"), "load-images"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads((self.directory / "release-calls").read_text()), ["load", str(root)])
        result = subprocess.run([str(profile / "production.sh"), "build"],
                                env=environment, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"prebuilt images", result.stderr)
        self.assertFalse((self.directory / "calls").exists())

    def test_bundle_backup_and_restore_need_no_git_build_or_pulls(self):
        root, profile, environment = self.bundle_fixture()
        backup = self.directory / "backup"
        result = subprocess.run([str(profile / "production.sh"), "backup", str(backup)],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((backup / "COMPLETE").exists())
        for name in ("source-revision", "release.json"):
            self.assertEqual((backup / name).read_bytes(), (root / name).read_bytes())
        result = subprocess.run([str(profile / "production.sh"), "restore-new", str(backup), "restored-bundle"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertFalse(any(command[0] in ("build", "pull") for command in commands))
        self.assertIn(["create", "--no-build", "--pull", "never", "db", "app"], commands)
        runs = [command for command in commands if command[0] == "run"]
        self.assertEqual(len(runs), 2)
        for command in runs:
            self.assertEqual(command[1:3], ["--pull", "never"])

    def test_bundle_backup_and_restore_work_without_python311_file_digest(self):
        root, profile, environment = self.bundle_fixture()
        (self.directory / "sitecustomize.py").write_text(
            "import hashlib\nhashlib.__dict__.pop('file_digest', None)\n")
        environment["PYTHONPATH"] = str(self.directory)
        backup = self.directory / "backup"
        result = subprocess.run([str(profile / "production.sh"), "backup", str(backup)],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((backup / "COMPLETE").exists())
        result = subprocess.run([str(profile / "production.sh"), "restore-new", str(backup), "python310-check"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        (backup / "identity.dump").write_bytes(b"corrupted backup")
        result = subprocess.run([str(profile / "production.sh"), "restore-new", str(backup), "corrupt-check"],
                                env=environment, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Backup checksum verification failed", result.stderr)

    def test_bundle_backup_requires_metadata_before_stopping_services(self):
        root, profile, environment = self.bundle_fixture()
        (root / "source-revision").unlink()
        backup = self.directory / "backup"
        result = subprocess.run([str(profile / "production.sh"), "backup", str(backup)],
                                env=environment, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"source-revision", result.stderr)
        self.assertFalse(backup.exists())
        self.assertFalse((self.directory / "calls").exists())

    def test_bundle_symlink_uses_physical_release_paths_for_compose(self):
        root, profile, environment = self.bundle_fixture()
        active = self.directory / "active"
        active.symlink_to(root, target_is_directory=True)
        result = subprocess.run([str(active / "deployment" / "production" / "production.sh"), "check"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        call = json.loads((self.directory / "calls").read_text())
        self.assertEqual(call[call.index("--project-directory") + 1], str(profile))
        self.assertEqual(call[call.index("-f") + 1], str(profile / "compose.yaml"))
        self.assertEqual(json.loads((self.directory / "release-calls").read_text()),
                         ["check", str(root), "--env-file", str(self.environment)])

    def test_bundle_restore_refuses_changed_image_identity_under_same_tag(self):
        root, profile, environment = self.bundle_fixture()
        backup = self.directory / "backup"
        result = subprocess.run([str(profile / "production.sh"), "backup", str(backup)],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((root / "release.json").read_text())
        manifest["images"]["app"]["id"] = "sha256:" + "b" * 64
        (root / "release.json").write_text(json.dumps(manifest) + "\n")
        calls_file = self.directory / "calls"
        calls_file.write_text("")
        result = subprocess.run([str(profile / "production.sh"), "restore-new", str(backup), "unused-project"],
                                env=environment, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"original release bundle", result.stderr)
        self.assertEqual(calls_file.read_text(), "")

    def test_failed_backup_restarts_previously_running_services(self):
        destination = self.directory / "backup"
        result = subprocess.run([str(PROFILE / "production.sh"), "backup", str(destination)],
                                env=self.docker_stub(failing_dump=True), capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertIn(["stop", "--timeout", "30", "proxy", "app", "auth", "rest"], commands)
        self.assertEqual(commands[-1], ["start", "--wait", "--wait-timeout", "240",
                                        "db", "auth", "rest", "app", "proxy"])
        self.assertFalse((destination / "COMPLETE").exists())

    def test_successful_backup_completes_and_waits_for_services_to_restart(self):
        destination = self.directory / "backup"
        result = subprocess.run([str(PROFILE / "production.sh"), "backup", str(destination)],
                                env=self.docker_stub(), capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((destination / "COMPLETE").exists())
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertIn(["stop", "--timeout", "30", "proxy", "app", "auth", "rest"], commands)
        self.assertIn(["stop", "--timeout", "30", "db"], commands)
        self.assertEqual(commands[-1], ["start", "--wait", "--wait-timeout", "240",
                                        "db", "auth", "rest", "app", "proxy"])

    def test_terminated_backup_restarts_original_services_without_marking_complete(self):
        destination = self.directory / "backup"
        marker = self.directory / "dump-blocked"
        process = subprocess.Popen([str(PROFILE / "production.sh"), "backup", str(destination)],
                                   env=self.docker_stub(blocking_dump=True), stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(marker.exists(), "Backup did not reach the blocking database dump")
            os.killpg(process.pid, signal.SIGTERM)
            process.communicate(timeout=10)
            self.assertNotEqual(process.returncode, 0)
            self.assertFalse((destination / "COMPLETE").exists())
            calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
            commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
            self.assertEqual(commands[-1], ["start", "--wait", "--wait-timeout", "240",
                                            "db", "auth", "rest", "app", "proxy"])
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.communicate(timeout=5)

    def test_backup_refuses_to_stop_services_when_running_service_discovery_fails(self):
        destination = self.directory / "backup"
        result = subprocess.run([str(PROFILE / "production.sh"), "backup", str(destination)],
                                env=self.docker_stub(failing_ps=True), capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertTrue(any(command[0] == "ps" for command in commands))
        self.assertFalse(any(command[0] in ("stop", "cp", "exec") for command in commands))
        self.assertFalse((destination / "COMPLETE").exists())

    def test_backup_reports_failed_healthy_restart_after_complete_snapshot(self):
        destination = self.directory / "backup"
        result = subprocess.run([str(PROFILE / "production.sh"), "backup", str(destination)],
                                env=self.docker_stub(failing_start=True), capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((destination / "COMPLETE").exists())
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertEqual(commands[-1], ["start", "--wait", "--wait-timeout", "240",
                                        "db", "auth", "rest", "app", "proxy"])

    def test_global_compose_project_cannot_redirect_helper(self):
        environment = self.docker_stub()
        environment["COMPOSE_PROJECT_NAME"] = "tover-identity"
        result = subprocess.run([str(PROFILE / "production.sh"), "status"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0)
        call = json.loads((self.directory / "calls").read_text().splitlines()[-1])
        self.assertEqual(call[call.index("--project-name") + 1], "tover-production")

    def test_migrate_applies_reviewed_identity_chain_before_runtime_role_password(self):
        result = subprocess.run([str(PROFILE / "production.sh"), "migrate"],
                                env=self.docker_stub(), capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in (self.directory / "calls").read_text().splitlines()]
        migrations = [call for call in calls if "psql" in call]
        self.assertEqual(len(migrations), 2)
        script = (PROFILE / "production.sh").read_text()
        self.assertLess(script.index("033_identity_and_sessions.sql"), script.index("034_workspace_actor_labels.sql"))

    def test_fresh_install_preserves_existing_project_containers_and_volumes(self):
        cases = [{"existing_container": True}] + [
            {"volume_names": ["tover-production_" + volume]}
            for volume in ("identity-data", "inventory-data", "tls-data", "tls-config")]
        for scenario in cases:
            with self.subTest(scenario=scenario):
                calls_file = self.directory / "calls"
                calls_file.write_text("")
                result = subprocess.run([str(PROFILE / "production.sh"), "install-fresh"],
                                        env=self.docker_stub(**scenario), capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b"preserved", result.stderr)
                calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
                commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
                self.assertFalse(any(command[0] in ("up", "build", "exec", "cp", "create", "run", "stop")
                                     for command in commands))

    def test_fresh_install_fails_before_mutation_when_resource_listing_fails(self):
        for scenario in ({"failing_ps": True}, {"failing_volume_ls": True}):
            with self.subTest(scenario=scenario):
                calls_file = self.directory / "calls"
                calls_file.write_text("")
                result = subprocess.run([str(PROFILE / "production.sh"), "install-fresh"],
                                        env=self.docker_stub(**scenario), capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
                commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
                self.assertFalse(any(command[0] in ("up", "build", "exec", "cp", "create", "run", "stop")
                                     for command in commands))

    def test_fresh_install_accepts_unrelated_volumes_and_uses_existing_install_chain(self):
        environment = self.docker_stub(volume_names=["other-project_identity-data",
                                                     "tover-production_inventory-data-copy",
                                                     "tover-production2_inventory-data"])
        result = subprocess.run([str(PROFILE / "production.sh"), "install-fresh"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls_file = self.directory / "calls"
        calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
        fresh_commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertEqual(fresh_commands[:2], [["config", "--quiet"], ["ps", "--all", "--quiet"]])
        self.assertIn(["volume", "ls", "--format", "{{.Name}}"], calls)
        self.assertEqual(len([command for command in fresh_commands if "psql" in command]), 2)
        calls_file.write_text("")
        result = subprocess.run([str(PROFILE / "production.sh"), "install"],
                                env=environment, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
        install_commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
        self.assertEqual(fresh_commands[2:], install_commands[1:])
        self.assertEqual(fresh_commands[-1], ["up", "-d", "--wait", "--wait-timeout", "240", "app", "proxy"])

    def test_restore_refuses_existing_target_without_copy(self):
        backup = self.restore_fixture()
        cases = [{"existing_container": True}] + [
            {"volume_names": ["unused-project_" + volume]}
            for volume in ("identity-data", "inventory-data", "tls-data", "tls-config")]
        for scenario in cases:
            with self.subTest(scenario=scenario):
                calls_file = self.directory / "calls"
                calls_file.write_text("")
                result = subprocess.run([str(PROFILE / "production.sh"), "restore-new", str(backup), "unused-project"],
                                        env=self.docker_stub(**scenario), capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(b"already", result.stderr)
                calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
                commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
                self.assertFalse(any(command[0] in ("create", "cp", "run", "up", "exec") for command in commands))

    def test_restore_refuses_failed_resource_listing_without_touching_data(self):
        backup = self.restore_fixture()
        for scenario in ({"failing_volume_ls": True}, {"failing_ps": True}):
            with self.subTest(scenario=scenario):
                calls_file = self.directory / "calls"
                calls_file.write_text("")
                result = subprocess.run([str(PROFILE / "production.sh"), "restore-new", str(backup), "unused-project"],
                                        env=self.docker_stub(**scenario), capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                calls = [json.loads(line) for line in calls_file.read_text().splitlines()]
                commands = [call[call.index("-f") + 2:] for call in calls if "-f" in call]
                self.assertFalse(any(command[0] in ("create", "cp", "run", "up", "exec") for command in commands))
                if scenario.get("failing_ps"):
                    self.assertIn(["ps", "--all", "--quiet"], commands)
                else:
                    self.assertIn(["volume", "ls", "--format", "{{.Name}}"], calls)

    def test_source_checkout_refuses_bundle_backup_without_original_bundle(self):
        backup = self.restore_fixture()
        (backup / "release.json").write_text('{"format": 1}\n')
        result = subprocess.run([str(PROFILE / "production.sh"), "restore-new", str(backup), "unused-project"],
                                env=self.docker_stub(), capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"original release bundle", result.stderr)
        self.assertFalse((self.directory / "calls").exists())

    def test_compose_publishes_only_proxy_and_excludes_development(self):
        # Compose itself parses and normalizes YAML; no Docker daemon is required.
        result = subprocess.run(["docker", "compose", "--project-directory", str(PROFILE),
                                 "--env-file", str(self.environment), "-f", str(PROFILE / "compose.yaml"),
                                 "config", "--format", "json"], capture_output=True, check=True)
        configuration = json.loads(result.stdout)
        self.assertEqual({name for name, service in configuration["services"].items() if service.get("ports")},
                         {"proxy"})
        self.assertTrue(configuration["networks"]["identity"]["internal"])
        self.assertEqual(configuration["services"]["app"]["environment"]["TOVER_INVENTORY_PATH"],
                         "/var/lib/tover/inventory")
        self.assertEqual(configuration["services"]["app"]["tmpfs"], ["/tmp:rw,nosuid,size=128m"])
        for name, service in configuration["services"].items():
            self.assertIn(":", service["image"])
            self.assertNotIn(":latest", service["image"])
            self.assertNotIn("mailpit", service["image"])
        dockerfile = (ROOT / "Dockerfile").read_text()
        self.assertNotIn("COPY . ", dockerfile)
        self.assertNotIn("COPY dev", dockerfile)
        self.assertNotIn("COPY test", dockerfile)
        self.assertIn("USER 10001:10001", dockerfile)

    def test_rehearsal_has_only_loopback_ingress_and_no_tls_issuer(self):
        result = subprocess.run(["docker", "compose", "--project-directory", str(PROFILE),
                                 "--env-file", str(self.environment), "-f", str(PROFILE / "compose.yaml"),
                                 "-f", str(PROFILE / "rehearsal.compose.yaml"),
                                 "config", "--format", "json"], capture_output=True, check=True)
        configuration = json.loads(result.stdout)
        for service in configuration["services"].values():
            for port in service.get("ports", []):
                self.assertEqual(port["host_ip"], "127.0.0.1")
        self.assertFalse(configuration["services"]["proxy"].get("ports"))
        auth = configuration["services"]["auth"]["environment"]
        self.assertEqual(auth["GOTRUE_SMTP_HOST"], "mail")
        self.assertEqual(auth["GOTRUE_SMTP_PASS"], "")
        self.assertNotIn("tls", (PROFILE / "rehearsal.Caddyfile").read_text())

    def test_compose_bounds_app_memory_and_all_service_logs(self):
        result = subprocess.run(["docker", "compose", "--project-directory", str(PROFILE),
                                 "--env-file", str(self.environment), "-f", str(PROFILE / "compose.yaml"),
                                 "config", "--format", "json"], capture_output=True, check=True)
        services = json.loads(result.stdout)["services"]
        app = services["app"]
        self.assertEqual(int(app["mem_limit"]), 4 * 1024 ** 3)
        java_options = app["environment"]["JAVA_TOOL_OPTIONS"].split()
        self.assertIn("-Xmx2g", java_options)
        self.assertIn("-XX:+ExitOnOutOfMemoryError", java_options)
        self.assertFalse(any("RAMPercentage" in option for option in java_options))
        for name, service in services.items():
            with self.subTest(service=name):
                self.assertEqual(service["logging"],
                                 {"driver": "json-file", "options": {"max-size": "10m", "max-file": "3"}})


if __name__ == "__main__":
    unittest.main()
