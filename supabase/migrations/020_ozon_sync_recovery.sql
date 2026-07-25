-- Durable, lease-based recovery for Ozon marketplace synchronization.

ALTER TABLE public.marketplace_connections
  DROP CONSTRAINT IF EXISTS marketplace_connections_last_sync_status_check;

ALTER TABLE public.marketplace_sync_runs
  DROP CONSTRAINT IF EXISTS marketplace_sync_runs_status_check;

WITH interrupted_runs AS (
  UPDATE public.marketplace_sync_runs
  SET
    status = 'failed',
    completed_at = COALESCE(completed_at, now()),
    error = COALESCE(error, 'Interrupted while durable sync recovery was installed')
  WHERE status = 'running'
  RETURNING connection_id
)
UPDATE public.marketplace_connections
SET
  status = CASE WHEN status = 'disabled' THEN status ELSE 'error' END,
  last_sync_status = 'failed',
  last_sync_error = 'The previous Ozon sync was interrupted',
  health = COALESCE(health, '{}'::jsonb) || jsonb_build_object(
    'ozonSync',
    COALESCE(health -> 'ozonSync', '{}'::jsonb) || jsonb_build_object(
      'status', 'failed',
      'interrupted', true,
      'updatedAt', now()
    )
  )
WHERE id IN (SELECT connection_id FROM interrupted_runs);

UPDATE public.marketplace_sync_runs AS runs
SET
  workspace_id = connections.workspace_id,
  provider = connections.provider
FROM public.marketplace_connections AS connections
WHERE runs.connection_id = connections.id
  AND (
    runs.workspace_id IS DISTINCT FROM connections.workspace_id
    OR runs.provider IS DISTINCT FROM connections.provider
  );

ALTER TABLE public.marketplace_connections
  ADD CONSTRAINT marketplace_connections_last_sync_status_check
  CHECK (
    last_sync_status IS NULL OR last_sync_status IN (
      'running',
      'retrying',
      'completed',
      'completed_with_errors',
      'failed'
    )
  );

ALTER TABLE public.marketplace_sync_runs
  ADD CONSTRAINT marketplace_sync_runs_status_check
  CHECK (status IN (
    'running',
    'retrying',
    'completed',
    'completed_with_errors',
      'failed'
  ));

ALTER TABLE public.marketplace_connections
  ADD CONSTRAINT marketplace_connections_id_workspace_provider_key
  UNIQUE (id, workspace_id, provider);

ALTER TABLE public.marketplace_sync_runs
  ADD CONSTRAINT marketplace_sync_runs_id_context_key
  UNIQUE (id, workspace_id, connection_id, provider);

ALTER TABLE public.marketplace_sync_runs
  ADD CONSTRAINT marketplace_sync_runs_connection_context_fkey
  FOREIGN KEY (connection_id, workspace_id, provider)
  REFERENCES public.marketplace_connections(id, workspace_id, provider)
  ON DELETE CASCADE;

CREATE UNIQUE INDEX marketplace_sync_runs_one_active_per_connection
  ON public.marketplace_sync_runs(connection_id)
  WHERE status IN ('running', 'retrying');

