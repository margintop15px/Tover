# Fresh tester deployment on VDS by Selectel

Use the **vds.selectel.ru** service, one 8 GiB / 4-core / 80 GiB VM in Russia, and one Tover instance. Start with new accounts, workspaces and empty inventory. Keep the existing installation and its data separate. The generic [production guide](production-deployment.md) describes the Compose profile, migrations and recovery format.

## Configure the server

Choose Ubuntu 24.04 LTS amd64 if available. The provisioned Ubuntu 22.04 amd64 host also works with its distribution-provided Python 3.10; apply current package updates before installation. Install Python 3.10+, curl, tar/gzip, Docker Engine 29+ with its containerd image store and the Docker Compose plugin (v2.24.4+). Follow [Docker's Ubuntu repository installation](https://docs.docker.com/engine/install/ubuntu/#install-using-the-apt-repository). Fresh Engine 29 installations use the [containerd image store](https://docs.docker.com/engine/storage/containerd/) by default. The bundle preflight refuses other image stores rather than changing daemon settings or image identities. Enable Docker at boot with `systemctl enable --now docker`. The server runs prebuilt images; it needs no Git, Java, Clojure or Node installation.

Use SSH keys. Restrict TCP 22 to administrator addresses. Publish TCP 80/443; UDP 443 is optional. Only Caddy publishes container ports. Do not publish 3030, 5432, 9999 or PostgREST. Account for Docker's [published-port firewall behavior](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations) when configuring host rules.

Point the application's DNS A record at the VM. Add an AAAA record only when IPv6 works. Reserve ports 80/443 for Caddy. The VM needs package-repository access for the initial Docker installation, then outbound DNS/HTTPS for the Selectel API and ACME, plus access to the chosen SMTP service. Application installation, upgrades and restore use the transferred images and do not access GitHub, Maven or a container registry.

Configure a verified email sender and SPF/DKIM/DMARC records with your SMTP provider. Check the policy of this exact VDS service; restrictions documented for other Selectel products do not establish its SMTP policy. [Selectel Email](https://docs.selectel.ru/en/email-service/connect-email-service/) documents `smtp.mail.selcloud.ru:1126` with STARTTLS as one option; test confirmation, invitation and recovery messages before inviting testers. Never disable confirmation to work around failed mail delivery.

## Install the release

Build on a machine that can reach GitHub, Maven and the pinned image registries, such as the development machine outside Russia. Use Docker Engine 29+ with its containerd image store and Buildx. The builder always selects `linux/amd64`, including on an Apple Silicon Mac. Use a committed, tested release and a new image tag; the manifest records the Git revision, dirty-tree flag and build-input checksum.

To commit all pending repository changes and publish a verified GitHub prerelease, install Git and an authenticated `gh` CLI, then run:

```sh
python3 deployment/production/publish.py v0.1.0-tester.1 \
  --message "Release first tester deployment" --output /tmp/tover-v0.1.0-tester.1
```

The command commits all nonignored changes, creates an annotated tag, atomically pushes the branch and tag to `origin`, builds/loads the bundle, rehearses fresh installation and backup/restore, then uploads only the public archive, checksum and release manifest. GitHub notes include generated changes. Keep other writers idle; source changes abort publication. `TOVER_REHEARSAL_NETWORK_BASE` remains available for the existing isolated-network override. Reused tags, images or output paths are refused. A failure preserves commits, tags and artifacts; inspect it before choosing a new tag. Private rehearsal data stays local.

For a local bundle without Git publication, choose a new directory outside the checkout:

```sh
python3 deployment/production/release.py package /tmp/tover-20261025 --image tover:tester-20261025
tar -czf /tmp/tover-20261025.tar.gz -C /tmp/tover-20261025 .
scp /tmp/tover-20261025.tar.gz root@YOUR_SERVER:/tmp/
```

The bundle contains all five runtime images, Compose configuration, scripts, email templates, migrations 033/034, documentation and checksums. It excludes real `.env` files, API tokens, stores and Git metadata. Runtime images use bundle-local tags verified against their recorded image IDs, so loading does not depend on registry digest metadata surviving `docker save`/`load`.

For the first installation, run as root on the VM. Use a new empty release directory and an unused `/srv/tover` path:

```sh
install -d -m 0700 /etc/tover /srv/tover-releases/20261025
tar -xzf /tmp/tover-20261025.tar.gz -C /srv/tover-releases/20261025
test ! -e /srv/tover && test ! -L /srv/tover
ln -sT /srv/tover-releases/20261025 /srv/tover
cd /srv/tover
deployment/production/production.sh load-images
export TOVER_ENV_FILE=/etc/tover/app.env
deployment/production/production.sh init
```

`load-images` verifies the bundle before loading images, then checks image IDs and architecture. Keep the transferred bundle until recovery is rehearsed. Edit `/etc/tover/app.env`, retaining mode 0600:

| Setting | Configure |
| --- | --- |
| `TOVER_DEPLOYMENT_NAME` | Use `tover-tester`, a new Compose project with unused volumes. |
| `TOVER_APP_IMAGE` | Keep the exact app tag recorded by this bundle, such as `tover:tester-20261025`. |
| `TOVER_DOMAIN` | Your hostname, such as `test.example.ru`. |
| `TOVER_ORIGIN` | Exactly `https://` plus that hostname, without a trailing slash. |
| `ACME_EMAIL` | Your certificate contact address. |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASS` | Real SMTP submission settings. |
| `SMTP_ADMIN_EMAIL`, `SMTP_SENDER_NAME` | Verified sender address and `Tover`. |
| Generated database/JWT/session secrets | Keep the generated values and retain a private recovery copy. |

Single-quote SMTP passwords containing `$`, `#` or spaces. `init` refuses to overwrite an existing environment. Do not copy development credentials or inventory into this installation.

```sh
deployment/production/production.sh check
deployment/production/production.sh install-fresh
deployment/production/production.sh status
```

`install-fresh` refuses existing project containers or data/TLS volumes. It initializes Auth, applies 033 then 034 and starts the loaded images with builds and pulls disabled. Missing, changed or wrong-architecture images fail the preflight. A partial failed installation retains its data; inspect the failure and use `install` to resume that same installation. Do not reset it. Subsequent starts use `up`. Export `TOVER_ENV_FILE=/etc/tover/app.env` in each administration shell; the backup service reads this path from its environment file.

The app has a 4 GiB container limit and 2 GiB maximum Java heap. The remaining container memory accommodates native/mapped data, and the rest of the 8 GiB host accommodates Postgres, Auth and the OS. Each of the five services retains at most three 10 MiB Docker log files. These limits are a starting budget, not a workload capacity guarantee.

## Update a release

Build and transfer the next bundle outside Russia under a new directory and image tag. Load its images using that release directory's `production.sh load-images`; the old app keeps running. Pause the backup timer and wait for any current backup job to finish. From the old active `/srv/tover` release, make the pre-upgrade backup described in the production guide.

Keep `/etc/tover/app.env` and its generated secrets. Update only the app image tag and deliberately changed settings. Switch `/srv/tover` to the new release with `ln -sfnT /srv/tover-releases/NEW_RELEASE /srv/tover`, enter that directory, export `TOVER_ENV_FILE`, and run `production.sh check` followed by `production.sh up`. Neither command builds or pulls in a bundle installation. Verify readiness, browser behavior and SMTP, then restart the backup timer. Schema or semantic changes still require their explicit reviewed migration and restore rehearsal.

Retain the previous bundle and images. Switching back is safe only when its data format is compatible; otherwise restore the full pre-upgrade backup into a new target. Keep enough disk space for the retained release archives and local backups.

## Enable daily backups

Create a VDS API token in the account panel and record this VM's numeric server ID. Store the token in a root-owned mode-0600 file, outside the checkout. It must contain only the token and an optional final newline. Restrict the token's permissions if the panel offers that capability. The published [VDS API](https://developers.vds.selectel.ru/documentation/api/v1/) still uses `https://api.vscale.io/v1`; the helper fixes that endpoint and refuses redirects.

```sh
install -d -m 0700 /etc/tover /srv/tover-backups
install -m 0600 /dev/null /etc/tover/selectel.token
# Edit /etc/tover/selectel.token privately; do not paste the token into shell commands.
install -m 0600 deployment/production/backup.env.example /etc/tover/backup.env
```

Edit `/etc/tover/backup.env`: set `SELECTEL_SERVER_ID`, check the token path, and keep the default installation/environment paths unless you deliberately changed them. Check the token file without calling the API:

```sh
python3 deployment/production/selectel-backup.py \
  --server-id YOUR_NUMERIC_SERVER_ID --token-file /etc/tover/selectel.token --check
install -m 0644 deployment/production/systemd/tover-backup.service /etc/systemd/system/
install -m 0644 deployment/production/systemd/tover-backup.timer /etc/systemd/system/
systemctl daemon-reload
# First run requires a maintenance window and creates a billed Selectel backup.
systemctl start tover-backup.service
journalctl -u tover-backup.service -n 50 --no-pager
systemctl enable --now tover-backup.timer
systemctl list-timers tover-backup.timer
```

The timer runs at **03:00 UTC** daily. Change `OnCalendar` to your agreed maintenance window before enabling it. Missed jobs are not caught up during daytime after a reboot. A successful job:

1. Locks the backup directory to prevent overlapping jobs.
2. Stops writers, dumps Postgres, closes both stores and captures matching data, secrets and a checksum manifest.
3. Restarts the previously running services and waits for their health checks.
4. Requests one Selectel VM backup, prints its UUID and waits up to 30 minutes for `finished`, available and unlocked status.
5. Keeps seven completed provider backups with this helper's exact server-specific names and two complete local backups. Unrelated/incomplete backups are preserved. A failed provider job does not prune local recovery points.

The local backup is a service interruption. Online VM backup upload can continue after services restart because the complete local recovery directory already exists on disk. The provider snapshot's live database directories are not our consistency proof; use the complete local backup inside it when rehearsing recovery. Repeated jobs that fail can consume disk: investigate failed units and old incomplete backups rather than ignoring them.

The unit allows five minutes to stop so the helper can restart services after cancellation. After a forced kill, host failure or power loss, inspect `production.sh status` and run `production.sh up` if needed. An incomplete local backup is never a restore source.

Provider copies contain application data, API credentials and session secrets. Protect the VDS account accordingly. Keep enough disk space for live data plus a new complete backup before retention removes old copies. Retain a separate encrypted recovery copy outside this account if recovery from loss of account access is required.

## Verify before inviting testers

```sh
curl --fail --show-error https://YOUR_HOST/healthz
curl --fail --show-error https://YOUR_HOST/readyz
curl --head http://YOUR_HOST
docker stats --no-stream
df -h / /srv/tover-backups
```

Confirm trusted TLS and the HTTP-to-HTTPS redirect. Test signup, confirmation, login/logout, recovery and invitations. In two browsers, create a draft, review and submit it, observe both pages updating, then refresh and restart the app. Check that a separate workspace cannot see the data.

Create a recovery VM from a provider backup through `make_from` in the API or the account panel. Keep production DNS unchanged and restrict its ingress and outbound access until it is isolated. The clone contains the original enabled timer, API token and server ID. Before granting outbound access, isolate its copied backup job:

```sh
systemctl disable --now tover-backup.timer
systemctl stop tover-backup.service
install -d -m 0700 /root/tover-clone-quarantine
mv /etc/tover/selectel.token /root/tover-clone-quarantine/
mv /etc/systemd/system/tover-backup.service /etc/systemd/system/tover-backup.timer /root/tover-clone-quarantine/
systemctl daemon-reload
systemctl mask tover-backup.timer tover-backup.service
```

Move the locally installed unit files before masking; systemd cannot replace those regular files with mask symlinks. Otherwise the copied job could create or rotate backups of the original VM. Keep the units masked for a rehearsal; a replacement production VM requires its own server ID and credentials before restoring and enabling its units.

Stop the copied application before recovery work; avoid sending test messages to real recipients. Locate the newest complete `/srv/tover-backups/tover-SERVERID-TIMESTAMP` directory. Use its recorded release/image and the generic guide's `restore-new` procedure with an unused Compose project, an isolated hostname and test SMTP. Confirm login, membership, stock/history, a pending draft and submission receipt retries. Do not rebuild or overwrite the live server to rehearse recovery.

Configure an external monitor for `/readyz` and failure notifications for `tover-backup.service`; pause expected downtime alerts during the maintenance window. The unit records success/failure in the journal but does not send notifications. Monitor free disk and use `production.sh logs` for diagnosis. Run the generic production rehearsal before each release; public TLS, real SMTP and provider restoration still need checks on the actual Selectel host.
