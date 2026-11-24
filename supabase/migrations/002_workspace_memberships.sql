-- Workspace Memberships (board chapters 34–39): membership revision and Team row fields, manager and self
-- writes under row policies, and a lock on a workspace's active Owners for the "at least one active Owner" rule.

ALTER TABLE public.memberships
  ADD COLUMN revision          bigint NOT NULL DEFAULT 1,
  ADD COLUMN removal_kind      text CHECK (removal_kind IN ('removed', 'left')),
  ADD COLUMN suspended_at      timestamptz,
  ADD COLUMN suspension_reason text CHECK (suspension_reason IS NULL OR char_length(suspension_reason) <= 500);

CREATE FUNCTION app_private.bel_owner(p_workspace_id uuid) RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT FROM app_private.bel_workspace_membership(p_workspace_id) w
    WHERE w.role = 'owner' AND w.status = 'active');
$$;

-- Active Owners of the caller's workspace, locked. Under Repeatable Read a concurrent committed change to any of
-- these rows makes this lock fail with a serialization error, so two departures cannot both see a co-owner.
CREATE FUNCTION app_private.bel_active_owners(p_workspace_id uuid) RETURNS SETOF uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF NOT EXISTS (SELECT FROM app_private.bel_workspace_membership(p_workspace_id) w WHERE w.status = 'active') THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  RETURN QUERY SELECT m.user_id FROM public.memberships m
    WHERE m.workspace_id = p_workspace_id AND m.role = 'owner' AND m.status = 'active'
    ORDER BY m.user_id
    FOR UPDATE;
END;
$$;

DROP FUNCTION app_private.bel_workspace_team(uuid);
CREATE FUNCTION app_private.bel_workspace_team(p_workspace_id uuid)
RETURNS TABLE(membership_id uuid, user_id uuid, name text, email text, role text, status text, revision bigint,
              removed_at timestamptz, removal_kind text, suspended_at timestamptz, suspension_reason text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.membership_id, m.user_id, p.display_name, lower(btrim(u.email)), m.role, m.status, m.revision,
         m.removed_at, m.removal_kind, m.suspended_at, m.suspension_reason
    FROM public.memberships m
    JOIN auth.users u ON u.id = m.user_id
    LEFT JOIN public.profiles p ON p.user_id = m.user_id
    WHERE m.workspace_id = p_workspace_id AND m.status IN ('active', 'suspended', 'removed')
      AND app_private.bel_manager(p_workspace_id)
    ORDER BY lower(btrim(u.email)), m.user_id;
$$;

REVOKE ALL ON FUNCTION app_private.bel_owner(uuid), app_private.bel_active_owners(uuid),
  app_private.bel_workspace_team(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_private.bel_owner(uuid), app_private.bel_active_owners(uuid),
  app_private.bel_workspace_team(uuid) TO tover_app;

-- Managers read and change memberships of their workspace; Owner rows, before and after, only by an active Owner.
-- Business rules (revision, last Owner, own membership) are decided by the commands; these policies are the floor.
CREATE POLICY membership_manager_read ON public.memberships FOR SELECT TO tover_app
  USING (app_private.bel_manager(workspace_id));
CREATE POLICY membership_manage ON public.memberships FOR UPDATE TO tover_app
  USING (app_private.bel_manager(workspace_id) AND (role <> 'owner' OR app_private.bel_owner(workspace_id)))
  WITH CHECK (app_private.bel_manager(workspace_id) AND (role <> 'owner' OR app_private.bel_owner(workspace_id)));
-- A member ends only their own active membership.
CREATE POLICY membership_leave ON public.memberships FOR UPDATE TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND status = 'active')
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid AND status = 'removed'
    AND removal_kind = 'left');

GRANT UPDATE (revision, removed_at, removal_kind, suspended_at, suspension_reason) ON public.memberships TO tover_app;
