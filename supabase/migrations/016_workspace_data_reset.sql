-- Owner-only workspace data reset.
-- Keeps organization shell, team membership, invites, workspace settings, and
-- marketplace connection credentials; removes operational/master/report data.

CREATE OR REPLACE FUNCTION public.reset_workspace_account_data(
  p_workspace_id UUID,
  p_confirmation TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_user_id UUID := auth.uid();
  v_deleted INTEGER;
  v_result JSONB := '{}'::jsonb;
BEGIN
  IF p_confirmation IS DISTINCT FROM 'RESET' THEN
    RAISE EXCEPTION 'RESET confirmation is required'
      USING ERRCODE = '22023';
  END IF;

  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Not authenticated'
      USING ERRCODE = '42501';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM public.organization_memberships m
    WHERE m.organization_id = p_workspace_id
      AND m.user_id = v_user_id
      AND m.status = 'active'
      AND m.role_id = 'owner'
  ) THEN
    RAISE EXCEPTION 'Only workspace owners can reset account data'
      USING ERRCODE = '42501';
  END IF;

  -- Marketplace mirrors and staged operation candidates. Connections survive.
  DELETE FROM public.marketplace_operation_commit_claims
  WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('marketplace_operation_commit_claims', v_deleted);

  DELETE FROM public.ozon_posting_items WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_posting_items', v_deleted);

  DELETE FROM public.ozon_supply_order_items WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_supply_order_items', v_deleted);

  DELETE FROM public.ozon_legal_entity_sales WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_legal_entity_sales', v_deleted);

  DELETE FROM public.ozon_unpaid_legal_products WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_unpaid_legal_products', v_deleted);

  DELETE FROM public.ozon_finance_reports WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_finance_reports', v_deleted);

  DELETE FROM public.ozon_removals WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_removals', v_deleted);

  DELETE FROM public.ozon_supply_orders WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_supply_orders', v_deleted);

  DELETE FROM public.ozon_stock_analytics WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_stock_analytics', v_deleted);

  DELETE FROM public.ozon_turnover_analytics WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_turnover_analytics', v_deleted);

  DELETE FROM public.ozon_discounted_products WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_discounted_products', v_deleted);

  DELETE FROM public.ozon_stock_snapshots WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_stock_snapshots', v_deleted);

  DELETE FROM public.ozon_postings WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_postings', v_deleted);

  DELETE FROM public.ozon_returns WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_returns', v_deleted);

  DELETE FROM public.ozon_finance_transactions WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_finance_transactions', v_deleted);

  DELETE FROM public.ozon_report_runs WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_report_runs', v_deleted);

  DELETE FROM public.ozon_products WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_products', v_deleted);

  DELETE FROM public.ozon_warehouses WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('ozon_warehouses', v_deleted);

  DELETE FROM public.marketplace_sync_runs WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('marketplace_sync_runs', v_deleted);

  DELETE FROM public.marketplace_operation_candidates WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('marketplace_operation_candidates', v_deleted);

  UPDATE public.marketplace_connections
  SET
    last_sync_at = NULL,
    last_sync_status = NULL,
    last_sync_error = NULL,
    updated_at = now()
  WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('marketplace_connections_reset', v_deleted);

  -- Import review state and operation evidence.
  DELETE FROM public.operation_import_committed_operations WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('operation_import_committed_operations', v_deleted);

  DELETE FROM public.operation_import_fingerprints WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('operation_import_fingerprints', v_deleted);

  DELETE FROM public.operation_import_candidates WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('operation_import_candidates', v_deleted);

  DELETE FROM public.operation_imports WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('operation_imports', v_deleted);

  DELETE FROM public.inventory_movements WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('inventory_movements', v_deleted);

  DELETE FROM public.operation_items oi
  USING public.operations o
  WHERE oi.operation_id = o.id
    AND o.workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('operation_items', v_deleted);

  DELETE FROM public.operations WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('operations', v_deleted);

  -- Legacy/manual import surfaces.
  DELETE FROM public.import_errors e
  USING public.imports i
  WHERE e.import_id = i.id
    AND i.workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('import_errors', v_deleted);

  DELETE FROM public.imports WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('imports', v_deleted);

  DELETE FROM public.payments WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('payments', v_deleted);

  DELETE FROM public.order_lines l
  USING public.orders o
  WHERE l.order_id = o.id
    AND o.workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('order_lines', v_deleted);

  DELETE FROM public.orders WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('orders', v_deleted);

  DELETE FROM public.inventory_snapshots WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('inventory_snapshots', v_deleted);

  -- Reports and master data.
  DELETE FROM public.report_templates WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('report_templates', v_deleted);

  DELETE FROM public.product_balances WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('product_balances', v_deleted);

  DELETE FROM public.products WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('products', v_deleted);

  DELETE FROM public.categories WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('categories', v_deleted);

  DELETE FROM public.stores WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('stores', v_deleted);

  DELETE FROM public.warehouses WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('warehouses', v_deleted);

  DELETE FROM public.suppliers WHERE workspace_id = p_workspace_id;
  GET DIAGNOSTICS v_deleted = ROW_COUNT;
  v_result := v_result || jsonb_build_object('suppliers', v_deleted);

  RETURN jsonb_build_object(
    'workspaceId', p_workspace_id,
    'deleted', v_result
  );
END;
$$;

GRANT EXECUTE ON FUNCTION public.reset_workspace_account_data(UUID, TEXT)
  TO authenticated;
