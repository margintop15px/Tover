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
docker exec "$test_container" createdb -U postgres fresh
docker exec "$test_container" psql -X -q -U postgres -d fresh -v ON_ERROR_STOP=1 -f /work/supabase/tests/auth-stub.sql
# The ledger applies each migration once; a second run is a no-op.
"$repo_dir/supabase/migrate.sh" docker exec -i "$test_container" psql -U postgres -d fresh >/dev/null
[[ -z "$("$repo_dir/supabase/migrate.sh" docker exec -i "$test_container" psql -U postgres -d fresh)" ]]
docker exec "$test_container" psql -X -q -U postgres -d fresh \
  -c "SET client_min_messages=warning" -v ON_ERROR_STOP=1 -v VERBOSITY=terse -f /work/supabase/tests/workspace-invitation-security.sql

# Hold the manager's cancellation open before the recipient accepts. Acceptance re-reads the locked offer
# after the cancel commits, so the membership policy finds no accepted offer and grants nothing.
docker exec "$test_container" psql -X -q -U postgres -d fresh -v ON_ERROR_STOP=1 -c \
  "INSERT INTO auth.users(id,email,email_confirmed_at) VALUES ('20000000-0000-0000-0000-000000000008','cancel-race@example.test',now()); INSERT INTO public.invitations(invitation_id,workspace_id,email,role) VALUES ('40000000-0000-0000-0000-000000000009','10000000-0000-0000-0000-000000000001','cancel-race@example.test','member'); INSERT INTO app_private.sessions(id,session_id,user_id,data) VALUES ('key-race','30000000-0000-0000-0000-000000000008','20000000-0000-0000-0000-000000000008','{}');"
cancel="BEGIN; SET LOCAL ROLE tover_app; SET LOCAL request.jwt.claim.sub='20000000-0000-0000-0000-000000000005'; SET LOCAL tover.session_key='key-manager'; UPDATE public.invitations SET status='revoked' WHERE invitation_id='40000000-0000-0000-0000-000000000009' AND status='pending'; SELECT pg_sleep(2); COMMIT;"
accept="BEGIN; SET LOCAL ROLE tover_app; SET LOCAL request.jwt.claim.sub='20000000-0000-0000-0000-000000000008'; SET LOCAL tover.session_key='key-race'; UPDATE public.invitations SET status='accepted' WHERE invitation_id='40000000-0000-0000-0000-000000000009' AND status='pending'; INSERT INTO public.memberships(workspace_id,user_id,role,status) VALUES ('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000008','member','active'); COMMIT;"
docker exec -e PGAPPNAME=tover-cancel-test "$test_container" psql -X -qAt -U postgres -d fresh -v ON_ERROR_STOP=1 -c "$cancel" >/dev/null &
first_pid=$!
for ((attempt=0; attempt<50; attempt++)); do
  sleeping=$(docker exec "$test_container" psql -X -qAt -U postgres -d fresh -c "SELECT count(*) FROM pg_stat_activity WHERE application_name='tover-cancel-test' AND wait_event='PgSleep'")
  if [[ "$sleeping" == 1 ]]; then break; fi
  sleep 0.02
done
[[ "$sleeping" == 1 ]]
# The acceptance must fail on the membership policy; its error is the expected outcome.
if accepted=$(docker exec -e PGAPPNAME=tover-accept-test "$test_container" psql -X -qAt -U postgres -d fresh -v ON_ERROR_STOP=1 -c "$accept" 2>&1); then
  echo 'FAIL: acceptance overlapping a cancellation committed' >&2
  exit 1
fi
[[ "$accepted" == *"row-level security"* ]] || { echo "FAIL: unexpected acceptance error: $accepted" >&2; exit 1; }
wait "$first_pid"
docker exec "$test_container" psql -X -q -U postgres -d fresh -v ON_ERROR_STOP=1 -c \
  "DO \$\$ BEGIN ASSERT NOT EXISTS (SELECT FROM public.memberships WHERE user_id='20000000-0000-0000-0000-000000000008'), 'concurrent cancellation granted membership'; ASSERT (SELECT status FROM public.invitations WHERE invitation_id='40000000-0000-0000-0000-000000000009')='revoked'; END; \$\$;"
printf 'PASS: baseline schema and overlapping cancellation/acceptance\n'

# The baseline installs only into an empty database; legacy databases are swapped, not upgraded.
docker exec "$test_container" createdb -U postgres legacy
docker exec "$test_container" psql -X -q -U postgres -d legacy -v ON_ERROR_STOP=1 \
  -f /work/supabase/tests/auth-stub.sql -c 'CREATE TABLE public.organizations(id uuid PRIMARY KEY);'
if "$repo_dir/supabase/migrate.sh" docker exec -i "$test_container" psql -U postgres -d legacy >/dev/null 2>&1; then
echo 'FAIL: baseline installed over a legacy schema' >&2
exit 1
fi
printf 'PASS: legacy schema rejected\n'