CREATE TABLE public.marketplace_sync_run_steps (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  run_id UUID NOT NULL REFERENCES public.marketplace_sync_runs(id) ON DELETE CASCADE,
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('ozon')),
  step_key TEXT NOT NULL CHECK (step_key IN ('warehouses', 'products', 'stocks', 'postings', 'returns', 'finance', 'legalEntities', 'reports', 'removals', 'supplies', 'analytics', 'discountedProducts')),
  step_order SMALLINT NOT NULL CHECK (step_order BETWEEN 1 AND 12),
  state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'running', 'retry_scheduled', 'completed', 'skipped', 'failed')),
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at TIMESTAMPTZ DEFAULT now(),
  lease_token UUID,
  lease_expires_at TIMESTAMPTZ,
  summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  last_error JSONB,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (
    (step_order = 1 AND step_key = 'warehouses')
    OR (step_order = 2 AND step_key = 'products')
    OR (step_order = 3 AND step_key = 'stocks')
    OR (step_order = 4 AND step_key = 'postings')
    OR (step_order = 5 AND step_key = 'returns')
    OR (step_order = 6 AND step_key = 'finance')
    OR (step_order = 7 AND step_key = 'legalEntities')
    OR (step_order = 8 AND step_key = 'reports')
    OR (step_order = 9 AND step_key = 'removals')
    OR (step_order = 10 AND step_key = 'supplies')
    OR (step_order = 11 AND step_key = 'analytics')
    OR (step_order = 12 AND step_key = 'discountedProducts')
  ),
  CHECK (
    (
      state = 'running'
      AND lease_token IS NOT NULL
      AND lease_expires_at IS NOT NULL
    )
    OR (
      state <> 'running'
      AND lease_token IS NULL
      AND lease_expires_at IS NULL
    )
  ),
  CHECK (state <> 'retry_scheduled' OR next_attempt_at IS NOT NULL),
  FOREIGN KEY (run_id, workspace_id, connection_id, provider)
    REFERENCES public.marketplace_sync_runs(id, workspace_id, connection_id, provider)
    ON DELETE CASCADE,
  UNIQUE (run_id, step_key),
  UNIQUE (run_id, step_order)
);

CREATE INDEX marketplace_sync_run_steps_due_work
  ON public.marketplace_sync_run_steps(state, next_attempt_at, created_at)
  WHERE state IN ('pending', 'retry_scheduled', 'running');

CREATE INDEX marketplace_sync_run_steps_run_order
  ON public.marketplace_sync_run_steps(run_id, step_order);

CREATE INDEX marketplace_sync_run_steps_connection_state
  ON public.marketplace_sync_run_steps(connection_id, state, lease_expires_at);

CREATE TRIGGER set_marketplace_sync_run_steps_updated_at
BEFORE UPDATE ON public.marketplace_sync_run_steps
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.marketplace_sync_run_steps ENABLE ROW LEVEL SECURITY;

CREATE POLICY "marketplace_sync_run_steps_select_member"
ON public.marketplace_sync_run_steps
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "marketplace_sync_run_steps_write_admin"
ON public.marketplace_sync_run_steps
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE OR REPLACE FUNCTION public.begin_or_resume_ozon_sync_run(
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
BEGIN
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_connection_id::text, 0)
  );

  SELECT *
  INTO v_connection
  FROM public.marketplace_connections
  WHERE id = p_connection_id
    AND provider = 'ozon'
  FOR UPDATE;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon connection not found' USING ERRCODE = 'P0002';
  END IF;

  IF v_connection.status = 'disabled' THEN
    RAISE EXCEPTION 'Ozon connection is disabled' USING ERRCODE = '55000';
  END IF;

  SELECT *
  INTO v_run
  FROM public.marketplace_sync_runs
  WHERE connection_id = p_connection_id
    AND status IN ('running', 'retrying')
  ORDER BY started_at DESC
  LIMIT 1
  FOR UPDATE;

  IF NOT FOUND THEN
    INSERT INTO public.marketplace_sync_runs (
      workspace_id,
      connection_id,
      provider,
      status,
      date_from,
      date_to,
      summary
    )
    VALUES (
      v_connection.workspace_id,
      v_connection.id,
      'ozon',
      'running',
      p_date_from,
      p_date_to,
      jsonb_build_object('totalSteps', 12, 'pendingSteps', 12)
    )
    RETURNING * INTO v_run;
  END IF;

  WITH step_registry(step_order, step_key) AS (
    VALUES
      (1, 'warehouses'),
      (2, 'products'),
      (3, 'stocks'),
      (4, 'postings'),
      (5, 'returns'),
      (6, 'finance'),
      (7, 'legalEntities'),
      (8, 'reports'),
      (9, 'removals'),
      (10, 'supplies'),
      (11, 'analytics'),
      (12, 'discountedProducts')
  )
  INSERT INTO public.marketplace_sync_run_steps (
    run_id,
    workspace_id,
    connection_id,
    provider,
    step_key,
    step_order,
    state,
    next_attempt_at
  )
  SELECT
    v_run.id,
    v_run.workspace_id,
    v_run.connection_id,
    v_run.provider,
    step_registry.step_key,
    step_registry.step_order,
    'pending',
    now()
  FROM step_registry
  ON CONFLICT (run_id, step_key) DO NOTHING;

  UPDATE public.marketplace_sync_run_steps
  SET next_attempt_at = now()
  WHERE run_id = v_run.id
    AND state = 'retry_scheduled';

  UPDATE public.marketplace_sync_runs
  SET
    status = CASE
      WHEN EXISTS (
        SELECT 1
        FROM public.marketplace_sync_run_steps
        WHERE run_id = v_run.id
          AND state = 'retry_scheduled'
      ) THEN 'retrying'
      ELSE 'running'
    END,
    completed_at = NULL,
    error = NULL
  WHERE id = v_run.id
  RETURNING * INTO v_run;

  UPDATE public.marketplace_connections
  SET
    last_sync_status = v_run.status,
    last_sync_error = NULL,
    health = COALESCE(health, '{}'::jsonb) || jsonb_build_object(
      'ozonSync',
      COALESCE(health -> 'ozonSync', '{}'::jsonb) || jsonb_build_object(
        'runId', v_run.id,
        'status', v_run.status,
        'updatedAt', now()
      )
    )
  WHERE id = v_run.connection_id;

  RETURN v_run;
