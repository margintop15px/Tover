#!/usr/bin/env python3
"""Build a public linux/amd64 release bundle; load and verify it without registry access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PROFILE = Path("deployment/production")
PLATFORM = "linux/amd64"
SERVICES = {"app", "db", "auth", "rest", "proxy"}
RUNTIME_FILES = (
    "production.sh", "config.py", "selectel-backup.py", "scheduled-backup.py", "Caddyfile",
    ".env.example", "backup.env.example", "release.py", "systemd/tover-backup.service",
    "systemd/tover-backup.timer", "templates/confirmation.html", "templates/invite.html",
    "templates/recovery.html", "templates/magic-link.html")
PUBLIC_FILES = tuple(str(PROFILE / name) for name in RUNTIME_FILES) + (
    "supabase/migrations/033_identity_and_sessions.sql",
    "supabase/migrations/034_workspace_actor_labels.sql",
    "docs/selectel-deployment.md", "docs/production-deployment.md")
BUILD_FILES = ("Dockerfile", ".dockerignore", "deps.edn",
               "deployment/production/build-classpath.sh", "deployment/production/Healthcheck.java")
BUILD_TREES = ("src", "resources/public", "resources/inventory", "resources/i18n")
BUNDLE_FILES = set(PUBLIC_FILES) | {str(PROFILE / "compose.yaml"), "source-revision", "release.json",
                                  "images.tar", "READY"}


def run(arguments, *, capture=False):
    result = subprocess.run(arguments, check=True, text=True, capture_output=capture)
    return result.stdout if capture else None


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def regular_file(root, name):
    relative = Path(name)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Release contains an unsafe file path")
    path = root / relative
    if any((root / Path(*relative.parts[:index])).is_symlink()
           for index in range(1, len(relative.parts) + 1)) or not path.is_file():
        raise ValueError(f"Release requires a regular file: {name}")
    return path


def copy_file(source, destination, name):
    original = regular_file(source, name)
    target = destination / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(original, target)


def snapshot(source, destination):
    names = list(BUILD_FILES)
    for tree in BUILD_TREES:
        for path in sorted((source / tree).rglob("*")):
            relative = path.relative_to(source)
            if any(part.startswith(".") for part in relative.parts):
                continue
            if path.is_symlink():
                raise ValueError("Build inputs must not contain symlinks")
            if path.is_file():
                names.append(str(relative))
    checksum = hashlib.sha256()
    for name in sorted(names):
        copy_file(source, destination, name)
        checksum.update(name.encode() + b"\0" + bytes.fromhex(digest(destination / name)))
    return checksum.hexdigest()


def inspect_image(reference):
    image = json.loads(run(["docker", "image", "inspect", "--platform", PLATFORM,
                            "--format", "{{json .}}", reference], capture=True))
    if (not isinstance(image, dict) or image.get("Os") != "linux"
            or image.get("Architecture") != "amd64"
            or not re.fullmatch(r"sha256:[0-9a-f]{64}", image.get("Id", ""))):
        raise ValueError("Release image must be linux/amd64 with a valid image ID")
    return {"id": image["Id"], "os": image["Os"], "architecture": image["Architecture"]}


def require_containerd():
    drivers = json.loads(run(["docker", "info", "--format", "{{json .DriverStatus}}"], capture=True))
    if not isinstance(drivers, list) or ["driver-type", "io.containerd.snapshotter.v1"] not in drivers:
        raise ValueError("Release bundles require Docker 29+ with the containerd image store; daemon settings were not changed")


def image_tag(value):
    return (isinstance(value, str)
            and re.fullmatch(r"[a-z0-9]+(?:[._/-][a-z0-9]+)*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", value)
            and value.rsplit(":", 1)[1] != "latest")


def compose_model(source):
    model = json.loads(run(["docker", "compose", "--env-file", os.devnull,
                            "-f", str(source / PROFILE / "compose.yaml"), "config", "--no-interpolate",
                            "--no-path-resolution", "--format", "json"], capture=True))
    if set(model.get("services", {})) != SERVICES:
        raise ValueError("Release requires exactly the five production services")
    model["name"] = "${TOVER_DEPLOYMENT_NAME:-tover-production}"
    for section in ("networks", "volumes"):
        for value in model.get(section, {}).values():
            value.pop("name", None)
    return model


def package(output, image, *, source=ROOT):
    source = Path(source).resolve()
    output = Path(output)
    if not output.is_absolute() or source == output.resolve() or source in output.resolve().parents:
        raise ValueError("Use a new absolute output directory outside the checkout")
    if not image_tag(image):
        raise ValueError("Use a local release-specific application image tag; latest is forbidden")
    if output.exists():
        raise FileExistsError("Release destination already exists")
    require_containerd()
    try:
        run(["docker", "image", "inspect", "--format", "{{json .}}", image], capture=True)
    except subprocess.CalledProcessError:
        pass
    else:
        raise ValueError("Application image tag already exists; choose a new release tag")
    output.mkdir(mode=0o700)
    try:
        for name in PUBLIC_FILES:
            copy_file(source, output, name)
        revision = run(["git", "-C", str(source), "rev-parse", "HEAD"], capture=True).strip()
        dirty = bool(run(["git", "-C", str(source), "status", "--porcelain"], capture=True).strip())
        if not re.fullmatch(r"[0-9a-f]{40,64}", revision):
            raise ValueError("Cannot record the source revision")
        with tempfile.TemporaryDirectory(prefix="tover-build-") as directory:
            context = Path(directory)
            build_hash = snapshot(source, context)
            # Read Compose from a public-only snapshot, never the checkout's private .env.
            copy_file(source, context, str(PROFILE / "compose.yaml"))
            model = compose_model(context)
            run(["docker", "buildx", "build", "--platform", PLATFORM, "--load", "--tag", image, str(context)])
        images = {}
        for service in sorted(SERVICES):
            original = image if service == "app" else model["services"][service]["image"]
            if service != "app":
                if not isinstance(original, str) or not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", original):
                    raise ValueError("Dependency images must use the reviewed immutable digest pins")
                run(["docker", "pull", "--platform", PLATFORM, original])
            details = inspect_image(original)
            reference = image if service == "app" else f"tover-bundle/{service}:{details['id'][7:]}"
            if reference != original:
                # Containerd image stores may expose platform IDs that cannot be tagged directly.
                run(["docker", "image", "tag", original, reference])
                if inspect_image(reference) != details:
                    raise ValueError("Local dependency tag resolved to a different platform image")
            images[service] = {**details, "reference": reference, "source_reference": original}
            model["services"][service].pop("build", None)
            model["services"][service].update(image=reference, pull_policy="never", platform=PLATFORM)
        (output / PROFILE / "compose.yaml").write_text(json.dumps(model, indent=2) + "\n")
        example = output / PROFILE / ".env.example"
        example.write_text(re.sub(r"(?m)^TOVER_APP_IMAGE=.*$", "TOVER_APP_IMAGE=" + image, example.read_text()))
        run(["docker", "image", "save", "--platform", PLATFORM, "--output", str(output / "images.tar"),
             *[images[service]["reference"] for service in sorted(SERVICES)]])
        manifest = {"format": 1, "platform": PLATFORM,
                    "source": {"revision": revision, "dirty": dirty, "build_sha256": build_hash}, "images": images}
        (output / "release.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (output / "source-revision").write_text(revision + (" dirty" if dirty else "") + f"\nbuild-sha256 {build_hash}\n")
        (output / "READY").write_text("tover-release-v1\n")
        (output / "SHA256SUMS").write_text("".join(f"{digest(regular_file(output, name))}  {name}\n"
                                                  for name in sorted(BUNDLE_FILES)))
        check(output)
        return manifest
    except BaseException:
        shutil.rmtree(output)
        raise


def verify_files(root, *, archive=False):
    sums = {}
    for line in regular_file(root, "SHA256SUMS").read_text().splitlines():
        checksum, separator, name = line.partition("  ")
        if not separator or not re.fullmatch(r"[0-9a-f]{64}", checksum) or name in sums:
            raise ValueError("Release checksum manifest is invalid")
        sums[name] = checksum
    if set(sums) != BUNDLE_FILES:
        raise ValueError("Release checksum manifest must cover every bundled file")
    for name, checksum in sums.items():
        path = regular_file(root, name)
        if (archive or name != "images.tar") and digest(path) != checksum:
            raise ValueError(f"Release checksum mismatch: {name}")
    if (root / "READY").read_text() != "tover-release-v1\n":
        raise ValueError("Release bundle is incomplete")


def check(root, env_file=None):
    root = Path(root).resolve()
    verify_files(root)
    require_containerd()
    manifest = json.loads((root / "release.json").read_text())
    if manifest.get("format") != 1 or manifest.get("platform") != PLATFORM or set(manifest.get("images", {})) != SERVICES:
        raise ValueError("Unsupported release manifest")
    source = manifest.get("source", {})
    if (not re.fullmatch(r"[0-9a-f]{40,64}", source.get("revision", ""))
            or type(source.get("dirty")) is not bool
            or not re.fullmatch(r"[0-9a-f]{64}", source.get("build_sha256", ""))):
        raise ValueError("Release source identity is invalid")
    model = json.loads((root / PROFILE / "compose.yaml").read_text())
    if set(model.get("services", {})) != SERVICES:
        raise ValueError("Release requires exactly the five production services")
    for service, expected in manifest["images"].items():
        reference = expected.get("reference")
        if (not image_tag(reference) or not re.fullmatch(r"sha256:[0-9a-f]{64}", expected.get("id", ""))
                or expected.get("os") != "linux" or expected.get("architecture") != "amd64"):
            raise ValueError("Release image manifest is invalid")
        if service != "app" and reference != f"tover-bundle/{service}:{expected['id'][7:]}":
            raise ValueError("Release dependency must use its local image ID tag")
        configuration = model["services"][service]
        if (configuration.get("image") != reference or "build" in configuration
                or configuration.get("pull_policy") != "never" or configuration.get("platform") != PLATFORM):
            raise ValueError("Release Compose configuration permits an unexpected image, build or pull")
        actual = inspect_image(reference)
        if any(actual[key] != expected[key] for key in ("id", "os", "architecture")):
            raise ValueError(f"Loaded image differs from release: {service}")
    if env_file is not None:
        value = None
        for line in Path(env_file).read_text().splitlines():
            key, separator, raw = line.partition("=")
            if separator and key == "TOVER_APP_IMAGE":
                values = shlex.split(raw, comments=True)
                value = values[0] if len(values) == 1 else None
        value = os.environ.get("TOVER_APP_IMAGE", value)
        if value != manifest["images"]["app"]["reference"]:
            raise ValueError("TOVER_APP_IMAGE must match the offline release image")
    return manifest


def load(root):
    root = Path(root).resolve()
    verify_files(root, archive=True)
    require_containerd()
    run(["docker", "image", "load", "--platform", PLATFORM, "--input", str(root / "images.tar")])
    return check(root)


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("package", help="build and export a public release on a connected machine")
    create.add_argument("output")
    create.add_argument("--image", required=True)
    validate = commands.add_parser("check", help="verify release files and already loaded images without network")
    validate.add_argument("root")
    validate.add_argument("--env-file")
    restore = commands.add_parser("load", help="verify all checksums, load bundled images and check them")
    restore.add_argument("root")
    args = parser.parse_args(arguments)
    try:
        if args.command == "package":
            package(args.output, args.image)
        elif args.command == "load":
            load(args.root)
        else:
            check(args.root, args.env_file)
        print("Release " + args.command + " complete.")
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
