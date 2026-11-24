#!/usr/bin/env python3
"""Rehearse the real production image and backup helper on fresh isolated volumes.

Leaves its projects stopped and data intact. Publishes only ephemeral loopback
ports, captures email and disables public TLS. Never reads local identity config.
"""
import html
import http.client
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

sys.dont_write_bytecode = True
SOURCE_PROFILE = Path(__file__).resolve().parent
RELEASE = os.environ.get("TOVER_REHEARSAL_RELEASE")
PROFILE = Path(RELEASE).resolve() / "deployment" / "production" if RELEASE else SOURCE_PROFILE
if RELEASE and not (PROFILE.parents[1] / "release.json").is_file():
    raise ValueError("Rehearsal release must be an extracted prebuilt bundle")
SPEC = importlib.util.spec_from_file_location("production_config", PROFILE / "config.py")
CONFIG = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONFIG)


def main():
    os.umask(0o077)
    restore_backup = len(sys.argv) > 1 and sys.argv[1] == "--restore-backup"
    resume = len(sys.argv) > 1
    evidence = Path(sys.argv[2] if restore_backup else sys.argv[1]).resolve() if resume else Path(tempfile.mkdtemp(prefix="tover-production-rehearsal-"))
    environment_file = evidence / ".env"
    if resume:
        project = CONFIG.read_env(environment_file).get("TOVER_DEPLOYMENT_NAME", "")
        if not re.fullmatch(r"tover-rehearsal-[a-f0-9]{10}", project):
            raise RuntimeError("Resume accepts only this runner's disposable project")
        if restore_backup:
            if not (evidence / "backup" / "COMPLETE").exists() or not (evidence / "identity.json").exists():
                raise RuntimeError("Restore retry requires this runner's complete backup and private identity checkpoint")
        elif (evidence / "backup").exists():
            raise RuntimeError("Use --restore-backup to verify a completed rehearsal backup")
    else:
        CONFIG.generate(environment_file, PROFILE / ".env.example")
        project = "tover-rehearsal-" + secrets.token_hex(5)
    restored = project + "-restored" + ("-" + secrets.token_hex(3) if restore_backup else "")
    values = {"TOVER_DEPLOYMENT_NAME": project,
              "TOVER_DOMAIN": "inventory.rehearsal.invalid",
              "TOVER_ORIGIN": "https://inventory.rehearsal.invalid",
              "ACME_EMAIL": "admin@rehearsal.invalid",
              "SMTP_HOST": "smtp.rehearsal.invalid", "SMTP_USER": "captured-only",
              "SMTP_PASS": "captured-only", "SMTP_ADMIN_EMAIL": "auth@rehearsal.invalid"}
    environment_file.write_text("\n".join(
        f"{line.split('=', 1)[0]}={values[line.split('=', 1)[0]]}"
        if line.split("=", 1)[0] in values else line
        for line in environment_file.read_text().splitlines()) + "\n")
    CONFIG.validate(environment_file)
    private = CONFIG.read_env(environment_file)
    override = SOURCE_PROFILE / "rehearsal.compose.yaml"
    override_source = override.read_text()
    if RELEASE:
        caddy = evidence / "rehearsal.Caddyfile"
        caddy.write_bytes((SOURCE_PROFILE / "rehearsal.Caddyfile").read_bytes())
        override_source = override_source.replace("./rehearsal.Caddyfile", str(caddy))
        override = evidence / "rehearsal.compose.yaml"
        override.write_text(override_source)
    network_environment = {}
    # Opt in only when the host's default Docker address pools are exhausted.
    network_base = os.environ.get("TOVER_REHEARSAL_NETWORK_BASE")
    if network_base:
        base = ipaddress.ip_network(network_base)
        if base.version != 4 or base.prefixlen != 22 or not base.is_private:
            raise ValueError("Rehearsal network base must be an unused private IPv4 /22")
        subnets = [str(subnet) for subnet in base.subnets(new_prefix=24)]
        network_environment = {
            target: {"TOVER_REHEARSAL_IDENTITY_SUBNET": subnets[index],
                     "TOVER_REHEARSAL_OUTBOUND_SUBNET": subnets[index + 1]}
            for target, index in ((project, 0), (restored, 2))}
        override = evidence / "rehearsal.compose.yaml"
        override.write_text(override_source + """
networks:
  identity:
    ipam:
      config: [{subnet: '${TOVER_REHEARSAL_IDENTITY_SUBNET:?}'}]
  outbound:
    ipam:
      config: [{subnet: '${TOVER_REHEARSAL_OUTBOUND_SUBNET:?}'}]
""")
    environment = {**os.environ, "TOVER_ENV_FILE": str(environment_file),
                   "TOVER_COMPOSE_OVERRIDE": str(override)}
    checks = json.loads((evidence / "result.json").read_text()) if restore_backup else {}
    checks.update({"project": project, "restored_project": restored, "evidence": str(evidence)})
    if RELEASE:
        checks["prebuilt_release"] = str(PROFILE.parents[1])
    if network_base:
        checks["rehearsal_networks"] = network_environment
    counter = max([int(path.stem.split("-")[1]) for path in evidence.glob("command-*.log")] or [0])

    def run(arguments, *, target=project, data=None):
        nonlocal counter
        counter += 1
        result = subprocess.run(arguments, env={**environment, "TOVER_DEPLOYMENT_NAME": target,
                                                **network_environment.get(target, {})},
                                input=data, text=True, capture_output=True)
        (evidence / f"command-{counter:03d}.log").write_text(result.stdout + result.stderr)
        if result.returncode:
            raise RuntimeError(f"Rehearsal command {counter} failed; private diagnostic log is in {evidence}")
        return result.stdout

    def compose(*arguments, target=project, data=None):
        return run(["docker", "compose", "--project-name", target, "--project-directory", str(PROFILE),
                    "--env-file", str(environment_file), "-f", str(PROFILE / "compose.yaml"),
                    "-f", str(override), *arguments], target=target, data=data)

    def production(*arguments, target=project):
        return run([str(PROFILE / "production.sh"), *arguments], target=target)

    def endpoint(service, port, target=project):
        address = compose("port", service, str(port), target=target).strip()
        if not address.startswith("127.0.0.1:"):
            raise RuntimeError("Rehearsal refused a non-loopback service endpoint")
        return "http://" + address

    def request(base, path, *, body=None):
        headers = {"Content-Type": "application/json", "apikey": private["ANON_KEY"]}
        message = urllib.request.Request(base + path, headers=headers,
                                         data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(message, timeout=20) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            raise RuntimeError(f"Rehearsal HTTP request returned {error.code} at {path.split('?')[0]}") from None

    def ready(target):
        base = endpoint("app", 3030, target)
        for path in ("/healthz", "/readyz"):
            with urllib.request.urlopen(base + path, timeout=10) as response:
                assert response.status == 200

    def inventory(mode, identity, target):
        # The application is closed before the offline verifier opens its own store.
        compose("stop", "--timeout", "30", "app", target=target)
        fingerprint = json.dumps(identity.get("fingerprint"))
        program = (SOURCE_PROFILE / "rehearsal_inventory.clj").read_text() + f"""
(ns user (:require [integrant.core :as ig] [tover.app :as app] [rehearsal-inventory :as rehearsal]))
(let [config (app/config)
      _      (ig/load-namespaces config)
      system (ig/init config [:tover.app/bel-system])
      part   #(val (ig/find-derived-1 system %))]
  (try
    (let [out (rehearsal/rehearse! "{mode}" (part :tover.app/bel-system) (part :tover.app/repo)
                                   (parse-uuid "{identity['workspace']}") (parse-uuid "{identity['actor']}")
                                   {"nil" if fingerprint == "null" else fingerprint})]
      (println (if (= "{mode}" "seed") (str "FINGERPRINT=" out) out)))
    (finally (ig/halt! system) (shutdown-agents))))
"""
        output = compose("run", "--rm", "-T", "--no-deps", "--entrypoint", "sh", "app", "-c",
                         'exec java --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED '
                         '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp "$(cat /app/classpath)" clojure.main -',
                         target=target, data=program)
        compose("up", "-d", "--wait", "--wait-timeout", "180", "app", target=target)
        ready(target)
        if mode == "seed":
            identity["fingerprint"] = re.search(r"^FINGERPRINT=(\S+)$", output, re.M).group(1)
        else:
            assert "PASS: accepted purchase of 17" in output

    class Browser:
        """Form posts to the app as a browser would, behind the configured HTTPS origin."""
        def __init__(self, target):
            self.address = endpoint("app", 3030, target).removeprefix("http://")
            self.headers = {"Host": values["TOVER_DOMAIN"], "Origin": values["TOVER_ORIGIN"]}
            self.cookies = {}

        def send(self, method, path, form=None):
            connection = http.client.HTTPConnection(self.address, timeout=20)
            headers = dict(self.headers, Cookie="; ".join(f"{k}={v}" for k, v in self.cookies.items()))
            body = urllib.parse.urlencode(form) if form is not None else None
            if body is not None:
                headers["Content-Type"] = "application/x-www-form-urlencoded"
            connection.request(method, path, body, headers)
            response = connection.getresponse()
            # Session cookies are Secure; the loopback hop stands in for the HTTPS proxy.
            for cookie in response.headers.get_all("Set-Cookie") or []:
                name, value = cookie.split(";", 1)[0].split("=", 1)
                self.cookies[name] = value
            page = response.read().decode()
            if response.status >= 400:
                raise RuntimeError(f"Rehearsal app request returned {response.status} at {path}")
            return response.headers.get("Location"), page

        def form(self, page, action, **fields):
            match = re.search(r'<form[^>]*action="%s"[^>]*>(.*?)</form>' % re.escape(action), page, re.S)
            assert match, f"Rehearsal page has no {action} form"
            data = {name: html.unescape(value) for name, value in
                    re.findall(r'<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"', match.group(1))}
            return self.send("POST", action, {**data, **fields})

        def sign_in(self, email, password):
            location, _ = self.form(self.send("GET", "/auth/login")[1], "/action/tover.identity.sign-in.web/sign-in",
                                    email=email, password=password)
            assert location == "/workspace", "Rehearsal sign-in did not reach the workspace page"

        def workspace_ids(self):
            page = self.send("GET", "/workspace")[1]
            forms = re.findall(r'<form[^>]*action="/action/tover.workspaces.select.web/select"[^>]*>(.*?)</form>', page, re.S)
            return [re.search(r'name="workspace-id" value="([0-9a-f-]{36})"', form).group(1) for form in forms], page

        def select(self, workspace):
            ids, page = self.workspace_ids()
            assert ids == [workspace], f"Rehearsal expected one workspace, saw {len(ids)}"
            self.form(page, "/action/tover.workspaces.select.web/select")
            team = self.send("GET", "/team")[1]
            assert "Tester" in team, "Rehearsal team page lacks the owner's display name"

    def verify_restored(identity):
        ready(restored)
        browser = Browser(restored)
        browser.sign_in(identity["email"], identity["password"])
        browser.select(identity["workspace"])
        checks["restored_identity_login_membership_and_display_name"] = True
        inventory("verify", identity, restored)
        checks["restored_inventory_draft_receipt_and_history"] = True
        checks["restored_readiness"] = True

    try:
        print(f"Private rehearsal evidence: {evidence}", flush=True)
        if RELEASE:
            production("load-images")
        if restore_backup:
            print("Restoring completed rehearsal backup into another unused target", flush=True)
            identity = json.loads((evidence / "identity.json").read_text())
            production("restore-new", str(evidence / "backup"), restored, target=restored)
            checks["backup_and_fresh_target_restore"] = True
            production("up", target=restored)
            verify_restored(identity)
            print("PASS: fresh restore, original identity login/membership, inventory, draft and receipt", flush=True)
            return
        print("Building/installing isolated production-shaped project", flush=True)
        if resume:
            compose("stop", "--timeout", "30")
        production("install" if resume else "install-fresh")
        ready(project)
        checks["initial_readiness"] = True
        auth, mail = endpoint("auth", 9999), endpoint("mail", 8025)
        email = f"restore-{secrets.token_hex(6)}@example.test"
        password = secrets.token_urlsafe(24)
        request(auth, "/signup", body={"email": email, "password": password, "data": {"name": "Tester"}})
        deadline = time.monotonic() + 30
        token_hash = None
        while time.monotonic() < deadline and not token_hash:
            messages = request(mail, "/api/v1/messages").get("messages", [])
            for message in messages:
                if any(recipient.get("Address") == email for recipient in message.get("To", [])):
                    body = request(mail, "/api/v1/message/" + message["ID"])
                    for link in re.findall(r'href="([^"]+)"', body.get("HTML", "")):
                        parameters = urllib.parse.parse_qs(urllib.parse.urlparse(html.unescape(link)).query)
                        if parameters.get("type") == ["email"]:
                            token_hash = parameters.get("token_hash", [None])[0]
            if not token_hash:
                time.sleep(0.2)
        assert token_hash, "Captured signup confirmation was not delivered"
        confirmed = request(auth, "/verify", body={"token_hash": token_hash, "type": "email"})
        assert confirmed["user"].get("email_confirmed_at")
        browser = Browser(project)
        browser.sign_in(email, password)
        assert browser.workspace_ids()[0] == [], "Signup metadata must not create a workspace"
        browser.form(browser.send("GET", "/workspace")[1], "/action/tover.workspaces.create.web/create",
                     name="Restore rehearsal")
        ids, _ = browser.workspace_ids()
        assert len(ids) == 1, "Rehearsal workspace creation failed"
        browser.select(ids[0])
        identity = {"email": email, "password": password, "workspace": ids[0], "actor": confirmed["user"]["id"]}
        checks["captured_confirmation_sign_in_and_workspace_creation"] = True
        inventory("seed", identity, project)
        inventory("verify", identity, project)
        (evidence / "identity.json").write_text(json.dumps(identity) + "\n")
        checks["accepted_inventory_pending_draft_and_receipt"] = True
        backup = evidence / "backup"
        print("Backing up both closed stores and restoring into a second unused project", flush=True)
        production("backup", str(backup))
        assert (backup / "COMPLETE").exists()
        production("restore-new", str(backup), restored, target=restored)
        checks["backup_and_fresh_target_restore"] = True
        production("up", target=restored)
        verify_restored(identity)
        print("PASS: production image, captured confirmation, identity/workspace and complete inventory backup/restore", flush=True)
    finally:
        for target in (project, restored):
            try:
                compose("stop", "--timeout", "30", target=target)
            except RuntimeError:
                pass
        checks["not_tested"] = ["public DNS/TLS issuance", "external SMTP delivery", "browser workflow (separate suite)"]
        (evidence / "result.json").write_text(json.dumps(checks, indent=2) + "\n")
        print(f"Projects stopped; volumes and private evidence preserved at {evidence}", flush=True)


if __name__ == "__main__":
    main()
