-- Allow the explicit, lease-free production repair flow to reset durable
-- retries alongside completed, skipped, and permanently failed steps.

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
      AND state IN ('completed', 'skipped', 'failed', 'retry_scheduled')
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

REVOKE EXECUTE ON FUNCTION public.repair_ozon_sync_run_steps_v2(uuid, text[])
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.repair_ozon_sync_run_steps_v2(uuid, text[])
  TO service_role;
