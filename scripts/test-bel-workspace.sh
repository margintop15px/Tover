#!/usr/bin/env bash
set -euo pipefail

# A new cluster only. Never read .env or use an existing database.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
test_root=$(mktemp -d /private/tmp/tover-bel-workspace.XXXXXX)
test_container=''
mail_container=''
test_data="/var/lib/postgresql/data/$(basename "$test_root")"
cleanup() {
  if [[ -n "$test_container" ]]; then docker stop "$test_container" >/dev/null; fi
  if [[ -n "$mail_container" ]]; then docker stop "$mail_container" >/dev/null; fi
  rm -rf "$test_root"
}
trap cleanup EXIT
test_container=$(docker run --rm -d -p 127.0.0.1::5432 \
  -e POSTGRES_HOST_AUTH_METHOD=trust -e PGDATA="$test_data" \
  --mount "type=bind,src=$repo_dir/supabase,dst=/work/supabase,readonly" postgres:16.3)
wait_ready() {
  for ((attempt=0; attempt<150; attempt++)); do
    if docker exec "$test_container" sh -c 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres' >/dev/null 2>&1; then return; fi
    sleep 0.2
  done
  docker logs "$test_container" >&2
  return 1
}
wait_ready
test_port=$(docker port "$test_container" 5432/tcp)
test_port=${test_port##*:}
docker exec "$test_container" psql -X -q -U postgres -d postgres -v ON_ERROR_STOP=1 \
  -c 'SET client_min_messages=warning' -f /work/supabase/tests/auth-stub.sql
"$repo_dir/supabase/migrate.sh" docker exec -i "$test_container" psql -U postgres -d postgres
# Only the freshly created disposable cluster gets a direct worker login.
docker exec "$test_container" psql -X -q -U postgres -d postgres \
  -v ON_ERROR_STOP=1 -c 'ALTER ROLE tover_journal LOGIN'
# A disposable Mailpit receives the SMTP mailer's real submission.
mail_container=$(docker run --rm -d -p 127.0.0.1::1025 -p 127.0.0.1::8025 axllent/mailpit:v1.27.8)
mail_smtp=$(docker port "$mail_container" 1025/tcp)
mail_api=$(docker port "$mail_container" 8025/tcp)
export TOVER_BEL_TEST_SMTP_PORT=${mail_smtp##*:}
export TOVER_BEL_TEST_MAILPIT_URL="http://127.0.0.1:${mail_api##*:}"
export TOVER_BEL_TEST_URL="jdbc:postgresql://127.0.0.1:$test_port/postgres"
export TOVER_BEL_TEST_STATE="$test_root/receipt.bin"
export TOVER_BEL_TEST_ROOT="$test_root"
export TOVER_BEL_TEST_DATA_DIRECTORY="$test_data"
cd "$repo_dir"
clojure -M:dev:bel-workspace-test
docker restart "$test_container" >/dev/null
wait_ready
test_port=$(docker port "$test_container" 5432/tcp)
test_port=${test_port##*:}
export TOVER_BEL_TEST_URL="jdbc:postgresql://127.0.0.1:$test_port/postgres"
TOVER_BEL_RESTART_CHECK=1 clojure -M:dev:bel-workspace-test
