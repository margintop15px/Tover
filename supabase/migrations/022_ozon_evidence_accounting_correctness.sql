-- Ozon mirror provenance, strict candidate evidence, and nullable cost basis.

DO $block$
DECLARE
  v_table TEXT;
BEGIN
  FOREACH v_table IN ARRAY ARRAY[
    'ozon_warehouses', 'ozon_products', 'ozon_stock_snapshots',
    'ozon_postings', 'ozon_posting_items', 'ozon_returns',
    'ozon_finance_transactions', 'ozon_report_runs',
    'ozon_legal_entity_sales', 'ozon_unpaid_legal_products',
    'ozon_finance_reports', 'ozon_removals', 'ozon_supply_orders',
    'ozon_supply_order_items', 'ozon_stock_analytics',
    'ozon_turnover_analytics', 'ozon_discounted_products'
  ]
  LOOP
    EXECUTE format(
      'ALTER TABLE public.%I
         ADD COLUMN IF NOT EXISTS source_contract_version TEXT NOT NULL
           DEFAULT %L,
         ADD COLUMN IF NOT EXISTS last_sync_run_id UUID
           REFERENCES public.marketplace_sync_runs(id) ON DELETE SET NULL,
         ADD COLUMN IF NOT EXISTS superseded_at TIMESTAMPTZ',
      v_table,
      'legacy-unverified'
    );
    EXECUTE format(
      'CREATE INDEX IF NOT EXISTS %I ON public.%I(last_sync_run_id)',
      v_table || '_last_sync_run',
      v_table
    );
  END LOOP;
END;
$block$;

ALTER TABLE public.marketplace_operation_candidates
  ADD COLUMN IF NOT EXISTS evidence_version INTEGER NOT NULL DEFAULT 0
    CHECK (evidence_version >= 0),
  ADD COLUMN IF NOT EXISTS evidence_hash TEXT
    CHECK (
      evidence_hash IS NULL OR evidence_hash ~ '^[a-f0-9]{64}$'
    );

ALTER TABLE public.operations
  ADD COLUMN IF NOT EXISTS cost_contract_version INTEGER NOT NULL DEFAULT 0
    CHECK (cost_contract_version IN (0, 1));
ALTER TABLE public.operations
  ALTER COLUMN cost_contract_version SET DEFAULT 1;

ALTER TABLE public.ozon_returns
  ALTER COLUMN quantity DROP NOT NULL,
  ALTER COLUMN quantity DROP DEFAULT,
  ADD COLUMN IF NOT EXISTS currency_code TEXT,
  ADD COLUMN IF NOT EXISTS logistic_return_date TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS logistic_final_moment TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS ozon_warehouse_id TEXT,
  ADD COLUMN IF NOT EXISTS warehouse_name TEXT,
  ADD COLUMN IF NOT EXISTS local_warehouse_id UUID
    REFERENCES public.warehouses(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ozon_returns_local_warehouse
  ON public.ozon_returns(local_warehouse_id);

ALTER TABLE public.ozon_removals
  ADD COLUMN IF NOT EXISTS return_id TEXT,
  ADD COLUMN IF NOT EXISTS box_id TEXT,
  ADD COLUMN IF NOT EXISTS stock_type TEXT,
  ADD COLUMN IF NOT EXISTS delivery_date TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS given_out_date TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS utilization_date TIMESTAMPTZ;

ALTER TABLE public.ozon_supply_order_items
  ADD COLUMN IF NOT EXISTS bundle_id TEXT,
  ADD COLUMN IF NOT EXISTS ozon_supply_id TEXT,
  ADD COLUMN IF NOT EXISTS supply_state TEXT,
  ADD COLUMN IF NOT EXISTS storage_warehouse_name TEXT,
  ADD COLUMN IF NOT EXISTS ozon_storage_warehouse_id TEXT,
  ADD COLUMN IF NOT EXISTS local_destination_warehouse_id UUID
    REFERENCES public.warehouses(id) ON DELETE SET NULL,
  ADD COLUMN IF NOT EXISTS completed_at_ozon TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ozon_supply_order_items_local_destination_warehouse
  ON public.ozon_supply_order_items(local_destination_warehouse_id);

ALTER TABLE public.ozon_stock_analytics
  ADD COLUMN IF NOT EXISTS valid_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS available_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS requested_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS transit_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS return_from_customer_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS return_to_seller_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS stock_defect_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS transit_defect_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS other_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS excess_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS expiring_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS waiting_docs_stock_count NUMERIC(14,3),
  ADD COLUMN IF NOT EXISTS macrolocal_cluster_id TEXT,
  ADD COLUMN IF NOT EXISTS ads NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS ads_cluster NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS idc NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS idc_cluster NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS turnover_grade TEXT,
  ADD COLUMN IF NOT EXISTS turnover_grade_cluster TEXT;

ALTER TABLE public.ozon_turnover_analytics
  ADD COLUMN IF NOT EXISTS offer_id TEXT,
  ADD COLUMN IF NOT EXISTS idc NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS idc_grade TEXT,
  ADD COLUMN IF NOT EXISTS turnover NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS turnover_grade TEXT;

ALTER TABLE public.ozon_discounted_products
  ADD COLUMN IF NOT EXISTS condition TEXT,
  ADD COLUMN IF NOT EXISTS condition_estimation TEXT,
  ADD COLUMN IF NOT EXISTS defects TEXT,
  ADD COLUMN IF NOT EXISTS mechanical_damage TEXT,
  ADD COLUMN IF NOT EXISTS package_damage TEXT,
  ADD COLUMN IF NOT EXISTS packaging_violation TEXT,
  ADD COLUMN IF NOT EXISTS shortage TEXT,
  ADD COLUMN IF NOT EXISTS repair TEXT,
  ADD COLUMN IF NOT EXISTS reason_damaged TEXT,
  ADD COLUMN IF NOT EXISTS comment_reason_damaged TEXT,
  ADD COLUMN IF NOT EXISTS warranty_type TEXT;

ALTER TABLE public.product_balances
  ALTER COLUMN unit_cost DROP NOT NULL,
  ALTER COLUMN unit_cost DROP DEFAULT,
  ADD COLUMN IF NOT EXISTS cost_basis_status TEXT NOT NULL DEFAULT 'unknown'
    CHECK (cost_basis_status IN ('known', 'transferred', 'unknown'));

ALTER TABLE public.inventory_movements
  ALTER COLUMN unit_cost DROP NOT NULL,
  ALTER COLUMN unit_cost DROP DEFAULT,
  ALTER COLUMN total_cost DROP NOT NULL,
  ALTER COLUMN total_cost DROP DEFAULT,
  ADD COLUMN IF NOT EXISTS cost_basis_status TEXT NOT NULL DEFAULT 'unknown'
    CHECK (cost_basis_status IN ('known', 'transferred', 'unknown')),
  ADD COLUMN IF NOT EXISTS balance_unit_cost_after NUMERIC(14,4),
  ADD COLUMN IF NOT EXISTS balance_total_cost_after NUMERIC(18,4),
  ADD COLUMN IF NOT EXISTS balance_cost_basis_status_after TEXT
    CHECK (
      balance_cost_basis_status_after IS NULL
      OR balance_cost_basis_status_after IN ('known', 'transferred', 'unknown')
    );

CREATE INDEX IF NOT EXISTS inventory_movements_balance_history
  ON public.inventory_movements(
    workspace_id, product_id, warehouse_id, quality_status,
    operation_date DESC, operation_id
  );

-- Existing costs are intentionally left numerically unchanged and retain the
-- new default status `unknown`. Their provenance cannot be inferred from a
-- non-null value, and historical correction requires the approved repair flow.

CREATE OR REPLACE FUNCTION public.replace_ozon_supply_order_items_v2(
  p_supply_order_id UUID,
  p_rows JSONB
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_order public.ozon_supply_orders%ROWTYPE;
  v_count INTEGER;
BEGIN
  SELECT * INTO v_order
  FROM public.ozon_supply_orders
  WHERE id = p_supply_order_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon supply order not found' USING ERRCODE = 'P0002';
  END IF;
  IF jsonb_typeof(COALESCE(p_rows, '[]'::jsonb)) <> 'array' THEN
    RAISE EXCEPTION 'Ozon supply rows must be an array' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM jsonb_array_elements(COALESCE(p_rows, '[]'::jsonb)) row
    WHERE (
      NULLIF(row ->> 'local_product_id', '') IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM public.products product
        WHERE product.id = (row ->> 'local_product_id')::uuid
          AND product.workspace_id = v_order.workspace_id
      )
    ) OR (
      NULLIF(row ->> 'local_destination_warehouse_id', '') IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM public.warehouses warehouse
        WHERE warehouse.id =
          (row ->> 'local_destination_warehouse_id')::uuid
          AND warehouse.workspace_id = v_order.workspace_id
      )
    ) OR (
      NULLIF(row ->> 'last_sync_run_id', '') IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM public.marketplace_sync_runs run
        WHERE run.id = (row ->> 'last_sync_run_id')::uuid
          AND run.workspace_id = v_order.workspace_id
          AND run.connection_id = v_order.connection_id
          AND run.provider = 'ozon'
      )
    )
  ) THEN
    RAISE EXCEPTION 'Ozon supply rows are out of scope' USING ERRCODE = '42501';
  END IF;

  DELETE FROM public.ozon_supply_order_items
  WHERE supply_order_id = p_supply_order_id;

  INSERT INTO public.ozon_supply_order_items (
    workspace_id, connection_id, supply_order_id, external_id,
    ozon_product_id, offer_id, sku, name, quantity, raw_payload,
    local_product_id, bundle_id, ozon_supply_id, supply_state,
    storage_warehouse_name, ozon_storage_warehouse_id,
    local_destination_warehouse_id, completed_at_ozon,
    source_contract_version, last_sync_run_id
  )
  SELECT
    v_order.workspace_id, v_order.connection_id, p_supply_order_id,
    row.external_id, row.ozon_product_id, row.offer_id, row.sku, row.name,
    row.quantity, COALESCE(row.raw_payload, '{}'::jsonb),
    row.local_product_id, row.bundle_id, row.ozon_supply_id, row.supply_state,
    row.storage_warehouse_name, row.ozon_storage_warehouse_id,
    row.local_destination_warehouse_id, row.completed_at_ozon,
    COALESCE(row.source_contract_version, 'seller-api-2026-07-27'),
    row.last_sync_run_id
  FROM jsonb_to_recordset(COALESCE(p_rows, '[]'::jsonb)) AS row(
    external_id TEXT,
    ozon_product_id TEXT,
    offer_id TEXT,
    sku TEXT,
    name TEXT,
    quantity NUMERIC,
    raw_payload JSONB,
    local_product_id UUID,
    bundle_id TEXT,
    ozon_supply_id TEXT,
    supply_state TEXT,
    storage_warehouse_name TEXT,
    ozon_storage_warehouse_id TEXT,
    local_destination_warehouse_id UUID,
    completed_at_ozon TIMESTAMPTZ,
    source_contract_version TEXT,
    last_sync_run_id UUID
  );
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$function$;