END;
$function$;

CREATE OR REPLACE FUNCTION public.claim_ozon_sync_run_step(
  p_run_id UUID DEFAULT NULL
)
RETURNS SETOF public.marketplace_sync_run_steps
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_connection_id UUID;
  v_step public.marketplace_sync_run_steps%ROWTYPE;
BEGIN
  SELECT steps.connection_id
  INTO v_connection_id
  FROM public.marketplace_sync_run_steps AS steps
  WHERE (p_run_id IS NULL OR steps.run_id = p_run_id)
    AND EXISTS (
      SELECT 1
      FROM public.marketplace_sync_runs AS parent_run
      WHERE parent_run.id = steps.run_id
        AND parent_run.status IN ('running', 'retrying')
    )
    AND (
      steps.state = 'pending'
      OR (
        steps.state = 'retry_scheduled'
        AND steps.next_attempt_at <= now()
      )
      OR (
        steps.state = 'running'
        AND steps.lease_expires_at <= now()
      )
    )
    AND NOT EXISTS (
      SELECT 1
      FROM public.marketplace_sync_run_steps AS live_step
      WHERE live_step.connection_id = steps.connection_id
        AND live_step.state = 'running'
        AND live_step.lease_expires_at > now()
    )
  ORDER BY
    COALESCE(steps.next_attempt_at, steps.lease_expires_at, steps.created_at),
    steps.step_order
  LIMIT 1;

  IF NOT FOUND THEN
    RETURN;
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_connection_id::text, 0)
  );

  SELECT steps.*
  INTO v_step
  FROM public.marketplace_sync_run_steps AS steps
  WHERE steps.connection_id = v_connection_id
    AND (p_run_id IS NULL OR steps.run_id = p_run_id)
    AND EXISTS (
      SELECT 1
      FROM public.marketplace_sync_runs AS parent_run
      WHERE parent_run.id = steps.run_id
        AND parent_run.status IN ('running', 'retrying')
    )
    AND (
      steps.state = 'pending'
      OR (
        steps.state = 'retry_scheduled'
        AND steps.next_attempt_at <= now()
      )
      OR (
        steps.state = 'running'
        AND steps.lease_expires_at <= now()
      )
    )
    AND NOT EXISTS (
      SELECT 1
      FROM public.marketplace_sync_run_steps AS live_step
      WHERE live_step.connection_id = steps.connection_id
        AND live_step.state = 'running'
        AND live_step.lease_expires_at > now()
    )
  ORDER BY
    COALESCE(steps.next_attempt_at, steps.lease_expires_at, steps.created_at),
    steps.step_order
  FOR UPDATE SKIP LOCKED
  LIMIT 1;

  IF NOT FOUND THEN
    RETURN;
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.marketplace_sync_run_steps
    WHERE connection_id = v_step.connection_id
      AND id <> v_step.id
      AND state = 'running'
      AND lease_expires_at > now()
  ) THEN
    RETURN;
  END IF;

  RETURN QUERY
  UPDATE public.marketplace_sync_run_steps
  SET
    state = 'running',
    attempt_count = attempt_count + 1,
    next_attempt_at = NULL,
    lease_token = gen_random_uuid(),
    lease_expires_at = now() + interval '10 minutes',
    started_at = now(),
    completed_at = NULL
  WHERE id = v_step.id
  RETURNING *;
