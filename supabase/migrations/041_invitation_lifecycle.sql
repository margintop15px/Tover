-- Invitation lifecycle (chapters 05, 05a, 05b, 06). Additive and idempotent; every run re-applies 034 onward.
--
-- Decisions:
-- * One pending offer per workspace and normalized recipient. Legacy resend inserted a new pending row for the
--   same pair; older duplicates are superseded, so they are marked 'revoked' (rows are kept) before the index.
-- * Membership removal (chapter 36, not built yet) is recorded as status 'removed' with `removed_at`; the row
--   stays so rejoining reuses it. Offers created at or before `removed_at` can never grant access again.
-- * The recipient reads and accepts only offers addressed to the verified email of the signed-in account.
--   Acceptance and the active membership grant commit together: the membership write is allowed only for an
--   offer this transaction marked accepted, with exactly that role.
-- * Delivery requests that stay Unconfirmed are reconciled for 60 seconds (`next_attempt_at` holds that
--   deadline) and then stopped by the worker.
BEGIN;

-- Memberships: removal marker.
ALTER TABLE public.organization_memberships ADD COLUMN IF NOT EXISTS removed_at timestamptz;
ALTER TABLE public.organization_memberships DROP CONSTRAINT IF EXISTS organization_memberships_status_check;
ALTER TABLE public.organization_memberships ADD CONSTRAINT organization_memberships_status_check
  CHECK (status IN ('active', 'invited', 'suspended', 'removed'));

-- Offers: one pending offer per workspace and recipient.
UPDATE public.organization_invites i SET status = 'revoked'
  WHERE i.status = 'pending' AND EXISTS (
    SELECT FROM public.organization_invites n
      WHERE n.organization_id = i.organization_id AND n.status = 'pending'
        AND lower(btrim(n.email)) = lower(btrim(i.email)) AND (n.created_at, n.id) > (i.created_at, i.id));
CREATE UNIQUE INDEX IF NOT EXISTS organization_invites_one_pending_idx
  ON public.organization_invites (organization_id, lower(btrim(email))) WHERE status = 'pending';

CREATE INDEX IF NOT EXISTS invitation_deliveries_reconcile_idx
  ON public.invitation_deliveries (next_attempt_at) WHERE status = 'unconfirmed';
CREATE INDEX IF NOT EXISTS invitation_deliveries_invitation_idx
  ON public.invitation_deliveries (invitation_id, requested_at);

-- The verified email of the signed-in account, normalized; null when unconfirmed or signed out.
CREATE OR REPLACE FUNCTION app_private.bel_my_email()
RETURNS text LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT lower(btrim(u.email)) FROM auth.users u
    WHERE u.id = auth.uid() AND u.email_confirmed_at IS NOT NULL;
$$;

