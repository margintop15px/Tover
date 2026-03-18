-- Migration: 004_report_functions.sql
-- Report RPC functions and supporting index

-- ============================================================
-- 1. INDEX
-- ============================================================

CREATE INDEX idx_operation_items_product_warehouse
  ON public.operation_items(product_id, warehouse_id, direction);

-- ============================================================
-- 2. RPC: report_inventory_balances_at_date
-- Forward-replays all operation_items up to target_date to compute
-- historical inventory balances per product per warehouse.
-- ============================================================

CREATE OR REPLACE FUNCTION public.report_inventory_balances_at_date(
  p_workspace_id UUID,
  p_target_date DATE
)
RETURNS TABLE(
  product_id UUID,
  warehouse_id UUID,
  quantity NUMERIC
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    oi.product_id,
    oi.warehouse_id,
    SUM(
      CASE WHEN oi.direction = 'in' THEN oi.quantity
           ELSE -oi.quantity
      END
    ) AS quantity
  FROM public.operation_items oi
  JOIN public.operations o ON o.id = oi.operation_id
  WHERE o.workspace_id = p_workspace_id
    AND o.operation_date <= p_target_date
  GROUP BY oi.product_id, oi.warehouse_id;
$$;

-- ============================================================
-- 3. RPC: report_product_movement
-- Aggregates operation_items by product, warehouse, operation type,
-- and direction within a date range.
-- ============================================================

CREATE OR REPLACE FUNCTION public.report_product_movement(
  p_workspace_id UUID,
  p_from DATE,
  p_to DATE
)
RETURNS TABLE(
  product_id UUID,
  warehouse_id UUID,
  operation_type TEXT,
  direction TEXT,
  total_quantity NUMERIC
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    oi.product_id,
    oi.warehouse_id,
    o.type AS operation_type,
    oi.direction,
    SUM(oi.quantity) AS total_quantity
  FROM public.operation_items oi
  JOIN public.operations o ON o.id = oi.operation_id
  WHERE o.workspace_id = p_workspace_id
    AND o.operation_date >= p_from
    AND o.operation_date <= p_to
  GROUP BY oi.product_id, oi.warehouse_id, o.type, oi.direction;
$$;

-- ============================================================
-- 4. RPC: report_supplier_debt
-- Computes all-time purchased/paid totals (up to as_of_date)
-- and period-specific amounts per supplier.
-- ============================================================

CREATE OR REPLACE FUNCTION public.report_supplier_debt(
  p_workspace_id UUID,
  p_as_of_date DATE,
  p_period_from DATE,
  p_period_to DATE
)
RETURNS TABLE(
  supplier_id UUID,
  purchased_total NUMERIC,
  paid_total NUMERIC,
  purchased_in_period NUMERIC,
  paid_in_period NUMERIC
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    s.id AS supplier_id,
    -- All-time purchased total (up to as_of_date)
    COALESCE((
      SELECT SUM(oi.quantity * COALESCE(oi.unit_price, 0))
      FROM public.operations o
      JOIN public.operation_items oi ON oi.operation_id = o.id
      WHERE o.workspace_id = p_workspace_id
        AND o.supplier_id = s.id
        AND o.type = 'purchase'
        AND o.operation_date <= p_as_of_date
    ), 0) AS purchased_total,
    -- All-time paid total (up to as_of_date)
    COALESCE((
      SELECT SUM(o.payment_amount)
      FROM public.operations o
      WHERE o.workspace_id = p_workspace_id
        AND o.supplier_id = s.id
        AND o.type = 'payment'
        AND o.operation_date <= p_as_of_date
    ), 0) AS paid_total,
    -- Period purchased
    COALESCE((
      SELECT SUM(oi.quantity * COALESCE(oi.unit_price, 0))
      FROM public.operations o
      JOIN public.operation_items oi ON oi.operation_id = o.id
      WHERE o.workspace_id = p_workspace_id
        AND o.supplier_id = s.id
        AND o.type = 'purchase'
        AND o.operation_date >= p_period_from
        AND o.operation_date <= p_period_to
    ), 0) AS purchased_in_period,
    -- Period paid
    COALESCE((
      SELECT SUM(o.payment_amount)
      FROM public.operations o
      WHERE o.workspace_id = p_workspace_id
        AND o.supplier_id = s.id
        AND o.type = 'payment'
        AND o.operation_date >= p_period_from
        AND o.operation_date <= p_period_to
    ), 0) AS paid_in_period
  FROM public.suppliers s
  WHERE s.workspace_id = p_workspace_id;
$$;
