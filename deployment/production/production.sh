#!/usr/bin/env bash
set -euo pipefail
umask 077
profile_dir=$(cd "$(dirname "$0")" && pwd -P)
repo_dir=$(cd "$profile_dir/../.." && pwd -P)
environment_file=${TOVER_ENV_FILE:-$profile_dir/.env}
configure_compose() {
  local project_name
  project_name=$(python3 "$profile_dir/config.py" project "$environment_file")
  # An unrelated COMPOSE_PROJECT_NAME must never redirect a backup or restore.
  compose=(docker compose --project-name "$project_name" --project-directory "$profile_dir" --env-file "$environment_file" -f "$profile_dir/compose.yaml")
  if [[ -n "${TOVER_COMPOSE_OVERRIDE:-}" ]]; then compose+=(-f "$TOVER_COMPOSE_OVERRIDE"); fi
}
configure_compose

runtime_compose() {
  local operation=$1
  shift
  if [[ -f "$repo_dir/release.json" ]]; then
    case "$operation" in
      up|create) set -- --no-build --pull never "$@" ;;
      run) set -- --pull never "$@" ;;
    esac
  fi
  "${compose[@]}" "$operation" "$@"
}

check() {
  python3 "$profile_dir/config.py" check "$environment_file"
  "${compose[@]}" config --quiet
  if [[ -f "$repo_dir/release.json" ]]; then
    python3 "$profile_dir/release.py" check "$repo_dir" --env-file "$environment_file"
  fi
}

migrate() {
  "${compose[@]}" exec -T db psql -X -q -U postgres -d postgres -v ON_ERROR_STOP=1 \
    < "$repo_dir/supabase/migrations/033_identity_and_sessions.sql"
  "${compose[@]}" exec -T db psql -X -q -U postgres -d postgres -v ON_ERROR_STOP=1 \
    < "$repo_dir/supabase/migrations/034_workspace_actor_labels.sql"
  "${compose[@]}" exec -T db sh -c \
    'psql -X -q -U postgres -d postgres -v ON_ERROR_STOP=1 --set=app_password="$TOVER_DB_PASSWORD"' <<'SQL'
ALTER ROLE tover_app WITH PASSWORD :'app_password';
SQL
}

case "${1:-}" in
  init)
    python3 "$profile_dir/config.py" init "$environment_file" "$profile_dir/.env.example"
    ;;
  check) check ;;
  load-images)
    [[ -f "$repo_dir/release.json" ]] || { printf 'load-images requires a release bundle.\n' >&2; exit 1; }
    python3 "$profile_dir/release.py" load "$repo_dir"
    ;;
  build)
    [[ ! -f "$repo_dir/release.json" ]] || { printf 'Release bundles contain prebuilt images; build on the release machine.\n' >&2; exit 1; }
    check
    "${compose[@]}" build app
    ;;
  install|install-fresh)
    check
    if [[ "$1" = install-fresh ]]; then
      project_name=$(python3 "$profile_dir/config.py" project "$environment_file")
      existing_containers=$("${compose[@]}" ps --all --quiet)
      if [[ -n "$existing_containers" ]]; then
        printf 'Fresh install requires an unused project; existing containers were preserved.\n' >&2; exit 1
      fi
      existing_volumes=$(docker volume ls --format '{{.Name}}')
      while IFS= read -r volume; do
        case "$volume" in
          "${project_name}_identity-data"|"${project_name}_inventory-data"|"${project_name}_tls-data"|"${project_name}_tls-config")
            printf 'Fresh install requires unused volumes; existing data was preserved.\n' >&2; exit 1 ;;
        esac
      done <<< "$existing_volumes"
    fi
    runtime_compose up -d --wait --wait-timeout 180 db
    "${compose[@]}" exec -T db bash -s <<'ROLES'
