-- Invitation delivery requests and private lookups for the Bel invitation slice. Additive and idempotent; apply after 037.
-- 036 said the worker login gets no directory, Auth or session access. 038 narrows that: tover_journal gains
-- read access to organization_invites, organizations and invitation_deliveries, update of the delivery
-- columns listed below, and EXECUTE on app_private.bel_recipient_language only. Still no Auth or session access.
BEGIN;

CREATE TABLE IF NOT EXISTS public.invitation_deliveries (
  id uuid PRIMARY KEY,
  invitation_id uuid NOT NULL REFERENCES public.organization_invites(id) ON DELETE CASCADE,
  organization_id uuid NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  requested_by uuid NOT NULL,
  requested_at timestamptz NOT NULL,
  status text NOT NULL CHECK (status IN ('queued','retrying','unconfirmed','submitted','failed','exhausted','stopped')),
  attempts int NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 4),
  next_attempt_at timestamptz,
  last_problem text CHECK (last_problem IN ('temporary','permanent','uncertain')),
  attempted_at timestamptz,
  submitted_at timestamptz
);
CREATE INDEX IF NOT EXISTS invitation_deliveries_due_idx
  ON public.invitation_deliveries (next_attempt_at) WHERE status IN ('queued','retrying');
ALTER TABLE public.invitation_deliveries ENABLE ROW LEVEL SECURITY;

-- Private directory lookups. Neither is callable by Auth-facing roles.
CREATE OR REPLACE FUNCTION app_private.bel_recipient_membership(p_workspace_id uuid, p_email text)
RETURNS text LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  -- Scoped to managers of the workspace (not an account-existence oracle); VOLATILE because the membership lock is FOR SHARE.
  SELECT m.status FROM public.organization_memberships m JOIN auth.users u ON u.id = m.user_id
    WHERE m.organization_id = p_workspace_id
      AND EXISTS (SELECT FROM app_private.bel_workspace_membership(p_workspace_id) w
        WHERE w.role_id IN ('owner','admin') AND w.status = 'active')
      AND lower(btrim(u.email)) = lower(btrim(p_email))
    ORDER BY (m.status = 'active') DESC, m.created_at LIMIT 1;
$$;
REVOKE ALL ON FUNCTION app_private.bel_recipient_membership(uuid, text)
  FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_recipient_membership(uuid, text) TO tover_app;

CREATE OR REPLACE FUNCTION app_private.bel_recipient_language(p_email text)
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT p.language FROM public.profiles p JOIN auth.users u ON u.id = p.user_id
    WHERE lower(btrim(u.email)) = lower(btrim(p_email))
    ORDER BY p.created_at, p.user_id LIMIT 1;
$$;
REVOKE ALL ON FUNCTION app_private.bel_recipient_language(text)
  FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_recipient_language(text) TO tover_journal;

-- Runtime role: managers issue invitations and delivery requests.
GRANT SELECT ON public.organization_invites, public.invitation_deliveries TO tover_app;
GRANT INSERT (id, organization_id, email, role_id, invited_by, status, expires_at, created_at)
  ON public.organization_invites TO tover_app;
GRANT UPDATE (status) ON public.organization_invites TO tover_app;
GRANT INSERT ON public.invitation_deliveries TO tover_app;

-- No unique index on pending offers: legacy resend inserts rows that would violate it.
DROP POLICY IF EXISTS bel_invites_manager_read ON public.organization_invites;
CREATE POLICY bel_invites_manager_read ON public.organization_invites FOR SELECT TO tover_app
  USING (EXISTS (SELECT FROM app_private.bel_workspace_membership(organization_id) w
    WHERE w.role_id IN ('owner','admin') AND w.status = 'active'));
DROP POLICY IF EXISTS bel_invites_manager_insert ON public.organization_invites;
CREATE POLICY bel_invites_manager_insert ON public.organization_invites FOR INSERT TO tover_app
  WITH CHECK (invited_by = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM app_private.bel_workspace_membership(organization_id) w
      WHERE w.role_id IN ('owner','admin') AND w.status = 'active'));
