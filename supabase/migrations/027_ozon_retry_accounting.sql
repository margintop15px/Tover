-- Count every executed Ozon step failure and make operator retries visible.

ALTER TABLE public.marketplace_sync_step_events
  DROP CONSTRAINT IF EXISTS marketplace_sync_step_events_event_type_check;
ALTER TABLE public.marketplace_sync_step_events
  ADD CONSTRAINT marketplace_sync_step_events_event_type_check
  CHECK (event_type IN (
    'claimed', 'checkpointed', 'yielded', 'retry_scheduled', 'failed',
    'skipped', 'completed', 'repair_reset', 'retry_requested'
  ));

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
DECLARE
  v_step public.marketplace_sync_run_steps%ROWTYPE;
  v_error JSONB := public._sanitize_ozon_sync_step_error(p_last_error);
BEGIN
  IF p_state NOT IN ('completed', 'skipped', 'retry_scheduled', 'failed') THEN
    RAISE EXCEPTION 'Invalid Ozon sync step finish state' USING ERRCODE = '22023';
  END IF;
  IF p_state = 'retry_scheduled' AND p_next_attempt_at IS NULL THEN
    RAISE EXCEPTION 'Retry time is required' USING ERRCODE = '22023';
  END IF;
  IF p_state = 'retry_scheduled'
    AND COALESCE((v_error ->> 'retryable')::boolean, false) IS NOT TRUE
  THEN
    RAISE EXCEPTION 'Retryable error metadata is required'
      USING ERRCODE = '22023';
  END IF;

  UPDATE public.marketplace_sync_run_steps
  SET state = p_state,
      failure_count = failure_count +
        CASE
          WHEN p_state IN ('retry_scheduled', 'failed') THEN 1
          ELSE 0
        END,
      summary = CASE
        WHEN p_state IN ('retry_scheduled', 'failed')
          AND (p_summary IS NULL OR p_summary = '{}'::jsonb)
          THEN summary
        ELSE COALESCE(p_summary, '{}'::jsonb)
      END,
      last_error = CASE
        WHEN p_state IN ('retry_scheduled', 'failed')
          THEN v_error
        ELSE NULL
      END,
      next_attempt_at = p_next_attempt_at,
      lease_token = NULL,
      lease_expires_at = NULL,
      checkpoint = CASE
        WHEN p_state IN ('completed', 'skipped') THEN '{}'::jsonb
        ELSE checkpoint
      END,
      last_checkpoint_at = CASE
        WHEN p_state IN ('completed', 'skipped') THEN NULL
        ELSE last_checkpoint_at
      END,
      completed_at = CASE
        WHEN p_state IN ('completed', 'skipped', 'failed')
          THEN clock_timestamp()
        ELSE NULL
      END
  WHERE id = p_step_id
    AND state = 'running'
    AND lease_token = p_lease_token
    AND lease_expires_at > clock_timestamp()
  RETURNING * INTO v_step;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '55000';
  END IF;

  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, phase, processed, total,
    next_action_at, endpoint, http_status, ozon_code, postgres_code,
    operation_name, safe_error
  ) VALUES (
    v_step.run_id, v_step.id, v_step.workspace_id, v_step.connection_id,
    v_step.step_key, p_state, v_step.attempt_count, v_step.failure_count,
    v_step.checkpoint ->> 'phase',
    NULLIF(v_step.checkpoint ->> 'processed', '')::integer,
    NULLIF(v_step.checkpoint ->> 'total', '')::integer,
    v_step.next_attempt_at,
    v_step.last_error ->> 'endpoint',
    NULLIF(v_step.last_error ->> 'status', '')::integer,
    v_step.last_error ->> 'code',
    v_step.last_error ->> 'postgresCode',
    v_step.last_error ->> 'operationName',
    v_step.last_error
  );

  IF p_state = 'failed'
    AND v_step.step_key IN ('warehouses', 'products')
  THEN
    WITH blocked_steps AS (
      UPDATE public.marketplace_sync_run_steps AS blocked
      SET state = 'failed',
          next_attempt_at = NULL,
          lease_token = NULL,
          lease_expires_at = NULL,
          last_error = public._sanitize_ozon_sync_step_error(
            jsonb_build_object(
              'kind', 'client',
              'retryable', false,
              'reason', 'dependency_failed'
            )
          ),
          completed_at = clock_timestamp()
      WHERE blocked.run_id = v_step.run_id
        AND blocked.state IN ('pending', 'retry_scheduled')
        AND (
          (
            v_step.step_key = 'warehouses'
            AND blocked.step_key IN ('postings', 'returns', 'supplies')
          )
          OR (
            v_step.step_key = 'products'
            AND blocked.step_key IN (
              'stocks', 'postings', 'returns', 'supplies', 'analytics',
              'discountedProducts'
            )
          )
        )
      RETURNING blocked.*
    )
    INSERT INTO public.marketplace_sync_step_events (
      run_id, step_id, workspace_id, connection_id, step_key, event_type,
      execution_count, failure_count, safe_error
    )
    SELECT
      run_id, id, workspace_id, connection_id, step_key, 'failed',
      attempt_count, failure_count, last_error
    FROM blocked_steps;
  END IF;

  PERFORM public._refresh_ozon_sync_run_v2(v_step.run_id);
  RETURN v_step;
