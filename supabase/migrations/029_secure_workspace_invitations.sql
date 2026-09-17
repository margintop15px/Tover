-- Signup metadata is user-controlled. It must never grant access to an
-- existing workspace. Invitations are accepted only after email verification.
CREATE OR REPLACE FUNCTION public.bootstrap_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_display_name text;
  v_org_name text;
  v_org_id uuid;
BEGIN
  v_display_name := nullif(trim(new.raw_user_meta_data ->> 'name'), '');

  INSERT INTO public.profiles (user_id, display_name)
  VALUES (new.id, v_display_name)
  ON CONFLICT (user_id) DO UPDATE
  SET display_name = coalesce(excluded.display_name, public.profiles.display_name),
      updated_at = now();

  -- Self-service signup may create a NEW workspace owned by this user.
  v_org_name := nullif(trim(new.raw_user_meta_data ->> 'organization_name'), '');
  IF v_org_name IS NOT NULL THEN
    INSERT INTO public.organizations (name, created_by)
    VALUES (v_org_name, new.id)
    RETURNING id INTO v_org_id;

    INSERT INTO public.organization_memberships
      (organization_id, user_id, role_id, status)
    VALUES (v_org_id, new.id, 'owner', 'active')
    ON CONFLICT (organization_id, user_id) DO NOTHING;

    INSERT INTO public.warehouses (workspace_id, name, is_default_defect)
    VALUES (v_org_id, 'Брак', true);
  END IF;

  -- Deliberately ignore organization_id and organization_role in user metadata.
  RETURN new;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.bootstrap_new_user() FROM PUBLIC, anon, authenticated;

CREATE OR REPLACE FUNCTION public.accept_my_organization_invites()
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
  v_email text;
  v_user_id uuid;
  v_count integer;
BEGIN
  v_user_id := auth.uid();
  IF v_user_id IS NULL THEN
    RAISE EXCEPTION 'Not authenticated';
  END IF;

  -- Trust Auth's verified email, never metadata or a caller-supplied address.
  -- Serialize concurrent acceptance requests for the same account.
  SELECT lower(u.email) INTO v_email
  FROM auth.users u
  WHERE u.id = v_user_id AND u.email_confirmed_at IS NOT NULL
  FOR UPDATE;

  IF v_email IS NULL THEN
    RETURN 0;
  END IF;

  WITH pending AS MATERIALIZED (
    SELECT i.id, i.organization_id, i.role_id, i.created_at
    FROM public.organization_invites i
    WHERE lower(i.email) = v_email
      AND i.status = 'pending'
      AND i.expires_at > now()
    FOR UPDATE
  ), memberships AS (
    INSERT INTO public.organization_memberships
      (organization_id, user_id, role_id, status)
    -- Resends can leave multiple pending records. Use the latest invitation.
    SELECT DISTINCT ON (p.organization_id)
      p.organization_id, v_user_id, p.role_id, 'active'
    FROM pending p
    ORDER BY p.organization_id, p.created_at DESC, p.id DESC
    ON CONFLICT (organization_id, user_id) DO UPDATE
    SET role_id = excluded.role_id,
        status = 'active',
        updated_at = now()
    RETURNING organization_id
  )
  UPDATE public.organization_invites i
  SET status = 'accepted', updated_at = now()
  FROM pending p
  JOIN memberships m ON m.organization_id = p.organization_id
  WHERE i.id = p.id;

  GET DIAGNOSTICS v_count = ROW_COUNT;
  RETURN v_count;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.accept_my_organization_invites() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.accept_my_organization_invites() TO authenticated;

-- Existing memberships are intentionally unchanged. Review any memberships
-- granted before this migration separately; do not infer legitimacy from metadata.
