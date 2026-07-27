-- Durable Ozon checkpoints, failure-based retry accounting, and safe events.
-- Expand-first: v1 worker RPCs remain available until the application deploys.

ALTER TABLE public.marketplace_sync_run_steps
  ADD COLUMN IF NOT EXISTS checkpoint JSONB NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN IF NOT EXISTS failure_count INTEGER NOT NULL DEFAULT 0
    CHECK (failure_count >= 0),
  ADD COLUMN IF NOT EXISTS last_checkpoint_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS public.marketplace_sync_step_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES public.marketplace_sync_runs(id) ON DELETE CASCADE,
  step_id UUID NOT NULL REFERENCES public.marketplace_sync_run_steps(id) ON DELETE CASCADE,
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  step_key TEXT NOT NULL,
  event_type TEXT NOT NULL CHECK (event_type IN (
    'claimed', 'checkpointed', 'yielded', 'retry_scheduled', 'failed',
    'skipped', 'completed', 'repair_reset'
  )),
  execution_count INTEGER NOT NULL CHECK (execution_count >= 0),
  failure_count INTEGER NOT NULL CHECK (failure_count >= 0),
  phase TEXT,
  processed INTEGER CHECK (processed IS NULL OR processed >= 0),
  total INTEGER CHECK (total IS NULL OR total >= 0),
  endpoint TEXT,
  http_status INTEGER CHECK (
    http_status IS NULL OR http_status BETWEEN 100 AND 599
  ),
  ozon_code TEXT,
  postgres_code TEXT,
  operation_name TEXT,
  next_action_at TIMESTAMPTZ,
  safe_error JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX IF NOT EXISTS marketplace_sync_step_events_run_created
  ON public.marketplace_sync_step_events(run_id, created_at DESC);
CREATE INDEX IF NOT EXISTS marketplace_sync_step_events_step_created
  ON public.marketplace_sync_step_events(step_id, created_at DESC);
CREATE INDEX IF NOT EXISTS marketplace_sync_step_events_workspace
  ON public.marketplace_sync_step_events(workspace_id);
CREATE INDEX IF NOT EXISTS marketplace_sync_step_events_connection
  ON public.marketplace_sync_step_events(connection_id);
CREATE INDEX IF NOT EXISTS marketplace_sync_run_steps_context_cover
  ON public.marketplace_sync_run_steps(
    run_id, workspace_id, connection_id, provider, step_order
  );
CREATE INDEX IF NOT EXISTS marketplace_sync_run_steps_workspace
  ON public.marketplace_sync_run_steps(workspace_id);
CREATE INDEX IF NOT EXISTS marketplace_sync_runs_connection_context
  ON public.marketplace_sync_runs(connection_id, workspace_id, provider);
CREATE INDEX IF NOT EXISTS marketplace_sync_runs_workspace
  ON public.marketplace_sync_runs(workspace_id);
CREATE INDEX IF NOT EXISTS marketplace_operation_candidates_created_operation
  ON public.marketplace_operation_candidates(created_operation_id);
CREATE INDEX IF NOT EXISTS marketplace_operation_commit_claims_connection
  ON public.marketplace_operation_commit_claims(connection_id);
CREATE INDEX IF NOT EXISTS marketplace_operation_commit_claims_operation
  ON public.marketplace_operation_commit_claims(operation_id);
CREATE INDEX IF NOT EXISTS inventory_movements_operation
  ON public.inventory_movements(operation_id);
CREATE INDEX IF NOT EXISTS inventory_movements_operation_item
  ON public.inventory_movements(operation_item_id);
CREATE INDEX IF NOT EXISTS inventory_movements_product
  ON public.inventory_movements(product_id);
CREATE INDEX IF NOT EXISTS inventory_movements_warehouse
  ON public.inventory_movements(warehouse_id);
CREATE INDEX IF NOT EXISTS inventory_movements_store
  ON public.inventory_movements(store_id);