CREATE OR REPLACE FUNCTION public.protect_ozon_candidate_evidence()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = ''
AS $function$
DECLARE
  v_owner NAME;
  v_protected_change BOOLEAN;
BEGIN
  SELECT pg_catalog.pg_get_userbyid(relowner)
  INTO v_owner
  FROM pg_catalog.pg_class
  WHERE oid = 'public.marketplace_operation_candidates'::regclass;

  IF TG_OP = 'INSERT' THEN
    v_protected_change :=
      NEW.evidence_version <> 0
      OR NEW.evidence_hash IS NOT NULL
      OR NEW.created_operation_id IS NOT NULL;
  ELSE
    v_protected_change :=
      NEW.provider IS DISTINCT FROM OLD.provider
      OR NEW.source_type IS DISTINCT FROM OLD.source_type
      OR NEW.external_event_id IS DISTINCT FROM OLD.external_event_id
      OR NEW.raw_payload IS DISTINCT FROM OLD.raw_payload
      OR NEW.evidence_version IS DISTINCT FROM OLD.evidence_version
      OR NEW.evidence_hash IS DISTINCT FROM OLD.evidence_hash
      OR NEW.created_operation_id IS DISTINCT FROM OLD.created_operation_id;
  END IF;

  IF v_protected_change
    AND current_user <> v_owner
    AND current_user <> 'service_role'
  THEN
    RAISE EXCEPTION 'Ozon candidate evidence is service-managed'
      USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS protect_ozon_candidate_evidence
  ON public.marketplace_operation_candidates;
CREATE TRIGGER protect_ozon_candidate_evidence
BEFORE INSERT OR UPDATE ON public.marketplace_operation_candidates
FOR EACH ROW EXECUTE FUNCTION public.protect_ozon_candidate_evidence();

CREATE OR REPLACE FUNCTION public.replace_ozon_posting_items_v2(
  p_posting_id UUID,
  p_rows JSONB
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_posting public.ozon_postings%ROWTYPE;
  v_count INTEGER;
BEGIN
  SELECT * INTO v_posting
  FROM public.ozon_postings
  WHERE id = p_posting_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon posting not found' USING ERRCODE = 'P0002';
  END IF;
  IF jsonb_typeof(COALESCE(p_rows, '[]'::jsonb)) <> 'array' THEN
    RAISE EXCEPTION 'Ozon posting rows must be an array' USING ERRCODE = '22023';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM jsonb_array_elements(COALESCE(p_rows, '[]'::jsonb)) row
    WHERE (
      NULLIF(row ->> 'local_product_id', '') IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM public.products product
        WHERE product.id = (row ->> 'local_product_id')::uuid
          AND product.workspace_id = v_posting.workspace_id
      )
    ) OR (
      NULLIF(row ->> 'last_sync_run_id', '') IS NOT NULL
      AND NOT EXISTS (
        SELECT 1 FROM public.marketplace_sync_runs run
        WHERE run.id = (row ->> 'last_sync_run_id')::uuid
          AND run.workspace_id = v_posting.workspace_id
          AND run.connection_id = v_posting.connection_id
          AND run.provider = 'ozon'
      )
    )
  ) THEN
    RAISE EXCEPTION 'Ozon posting rows are out of scope' USING ERRCODE = '42501';
  END IF;

  DELETE FROM public.ozon_posting_items WHERE posting_id = p_posting_id;
  INSERT INTO public.ozon_posting_items (
    workspace_id, connection_id, posting_id, ozon_product_id, offer_id, sku,
    name, quantity, price, currency_code, raw_payload, local_product_id,
    source_contract_version, last_sync_run_id
  )
  SELECT
    v_posting.workspace_id, v_posting.connection_id, p_posting_id,
    row.ozon_product_id, row.offer_id, row.sku, row.name, row.quantity,
    row.price, row.currency_code, COALESCE(row.raw_payload, '{}'::jsonb),
    row.local_product_id,
    COALESCE(row.source_contract_version, 'seller-api-2026-07-27'),
    row.last_sync_run_id
  FROM jsonb_to_recordset(COALESCE(p_rows, '[]'::jsonb)) AS row(
    ozon_product_id TEXT,
    offer_id TEXT,
    sku TEXT,
    name TEXT,
    quantity NUMERIC,
    price NUMERIC,
    currency_code TEXT,
    raw_payload JSONB,
    local_product_id UUID,
    source_contract_version TEXT,
    last_sync_run_id UUID
  );
  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$function$;

CREATE OR REPLACE FUNCTION public.replace_ozon_posting_with_items_v2(
  p_parent JSONB,
  p_rows JSONB
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_posting public.ozon_postings%ROWTYPE;
  v_workspace_id UUID := NULLIF(p_parent ->> 'workspace_id', '')::uuid;
  v_connection_id UUID := NULLIF(p_parent ->> 'connection_id', '')::uuid;
  v_local_warehouse_id UUID :=
    NULLIF(p_parent ->> 'local_warehouse_id', '')::uuid;
  v_last_sync_run_id UUID :=
    NULLIF(p_parent ->> 'last_sync_run_id', '')::uuid;
BEGIN
  IF jsonb_typeof(COALESCE(p_parent, '{}'::jsonb)) <> 'object' THEN
    RAISE EXCEPTION 'Ozon posting parent must be an object'
      USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM public.marketplace_connections connection
    WHERE connection.id = v_connection_id
      AND connection.workspace_id = v_workspace_id
      AND connection.provider = 'ozon'
  ) OR (
    v_local_warehouse_id IS NOT NULL
    AND NOT EXISTS (
      SELECT 1 FROM public.warehouses warehouse
      WHERE warehouse.id = v_local_warehouse_id
        AND warehouse.workspace_id = v_workspace_id
    )
  ) OR (
    v_last_sync_run_id IS NOT NULL
    AND NOT EXISTS (
      SELECT 1 FROM public.marketplace_sync_runs run
      WHERE run.id = v_last_sync_run_id
        AND run.workspace_id = v_workspace_id
        AND run.connection_id = v_connection_id
        AND run.provider = 'ozon'
    )
  ) THEN
    RAISE EXCEPTION 'Ozon posting parent is out of scope'
      USING ERRCODE = '42501';
  END IF;

  INSERT INTO public.ozon_postings (
    workspace_id, connection_id, posting_schema, posting_number, order_id,
    status, substatus, in_process_at, shipment_date, delivered_at,
    cancelled_at, warehouse_name, financial_data, analytics_data, raw_payload,
    local_warehouse_id, synced_at, source_contract_version, last_sync_run_id,
    superseded_at
  ) VALUES (
    v_workspace_id,
    v_connection_id,
    p_parent ->> 'posting_schema',
    p_parent ->> 'posting_number',
    NULLIF(p_parent ->> 'order_id', ''),
    NULLIF(p_parent ->> 'status', ''),
    NULLIF(p_parent ->> 'substatus', ''),
    NULLIF(p_parent ->> 'in_process_at', '')::timestamptz,
    NULLIF(p_parent ->> 'shipment_date', '')::timestamptz,
    NULLIF(p_parent ->> 'delivered_at', '')::timestamptz,
    NULLIF(p_parent ->> 'cancelled_at', '')::timestamptz,
    NULLIF(p_parent ->> 'warehouse_name', ''),
    COALESCE(p_parent -> 'financial_data', '{}'::jsonb),
    COALESCE(p_parent -> 'analytics_data', '{}'::jsonb),
    COALESCE(p_parent -> 'raw_payload', '{}'::jsonb),
    v_local_warehouse_id,
    COALESCE(
      NULLIF(p_parent ->> 'synced_at', '')::timestamptz,
      clock_timestamp()
    ),
    COALESCE(
      NULLIF(p_parent ->> 'source_contract_version', ''),
      'seller-api-2026-07-27'
    ),
    v_last_sync_run_id,
    NULLIF(p_parent ->> 'superseded_at', '')::timestamptz
  )
  ON CONFLICT (connection_id, posting_schema, posting_number)
  DO UPDATE SET
    order_id = EXCLUDED.order_id,
    status = EXCLUDED.status,
    substatus = EXCLUDED.substatus,
    in_process_at = EXCLUDED.in_process_at,
    shipment_date = EXCLUDED.shipment_date,
    delivered_at = EXCLUDED.delivered_at,
    cancelled_at = EXCLUDED.cancelled_at,
    warehouse_name = EXCLUDED.warehouse_name,
    financial_data = EXCLUDED.financial_data,
    analytics_data = EXCLUDED.analytics_data,
    raw_payload = EXCLUDED.raw_payload,
    local_warehouse_id = EXCLUDED.local_warehouse_id,
    synced_at = EXCLUDED.synced_at,
    source_contract_version = EXCLUDED.source_contract_version,
    last_sync_run_id = EXCLUDED.last_sync_run_id,
    superseded_at = EXCLUDED.superseded_at
  RETURNING * INTO v_posting;

  PERFORM public.replace_ozon_posting_items_v2(v_posting.id, p_rows);
  RETURN v_posting.id;
