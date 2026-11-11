-- Isolated Goods delivery. Existing directory/domain rows and journal payloads stay unchanged.
BEGIN;

ALTER TABLE app_private.bel_events ADD COLUMN IF NOT EXISTS scope_kind text NOT NULL DEFAULT 'user'
  CHECK (scope_kind IN ('user', 'workspace'));
ALTER TABLE app_private.bel_receipts ADD COLUMN IF NOT EXISTS scope_kind text NOT NULL DEFAULT 'user'
  CHECK (scope_kind IN ('user', 'workspace'));
-- Old rows have unknown arrival/order metadata; do not invent historical values.
ALTER TABLE app_private.bel_events ADD COLUMN IF NOT EXISTS event_index integer CHECK (event_index >= 0);
ALTER TABLE app_private.bel_events ADD COLUMN IF NOT EXISTS recorded_at timestamptz;
ALTER TABLE app_private.bel_events ALTER COLUMN recorded_at SET DEFAULT clock_timestamp();
ALTER TABLE app_private.bel_receipts DROP CONSTRAINT bel_receipts_pkey;
ALTER TABLE app_private.bel_receipts ADD PRIMARY KEY (scope_kind, scope_id, command_id);
CREATE UNIQUE INDEX IF NOT EXISTS bel_events_scope_command_order_idx
  ON app_private.bel_events(scope_kind, scope_id, command_id, event_index);

-- Row locking normally needs UPDATE privilege; expose only the caller's membership lock.
CREATE OR REPLACE FUNCTION app_private.bel_workspace_membership(p_workspace_id uuid)
RETURNS TABLE(role_id text, status text)
LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.role_id, m.status FROM public.organization_memberships m
    WHERE m.organization_id = p_workspace_id AND m.user_id = auth.uid()
      AND EXISTS (SELECT FROM app_private.sessions s
        WHERE s.id = current_setting('tover.session_key', true)
          AND s.user_id = auth.uid() AND s.expires_at > clock_timestamp())
    FOR SHARE OF m;
$$;
REVOKE ALL ON FUNCTION app_private.bel_workspace_membership(uuid)
  FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_workspace_membership(uuid) TO tover_app;

ALTER POLICY bel_events_user ON app_private.bel_events
  USING (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id)
  WITH CHECK (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id);
ALTER POLICY bel_receipts_user ON app_private.bel_receipts
  USING (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id)
  WITH CHECK (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id);

DROP POLICY IF EXISTS bel_events_workspace_read ON app_private.bel_events;
CREATE POLICY bel_events_workspace_read ON app_private.bel_events FOR SELECT TO tover_app
  USING (scope_kind = 'workspace' AND EXISTS (
    SELECT FROM public.organization_memberships m
      WHERE m.organization_id = scope_id AND m.user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND m.status = 'active'));
DROP POLICY IF EXISTS bel_receipts_workspace_read ON app_private.bel_receipts;
CREATE POLICY bel_receipts_workspace_read ON app_private.bel_receipts FOR SELECT TO tover_app
  USING (scope_kind = 'workspace' AND EXISTS (
    SELECT FROM public.organization_memberships m
      WHERE m.organization_id = scope_id AND m.user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND m.status = 'active'));

-- Provision a private worker login separately. It gets no directory, Auth or session access.
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tover_journal') THEN
    CREATE ROLE tover_journal NOLOGIN;
  END IF;
END $$;
GRANT USAGE ON SCHEMA app_private TO tover_journal;
GRANT SELECT, INSERT ON app_private.bel_events, app_private.bel_receipts TO tover_journal;
DROP POLICY IF EXISTS bel_events_delivery ON app_private.bel_events;
CREATE POLICY bel_events_delivery ON app_private.bel_events TO tover_journal
  USING (scope_kind = 'workspace') WITH CHECK (scope_kind = 'workspace');
DROP POLICY IF EXISTS bel_receipts_delivery ON app_private.bel_receipts;
CREATE POLICY bel_receipts_delivery ON app_private.bel_receipts TO tover_journal
  USING (scope_kind = 'workspace') WITH CHECK (scope_kind = 'workspace');

COMMIT;
