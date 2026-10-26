-- Only the disposable cluster in scripts/test-auth-security.sh runs this fixture.
\ir ../migrations/034_workspace_actor_labels.sql
\ir ../migrations/034_workspace_actor_labels.sql

INSERT INTO public.organizations (id, name) VALUES
  ('70000000-0000-4000-8000-000000000001', 'Actor labels'),
  ('70000000-0000-4000-8000-000000000002', 'Other actor labels');
INSERT INTO auth.users (id, email, email_confirmed_at) VALUES
  ('70000000-0000-4000-8000-000000000011', 'actor-owner@example.test', now()),
  ('70000000-0000-4000-8000-000000000012', 'actor-member@example.test', now()),
  ('70000000-0000-4000-8000-000000000013', 'former-member@example.test', now()),
  ('70000000-0000-4000-8000-000000000014', 'other-actor@example.test', now());
INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status) VALUES
  ('70000000-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000011', 'owner', 'active'),
  ('70000000-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000012', 'member', 'active'),
  ('70000000-0000-4000-8000-000000000001', '70000000-0000-4000-8000-000000000013', 'member', 'suspended'),
  ('70000000-0000-4000-8000-000000000002', '70000000-0000-4000-8000-000000000014', 'owner', 'active');
UPDATE public.profiles SET display_name = '  Owner Name  '
WHERE user_id = '70000000-0000-4000-8000-000000000011';
UPDATE public.profiles SET display_name = '   '
WHERE user_id = '70000000-0000-4000-8000-000000000012';

DO $$ BEGIN
  ASSERT NOT has_function_privilege('anon', 'public.workspace_actor_labels(uuid,uuid[])', 'EXECUTE');
  ASSERT NOT has_function_privilege('anon', 'private.workspace_actor_labels(uuid,uuid[])', 'EXECUTE');
  ASSERT NOT has_function_privilege('tover_app', 'public.workspace_actor_labels(uuid,uuid[])', 'EXECUTE');
  ASSERT has_function_privilege('authenticated', 'public.workspace_actor_labels(uuid,uuid[])', 'EXECUTE');
  ASSERT (SELECT prosecdef FROM pg_proc WHERE oid='private.workspace_actor_labels(uuid,uuid[])'::regprocedure);
  ASSERT NOT (SELECT prosecdef FROM pg_proc WHERE oid='public.workspace_actor_labels(uuid,uuid[])'::regprocedure);
  ASSERT (SELECT proconfig=ARRAY['search_path=""'] FROM pg_proc
    WHERE oid='private.workspace_actor_labels(uuid,uuid[])'::regprocedure);
END; $$;

SET ROLE authenticated;
SET request.jwt.claim.sub = '70000000-0000-4000-8000-000000000012';
DO $$ DECLARE labels jsonb; BEGIN
  labels := public.workspace_actor_labels('70000000-0000-4000-8000-000000000001', ARRAY[
    '70000000-0000-4000-8000-000000000011', '70000000-0000-4000-8000-000000000012',
    '70000000-0000-4000-8000-000000000013', '70000000-0000-4000-8000-000000000014',
    '70000000-0000-4000-8000-000000000099']::uuid[]);
  ASSERT labels='[{"userId":"70000000-0000-4000-8000-000000000011","label":"Owner Name"},
    {"userId":"70000000-0000-4000-8000-000000000012","label":"actor-member@example.test"},
    {"userId":"70000000-0000-4000-8000-000000000013","label":"former-member@example.test"}]'::jsonb,
    'labels leaked cross-workspace actors or lost inactive members/fallback names';
  ASSERT public.workspace_actor_labels('70000000-0000-4000-8000-000000000001', ARRAY[]::uuid[])='[]'::jsonb;
  BEGIN
    PERFORM public.workspace_team('70000000-0000-4000-8000-000000000001');
    RAISE EXCEPTION 'actor lookup widened member team administration';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    PERFORM public.workspace_actor_labels('70000000-0000-4000-8000-000000000002', ARRAY[]::uuid[]);
    RAISE EXCEPTION 'actor lookup accepted other workspace';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    PERFORM public.workspace_actor_labels('70000000-0000-4000-8000-000000000001', NULL);
    RAISE EXCEPTION 'actor lookup accepted null IDs';
  EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
  BEGIN
    PERFORM public.workspace_actor_labels('70000000-0000-4000-8000-000000000001',
      array_fill('70000000-0000-4000-8000-000000000011'::uuid, ARRAY[201]));
    RAISE EXCEPTION 'actor lookup exceeded its bound';
  EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
  BEGIN
    PERFORM public.workspace_actor_labels('70000000-0000-4000-8000-000000000001', ARRAY[NULL]::uuid[]);
    RAISE EXCEPTION 'actor lookup accepted null actor';
  EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
END; $$;

SET request.jwt.claim.sub = '70000000-0000-4000-8000-000000000013';
DO $$ BEGIN
  BEGIN
    PERFORM public.workspace_actor_labels('70000000-0000-4000-8000-000000000001', ARRAY[]::uuid[]);
    RAISE EXCEPTION 'suspended caller read actor labels';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END; $$;
RESET request.jwt.claim.sub;
DO $$ BEGIN
  BEGIN
    PERFORM public.workspace_actor_labels('70000000-0000-4000-8000-000000000001', ARRAY[]::uuid[]);
    RAISE EXCEPTION 'missing identity read actor labels';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END; $$;
RESET ROLE;

DELETE FROM auth.users WHERE id='70000000-0000-4000-8000-000000000013';
SET ROLE authenticated;
SET request.jwt.claim.sub = '70000000-0000-4000-8000-000000000012';
DO $$ BEGIN
  ASSERT public.workspace_actor_labels('70000000-0000-4000-8000-000000000001',
    ARRAY['70000000-0000-4000-8000-000000000013']::uuid[])='[]'::jsonb,
    'deleted identity returned a stale actor label';
END; $$;
RESET ROLE;
RESET request.jwt.claim.sub;