END;
$function$;

CREATE OR REPLACE FUNCTION public.replace_ozon_supply_order_with_items_v2(
  p_parent JSONB,
  p_rows JSONB
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_order public.ozon_supply_orders%ROWTYPE;
  v_workspace_id UUID := NULLIF(p_parent ->> 'workspace_id', '')::uuid;
  v_connection_id UUID := NULLIF(p_parent ->> 'connection_id', '')::uuid;
  v_local_warehouse_id UUID :=
    NULLIF(p_parent ->> 'local_destination_warehouse_id', '')::uuid;
  v_last_sync_run_id UUID :=
    NULLIF(p_parent ->> 'last_sync_run_id', '')::uuid;
BEGIN
  IF jsonb_typeof(COALESCE(p_parent, '{}'::jsonb)) <> 'object' THEN
    RAISE EXCEPTION 'Ozon supply parent must be an object'
      USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM public.marketplace_connections connection
    WHERE connection.id = v_connection_id
      AND connection.workspace_id = v_workspace_id
      AND connection.provider = 'ozon'
  ) OR (
    v_local_warehouse_id IS NOT NULL
    AND NOT EXISTS (
      SELECT 1 FROM public.warehouses warehouse
      WHERE warehouse.id = v_local_warehouse_id
        AND warehouse.workspace_id = v_workspace_id
    )
  ) OR (
    v_last_sync_run_id IS NOT NULL
    AND NOT EXISTS (
      SELECT 1 FROM public.marketplace_sync_runs run
      WHERE run.id = v_last_sync_run_id
        AND run.workspace_id = v_workspace_id
        AND run.connection_id = v_connection_id
        AND run.provider = 'ozon'
    )
  ) THEN
    RAISE EXCEPTION 'Ozon supply parent is out of scope'
      USING ERRCODE = '42501';
  END IF;

  INSERT INTO public.ozon_supply_orders (
    workspace_id, connection_id, ozon_supply_order_id, order_number, state,
    created_at_ozon, warehouse_name, ozon_warehouse_id, bundle_ids,
    raw_payload, local_destination_warehouse_id, synced_at,
    source_contract_version, last_sync_run_id, superseded_at
  ) VALUES (
    v_workspace_id,
    v_connection_id,
    p_parent ->> 'ozon_supply_order_id',
    NULLIF(p_parent ->> 'order_number', ''),
    NULLIF(p_parent ->> 'state', ''),
    NULLIF(p_parent ->> 'created_at_ozon', '')::timestamptz,
    NULLIF(p_parent ->> 'warehouse_name', ''),
    NULLIF(p_parent ->> 'ozon_warehouse_id', ''),
    COALESCE(p_parent -> 'bundle_ids', '[]'::jsonb),
    COALESCE(p_parent -> 'raw_payload', '{}'::jsonb),
    v_local_warehouse_id,
    COALESCE(
      NULLIF(p_parent ->> 'synced_at', '')::timestamptz,
      clock_timestamp()
    ),
    COALESCE(
      NULLIF(p_parent ->> 'source_contract_version', ''),
      'seller-api-2026-07-27'
    ),
    v_last_sync_run_id,
    NULLIF(p_parent ->> 'superseded_at', '')::timestamptz
  )
  ON CONFLICT (connection_id, ozon_supply_order_id)
  DO UPDATE SET
    order_number = EXCLUDED.order_number,
    state = EXCLUDED.state,
    created_at_ozon = EXCLUDED.created_at_ozon,
    warehouse_name = EXCLUDED.warehouse_name,
    ozon_warehouse_id = EXCLUDED.ozon_warehouse_id,
    bundle_ids = EXCLUDED.bundle_ids,
    raw_payload = EXCLUDED.raw_payload,
    local_destination_warehouse_id =
      EXCLUDED.local_destination_warehouse_id,
    synced_at = EXCLUDED.synced_at,
    source_contract_version = EXCLUDED.source_contract_version,
    last_sync_run_id = EXCLUDED.last_sync_run_id,
    superseded_at = EXCLUDED.superseded_at
  RETURNING * INTO v_order;

  PERFORM public.replace_ozon_supply_order_items_v2(v_order.id, p_rows);
  RETURN v_order.id;
END;
$function$;

CREATE OR REPLACE FUNCTION public.rebuild_inventory_reporting(p_workspace_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_op RECORD;
  v_item RECORD;
  v_balance public.product_balances%ROWTYPE;
  v_qty_after NUMERIC;
  v_unit_cost NUMERIC;
  v_new_cost NUMERIC;
  v_cost_status TEXT;
  v_new_status TEXT;
  v_transfer_cost NUMERIC;
  v_transfer_status TEXT;
  v_production_total NUMERIC;
  v_production_known BOOLEAN;
  v_output_qty NUMERIC;
BEGIN
  IF COALESCE(
      NULLIF(
        pg_catalog.current_setting('request.jwt.claim.role', true), ''
      ),
      NULLIF(
        pg_catalog.current_setting('request.jwt.claims', true), ''
      )::jsonb ->> 'role',
      ''
    ) <> 'service_role'
    AND NOT public.app_has_org_role(
      p_workspace_id, ARRAY['owner', 'admin']
    )
  THEN
    RAISE EXCEPTION 'Inventory rebuild is out of scope'
      USING ERRCODE = '42501';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text, 91742)
  );
  DELETE FROM public.inventory_movements WHERE workspace_id = p_workspace_id;
  DELETE FROM public.product_balances WHERE workspace_id = p_workspace_id;

  FOR v_op IN
    SELECT * FROM public.operations
    WHERE workspace_id = p_workspace_id
    ORDER BY operation_date, created_at, id
  LOOP
    IF v_op.type = 'payment' THEN CONTINUE; END IF;

    IF v_op.type IN ('transfer', 'defect') THEN
      v_transfer_cost := NULL;
      v_transfer_status := 'unknown';
    ELSIF v_op.type = 'production' THEN
      v_production_total := 0;
      v_production_known := true;
      SELECT COALESCE(sum(quantity), 0) INTO v_output_qty
      FROM public.operation_items
      WHERE operation_id = v_op.id AND direction = 'in';
    END IF;

    FOR v_item IN
      SELECT oi.*, p.store_id AS product_store_id
      FROM public.operation_items oi
      JOIN public.products p ON p.id = oi.product_id
      WHERE oi.operation_id = v_op.id
      ORDER BY CASE WHEN oi.direction = 'out' THEN 0 ELSE 1 END,
               oi.created_at, oi.id
    LOOP
      SELECT * INTO v_balance
      FROM public.product_balances
      WHERE workspace_id = p_workspace_id
        AND product_id = v_item.product_id
        AND warehouse_id = v_item.warehouse_id
        AND quality_status = v_item.quality_status
      FOR UPDATE;

      IF v_op.type IN ('purchase', 'inventory_adjustment') THEN
        IF v_op.cost_contract_version = 0 THEN
          v_unit_cost := COALESCE(v_item.unit_price, 0);
          v_cost_status := CASE
            WHEN v_item.unit_price IS NULL THEN 'unknown' ELSE 'known'
          END;
        ELSE
          v_unit_cost := v_item.unit_price;
          v_cost_status := CASE
            WHEN v_unit_cost IS NULL THEN 'unknown' ELSE 'known'
          END;
        END IF;
      ELSIF v_op.type IN ('sale', 'write_off', 'return') THEN
        IF v_op.cost_contract_version = 0 THEN
          IF v_balance.unit_cost IS NOT NULL THEN
            v_unit_cost := v_balance.unit_cost;
            v_cost_status :=
              COALESCE(v_balance.cost_basis_status, 'unknown');
          ELSE
            v_unit_cost := COALESCE(v_item.unit_price, 0);
            v_cost_status := 'unknown';
          END IF;
        ELSE
          v_cost_status := COALESCE(v_balance.cost_basis_status, 'unknown');
          v_unit_cost := CASE
            WHEN v_cost_status = 'unknown' THEN NULL
            ELSE v_balance.unit_cost
          END;
        END IF;
      ELSIF v_op.type IN ('transfer', 'defect') THEN
        IF v_item.direction = 'out' THEN
          IF v_op.cost_contract_version = 0 THEN
            IF v_balance.unit_cost IS NOT NULL THEN
              v_unit_cost := v_balance.unit_cost;
              v_cost_status :=
                COALESCE(v_balance.cost_basis_status, 'unknown');
            ELSE
              v_unit_cost := COALESCE(v_item.unit_price, 0);
              v_cost_status := 'unknown';
            END IF;
          ELSE
            v_cost_status := COALESCE(v_balance.cost_basis_status, 'unknown');
            v_unit_cost := CASE
              WHEN v_cost_status = 'unknown' THEN NULL
              ELSE v_balance.unit_cost
            END;
          END IF;
          v_transfer_cost := v_unit_cost;
          v_transfer_status := v_cost_status;
        ELSE
          v_unit_cost := v_transfer_cost;
          v_cost_status := CASE
            WHEN v_transfer_status = 'unknown' THEN 'unknown'
            ELSE 'transferred'
          END;
        END IF;
      ELSIF v_op.type = 'production' THEN
        IF v_item.direction = 'out' THEN
          IF v_op.cost_contract_version = 0 THEN
            v_unit_cost := COALESCE(v_balance.unit_cost, 0);
            v_cost_status := CASE
              WHEN v_balance.unit_cost IS NULL THEN 'unknown'
              ELSE COALESCE(v_balance.cost_basis_status, 'unknown')
            END;
          ELSE
            v_cost_status := COALESCE(v_balance.cost_basis_status, 'unknown');
            v_unit_cost := CASE
              WHEN v_cost_status = 'unknown' THEN NULL
              ELSE v_balance.unit_cost
            END;
          END IF;
          IF v_unit_cost IS NULL OR v_cost_status = 'unknown' THEN
            v_production_known := false;
          ELSE
            v_production_total :=
              v_production_total + v_item.quantity * v_unit_cost;
          END IF;
        ELSE
          v_unit_cost := CASE
            WHEN v_production_known AND v_output_qty > 0
              THEN v_production_total / v_output_qty
            ELSE NULL
          END;
          v_cost_status := CASE
            WHEN v_unit_cost IS NULL THEN 'unknown' ELSE 'transferred'
          END;
        END IF;
      ELSE
        CONTINUE;
      END IF;

      v_qty_after := COALESCE(v_balance.quantity, 0) +
        CASE WHEN v_item.direction = 'in'
          THEN v_item.quantity ELSE -v_item.quantity END;

      IF v_item.direction = 'in' THEN
        IF v_balance IS NULL OR v_balance.quantity = 0 THEN
          v_new_cost := v_unit_cost;
          v_new_status := v_cost_status;
        ELSIF v_balance.unit_cost IS NOT NULL
          AND v_balance.cost_basis_status <> 'unknown'
          AND v_unit_cost IS NOT NULL
          AND v_cost_status <> 'unknown'
          AND v_qty_after <> 0
        THEN
          v_new_cost :=
            (v_balance.quantity * v_balance.unit_cost +
             v_item.quantity * v_unit_cost) / v_qty_after;
          v_new_status := CASE
            WHEN v_cost_status = 'transferred' THEN 'transferred' ELSE 'known'
          END;
        ELSE
          v_new_cost := NULL;
          v_new_status := 'unknown';
        END IF;
      ELSE
        v_new_cost := v_balance.unit_cost;
        v_new_status := COALESCE(v_balance.cost_basis_status, 'unknown');
      END IF;

      INSERT INTO public.product_balances (
        workspace_id, product_id, warehouse_id, quality_status,
        quantity, unit_cost, cost_basis_status
      ) VALUES (
        p_workspace_id, v_item.product_id, v_item.warehouse_id,
        v_item.quality_status, v_qty_after, v_new_cost, v_new_status
      )
      ON CONFLICT (workspace_id, product_id, warehouse_id, quality_status)
      DO UPDATE SET
        quantity = EXCLUDED.quantity,
        unit_cost = EXCLUDED.unit_cost,
        cost_basis_status = EXCLUDED.cost_basis_status;

      INSERT INTO public.inventory_movements (
        workspace_id, operation_id, operation_item_id, operation_date,
        operation_type, product_id, warehouse_id, store_id, supplier_id,
        quality_status, direction, quantity, unit_cost, total_cost,
        cost_basis_status, invoice_unit_price, invoice_amount,
        balance_quantity_after, balance_unit_cost_after,
        balance_total_cost_after, balance_cost_basis_status_after,
        is_negative_after
      ) VALUES (
        p_workspace_id, v_op.id, v_item.id, v_op.operation_date, v_op.type,
        v_item.product_id, v_item.warehouse_id,
        COALESCE(v_item.store_id, v_item.product_store_id), v_op.supplier_id,
        v_item.quality_status, v_item.direction, v_item.quantity,
        v_unit_cost,
        CASE WHEN v_unit_cost IS NULL THEN NULL
          ELSE v_item.quantity * v_unit_cost END,
        v_cost_status,
        CASE WHEN v_op.type IN ('purchase', 'sale', 'return')
          THEN v_item.unit_price ELSE NULL END,
        CASE WHEN v_op.type IN ('purchase', 'sale', 'return')
          AND v_item.unit_price IS NOT NULL
          THEN v_item.quantity * v_item.unit_price ELSE NULL END,
        v_qty_after, v_new_cost,
        CASE WHEN v_new_cost IS NULL THEN NULL
          ELSE v_qty_after * v_new_cost END,
        v_new_status,
        v_qty_after < 0
      );
    END LOOP;
  END LOOP;
