#!/usr/bin/env bash
# Apply each supabase/migrations/NNN_*.sql once, in name order, recording it in app_private.schema_migrations.
# Usage: supabase/migrate.sh <psql command…>, e.g. supabase/migrate.sh docker exec -i db psql -U postgres -d postgres
# Each file runs in one transaction together with its ledger row, so files contain no BEGIN/COMMIT.
set -euo pipefail
migrations_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/migrations"
psql_command=("$@")
[[ ${#psql_command[@]} -gt 0 ]] || { printf 'usage: %s <psql command…>\n' "$0" >&2; exit 2; }

"${psql_command[@]}" -X -q -v ON_ERROR_STOP=1 <<'SQL'
SET client_min_messages = warning;
CREATE SCHEMA IF NOT EXISTS app_private;
REVOKE ALL ON SCHEMA app_private FROM PUBLIC;
CREATE TABLE IF NOT EXISTS app_private.schema_migrations (
  name       text PRIMARY KEY,
  applied_at timestamptz NOT NULL DEFAULT now()
);
SQL

applied="$("${psql_command[@]}" -X -At -v ON_ERROR_STOP=1 -c 'SELECT name FROM app_private.schema_migrations')"
for migration in "$migrations_dir"/[0-9][0-9][0-9]_*.sql; do
  name="$(basename "$migration")"
  grep -qxF "$name" <<<"$applied" && continue
  { printf 'SET client_min_messages = warning;\n'; cat "$migration"; printf "\nINSERT INTO app_private.schema_migrations (name) VALUES ('%s');\n" "$name"; } |
    "${psql_command[@]}" -X -q -v ON_ERROR_STOP=1 --single-transaction
  printf 'Applied %s\n' "$name"
done
