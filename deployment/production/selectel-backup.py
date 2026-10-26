#!/usr/bin/env python3
"""Back up legacy VDS by Selectel; VM snapshots alone do not establish DB consistency."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import urllib.error
import urllib.request
import uuid

API = "https://api.vscale.io/v1"
WAIT_SECONDS = 1800
REQUEST_SECONDS = 30
POLL_SECONDS = 10
PENDING = {"defined", "defining", "creating", "uploading"}


class BackupError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def request(method, path, token, payload=None, timeout=REQUEST_SECONDS):
    message = urllib.request.Request(
        API + path, method=method,
        headers={"X-Token": token, "Content-Type": "application/json"},
        data=json.dumps(payload).encode() if payload is not None else None)
    try:
        # Redirects must never forward the provider credential to another host.
        with urllib.request.build_opener(NoRedirect()).open(message, timeout=timeout) as response:
            if response.status not in (200, 204):
                raise BackupError(f"Provider returned HTTP {response.status}")
            body = response.read(1024 * 1024 + 1)
        if len(body) > 1024 * 1024:
            raise BackupError("Provider response exceeds the size limit")
        return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        raise BackupError(f"Provider returned HTTP {error.code}") from None
    except (urllib.error.URLError, OSError, TimeoutError):
        raise BackupError("Provider request failed; it was not retried") from None
    except (ValueError, UnicodeError):
        raise BackupError("Provider returned invalid JSON") from None


def read_token(path):
    if not Path(path).is_absolute():
        raise BackupError("Token file must use an absolute path")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as source:
            info = os.fstat(source.fileno())
            if (not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600
                    or info.st_uid != os.geteuid()):
                raise BackupError("Token file must be owned by the current user with mode 0600")
            raw = source.read(8193)
        if raw.endswith(b"\n"):
            raw = raw[:-1]
        if not raw or len(raw) > 8192 or any(byte < 33 or byte > 126 for byte in raw):
            raise BackupError("Token file must contain one nonempty ASCII token")
        return raw.decode("ascii")
    except OSError:
        raise BackupError("Cannot read the private token file") from None


def backup_id(value):
    if not isinstance(value, str):
        raise BackupError("Provider returned an invalid backup ID")
    try:
        identifier = str(uuid.UUID(value))
    except ValueError:
        raise BackupError("Provider returned an invalid backup ID") from None
    if identifier != value:
        raise BackupError("Provider returned an invalid backup ID")
    return identifier


def managed_time(name, server_id):
    if not isinstance(name, str) or not re.fullmatch(rf"tover-{server_id}-[0-9]{{8}}T[0-9]{{6}}Z", name):
        return None
    try:
        return datetime.strptime(name.rsplit("-", 1)[1], "%Y%m%dT%H%M%SZ")
    except ValueError:
        return None


def same_server(value, server_id):
    return type(value) is int and value == server_id


def finished(backup):
    return (backup.get("status") == "finished" and backup.get("active") is True
            and backup.get("locked") is False and backup.get("is_deleted") is False)


def managed_backup(backup, server_id):
    if not isinstance(backup, dict) or not same_server(backup.get("scalet"), server_id):
        return None
    timestamp = managed_time(backup.get("name"), server_id)
    if timestamp is None or not finished(backup):
        return None
    try:
        return backup_id(backup.get("id")), timestamp
    except BackupError:
        return None


def create_backup(token, server_id, name, keep=7, *, call=request, clock=time.monotonic,
                  sleep=time.sleep, announce=lambda identifier: print(identifier, flush=True)):
    if type(server_id) is not int or server_id <= 0:
        raise BackupError("Server ID must be a positive integer")
    if type(keep) is not int or keep < 2:
        raise BackupError("Keep at least two managed provider backups")
    if managed_time(name, server_id) is None:
        raise BackupError("Backup name must be tover-SERVERID-YYYYmmddTHHMMSSZ")
    deadline = clock() + WAIT_SECONDS
    created = call("POST", f"/scalets/{server_id}/backup", token, {"name": name},
                   timeout=REQUEST_SECONDS)
    if not isinstance(created, dict):
        raise BackupError("Provider returned an invalid backup response")
    identifier = backup_id(created.get("id"))
    announce(identifier)
    if not same_server(created.get("scalet"), server_id):
        raise BackupError("Provider returned a different backup server")
    while True:
        remaining = deadline - clock()
        if remaining <= 0:
            raise BackupError("Timed out waiting for provider backup; retention was skipped")
        backup = call("GET", f"/backups/{identifier}", token,
                      timeout=min(REQUEST_SECONDS, remaining))
        if (not isinstance(backup, dict) or backup.get("id") != identifier
                or not same_server(backup.get("scalet"), server_id) or backup.get("name") != name):
            raise BackupError("Provider returned an inconsistent backup response")
        if backup.get("status") == "finished":
            if not finished(backup):
                raise BackupError("Provider backup finished but is not available; retention was skipped")
            break
        if backup.get("status") not in PENDING:
            raise BackupError("Provider returned an unknown backup status; retention was skipped")
        sleep(min(POLL_SECONDS, max(0, deadline - clock())))

    backups = call("GET", "/backups", token)
    if not isinstance(backups, list):
        raise BackupError("Provider returned an invalid backup list")
    candidates = {}
    duplicates = set()
    for entry in backups:
        candidate = managed_backup(entry, server_id)
        if candidate:
            entry_id, timestamp = candidate
            if entry_id in candidates:
                duplicates.add(entry_id)
            candidates[entry_id] = (timestamp, entry["name"])
    candidates.pop(identifier, None)
    for duplicate in duplicates:
        candidates.pop(duplicate, None)
    ordered = sorted(candidates, key=lambda value: (candidates[value][0], value), reverse=True)
    for expired in ordered[keep - 1:]:
        current = call("GET", f"/backups/{expired}", token)
        guarded = managed_backup(current, server_id)
        if guarded == (expired, candidates[expired][0]) and current["name"] == candidates[expired][1]:
            call("DELETE", f"/backups/{expired}", token)
    return identifier


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-id", type=int, required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--name")
    parser.add_argument("--keep", type=int, default=7)
    parser.add_argument("--check", action="store_true", help="validate local configuration without network access")
    args = parser.parse_args(arguments)
    try:
        if args.server_id <= 0:
            raise BackupError("Server ID must be a positive integer")
        if args.keep < 2:
            raise BackupError("Keep at least two managed provider backups")
        if args.name is not None and managed_time(args.name, args.server_id) is None:
            raise BackupError("Backup name must be tover-SERVERID-YYYYmmddTHHMMSSZ")
        if not args.check and args.name is None:
            raise BackupError("A managed backup name is required")
        token = read_token(args.token_file)
        if not args.check:
            create_backup(token, args.server_id, args.name, args.keep)
        return 0
    except BackupError as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
