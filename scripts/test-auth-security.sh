#!/usr/bin/env bash
set -euo pipefail

# Requires Docker. Disposable cluster only: no .env, published ports or existing DB.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
test_container=$(docker run --rm -d --network none \
  -e POSTGRES_HOST_AUTH_METHOD=trust \
  --mount "type=bind,src=$repo_dir/supabase,dst=/work/supabase,readonly" \
  postgres:17.6-alpine3.22 -c listen_addresses='')
test_output=$(mktemp -d)
cleanup() { docker stop "$test_container" >/dev/null; rm -rf "$test_output"; }
trap cleanup EXIT
for ((attempt = 0; attempt < 300; attempt++)); do
  if docker exec "$test_container" sh -c 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres'  >/dev/null 2>&1; then break; fi
  sleep 0.2
done
if ! docker exec "$test_container" sh -c 'test "$(cat /proc/1/comm)" = postgres && pg_isready -U postgres'  >/dev/null 2>&1; then
  docker logs "$test_container" >&2
  exit 1
fi
for scenario in fresh upgrade; do
  legacy=0
  if [[ "$scenario" == upgrade ]]; then legacy=1; fi
  docker exec "$test_container" createdb -U postgres "$scenario"
  docker exec "$test_container" psql -X -q -U postgres -d "$scenario" \
    -c "SET client_min_messages=warning" -v ON_ERROR_STOP=1 -v VERBOSITY=terse -v legacy="$legacy" -f /work/supabase/tests/workspace-invitation-security.sql
  docker exec "$test_container" psql -X -q -U postgres -d "$scenario" \
    -c "SET client_min_messages=warning" -v ON_ERROR_STOP=1 -v VERBOSITY=terse -f /work/supabase/tests/workspace-actor-labels-security.sql

  # Hold the manager's cancellation open before the recipient accepts. Acceptance re-reads the locked offer
  # after the cancel commits, so the membership policy finds no accepted offer and grants nothing.
  docker exec "$test_container" psql -X -q -U postgres -d "$scenario" -v ON_ERROR_STOP=1 -c \
    "INSERT INTO auth.users(id,email,email_confirmed_at) VALUES ('20000000-0000-0000-0000-000000000008','cancel-race@example.test',now()); INSERT INTO public.organization_invites(id,organization_id,email,role_id) VALUES ('40000000-0000-0000-0000-000000000009','10000000-0000-0000-0000-000000000001','cancel-race@example.test','member'); INSERT INTO app_private.sessions(id,session_id,user_id,data) VALUES ('key-race','30000000-0000-0000-0000-000000000008','20000000-0000-0000-0000-000000000008','{}');"
  cancel="BEGIN; SET LOCAL ROLE tover_app; SET LOCAL request.jwt.claim.sub='20000000-0000-0000-0000-000000000005'; SET LOCAL tover.session_key='key-manager'; UPDATE public.organization_invites SET status='revoked' WHERE id='40000000-0000-0000-0000-000000000009' AND status='pending'; SELECT pg_sleep(2); COMMIT;"
  accept="BEGIN; SET LOCAL ROLE tover_app; SET LOCAL request.jwt.claim.sub='20000000-0000-0000-0000-000000000008'; SET LOCAL tover.session_key='key-race'; UPDATE public.organization_invites SET status='accepted' WHERE id='40000000-0000-0000-0000-000000000009' AND status='pending'; INSERT INTO public.organization_memberships(organization_id,user_id,role_id,status) VALUES ('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000008','member','active'); COMMIT;"
  docker exec -e PGAPPNAME=tover-cancel-test "$test_container" psql -X -qAt -U postgres -d "$scenario" -v ON_ERROR_STOP=1 -c "$cancel" >/dev/null &
  first_pid=$!
  for ((attempt=0; attempt<50; attempt++)); do
    sleeping=$(docker exec "$test_container" psql -X -qAt -U postgres -d "$scenario" -c "SELECT count(*) FROM pg_stat_activity WHERE application_name='tover-cancel-test' AND wait_event='PgSleep'")
    if [[ "$sleeping" == 1 ]]; then break; fi
    sleep 0.02
  done
  [[ "$sleeping" == 1 ]]
  # The acceptance must fail on the membership policy; its error is the expected outcome.
  if accepted=$(docker exec -e PGAPPNAME=tover-accept-test "$test_container" psql -X -qAt -U postgres -d "$scenario" -v ON_ERROR_STOP=1 -c "$accept" 2>&1); then
    echo 'FAIL: acceptance overlapping a cancellation committed' >&2
    exit 1
  fi
  [[ "$accepted" == *"row-level security"* ]] || { echo "FAIL: unexpected acceptance error: $accepted" >&2; exit 1; }
  wait "$first_pid"
  docker exec "$test_container" psql -X -q -U postgres -d "$scenario" -v ON_ERROR_STOP=1 -c \
    "DO \$\$ BEGIN ASSERT NOT EXISTS (SELECT FROM public.organization_memberships WHERE user_id='20000000-0000-0000-0000-000000000008'), 'concurrent cancellation granted membership'; ASSERT (SELECT status FROM public.organization_invites WHERE id='40000000-0000-0000-0000-000000000009')='revoked'; END; \$\$;"
  docker exec "$test_container" psql -X -qAt -U postgres -d "$scenario" -v ON_ERROR_STOP=1 \
    -f /work/supabase/tests/identity-contract-snapshot.sql > "$test_output/$scenario.json"
  printf 'PASS: %s schema and overlapping cancellation/acceptance\n' "$scenario"
done

if ! cmp -s "$test_output/fresh.json" "$test_output/upgrade.json"; then
  echo 'FAIL: fresh and upgrade identity schema/permission contracts differ' >&2
  diff -u "$test_output/fresh.json" "$test_output/upgrade.json" >&2 || true
  exit 1
fi
printf 'PASS: fresh and upgrade schema, functions, RLS and grants match\n'

# A partial schema must fail before changing anything.
docker exec "$test_container" createdb -U postgres partial
docker exec "$test_container" psql -X -q -U postgres -d partial -v ON_ERROR_STOP=1 \
  -f /work/supabase/tests/auth-stub.sql -c 'CREATE TABLE public.profiles(user_id uuid PRIMARY KEY);'
if docker exec "$test_container" psql -X -q -U postgres -d partial -v ON_ERROR_STOP=1 \
  -f /work/supabase/migrations/033_identity_and_sessions.sql >/dev/null 2>&1; then
  echo 'FAIL: partial identity schema was accepted' >&2
  exit 1
fi
printf 'PASS: incompatible partial schema rejected\n'

# A complete identity schema with a missing integrity constraint is incompatible.
docker exec "$test_container" psql -X -q -U postgres -d fresh -v ON_ERROR_STOP=1 -c \
  'ALTER TABLE public.organization_memberships DROP CONSTRAINT organization_memberships_organization_id_user_id_key;'
if docker exec "$test_container" psql -X -q -U postgres -d fresh -v ON_ERROR_STOP=1 \
  -f /work/supabase/migrations/033_identity_and_sessions.sql >/dev/null 2>&1; then
  echo 'FAIL: identity schema missing membership uniqueness was accepted' >&2
  exit 1
fi
printf 'PASS: incompatible identity constraints rejected\n'
