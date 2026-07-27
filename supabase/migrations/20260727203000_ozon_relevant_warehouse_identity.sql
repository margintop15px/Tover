-- Match referenced Ozon warehouses by name only when the source contract did
-- not provide an ID. This prevents a global warehouse with the same normalized
-- name as a seller-referenced warehouse from appearing in account counts.

CREATE OR REPLACE FUNCTION public.ozon_relevant_warehouse_counts(
  p_workspace_id UUID,
  p_connection_id UUID
)
RETURNS TABLE(
  warehouses BIGINT,
  unmapped_warehouses BIGINT
)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = ''
AS $function$
  WITH referenced AS (
    SELECT ozon_warehouse_id, warehouse_name
    FROM public.ozon_returns
    WHERE workspace_id = p_workspace_id
      AND connection_id = p_connection_id
    UNION
    SELECT NULL, warehouse_name
    FROM public.ozon_postings
    WHERE workspace_id = p_workspace_id
      AND connection_id = p_connection_id
    UNION
    SELECT ozon_warehouse_id, warehouse_name
    FROM public.ozon_removals
    WHERE workspace_id = p_workspace_id
      AND connection_id = p_connection_id
    UNION
    SELECT ozon_warehouse_id, warehouse_name
    FROM public.ozon_supply_orders
    WHERE workspace_id = p_workspace_id
      AND connection_id = p_connection_id
    UNION
    SELECT ozon_storage_warehouse_id, storage_warehouse_name
    FROM public.ozon_supply_order_items
    WHERE workspace_id = p_workspace_id
      AND connection_id = p_connection_id
    UNION
    SELECT ozon_warehouse_id, warehouse_name
    FROM public.ozon_stock_analytics
    WHERE workspace_id = p_workspace_id
      AND connection_id = p_connection_id
  ),
  relevant AS (
    SELECT warehouse.*
    FROM public.ozon_warehouses warehouse
    WHERE warehouse.workspace_id = p_workspace_id
      AND warehouse.connection_id = p_connection_id
      AND (
        lower(COALESCE(warehouse.fulfillment_schema, '')) IN (
          'fbs', 'rfbs', 'fbo_seller'
        )
        OR warehouse.local_warehouse_id IS NOT NULL
        OR warehouse.mapping_status IN ('auto_matched', 'manual', 'ignored')
        OR EXISTS (
          SELECT 1
          FROM referenced reference
          WHERE (
              reference.ozon_warehouse_id IS NOT NULL
              AND reference.ozon_warehouse_id = warehouse.ozon_warehouse_id
            )
            OR (
              reference.ozon_warehouse_id IS NULL
              AND reference.warehouse_name IS NOT NULL
              AND lower(trim(reference.warehouse_name)) =
                lower(trim(warehouse.name))
            )
        )
      )
  )
  SELECT
    count(*)::BIGINT,
    count(*) FILTER (
      WHERE local_warehouse_id IS NULL
        AND mapping_status = 'unmapped'
    )::BIGINT
  FROM relevant;
$function$;

ALTER FUNCTION public._sanitize_ozon_sync_step_error(JSONB) STABLE;
