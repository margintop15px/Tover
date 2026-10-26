import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("production_release", ROOT / "deployment/production/release.py")
RELEASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RELEASE)


class FakeDocker:
    def __init__(self):
        self.calls = []
        self.images = {}
        self.saved = {}
        self.context = {}
        self.fail_build = False
        self.drivers = [["driver-type", "io.containerd.snapshotter.v1"]]
        self.model = {
            "name": "fixed-wrong-name", "services": {},
            "networks": {"identity": {"internal": True, "name": "fixed_identity"}},
            "volumes": {"identity-data": {"name": "fixed_volume"}}}
        for index, service in enumerate(sorted(RELEASE.SERVICES), 1):
            reference = f"vendor/{service}:1@sha256:" + str(index) * 64
            self.model["services"][service] = {"image": reference}
            self.images[reference] = {"Id": "sha256:" + str(index) * 64,
                                      "Os": "linux", "Architecture": "amd64"}
        self.model["services"]["app"].update(image="${TOVER_APP_IMAGE:?required}", build={"context": "../.."})
        self.model["services"]["proxy"]["volumes"] = [
            {"type": "bind", "source": "./Caddyfile", "target": "/etc/caddy/Caddyfile"},
            {"type": "bind", "source": "./templates", "target": "/srv/templates"}]

    def __call__(self, arguments, *, capture=False):
        self.calls.append(arguments)
        if arguments[0] == "git":
            return "a" * 40 + "\n" if arguments[-2:] == ["rev-parse", "HEAD"] else " M src/core.clj\n"
        if arguments[:2] == ["docker", "info"]:
            return json.dumps(self.drivers)
        if arguments[:2] == ["docker", "compose"]:
            self.assert_compose_private(arguments)
            return json.dumps(copy.deepcopy(self.model))
        if arguments[:3] == ["docker", "buildx", "build"]:
            context = Path(arguments[-1])
            self.context = {str(path.relative_to(context)): path.read_bytes()
                            for path in context.rglob("*") if path.is_file()}
            if self.fail_build:
                raise subprocess.CalledProcessError(1, arguments)
            self.images[arguments[arguments.index("--tag") + 1]] = {
                "Id": "sha256:" + "a" * 64, "Os": "linux", "Architecture": "amd64"}
            return None
        if arguments[:2] == ["docker", "pull"]:
            return None
        if arguments[:3] == ["docker", "image", "inspect"]:
            if arguments[-1] not in self.images:
                raise subprocess.CalledProcessError(1, arguments)
            return json.dumps(self.images[arguments[-1]])
        if arguments[:3] == ["docker", "image", "tag"]:
            details = self.images[arguments[-2]]
            self.images[arguments[-1]] = copy.deepcopy(details)
            return None
        if arguments[:3] == ["docker", "image", "save"]:
            index = arguments.index("--output")
            Path(arguments[index + 1]).write_bytes(b"fake-image-archive")
            self.saved = {name: copy.deepcopy(self.images[name]) for name in arguments[index + 2:]}
            return None
        if arguments[:3] == ["docker", "image", "load"]:
            self.images.update(copy.deepcopy(self.saved))
            return None
        raise AssertionError(arguments)

    @staticmethod
    def assert_compose_private(arguments):
        assert arguments[arguments.index("--env-file") + 1] == os.devnull
        assert "--no-interpolate" in arguments and "--no-path-resolution" in arguments


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.source = self.directory / "source"
        self.source.mkdir()
        for name in RELEASE.PUBLIC_FILES:
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((ROOT / name).read_bytes())
        for name in RELEASE.BUILD_FILES:
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("public-build-input\n")
        for name in ("src/core.clj", "resources/public/app.js", "resources/inventory/ontology.ttl"):
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("public-source\n")
        catalog = self.source / "resources/i18n/messages.json"
        catalog.parent.mkdir(parents=True)
        catalog.write_text('{"en":{"hello":"Hello"}}\n')
        (self.source / RELEASE.PROFILE / "compose.yaml").write_text("public compose input\n")
        for name in (".env", ".git/config", ".data/store", "captures/private.json",
                     "deployment/production/.env", "src/.env", "resources/public/.env"):
            target = self.source / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("SENSITIVE_TOKEN_NEVER_COPY\n")
        self.output = self.directory / "release"
        self.docker = FakeDocker()
        self.runner = patch.object(RELEASE, "run", self.docker)
        self.runner.start()

    def tearDown(self):
        self.runner.stop()
        self.temp.cleanup()

    def package(self):
        return RELEASE.package(self.output, "tover:tester-unit-unique", source=self.source)

    def rehash(self):
        (self.output / "SHA256SUMS").write_text("".join(
            f"{RELEASE.digest(self.output / name)}  {name}\n" for name in sorted(RELEASE.BUNDLE_FILES)))

    def test_package_is_public_portable_and_contains_exactly_five_images(self):
        manifest = self.package()
        all_files = {str(path.relative_to(self.output)) for path in self.output.rglob("*") if path.is_file()}
        self.assertEqual(all_files, RELEASE.BUNDLE_FILES | {"SHA256SUMS"})
        for path in self.output.rglob("*"):
            if path.is_file():
                self.assertNotIn(b"SENSITIVE_TOKEN_NEVER_COPY", path.read_bytes())
        self.assertTrue(self.docker.context)
        self.assertIn("resources/i18n/messages.json", self.docker.context)
        self.assertFalse(any(".env" in name or name.startswith(".git/") for name in self.docker.context))
        self.assertFalse(any(b"SENSITIVE_TOKEN_NEVER_COPY" in value for value in self.docker.context.values()))
        self.assertEqual(set(manifest["images"]), RELEASE.SERVICES)
        self.assertEqual(len(self.docker.saved), 5)
        self.assertTrue(manifest["source"]["dirty"])
        self.assertIn(" dirty\nbuild-sha256 ", (self.output / "source-revision").read_text())
        model = json.loads((self.output / RELEASE.PROFILE / "compose.yaml").read_text())
        self.assertEqual(model["name"], "${TOVER_DEPLOYMENT_NAME:-tover-production}")
        self.assertNotIn("name", model["networks"]["identity"])
        self.assertNotIn("name", model["volumes"]["identity-data"])
        self.assertEqual(model["services"]["proxy"]["volumes"][0]["source"], "./Caddyfile")
        for name, service in model["services"].items():
            self.assertNotIn("build", service)
            self.assertEqual(service["pull_policy"], "never")
            self.assertEqual(service["platform"], "linux/amd64")
            self.assertEqual(service["image"], manifest["images"][name]["reference"])
        for command in self.docker.calls:
            if command[0] == "docker" and command[1] not in ("compose", "info"):
                if command[1:3] != ["image", "tag"] and "--platform" not in command:
                    self.assertEqual(command[:3], ["docker", "image", "inspect"])
                else:
                    if command[1:3] != ["image", "tag"]:
                        self.assertIn("--platform", command)

    def test_check_and_load_run_outside_git_without_network(self):
        manifest = self.package()
        self.docker.images.clear()
        self.docker.calls.clear()
        current = Path.cwd()
        try:
            os.chdir(self.directory)
            with self.assertRaises(subprocess.CalledProcessError):
                RELEASE.check(self.output)
            self.docker.calls.clear()
            self.assertEqual(RELEASE.load(self.output), manifest)
        finally:
            os.chdir(current)
        self.assertEqual(self.docker.calls[1][:3], ["docker", "image", "load"])
        self.assertTrue(all(command[:2] == ["docker", "info"]
                            or command[:3] in (["docker", "image", "load"], ["docker", "image", "inspect"])
                            for command in self.docker.calls))

    def test_checksums_and_load_work_without_python311_file_digest(self):
        contents = b"multi-chunk checksum\x00" * 170000
        path = self.directory / "large-file"
        path.write_bytes(contents)
        expected = RELEASE.hashlib.sha256(contents).hexdigest()
        with patch.dict(RELEASE.hashlib.__dict__):
            RELEASE.hashlib.__dict__.pop("file_digest", None)
            self.assertEqual(RELEASE.digest(path), expected)
            manifest = self.package()
            self.docker.images.clear()
            self.assertEqual(RELEASE.load(self.output), manifest)

    def test_classic_image_store_is_rejected_before_build_load_or_image_checks(self):
        self.package()
        self.docker.drivers = [["Backing Filesystem", "extfs"]]
        self.docker.calls.clear()
        for action in (lambda: RELEASE.load(self.output), lambda: RELEASE.check(self.output),
                       lambda: RELEASE.package(self.directory / "second", "tover:tester-second", source=self.source)):
            with self.assertRaisesRegex(ValueError, "containerd image store"):
                action()
        self.assertTrue(all(command[:2] == ["docker", "info"] for command in self.docker.calls))
        self.assertFalse((self.directory / "second").exists())

    def test_retagged_wrong_platform_and_missing_images_are_rejected(self):
        manifest = self.package()
        app = manifest["images"]["app"]["reference"]
        original = copy.deepcopy(self.docker.images[app])
        for values in ({"Id": "sha256:" + "f" * 64}, {"Architecture": "arm64"}, {"Os": "windows"}):
            self.docker.images[app] = {**original, **values}
            with self.assertRaises(ValueError):
                RELEASE.check(self.output)
        self.docker.images.pop(app)
        with self.assertRaises(subprocess.CalledProcessError):
            RELEASE.check(self.output)

    def test_archive_or_runtime_checksum_failure_prevents_docker_load(self):
        self.package()
        for name in ("images.tar", "deployment/production/production.sh", "release.json"):
            with self.subTest(name=name):
                path = self.output / name
                original = path.read_bytes()
                path.write_bytes(original + b"corruption")
                self.docker.calls.clear()
                with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                    RELEASE.load(self.output)
                self.assertEqual(self.docker.calls, [])
                path.write_bytes(original)

    def test_manifest_and_compose_cannot_enable_pulls_or_builds(self):
        self.package()
        compose = self.output / RELEASE.PROFILE / "compose.yaml"
        original = compose.read_text()
        for changes in ({"pull_policy": "always"}, {"build": {"context": "../.."}}, {"image": "foreign:latest"}):
            model = json.loads(original)
            model["services"]["app"].update(changes)
            compose.write_text(json.dumps(model))
            self.rehash()
            with self.assertRaises(ValueError):
                RELEASE.check(self.output)

    def test_private_environment_must_match_release_and_respects_process_override(self):
        self.package()
        private = self.directory / "app.env"
        private.write_text("SMTP_PASS=never-print-this\nTOVER_APP_IMAGE='tover:tester-unit-unique'\n")
        with patch.dict(os.environ, {}, clear=True):
            RELEASE.check(self.output, private)
            with patch.dict(os.environ, {"TOVER_APP_IMAGE": "tover:wrong"}):
                with self.assertRaisesRegex(ValueError, "TOVER_APP_IMAGE"):
                    RELEASE.check(self.output, private)

    def test_existing_or_checkout_output_is_preserved_and_invalid_tags_do_not_build(self):
        self.output.mkdir()
        marker = self.output / "keep"
        marker.write_text("keep")
        with self.assertRaises(FileExistsError):
            self.package()
        self.assertEqual(marker.read_text(), "keep")
        for output in (self.source / "bundle", Path("relative")):
            with self.assertRaises(ValueError):
                RELEASE.package(output, "tover:tester-unit", source=self.source)
        for image in ("tover:latest", "tover", "bad image:tag", "-argument:tag"):
            with self.assertRaises(ValueError):
                RELEASE.package(self.directory / "other", image, source=self.source)
        self.assertFalse(any(command[:3] == ["docker", "buildx", "build"] for command in self.docker.calls))

    def test_existing_app_image_tag_is_preserved(self):
        image = {"Id": "sha256:" + "b" * 64, "Os": "linux", "Architecture": "arm64"}
        self.docker.images["tover:tester-unit-unique"] = copy.deepcopy(image)
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.package()
        self.assertEqual(self.docker.images["tover:tester-unit-unique"], image)
        self.assertFalse(self.output.exists())

    def test_failed_build_leaves_no_loadable_bundle(self):
        self.docker.fail_build = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.package()
        self.assertFalse(self.output.exists())
        self.docker.calls.clear()
        with self.assertRaises(ValueError):
            RELEASE.load(self.output)
        self.assertEqual(self.docker.calls, [])

    def test_incomplete_or_unsafe_bundle_is_rejected_before_load(self):
        self.package()
        (self.output / "READY").unlink()
        self.docker.calls.clear()
        with self.assertRaises(ValueError):
            RELEASE.load(self.output)
        self.assertEqual(self.docker.calls, [])
        (self.output / "READY").symlink_to(self.source / "deps.edn")
        with self.assertRaises(ValueError):
            RELEASE.load(self.output)
        sums = self.output / "SHA256SUMS"
        sums.write_text(sums.read_text() + "a" * 64 + "  ../outside\n")
        with self.assertRaises(ValueError):
            RELEASE.load(self.output)

    def test_build_symlink_is_rejected_and_private_changes_do_not_change_build_hash(self):
        first = self.package()["source"]["build_sha256"]
        (self.source / ".env").write_text("different-private-secret")
        second = RELEASE.package(self.directory / "second", "tover:tester-second", source=self.source)
        self.assertEqual(first, second["source"]["build_sha256"])
        (self.source / "src/secret.clj").symlink_to(self.source / ".env")
        with self.assertRaisesRegex(ValueError, "symlinks"):
            RELEASE.package(self.directory / "third", "tover:tester-third", source=self.source)
        self.assertFalse((self.directory / "third").exists())

    def test_public_message_catalog_changes_are_included_in_build_identity(self):
        first = self.package()["source"]["build_sha256"]
        (self.source / "resources/i18n/messages.json").write_text('{"en":{"hello":"Updated"}}\n')
        second = RELEASE.package(self.directory / "second", "tover:tester-second", source=self.source)
        self.assertNotEqual(first, second["source"]["build_sha256"])


if __name__ == "__main__":
    unittest.main()
