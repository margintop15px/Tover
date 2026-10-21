#!/usr/bin/env bash
set -euo pipefail
profile_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(cd "$profile_dir/../.." && pwd)
compose=(docker compose --project-directory "$profile_dir" --env-file "$profile_dir/.env" -f "$profile_dir/compose.yaml")
case "${1:-}" in
  init)
    # Refuse overwriting any existing local credentials.
    python3 - "$profile_dir/.env" <<'PY'
import base64, hashlib, hmac, json, os, secrets, sys, time
path=sys.argv[1]
secret=secrets.token_urlsafe(48)
def b64(value): return base64.urlsafe_b64encode(value).decode().rstrip('=')
def jwt(role):
    header=b64(json.dumps({'alg':'HS256','typ':'JWT'},separators=(',',':')).encode())
    body=b64(json.dumps({'role':role,'iss':'supabase','iat':int(time.time()),'exp':int(time.time())+315360000},separators=(',',':')).encode())
    value=f'{header}.{body}'
    return value+'.'+b64(hmac.new(secret.encode(),value.encode(),hashlib.sha256).digest())
values={'POSTGRES_PASSWORD':secrets.token_urlsafe(32),'TO_DEV_APP_PASSWORD':secrets.token_urlsafe(32),
        'JWT_SECRET':secret,'ANON_KEY':jwt('anon'),'SERVICE_ROLE_KEY':jwt('service_role'),
        'SESSION_ENCRYPTION_KEY':base64.b64encode(secrets.token_bytes(32)).decode()}
fd=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
with os.fdopen(fd,'w') as output:
    for key,value in values.items(): output.write(f'{key}={value}\n')
print('Created private local credentials in deployment/local-identity/.env')
PY
    ;;
  up)
    "${compose[@]}" up -d --wait --wait-timeout 180 db mail
    "${compose[@]}" exec -T db bash -s < "$profile_dir/db-roles.sh"
    "${compose[@]}" up -d --wait --wait-timeout 180
    "${compose[@]}" exec -T db psql -X -q -U postgres -d postgres -v ON_ERROR_STOP=1 \
      < "$repo_dir/supabase/migrations/033_identity_and_sessions.sql"
    # The migration never includes a password; configure this local role separately.
    "${compose[@]}" exec -T db sh -c \
      'psql -X -q -U postgres -d postgres -v ON_ERROR_STOP=1 --set=app_password="$TO_DEV_APP_PASSWORD"' <<'SQL'
ALTER ROLE tover_app WITH PASSWORD :'app_password';
SQL
    printf 'Local identity profile is ready. Run identity.sh app-env to configure the application.\n'
    ;;
  app-env)
    python3 - "$profile_dir/.env" "$profile_dir/.env.app.local" <<'PYENV'
import os, shlex, sys
with open(sys.argv[1]) as source:
    values=dict(line.strip().split('=',1) for line in source if line.strip() and not line.startswith('#'))
for key in ('IDENTITY_API_PORT','IDENTITY_DB_PORT','TO_DEV_SITE_URL'):
    if key in os.environ: values[key]=os.environ[key]
api=values.get('IDENTITY_API_PORT','58000')
configuration={
    'TOVER_AUTH_URL':f'http://127.0.0.1:{api}/auth/v1',
    'TOVER_REST_URL':f'http://127.0.0.1:{api}/rest/v1',
    'TOVER_API_KEY':values['ANON_KEY'],'TOVER_AUTH_ADMIN_KEY':values['SERVICE_ROLE_KEY'],
    'TOVER_ORIGIN':values.get('TO_DEV_SITE_URL','http://localhost:3030'),
    'TOVER_JDBC_URL':f"jdbc:postgresql://127.0.0.1:{values.get('IDENTITY_DB_PORT','55432')}/postgres",
    'TOVER_DB_USER':'tover_app','TOVER_DB_PASSWORD':values['TO_DEV_APP_PASSWORD'],
    'TOVER_SESSION_KEY':values['SESSION_ENCRYPTION_KEY']}
fd=os.open(sys.argv[2],os.O_CREAT|os.O_TRUNC|os.O_WRONLY,0o600)
os.fchmod(fd,0o600)
with os.fdopen(fd,'w') as output:
    for key,value in configuration.items(): output.write(f'export {key}={shlex.quote(value)}\n')
print('Wrote private application environment to deployment/local-identity/.env.app.local')
PYENV
    ;;
  stop) "${compose[@]}" stop ;;
  logs) "${compose[@]}" logs --tail=100 "${@:2}" ;;
  *) printf 'Usage: %s init|up|app-env|stop|logs [service]\n' "$0" >&2; exit 2 ;;
esac
