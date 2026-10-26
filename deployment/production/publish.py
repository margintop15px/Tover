#!/usr/bin/env python3
"""Commit all pending changes, tag/push, verify an offline bundle and publish a GitHub release."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[2]
PROFILE = Path("deployment/production")


def run(arguments, *, capture=False, check=True, env=None):
    return subprocess.run(arguments, check=check, text=True, capture_output=capture, env=env)


def guard_private_paths(root, names):
    private = {".git", ".data", ".aws", ".ssh", ".cpcache", ".migration-backups", ".pal",
               "node_modules", "__pycache__", "test-results", "playwright-report", "private", "backups", "logs"}
    for name in names:
        path = Path(name)
        if not (root / path).exists() and not (root / path).is_symlink():
            continue
        if (any(part in private for part in path.parts)
                or (path.name.startswith(".env") and path.name != ".env.example")
                or path.name in {".nrepl-port", "selectel.token", "credentials", "id_rsa", "id_ed25519"}
                or path.suffix.lower() in {".pem", ".key", ".p12", ".pfx"}):
            raise ValueError(f"Refusing to publish a private/generated path: {name}")


def publish(tag, message, output, *, root=ROOT):
    root = Path(root).resolve()
    output = Path(output)
    if (not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}", tag) or tag == "latest"
            or not message.strip()):
        raise ValueError("Supply a new Git/Docker-compatible release tag and a nonempty commit message")
    if (not output.is_absolute() or root == output.resolve() or root in output.resolve().parents
            or any(character in str(output) for character in "#\r\n")):
        raise ValueError("Use a new absolute output directory outside the checkout without # or newlines")
    archive = output.with_name(output.name + ".tar.gz")
    checksum_file = archive.with_name(archive.name + ".sha256")
    notes_file = output.with_name(output.name + ".notes.md")
    if any(path.exists() or path.is_symlink() for path in (output, archive, checksum_file, notes_file)):
        raise ValueError("Release output or adjacent archive/checksum/notes already exists")
    if not output.parent.is_dir():
        raise ValueError("Create the output parent directory first")
    for tool in ("git", "docker", "gh"):
        if not shutil.which(tool):
            raise ValueError(f"Install {tool} before publishing")

    def git(*arguments, capture=True, check=True):
        return run(["git", "-C", str(root), *arguments], capture=capture, check=check)

    def private_paths():
        names = git("ls-files", "--cached", "--others", "--exclude-standard", "-z").stdout.split("\0")
        guard_private_paths(root, [name for name in names if name])

    def clean_commit(commit):
        if git("rev-parse", "HEAD").stdout.strip() != commit or git("status", "--porcelain").stdout.strip():
            raise ValueError("The working tree or commit changed during publication; stop other writers and use a new tag")

    if Path(git("rev-parse", "--show-toplevel").stdout.strip()).resolve() != root:
        raise ValueError("Publish from the application repository")
    branch = git("symbolic-ref", "--quiet", "--short", "HEAD", check=False)
    if branch.returncode or not branch.stdout.strip():
        raise ValueError("Publishing requires a checked-out branch")
    branch = branch.stdout.strip()
    git("check-ref-format", "refs/tags/" + tag)
    local_tag = git("show-ref", "--verify", "--quiet", "refs/tags/" + tag, check=False)
    if local_tag.returncode == 0:
        raise ValueError("The release tag already exists locally")
    if local_tag.returncode != 1:
        raise ValueError("Cannot verify the local release tag")
    origin = git("remote", "get-url", "origin").stdout.strip()
    match = re.fullmatch(r"(?:git@github\.com:|https://github\.com/)([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?", origin)
    if not match:
        raise ValueError("Origin must identify a GitHub repository over SSH or HTTPS")
    repository = match[1]
    private_paths()
    git("diff", "--check", capture=False)
    git("diff", "--cached", "--check", capture=False)
    drivers = json.loads(run(["docker", "info", "--format", "{{json .DriverStatus}}"], capture=True).stdout)
    if ["driver-type", "io.containerd.snapshotter.v1"] not in (drivers or []):
        raise ValueError("Publishing requires Docker 29+ with the containerd image store")
    run(["docker", "compose", "version"], capture=True)
    run(["docker", "buildx", "version"], capture=True)
    existing_image = run(["docker", "image", "inspect", "tover:" + tag], capture=True, check=False)
    if existing_image.returncode == 0:
        raise ValueError("The application image tag already exists; choose a new release tag")
    if existing_image.returncode != 1:
        raise ValueError("Cannot verify that the application image tag is unused")
    run(["gh", "auth", "status", "--hostname", "github.com"], capture=True)
    if git("ls-remote", "--tags", "origin", "refs/tags/" + tag).stdout.strip():
        raise ValueError("The release tag already exists on origin")
    existing_release = run(["gh", "api", "--hostname", "github.com", "--include", f"repos/{repository}/releases/tags/{tag}"],
                           capture=True, check=False)
    if existing_release.returncode == 0:
        raise ValueError("A GitHub release already uses this tag")
    if not re.search(r"^HTTP/\S+ 404(?:\s|$)", existing_release.stdout, re.MULTILINE):
        raise ValueError("Cannot verify that the GitHub release tag is unused")

    git("add", "-A", capture=False)
    private_paths()
    git("diff", "--cached", "--check", capture=False)
    staged = git("diff", "--cached", "--quiet", check=False)
    if staged.returncode == 1:
        git("commit", "--message", message, capture=False)
    elif staged.returncode != 0:
        raise ValueError("Cannot inspect the staged changes")
    commit = git("rev-parse", "HEAD").stdout.strip()
    clean_commit(commit)
    git("tag", "--annotate", tag, "--message", message, commit)
    git("push", "--atomic", "--no-follow-tags", "origin", f"{commit}:refs/heads/{branch}",
        f"refs/tags/{tag}:refs/tags/{tag}", capture=False)
    clean_commit(commit)

    release = [sys.executable, str(root / PROFILE / "release.py")]
    run([*release, "package", str(output), "--image", "tover:" + tag])
    clean_commit(commit)
    manifest = json.loads((output / "release.json").read_text())
    if (manifest["source"]["dirty"] is not False or manifest["source"]["revision"] != commit
            or manifest["images"]["app"]["reference"] != "tover:" + tag):
        raise ValueError("The bundle does not identify the clean release commit and image")
    run([*release, "load", str(output)])
    environment = {**os.environ, "TOVER_REHEARSAL_RELEASE": str(output)}
    environment.pop("PYTHONOPTIMIZE", None)
    run([sys.executable, str(root / PROFILE / "rehearse.py")], env=environment)
    clean_commit(commit)
    with archive.open("xb") as raw:
        with tarfile.open(fileobj=raw, mode="w:gz") as bundle:
            bundle.add(output, arcname=".")
    checksum = hashlib.sha256()
    with archive.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(chunk)
    with checksum_file.open("x") as target:
        target.write(f"{checksum.hexdigest()}  {archive.name}\n")
    with notes_file.open("x") as notes:
        notes.write(f"{message}\n\nCommit: `{commit}`\n\n"
                    f"Offline Linux amd64 bundle: `{archive.name}`; application image: `tover:{tag}`.\n\n"
                    "Verified image checksums/identities and production-shaped fresh installation, signup, "
                    "closed-store backup and restore. Private rehearsal evidence stays on the build machine.\n\n"
                    "Public TLS, external SMTP and Selectel recovery still require host checks.\n")
    clean_commit(commit)
    prerelease = [] if re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag) else ["--prerelease"]
    published = run(["gh", "release", "create", tag, str(archive), str(checksum_file), str(output / "release.json"),
                     "--repo", "github.com/" + repository, "--verify-tag", *prerelease, "--generate-notes",
                     "--title", tag, "--notes-file", str(notes_file)], capture=True)
    return {"url": published.stdout.strip(), "commit": commit, "tag": tag, "bundle": str(output),
            "archive": str(archive), "checksum": str(checksum_file)}


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--message", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(arguments)
    try:
        print(json.dumps(publish(args.tag, args.message, args.output), indent=2))
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Publication stopped: {error}\nExisting commits, tags and artifacts were preserved.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