CREATE INDEX IF NOT EXISTS inventory_movements_supplier
  ON public.inventory_movements(supplier_id);
CREATE INDEX IF NOT EXISTS operation_items_store
  ON public.operation_items(store_id);

ALTER TABLE public.marketplace_sync_step_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "marketplace_sync_step_events_select_member"
  ON public.marketplace_sync_step_events;
CREATE POLICY "marketplace_sync_step_events_select_member"
  ON public.marketplace_sync_step_events
  FOR SELECT TO authenticated
  USING (public.app_is_org_member(workspace_id));

DROP POLICY IF EXISTS "marketplace_sync_run_steps_write_admin"
  ON public.marketplace_sync_run_steps;
DROP POLICY IF EXISTS "marketplace_sync_run_steps_insert_admin"
  ON public.marketplace_sync_run_steps;
DROP POLICY IF EXISTS "marketplace_sync_run_steps_update_admin"
  ON public.marketplace_sync_run_steps;
DROP POLICY IF EXISTS "marketplace_sync_run_steps_delete_admin"
  ON public.marketplace_sync_run_steps;
CREATE POLICY "marketplace_sync_run_steps_insert_admin"
  ON public.marketplace_sync_run_steps
  FOR INSERT TO authenticated
  WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));
CREATE POLICY "marketplace_sync_run_steps_update_admin"
  ON public.marketplace_sync_run_steps
  FOR UPDATE TO authenticated
  USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
  WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));