END;
$function$;

CREATE OR REPLACE FUNCTION public.report_inventory_balances_at_date(
  p_workspace_id UUID,
  p_target_date DATE
)
RETURNS TABLE(
  product_id UUID,
  warehouse_id UUID,
  store_id UUID,
  quality_status TEXT,
  quantity NUMERIC,
  total_cost NUMERIC,
  has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  WITH eligible AS (
    SELECT
      im.*,
      op.created_at AS operation_created_at,
      item.created_at AS item_created_at
    FROM public.inventory_movements im
    JOIN public.operations op ON op.id = im.operation_id
    LEFT JOIN public.operation_items item ON item.id = im.operation_item_id
    WHERE im.workspace_id = p_workspace_id
      AND im.operation_date <= p_target_date
      AND (
        COALESCE(
          NULLIF(
            pg_catalog.current_setting('request.jwt.claim.role', true), ''
          ),
          NULLIF(
            pg_catalog.current_setting('request.jwt.claims', true), ''
          )::jsonb ->> 'role',
          ''
        ) = 'service_role'
        OR public.app_is_org_member(p_workspace_id)
      )
  ),
  latest AS (
    SELECT DISTINCT ON (
      product_id, warehouse_id, quality_status
    )
      product_id,
      warehouse_id,
      store_id,
      quality_status,
      balance_quantity_after,
      balance_total_cost_after,
      balance_cost_basis_status_after
    FROM eligible
    ORDER BY
      product_id, warehouse_id, quality_status,
      operation_date DESC, operation_created_at DESC, operation_id DESC,
      item_created_at DESC NULLS LAST, operation_item_id DESC NULLS LAST
  ),
  history AS (
    SELECT
      product_id,
      warehouse_id,
      quality_status,
      bool_or(is_negative_after) AS has_negative,
      CASE WHEN bool_or(
        total_cost IS NULL OR cost_basis_status = 'unknown'
      ) THEN NULL
        ELSE sum(CASE WHEN direction = 'in' THEN total_cost ELSE -total_cost END)
      END AS legacy_total_cost
    FROM eligible
    GROUP BY product_id, warehouse_id, quality_status
  )
  SELECT
    latest.product_id,
    latest.warehouse_id,
    latest.store_id,
    latest.quality_status,
    latest.balance_quantity_after AS quantity,
    CASE
      WHEN latest.balance_cost_basis_status_after IS NULL
        THEN history.legacy_total_cost
      WHEN latest.balance_cost_basis_status_after = 'unknown'
        THEN NULL
      ELSE latest.balance_total_cost_after
    END AS total_cost,
    history.has_negative
  FROM latest
  JOIN history USING (product_id, warehouse_id, quality_status);
$function$;

CREATE OR REPLACE FUNCTION public.report_product_movement(
  p_workspace_id UUID,
  p_from DATE,
  p_to DATE
)
RETURNS TABLE(
  product_id UUID,
  warehouse_id UUID,
  store_id UUID,
  quality_status TEXT,
  operation_type TEXT,
  direction TEXT,
  total_quantity NUMERIC,
  total_cost NUMERIC,
  has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  SELECT
    im.product_id,
    im.warehouse_id,
    im.store_id,
    im.quality_status,
    im.operation_type,
    im.direction,
    sum(im.quantity) AS total_quantity,
    CASE WHEN bool_or(
      im.total_cost IS NULL OR im.cost_basis_status = 'unknown'
    ) THEN NULL
      ELSE sum(im.total_cost)
    END AS total_cost,
    bool_or(im.is_negative_after) AS has_negative
  FROM public.inventory_movements im
  WHERE im.workspace_id = p_workspace_id
    AND im.operation_date BETWEEN p_from AND p_to
    AND (
      COALESCE(
        NULLIF(
          pg_catalog.current_setting('request.jwt.claim.role', true), ''
        ),
        NULLIF(
          pg_catalog.current_setting('request.jwt.claims', true), ''
        )::jsonb ->> 'role',
        ''
      ) = 'service_role'
      OR public.app_is_org_member(p_workspace_id)
    )
  GROUP BY im.product_id, im.warehouse_id, im.store_id, im.quality_status,
           im.operation_type, im.direction;
$function$;

CREATE OR REPLACE FUNCTION public.report_inventory_balances_v2(
  p_workspace_id UUID,
  p_target_date DATE,
  p_product_id UUID,
  p_category_id UUID,
  p_warehouse_id UUID,
  p_store_id UUID,
  p_quality_status TEXT,
  p_search TEXT,
  p_hide_zeros BOOLEAN,
  p_negatives_only BOOLEAN
)
RETURNS TABLE(
  product_id UUID,
  product_name TEXT,
  sku_code TEXT,
  category_name TEXT,
  store_id UUID,
  store_name TEXT,
  quality_status TEXT,
  warehouses JSONB,
  total_quantity NUMERIC,
  total_cost NUMERIC,
  has_negative BOOLEAN,
  grand_total_quantity NUMERIC,
  grand_total_cost NUMERIC,
  grand_has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  WITH base AS (
    SELECT
      balance.product_id,
      product.name AS product_name,
      product.sku_code,
      category.name AS category_name,
      COALESCE(balance.store_id, product.store_id) AS effective_store_id,
      store.name AS effective_store_name,
      balance.warehouse_id,
      warehouse.name AS warehouse_name,
      balance.quality_status,
      balance.quantity,
      balance.total_cost,
      balance.has_negative OR balance.quantity < 0 AS has_negative
    FROM public.report_inventory_balances_at_date(
      p_workspace_id, p_target_date
    ) balance
    JOIN public.products product ON product.id = balance.product_id
    JOIN public.warehouses warehouse ON warehouse.id = balance.warehouse_id
    LEFT JOIN public.categories category ON category.id = product.category_id
    LEFT JOIN public.stores store
      ON store.id = COALESCE(balance.store_id, product.store_id)
    WHERE NOT product.is_defect_copy
      AND (p_product_id IS NULL OR balance.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR balance.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(balance.store_id, product.store_id) = p_store_id
      )
      AND (
        p_quality_status IS NULL
        OR balance.quality_status = p_quality_status
      )
      AND (
        NULLIF(pg_catalog.btrim(p_search), '') IS NULL
        OR product.name ILIKE '%' || pg_catalog.btrim(p_search) || '%'
        OR product.sku_code ILIKE '%' || pg_catalog.btrim(p_search) || '%'
      )
  ),
  grouped AS (
    SELECT
      product_id,
      product_name,
      sku_code,
      category_name,
      effective_store_id AS store_id,
      effective_store_name AS store_name,
      quality_status,
      jsonb_agg(
        jsonb_build_object(
          'warehouseId', warehouse_id,
          'warehouseName', warehouse_name,
          'qualityStatus', quality_status,
          'quantity', quantity,
          'totalCost', total_cost,
          'hasNegative', has_negative
        )
        ORDER BY warehouse_name, warehouse_id
      ) AS warehouses,
      sum(quantity) AS total_quantity,
      CASE WHEN bool_or(total_cost IS NULL) THEN NULL
        ELSE sum(total_cost)
      END AS total_cost,
      bool_or(has_negative) AS has_negative
    FROM base
    GROUP BY product_id, product_name, sku_code, category_name,
             effective_store_id, effective_store_name, quality_status
  ),
  filtered AS (
    SELECT *
    FROM grouped
    WHERE (NOT p_hide_zeros OR total_quantity <> 0)
      AND (
        NOT p_negatives_only
        OR has_negative
        OR total_quantity < 0
      )
  )
  SELECT
    filtered.product_id,
    filtered.product_name,
    filtered.sku_code,
    filtered.category_name,
    filtered.store_id,
    filtered.store_name,
    filtered.quality_status,
    filtered.warehouses,
    filtered.total_quantity,
    filtered.total_cost,
    filtered.has_negative,
    sum(filtered.total_quantity) OVER () AS grand_total_quantity,
    CASE
      WHEN bool_or(filtered.total_cost IS NULL) OVER () THEN NULL
      ELSE sum(filtered.total_cost) OVER ()
    END AS grand_total_cost,
    bool_or(filtered.has_negative) OVER () AS grand_has_negative
  FROM filtered
  ORDER BY filtered.product_name, filtered.product_id;
$function$;

CREATE OR REPLACE FUNCTION public.report_inventory_balances_grouped_v2(
  p_workspace_id UUID,
  p_target_date DATE,
  p_group_by TEXT,
  p_product_id UUID,
  p_category_id UUID,
  p_warehouse_id UUID,
  p_store_id UUID,
  p_quality_status TEXT,
  p_search TEXT,
  p_hide_zeros BOOLEAN,
  p_negatives_only BOOLEAN
)
RETURNS TABLE(
  group_id TEXT,
  group_name TEXT,
  sku_code TEXT,
  total_quantity NUMERIC,
  total_cost NUMERIC,
  has_negative BOOLEAN,
  grand_total_quantity NUMERIC,
  grand_total_cost NUMERIC,
  grand_has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  WITH base AS (
    SELECT
      balance.product_id,
      product.name AS product_name,
      product.sku_code,
      product.category_id,
      category.name AS category_name,
      COALESCE(balance.store_id, product.store_id) AS effective_store_id,
      store.name AS effective_store_name,
      balance.warehouse_id,
      warehouse.name AS warehouse_name,
      balance.quality_status,
      balance.quantity,
      balance.total_cost,
      balance.has_negative OR balance.quantity < 0 AS has_negative
    FROM public.report_inventory_balances_at_date(
      p_workspace_id, p_target_date
    ) balance
    JOIN public.products product ON product.id = balance.product_id
    JOIN public.warehouses warehouse ON warehouse.id = balance.warehouse_id
    LEFT JOIN public.categories category ON category.id = product.category_id
    LEFT JOIN public.stores store
      ON store.id = COALESCE(balance.store_id, product.store_id)
    WHERE NOT product.is_defect_copy
      AND (p_product_id IS NULL OR balance.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR balance.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(balance.store_id, product.store_id) = p_store_id
      )
      AND (
        p_quality_status IS NULL
        OR balance.quality_status = p_quality_status
      )
      AND (
        NULLIF(pg_catalog.btrim(p_search), '') IS NULL
        OR product.name ILIKE '%' || pg_catalog.btrim(p_search) || '%'
        OR product.sku_code ILIKE '%' || pg_catalog.btrim(p_search) || '%'
      )
  ),
  keyed AS (
    SELECT
      CASE p_group_by
        WHEN 'category' THEN COALESCE(category_id::text, 'uncategorized')
        WHEN 'warehouse' THEN warehouse_id::text
        WHEN 'store' THEN COALESCE(effective_store_id::text, 'unassigned')
        WHEN 'quality' THEN quality_status
        ELSE product_id::text
      END AS row_group_id,
      CASE p_group_by
        WHEN 'category' THEN COALESCE(category_name, 'No category')
        WHEN 'warehouse' THEN warehouse_name
        WHEN 'store' THEN COALESCE(effective_store_name, 'No store')
        WHEN 'quality' THEN quality_status
        ELSE product_name
      END AS row_group_name,
      CASE WHEN p_group_by = 'product' THEN base.sku_code END
        AS row_sku_code,
      quantity,
      total_cost,
      has_negative
    FROM base
  ),
  grouped AS (
    SELECT
      row_group_id,
      min(row_group_name) AS row_group_name,
      min(row_sku_code) AS row_sku_code,
      sum(quantity) AS total_quantity,
      CASE WHEN bool_or(total_cost IS NULL) THEN NULL
        ELSE sum(total_cost)
      END AS total_cost,
      bool_or(has_negative) AS has_negative
    FROM keyed
    GROUP BY row_group_id
  ),
  filtered AS (
    SELECT *
    FROM grouped
    WHERE (NOT p_hide_zeros OR total_quantity <> 0)
      AND (
        NOT p_negatives_only
        OR has_negative
        OR total_quantity < 0
      )
  )
  SELECT
    filtered.row_group_id,
    filtered.row_group_name,
    filtered.row_sku_code,
    filtered.total_quantity,
    filtered.total_cost,
    filtered.has_negative,
    sum(filtered.total_quantity) OVER () AS grand_total_quantity,
    CASE
      WHEN bool_or(filtered.total_cost IS NULL) OVER () THEN NULL
      ELSE sum(filtered.total_cost) OVER ()
    END AS grand_total_cost,
    bool_or(filtered.has_negative) OVER () AS grand_has_negative
  FROM filtered
  ORDER BY filtered.row_group_name, filtered.row_group_id;
$function$;

CREATE OR REPLACE FUNCTION public.report_product_movement_v2(
  p_workspace_id UUID,
  p_from DATE,
  p_to DATE,
  p_group_by TEXT,
  p_product_id UUID,
  p_category_id UUID,
  p_warehouse_id UUID,
  p_store_id UUID,
  p_quality_status TEXT
)
RETURNS TABLE(
  group_id TEXT,
  group_name TEXT,
  sku_code TEXT,
  quality_status TEXT,
  purchase_in NUMERIC,
  purchase_in_cost NUMERIC,
  sale_out NUMERIC,
  sale_out_cost NUMERIC,
  return_in NUMERIC,
  return_in_cost NUMERIC,
  write_off_out NUMERIC,
  write_off_out_cost NUMERIC,
  transfer_in NUMERIC,
  transfer_in_cost NUMERIC,
  transfer_out NUMERIC,
  transfer_out_cost NUMERIC,
  production_in NUMERIC,
  production_in_cost NUMERIC,
  production_out NUMERIC,
  production_out_cost NUMERIC,
  defect_out NUMERIC,
  defect_out_cost NUMERIC,
  inventory_adjustment_in NUMERIC,
  inventory_adjustment_in_cost NUMERIC,
  net NUMERIC,
  net_cost NUMERIC,
  has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  WITH base AS (
    SELECT
      movement.*,
      product.name AS product_name,
      product.sku_code,
      product.category_id,
      COALESCE(movement.store_id, product.store_id) AS effective_store_id,
      COALESCE(store.name, 'No store') AS effective_store_name,
      COALESCE(warehouse.name, 'Unknown') AS warehouse_name
    FROM public.inventory_movements movement
    JOIN public.products product ON product.id = movement.product_id
    JOIN public.warehouses warehouse ON warehouse.id = movement.warehouse_id
    LEFT JOIN public.stores store
      ON store.id = COALESCE(movement.store_id, product.store_id)
    WHERE movement.workspace_id = p_workspace_id
      AND movement.operation_date BETWEEN p_from AND p_to
      AND NOT product.is_defect_copy
      AND (p_product_id IS NULL OR movement.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR movement.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(movement.store_id, product.store_id) = p_store_id
      )
      AND (
        p_quality_status IS NULL
        OR movement.quality_status = p_quality_status
      )
      AND (
        COALESCE(
          NULLIF(
            pg_catalog.current_setting('request.jwt.claim.role', true), ''
          ),
          NULLIF(
            pg_catalog.current_setting('request.jwt.claims', true), ''
          )::jsonb ->> 'role',
          ''
        ) = 'service_role'
        OR public.app_is_org_member(p_workspace_id)
      )
  ),
  keyed AS (
    SELECT
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_id::text
        WHEN 'store' THEN COALESCE(effective_store_id::text, 'unassigned')
        WHEN 'quality' THEN quality_status
        ELSE product_id::text
      END AS group_id,
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_name
        WHEN 'store' THEN effective_store_name
        WHEN 'quality' THEN quality_status
        ELSE product_name
      END AS group_name,
      CASE WHEN p_group_by = 'product' THEN sku_code ELSE NULL END
        AS group_sku_code,
      CASE WHEN p_group_by = 'quality' THEN quality_status ELSE NULL END
        AS grouped_quality_status,
      base.*
    FROM base
  )
  SELECT
    keyed.group_id,
    min(keyed.group_name) AS group_name,
    min(keyed.group_sku_code) AS sku_code,
    min(keyed.grouped_quality_status) AS quality_status,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'purchase' AND direction = 'in'
    ), 0) AS purchase_in,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'purchase' AND direction = 'in'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'purchase' AND direction = 'in'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'purchase' AND direction = 'in'
      )
    END AS purchase_in_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'sale' AND direction = 'out'
    ), 0) AS sale_out,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'sale' AND direction = 'out'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'sale' AND direction = 'out'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'sale' AND direction = 'out'
      )
    END AS sale_out_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'return' AND direction = 'in'
    ), 0) AS return_in,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'return' AND direction = 'in'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'return' AND direction = 'in'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'return' AND direction = 'in'
      )
    END AS return_in_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'write_off' AND direction = 'out'
    ), 0) AS write_off_out,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'write_off' AND direction = 'out'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'write_off' AND direction = 'out'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'write_off' AND direction = 'out'
      )
    END AS write_off_out_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'transfer' AND direction = 'in'
    ), 0) AS transfer_in,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'transfer' AND direction = 'in'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'transfer' AND direction = 'in'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'transfer' AND direction = 'in'
      )
    END AS transfer_in_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'transfer' AND direction = 'out'
    ), 0) AS transfer_out,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'transfer' AND direction = 'out'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'transfer' AND direction = 'out'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'transfer' AND direction = 'out'
      )
    END AS transfer_out_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'production' AND direction = 'in'
    ), 0) AS production_in,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'production' AND direction = 'in'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'production' AND direction = 'in'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'production' AND direction = 'in'
      )
    END AS production_in_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'production' AND direction = 'out'
    ), 0) AS production_out,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'production' AND direction = 'out'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'production' AND direction = 'out'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'production' AND direction = 'out'
      )
    END AS production_out_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'defect' AND direction = 'out'
    ), 0) AS defect_out,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'defect' AND direction = 'out'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'defect' AND direction = 'out'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'defect' AND direction = 'out'
      )
    END AS defect_out_cost,
    COALESCE(sum(quantity) FILTER (
      WHERE operation_type = 'inventory_adjustment' AND direction = 'in'
    ), 0) AS inventory_adjustment_in,
    CASE
      WHEN count(*) FILTER (
        WHERE operation_type = 'inventory_adjustment' AND direction = 'in'
      ) = 0 THEN 0
      WHEN bool_or(total_cost IS NULL) FILTER (
        WHERE operation_type = 'inventory_adjustment' AND direction = 'in'
      ) THEN NULL
      ELSE sum(total_cost) FILTER (
        WHERE operation_type = 'inventory_adjustment' AND direction = 'in'
      )
    END AS inventory_adjustment_in_cost,
    COALESCE(sum(
      CASE WHEN direction = 'in' THEN quantity ELSE -quantity END
    ), 0) AS net,
    CASE WHEN bool_or(total_cost IS NULL) THEN NULL
      ELSE sum(CASE WHEN direction = 'in' THEN total_cost ELSE -total_cost END)
    END AS net_cost,
    bool_or(is_negative_after) AS has_negative
  FROM keyed
  GROUP BY keyed.group_id
  ORDER BY min(keyed.group_name), keyed.group_id;