DROP POLICY IF EXISTS bel_invites_manager_update ON public.organization_invites;
CREATE POLICY bel_invites_manager_update ON public.organization_invites FOR UPDATE TO tover_app
  USING (EXISTS (SELECT FROM app_private.bel_workspace_membership(organization_id) w
    WHERE w.role_id IN ('owner','admin') AND w.status = 'active'))
  WITH CHECK (EXISTS (SELECT FROM app_private.bel_workspace_membership(organization_id) w
    WHERE w.role_id IN ('owner','admin') AND w.status = 'active'));

DROP POLICY IF EXISTS bel_deliveries_manager_read ON public.invitation_deliveries;
CREATE POLICY bel_deliveries_manager_read ON public.invitation_deliveries FOR SELECT TO tover_app
  USING (EXISTS (SELECT FROM app_private.bel_workspace_membership(organization_id) w
    WHERE w.role_id IN ('owner','admin') AND w.status = 'active'));
DROP POLICY IF EXISTS bel_deliveries_manager_insert ON public.invitation_deliveries;
CREATE POLICY bel_deliveries_manager_insert ON public.invitation_deliveries FOR INSERT TO tover_app
  WITH CHECK (requested_by = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM public.organization_invites i
      WHERE i.id = invitation_id AND i.organization_id = invitation_deliveries.organization_id)
    AND EXISTS (SELECT FROM app_private.bel_workspace_membership(organization_id) w
      WHERE w.role_id IN ('owner','admin') AND w.status = 'active'));

-- Worker login: narrow reads and delivery bookkeeping only.
GRANT USAGE ON SCHEMA public TO tover_journal;
GRANT SELECT ON public.organization_invites, public.organizations, public.invitation_deliveries TO tover_journal;
GRANT UPDATE (status, attempts, next_attempt_at, last_problem, attempted_at, submitted_at)
  ON public.invitation_deliveries TO tover_journal;
DROP POLICY IF EXISTS bel_invites_delivery_read ON public.organization_invites;
CREATE POLICY bel_invites_delivery_read ON public.organization_invites FOR SELECT TO tover_journal USING (true);
DROP POLICY IF EXISTS bel_organizations_delivery_read ON public.organizations;
CREATE POLICY bel_organizations_delivery_read ON public.organizations FOR SELECT TO tover_journal USING (true);
DROP POLICY IF EXISTS bel_deliveries_delivery_read ON public.invitation_deliveries;
CREATE POLICY bel_deliveries_delivery_read ON public.invitation_deliveries FOR SELECT TO tover_journal USING (true);
DROP POLICY IF EXISTS bel_deliveries_delivery_update ON public.invitation_deliveries;
CREATE POLICY bel_deliveries_delivery_update ON public.invitation_deliveries FOR UPDATE TO tover_journal
  USING (true) WITH CHECK (true);

-- Colocated workspace-scoped directory commands write the journal as the runtime role, for active members only.
DROP POLICY IF EXISTS bel_events_workspace_write ON app_private.bel_events;
CREATE POLICY bel_events_workspace_write ON app_private.bel_events FOR INSERT TO tover_app
  WITH CHECK (scope_kind = 'workspace' AND actor_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM public.organization_memberships m
      WHERE m.organization_id = scope_id AND m.user_id = actor_id AND m.status = 'active'));
DROP POLICY IF EXISTS bel_receipts_workspace_write ON app_private.bel_receipts;
CREATE POLICY bel_receipts_workspace_write ON app_private.bel_receipts FOR INSERT TO tover_app
  WITH CHECK (scope_kind = 'workspace' AND actor_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM public.organization_memberships m
      WHERE m.organization_id = scope_id AND m.user_id = actor_id AND m.status = 'active'));

COMMIT;
