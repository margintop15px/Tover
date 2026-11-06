# Deployment

Verified workflow for `fin-craft.ru`, using `v0.2.0` as the worked example. Choose an unused tag for future releases. Run build commands from the repository root.

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
  --message "Release $RELEASE" --output "/tmp/tover-$RELEASE" --skip-rehearsal
```

This fast path builds and verifies the offline bundle, then publishes the archive, checksum and manifest. Omit `--skip-rehearsal` for schema, storage, dependency or recovery changes.

Failures preserve commits, tags and artifacts. Inspect them before retrying; existing tags and output paths cannot be reused.

## Transfer and update

Prepare the server using the [setup guide](../docs/selectel-deployment.md#configure-the-server). On the build machine:

```sh
scp "/tmp/tover-$RELEASE.tar.gz" "/tmp/tover-$RELEASE.tar.gz.sha256" root@82.148.19.59:/tmp/
```

SSH to `root@82.148.19.59`. Extract each release once; if already extracted, continue with **Activate the release**.

```sh
set -eu
RELEASE=v0.2.0
cd /tmp
sha256sum --check "tover-$RELEASE.tar.gz.sha256"
install -d -m 0700 /srv/tover-releases
mkdir -m 0700 "/srv/tover-releases/$RELEASE"
tar -xzf "tover-$RELEASE.tar.gz" -C "/srv/tover-releases/$RELEASE"
```

## Activate the release

Set `TOVER_ENV_FILE` in every new SSH session. Persist the image from the bundle manifest in the existing `app.env`: for `v0.2.0`, this changes `TOVER_APP_IMAGE=tover:v0.1.6` to `TOVER_APP_IMAGE=tover:v0.2.0`. The commands below update only that setting and clear any shell override.

```sh
set -eu
RELEASE=v0.2.0
export TOVER_ENV_FILE=/etc/tover/app.env
unset TOVER_APP_IMAGE
cd "/srv/tover-releases/$RELEASE"
deployment/production/production.sh load-images
RELEASE_IMAGE=$(python3 -c 'import json; print(json.load(open("release.json"))["images"]["app"]["reference"])')
sed -i "s|^TOVER_APP_IMAGE=.*$|TOVER_APP_IMAGE=$RELEASE_IMAGE|" "$TOVER_ENV_FILE"
grep '^TOVER_APP_IMAGE=' "$TOVER_ENV_FILE"
ln -sfnT "/srv/tover-releases/$RELEASE" /srv/tover
cd /srv/tover
deployment/production/production.sh check
deployment/production/production.sh up
deployment/production/production.sh status
curl --fail --show-error https://fin-craft.ru/healthz
curl --fail --show-error https://fin-craft.ru/readyz
```

Both endpoints must return `200` / `ok`; confirm `tover:v0.2.0` is healthy in `status`. Refresh the browser and check the updated UI in English and Russian.

The `v0.2.0` deployment skipped an additional pre-deploy backup at the user's request. See the [full runbook](../docs/production-deployment.md) for backups, migrations and compatible rollback. Keep the previous release and existing project/secrets.

## Translation changes

Add new labels to both languages in [messages.json](../resources/i18n/messages.json) and run `node --test test/browser/i18n.test.mjs`. Verify the rendered UI in both languages.

The server caches the catalog. In development, evaluate `(require 'tover.shared.i18n :reload)` in the running app's REPL, then refresh the page. Production loads the catalog from the new image when it starts; refresh the browser afterward.
