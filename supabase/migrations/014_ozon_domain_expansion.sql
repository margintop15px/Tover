-- Expand read-only Ozon mirrors and candidate sources beyond postings/returns.

ALTER TABLE public.marketplace_operation_candidates
  DROP CONSTRAINT IF EXISTS marketplace_operation_candidates_source_type_check;

ALTER TABLE public.marketplace_operation_candidates
  ADD CONSTRAINT marketplace_operation_candidates_source_type_check
  CHECK (source_type IN (
    'posting',
    'return',
    'finance',
    'legal_entity_sale',
    'removal',
    'supply',
    'stock_reconciliation',
    'discounted_product',
    'report'
  ));

ALTER TABLE public.marketplace_operation_candidates
  DROP CONSTRAINT IF EXISTS marketplace_operation_candidates_operation_type_check;

ALTER TABLE public.marketplace_operation_candidates
  ADD CONSTRAINT marketplace_operation_candidates_operation_type_check
  CHECK (
    operation_type IS NULL OR operation_type IN (
      'purchase',
      'sale',
      'return',
      'write_off',
      'transfer',
      'production',
      'defect',
      'payment',
      'inventory_adjustment'
    )
  );

CREATE TABLE IF NOT EXISTS public.ozon_legal_entity_sales (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  invoice_number TEXT,
  invoice_date DATE,
  posting_number TEXT,
  buyer_company_name TEXT,
  buyer_inn TEXT,
  buyer_kpp TEXT,
  amount NUMERIC(14,4),
  currency_code TEXT,
  products JSONB NOT NULL DEFAULT '[]'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_unpaid_legal_products (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  posting_number TEXT,
  ozon_product_id TEXT,
  offer_id TEXT,
  sku TEXT,
  name TEXT,
  quantity NUMERIC(14,3),
  amount NUMERIC(14,4),
  currency_code TEXT,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_finance_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  report_type TEXT NOT NULL,
  period_start DATE,
  period_end DATE,
  status TEXT,
  ozon_report_code TEXT,
  file_url TEXT,
  amount NUMERIC(14,4),
  currency_code TEXT,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_removals (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  removal_type TEXT NOT NULL CHECK (removal_type IN ('from_stock', 'from_supply')),
  status TEXT,
  reason TEXT,
  event_date TIMESTAMPTZ,
  posting_number TEXT,
  ozon_product_id TEXT,
  offer_id TEXT,
  sku TEXT,
  name TEXT,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
  warehouse_name TEXT,
  ozon_warehouse_id TEXT,
  amount NUMERIC(14,4),
  currency_code TEXT,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  local_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_supply_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  ozon_supply_order_id TEXT NOT NULL,
  order_number TEXT,
  state TEXT,
  created_at_ozon TIMESTAMPTZ,
  warehouse_name TEXT,
  ozon_warehouse_id TEXT,
  bundle_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_destination_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, ozon_supply_order_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_supply_order_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  supply_order_id UUID NOT NULL REFERENCES public.ozon_supply_orders(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  ozon_product_id TEXT,
  offer_id TEXT,
  sku TEXT,
  name TEXT,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(supply_order_id, external_id)
);

CREATE TABLE IF NOT EXISTS public.ozon_stock_analytics (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  snapshot_date DATE NOT NULL,
  ozon_product_id TEXT,
  offer_id TEXT,
  sku TEXT,
  name TEXT,
  warehouse_name TEXT,
  ozon_warehouse_id TEXT,
  cluster_id TEXT,
  stock NUMERIC(14,3) NOT NULL DEFAULT 0,
  available_stock NUMERIC(14,3),
  reserved_stock NUMERIC(14,3),
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  local_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id, snapshot_date)
);

CREATE TABLE IF NOT EXISTS public.ozon_turnover_analytics (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  snapshot_date DATE NOT NULL,
  ozon_product_id TEXT,
  sku TEXT,
  name TEXT,
  current_stock NUMERIC(14,3),
  ads NUMERIC(14,4),
  days_to_stock_out NUMERIC(14,4),
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id, snapshot_date)
);

CREATE TABLE IF NOT EXISTS public.ozon_discounted_products (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  external_id TEXT NOT NULL,
  ozon_product_id TEXT,
  discounted_sku TEXT,
  sku TEXT,
  offer_id TEXT,
  name TEXT,
  status TEXT,
  reason TEXT,
  quantity NUMERIC(14,3),
  discount_percent NUMERIC(10,4),
  raw_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  local_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  local_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL,
  operation_candidate_id UUID REFERENCES public.marketplace_operation_candidates(id) ON DELETE SET NULL,
  synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(connection_id, external_id)
);

CREATE INDEX IF NOT EXISTS idx_ozon_legal_entity_sales_connection_date
  ON public.ozon_legal_entity_sales(connection_id, invoice_date DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_unpaid_legal_products_connection
  ON public.ozon_unpaid_legal_products(connection_id, posting_number);
CREATE INDEX IF NOT EXISTS idx_ozon_finance_reports_connection_type
  ON public.ozon_finance_reports(connection_id, report_type, period_start DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_removals_connection_date
  ON public.ozon_removals(connection_id, event_date DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_supply_orders_connection_state
  ON public.ozon_supply_orders(connection_id, state, created_at_ozon DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_stock_analytics_connection_date
  ON public.ozon_stock_analytics(connection_id, snapshot_date DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_turnover_analytics_connection_date
  ON public.ozon_turnover_analytics(connection_id, snapshot_date DESC);
CREATE INDEX IF NOT EXISTS idx_ozon_discounted_products_connection
  ON public.ozon_discounted_products(connection_id, status);

DROP TRIGGER IF EXISTS set_ozon_legal_entity_sales_updated_at ON public.ozon_legal_entity_sales;
CREATE TRIGGER set_ozon_legal_entity_sales_updated_at
BEFORE UPDATE ON public.ozon_legal_entity_sales
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_unpaid_legal_products_updated_at ON public.ozon_unpaid_legal_products;
CREATE TRIGGER set_ozon_unpaid_legal_products_updated_at
BEFORE UPDATE ON public.ozon_unpaid_legal_products
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_finance_reports_updated_at ON public.ozon_finance_reports;
CREATE TRIGGER set_ozon_finance_reports_updated_at
BEFORE UPDATE ON public.ozon_finance_reports
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_removals_updated_at ON public.ozon_removals;
CREATE TRIGGER set_ozon_removals_updated_at
BEFORE UPDATE ON public.ozon_removals
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_supply_orders_updated_at ON public.ozon_supply_orders;
CREATE TRIGGER set_ozon_supply_orders_updated_at
BEFORE UPDATE ON public.ozon_supply_orders
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_stock_analytics_updated_at ON public.ozon_stock_analytics;
CREATE TRIGGER set_ozon_stock_analytics_updated_at
BEFORE UPDATE ON public.ozon_stock_analytics
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_turnover_analytics_updated_at ON public.ozon_turnover_analytics;
CREATE TRIGGER set_ozon_turnover_analytics_updated_at
BEFORE UPDATE ON public.ozon_turnover_analytics
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS set_ozon_discounted_products_updated_at ON public.ozon_discounted_products;
CREATE TRIGGER set_ozon_discounted_products_updated_at
BEFORE UPDATE ON public.ozon_discounted_products
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.ozon_legal_entity_sales ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_unpaid_legal_products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_finance_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_removals ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_supply_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_supply_order_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_stock_analytics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_turnover_analytics ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ozon_discounted_products ENABLE ROW LEVEL SECURITY;

CREATE POLICY "ozon_legal_entity_sales_select_member" ON public.ozon_legal_entity_sales
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_legal_entity_sales_write_admin" ON public.ozon_legal_entity_sales
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_unpaid_legal_products_select_member" ON public.ozon_unpaid_legal_products
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_unpaid_legal_products_write_admin" ON public.ozon_unpaid_legal_products
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_finance_reports_select_member" ON public.ozon_finance_reports
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_finance_reports_write_admin" ON public.ozon_finance_reports
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_removals_select_member" ON public.ozon_removals
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_removals_write_admin" ON public.ozon_removals
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_supply_orders_select_member" ON public.ozon_supply_orders
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_supply_orders_write_admin" ON public.ozon_supply_orders
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_supply_order_items_select_member" ON public.ozon_supply_order_items
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_supply_order_items_write_admin" ON public.ozon_supply_order_items
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_stock_analytics_select_member" ON public.ozon_stock_analytics
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_stock_analytics_write_admin" ON public.ozon_stock_analytics
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_turnover_analytics_select_member" ON public.ozon_turnover_analytics
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_turnover_analytics_write_admin" ON public.ozon_turnover_analytics
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ozon_discounted_products_select_member" ON public.ozon_discounted_products
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ozon_discounted_products_write_admin" ON public.ozon_discounted_products
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

NOTIFY pgrst, 'reload schema';
