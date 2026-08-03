-- Keep Ozon mirrors current without letting scheduler state mutate inventory.
-- All automatic boundaries are UTC; runs only advance coverage after every
-- durable step completed successfully.

ALTER TABLE public.marketplace_connections
  ADD COLUMN IF NOT EXISTS last_synced_through TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS next_automatic_sync_at TIMESTAMPTZ;

ALTER TABLE public.marketplace_sync_runs
  ADD COLUMN IF NOT EXISTS advances_watermark BOOLEAN NOT NULL DEFAULT false;

WITH latest_completed AS (
  SELECT connection_id, MAX(date_to) AS date_to
  FROM public.marketplace_sync_runs
  WHERE provider = 'ozon'
    AND status = 'completed'
    AND date_to IS NOT NULL
  GROUP BY connection_id
)
UPDATE public.marketplace_connections AS connections
SET last_synced_through = latest_completed.date_to
FROM latest_completed
WHERE connections.id = latest_completed.connection_id
  AND (
    connections.last_synced_through IS NULL
    OR connections.last_synced_through < latest_completed.date_to
  );

UPDATE public.marketplace_connections
SET next_automatic_sync_at = clock_timestamp()
WHERE provider = 'ozon'
  AND status IN ('connected', 'error')
  AND next_automatic_sync_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_marketplace_connections_ozon_auto_due
  ON public.marketplace_connections (next_automatic_sync_at)
  WHERE provider = 'ozon'
    AND status IN ('connected', 'error')
    AND next_automatic_sync_at IS NOT NULL;

CREATE OR REPLACE FUNCTION public._refresh_ozon_sync_run_v2(p_run_id UUID)
RETURNS public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_run public.marketplace_sync_runs%ROWTYPE;
  v_total INTEGER;
  v_success INTEGER;
  v_failed INTEGER;
  v_retry INTEGER;
  v_running INTEGER;
  v_pending INTEGER;
  v_status TEXT;
BEGIN
  SELECT
    count(*),
    count(*) FILTER (WHERE state IN ('completed', 'skipped')),
    count(*) FILTER (WHERE state = 'failed'),
    count(*) FILTER (WHERE state = 'retry_scheduled'),
    count(*) FILTER (WHERE state = 'running'),
    count(*) FILTER (WHERE state = 'pending')
  INTO v_total, v_success, v_failed, v_retry, v_running, v_pending
  FROM public.marketplace_sync_run_steps
  WHERE run_id = p_run_id;

  v_status := CASE
    WHEN v_retry > 0 THEN 'retrying'
    WHEN v_running > 0 OR v_pending > 0 THEN 'running'
    WHEN v_failed > 0 AND v_success = 0 THEN 'failed'
    WHEN v_failed > 0 THEN 'completed_with_errors'
    ELSE 'completed'
  END;

  UPDATE public.marketplace_sync_runs
  SET status = v_status,
      completed_at = CASE
        WHEN v_status IN ('completed', 'completed_with_errors', 'failed')
          THEN COALESCE(completed_at, clock_timestamp())
        ELSE NULL
      END,
      summary = jsonb_build_object(
        'totalSteps', v_total,
        'successfulSteps', v_success,
        'failedSteps', v_failed,
        'retryingSteps', v_retry,
        'runningSteps', v_running,
        'pendingSteps', v_pending
      ),
      error = CASE WHEN v_failed > 0 THEN 'Ozon sync step failed' ELSE NULL END
  WHERE id = p_run_id
  RETURNING * INTO v_run;

  UPDATE public.marketplace_connections
  SET last_synced_through = CASE
        WHEN v_status = 'completed'
          AND v_run.advances_watermark
          AND v_run.date_to IS NOT NULL
          AND (
            last_synced_through IS NULL
            OR last_synced_through < v_run.date_to
          )
          THEN v_run.date_to
        ELSE last_synced_through
      END,
      last_sync_status = v_status,
      last_sync_at = CASE
        WHEN v_status IN ('completed', 'completed_with_errors', 'failed')
          THEN clock_timestamp()
        ELSE last_sync_at
      END,
      last_sync_error = CASE
        WHEN v_status IN ('completed_with_errors', 'failed')
          THEN 'Ozon sync step failed'
        ELSE NULL
      END,
      status = CASE
        WHEN status = 'disabled' THEN status
        WHEN v_status IN ('completed_with_errors', 'failed') THEN 'error'
        WHEN v_status = 'completed' THEN 'connected'
        ELSE status
      END,
      health = COALESCE(health, '{}'::jsonb) || jsonb_build_object(
        'ozonSync',
        COALESCE(health -> 'ozonSync', '{}'::jsonb) || jsonb_build_object(
          'runId', p_run_id,
          'status', v_status,
          'updatedAt', clock_timestamp()
        )
      )
  WHERE id = v_run.connection_id;

  RETURN v_run;