$function$;

CREATE OR REPLACE FUNCTION public.report_defect_dynamics_v2(
  p_workspace_id UUID,
  p_from DATE,
  p_to DATE,
  p_group_by TEXT,
  p_product_id UUID,
  p_category_id UUID,
  p_warehouse_id UUID,
  p_store_id UUID
)
RETURNS TABLE(
  group_id TEXT,
  group_name TEXT,
  sku_code TEXT,
  defect_in_quantity NUMERIC,
  defect_out_quantity NUMERIC,
  defect_balance_delta NUMERIC,
  defect_cost NUMERIC,
  grand_defect_in_quantity NUMERIC,
  grand_defect_out_quantity NUMERIC,
  grand_defect_cost NUMERIC
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  WITH base AS (
    SELECT
      movement.*,
      product.name AS product_name,
      product.sku_code,
      product.category_id,
      COALESCE(movement.store_id, product.store_id) AS effective_store_id,
      COALESCE(store.name, 'No store') AS effective_store_name,
      COALESCE(warehouse.name, 'Unknown') AS warehouse_name
    FROM public.inventory_movements movement
    JOIN public.products product ON product.id = movement.product_id
    JOIN public.warehouses warehouse ON warehouse.id = movement.warehouse_id
    LEFT JOIN public.stores store
      ON store.id = COALESCE(movement.store_id, product.store_id)
    WHERE movement.workspace_id = p_workspace_id
      AND movement.operation_date BETWEEN p_from AND p_to
      AND movement.quality_status = 'defect'
      AND NOT product.is_defect_copy
      AND (p_product_id IS NULL OR movement.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR movement.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(movement.store_id, product.store_id) = p_store_id
      )
      AND (
        COALESCE(
          NULLIF(
            pg_catalog.current_setting('request.jwt.claim.role', true), ''
          ),
          NULLIF(
            pg_catalog.current_setting('request.jwt.claims', true), ''
          )::jsonb ->> 'role',
          ''
        ) = 'service_role'
        OR public.app_is_org_member(p_workspace_id)
      )
  ),
  keyed AS (
    SELECT
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_id::text
        WHEN 'store' THEN COALESCE(effective_store_id::text, 'unassigned')
        ELSE product_id::text
      END AS group_id,
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_name
        WHEN 'store' THEN effective_store_name
        ELSE product_name
      END AS group_name,
      CASE WHEN p_group_by = 'product' THEN sku_code ELSE NULL END
        AS group_sku_code,
      base.*
    FROM base
  ),
  grouped AS (
    SELECT
      group_id,
      min(group_name) AS group_name,
      min(group_sku_code) AS sku_code,
      COALESCE(sum(quantity) FILTER (WHERE direction = 'in'), 0)
        AS defect_in_quantity,
      COALESCE(sum(quantity) FILTER (WHERE direction = 'out'), 0)
        AS defect_out_quantity,
      COALESCE(sum(
        CASE WHEN direction = 'in' THEN quantity ELSE -quantity END
      ), 0) AS defect_balance_delta,
      CASE WHEN bool_or(total_cost IS NULL) THEN NULL
        ELSE sum(CASE WHEN direction = 'in' THEN total_cost ELSE -total_cost END)
      END AS defect_cost
    FROM keyed
    GROUP BY group_id
  )
  SELECT
    grouped.group_id,
    grouped.group_name,
    grouped.sku_code,
    grouped.defect_in_quantity,
    grouped.defect_out_quantity,
    grouped.defect_balance_delta,
    grouped.defect_cost,
    sum(grouped.defect_in_quantity) OVER () AS grand_defect_in_quantity,
    sum(grouped.defect_out_quantity) OVER () AS grand_defect_out_quantity,
    CASE WHEN bool_or(grouped.defect_cost IS NULL) OVER () THEN NULL
      ELSE sum(grouped.defect_cost) OVER ()
    END AS grand_defect_cost
  FROM grouped
  ORDER BY grouped.group_name, grouped.group_id;
$function$;

CREATE OR REPLACE FUNCTION public.report_turnover_v2(
  p_workspace_id UUID,
  p_from DATE,
  p_to DATE,
  p_group_by TEXT,
  p_product_id UUID,
  p_category_id UUID,
  p_warehouse_id UUID,
  p_store_id UUID
)
RETURNS TABLE(
  group_id TEXT,
  group_name TEXT,
  sku_code TEXT,
  outflow_cost NUMERIC,
  average_inventory_cost NUMERIC,
  turnover_ratio NUMERIC,
  turnover_days NUMERIC
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = ''
AS $function$
  WITH movement_base AS (
    SELECT
      movement.*,
      product.name AS product_name,
      product.sku_code,
      product.category_id,
      COALESCE(movement.store_id, product.store_id) AS effective_store_id,
      COALESCE(store.name, 'No store') AS effective_store_name,
      COALESCE(warehouse.name, 'Unknown') AS warehouse_name
    FROM public.inventory_movements movement
    JOIN public.products product ON product.id = movement.product_id
    JOIN public.warehouses warehouse ON warehouse.id = movement.warehouse_id
    LEFT JOIN public.stores store
      ON store.id = COALESCE(movement.store_id, product.store_id)
    WHERE movement.workspace_id = p_workspace_id
      AND movement.operation_date BETWEEN p_from AND p_to
      AND NOT product.is_defect_copy
      AND (p_product_id IS NULL OR movement.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR movement.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(movement.store_id, product.store_id) = p_store_id
      )
      AND (
        COALESCE(
          NULLIF(
            pg_catalog.current_setting('request.jwt.claim.role', true), ''
          ),
          NULLIF(
            pg_catalog.current_setting('request.jwt.claims', true), ''
          )::jsonb ->> 'role',
          ''
        ) = 'service_role'
        OR public.app_is_org_member(p_workspace_id)
      )
  ),
  movement_keyed AS (
    SELECT
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_id::text
        WHEN 'store' THEN COALESCE(effective_store_id::text, 'unassigned')
        ELSE product_id::text
      END AS group_id,
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_name
        WHEN 'store' THEN effective_store_name
        ELSE product_name
      END AS group_name,
      CASE WHEN p_group_by = 'product' THEN sku_code ELSE NULL END
        AS group_sku_code,
      movement_base.*
    FROM movement_base
  ),
  movement_groups AS (
    SELECT
      group_id,
      min(group_name) AS group_name,
      min(group_sku_code) AS sku_code,
      CASE
        WHEN count(*) FILTER (
          WHERE direction = 'out'
            AND operation_type IN ('sale', 'write_off', 'defect')
        ) = 0 THEN 0
        WHEN bool_or(total_cost IS NULL) FILTER (
          WHERE direction = 'out'
            AND operation_type IN ('sale', 'write_off', 'defect')
        ) THEN NULL
        ELSE sum(total_cost) FILTER (
          WHERE direction = 'out'
            AND operation_type IN ('sale', 'write_off', 'defect')
        )
      END AS outflow_cost
    FROM movement_keyed
    GROUP BY group_id
  ),
  opening_base AS (
    SELECT
      balance.*,
      product.name AS product_name,
      product.sku_code,
      product.category_id,
      COALESCE(balance.store_id, product.store_id) AS effective_store_id
    FROM public.report_inventory_balances_at_date(
      p_workspace_id, p_from - 1
    ) balance
    JOIN public.products product ON product.id = balance.product_id
    WHERE NOT product.is_defect_copy
      AND (p_product_id IS NULL OR balance.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR balance.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(balance.store_id, product.store_id) = p_store_id
      )
  ),
  closing_base AS (
    SELECT
      balance.*,
      product.name AS product_name,
      product.sku_code,
      product.category_id,
      COALESCE(balance.store_id, product.store_id) AS effective_store_id
    FROM public.report_inventory_balances_at_date(
      p_workspace_id, p_to
    ) balance
    JOIN public.products product ON product.id = balance.product_id
    WHERE NOT product.is_defect_copy
      AND (p_product_id IS NULL OR balance.product_id = p_product_id)
      AND (p_category_id IS NULL OR product.category_id = p_category_id)
      AND (p_warehouse_id IS NULL OR balance.warehouse_id = p_warehouse_id)
      AND (
        p_store_id IS NULL
        OR COALESCE(balance.store_id, product.store_id) = p_store_id
      )
  ),
  opening_grouped AS (
    SELECT
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_id::text
        WHEN 'store' THEN COALESCE(effective_store_id::text, 'unassigned')
        ELSE product_id::text
      END AS group_id,
      CASE WHEN bool_or(total_cost IS NULL) THEN NULL ELSE sum(total_cost) END
        AS opening_cost
    FROM opening_base
    GROUP BY 1
  ),
  closing_grouped AS (
    SELECT
      CASE p_group_by
        WHEN 'warehouse' THEN warehouse_id::text
        WHEN 'store' THEN COALESCE(effective_store_id::text, 'unassigned')
        ELSE product_id::text
      END AS group_id,
      CASE WHEN bool_or(total_cost IS NULL) THEN NULL ELSE sum(total_cost) END
        AS closing_cost
    FROM closing_base
    GROUP BY 1
  ),
  values AS (
    SELECT
      movement_groups.*,
      COALESCE(opening_grouped.opening_cost, 0) AS opening_cost,
      COALESCE(closing_grouped.closing_cost, 0) AS closing_cost,
      CASE
        WHEN opening_grouped.group_id IS NOT NULL
          AND opening_grouped.opening_cost IS NULL THEN NULL
        WHEN closing_grouped.group_id IS NOT NULL
          AND closing_grouped.closing_cost IS NULL THEN NULL
        ELSE (
          COALESCE(opening_grouped.opening_cost, 0)
          + COALESCE(closing_grouped.closing_cost, 0)
        ) / 2::numeric
      END AS average_inventory_cost
    FROM movement_groups
    LEFT JOIN opening_grouped USING (group_id)
    LEFT JOIN closing_grouped USING (group_id)
  ),
  calculated AS (
    SELECT
      values.*,
      CASE
        WHEN values.outflow_cost IS NULL
          OR values.average_inventory_cost IS NULL
          OR values.average_inventory_cost <= 0
        THEN NULL
        ELSE values.outflow_cost / values.average_inventory_cost
      END AS turnover_ratio
    FROM values
  )
  SELECT
    calculated.group_id,
    calculated.group_name,
    calculated.sku_code,
    calculated.outflow_cost,
    calculated.average_inventory_cost,
    calculated.turnover_ratio,
    CASE
      WHEN calculated.turnover_ratio IS NULL
        OR calculated.turnover_ratio <= 0
      THEN NULL
      ELSE ((p_to - p_from) + 1)::numeric / calculated.turnover_ratio
    END AS turnover_days
  FROM calculated
  ORDER BY calculated.outflow_cost DESC NULLS LAST,
           calculated.group_name, calculated.group_id;
$function$;

CREATE OR REPLACE FUNCTION public.commit_ozon_operation_candidate_v2(
  p_workspace_id UUID,
  p_candidate_id UUID,
  p_evidence_hash TEXT,
  p_operation JSONB
)
RETURNS TABLE(operation_id UUID, skipped BOOLEAN)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_candidate public.marketplace_operation_candidates%ROWTYPE;
  v_operation_id UUID;
  v_type TEXT;
  v_date DATE;
  v_comment TEXT;
  v_item_count INTEGER;
BEGIN
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_workspace_id::text, 91742)
  );
  SELECT * INTO v_candidate
  FROM public.marketplace_operation_candidates
  WHERE id = p_candidate_id AND workspace_id = p_workspace_id
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon candidate not found' USING ERRCODE = 'P0002';
  END IF;
  IF v_candidate.created_operation_id IS NOT NULL THEN
    IF NOT EXISTS (
      SELECT 1 FROM public.operations op
      WHERE op.id = v_candidate.created_operation_id
        AND op.workspace_id = p_workspace_id
    ) THEN
      RAISE EXCEPTION 'Ozon candidate operation link is out of scope'
        USING ERRCODE = '42501';
    END IF;
    RETURN QUERY SELECT v_candidate.created_operation_id, true;
    RETURN;
  END IF;
  IF v_candidate.status <> 'approved' THEN
    RAISE EXCEPTION 'Ozon candidate is not approved' USING ERRCODE = '55000';
  END IF;
  IF v_candidate.evidence_version <> 1
    OR v_candidate.evidence_hash IS NULL
    OR v_candidate.evidence_hash <> p_evidence_hash
  THEN
    RAISE EXCEPTION 'Ozon candidate evidence is stale' USING ERRCODE = '55000';
  END IF;
  IF v_candidate.provider <> 'ozon'
    OR v_candidate.normalized_operation IS DISTINCT FROM p_operation
  THEN
    RAISE EXCEPTION 'Ozon candidate operation is stale' USING ERRCODE = '55000';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM public.marketplace_connections connection
    WHERE connection.id = v_candidate.connection_id
      AND connection.workspace_id = p_workspace_id
      AND connection.provider = 'ozon'
  ) THEN
    RAISE EXCEPTION 'Ozon candidate connection is out of scope'
      USING ERRCODE = '42501';
  END IF;
  IF EXISTS (
    SELECT 1
    FROM public.marketplace_operation_commit_claims claim
    WHERE claim.workspace_id = p_workspace_id
      AND claim.provider = v_candidate.provider
      AND claim.source_type = v_candidate.source_type
      AND claim.external_event_id = v_candidate.external_event_id
      AND claim.candidate_id <> v_candidate.id
  ) THEN
    RAISE EXCEPTION 'Ozon candidate source was already committed'
      USING ERRCODE = '23505';
  END IF;
  IF jsonb_typeof(p_operation -> 'items') <> 'array' THEN
    RAISE EXCEPTION 'Ozon candidate items are incomplete' USING ERRCODE = '22023';
  END IF;

  v_type := p_operation ->> 'type';
  v_date := NULLIF(p_operation ->> 'operationDate', '')::date;
  v_comment := NULLIF(p_operation ->> 'comment', '');
  IF v_type NOT IN ('sale', 'return', 'write_off', 'transfer', 'defect',
                    'inventory_adjustment')
    OR v_date IS NULL
  THEN
    RAISE EXCEPTION 'Ozon candidate operation is invalid' USING ERRCODE = '22023';
  END IF;

  SELECT count(*) INTO v_item_count
  FROM jsonb_array_elements(p_operation -> 'items') item
  WHERE NULLIF(item ->> 'productId', '') IS NOT NULL
    AND NULLIF(item ->> 'warehouseId', '') IS NOT NULL
    AND item ->> 'direction' IN ('in', 'out')
    AND (item ->> 'quantity')::numeric > 0
    AND (
      NULLIF(item ->> 'unitPrice', '') IS NULL
      OR (item ->> 'unitPrice')::numeric >= 0
    )
    AND EXISTS (
      SELECT 1 FROM public.products p
      WHERE p.id = (item ->> 'productId')::uuid
        AND p.workspace_id = p_workspace_id
    )
    AND EXISTS (
      SELECT 1 FROM public.warehouses w
      WHERE w.id = (item ->> 'warehouseId')::uuid
        AND w.workspace_id = p_workspace_id
    )
    AND (
      NULLIF(item ->> 'storeId', '') IS NULL
      OR EXISTS (
        SELECT 1 FROM public.stores s
        WHERE s.id = (item ->> 'storeId')::uuid
          AND s.workspace_id = p_workspace_id
      )
    );
  IF v_item_count <> jsonb_array_length(p_operation -> 'items')
    OR v_item_count = 0
  THEN
    RAISE EXCEPTION 'Ozon candidate mappings are incomplete' USING ERRCODE = '22023';
  END IF;

  IF (
    v_type IN ('sale', 'write_off')
    AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(p_operation -> 'items') item
      WHERE item ->> 'direction' <> 'out'
    )
  ) OR (
    v_type IN ('return', 'inventory_adjustment')
    AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(p_operation -> 'items') item
      WHERE item ->> 'direction' <> 'in'
    )
  ) OR (
    v_type = 'inventory_adjustment'
    AND EXISTS (
      SELECT 1 FROM jsonb_array_elements(p_operation -> 'items') item
      WHERE NULLIF(item ->> 'unitPrice', '') IS NULL
        OR (item ->> 'unitPrice')::numeric <= 0
    )
  ) OR (
    v_type = 'defect'
    AND (
      jsonb_array_length(p_operation -> 'items') <> 1
      OR (p_operation -> 'items' -> 0 ->> 'direction') <> 'out'
    )
  ) THEN
    RAISE EXCEPTION 'Ozon candidate item directions are invalid'
      USING ERRCODE = '22023';
  END IF;

  IF v_type = 'transfer' AND (
    (SELECT count(*) FROM jsonb_array_elements(p_operation -> 'items') item
      WHERE item ->> 'direction' = 'out') <> 1
    OR
    (SELECT count(*) FROM jsonb_array_elements(p_operation -> 'items') item
      WHERE item ->> 'direction' = 'in') <> 1
    OR
    (SELECT item ->> 'productId'
       FROM jsonb_array_elements(p_operation -> 'items') item
       WHERE item ->> 'direction' = 'out')
      IS DISTINCT FROM
    (SELECT item ->> 'productId'
       FROM jsonb_array_elements(p_operation -> 'items') item
       WHERE item ->> 'direction' = 'in')
    OR
    (SELECT (item ->> 'quantity')::numeric
       FROM jsonb_array_elements(p_operation -> 'items') item
       WHERE item ->> 'direction' = 'out')
      IS DISTINCT FROM
    (SELECT (item ->> 'quantity')::numeric
       FROM jsonb_array_elements(p_operation -> 'items') item
       WHERE item ->> 'direction' = 'in')
    OR
    (SELECT item ->> 'warehouseId'
       FROM jsonb_array_elements(p_operation -> 'items') item
       WHERE item ->> 'direction' = 'out')
      IS NOT DISTINCT FROM
    (SELECT item ->> 'warehouseId'
       FROM jsonb_array_elements(p_operation -> 'items') item
       WHERE item ->> 'direction' = 'in')
  ) THEN
    RAISE EXCEPTION 'Ozon transfer evidence is asymmetric' USING ERRCODE = '22023';
  END IF;

  INSERT INTO public.operations (
    workspace_id, type, operation_date, comment
  ) VALUES (
    p_workspace_id, v_type, v_date, v_comment
  ) RETURNING id INTO v_operation_id;

  INSERT INTO public.operation_items (
    operation_id, product_id, warehouse_id, quantity, unit_price,
    direction, store_id, quality_status
  )
  SELECT
    v_operation_id,
    (item ->> 'productId')::uuid,
    (item ->> 'warehouseId')::uuid,
    (item ->> 'quantity')::numeric,
    NULLIF(item ->> 'unitPrice', '')::numeric,
    item ->> 'direction',
    NULLIF(item ->> 'storeId', '')::uuid,
    CASE WHEN v_type = 'defect' AND item ->> 'direction' = 'in'
      THEN 'defect' ELSE 'ordinary' END
  FROM jsonb_array_elements(p_operation -> 'items') item;

  IF v_type = 'defect' THEN
    INSERT INTO public.operation_items (
      operation_id, product_id, warehouse_id, quantity, unit_price,
      direction, store_id, quality_status
    )
    SELECT
      v_operation_id,
      (item ->> 'productId')::uuid,
      (item ->> 'warehouseId')::uuid,
      (item ->> 'quantity')::numeric,
      NULL,
      'in',
      NULLIF(item ->> 'storeId', '')::uuid,
      'defect'
    FROM jsonb_array_elements(p_operation -> 'items') item;
  END IF;

  PERFORM public.rebuild_inventory_reporting(p_workspace_id);

  INSERT INTO public.marketplace_operation_commit_claims (
    workspace_id, connection_id, candidate_id, provider, source_type,
    external_event_id, status, operation_id, committed_at
  ) VALUES (
    p_workspace_id, v_candidate.connection_id, v_candidate.id,
    v_candidate.provider, v_candidate.source_type,
    v_candidate.external_event_id, 'committed', v_operation_id, clock_timestamp()
  )
  ON CONFLICT (candidate_id) DO UPDATE
  SET status = 'committed',
      operation_id = EXCLUDED.operation_id,
      error = NULL,
      committed_at = clock_timestamp(),
      failed_at = NULL;

  UPDATE public.marketplace_operation_candidates
  SET status = 'committed',
      normalized_operation = p_operation,
      validation_errors = '[]'::jsonb,
      created_operation_id = v_operation_id
  WHERE id = v_candidate.id;

  RETURN QUERY SELECT v_operation_id, false;
