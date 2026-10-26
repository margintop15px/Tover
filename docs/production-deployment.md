# First tester deployment

Run one Tover instance on one Docker Compose v2 host. Caddy terminates HTTPS; the application owns browser traffic. Supabase Auth, PostgREST and Postgres have no published ports. Auth alone has outbound SMTP access. Inventory and shared drafts use a dedicated RDF volume; identity and encrypted sessions use a separate Postgres volume.

For the selected VDS by Selectel fresh tester installation, follow the [server configuration and backup runbook](selectel-deployment.md). That path builds an amd64 release bundle outside Russia and transfers all runtime images over SSH; the server never needs GitHub, Maven or registry access for application deployment.

The profile does not read, migrate or replace `.data` or `deployment/local-identity/.env`. A new deployment starts with empty inventory. The pinned RDF4J dependency requires Java 25; both build and runtime images use Java 25. Test fixtures, development namespaces, Node tooling and captured mail are excluded from the application image. Image construction loads the production application namespace to catch classpath/runtime incompatibilities before deployment.

## Install

Requirements: Docker Compose v2.24.4+, Python 3.10+, a DNS hostname pointing to this host, inbound TCP 80/443 (UDP 443 is optional), and a verified SMTP sender. A source installation also needs Git and access to build dependencies; a transferred bundle needs Docker Engine 29+ with the containerd image store and no Git. Reserve these ports for this profile. Keep SSH access restricted. The current tester budget is 4 CPUs, 8 GiB RAM and 80 GiB persistent disk, with enough free space for complete backups. The app has a 4 GiB container limit and 2 GiB heap; all services rotate three 10 MiB log files.

From a release checkout, or an extracted bundle after `production.sh load-images`:

```sh
deployment/production/production.sh init
```

Edit the newly created private `deployment/production/.env`. Set `TOVER_DOMAIN`, the matching `https://...` `TOVER_ORIGIN`, `ACME_EMAIL`, real SMTP credentials and the verified sender address. Use the provider's TLS-enabled SMTP submission port, normally 587. Single-quote values containing `$`, `#` or spaces. Keep generated database, JWT and session encryption secrets unchanged and retain mode 0600. Use a release-specific `TOVER_APP_IMAGE` tag; never reuse it for a different build.

```sh
deployment/production/production.sh check
deployment/production/production.sh install-fresh
deployment/production/production.sh status
```

`install-fresh` refuses existing project containers and volumes, then starts the private database, configures Auth roles, starts Auth/PostgREST, applies the fresh identity baseline 033 then actor-label migration 034, assigns the restricted session role password and starts the application with Caddy. A source checkout builds the app; a bundle verifies and uses its loaded images with builds and pulls disabled. It never runs legacy inventory migrations. `install` resumes a partial installation without the fresh-target guard. Repeating 033 and 034 is supported; a partial incompatible identity schema fails explicitly. Existing installations through 032 also use 033 then 034; any migration of an unrelated hosted identity target needs its own rehearsed procedure.

