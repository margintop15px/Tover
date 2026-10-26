BEGIN;

-- History attribution is available to members without exposing the team-admin API.
CREATE OR REPLACE FUNCTION private.workspace_actor_labels(p_workspace_id uuid, p_actor_ids uuid[])
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_labels jsonb;
BEGIN
  IF auth.uid() IS NULL OR NOT public.app_is_org_member(p_workspace_id) THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_actor_ids IS NULL OR cardinality(p_actor_ids) > 200 OR array_position(p_actor_ids, NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'Expected at most 200 actor IDs' USING ERRCODE = '22023';
  END IF;
  SELECT coalesce(jsonb_agg(jsonb_build_object('userId', m.user_id,
    'label', coalesce(nullif(btrim(p.display_name), ''), nullif(btrim(u.email), '')))
    ORDER BY m.user_id), '[]'::jsonb) INTO v_labels
  FROM public.organization_memberships m
  JOIN auth.users u ON u.id = m.user_id
  LEFT JOIN public.profiles p ON p.user_id = m.user_id
  WHERE m.organization_id = p_workspace_id AND m.user_id = ANY(p_actor_ids)
    AND coalesce(nullif(btrim(p.display_name), ''), nullif(btrim(u.email), '')) IS NOT NULL;
  RETURN v_labels;
END;
$$;

CREATE OR REPLACE FUNCTION public.workspace_actor_labels(p_workspace_id uuid, p_actor_ids uuid[])
RETURNS jsonb LANGUAGE sql STABLE SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.workspace_actor_labels(p_workspace_id, p_actor_ids);
$$;

REVOKE ALL ON FUNCTION private.workspace_actor_labels(uuid, uuid[]),
  public.workspace_actor_labels(uuid, uuid[]) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.workspace_actor_labels(uuid, uuid[]),
  public.workspace_actor_labels(uuid, uuid[]) TO authenticated;

NOTIFY pgrst, 'reload schema';
COMMIT;