END;
$function$;

CREATE OR REPLACE FUNCTION public.begin_or_resume_ozon_sync_run_v3(
  p_connection_id UUID,
  p_date_from TIMESTAMPTZ DEFAULT NULL,
  p_date_to TIMESTAMPTZ DEFAULT NULL
)
RETURNS public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_connection public.marketplace_connections%ROWTYPE;
  v_run public.marketplace_sync_runs%ROWTYPE;
  v_now TIMESTAMPTZ := clock_timestamp();
  v_date_from TIMESTAMPTZ;
  v_date_to TIMESTAMPTZ;
  v_advances_watermark BOOLEAN := false;
BEGIN
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_connection_id::text, 0)
  );

  SELECT * INTO v_connection
  FROM public.marketplace_connections
  WHERE id = p_connection_id
    AND provider = 'ozon'
    AND status IN ('connected', 'error')
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon connection is not active' USING ERRCODE = '55000';
  END IF;

  SELECT * INTO v_run
  FROM public.marketplace_sync_runs
  WHERE connection_id = p_connection_id
    AND status IN ('running', 'retrying')
  ORDER BY started_at DESC
  LIMIT 1
  FOR UPDATE;

  IF FOUND THEN
    RETURN v_run;
  END IF;

  IF (p_date_from IS NULL) <> (p_date_to IS NULL) THEN
    RAISE EXCEPTION 'Ozon sync window requires both dates' USING ERRCODE = '22023';
  END IF;

  IF p_date_from IS NULL THEN
    v_date_to := v_now;
    v_date_from := COALESCE(
      v_connection.last_synced_through,
      date_trunc('month', v_now AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'
    );
    v_advances_watermark := true;
  ELSE
    v_date_from := p_date_from;
    v_date_to := p_date_to;
    IF v_date_from > v_date_to OR v_date_to > v_now THEN
      RAISE EXCEPTION 'Invalid Ozon sync date window' USING ERRCODE = '22023';
    END IF;
  END IF;

  SELECT * INTO v_run
  FROM public.begin_or_resume_ozon_sync_run(
    p_connection_id,
    v_date_from,
    v_date_to
  );

  UPDATE public.marketplace_sync_runs
  SET advances_watermark = v_advances_watermark
  WHERE id = v_run.id
  RETURNING * INTO v_run;

  RETURN v_run;
END;
$function$;

CREATE OR REPLACE FUNCTION public.enqueue_due_ozon_sync_runs_v1(
  p_now TIMESTAMPTZ DEFAULT clock_timestamp(),
  p_connection_id UUID DEFAULT NULL
)
RETURNS SETOF public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_connection public.marketplace_connections%ROWTYPE;
  v_run public.marketplace_sync_runs%ROWTYPE;
  v_next_midnight TIMESTAMPTZ := (
    date_trunc('day', p_now AT TIME ZONE 'UTC') + interval '1 day'
  ) AT TIME ZONE 'UTC';
BEGIN
  FOR v_connection IN
    SELECT *
    FROM public.marketplace_connections
    WHERE provider = 'ozon'
      AND status IN ('connected', 'error')
      AND next_automatic_sync_at <= p_now
      AND (p_connection_id IS NULL OR id = p_connection_id)
    ORDER BY next_automatic_sync_at, id
  LOOP
    PERFORM pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(v_connection.id::text, 0)
    );

    SELECT * INTO v_connection
    FROM public.marketplace_connections
    WHERE id = v_connection.id
      AND provider = 'ozon'
      AND status IN ('connected', 'error')
      AND next_automatic_sync_at <= p_now
    FOR UPDATE;
    IF NOT FOUND THEN
      CONTINUE;
    END IF;

    IF EXISTS (
      SELECT 1
      FROM public.marketplace_sync_runs
      WHERE connection_id = v_connection.id
        AND status IN ('running', 'retrying')
    ) THEN
      CONTINUE;
    END IF;

    SELECT * INTO v_run
    FROM public.begin_or_resume_ozon_sync_run_v3(v_connection.id);

    UPDATE public.marketplace_connections
    SET next_automatic_sync_at = v_next_midnight
    WHERE id = v_connection.id;

    RETURN NEXT v_run;
  END LOOP;
  RETURN;
