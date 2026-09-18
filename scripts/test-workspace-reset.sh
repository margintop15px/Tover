#!/usr/bin/env bash
set -euo pipefail

# No .env, published ports, or existing database: only disposable synthetic data.
repo_dir=$(cd "$(dirname "$0")/.." && pwd)
test_container=$(docker run --rm -d --network none \
  -e POSTGRES_HOST_AUTH_METHOD=trust \
  --mount "type=bind,src=$repo_dir/supabase,dst=/work/supabase,readonly" \
  postgres:16-alpine -c listen_addresses='')
trap 'docker stop "$test_container" >/dev/null' EXIT
for ((attempt = 0; attempt < 50; attempt++)); do
  if docker exec "$test_container" pg_isready -U postgres >/dev/null 2>&1; then
    break
  fi
  sleep 0.2
done
docker exec "$test_container" psql -X -U postgres -d postgres \
  -v ON_ERROR_STOP=1 -f /work/supabase/tests/workspace-reset-performance.sql