END;
$function$;

-- A SELECT policy plus a FOR ALL policy is evaluated as two permissive policies.
-- Keep member reads separate from the equivalent admin-only write permissions.
DO $block$
DECLARE
  v_table TEXT;
  v_policy RECORD;
BEGIN
  FOREACH v_table IN ARRAY ARRAY[
    'marketplace_connections', 'marketplace_sync_runs',
    'marketplace_operation_candidates',
    'marketplace_operation_commit_claims', 'ozon_warehouses', 'ozon_products',
    'ozon_stock_snapshots', 'ozon_postings', 'ozon_posting_items',
    'ozon_returns', 'ozon_finance_transactions', 'ozon_report_runs',
    'ozon_legal_entity_sales', 'ozon_unpaid_legal_products',
    'ozon_finance_reports', 'ozon_removals', 'ozon_supply_orders',
    'ozon_supply_order_items', 'ozon_stock_analytics',
    'ozon_turnover_analytics', 'ozon_discounted_products',
    'inventory_movements', 'product_balances', 'operations'
  ]
  LOOP
    FOR v_policy IN
      SELECT policyname
      FROM pg_catalog.pg_policies
      WHERE schemaname = 'public'
        AND tablename = v_table
        AND cmd = 'ALL'
    LOOP
      EXECUTE format(
        'DROP POLICY %I ON public.%I',
        v_policy.policyname,
        v_table
      );
    END LOOP;

    EXECUTE format(
      'DROP POLICY IF EXISTS %I ON public.%I',
      v_table || '_insert_admin_v2',
      v_table
    );
    EXECUTE format(
      'DROP POLICY IF EXISTS %I ON public.%I',
      v_table || '_update_admin_v2',
      v_table
    );
    EXECUTE format(
      'DROP POLICY IF EXISTS %I ON public.%I',
      v_table || '_delete_admin_v2',
      v_table
    );
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR INSERT TO authenticated
       WITH CHECK (public.app_has_org_role(workspace_id, array[''owner'', ''admin'']))',
      v_table || '_insert_admin_v2',
      v_table
    );
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR UPDATE TO authenticated
       USING (public.app_has_org_role(workspace_id, array[''owner'', ''admin'']))
       WITH CHECK (public.app_has_org_role(workspace_id, array[''owner'', ''admin'']))',
      v_table || '_update_admin_v2',
      v_table
    );
    EXECUTE format(
      'CREATE POLICY %I ON public.%I FOR DELETE TO authenticated
       USING (public.app_has_org_role(workspace_id, array[''owner'', ''admin'']))',
      v_table || '_delete_admin_v2',
      v_table
    );
  END LOOP;
