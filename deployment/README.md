# Deployment

Build a Linux amd64 release locally, transfer it over SSH, then update the single production Compose instance. Run build commands from the repository root.

Detailed guides:

- [Server setup and first installation](../docs/selectel-deployment.md).
- [Production configuration, backups and recovery](../docs/production-deployment.md).
- [Local identity setup](../docs/identity-deployment.md).

## Build and publish

The build machine needs Python 3.10+, Git, authenticated `gh`, Docker 29+ with the containerd image store, Compose v2.24.4+ and Buildx. It needs access to GitHub, Maven and image registries.

Run the checks relevant to the change, including English/Russian UI checks when adding elements. Review `git status`: **publishing commits all pending nonignored changes and pushes the current branch and release tag**.

Choose an unused release tag and output directory; replace the example below:

```sh
RELEASE=v0.2.0
python3 deployment/production/publish.py "$RELEASE" \
  --message "Release $RELEASE" --output "/tmp/tover-$RELEASE"
```

This builds and verifies the offline bundle, rehearses installation and backup/restore, then publishes the archive, checksum and manifest to GitHub. For small application changes, add `--skip-rehearsal`; bundle checks still run. Use the full rehearsal for schema, storage, dependency or recovery changes.

Failures preserve commits, tags and artifacts. Inspect them before retrying; existing tags and output paths cannot be reused.

## Transfer and update

Prepare the server using the [setup guide](../docs/selectel-deployment.md#configure-the-server). On the build machine:

```sh
scp "/tmp/tover-$RELEASE.tar.gz" "/tmp/tover-$RELEASE.tar.gz.sha256" root@YOUR_SERVER:/tmp/
```

On the server, as root, use the same release tag:

```sh
set -eu
RELEASE=v0.2.0
cd /tmp
sha256sum --check "tover-$RELEASE.tar.gz.sha256"
install -d -m 0700 /srv/tover-releases
mkdir -m 0700 "/srv/tover-releases/$RELEASE"
tar -xzf "tover-$RELEASE.tar.gz" -C "/srv/tover-releases/$RELEASE"
cd "/srv/tover-releases/$RELEASE"
export TOVER_ENV_FILE=/etc/tover/app.env
deployment/production/production.sh load-images
```

For a **first installation**, follow [Install the release](../docs/selectel-deployment.md#install-the-release): initialize the private environment, configure HTTPS/SMTP, then use `install-fresh` with an unused Compose project.

For an **existing installation**, pause the backup timer if configured and wait for any active backup to finish. Back up from the old active release:

```sh
cd /srv/tover
deployment/production/production.sh backup "/srv/tover-backups/before-$RELEASE"
```

Copy the completed backup off-server. Set `TOVER_APP_IMAGE=tover:YOUR_RELEASE_TAG` in `/etc/tover/app.env`, matching the new bundle. Keep the existing project name and generated secrets. Apply any reviewed migration using its release-specific instructions before starting the new app.

```sh
ln -sfnT "/srv/tover-releases/$RELEASE" /srv/tover
cd /srv/tover
deployment/production/production.sh check
deployment/production/production.sh up
deployment/production/production.sh status
curl --fail --show-error https://YOUR_HOST/healthz
curl --fail --show-error https://YOUR_HOST/readyz
```

Check login, shared drafts, Preview/Submit, realtime updates and both languages in the browser. Resume the previously enabled backup timer after verification. Bundled deployments use loaded images without builds or pulls.

## Backups and rollback

Backups interrupt service and include all workspaces, shared drafts, identity data and private secrets. Use only backups marked `COMPLETE`, protect them and retain an off-server copy plus the matching release bundle. See [daily backups](../docs/selectel-deployment.md#enable-daily-backups).

Retain the previous release. Switch back only when its data format remains compatible; otherwise follow [restore and rehearsal](../docs/production-deployment.md#restore-and-rehearsal) to restore the complete backup into a new project. Preserve the original installation and never mix stores or secrets from different backups.

## Translation changes

Add new labels to both languages in [messages.json](../resources/i18n/messages.json) and run `node --test test/browser/i18n.test.mjs`. Verify the rendered UI in both languages.

The server caches the catalog. In development, evaluate `(require 'tover.shared.i18n :reload)` in the running app's REPL, then refresh the page. Production loads the catalog from the new image when it starts; refresh the browser afterward.