END;
$function$;

CREATE OR REPLACE FUNCTION public.finish_ozon_sync_run_step(
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
  v_connection_id UUID;
  v_step public.marketplace_sync_run_steps%ROWTYPE;
  v_run_status TEXT;
  v_run_summary JSONB;
BEGIN
  IF p_state NOT IN ('retry_scheduled', 'completed', 'skipped', 'failed') THEN
    RAISE EXCEPTION 'Invalid Ozon sync step result state'
      USING ERRCODE = '22023';
  END IF;

  IF p_state = 'retry_scheduled' AND p_next_attempt_at IS NULL THEN
    RAISE EXCEPTION 'A scheduled retry requires its next attempt time'
      USING ERRCODE = '22023';
  END IF;

  SELECT connection_id
  INTO v_connection_id
  FROM public.marketplace_sync_run_steps
  WHERE id = p_step_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale or invalid'
      USING ERRCODE = '55000';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_connection_id::text, 0)
  );

  UPDATE public.marketplace_sync_run_steps
  SET
    state = p_state,
    summary = COALESCE(p_summary, '{}'::jsonb),
    last_error = CASE
      WHEN p_state IN ('retry_scheduled', 'failed') THEN p_last_error
      ELSE NULL
    END,
    next_attempt_at = CASE
      WHEN p_state = 'retry_scheduled' THEN p_next_attempt_at
      ELSE NULL
    END,
    lease_token = NULL,
    lease_expires_at = NULL,
    completed_at = CASE
      WHEN p_state IN ('completed', 'skipped', 'failed') THEN now()
      ELSE NULL
    END
  WHERE id = p_step_id
    AND state = 'running'
    AND lease_token = p_lease_token
    AND lease_expires_at > now()
  RETURNING * INTO v_step;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale or invalid'
      USING ERRCODE = '55000';
  END IF;

  SELECT
    CASE
      WHEN count(*) FILTER (WHERE state = 'retry_scheduled') > 0 THEN 'retrying'
      WHEN count(*) FILTER (WHERE state IN ('running', 'pending')) > 0 THEN 'running'
      WHEN count(*) FILTER (WHERE state = 'failed') > 0
        AND count(*) FILTER (WHERE state IN ('completed', 'skipped')) > 0
        THEN 'completed_with_errors'
      WHEN count(*) FILTER (WHERE state = 'failed') > 0 THEN 'failed'
      ELSE 'completed'
    END,
    jsonb_build_object(
      'totalSteps', count(*),
      'pendingSteps', count(*) FILTER (WHERE state = 'pending'),
      'runningSteps', count(*) FILTER (WHERE state = 'running'),
      'retryScheduledSteps', count(*) FILTER (WHERE state = 'retry_scheduled'),
      'completedSteps', count(*) FILTER (WHERE state = 'completed'),
      'skippedSteps', count(*) FILTER (WHERE state = 'skipped'),
      'failedSteps', count(*) FILTER (WHERE state = 'failed'),
      'steps', COALESCE(
        jsonb_object_agg(
          step_key,
          jsonb_build_object(
            'state', state,
            'attemptCount', attempt_count,
            'summary', summary,
            'lastError', last_error,
            'nextAttemptAt', next_attempt_at,
            'completedAt', completed_at
          )
          ORDER BY step_order
        ),
        '{}'::jsonb
      )
    )
  INTO v_run_status, v_run_summary
  FROM public.marketplace_sync_run_steps
  WHERE run_id = v_step.run_id;

  UPDATE public.marketplace_sync_runs
  SET
    status = v_run_status,
    summary = v_run_summary,
    completed_at = CASE
      WHEN v_run_status IN ('completed', 'completed_with_errors', 'failed')
        THEN now()
      ELSE NULL
    END,
    error = CASE
      WHEN v_run_status IN ('completed_with_errors', 'failed')
        THEN 'One or more Ozon sync steps failed'
      ELSE NULL
    END
  WHERE id = v_step.run_id;

  UPDATE public.marketplace_connections
  SET
    status = CASE
      WHEN status = 'disabled' THEN status
      WHEN v_run_status = 'completed' THEN 'connected'
      WHEN v_run_status IN ('completed_with_errors', 'failed') THEN 'error'
      ELSE status
    END,
    last_sync_status = v_run_status,
    last_sync_at = CASE
      WHEN v_run_status IN ('completed', 'completed_with_errors', 'failed')
        THEN now()
      ELSE last_sync_at
    END,
    last_sync_error = CASE
      WHEN v_run_status = 'retrying' THEN 'An Ozon sync step is scheduled to retry'
      WHEN v_run_status IN ('completed_with_errors', 'failed')
        THEN 'One or more Ozon sync steps failed'
      ELSE NULL
    END,
    health = COALESCE(health, '{}'::jsonb) || jsonb_build_object(
      'ozonSync',
      COALESCE(health -> 'ozonSync', '{}'::jsonb) || jsonb_build_object(
        'runId', v_step.run_id,
        'status', v_run_status,
        'summary', v_run_summary - 'steps',
        'updatedAt', now()
      )
    )
  WHERE id = v_step.connection_id;

  RETURN v_step;