Caddy obtains and renews its certificate and redirects HTTP to HTTPS. Its persistent TLS volumes survive restarts. The profile follows [Supabase's HTTPS boundary guidance](https://supabase.com/docs/guides/self-hosting/self-hosted-proxy-https): public HTTPS and private HTTP behind the proxy. Tover calls Auth/PostgREST internally; custom token-hash email templates lead to Tover `/auth/confirm`. Public `/auth/v1` and `/rest/v1` APIs and OAuth providers are outside this profile.

Verify `https://YOUR_HOST/healthz` and `/readyz` return 200 and that HTTP redirects to HTTPS. `/healthz` checks the running process. `/readyz` reads RDF, probes the private session table through JDBC, and calls Auth `/health` and PostgREST `/`; any failed dependency returns 503. Auth/PostgREST/Postgres also have independent container health checks. Inspect status and logs after boot or any restart. No daemon or external uptime monitor is included.

Before inviting testers, exercise signup, confirmation email, login/logout, recovery, workspace creation, invitation to a new user and invitation to an existing user. Use two browsers to save a shared draft, review it from the other account, submit it, and observe Stock/Operations updates. Refresh and reconnect both browsers. Repeat after an application restart. Confirm an unrelated workspace cannot see the entries or history. SMTP delivery and trusted public TLS must be tested on the actual configured host.

Keep initial tester histories small and watch Preview/Submit latency. Validation queries the connected affected item/operation component and checks its full chronology; complete multi-line operations and references can make that component large. Ordered prefix validation has quadratic worst-case growth within the component. Measured query-only samples were 58 ms for 101 effects, 506 ms for 501 and 1.82 s for 1,001; one operation can produce multiple effects. These samples are not total request times or a capacity guarantee. Each query has a 15-second execution bound; a timeout aborts acceptance and preserves the draft. Validate representative larger connected histories before a broader rollout.

## Backup

Use a new absolute destination on private storage, outside the checkout:

```sh
deployment/production/production.sh backup /srv/tover-backups/2026-10-21-before-upgrade
```

The helper stops public access, Tover, Auth and PostgREST before dumping Postgres. It then stops Postgres and copies the closed RDF and Postgres directories. It includes a custom-format database dump, global-role dump, private environment, source revision, image identifiers and SHA-256 manifest. Only a fully completed backup receives `COMPLETE`. Its exit handler restarts the services that were running before the backup, including after failure; check status afterward.

This is a maintenance window: writes and sessions are unavailable during the backup. RDF must never be copied while its application process is running. The physical database copy requires the same pinned Postgres image on restore. Keep the logical dumps for version-specific recovery or migration work. The `.env` is necessary to decrypt sessions and retain valid credentials. Encrypt backups at rest, copy a complete backup off-host, and restrict access as tightly as production secrets.

## Restore and rehearsal

Restore only into a new Compose project with unused volume names. The helper refuses any existing target volume or container and never resets an installation. Retain the original private environment; `restore-new` compares the database, JWT and encryption secrets with the backup before copying. A second checkout can use `TOVER_ENV_FILE=/absolute/private/rehearsal.env`; copy the backed-up environment there with mode 0600, retain secrets and adjust domain/origin/SMTP for the isolated host. Do not point a rehearsal at the live hostname or live mail recipients.

```sh
deployment/production/production.sh restore-new /srv/tover-backups/2026-10-21-before-upgrade tover-restore-check
```

The helper verifies checksums, creates stopped containers on fresh volumes and restores both data directories. Services remain stopped. Use the exact application image and Compose configuration recorded by the backup; load or pull that release image before restoring on another host. The backup does not bundle container images.

For a bundle installation, retain its complete release directory and `images.tar`. Run that release's `production.sh load-images` before restoration to verify and load all five runtime images locally. Restore requires the exact release manifest recorded in the backup, including image IDs; a reused app tag is insufficient. The bundle carries source identity for backups; Git and registry access are not required on the recovery host.

After configuring an isolated domain and SMTP target, start the restored project:

```sh
TOVER_DEPLOYMENT_NAME=tover-restore-check deployment/production/production.sh up
TOVER_DEPLOYMENT_NAME=tover-restore-check deployment/production/production.sh status
```

Validate an existing user's login, memberships, accepted operation IDs, stock totals, an acknowledged incomplete draft, an item-history expansion and the last submission receipt. Submit a new synthetic entry in the rehearsal workspace and check that both pages agree. Preserve the original backup and installation until these checks pass. Provision a separate host for a rehearsal if production already owns ports 80/443.

## Upgrade

1. Record the current source revision and image IDs. Complete and copy a backup off-host.
2. Build the next release under a new application tag. For a bundle installation, build on the connected release machine and transfer/load the new bundle over SSH. Run Clojure, browser and deployment configuration checks against it; rehearse restore with the pinned dependency versions.
3. Stop public access and application writers for any schema migration. Review the new consecutive SQL migration and its compatibility before applying it. This release requires 034 for workspace-scoped actor display labels; it preserves identity and inventory records. `production.sh migrate` applies only the reviewed, idempotent 033 → 034 chain, not arbitrary future SQL. A changed RDF semantic package also requires an explicit reviewed migration: startup refuses an existing store whose installed ontology/shapes/query checksum differs. Do not overwrite the installed package automatically. Rehearse that migration against a restored backup first.
4. Change `TOVER_APP_IMAGE` to the verified release, run `production.sh up`, verify readiness and repeat the smoke workflow. Keep the old image and backup.
5. Roll back the application image only when the new release's data format is compatible. Otherwise restore the complete pre-upgrade backup into a new project and switch the public host after validation. Never combine one backup's RDF directory with another backup's database or secrets.

Scoped acceptance upgrades the derived inventory cache to version 4 and replaces the aggregate projection payload with a lightweight storage marker. Immutable ledger records and the semantic package are unchanged. Earlier application images expect the aggregate payload, so rollback to those images requires the complete pre-upgrade backup. Rehearse backup restoration with the new release before tester deployment.

Supabase/Postgres upgrades are separate from application releases. Keep the tested Auth `v2.197.0`, PostgREST `v14.18` and Postgres `17.6.1.136` pins until a version-specific upgrade and restoration rehearsal succeeds. Third-party images have both version tags and immutable registry digests in the Dockerfile and Compose profile. Retain the application image under its release-specific tag or publish it to a private registry and use its digest. The profile contains no volume deletion, database reset or destructive overwrite command.

Configuration and backup failure guards have automated checks:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s test/production -v
```

Run the reproducible production-shaped rehearsal before a release:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 deployment/production/rehearse.py
```

To exercise the transferred amd64 bundle instead of building from source, add `TOVER_REHEARSAL_RELEASE=/absolute/extracted/bundle` to that command. The runner loads its images and uses that bundle's installer with builds and pulls disabled.

It creates generated private configuration and two fresh Compose projects. A rehearsal-only override captures mail, publishes ephemeral loopback ports and disables public TLS. It verifies signup/confirmation and automatic workspace creation, saves accepted inventory plus an incomplete shared draft, backs up both closed stores, restores to unused volumes, then verifies existing login/membership, inventory, item history, draft, submission receipt and projection rebuild. The projects finish stopped; their volumes and private logs remain for inspection. It never uses the local identity profile or prototype data. The printed private evidence directory contains the result report and secrets; retain it as a backup. Use the normal production profile for deployment; the rehearsal override is not an external SMTP or public TLS check.

If Docker's default address pools are exhausted, set `TOVER_REHEARSAL_NETWORK_BASE` to an unused private IPv4 `/22`, such as `10.254.240.0/22`, after inspecting existing network allocations. The runner writes a private override with four distinct `/24` subnets, two per fresh rehearsal project. This does not change production profiles, prune networks or change existing projects. The normal configuration and unused-target restore guards still apply.

These checks do not prove public certificate issuance, external SMTP delivery or the full restored browser workflow. Record those release checks on the configured host before inviting testers.

Verified locally on 2026-10-22 with `tover:tester-20261022-ce2`: production image build and namespace load, 348 Clojure tests with 4,020 assertions, 47 isolated browser cases, a separate real-email browser flow, migration 034 permission checks and eight deployment/guard checks. A consistent closed-store backup restored into a fresh target with the original login, workspace membership and actor display name. Accepted stock/history, an incomplete durable draft, receipt retries, projection rebuild and readiness passed. Public DNS/TLS issuance and external SMTP delivery remain host-specific checks.

Verified locally on 2026-10-23 with `tover:tester-20261023-selectel`: production image build, guarded fresh installation, captured confirmation email, readiness and complete closed-store backup/restore passed. Restored login, membership, actor labels, stock/history, incomplete shared draft, receipt retry and projection rebuild passed. All 31 deployment tests passed, including backup cancellation/restart and fake-provider retention/failure checks. All pinned base images include linux/amd64. The application image for that check was built locally on arm64; it did not establish the amd64 bundle path. Live Selectel API calls, Linux systemd execution, provider restoration, public TLS, external SMTP and the full browser suite were not rerun by this deployment check.

Verified locally on 2026-10-25: an amd64 bundle containing all five runtime images was built, exported and loaded on an Apple Silicon Docker host. Fresh installation, captured confirmation, readiness and closed-store backup/restore passed using the bundle, with no Git checkout and with runtime builds and image pulls disabled. Restored login, membership, stock/history, shared draft, submission receipt and projection rebuild passed. All 52 deployment tests passed, including release checksums, image identity/platform checks, physical release paths and fail-closed restore guards. This used amd64 emulation locally; public TLS, external SMTP, Linux systemd and live Selectel recovery still need host checks.

The subsequent Ubuntu 22.04 compatibility change replaces Python 3.11-only checksum calls with bounded SHA-256 updates. All 54 deployment tests passed natively. An actual Python 3.10 container passed 51 helper tests, including bundle checksums/load and backup/restore corruption checks; the three Compose CLI configuration checks ran only in the native suite. The Python container used fake Docker commands and a Git revision stub, not real application containers. Build a new bundle to include this change; previously generated bundles are unchanged.