-- One offer, only for its verified recipient, with the names the review screen shows.
CREATE OR REPLACE FUNCTION app_private.bel_received_invitation(p_invitation_id uuid)
RETURNS TABLE(id uuid, organization_id uuid, workspace_name text, email text, role_id text, inviter_name text,
              status text, expires_at timestamptz, created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT i.id, i.organization_id, o.name, lower(btrim(i.email)), i.role_id,
         coalesce(nullif(btrim(p.display_name), ''), u.email), i.status, i.expires_at, i.created_at
    FROM public.organization_invites i
    JOIN public.organizations o ON o.id = i.organization_id
    LEFT JOIN public.profiles p ON p.user_id = i.invited_by
    LEFT JOIN auth.users u ON u.id = i.invited_by
    WHERE i.id = p_invitation_id AND lower(btrim(i.email)) = app_private.bel_my_email();
$$;

-- Manager-scoped recipient access: membership status and latest removal (not an account-existence oracle).
CREATE OR REPLACE FUNCTION app_private.bel_recipient_access(p_workspace_id uuid, p_email text)
RETURNS TABLE(status text, removed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.status, m.removed_at FROM public.organization_memberships m JOIN auth.users u ON u.id = m.user_id
    WHERE m.organization_id = p_workspace_id
      AND EXISTS (SELECT FROM app_private.bel_workspace_membership(p_workspace_id) w
        WHERE w.role_id IN ('owner','admin') AND w.status = 'active')
      AND lower(btrim(u.email)) = lower(btrim(p_email))
    ORDER BY (m.status = 'active') DESC, m.created_at LIMIT 1;
$$;

-- The team for its current owners and admins: names and emails of current, suspended and removed members.
CREATE OR REPLACE FUNCTION app_private.bel_workspace_team(p_workspace_id uuid)
RETURNS TABLE(user_id uuid, display_name text, email text, role_id text, status text, removed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.user_id, p.display_name, lower(btrim(u.email)), m.role_id, m.status, m.removed_at
    FROM public.organization_memberships m
    JOIN auth.users u ON u.id = m.user_id
    LEFT JOIN public.profiles p ON p.user_id = m.user_id
    WHERE m.organization_id = p_workspace_id AND m.status IN ('active', 'suspended', 'removed')
      AND EXISTS (SELECT FROM app_private.bel_workspace_membership(p_workspace_id) w
        WHERE w.role_id IN ('owner','admin') AND w.status = 'active')
    ORDER BY lower(btrim(u.email)), m.user_id;
$$;

-- True only for an offer to the caller that this transaction accepted with exactly this role.
CREATE OR REPLACE FUNCTION app_private.bel_invitation_grant(p_workspace_id uuid, p_role_id text)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT FROM public.organization_invites i
    WHERE i.organization_id = p_workspace_id AND i.role_id = p_role_id AND i.status = 'accepted'
      AND lower(btrim(i.email)) = app_private.bel_my_email() AND i.xmin = pg_current_xact_id()::xid);
$$;

REVOKE ALL ON FUNCTION app_private.bel_my_email(), app_private.bel_received_invitation(uuid),
  app_private.bel_recipient_access(uuid, text), app_private.bel_workspace_team(uuid),
  app_private.bel_invitation_grant(uuid, text)
  FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_my_email(), app_private.bel_received_invitation(uuid),
  app_private.bel_recipient_access(uuid, text), app_private.bel_workspace_team(uuid),
  app_private.bel_invitation_grant(uuid, text)
  TO tover_app;

-- Recipient: read and accept own pending offers.
DROP POLICY IF EXISTS bel_invites_recipient_read ON public.organization_invites;
CREATE POLICY bel_invites_recipient_read ON public.organization_invites FOR SELECT TO tover_app
  USING (lower(btrim(email)) = app_private.bel_my_email());
DROP POLICY IF EXISTS bel_invites_recipient_accept ON public.organization_invites;
CREATE POLICY bel_invites_recipient_accept ON public.organization_invites FOR UPDATE TO tover_app
  USING (status = 'pending' AND lower(btrim(email)) = app_private.bel_my_email())
  WITH CHECK (status = 'accepted' AND lower(btrim(email)) = app_private.bel_my_email());

-- Recipient: the accepted offer's membership, new or rejoining (a legacy 'invited' row or a removed one).
GRANT UPDATE (organization_id, user_id, role_id, status) ON public.organization_memberships TO tover_app;
DROP POLICY IF EXISTS bel_invited_member_insert ON public.organization_memberships;
CREATE POLICY bel_invited_member_insert ON public.organization_memberships FOR INSERT TO tover_app
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND status = 'active'
    AND app_private.bel_invitation_grant(organization_id, role_id));
DROP POLICY IF EXISTS bel_invited_member_rejoin ON public.organization_memberships;
CREATE POLICY bel_invited_member_rejoin ON public.organization_memberships FOR UPDATE TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND status IN ('invited', 'removed'))
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND status = 'active'
    AND app_private.bel_invitation_grant(organization_id, role_id));

COMMIT;