END;
$function$;

CREATE OR REPLACE FUNCTION public.retry_failed_ozon_sync_run_steps(
  p_run_id UUID
)
RETURNS public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_run public.marketplace_sync_runs%ROWTYPE;
  v_failed_count INTEGER;
  v_summary JSONB;
BEGIN
  SELECT *
  INTO v_run
  FROM public.marketplace_sync_runs
  WHERE id = p_run_id;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync run not found' USING ERRCODE = 'P0002';
  END IF;

  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_run.connection_id::text, 0)
  );

  SELECT *
  INTO v_run
  FROM public.marketplace_sync_runs
  WHERE id = p_run_id
  FOR UPDATE;

  IF v_run.status NOT IN ('completed', 'completed_with_errors', 'failed') THEN
    RAISE EXCEPTION 'Only a terminal Ozon sync run can retry failed steps'
      USING ERRCODE = '55000';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.marketplace_sync_runs
    WHERE connection_id = v_run.connection_id
      AND id <> p_run_id
      AND status IN ('running', 'retrying')
  ) THEN
    RAISE EXCEPTION 'Another Ozon sync run is already active'
      USING ERRCODE = '55000';
  END IF;

  UPDATE public.marketplace_sync_run_steps
  SET
    state = 'pending',
    attempt_count = 0,
    next_attempt_at = now(),
    lease_token = NULL,
    lease_expires_at = NULL,
    summary = '{}'::jsonb,
    last_error = NULL,
    started_at = NULL,
    completed_at = NULL
  WHERE run_id = p_run_id
    AND state = 'failed';

  GET DIAGNOSTICS v_failed_count = ROW_COUNT;

  IF v_failed_count = 0 THEN
    RAISE EXCEPTION 'Ozon sync run has no failed steps to retry'
      USING ERRCODE = '55000';
  END IF;

  SELECT jsonb_build_object(
    'totalSteps', count(*),
    'pendingSteps', count(*) FILTER (WHERE state = 'pending'),
    'runningSteps', count(*) FILTER (WHERE state = 'running'),
    'retryScheduledSteps', count(*) FILTER (WHERE state = 'retry_scheduled'),
    'completedSteps', count(*) FILTER (WHERE state = 'completed'),
    'skippedSteps', count(*) FILTER (WHERE state = 'skipped'),
    'failedSteps', count(*) FILTER (WHERE state = 'failed')
  )
  INTO v_summary
  FROM public.marketplace_sync_run_steps
  WHERE run_id = p_run_id;

  UPDATE public.marketplace_sync_runs
  SET
    status = 'running',
    started_at = now(),
    completed_at = NULL,
    summary = v_summary,
    error = NULL
  WHERE id = p_run_id
  RETURNING * INTO v_run;

  UPDATE public.marketplace_connections
  SET
    last_sync_status = 'running',
    last_sync_error = NULL,
    health = COALESCE(health, '{}'::jsonb) || jsonb_build_object(
      'ozonSync',
      COALESCE(health -> 'ozonSync', '{}'::jsonb) || jsonb_build_object(
        'runId', v_run.id,
        'status', 'running',
        'summary', v_summary,
        'updatedAt', now()
      )
    )
  WHERE id = v_run.connection_id;

  RETURN v_run;