END;
$function$;

CREATE OR REPLACE FUNCTION public.disable_ozon_connection_v1(
  p_workspace_id UUID
)
RETURNS public.marketplace_connections
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_connection public.marketplace_connections%ROWTYPE;
  v_run_id UUID;
BEGIN
  SELECT * INTO v_connection
  FROM public.marketplace_connections
  WHERE workspace_id = p_workspace_id
    AND provider = 'ozon';

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon connection not found' USING ERRCODE = 'P0002';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_connection.id::text, 0)
  );

  SELECT * INTO v_connection
  FROM public.marketplace_connections
  WHERE id = v_connection.id
  FOR UPDATE;

  UPDATE public.marketplace_connections
  SET status = 'disabled',
      credential_ciphertext = '{}'::jsonb,
      client_id_hint = NULL,
      api_key_hint = NULL,
      next_automatic_sync_at = NULL
  WHERE id = v_connection.id;

  WITH stopped_steps AS (
    UPDATE public.marketplace_sync_run_steps
    SET state = 'failed',
        next_attempt_at = NULL,
        lease_token = NULL,
        lease_expires_at = NULL,
        last_error = public._sanitize_ozon_sync_step_error(
          jsonb_build_object(
            'kind', 'client',
            'retryable', false,
            'reason', 'connection_disabled'
          )
        ),
        completed_at = clock_timestamp()
    WHERE connection_id = v_connection.id
      AND state IN ('pending', 'running', 'retry_scheduled')
    RETURNING *
  )
  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, safe_error
  )
  SELECT
    run_id, id, workspace_id, connection_id, step_key, 'failed',
    attempt_count, failure_count, last_error
  FROM stopped_steps;

  FOR v_run_id IN
    SELECT id
    FROM public.marketplace_sync_runs
    WHERE connection_id = v_connection.id
      AND status IN ('running', 'retrying')
  LOOP
    PERFORM public._refresh_ozon_sync_run_v2(v_run_id);
  END LOOP;

  UPDATE public.marketplace_connections
  SET last_sync_error = NULL
  WHERE id = v_connection.id
  RETURNING * INTO v_connection;

  RETURN v_connection;
END;
$function$;

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
  JOIN public.marketplace_connections AS connections
    ON connections.id = steps.connection_id
  WHERE (p_run_id IS NULL OR steps.run_id = p_run_id)
    AND runs.status IN ('running', 'retrying')
    AND connections.status IN ('connected', 'error')
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

REVOKE ALL ON FUNCTION public.begin_or_resume_ozon_sync_run_v3(
  UUID, TIMESTAMPTZ, TIMESTAMPTZ
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.enqueue_due_ozon_sync_runs_v1(
  TIMESTAMPTZ, UUID
) FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.disable_ozon_connection_v1(UUID)
  FROM PUBLIC, anon, authenticated;
REVOKE ALL ON FUNCTION public.claim_ozon_sync_run_step_v2(UUID)
  FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.begin_or_resume_ozon_sync_run_v3(
  UUID, TIMESTAMPTZ, TIMESTAMPTZ
) TO service_role;
GRANT EXECUTE ON FUNCTION public.enqueue_due_ozon_sync_runs_v1(
  TIMESTAMPTZ, UUID
) TO service_role;
GRANT EXECUTE ON FUNCTION public.disable_ozon_connection_v1(UUID)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.claim_ozon_sync_run_step_v2(UUID)
  TO service_role;