CREATE POLICY "marketplace_sync_run_steps_delete_admin"
  ON public.marketplace_sync_run_steps
  FOR DELETE TO authenticated
  USING (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE OR REPLACE FUNCTION public._sanitize_ozon_sync_step_error(p_error JSONB)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
SET search_path = ''
AS $function$
DECLARE
  v_result JSONB := jsonb_build_object('message', 'Ozon sync step failed');
  v_text TEXT;
  v_number BIGINT;
BEGIN
  IF p_error IS NULL OR jsonb_typeof(p_error) <> 'object' THEN
    RETURN v_result;
  END IF;
  IF p_error ->> 'kind' IN (
    'transport', 'timeout', 'rate_limit', 'server', 'client', 'unknown'
  ) THEN
    v_result := v_result || jsonb_build_object('kind', p_error ->> 'kind');
  END IF;
  IF p_error ->> 'status' ~ '^[0-9]{3}$'
    AND (p_error ->> 'status')::integer BETWEEN 100 AND 599
  THEN
    v_result := v_result || jsonb_build_object(
      'status', (p_error ->> 'status')::integer
    );
  END IF;
  IF p_error ->> 'retryAfterMs' ~ '^(0|[1-9][0-9]{0,7})$' THEN
    v_number := (p_error ->> 'retryAfterMs')::bigint;
    IF v_number BETWEEN 0 AND 86400000 THEN
      v_result := v_result || jsonb_build_object('retryAfterMs', v_number);
    END IF;
  END IF;
  IF jsonb_typeof(p_error -> 'retryable') = 'boolean' THEN
    v_result := v_result || jsonb_build_object(
      'retryable', (p_error ->> 'retryable')::boolean
    );
  END IF;
  v_text := p_error ->> 'endpoint';
  IF v_text ~ '^/v[0-9]+/[a-z0-9/_-]+$' AND length(v_text) <= 160 THEN
    v_result := v_result || jsonb_build_object('endpoint', v_text);
  END IF;
  v_text := p_error ->> 'code';
  IF v_text ~ '^[A-Za-z0-9._:-]{1,80}$' THEN
    v_result := v_result || jsonb_build_object('code', v_text);
  END IF;
  v_text := p_error ->> 'reason';
  IF length(v_text) BETWEEN 1 AND 500
    AND v_text !~ '[[:cntrl:]]'
    AND v_text !~* (
      'authorization|api[-_ ]?key|client[-_ ]?id|bearer[[:space:]]|'
      || 'token|jwt|[[:alnum:]._%+-]+@[[:alnum:].-]+'
    )
    AND v_text !~ '(^|[^0-9])\+?[0-9][0-9() .-]{7,}[0-9]'
  THEN
    v_result := v_result || jsonb_build_object('reason', v_text);
  END IF;
  v_text := p_error ->> 'postgresCode';
  IF v_text ~ '^[A-Z0-9]{5}$' THEN
    v_result := v_result || jsonb_build_object('postgresCode', v_text);
  END IF;
  v_text := p_error ->> 'operationName';
  IF v_text ~ '^[a-z0-9:_-]{1,100}$' THEN
    v_result := v_result || jsonb_build_object('operationName', v_text);
  END IF;
  RETURN v_result;
END;
$function$;

CREATE OR REPLACE FUNCTION public.protect_marketplace_sync_run_step_state()
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
  WHERE oid = 'public.marketplace_sync_run_steps'::regclass;

  IF TG_OP = 'DELETE' THEN
    IF pg_catalog.pg_trigger_depth() = 1 AND current_user <> v_owner THEN
      RAISE EXCEPTION 'Ozon sync steps may only be deleted by cascade cleanup'
        USING ERRCODE = '42501';
    END IF;
    RETURN OLD;
  END IF;

  IF TG_OP = 'INSERT' THEN
    IF NEW.state <> 'pending'
      OR NEW.attempt_count <> 0
      OR NEW.failure_count <> 0
      OR NEW.checkpoint <> '{}'::jsonb
      OR NEW.last_checkpoint_at IS NOT NULL
      OR NEW.lease_token IS NOT NULL
      OR NEW.lease_expires_at IS NOT NULL
      OR NEW.summary <> '{}'::jsonb
      OR NEW.last_error IS NOT NULL
      OR NEW.started_at IS NOT NULL
      OR NEW.completed_at IS NOT NULL
    THEN
      RAISE EXCEPTION 'Ozon sync steps must be inserted in their initial state'
        USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
  END IF;

  v_protected_change :=
    NEW.id IS DISTINCT FROM OLD.id
    OR NEW.run_id IS DISTINCT FROM OLD.run_id
    OR NEW.workspace_id IS DISTINCT FROM OLD.workspace_id
    OR NEW.connection_id IS DISTINCT FROM OLD.connection_id
    OR NEW.provider IS DISTINCT FROM OLD.provider
    OR NEW.step_key IS DISTINCT FROM OLD.step_key
    OR NEW.step_order IS DISTINCT FROM OLD.step_order
    OR NEW.state IS DISTINCT FROM OLD.state
    OR NEW.attempt_count IS DISTINCT FROM OLD.attempt_count
    OR NEW.failure_count IS DISTINCT FROM OLD.failure_count
    OR NEW.checkpoint IS DISTINCT FROM OLD.checkpoint
    OR NEW.last_checkpoint_at IS DISTINCT FROM OLD.last_checkpoint_at
    OR NEW.next_attempt_at IS DISTINCT FROM OLD.next_attempt_at
    OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
    OR NEW.lease_expires_at IS DISTINCT FROM OLD.lease_expires_at
    OR NEW.summary IS DISTINCT FROM OLD.summary
    OR NEW.last_error IS DISTINCT FROM OLD.last_error
    OR NEW.started_at IS DISTINCT FROM OLD.started_at
    OR NEW.completed_at IS DISTINCT FROM OLD.completed_at
    OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.updated_at IS DISTINCT FROM OLD.updated_at;

  IF v_protected_change AND current_user <> v_owner THEN
    RAISE EXCEPTION 'Ozon sync step state may only change through worker RPCs'
      USING ERRCODE = '42501';
  END IF;

  IF NEW.state IN ('retry_scheduled', 'failed') THEN
    NEW.last_error := public._sanitize_ozon_sync_step_error(NEW.last_error);
  ELSE
    NEW.last_error := NULL;
  END IF;
  RETURN NEW;
END;
$function$;

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
  SET last_sync_status = v_status,
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
DECLARE
  v_step public.marketplace_sync_run_steps%ROWTYPE;
BEGIN
  UPDATE public.marketplace_sync_run_steps
  SET checkpoint = COALESCE(p_checkpoint, '{}'::jsonb),
      summary = CASE
        WHEN p_summary IS NULL OR p_summary = '{}'::jsonb THEN summary
        ELSE p_summary
      END,
      last_checkpoint_at = clock_timestamp(),
      lease_expires_at = clock_timestamp() + interval '10 minutes'
  WHERE id = p_step_id
    AND state = 'running'
    AND lease_token = p_lease_token
    AND lease_expires_at > clock_timestamp()
  RETURNING * INTO v_step;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '40001';
  END IF;

  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, phase, processed, total
  ) VALUES (
    v_step.run_id, v_step.id, v_step.workspace_id, v_step.connection_id,
    v_step.step_key, 'checkpointed', v_step.attempt_count, v_step.failure_count,
    v_step.checkpoint ->> 'phase',
    NULLIF(v_step.checkpoint ->> 'processed', '')::integer,
    NULLIF(v_step.checkpoint ->> 'total', '')::integer
  );
  RETURN v_step;
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
DECLARE
  v_step public.marketplace_sync_run_steps%ROWTYPE;