END;
$function$;

CREATE OR REPLACE FUNCTION public.schedule_ozon_sync_recovery()
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_job_id BIGINT;
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM vault.decrypted_secrets
    WHERE name = 'tover_ozon_recovery_url'
      AND decrypted_secret IS NOT NULL
  ) THEN
    RAISE EXCEPTION 'Missing required Vault secret: tover_ozon_recovery_url'
      USING ERRCODE = '55000';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM vault.decrypted_secrets
    WHERE name = 'tover_ozon_recovery_secret'
      AND decrypted_secret IS NOT NULL
  ) THEN
    RAISE EXCEPTION 'Missing required Vault secret: tover_ozon_recovery_secret'
      USING ERRCODE = '55000';
  END IF;

  FOR v_job_id IN
    SELECT jobid
    FROM cron.job
    WHERE jobname = 'tover-ozon-sync-recovery'
  LOOP
    PERFORM cron.unschedule(v_job_id);
  END LOOP;

  SELECT cron.schedule(
    'tover-ozon-sync-recovery',
    '* * * * *',
    $cron$
      SELECT net.http_post(
        url := (
          SELECT decrypted_secret
          FROM vault.decrypted_secrets
          WHERE name = 'tover_ozon_recovery_url'
        ),
        headers := jsonb_build_object(
          'Content-Type', 'application/json',
          'x-tover-recovery-secret', (
            SELECT decrypted_secret
            FROM vault.decrypted_secrets
            WHERE name = 'tover_ozon_recovery_secret'
          )
        ),
        body := jsonb_build_object('source', 'pg_cron'),
        timeout_milliseconds := 120000
      ) AS request_id;
    $cron$
  )
  INTO v_job_id;

  RETURN v_job_id;
END;
$function$;

REVOKE EXECUTE ON FUNCTION public.begin_or_resume_ozon_sync_run(uuid, timestamptz, timestamptz)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.begin_or_resume_ozon_sync_run(uuid, timestamptz, timestamptz)
  TO service_role;

REVOKE EXECUTE ON FUNCTION public.claim_ozon_sync_run_step(uuid)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.claim_ozon_sync_run_step(uuid)
  TO service_role;

REVOKE EXECUTE ON FUNCTION public.finish_ozon_sync_run_step(uuid, uuid, text, jsonb, jsonb, timestamptz)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.finish_ozon_sync_run_step(uuid, uuid, text, jsonb, jsonb, timestamptz)
  TO service_role;

REVOKE EXECUTE ON FUNCTION public.retry_failed_ozon_sync_run_steps(uuid)
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.retry_failed_ozon_sync_run_steps(uuid)
  TO service_role;

REVOKE EXECUTE ON FUNCTION public.schedule_ozon_sync_recovery()
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.schedule_ozon_sync_recovery()
  TO service_role;

/*
Pause and remove the recovery job:
  SELECT cron.unschedule('tover-ozon-sync-recovery');

Resume (or replace) the recovery job after both Vault secrets exist:
  SELECT public.schedule_ozon_sync_recovery();

Inspect the job definition and recent executions:
  SELECT * FROM cron.job WHERE jobname = 'tover-ozon-sync-recovery';
  SELECT * FROM cron.job_run_details
  WHERE jobid IN (
    SELECT jobid FROM cron.job WHERE jobname = 'tover-ozon-sync-recovery'
  )
  ORDER BY start_time DESC;
*/
