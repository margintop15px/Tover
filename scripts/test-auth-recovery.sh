#!/usr/bin/env bash
set -euo pipefail
# All services and messages are disposable and local. Never reads .env or uses a saved project.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
cd "$repo_dir"
stack="tover-auth-test-$$"
export TOVER_LOCAL_AUTH=1
export NEXT_PUBLIC_SUPABASE_URL=http://127.0.0.1:3421
export NEXT_PUBLIC_SITE_URL=http://127.0.0.1:3420
export TOVER_TEST_JWT_SECRET=local-auth-recovery-tests-only-never-use-in-production-123456789
export NEXT_PUBLIC_SUPABASE_ANON_KEY=$(node -e 'const c=require("crypto");const e=o=>Buffer.from(JSON.stringify(o)).toString("base64url");const p=e({alg:"HS256",typ:"JWT"})+"."+e({role:"anon",iss:"supabase",exp:Math.floor(Date.now()/1000)+7200});process.stdout.write(p+"."+c.createHmac("sha256",process.env.TOVER_TEST_JWT_SECRET).update(p).digest("base64url"))')
export SUPABASE_SERVICE_ROLE_KEY=$(node -e 'const c=require("crypto");const e=o=>Buffer.from(JSON.stringify(o)).toString("base64url");const p=e({alg:"HS256",typ:"JWT"})+"."+e({role:"service_role",iss:"supabase",exp:Math.floor(Date.now()/1000)+7200});process.stdout.write(p+"."+c.createHmac("sha256",process.env.TOVER_TEST_JWT_SECRET).update(p).digest("base64url"))')
export SENTRY_AUTH_TOKEN= SENTRY_DSN= NEXT_PUBLIC_SENTRY_DSN= OPENAI_API_KEY=
cleanup() {
  local result=$?
  if ((result != 0)); then
    docker logs "$stack-auth" 2>&1 | rg '"level":"(error|fatal)"' || true
  fi
  docker rm -f "$stack-rest" "$stack-auth" "$stack-mail" "$stack-db" >/dev/null 2>&1 || true
  docker network rm "$stack" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# Ports are fixed so browser and Next.js config agree; Docker fails if any are in use.
docker network create "$stack" >/dev/null
docker run --rm -d --name "$stack-db" --network "$stack" --network-alias db \
  -e POSTGRES_PASSWORD=local-test-only \
  --mount "type=bind,src=$repo_dir/supabase,dst=/work/supabase,readonly" postgres:16-alpine >/dev/null
for ((attempt=0; attempt<60; attempt++)); do
  if docker exec "$stack-db" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; then break; fi
  sleep 0.2
done
docker exec -i "$stack-db" psql -X -U postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
CREATE ROLE anon NOLOGIN;
CREATE ROLE authenticated NOLOGIN;
CREATE ROLE service_role NOLOGIN BYPASSRLS;
CREATE ROLE authenticator LOGIN NOINHERIT PASSWORD 'local-test-only';
GRANT anon, authenticated, service_role TO authenticator;
CREATE SCHEMA auth;
SQL
docker run --rm -d --name "$stack-mail" --network "$stack" --network-alias mail \
  -p 127.0.0.1:3424:8025 public.ecr.aws/supabase/mailpit:v1.30.2 >/dev/null
docker run --rm -d --name "$stack-auth" --network "$stack" -p 127.0.0.1:3422:9999 \
  -e GOTRUE_API_HOST=0.0.0.0 -e GOTRUE_API_PORT=9999 \
  -e API_EXTERNAL_URL=http://127.0.0.1:3421/auth/v1 \
  -e GOTRUE_SITE_URL=http://127.0.0.1:3420 -e 'GOTRUE_URI_ALLOW_LIST=http://127.0.0.1:3420/**' \
  -e GOTRUE_DB_DRIVER=postgres -e GOTRUE_DB_NAMESPACE=auth \
  -e 'GOTRUE_DB_DATABASE_URL=postgres://postgres:local-test-only@db:5432/postgres?sslmode=disable&search_path=auth,public' \
  -e GOTRUE_JWT_SECRET="$TOVER_TEST_JWT_SECRET" -e GOTRUE_JWT_AUD=authenticated \
  -e GOTRUE_JWT_ADMIN_ROLES=service_role -e GOTRUE_JWT_DEFAULT_GROUP_NAME=authenticated \
  -e GOTRUE_EXTERNAL_EMAIL_ENABLED=true -e GOTRUE_MAILER_AUTOCONFIRM=false \
  -e GOTRUE_SMTP_HOST=mail -e GOTRUE_SMTP_PORT=1025 -e GOTRUE_SMTP_ADMIN_EMAIL=noreply@tover.test \
  -e GOTRUE_SMTP_SENDER_NAME=Tover -e GOTRUE_SMTP_MAX_FREQUENCY=1s -e GOTRUE_RATE_LIMIT_EMAIL_SENT=1000 \
  -e GOTRUE_MAILER_URLPATHS_INVITE=/auth/v1/verify -e GOTRUE_MAILER_URLPATHS_RECOVERY=/auth/v1/verify \
  -e GOTRUE_MAILER_URLPATHS_CONFIRMATION=/auth/v1/verify -e GOTRUE_MAILER_URLPATHS_EMAIL_CHANGE=/auth/v1/verify \
  public.ecr.aws/supabase/gotrue:v2.196.0 >/dev/null
for ((attempt=0; attempt<120; attempt++)); do
  if curl --max-time 1 -fsS http://127.0.0.1:3422/health >/dev/null 2>&1; then break; fi
  if ((attempt==119)); then docker logs "$stack-auth"; exit 1; fi
  sleep 0.25
done
docker exec -i "$stack-db" psql -X -U postgres -v ON_ERROR_STOP=1 >/dev/null <<'SQL'
GRANT USAGE ON SCHEMA auth TO anon, authenticated, service_role;
\i /work/supabase/migrations/001_initial_schema.sql
\i /work/supabase/migrations/002_auth_orgs_rbac.sql
\i /work/supabase/migrations/003_inventory_system.sql
\i /work/supabase/migrations/004_report_functions.sql
\i /work/supabase/migrations/005_workspace_settings.sql
\i /work/supabase/migrations/029_secure_workspace_invitations.sql
\i /work/supabase/migrations/032_team_invitation_recovery.sql
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO authenticated, service_role;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO authenticated, service_role;
SQL
docker run --rm -d --name "$stack-rest" --network "$stack" -p 127.0.0.1:3423:3000 \
  -e 'PGRST_DB_URI=postgres://authenticator:local-test-only@db:5432/postgres' \
  -e PGRST_DB_SCHEMAS=public -e PGRST_DB_ANON_ROLE=anon -e PGRST_JWT_SECRET="$TOVER_TEST_JWT_SECRET" \
  public.ecr.aws/supabase/postgrest:v16.2 >/dev/null
node_modules/.bin/playwright test --config playwright.recovery.config.ts "$@"