BEGIN
  UPDATE public.marketplace_sync_run_steps
  SET state = 'pending',
      checkpoint = COALESCE(p_checkpoint, checkpoint),
      summary = CASE
        WHEN p_summary IS NULL OR p_summary = '{}'::jsonb THEN summary
        ELSE p_summary
      END,
      last_checkpoint_at = clock_timestamp(),
      next_attempt_at = COALESCE(p_next_attempt_at, clock_timestamp()),
      lease_token = NULL,
      lease_expires_at = NULL
  WHERE id = p_step_id
    AND state = 'running'
    AND lease_token = p_lease_token
    AND lease_expires_at > clock_timestamp()
  RETURNING * INTO v_step;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '40001';
  END IF;

  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count, phase, processed, total, next_action_at
  ) VALUES (
    v_step.run_id, v_step.id, v_step.workspace_id, v_step.connection_id,
    v_step.step_key, 'yielded', v_step.attempt_count, v_step.failure_count,
    v_step.checkpoint ->> 'phase',
    NULLIF(v_step.checkpoint ->> 'processed', '')::integer,
    NULLIF(v_step.checkpoint ->> 'total', '')::integer,
    v_step.next_attempt_at
  );
  PERFORM public._refresh_ozon_sync_run_v2(v_step.run_id);
  RETURN v_step;
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
          WHEN p_state = 'retry_scheduled' THEN 1
          WHEN p_state = 'failed'
            AND COALESCE((v_error ->> 'retryable')::boolean, false)
            THEN 1
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
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '40001';
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
  UPDATE public.marketplace_sync_run_steps
  SET state = 'pending',
      failure_count = CASE WHEN state = 'failed' THEN 0 ELSE failure_count END,
      next_attempt_at = clock_timestamp(),
      last_error = NULL,
      completed_at = NULL
  WHERE run_id = p_run_id
    AND state IN ('failed', 'retry_scheduled');
  SELECT * INTO v_run FROM public._refresh_ozon_sync_run_v2(p_run_id);
  RETURN v_run;
END;
$function$;

