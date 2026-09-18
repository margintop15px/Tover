-- No existing accounts, invitations or memberships are removed or reclassified.
ALTER TABLE public.organization_memberships
  ADD COLUMN recovery_requested_at timestamptz;

CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC;
GRANT USAGE ON SCHEMA private TO authenticated;

-- Auth details are exposed only for members of a workspace managed by the caller.
CREATE FUNCTION private.workspace_team(p_workspace_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_members jsonb; v_invites jsonb;
BEGIN
  IF auth.uid() IS NULL OR NOT public.app_has_org_role(p_workspace_id, ARRAY['owner', 'admin']) THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'userId', m.user_id, 'name', p.display_name, 'email', u.email,
    'role', m.role_id, 'status', m.status, 'emailConfirmedAt', u.email_confirmed_at,
    'lastSignInAt', u.last_sign_in_at, 'recoveryRequestedAt', m.recovery_requested_at
  ) ORDER BY lower(u.email), m.user_id), '[]'::jsonb) INTO v_members
  FROM public.organization_memberships m
  JOIN auth.users u ON u.id = m.user_id
  LEFT JOIN public.profiles p ON p.user_id = m.user_id
  WHERE m.organization_id = p_workspace_id;

  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'id', i.id, 'email', lower(btrim(i.email)), 'role', i.role_id,
    'status', CASE WHEN i.expires_at <= now() OR i.status = 'expired' THEN 'expired' ELSE 'pending' END,
    'createdAt', i.created_at, 'expiresAt', i.expires_at,
    'lastRequestedAt', (SELECT max(a.created_at) FROM public.organization_invites a
      WHERE a.organization_id = p_workspace_id AND lower(btrim(a.email)) = lower(btrim(i.email)))
  ) ORDER BY i.created_at DESC, i.id), '[]'::jsonb) INTO v_invites
  FROM (
    SELECT DISTINCT ON (lower(btrim(email))) * FROM public.organization_invites
    WHERE organization_id = p_workspace_id AND status IN ('pending', 'expired')
    ORDER BY lower(btrim(email)), created_at DESC, id DESC
  ) i;
  RETURN jsonb_build_object('members', v_members, 'invitations', v_invites);
END;
$$;

-- The short transaction reserves one send. HTTP email delivery happens afterwards;
-- completion never makes a revoked/accepted invitation pending again.
CREATE FUNCTION private.manage_workspace_invitation(
  p_workspace_id uuid, p_action text, p_invite_id uuid DEFAULT NULL,
  p_email text DEFAULT NULL, p_role text DEFAULT 'member'
)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_email text; v_role text; v_invite public.organization_invites%ROWTYPE;
  v_member uuid; v_last_request timestamptz; v_existing uuid;
BEGIN
  IF auth.uid() IS NULL OR NOT public.app_has_org_role(p_workspace_id, ARRAY['owner', 'admin']) THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_action NOT IN ('create', 'resend', 'cancel') OR p_action IS NULL THEN
    RETURN jsonb_build_object('code', 'INVALID_INPUT');
  END IF;
  IF p_action = 'create' THEN
    v_email := lower(btrim(p_email)); v_role := p_role;
    IF v_email IS NULL OR length(v_email) > 254 OR v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
      OR v_role IS NULL OR v_role NOT IN ('admin', 'member') THEN
      RETURN jsonb_build_object('code', 'INVALID_INPUT');
    END IF;
  ELSE
    SELECT lower(btrim(email)) INTO v_email FROM public.organization_invites
    WHERE id = p_invite_id AND organization_id = p_workspace_id;
    IF NOT FOUND THEN RETURN jsonb_build_object('code', 'NOT_FOUND'); END IF;
  END IF;

  PERFORM pg_advisory_xact_lock(hashtextextended(p_workspace_id::text || ':' || v_email, 0));
  IF p_action <> 'create' THEN
    SELECT * INTO v_invite FROM public.organization_invites
    WHERE id = p_invite_id AND organization_id = p_workspace_id FOR UPDATE;
    IF p_action = 'cancel' AND v_invite.status = 'revoked' THEN
      RETURN jsonb_build_object('ok', true);
    END IF;
    IF v_invite.status NOT IN ('pending', 'expired') THEN
      RETURN jsonb_build_object('code', 'INVITE_CHANGED');
    END IF;
    v_role := v_invite.role_id;
    IF p_action = 'cancel' THEN
      UPDATE public.organization_invites SET status = 'revoked'
      WHERE organization_id = p_workspace_id AND lower(btrim(email)) = v_email
        AND status IN ('pending', 'expired');
      RETURN jsonb_build_object('ok', true);
    END IF;
    IF v_role NOT IN ('admin', 'member') THEN
      RETURN jsonb_build_object('code', 'INVALID_INPUT');
    END IF;
  END IF;

  SELECT m.user_id INTO v_member FROM public.organization_memberships m
  JOIN auth.users u ON u.id = m.user_id
  WHERE m.organization_id = p_workspace_id AND lower(btrim(u.email)) = v_email
    AND m.status IN ('active', 'suspended') LIMIT 1;
  IF FOUND THEN RETURN jsonb_build_object('code', 'MEMBER_EXISTS', 'userId', v_member); END IF;

  IF p_action = 'create' THEN
    SELECT id INTO v_existing FROM public.organization_invites
    WHERE organization_id = p_workspace_id AND lower(btrim(email)) = v_email
      AND status IN ('pending', 'expired') ORDER BY created_at DESC, id DESC LIMIT 1;
    IF FOUND THEN RETURN jsonb_build_object('code', 'INVITE_EXISTS', 'inviteId', v_existing); END IF;
  END IF;
  SELECT max(created_at) INTO v_last_request FROM public.organization_invites
  WHERE organization_id = p_workspace_id AND lower(btrim(email)) = v_email;
  IF v_last_request > clock_timestamp() - interval '60 seconds' THEN
    RETURN jsonb_build_object('code', 'RATE_LIMITED', 'retryAfter',
      greatest(1, ceil(extract(epoch FROM v_last_request + interval '60 seconds' - clock_timestamp()))));
  END IF;
  INSERT INTO public.organization_invites (organization_id, email, role_id, invited_by)
  VALUES (p_workspace_id, v_email, v_role, auth.uid()) RETURNING * INTO v_invite;
  RETURN jsonb_build_object('id', v_invite.id, 'email', v_email, 'role', v_role);
