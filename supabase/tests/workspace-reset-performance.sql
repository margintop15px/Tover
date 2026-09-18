-- Run via bash scripts/test-workspace-reset.sh; synthetic data only.
\set QUIET 1
SET client_min_messages = warning;
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role BYPASSRLS;
CREATE SCHEMA auth;
CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text,
  email_confirmed_at timestamptz,
  raw_user_meta_data jsonb DEFAULT '{}'::jsonb
);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;
CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT current_setting('request.jwt.claim.role', true);
$$;
GRANT USAGE ON SCHEMA auth TO authenticated, anon;

-- Use the real schema, including the later sync-step cascade/protection triggers.
BEGIN;
\ir ../migrations/001_initial_schema.sql
\ir ../migrations/002_auth_orgs_rbac.sql
\ir ../migrations/003_inventory_system.sql
\ir ../migrations/004_report_functions.sql
\ir ../migrations/005_workspace_settings.sql
\ir ../migrations/006_product_name_unique.sql
\ir ../migrations/007_inventory_adjustment_operation.sql
\ir ../migrations/008_operation_imports.sql
\ir ../migrations/009_master_data_import_defaults.sql
\ir ../migrations/010_remove_product_import_default.sql
\ir ../migrations/011_operation_reporting_ledger.sql
\ir ../migrations/012_ozon_marketplace_integration.sql
\ir ../migrations/013_ozon_candidate_approval_status.sql
\ir ../migrations/014_ozon_domain_expansion.sql
\ir ../migrations/015_ozon_commit_hardening.sql
\ir ../migrations/016_workspace_data_reset.sql
\ir ../migrations/017_drop_product_name_unique.sql
\ir ../migrations/018_operation_import_targeted_reprocess.sql
\ir ../migrations/019_operation_import_partial_commit.sql
\ir ../migrations/020_ozon_sync_recovery.sql
\ir ../migrations/021_ozon_sync_checkpoints_observability.sql
\ir ../migrations/022_ozon_evidence_accounting_correctness.sql
\ir ../migrations/023_ozon_repair_retry_scheduled_steps.sql
\ir ../migrations/024_ozon_live_contract_fixes.sql
\ir ../migrations/025_ozon_relevant_warehouse_identity.sql
\ir ../migrations/026_optimize_ozon_relevant_warehouse_counts.sql
\ir ../migrations/027_ozon_retry_accounting.sql
\ir ../migrations/028_ozon_automatic_incremental_sync.sql
\ir ../migrations/029_secure_workspace_invitations.sql
\ir ../migrations/030_restore_inventory_adjustment_type.sql
COMMIT;

INSERT INTO auth.users (id, email) VALUES
  ('20000000-0000-0000-0000-000000000001', 'owner@example.test');
INSERT INTO public.organizations (id, name) VALUES
  ('10000000-0000-0000-0000-000000000001', 'Reset target'),
  ('10000000-0000-0000-0000-000000000002', 'Other workspace');
INSERT INTO public.organization_memberships (organization_id, user_id, role_id)
VALUES ('10000000-0000-0000-0000-000000000001',
  '20000000-0000-0000-0000-000000000001', 'owner');
INSERT INTO public.workspace_settings (workspace_id, currency)
SELECT id, 'RUB' FROM public.organizations;
INSERT INTO public.marketplace_connections (
  workspace_id, provider, credential_ciphertext, status, last_sync_status
) SELECT id, 'ozon', '{"test":"preserve"}', 'connected', 'completed'
FROM public.organizations;
INSERT INTO public.products (workspace_id, name)
SELECT o.id, 'Product ' || n FROM public.organizations o, generate_series(1, 20) n;
INSERT INTO public.warehouses (workspace_id, name)
SELECT o.id, 'Warehouse ' || n FROM public.organizations o, generate_series(1, 5) n;
INSERT INTO public.operations (workspace_id, type, operation_date)
SELECT o.id, 'purchase', current_date FROM public.organizations o, generate_series(1, 20) n;
INSERT INTO public.operation_imports (workspace_id, file_name, file_type, file_hash)
SELECT id, 'test.csv', 'csv', 'synthetic' FROM public.organizations;
INSERT INTO public.operation_import_candidates (
  workspace_id, import_id, row_index, fingerprint, raw
) SELECT workspace_id, id, n, n::text, jsonb_build_object('padding', repeat('x', 1000))
FROM public.operation_imports, generate_series(1, 6000) n
WHERE n <= CASE WHEN workspace_id = '10000000-0000-0000-0000-000000000001'
  THEN 400 ELSE 6000 END;