CREATE OR REPLACE FUNCTION public.begin_or_resume_ozon_sync_run_v2(
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
  v_run public.marketplace_sync_runs%ROWTYPE;
BEGIN
  SELECT *
  INTO v_run
  FROM public.begin_or_resume_ozon_sync_run(
    p_connection_id,
    p_date_from,
    p_date_to
  );

  SELECT *
  INTO v_run
  FROM public.retry_failed_ozon_sync_run_steps_v2(v_run.id);
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
    RAISE EXCEPTION 'Ozon sync step lease is stale' USING ERRCODE = '40001';
  END IF;

  WITH failed_steps AS (
    UPDATE public.marketplace_sync_run_steps
    SET state = 'failed',
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

CREATE OR REPLACE FUNCTION public.repair_ozon_sync_run_steps_v2(
  p_run_id UUID,
  p_step_keys TEXT[]
)
RETURNS public.marketplace_sync_runs
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $function$
DECLARE
  v_run public.marketplace_sync_runs%ROWTYPE;
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.marketplace_sync_run_steps
    WHERE run_id = p_run_id
      AND state = 'running'
      AND lease_expires_at > clock_timestamp()
  ) THEN
    RAISE EXCEPTION 'A live Ozon sync lease exists' USING ERRCODE = '55000';
  END IF;

  WITH reset_steps AS (
    UPDATE public.marketplace_sync_run_steps
    SET state = 'pending',
        failure_count = 0,
        next_attempt_at = clock_timestamp(),
        checkpoint = '{}'::jsonb,
        last_checkpoint_at = NULL,
        summary = '{}'::jsonb,
        last_error = NULL,
        completed_at = NULL,
        lease_token = NULL,
        lease_expires_at = NULL
    WHERE run_id = p_run_id
      AND step_key = ANY(p_step_keys)
      AND state IN ('completed', 'skipped', 'failed')
    RETURNING *
  )
  INSERT INTO public.marketplace_sync_step_events (
    run_id, step_id, workspace_id, connection_id, step_key, event_type,
    execution_count, failure_count
  )
  SELECT run_id, id, workspace_id, connection_id, step_key, 'repair_reset',
         attempt_count, failure_count
  FROM reset_steps;

  SELECT * INTO v_run FROM public._refresh_ozon_sync_run_v2(p_run_id);
  RETURN v_run;
END;
$function$;

REVOKE ALL ON TABLE public.marketplace_sync_step_events
  FROM PUBLIC, anon, authenticated;
GRANT SELECT ON TABLE public.marketplace_sync_step_events TO authenticated;

REVOKE EXECUTE ON FUNCTION public._refresh_ozon_sync_run_v2(uuid)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.claim_ozon_sync_run_step_v2(uuid)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.checkpoint_ozon_sync_run_step_v2(uuid, uuid, jsonb, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.yield_ozon_sync_run_step_v2(uuid, uuid, jsonb, jsonb, timestamptz)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.finish_ozon_sync_run_step_v2(uuid, uuid, text, jsonb, jsonb, timestamptz)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.retry_failed_ozon_sync_run_steps_v2(uuid)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.begin_or_resume_ozon_sync_run_v2(
  uuid, timestamptz, timestamptz
) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.fail_ozon_sync_connection_v2(uuid, uuid, jsonb)
  FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.repair_ozon_sync_run_steps_v2(uuid, text[])
  FROM PUBLIC, anon, authenticated;

GRANT EXECUTE ON FUNCTION public.claim_ozon_sync_run_step_v2(uuid)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.checkpoint_ozon_sync_run_step_v2(uuid, uuid, jsonb, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.yield_ozon_sync_run_step_v2(uuid, uuid, jsonb, jsonb, timestamptz)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.finish_ozon_sync_run_step_v2(uuid, uuid, text, jsonb, jsonb, timestamptz)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.retry_failed_ozon_sync_run_steps_v2(uuid)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.begin_or_resume_ozon_sync_run_v2(
  uuid, timestamptz, timestamptz
) TO service_role;
GRANT EXECUTE ON FUNCTION public.fail_ozon_sync_connection_v2(uuid, uuid, jsonb)
  TO service_role;
GRANT EXECUTE ON FUNCTION public.repair_ozon_sync_run_steps_v2(uuid, text[])
  TO service_role;