END;
$block$;

-- operation_items is scoped through its parent operation and therefore cannot
-- use the generic workspace_id policy block above.
DROP POLICY IF EXISTS "operation_items_write_admin"
  ON public.operation_items;
DROP POLICY IF EXISTS "operation_items_insert_admin_v2"
  ON public.operation_items;
DROP POLICY IF EXISTS "operation_items_update_admin_v2"
  ON public.operation_items;
DROP POLICY IF EXISTS "operation_items_delete_admin_v2"
  ON public.operation_items;
CREATE POLICY "operation_items_insert_admin_v2"
  ON public.operation_items
  FOR INSERT TO authenticated
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM public.operations operation
      WHERE operation.id = operation_id
        AND public.app_has_org_role(
          operation.workspace_id, array['owner', 'admin']
        )
    )
  );
CREATE POLICY "operation_items_update_admin_v2"
  ON public.operation_items
  FOR UPDATE TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public.operations operation
      WHERE operation.id = operation_id
        AND public.app_has_org_role(
          operation.workspace_id, array['owner', 'admin']
        )
    )
  )
  WITH CHECK (
    EXISTS (
      SELECT 1 FROM public.operations operation
      WHERE operation.id = operation_id
        AND public.app_has_org_role(
          operation.workspace_id, array['owner', 'admin']
        )
    )
  );