INSERT INTO public.ozon_postings (workspace_id, connection_id, posting_schema, posting_number)
SELECT workspace_id, id, 'fbs', n::text
FROM public.marketplace_connections, generate_series(1, 4000) n
WHERE n <= CASE WHEN workspace_id = '10000000-0000-0000-0000-000000000001'
  THEN 1000 ELSE 4000 END;
INSERT INTO public.ozon_posting_items (workspace_id, connection_id, posting_id)
SELECT workspace_id, connection_id, id FROM public.ozon_postings;
INSERT INTO public.ozon_stock_snapshots (workspace_id, connection_id, raw_payload)
SELECT workspace_id, id, jsonb_build_object('padding', repeat('x', 200))
FROM public.marketplace_connections, generate_series(1, 120000) n
WHERE n <= CASE WHEN workspace_id = '10000000-0000-0000-0000-000000000001'
  THEN 800 ELSE 120000 END;
INSERT INTO public.marketplace_sync_runs (workspace_id, connection_id, provider, status)
SELECT workspace_id, id, 'ozon', 'completed' FROM public.marketplace_connections;
INSERT INTO public.marketplace_sync_run_steps (
  run_id, workspace_id, connection_id, provider, step_key, step_order
) SELECT id, workspace_id, connection_id, provider, 'products', 2
FROM public.marketplace_sync_runs;
ANALYZE;

SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000001';
\echo Before migration (rolled back):
BEGIN;
SET ROLE authenticated;
\timing on
SELECT public.reset_workspace_account_data('10000000-0000-0000-0000-000000000001', 'RESET') AS baseline \gset
\timing off
ROLLBACK;

\ir ../migrations/031_workspace_reset_performance.sql
DO $$ BEGIN
  ASSERT (SELECT proconfig @> ARRAY['statement_timeout=60s'] FROM pg_proc
    WHERE oid = 'public.reset_workspace_account_data(uuid,text)'::regprocedure),
    'PostgREST must see the reset-specific timeout';
END; $$;

SET ROLE authenticated;
DO $$ BEGIN
  BEGIN
    PERFORM public.reset_workspace_account_data('10000000-0000-0000-0000-000000000001', 'wrong');
    RAISE EXCEPTION 'confirmation check missing';
  EXCEPTION WHEN invalid_parameter_value THEN NULL;
  END;
  BEGIN
    PERFORM public.reset_workspace_account_data('10000000-0000-0000-0000-000000000002', 'RESET');
    RAISE EXCEPTION 'owner check missing';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END; $$;

-- Direct SQL starts its timer before entering the function; the indexes must
-- make this fixture fit even within the original request budget.
SET statement_timeout = '8s';
\echo After migration:
\timing on
DO $$
DECLARE result jsonb;
BEGIN
  result := public.reset_workspace_account_data('10000000-0000-0000-0000-000000000001', 'RESET');
  ASSERT (result #>> '{deleted,operation_import_candidates}')::int = 400;
  ASSERT (result #>> '{deleted,ozon_postings}')::int = 1000;
  ASSERT (result #>> '{deleted,ozon_posting_items}')::int = 1000;
  ASSERT (result #>> '{deleted,ozon_stock_snapshots}')::int = 800;
  ASSERT (result #>> '{deleted,products}')::int = 20;
END; $$;
\timing off
RESET ROLE;
RESET statement_timeout;
DO $$ BEGIN
  ASSERT (SELECT count(*) = 6000 FROM public.operation_import_candidates);
  ASSERT (SELECT count(*) = 4000 FROM public.ozon_postings);
  ASSERT (SELECT count(*) = 4000 FROM public.ozon_posting_items);
  ASSERT (SELECT count(*) = 120000 FROM public.ozon_stock_snapshots);
  ASSERT (SELECT count(*) = 20 FROM public.products);
  ASSERT (SELECT count(*) = 20 FROM public.operations);
  ASSERT (SELECT count(*) = 1 FROM public.marketplace_sync_run_steps);
  ASSERT (SELECT count(*) = 2 FROM public.organizations);
  ASSERT (SELECT count(*) = 1 FROM public.organization_memberships);
  ASSERT (SELECT count(*) = 2 FROM public.workspace_settings WHERE currency = 'RUB');
  ASSERT (SELECT count(*) = 2 FROM public.marketplace_connections
    WHERE credential_ciphertext = '{"test":"preserve"}');
END; $$;
\echo Reset performance, confirmation, owner authorization, workspace isolation, and preservation checks passed.
