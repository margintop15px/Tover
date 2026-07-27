-- Live-contract follow-up: count only seller-relevant Ozon warehouses and
-- make service-role access explicit for fresh Supabase projects.

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
        lower(COALESCE(warehouse.fulfillment_schema, '')) IN ('fbs', 'rfbs')
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
              reference.warehouse_name IS NOT NULL
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

-- Two workers can select different pending rows for the same connection before
-- either transaction makes its running lease visible. Serialize the short
-- claim transaction globally; the lock is released before domain execution,
-- so work for different connections still runs concurrently.
CREATE OR REPLACE FUNCTION public.claim_ozon_sync_run_step_v2(
  p_run_id UUID DEFAULT NULL
)
RETURNS SETOF public.marketplace_sync_run_steps
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_step public.marketplace_sync_run_steps%ROWTYPE;
  v_now TIMESTAMPTZ := clock_timestamp();
BEGIN
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('ozon-sync-step-claim', 91743)
  );

  SELECT steps.*
  INTO v_step
  FROM public.marketplace_sync_run_steps AS steps
  JOIN public.marketplace_sync_runs AS runs ON runs.id = steps.run_id
  WHERE (p_run_id IS NULL OR steps.run_id = p_run_id)
    AND runs.status IN ('running', 'retrying')
    AND (
      (steps.state = 'pending' AND
        (steps.next_attempt_at IS NULL OR steps.next_attempt_at <= v_now))
      OR (steps.state = 'retry_scheduled' AND steps.next_attempt_at <= v_now)
      OR (steps.state = 'running' AND steps.lease_expires_at <= v_now)
    )
    AND NOT EXISTS (
      SELECT 1
      FROM public.marketplace_sync_run_steps AS live
      WHERE live.connection_id = steps.connection_id
        AND live.state = 'running'
        AND live.lease_expires_at > v_now
    )
    AND NOT EXISTS (
      SELECT 1
      FROM public.marketplace_sync_run_steps AS dependency
      WHERE dependency.run_id = steps.run_id
        AND dependency.step_key = ANY (
          CASE steps.step_key
            WHEN 'stocks' THEN ARRAY['products']
            WHEN 'postings' THEN ARRAY['warehouses', 'products']
            WHEN 'returns' THEN ARRAY['warehouses', 'products']
            WHEN 'supplies' THEN ARRAY['warehouses', 'products']
            WHEN 'analytics' THEN ARRAY['products']
            WHEN 'discountedProducts' THEN ARRAY['products']
            ELSE ARRAY[]::text[]
          END
        )
        AND dependency.state NOT IN ('completed', 'skipped')
    )
  ORDER BY
    CASE WHEN steps.state = 'running' THEN 0 ELSE 1 END,
    COALESCE(steps.next_attempt_at, steps.lease_expires_at, steps.created_at),
    steps.step_order
  FOR UPDATE OF steps SKIP LOCKED
  LIMIT 1;

  IF NOT FOUND THEN RETURN; END IF;

  UPDATE public.marketplace_sync_run_steps
  SET state = 'running',
      attempt_count = attempt_count + 1,
      lease_token = gen_random_uuid(),
      lease_expires_at = v_now + interval '10 minutes',
      next_attempt_at = NULL,
      started_at = COALESCE(started_at, v_now),
      completed_at = NULL
  WHERE id = v_step.id
  RETURNING * INTO v_step;

  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, phase, processed, total
  ) VALUES (
    v_step.run_id, v_step.id, v_step.workspace_id, v_step.connection_id,
    v_step.step_key, 'claimed', v_step.attempt_count, v_step.failure_count,
    v_step.checkpoint ->> 'phase',
    NULLIF(v_step.checkpoint ->> 'processed', '')::integer,
    NULLIF(v_step.checkpoint ->> 'total', '')::integer
  );

  PERFORM public._refresh_ozon_sync_run_v2(v_step.run_id);
  RETURN NEXT v_step;
END;
$function$;

