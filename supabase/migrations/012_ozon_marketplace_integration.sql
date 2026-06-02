-- Read-only marketplace integration foundation for Ozon Seller API.
-- Credentials stay encrypted in marketplace_connections; synced Ozon data is
-- staged in mirror tables and never mutates core inventory operations directly.

CREATE TABLE IF NOT EXISTS public.marketplace_connections (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('ozon')),
  name TEXT NOT NULL DEFAULT 'Ozon',
  credential_ciphertext JSONB NOT NULL DEFAULT '{}'::jsonb,
  client_id_hint TEXT,
  api_key_hint TEXT,
  status TEXT NOT NULL DEFAULT 'draft' CHECK (
    status IN ('draft', 'connected', 'invalid', 'error', 'disabled')
  ),
  health JSONB NOT NULL DEFAULT '{}'::jsonb,
  last_validated_at TIMESTAMPTZ,
  last_sync_at TIMESTAMPTZ,
  last_sync_status TEXT CHECK (
    last_sync_status IS NULL OR last_sync_status IN ('running', 'completed', 'failed')
  ),
  last_sync_error TEXT,
  created_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, provider)
);

CREATE TABLE IF NOT EXISTS public.marketplace_sync_runs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('ozon')),
  status TEXT NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'completed', 'failed')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  date_from TIMESTAMPTZ,
  date_to TIMESTAMPTZ,
  summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.marketplace_operation_candidates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('ozon')),
  source_type TEXT NOT NULL CHECK (source_type IN ('posting', 'return', 'finance')),
  external_event_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'needs_mapping' CHECK (
    status IN ('needs_mapping', 'ready', 'approved', 'ignored', 'committed')
  ),
  operation_type TEXT CHECK (
    operation_type IS NULL OR operation_type IN ('sale', 'return', 'write_off')
  ),
  operation_date DATE,
  confidence NUMERIC(5,4) NOT NULL DEFAULT 0,
  operation JSONB NOT NULL DEFAULT '{}'::jsonb,
  normalized_operation JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_operation_id UUID REFERENCES public.operations(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, source_type, external_event_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_products (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  ozon_product_id TEXT NOT NULL,
  offer_id TEXT,
  sku TEXT,
  name TEXT,
  currency_code TEXT,
  price NUMERIC(14,4),
  old_price NUMERIC(14,4),
  min_price NUMERIC(14,4),
  status TEXT,
  visibility TEXT,
  description_category_id TEXT,
  type_id TEXT,
  barcodes JSONB NOT NULL DEFAULT '[]'::jsonb,
  images JSONB NOT NULL DEFAULT '[]'::jsonb,
  attributes JSONB NOT NULL DEFAULT '[]'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  mapping_status TEXT NOT NULL DEFAULT 'unmapped' CHECK (
    mapping_status IN ('unmapped', 'auto_matched', 'manual', 'ignored')
  ),
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, ozon_product_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_warehouses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  ozon_warehouse_id TEXT NOT NULL,
  name TEXT NOT NULL,
  fulfillment_schema TEXT,
  status TEXT,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  mapping_status TEXT NOT NULL DEFAULT 'unmapped' CHECK (
    mapping_status IN ('unmapped', 'auto_matched', 'manual', 'ignored')
  ),
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, ozon_warehouse_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_stock_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  snapshot_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  ozon_product_id TEXT,
  offer_id TEXT,
  sku TEXT,
  warehouse_name TEXT,
  ozon_warehouse_id TEXT,
  fulfillment_schema TEXT,
  present NUMERIC(14,3) NOT NULL DEFAULT 0,
  reserved NUMERIC(14,3) NOT NULL DEFAULT 0,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  local_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.ozon_postings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  posting_schema TEXT NOT NULL CHECK (posting_schema IN ('fbs', 'fbo')),
  posting_number TEXT NOT NULL,
  order_id TEXT,
  status TEXT,
  substatus TEXT,
  in_process_at TIMESTAMPTZ,
  shipment_date TIMESTAMPTZ,
  delivered_at TIMESTAMPTZ,
  cancelled_at TIMESTAMPTZ,
  warehouse_name TEXT,
  financial_data JSONB NOT NULL DEFAULT '{}'::jsonb,
  analytics_data JSONB NOT NULL DEFAULT '{}'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, posting_schema, posting_number)
);

CREATE TABLE IF NOT EXISTS public.ozon_posting_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  posting_id UUID NOT NULL REFERENCES public.ozon_postings(id) ON DELETE CASCADE,
  ozon_product_id TEXT,
  offer_id TEXT,
  sku TEXT,
  name TEXT,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
  price NUMERIC(14,4),
  currency_code TEXT,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.ozon_returns (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  ozon_return_id TEXT NOT NULL,
  posting_number TEXT,
  status TEXT,
  return_schema TEXT,
  returned_at TIMESTAMPTZ,
  offer_id TEXT,
  sku TEXT,
  ozon_product_id TEXT,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 1,
  price NUMERIC(14,4),
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, ozon_return_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_finance_transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  transaction_id TEXT NOT NULL,
  operation_type TEXT,
  operation_date TIMESTAMPTZ,
  posting_number TEXT,
  amount NUMERIC(14,4) NOT NULL DEFAULT 0,
  currency_code TEXT,
  items JSONB NOT NULL DEFAULT '[]'::jsonb,
  services JSONB NOT NULL DEFAULT '[]'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, transaction_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_report_runs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  report_type TEXT NOT NULL,
  ozon_report_code TEXT NOT NULL,
  status TEXT,
  request_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  response_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  file_url TEXT,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, ozon_report_code)
);

