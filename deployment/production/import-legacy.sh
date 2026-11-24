#!/usr/bin/env bash
# One-time cutover: copy accounts, profiles, workspaces, memberships and invitations from a legacy
# (033/034 schema, releases up to v0.3.0) project into this freshly installed 001_baseline project.
#
#   TOVER_ENV_FILE=/etc/tover/app-v3.env deployment/production/import-legacy.sh /etc/tover/app.env
#
# The legacy project is only read; stop its proxy/app/auth/rest first so nothing writes during export.
# The import runs in one transaction and commits only if every row count matches the source.
# Not copied: legacy sessions (users sign in again), Ozon/import/inventory tables, RDF inventory.
set -euo pipefail
umask 077
profile_dir=$(cd "$(dirname "$0")" && pwd -P)
new_env=${TOVER_ENV_FILE:-$profile_dir/.env}
old_env=${1:?Usage: TOVER_ENV_FILE=NEW_ENV import-legacy.sh LEGACY_ENV}
[[ -f "$old_env" && -f "$new_env" ]] || { printf 'Both environment files must exist.\n' >&2; exit 1; }

new_project=$(python3 "$profile_dir/config.py" project "$new_env")
old_project=$(env -u TOVER_DEPLOYMENT_NAME python3 "$profile_dir/config.py" project "$old_env")
[[ "$old_project" != "$new_project" ]] || { printf 'Legacy and new projects must differ.\n' >&2; exit 1; }
new=(docker compose --project-name "$new_project" --project-directory "$profile_dir" --env-file "$new_env" -f "$profile_dir/compose.yaml")
if [[ -n "${TOVER_COMPOSE_OVERRIDE:-}" ]]; then new+=(-f "$TOVER_COMPOSE_OVERRIDE"); fi
# ponytail: only `ps` and `exec db` run against the legacy project, so the current compose file is enough.
# Legacy environments predate the journal worker; the placeholder only satisfies interpolation.
old=(env TOVER_JOURNAL_DB_PASSWORD=unused docker compose --project-name "$old_project" --project-directory "$profile_dir" --env-file "$old_env" -f "$profile_dir/compose.yaml")
old_sql() { "${old[@]}" exec -T db psql -X -q -At -v ON_ERROR_STOP=1 -U supabase_admin -d postgres "$@"; }
new_sql() { "${new[@]}" exec -T db psql -X -q -At -v ON_ERROR_STOP=1 -U supabase_admin -d postgres "$@"; }

running=$("${old[@]}" ps --status running --services)
grep -qx db <<<"$running" || { printf 'Legacy database %s is not running.\n' "$old_project" >&2; exit 1; }
if grep -qxE 'proxy|app|auth|rest' <<<"$running"; then
  printf 'Stop legacy writers first (docker compose ... stop proxy app auth rest); keep its db running.\n' >&2; exit 1
fi
[[ $(old_sql -c "SELECT to_regclass('public.organizations') IS NOT NULL AND to_regclass('public.workspaces') IS NULL") = t ]] ||
  { printf '%s does not have the legacy identity schema.\n' "$old_project" >&2; exit 1; }
[[ $(new_sql -c "SELECT EXISTS (SELECT FROM app_private.schema_migrations WHERE name = '001_baseline.sql')
                   AND NOT EXISTS (SELECT FROM auth.users) AND NOT EXISTS (SELECT FROM public.workspaces)") = t ]] ||
  { printf '%s must be a fresh baseline install with no users or workspaces.\n' "$new_project" >&2; exit 1; }

# Counts are taken with writers stopped, so they describe exactly what is exported below.
IFS='|' read -r users identities profiles workspaces memberships invitations < <(old_sql -c \
  "SELECT (SELECT count(*) FROM auth.users), (SELECT count(*) FROM auth.identities), (SELECT count(*) FROM public.profiles),
          (SELECT count(*) FROM public.organizations), (SELECT count(*) FROM public.organization_memberships),
          (SELECT count(*) FROM public.organization_invites)")

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
script="$work/import.sql"
copy_rows() {
  printf 'COPY %s FROM stdin;\n' "$1" >> "$script"
  old_sql -c "COPY ($2) TO STDOUT" >> "$script"
  printf '\\.\n' >> "$script"
}
# Auth rows go in unchanged with triggers off (no duplicate profiles, any table order).
printf 'SET session_replication_role = replica;\n' > "$script"
"${old[@]}" exec -T db pg_dump -U supabase_admin -d postgres --data-only --schema=auth \
  --exclude-table=auth.schema_migrations >> "$script"
# Identity rows go in with triggers and foreign keys checked.
printf 'SET session_replication_role = origin;\nSET search_path = public;\n' >> "$script"
copy_rows "public.workspaces (workspace_id, name, created_by, created_at, updated_at)" \
  "SELECT id, name, created_by, created_at, updated_at FROM public.organizations"
copy_rows "public.memberships (membership_id, workspace_id, user_id, role, status, created_at, updated_at, recovery_requested_at)" \
  "SELECT id, organization_id, user_id, role_id, status, created_at, updated_at, recovery_requested_at FROM public.organization_memberships"
copy_rows "public.invitations (invitation_id, workspace_id, email, role, invited_by, status, expires_at, created_at, updated_at)" \
  "SELECT id, organization_id, email, role_id, invited_by, status, expires_at, created_at, updated_at FROM public.organization_invites"
copy_rows "public.profiles (user_id, display_name, created_at, updated_at)" \
  "SELECT user_id, display_name, created_at, updated_at FROM public.profiles"
cat >> "$script" <<SQL
INSERT INTO public.profiles (user_id) SELECT id FROM auth.users ON CONFLICT (user_id) DO NOTHING;
DO \$check\$ BEGIN
  IF (SELECT count(*) FROM auth.users) <> $users OR (SELECT count(*) FROM auth.identities) <> $identities
    OR (SELECT count(*) FROM public.workspaces) <> $workspaces OR (SELECT count(*) FROM public.memberships) <> $memberships
    OR (SELECT count(*) FROM public.invitations) <> $invitations
    OR (SELECT count(*) FROM public.profiles) <> $users OR $profiles > $users THEN
    RAISE EXCEPTION 'Imported row counts differ from the legacy source; nothing was committed';
  END IF;
END \$check\$;
SQL
new_sql --single-transaction -o /dev/null < "$script"
printf 'Imported %s users, %s workspaces, %s memberships, %s invitations from %s into %s.\n' \
  "$users" "$workspaces" "$memberships" "$invitations" "$old_project" "$new_project"
printf 'Restart the new app (production.sh up), sign in as an existing user, then resend pending invitations.\n'