set -euo pipefail
psql -X -q -v ON_ERROR_STOP=1 -U supabase_admin -d postgres --set=auth_password="$POSTGRES_PASSWORD" <<'SQL'
ALTER ROLE authenticator WITH PASSWORD :'auth_password';
ALTER ROLE supabase_auth_admin WITH PASSWORD :'auth_password';
SQL
ROLES
    runtime_compose up -d --wait --wait-timeout 180 auth rest
    migrate
    if [[ ! -f "$repo_dir/release.json" ]]; then "${compose[@]}" build app; fi
    runtime_compose up -d --wait --wait-timeout 240 app proxy
    ;;
  migrate) check; migrate ;;
  up) check; runtime_compose up -d --wait --wait-timeout 240 ;;
  stop) "${compose[@]}" stop --timeout 30 ;;
  status) "${compose[@]}" ps ;;
  logs) "${compose[@]}" logs --tail=100 "${@:2}" ;;
  backup)
    destination=${2:?Usage: production.sh backup /absolute/new-backup-directory}
    [[ "$destination" = /* && ! -e "$destination" ]] || { printf 'Backup needs a new absolute directory.\n' >&2; exit 1; }
    if [[ -f "$repo_dir/release.json" ]]; then
      [[ -r "$repo_dir/release.json" && -f "$repo_dir/source-revision" && -r "$repo_dir/source-revision" ]] || {
        printf 'Backup requires readable release metadata and source-revision.\n' >&2; exit 1;
      }
    fi
    mkdir -m 700 "$destination"
    running_services=$("${compose[@]}" ps --status running --services)
    running=()
    while IFS= read -r service; do [[ -z "$service" ]] || running+=("$service"); done <<< "$running_services"
    restart() {
      local result=$?
      trap - EXIT
      if ((${#running[@]})); then
        "${compose[@]}" start --wait --wait-timeout 240 "${running[@]}" >/dev/null || result=1
      fi
      exit "$result"
    }
    trap restart EXIT
    trap 'exit 143' TERM
    trap 'exit 130' INT
    # Freeze all application and Auth writes before capturing either authority.
    "${compose[@]}" stop --timeout 30 proxy app auth rest
    "${compose[@]}" exec -T db pg_dump -U postgres -d postgres --format=custom > "$destination/identity.dump"
    "${compose[@]}" exec -T db pg_dumpall -U postgres --globals-only > "$destination/roles.sql"
    "${compose[@]}" stop --timeout 30 db
    mkdir "$destination/postgres" "$destination/tover"
    "${compose[@]}" cp db:/var/lib/postgresql/data/. "$destination/postgres/"
    "${compose[@]}" cp app:/var/lib/tover/. "$destination/tover/"
    cp "$environment_file" "$destination/.env"
    cp "$profile_dir/compose.yaml" "$destination/compose.yaml"
    if [[ -n "${TOVER_COMPOSE_OVERRIDE:-}" ]]; then cp "$TOVER_COMPOSE_OVERRIDE" "$destination/compose.override.yaml"; fi
    "${compose[@]}" images --format json > "$destination/images.json"
    if [[ -f "$repo_dir/release.json" ]]; then
      cp "$repo_dir/source-revision" "$destination/source-revision"
      cp "$repo_dir/release.json" "$destination/release.json"
    else
      git -C "$repo_dir" rev-parse HEAD > "$destination/source-revision"
    fi
    python3 - "$destination" <<'MANIFEST'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
with (root / 'SHA256SUMS').open('w') as output:
    for path in sorted(root.rglob('*')):
        if path.is_file() and path.name != 'SHA256SUMS':
            checksum = hashlib.sha256()
            with path.open('rb') as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b''):
                    checksum.update(chunk)
            output.write(f'{checksum.hexdigest()}  {path.relative_to(root)}\n')
MANIFEST
    touch "$destination/COMPLETE"
    printf 'Complete backup saved. Treat its directory as a secret; copy it off-host.\n'
    ;;
  restore-new)
    backup=${2:?Usage: production.sh restore-new /absolute/backup-directory new-project-name}
    target=${3:?Supply a new, unused Compose project name}
    [[ "$backup" = /* && -f "$backup/COMPLETE" ]] || { printf 'A complete absolute backup path is required.\n' >&2; exit 1; }
    [[ "$target" =~ ^[a-z0-9][a-z0-9_-]+$ ]] || { printf 'Invalid new project name.\n' >&2; exit 1; }
    if [[ -f "$repo_dir/release.json" ]]; then
      cmp -s "$repo_dir/release.json" "$backup/release.json" || {
        printf 'Restore requires the original release bundle recorded by the backup.\n' >&2; exit 1;
      }
      python3 "$profile_dir/release.py" check "$repo_dir" --env-file "$environment_file"
    elif [[ -f "$backup/release.json" ]]; then
      printf 'Restore requires the original release bundle recorded by the backup.\n' >&2; exit 1
    fi
    python3 "$profile_dir/config.py" compare-restore "$environment_file" "$backup/.env"
    cmp -s "$profile_dir/compose.yaml" "$backup/compose.yaml" || { printf 'Restore requires the backup release Compose profile and image pins.\n' >&2; exit 1; }
    python3 - "$backup" <<'VERIFY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
for line in (root / 'SHA256SUMS').read_text().splitlines():
    expected, name = line.split('  ', 1)
    path = root / name
    if not path.resolve().is_relative_to(root.resolve()):
        sys.exit('Backup manifest contains a path outside the backup')
    checksum = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            checksum.update(chunk)
    if checksum.hexdigest() != expected:
        sys.exit('Backup checksum verification failed')
VERIFY
    export TOVER_DEPLOYMENT_NAME="$target"
    configure_compose
    existing_volumes=$(docker volume ls --format '{{.Name}}')
    while IFS= read -r volume; do
      case "$volume" in
        "${target}_identity-data"|"${target}_inventory-data"|"${target}_tls-data"|"${target}_tls-config")
          printf 'Refusing restore: target volume already exists.\n' >&2; exit 1 ;;
      esac
    done <<< "$existing_volumes"
    existing_containers=$("${compose[@]}" ps --all --quiet)
    if [[ -n "$existing_containers" ]]; then
      printf 'Refusing restore: target project already has containers.\n' >&2; exit 1
    fi
    # Create stopped containers on new volumes only; never overwrite an installation.
    runtime_compose create db app
    "${compose[@]}" cp "$backup/postgres/." db:/var/lib/postgresql/data/
    "${compose[@]}" cp "$backup/tover/." app:/var/lib/tover/
    runtime_compose run --rm --no-deps --user 0 --entrypoint sh db -c 'chown -R postgres:postgres /var/lib/postgresql/data'
    runtime_compose run --rm --no-deps --user 0 --cap-add CHOWN --cap-add DAC_OVERRIDE --entrypoint sh app -c 'chown -R 10001:10001 /var/lib/tover'
    printf 'Restored into new project %s; services remain stopped. Configure its origin/SMTP before starting it.\n' "$target"
    ;;
  *) printf 'Usage: %s init|check|load-images|build|install-fresh|install|migrate|up|stop|status|logs|backup DIR|restore-new DIR PROJECT\n' "$0" >&2; exit 2 ;;
esac