-- SQLSTATE 40001 asks database clients to retry a transaction. A stale lease is
-- deterministic, so expose it as object_not_in_prerequisite_state instead of
-- making PostgREST retry or hold the request until its transaction timeout.
DO $block$
BEGIN
  IF to_regprocedure(
    'public.checkpoint_ozon_sync_run_step_v2_legacy_40001(uuid,uuid,jsonb,jsonb)'
  ) IS NULL THEN
    ALTER FUNCTION public.checkpoint_ozon_sync_run_step_v2(
      UUID, UUID, JSONB, JSONB
    ) RENAME TO checkpoint_ozon_sync_run_step_v2_legacy_40001;
  END IF;
  IF to_regprocedure(
    'public.yield_ozon_sync_run_step_v2_legacy_40001(uuid,uuid,jsonb,jsonb,timestamp with time zone)'
  ) IS NULL THEN
    ALTER FUNCTION public.yield_ozon_sync_run_step_v2(
      UUID, UUID, JSONB, JSONB, TIMESTAMPTZ
    ) RENAME TO yield_ozon_sync_run_step_v2_legacy_40001;
  END IF;
  IF to_regprocedure(
    'public.finish_ozon_sync_run_step_v2_legacy_40001(uuid,uuid,text,jsonb,jsonb,timestamp with time zone)'
  ) IS NULL THEN
    ALTER FUNCTION public.finish_ozon_sync_run_step_v2(
      UUID, UUID, TEXT, JSONB, JSONB, TIMESTAMPTZ
    ) RENAME TO finish_ozon_sync_run_step_v2_legacy_40001;
  END IF;
  IF to_regprocedure(
    'public.fail_ozon_sync_connection_v2_legacy_40001(uuid,uuid,jsonb)'
  ) IS NULL THEN
    ALTER FUNCTION public.fail_ozon_sync_connection_v2(
      UUID, UUID, JSONB
    ) RENAME TO fail_ozon_sync_connection_v2_legacy_40001;
  END IF;
END;
$block$;

CREATE OR REPLACE FUNCTION public.checkpoint_ozon_sync_run_step_v2(
  p_step_id UUID,
  p_lease_token UUID,
  p_checkpoint JSONB,
  p_summary JSONB DEFAULT '{}'::jsonb
)
RETURNS public.marketplace_sync_run_steps
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
BEGIN
  RETURN public.checkpoint_ozon_sync_run_step_v2_legacy_40001(
    p_step_id, p_lease_token, p_checkpoint, p_summary
  );
EXCEPTION
  WHEN serialization_failure THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION public.yield_ozon_sync_run_step_v2(
  p_step_id UUID,
  p_lease_token UUID,
  p_checkpoint JSONB,
  p_summary JSONB DEFAULT '{}'::jsonb,
  p_next_attempt_at TIMESTAMPTZ DEFAULT clock_timestamp()
)
RETURNS public.marketplace_sync_run_steps
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
BEGIN
  RETURN public.yield_ozon_sync_run_step_v2_legacy_40001(
    p_step_id, p_lease_token, p_checkpoint, p_summary, p_next_attempt_at
  );
EXCEPTION
  WHEN serialization_failure THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION public.finish_ozon_sync_run_step_v2(
  p_step_id UUID,
  p_lease_token UUID,
  p_state TEXT,
  p_summary JSONB DEFAULT '{}'::jsonb,
  p_last_error JSONB DEFAULT NULL,
  p_next_attempt_at TIMESTAMPTZ DEFAULT NULL
)
RETURNS public.marketplace_sync_run_steps
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
BEGIN
  RETURN public.finish_ozon_sync_run_step_v2_legacy_40001(
    p_step_id, p_lease_token, p_state, p_summary, p_last_error,
    p_next_attempt_at
  );
EXCEPTION
  WHEN serialization_failure THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '55000';
END;
$function$;

CREATE OR REPLACE FUNCTION public.fail_ozon_sync_connection_v2(
  p_step_id UUID,
  p_lease_token UUID,
  p_last_error JSONB
)
RETURNS public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
BEGIN
  RETURN public.fail_ozon_sync_connection_v2_legacy_40001(
    p_step_id, p_lease_token, p_last_error
  );
EXCEPTION
  WHEN serialization_failure THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '55000';
END;
$function$;

REVOKE ALL ON FUNCTION public.ozon_relevant_warehouse_counts(uuid, uuid)
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.ozon_relevant_warehouse_counts(uuid, uuid)
  TO authenticated, service_role;

REVOKE ALL ON FUNCTION public.checkpoint_ozon_sync_run_step_v2(
  UUID, UUID, JSONB, JSONB
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.yield_ozon_sync_run_step_v2(
  UUID, UUID, JSONB, JSONB, TIMESTAMPTZ
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.finish_ozon_sync_run_step_v2(
  UUID, UUID, TEXT, JSONB, JSONB, TIMESTAMPTZ
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fail_ozon_sync_connection_v2(
  UUID, UUID, JSONB
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.checkpoint_ozon_sync_run_step_v2_legacy_40001(
  UUID, UUID, JSONB, JSONB
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.yield_ozon_sync_run_step_v2_legacy_40001(
  UUID, UUID, JSONB, JSONB, TIMESTAMPTZ
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.finish_ozon_sync_run_step_v2_legacy_40001(
  UUID, UUID, TEXT, JSONB, JSONB, TIMESTAMPTZ
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fail_ozon_sync_connection_v2_legacy_40001(
  UUID, UUID, JSONB
) FROM PUBLIC, anon, authenticated;

GRANT USAGE ON SCHEMA public TO service_role;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO service_role;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO service_role;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO service_role;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT ALL PRIVILEGES ON TABLES TO service_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT ALL PRIVILEGES ON SEQUENCES TO service_role;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO service_role;