CREATE POLICY "operation_items_delete_admin_v2"
  ON public.operation_items
  FOR DELETE TO authenticated
  USING (
    EXISTS (
      SELECT 1 FROM public.operations operation
      WHERE operation.id = operation_id
        AND public.app_has_org_role(
          operation.workspace_id, array['owner', 'admin']
        )
    )
  );

REVOKE EXECUTE ON FUNCTION public.replace_ozon_supply_order_items_v2(uuid, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.replace_ozon_posting_items_v2(uuid, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.replace_ozon_posting_with_items_v2(jsonb, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.replace_ozon_supply_order_with_items_v2(jsonb, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.commit_ozon_operation_candidate_v2(uuid, uuid, text, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.protect_ozon_candidate_evidence()
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.rebuild_inventory_reporting(uuid)
  FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_inventory_balances_at_date(uuid, date)
  FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_product_movement(uuid, date, date)
  FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_inventory_balances_v2(
  uuid, date, uuid, uuid, uuid, uuid, text, text, boolean, boolean
) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_inventory_balances_grouped_v2(
  uuid, date, text, uuid, uuid, uuid, uuid, text, text, boolean, boolean
) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_product_movement_v2(
  uuid, date, date, text, uuid, uuid, uuid, uuid, text
) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_defect_dynamics_v2(
  uuid, date, date, text, uuid, uuid, uuid, uuid
) FROM PUBLIC, anon;
REVOKE EXECUTE ON FUNCTION public.report_turnover_v2(
  uuid, date, date, text, uuid, uuid, uuid, uuid
) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.replace_ozon_supply_order_items_v2(uuid, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.replace_ozon_posting_items_v2(uuid, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.replace_ozon_posting_with_items_v2(jsonb, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.replace_ozon_supply_order_with_items_v2(jsonb, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.commit_ozon_operation_candidate_v2(uuid, uuid, text, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.rebuild_inventory_reporting(uuid)
  TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_inventory_balances_at_date(uuid, date)
  TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_product_movement(uuid, date, date)
  TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_inventory_balances_v2(
  uuid, date, uuid, uuid, uuid, uuid, text, text, boolean, boolean
) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_inventory_balances_grouped_v2(
  uuid, date, text, uuid, uuid, uuid, uuid, text, text, boolean, boolean
) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_product_movement_v2(
  uuid, date, date, text, uuid, uuid, uuid, uuid, text
) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_defect_dynamics_v2(
  uuid, date, date, text, uuid, uuid, uuid, uuid
) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.report_turnover_v2(
  uuid, date, date, text, uuid, uuid, uuid, uuid
) TO authenticated, service_role;

NOTIFY pgrst, 'reload schema';
