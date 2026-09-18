-- Reset deletes are workspace-scoped, but foreign-key checks must search the
-- entire referencing table, including other workspaces, once per deleted row.
CREATE INDEX IF NOT EXISTS idx_operation_import_candidates_duplicate_of
  ON public.operation_import_candidates (duplicate_of);
CREATE INDEX IF NOT EXISTS idx_operation_import_candidates_created_operation_id
  ON public.operation_import_candidates (created_operation_id);
CREATE INDEX IF NOT EXISTS idx_ozon_posting_items_posting_id
  ON public.ozon_posting_items (posting_id);
CREATE INDEX IF NOT EXISTS idx_ozon_stock_snapshots_local_product_id
  ON public.ozon_stock_snapshots (local_product_id);
CREATE INDEX IF NOT EXISTS idx_ozon_stock_snapshots_local_warehouse_id
  ON public.ozon_stock_snapshots (local_warehouse_id);

-- PostgREST reads this per-function setting before invoking the RPC. Keep the
-- reset atomic and give it bounded headroom above authenticated's default 8s;
-- ordinary requests retain their existing timeout and authorization checks.
ALTER FUNCTION public.reset_workspace_account_data(UUID, TEXT)
  SET statement_timeout = '60s';

NOTIFY pgrst, 'reload schema';
