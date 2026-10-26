#!/usr/bin/env python3
"""Private Compose configuration generation and validation; never print values."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import sys
import time
from urllib.parse import urlsplit


def read_env(path):
    result = {}
    for line in Path(path).read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, raw = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError("Use KEY=value lines in the private environment file")
        values = shlex.split(raw, comments=True)
        if len(values) > 1:
            raise ValueError(f"Quote spaces in {key}")
        result[key] = values[0] if values else ""
    return result


def generate(path, template):
    jwt_secret = secrets.token_urlsafe(48)

    def b64(value):
        return base64.urlsafe_b64encode(value).decode().rstrip("=")

    def jwt(role):
        header = b64(b'{"alg":"HS256","typ":"JWT"}')
        payload = b64(json.dumps({"role": role, "iss": "supabase", "iat": int(time.time()),
                                  "exp": int(time.time()) + 315360000}, separators=(",", ":")).encode())
        body = f"{header}.{payload}"
        return body + "." + b64(hmac.new(jwt_secret.encode(), body.encode(), hashlib.sha256).digest())

    generated = {"POSTGRES_PASSWORD": secrets.token_urlsafe(32),
                 "TOVER_DB_PASSWORD": secrets.token_urlsafe(32), "JWT_SECRET": jwt_secret,
                 "ANON_KEY": jwt("anon"), "SERVICE_ROLE_KEY": jwt("service_role"),
                 "SESSION_ENCRYPTION_KEY": base64.b64encode(secrets.token_bytes(32)).decode()}
    contents = Path(template).read_text()
    for key, value in generated.items():
        contents = contents.replace(f"{key}=GENERATE", f"{key}={value}")
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "w") as output:
        output.write(contents)


def validate(path):
    if Path(path).stat().st_mode & 0o077:
        raise ValueError("Private environment permissions must be 0600")
    values = read_env(path)
    values.update({key: os.environ[key] for key in values if key in os.environ})
    required = ("TOVER_APP_IMAGE", "TOVER_DOMAIN", "TOVER_ORIGIN", "ACME_EMAIL", "SMTP_HOST",
                "SMTP_PORT", "SMTP_USER", "SMTP_PASS", "SMTP_ADMIN_EMAIL", "POSTGRES_PASSWORD",
                "TOVER_DB_PASSWORD", "JWT_SECRET", "ANON_KEY", "SERVICE_ROLE_KEY", "SESSION_ENCRYPTION_KEY")
    missing = [key for key in required if not values.get(key) or values[key] == "GENERATE"]
    if missing:
        raise ValueError("Configure: " + ", ".join(missing))
    domain = values["TOVER_DOMAIN"]
    if not re.fullmatch(r"[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?", domain) or "." not in domain:
        raise ValueError("TOVER_DOMAIN must be a DNS hostname")
    origin = urlsplit(values["TOVER_ORIGIN"])
    if origin.scheme != "https" or origin.netloc != domain or origin.path or origin.query or origin.fragment:
        raise ValueError("TOVER_ORIGIN must be https://TOVER_DOMAIN without a trailing slash")
    if domain.endswith(".example.com") or domain.endswith(".example.test"):
        raise ValueError("Replace the example domain before deployment")
    if any(not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", values[key])
           for key in ("ACME_EMAIL", "SMTP_ADMIN_EMAIL")):
        raise ValueError("Configure valid ACME_EMAIL and SMTP_ADMIN_EMAIL")
    if not values["SMTP_PORT"].isdigit() or not 1 <= int(values["SMTP_PORT"]) <= 65535:
        raise ValueError("SMTP_PORT must be a valid TCP port")
    if values["SMTP_HOST"].lower() in ("mail", "mailpit", "localhost", "127.0.0.1"):
        raise ValueError("Production requires an external SMTP provider")
    if ":" not in values["TOVER_APP_IMAGE"] or values["TOVER_APP_IMAGE"].endswith(":latest"):
        raise ValueError("TOVER_APP_IMAGE must name a release-specific tag or digest")
    if min(len(values[key]) for key in ("POSTGRES_PASSWORD", "TOVER_DB_PASSWORD", "JWT_SECRET")) < 32:
        raise ValueError("Generated database/JWT secrets must have at least 32 characters")
    # These passwords are embedded in internal connection URLs.
    if not re.fullmatch(r"[A-Za-z0-9_-]+", values["POSTGRES_PASSWORD"]):
        raise ValueError("Use the generated URL-safe POSTGRES_PASSWORD")
    try:
        if len(base64.b64decode(values["SESSION_ENCRYPTION_KEY"], validate=True)) != 32:
            raise ValueError()
    except (ValueError, TypeError):
        raise ValueError("SESSION_ENCRYPTION_KEY must encode exactly 32 bytes") from None
    for key, role in (("ANON_KEY", "anon"), ("SERVICE_ROLE_KEY", "service_role")):
        try:
            header, payload, signature = values[key].split(".")
            expected = hmac.new(values["JWT_SECRET"].encode(), f"{header}.{payload}".encode(), hashlib.sha256).digest()
            decoded = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            actual = base64.urlsafe_b64decode(signature + "=" * (-len(signature) % 4))
            if decoded.get("role") != role or not hmac.compare_digest(expected, actual):
                raise ValueError()
        except (ValueError, KeyError, TypeError):
            raise ValueError(f"{key} must match the generated JWT_SECRET and role") from None
    return values


def compare_restore(current, backup):
    current_values, backup_values = read_env(current), read_env(backup)
    keys = ("POSTGRES_PASSWORD", "TOVER_DB_PASSWORD", "JWT_SECRET", "ANON_KEY",
            "SERVICE_ROLE_KEY", "SESSION_ENCRYPTION_KEY")
    if any(current_values.get(key) != backup_values.get(key) for key in keys):
        raise ValueError("Restore requires the original database, JWT and session encryption secrets")
    if current_values.get("TOVER_APP_IMAGE") != backup_values.get("TOVER_APP_IMAGE"):
        raise ValueError("Restore requires the application's original release image")


def project_name(path):
    values = read_env(path) if Path(path).exists() else {}
    name = os.environ.get("TOVER_DEPLOYMENT_NAME", values.get("TOVER_DEPLOYMENT_NAME", "tover-production"))
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", name):
        raise ValueError("TOVER_DEPLOYMENT_NAME must be a lowercase Compose project name")
    return name


if __name__ == "__main__":
    try:
        if sys.argv[1] == "init":
            generate(sys.argv[2], sys.argv[3])
            print("Created private environment. Configure DNS, origin, ACME contact and real SMTP before install.")
        elif sys.argv[1] == "check":
            validate(sys.argv[2])
            print("Private configuration is valid.")
        elif sys.argv[1] == "compare-restore":
            compare_restore(sys.argv[2], sys.argv[3])
        elif sys.argv[1] == "project":
            print(project_name(sys.argv[2]))
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
