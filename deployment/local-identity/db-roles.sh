#!/usr/bin/env bash
set -euo pipefail
# This entrypoint runs only in the new local integration volume.
# psql variables quote generated values as SQL strings; do not log credentials.
psql -X -v ON_ERROR_STOP=1 -U supabase_admin -d postgres \
  --set=auth_password="$POSTGRES_PASSWORD" <<'SQL'
ALTER ROLE authenticator WITH PASSWORD :'auth_password';
ALTER ROLE supabase_auth_admin WITH PASSWORD :'auth_password';
SQL