CREATE INDEX IF NOT EXISTS idx_marketplace_connections_workspace
  ON public.marketplace_connections(workspace_id, provider);
CREATE INDEX IF NOT EXISTS idx_marketplace_sync_runs_connection
  ON public.marketplace_sync_runs(connection_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_marketplace_candidates_workspace_status
  ON public.marketplace_operation_candidates(workspace_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_products_workspace_mapping
  ON public.ozon_products(workspace_id, mapping_status);
CREATE INDEX IF NOT EXISTS idx_ozon_products_offer
  ON public.ozon_products(workspace_id, offer_id) WHERE offer_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ozon_warehouses_workspace_mapping
  ON public.ozon_warehouses(workspace_id, mapping_status);
CREATE INDEX IF NOT EXISTS idx_ozon_stock_snapshots_connection_time
  ON public.ozon_stock_snapshots(connection_id, snapshot_at DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_postings_connection_status
  ON public.ozon_postings(connection_id, status, in_process_at DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_returns_connection_status
  ON public.ozon_returns(connection_id, status, returned_at DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_finance_connection_date
  ON public.ozon_finance_transactions(connection_id, operation_date DESC);

DROP TRIGGER IF EXISTS set_marketplace_connections_updated_at ON public.marketplace_connections;
CREATE TRIGGER set_marketplace_connections_updated_at
BEFORE UPDATE ON public.marketplace_connections
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_marketplace_operation_candidates_updated_at ON public.marketplace_operation_candidates;
CREATE TRIGGER set_marketplace_operation_candidates_updated_at
BEFORE UPDATE ON public.marketplace_operation_candidates
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_products_updated_at ON public.ozon_products;
CREATE TRIGGER set_ozon_products_updated_at
BEFORE UPDATE ON public.ozon_products
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_warehouses_updated_at ON public.ozon_warehouses;
CREATE TRIGGER set_ozon_warehouses_updated_at
BEFORE UPDATE ON public.ozon_warehouses
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_postings_updated_at ON public.ozon_postings;
CREATE TRIGGER set_ozon_postings_updated_at
BEFORE UPDATE ON public.ozon_postings
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_returns_updated_at ON public.ozon_returns;
CREATE TRIGGER set_ozon_returns_updated_at
BEFORE UPDATE ON public.ozon_returns
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_finance_transactions_updated_at ON public.ozon_finance_transactions;
CREATE TRIGGER set_ozon_finance_transactions_updated_at
BEFORE UPDATE ON public.ozon_finance_transactions
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_report_runs_updated_at ON public.ozon_report_runs;
CREATE TRIGGER set_ozon_report_runs_updated_at
BEFORE UPDATE ON public.ozon_report_runs
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.marketplace_connections ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.marketplace_sync_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.marketplace_operation_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_warehouses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_stock_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_postings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_posting_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_returns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_finance_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_report_runs ENABLE ROW LEVEL SECURITY;

CREATE POLICY "marketplace_connections_select_member" ON public.marketplace_connections
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "marketplace_connections_write_admin" ON public.marketplace_connections
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "marketplace_sync_runs_select_member" ON public.marketplace_sync_runs
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "marketplace_sync_runs_write_admin" ON public.marketplace_sync_runs
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "marketplace_candidates_select_member" ON public.marketplace_operation_candidates
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "marketplace_candidates_write_admin" ON public.marketplace_operation_candidates
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_products_select_member" ON public.ozon_products
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_products_write_admin" ON public.ozon_products
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_warehouses_select_member" ON public.ozon_warehouses
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_warehouses_write_admin" ON public.ozon_warehouses
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_stock_snapshots_select_member" ON public.ozon_stock_snapshots
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_stock_snapshots_write_admin" ON public.ozon_stock_snapshots
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_postings_select_member" ON public.ozon_postings
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_postings_write_admin" ON public.ozon_postings
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_posting_items_select_member" ON public.ozon_posting_items
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_posting_items_write_admin" ON public.ozon_posting_items
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_returns_select_member" ON public.ozon_returns
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_returns_write_admin" ON public.ozon_returns
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_finance_select_member" ON public.ozon_finance_transactions
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_finance_write_admin" ON public.ozon_finance_transactions
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_report_runs_select_member" ON public.ozon_report_runs
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_report_runs_write_admin" ON public.ozon_report_runs
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));
