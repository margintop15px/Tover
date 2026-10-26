#!/usr/bin/env python3
"""Capture matching closed stores, optionally followed by a Selectel VM backup."""
from datetime import datetime, timezone
import fcntl
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys

PROFILE = Path(__file__).resolve().parent


def main():
    os.umask(0o077)
    mode = os.environ.get("TOVER_BACKUP_MODE", "selectel")
    if mode not in {"local", "selectel"}:
        raise ValueError("TOVER_BACKUP_MODE must be local or selectel")
    profile = Path(os.environ.get("TOVER_BACKUP_PROFILE", str(PROFILE)))
    if not profile.is_absolute() or not (profile / "production.sh").is_file():
        raise ValueError("Use an absolute backup profile containing production.sh")
    server = os.environ.get("SELECTEL_SERVER_ID", "")
    token = os.environ.get("SELECTEL_TOKEN_FILE", "")
    if not re.fullmatch(r"[1-9][0-9]*", server):
        raise ValueError("Configure SELECTEL_SERVER_ID for backup naming")
    if mode == "selectel" and not Path(token).is_absolute():
        raise ValueError("Configure an absolute SELECTEL_TOKEN_FILE")
    root = Path(os.environ.get("TOVER_BACKUP_ROOT", "/srv/tover-backups"))
    keep = int(os.environ.get("TOVER_BACKUP_KEEP_LOCAL", "2"))
    if not root.is_absolute() or root.is_symlink() or keep < 2:
        raise ValueError("Use an absolute private backup directory and keep at least two local backups")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = root.stat()
    if stat.S_IMODE(info.st_mode) & 0o077 or info.st_uid != os.geteuid():
        raise ValueError("Backup directory must be owned by the current user with mode 0700")
    client = [sys.executable, str(profile / "selectel-backup.py"),
              "--server-id", server, "--token-file", token, "--keep", "7"]
    # Validate credentials before the local helper interrupts service.
    if mode == "selectel":
        subprocess.run([*client, "--check"], check=True)
    with (root / ".lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("Another scheduled backup is still running") from None
        name = f"tover-{server}-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        destination = root / name
        subprocess.run([str(profile / "production.sh"), "backup", str(destination)], check=True)
        if not (destination / "COMPLETE").is_file():
            raise ValueError("Local backup is incomplete; no provider backup was requested")
        if mode == "selectel":
            subprocess.run([*client, "--name", name], check=True)
        # Failed provider jobs retain every local recovery point for investigation.
        pattern = re.compile(r"tover-" + server + r"-[0-9]{8}T[0-9]{6}Z")
        completed = sorted(path for path in root.iterdir()
                           if pattern.fullmatch(path.name) and not path.is_symlink()
                           and path.is_dir() and (path / "COMPLETE").is_file())
        for path in completed[:-keep]:
            shutil.rmtree(path)
        if mode == "selectel":
            print("Complete local and Selectel backups verified; services restarted.", flush=True)
        else:
            print("Complete local app-data backup saved; services restarted. No off-server copy was made.",
                  flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Backup failed: {error}", file=sys.stderr)
        sys.exit(1)