END;
$$;

CREATE FUNCTION private.request_member_recovery(p_workspace_id uuid, p_user_id uuid)
RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_email text; v_member public.organization_memberships%ROWTYPE;
BEGIN
  IF auth.uid() IS NULL OR NOT public.app_has_org_role(p_workspace_id, ARRAY['owner', 'admin']) THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO v_member FROM public.organization_memberships
  WHERE organization_id = p_workspace_id AND user_id = p_user_id FOR UPDATE;
  IF NOT FOUND THEN RETURN jsonb_build_object('code', 'NOT_FOUND'); END IF;
  IF v_member.status <> 'active' THEN RETURN jsonb_build_object('code', 'MEMBER_INACTIVE'); END IF;
  IF v_member.recovery_requested_at > clock_timestamp() - interval '60 seconds' THEN
    RETURN jsonb_build_object('code', 'RATE_LIMITED', 'retryAfter',
      greatest(1, ceil(extract(epoch FROM v_member.recovery_requested_at + interval '60 seconds' - clock_timestamp()))));
  END IF;
  SELECT email INTO v_email FROM auth.users WHERE id = p_user_id;
  IF v_email IS NULL THEN RETURN jsonb_build_object('code', 'NOT_FOUND'); END IF;
  UPDATE public.organization_memberships SET recovery_requested_at = clock_timestamp()
  WHERE id = v_member.id;
  RETURN jsonb_build_object('email', v_email);
END;
$$;

CREATE FUNCTION private.accept_workspace_invitations()
RETURNS integer LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_email text; v_org uuid; v_role text; v_count integer := 0; v_updated integer;
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Not authenticated' USING ERRCODE = '42501'; END IF;
  SELECT lower(btrim(email)) INTO v_email FROM auth.users
  WHERE id = auth.uid() AND email_confirmed_at IS NOT NULL FOR UPDATE;
  IF v_email IS NULL THEN RETURN 0; END IF;
  -- Same locks and ordering as manager actions: cancellation cannot race acceptance.
  FOR v_org IN SELECT DISTINCT organization_id FROM public.organization_invites
    WHERE lower(btrim(email)) = v_email AND status = 'pending' AND expires_at > now()
    ORDER BY organization_id
  LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended(v_org::text || ':' || v_email, 0));
    SELECT role_id INTO v_role FROM public.organization_invites
    WHERE organization_id = v_org AND lower(btrim(email)) = v_email
      AND status = 'pending' AND expires_at > now()
    ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE;
    IF NOT FOUND THEN CONTINUE; END IF;
    INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status)
    VALUES (v_org, auth.uid(), v_role, 'active')
    ON CONFLICT (organization_id, user_id) DO UPDATE
    SET role_id = excluded.role_id, status = 'active', updated_at = now()
    WHERE public.organization_memberships.status = 'invited';

    IF EXISTS (SELECT 1 FROM public.organization_memberships
      WHERE organization_id = v_org AND user_id = auth.uid() AND status = 'active') THEN
      UPDATE public.organization_invites SET status = 'accepted'
      WHERE organization_id = v_org AND lower(btrim(email)) = v_email
        AND status = 'pending' AND expires_at > now();
      GET DIAGNOSTICS v_updated = ROW_COUNT;
      v_count := v_count + v_updated;
    END IF;
  END LOOP;
  RETURN v_count;
END;
$$;

-- Only invoker wrappers are in the exposed schema. The private functions also
-- authenticate and authorize every call, including direct SQL calls.
CREATE FUNCTION public.workspace_team(p_workspace_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.workspace_team(p_workspace_id);
$$;
CREATE FUNCTION public.manage_workspace_invitation(
  p_workspace_id uuid, p_action text, p_invite_id uuid DEFAULT NULL,
  p_email text DEFAULT NULL, p_role text DEFAULT 'member'
)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.manage_workspace_invitation(p_workspace_id, p_action, p_invite_id, p_email, p_role);
$$;
CREATE FUNCTION public.request_member_recovery(p_workspace_id uuid, p_user_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.request_member_recovery(p_workspace_id, p_user_id);
$$;
CREATE OR REPLACE FUNCTION public.accept_my_organization_invites()
RETURNS integer LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.accept_workspace_invitations();
$$;

REVOKE ALL ON FUNCTION private.workspace_team(uuid),
  private.manage_workspace_invitation(uuid, text, uuid, text, text),
  private.request_member_recovery(uuid, uuid), private.accept_workspace_invitations(),
  public.workspace_team(uuid), public.manage_workspace_invitation(uuid, text, uuid, text, text),
  public.request_member_recovery(uuid, uuid), public.accept_my_organization_invites() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.workspace_team(uuid),
  private.manage_workspace_invitation(uuid, text, uuid, text, text),
  private.request_member_recovery(uuid, uuid), private.accept_workspace_invitations(),
  public.workspace_team(uuid), public.manage_workspace_invitation(uuid, text, uuid, text, text),
  public.request_member_recovery(uuid, uuid), public.accept_my_organization_invites() TO authenticated;
