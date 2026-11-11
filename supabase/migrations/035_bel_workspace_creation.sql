-- Isolated Bel milestone. Apply explicitly; no live routes or existing rows change.
BEGIN;

CREATE TABLE IF NOT EXISTS app_private.bel_events (
  id uuid PRIMARY KEY,
  scope_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  command_id text NOT NULL,
  event_type text NOT NULL,
  occurred_at timestamptz NOT NULL,
  data jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object')
);
CREATE INDEX IF NOT EXISTS bel_events_command_idx
  ON app_private.bel_events(scope_id, command_id);

CREATE TABLE IF NOT EXISTS app_private.bel_receipts (
  scope_id uuid NOT NULL,
  command_id text NOT NULL,
  actor_id uuid NOT NULL,
  payload bytea NOT NULL,
  PRIMARY KEY (scope_id, command_id)
);

ALTER TABLE app_private.bel_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.bel_receipts ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.bel_events, app_private.bel_receipts FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT ON app_private.bel_events, app_private.bel_receipts TO tover_app;

DROP POLICY IF EXISTS bel_events_user ON app_private.bel_events;
CREATE POLICY bel_events_user ON app_private.bel_events TO tover_app
  USING (scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id)
  WITH CHECK (scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id);
DROP POLICY IF EXISTS bel_receipts_user ON app_private.bel_receipts;
CREATE POLICY bel_receipts_user ON app_private.bel_receipts TO tover_app
  USING (scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id)
  WITH CHECK (scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND actor_id = scope_id);

-- Narrow private lookup: do not grant the runtime access to Auth credentials.
CREATE OR REPLACE FUNCTION app_private.bel_confirmed_user()
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE confirmed boolean;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT FROM app_private.sessions s
    WHERE s.id = current_setting('tover.session_key', true)
      AND s.user_id = auth.uid() AND s.expires_at > clock_timestamp()
  ) THEN RETURN false; END IF;
  SELECT u.email_confirmed_at IS NOT NULL INTO confirmed
    FROM auth.users u WHERE u.id = auth.uid() FOR SHARE;
  PERFORM 1 FROM public.profiles p WHERE p.user_id = auth.uid() FOR SHARE;
  RETURN coalesce(confirmed, false);
END;
$$;

-- Only the first owner of a workspace inserted by this transaction may be added.
CREATE OR REPLACE FUNCTION app_private.bel_new_workspace(p_workspace_id uuid)
RETURNS boolean LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT auth.uid() IS NOT NULL AND EXISTS (
    SELECT FROM public.organizations o
    WHERE o.id = p_workspace_id AND o.created_by = auth.uid()
      AND o.xmin = pg_current_xact_id()::xid
  );
$$;
REVOKE ALL ON FUNCTION app_private.bel_confirmed_user(), app_private.bel_new_workspace(uuid)
  FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_confirmed_user(), app_private.bel_new_workspace(uuid) TO tover_app;

GRANT USAGE ON SCHEMA public TO tover_app;
GRANT SELECT ON public.profiles, public.organizations, public.organization_memberships TO tover_app;
GRANT INSERT (id, name, created_by, created_at) ON public.organizations TO tover_app;
GRANT INSERT (id, organization_id, user_id, role_id, status) ON public.organization_memberships TO tover_app;

DROP POLICY IF EXISTS bel_profile_read ON public.profiles;
CREATE POLICY bel_profile_read ON public.profiles FOR SELECT TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
DROP POLICY IF EXISTS bel_membership_read ON public.organization_memberships;
CREATE POLICY bel_membership_read ON public.organization_memberships FOR SELECT TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
DROP POLICY IF EXISTS bel_workspace_read ON public.organizations;
CREATE POLICY bel_workspace_read ON public.organizations FOR SELECT TO tover_app
  USING (EXISTS (SELECT FROM public.organization_memberships m
    WHERE m.organization_id = public.organizations.id AND m.user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
      AND m.status = 'active'));
DROP POLICY IF EXISTS bel_workspace_create ON public.organizations;
CREATE POLICY bel_workspace_create ON public.organizations FOR INSERT TO tover_app
  WITH CHECK (created_by = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
DROP POLICY IF EXISTS bel_first_owner ON public.organization_memberships;
CREATE POLICY bel_first_owner ON public.organization_memberships FOR INSERT TO tover_app
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND role_id = 'owner' AND status = 'active' AND app_private.bel_new_workspace(organization_id));

COMMIT;