END;
$function$;

CREATE OR REPLACE FUNCTION public.retry_failed_ozon_sync_run_steps_v2(
  p_run_id UUID
)
RETURNS public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_run public.marketplace_sync_runs%ROWTYPE;
BEGIN
  WITH reset_steps AS (
    UPDATE public.marketplace_sync_run_steps
    SET state = 'pending',
        failure_count = CASE WHEN state = 'failed' THEN 0 ELSE failure_count END,
        next_attempt_at = clock_timestamp(),
        last_error = NULL,
        completed_at = NULL,
        lease_token = NULL,
        lease_expires_at = NULL
    WHERE run_id = p_run_id
      AND state IN ('failed', 'retry_scheduled')
    RETURNING *
  )
  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, phase, processed, total, next_action_at
  )
  SELECT
    run_id, id, workspace_id, connection_id, step_key, 'retry_requested',
    attempt_count, failure_count, checkpoint ->> 'phase',
    NULLIF(checkpoint ->> 'processed', '')::integer,
    NULLIF(checkpoint ->> 'total', '')::integer,
    next_attempt_at
  FROM reset_steps;

  SELECT * INTO v_run FROM public._refresh_ozon_sync_run_v2(p_run_id);
  RETURN v_run;
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
DECLARE
  v_step public.marketplace_sync_run_steps%ROWTYPE;
  v_error JSONB := public._sanitize_ozon_sync_step_error(p_last_error);
  v_run public.marketplace_sync_runs%ROWTYPE;
BEGIN
  SELECT * INTO v_step
  FROM public.marketplace_sync_run_steps
  WHERE id = p_step_id
    AND state = 'running'
    AND lease_token = p_lease_token
    AND lease_expires_at > clock_timestamp()
  FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '55000';
  END IF;

  WITH failed_steps AS (
    UPDATE public.marketplace_sync_run_steps
    SET state = 'failed',
        failure_count = failure_count +
          CASE WHEN id = v_step.id THEN 1 ELSE 0 END,
        next_attempt_at = NULL,
        lease_token = NULL,
        lease_expires_at = NULL,
        last_error = v_error,
        completed_at = clock_timestamp()
    WHERE run_id = v_step.run_id
      AND state IN ('pending', 'running', 'retry_scheduled')
    RETURNING *
  )
  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, endpoint, http_status, ozon_code,
    next_action_at, safe_error
  )
  SELECT
    run_id, id, workspace_id, connection_id, step_key, 'failed',
    attempt_count, failure_count, v_error ->> 'endpoint',
    NULLIF(v_error ->> 'status', '')::integer, v_error ->> 'code',
    NULL, v_error
  FROM failed_steps;

  SELECT * INTO v_run
  FROM public._refresh_ozon_sync_run_v2(v_step.run_id);
  RETURN v_run;
END;
$function$;

REVOKE ALL ON FUNCTION public.finish_ozon_sync_run_step_v2(
  UUID, UUID, TEXT, JSONB, JSONB, TIMESTAMPTZ
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.retry_failed_ozon_sync_run_steps_v2(UUID)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.fail_ozon_sync_connection_v2(
  UUID, UUID, JSONB
) FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.finish_ozon_sync_run_step_v2(
  UUID, UUID, TEXT, JSONB, JSONB, TIMESTAMPTZ
) TO service_role;
GRANT EXECUTE ON FUNCTION public.retry_failed_ozon_sync_run_steps_v2(UUID)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.fail_ozon_sync_connection_v2(
  UUID, UUID, JSONB
) TO service_role;
