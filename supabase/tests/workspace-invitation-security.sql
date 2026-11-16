-- Run only via scripts/test-auth-security.sh against its disposable cluster.
\set QUIET 1
\ir auth-stub.sql
\if :legacy
\ir ../migrations/001_initial_schema.sql
\ir ../migrations/002_auth_orgs_rbac.sql
\ir ../migrations/003_inventory_system.sql
\ir ../migrations/029_secure_workspace_invitations.sql
\ir ../migrations/032_team_invitation_recovery.sql
-- Simulate legacy Data API grants before the forward migration restricts them.
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO authenticated;
CREATE FUNCTION public.legacy_privilege_probe() RETURNS integer LANGUAGE sql SECURITY DEFINER AS $$ SELECT 1; $$;
GRANT EXECUTE ON FUNCTION public.legacy_privilege_probe() TO authenticated;
INSERT INTO auth.users (id,email,email_confirmed_at,raw_user_meta_data)
VALUES ('90000000-0000-0000-0000-000000000001','preserved@example.test',now(),
  '{"organization_name":"Preserved workspace"}');
\endif
\ir ../migrations/033_identity_and_sessions.sql
-- A second application must preserve data and authorization.
\ir ../migrations/033_identity_and_sessions.sql
\if :legacy
DO $$ BEGIN
  ASSERT EXISTS (SELECT FROM auth.users u JOIN public.profiles p ON p.user_id=u.id
    JOIN public.organization_memberships m ON m.user_id=u.id
    JOIN public.organizations o ON o.id=m.organization_id
    JOIN public.warehouses w ON w.workspace_id=o.id AND w.is_default_defect
    WHERE u.id='90000000-0000-0000-0000-000000000001' AND o.name='Preserved workspace' AND m.role_id='owner'),
    'upgrade did not preserve existing account, workspace, owner membership and inventory';
  ASSERT NOT has_function_privilege('tover_app','public.legacy_privilege_probe()','EXECUTE'),
    'session role inherited legacy SECURITY DEFINER access through PUBLIC';
  ASSERT has_function_privilege('authenticated','public.legacy_privilege_probe()','EXECUTE'),
    'forward hardening removed a legacy explicit role grant';
END; $$;
\else
DO $$ BEGIN
  ASSERT to_regclass('public.warehouses') IS NULL, 'fresh identity installed legacy inventory';
END; $$;
\endif

-- Bel onboarding migrations (keep in step with supabase/migrations), applied twice for idempotence.
\ir ../migrations/034_workspace_actor_labels.sql
\ir ../migrations/035_bel_workspace_creation.sql
\ir ../migrations/036_bel_shared_journal.sql
\ir ../migrations/037_profile_preferences.sql
\ir ../migrations/038_invitation_delivery.sql
\ir ../migrations/039_onboarding_foundation.sql
\ir ../migrations/040_identity_flows.sql
\ir ../migrations/042_invitation_lifecycle.sql
\ir ../migrations/043_onboarding_cleanup.sql
\ir ../migrations/034_workspace_actor_labels.sql
\ir ../migrations/035_bel_workspace_creation.sql
\ir ../migrations/036_bel_shared_journal.sql
\ir ../migrations/037_profile_preferences.sql
\ir ../migrations/038_invitation_delivery.sql
\ir ../migrations/039_onboarding_foundation.sql
\ir ../migrations/040_identity_flows.sql
\ir ../migrations/042_invitation_lifecycle.sql
\ir ../migrations/043_onboarding_cleanup.sql
\if :legacy
DO $$ BEGIN
  ASSERT EXISTS (SELECT FROM public.organization_memberships m JOIN public.organizations o ON o.id=m.organization_id
    WHERE m.user_id='90000000-0000-0000-0000-000000000001' AND o.name='Preserved workspace' AND m.role_id='owner'),
    'onboarding migrations did not preserve an existing workspace and owner membership';
END; $$;
\endif

-- Identity comes from Postgres, not token claims or PostgREST RPCs: the legacy entry points are gone.
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE (n.nspname, p.proname) IN (('public','custom_access_token_hook'), ('public','create_organization'),
      ('public','accept_my_organization_invites'), ('private','accept_workspace_invitations'),
      ('public','manage_workspace_invitation'), ('private','manage_workspace_invitation'),
      ('public','revoke_workspace_invitation_reservation'), ('private','revoke_workspace_invitation_reservation'),
      ('public','workspace_team'), ('private','workspace_team'), ('public','my_workspaces'), ('private','my_workspaces'),
      ('public','request_member_recovery'), ('private','request_member_recovery'),
      ('app_private','bel_recipient_membership'))),
    'a legacy identity RPC survived the cutover';
  ASSERT NOT has_function_privilege('authenticated', 'public.bootstrap_new_user()', 'EXECUTE');
END; $$;

-- A victim workspace exists; the attacker knows its UUID. Signup metadata names the profile only.
INSERT INTO public.organizations (id, name)
VALUES ('10000000-0000-0000-0000-000000000001', 'Target workspace');
INSERT INTO auth.users (id, email, email_confirmed_at, raw_user_meta_data) VALUES
  ('20000000-0000-0000-0000-000000000001', 'attacker@example.test', now(),
   '{"name":"Mallory","organization_id":"10000000-0000-0000-0000-000000000001","organization_role":"owner","organization_name":"My own workspace"}'),
  ('20000000-0000-0000-0000-000000000002', 'invitee@example.test', NULL, '{"email_verified":true}'),
  ('20000000-0000-0000-0000-000000000003', 'member@example.test', now(), '{}'),
  ('20000000-0000-0000-0000-000000000005', 'manager@example.test', now(), '{}');
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_memberships WHERE user_id = '20000000-0000-0000-0000-000000000001'),
    'signup metadata granted or created a membership';
  ASSERT NOT EXISTS (SELECT FROM public.organizations WHERE name = 'My own workspace'),
    'signup metadata created a workspace';
  ASSERT (SELECT display_name FROM public.profiles WHERE user_id = '20000000-0000-0000-0000-000000000001') = 'Mallory';
END; $$;
INSERT INTO public.organization_memberships (organization_id, user_id, role_id) VALUES
  ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000005', 'owner'),
  ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000003', 'member');
INSERT INTO public.organization_invites (id, organization_id, email, role_id, invited_by) VALUES
  ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Invitee@example.test', 'member',
   '20000000-0000-0000-0000-000000000005'),
  ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 'somebody@example.test', 'admin',
   '20000000-0000-0000-0000-000000000005');
-- Live sign-ins. The secret key travels only as tover.session_key, as the session authority sets it.
INSERT INTO app_private.sessions (id, session_id, user_id, data) VALUES
  ('key-attacker', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', '{}'),
  ('key-invitee',  '30000000-0000-0000-0000-000000000002', '20000000-0000-0000-0000-000000000002', '{}'),
  ('key-member',   '30000000-0000-0000-0000-000000000003', '20000000-0000-0000-0000-000000000003', '{}'),
  ('key-manager',  '30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005', '{}');

-- An outsider's own sign-in cannot join, read or change another workspace's invitations.
SET ROLE tover_app;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000001';
SET tover.session_key = 'key-attacker';
DO $$ DECLARE n integer; BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_invites), 'outsider read another workspace''s invitations';
  UPDATE public.organization_invites SET status = 'revoked';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'outsider changed another workspace''s invitations';
  BEGIN
    INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status)
    VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'owner', 'active');
    RAISE EXCEPTION 'direct unauthorized membership insert succeeded';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO public.organization_invites (organization_id, email, role_id, invited_by)
    VALUES ('10000000-0000-0000-0000-000000000001', 'attacker@example.test', 'owner', '20000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'outsider created their own invitation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT NOT EXISTS (SELECT FROM app_private.bel_recipient_access('10000000-0000-0000-0000-000000000001', 'member@example.test')),
    'recipient lookup answered a non-manager';
  ASSERT NOT EXISTS (SELECT FROM app_private.bel_workspace_team('10000000-0000-0000-0000-000000000001')),
    'team lookup answered a non-manager';
  ASSERT NOT EXISTS (SELECT FROM app_private.bel_received_invitation('40000000-0000-0000-0000-000000000001')),
    'another recipient''s invitation was readable';
END; $$;

-- Sign-in records belong to their own user and live session.
DO $$ DECLARE n integer; BEGIN
  BEGIN
    INSERT INTO app_private.bel_events (id, scope_kind, scope_id, actor_id, command_id, event_type, occurred_at, data)
    VALUES (gen_random_uuid(), 'session', '30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000005',
            'forged', 'forged', now(), '{}');
    RAISE EXCEPTION 'wrote a journal entry for another user''s session';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO app_private.bel_events (id, scope_kind, scope_id, actor_id, command_id, event_type, occurred_at, data)
    VALUES (gen_random_uuid(), 'session', '30000000-0000-0000-0000-000000000005', '20000000-0000-0000-0000-000000000001',
            'forged', 'forged', now(), '{}');
    RAISE EXCEPTION 'wrote a journal entry into a session that is not the current one';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  INSERT INTO app_private.bel_events (id, scope_kind, scope_id, actor_id, command_id, event_type, occurred_at, data)
  VALUES (gen_random_uuid(), 'session', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
          'own', 'own', now(), '{}');
  BEGIN
    INSERT INTO app_private.session_boundaries (user_id, valid_after)
    VALUES ('20000000-0000-0000-0000-000000000005', now());
    RAISE EXCEPTION 'ended another user''s sign-ins';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO app_private.password_resets (attempt_id, user_id, session_id, status, requested_at)
    VALUES (gen_random_uuid(), '20000000-0000-0000-0000-000000000005', '30000000-0000-0000-0000-000000000001', 'requested', now());
    RAISE EXCEPTION 'recorded a password reset for another user';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END; $$;
RESET ROLE;
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM app_private.session_boundaries), 'a forged reset boundary was stored';
END; $$;

-- A member who does not manage the workspace sees no team or invitations.
SET ROLE tover_app;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000003';
SET tover.session_key = 'key-member';
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_invites), 'member read the workspace invitations';
  ASSERT NOT EXISTS (SELECT FROM app_private.bel_workspace_team('10000000-0000-0000-0000-000000000001')),
    'member read private team details';
  BEGIN
    INSERT INTO public.organization_invites (organization_id, email, role_id, invited_by)
    VALUES ('10000000-0000-0000-0000-000000000001', 'other@example.test', 'member', '20000000-0000-0000-0000-000000000003');
    RAISE EXCEPTION 'member created an invitation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END; $$;
RESET ROLE;

-- A manager's live sign-in manages only its own workspace; without that sign-in nothing is visible.
SET ROLE tover_app;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000005';
SET tover.session_key = 'key-manager';
DO $$ DECLARE n integer; BEGIN
  ASSERT (SELECT count(*) FROM public.organization_invites) = 2;
  ASSERT (SELECT count(*) FROM app_private.bel_workspace_team('10000000-0000-0000-0000-000000000001')) = 2;
  ASSERT (SELECT status FROM app_private.bel_recipient_access('10000000-0000-0000-0000-000000000001', ' MEMBER@example.test ')) = 'active';
  BEGIN
    INSERT INTO public.organization_invites (organization_id, email, role_id, invited_by)
    VALUES ('10000000-0000-0000-0000-000000000001', 'new@example.test', 'member', '20000000-0000-0000-0000-000000000003');
    RAISE EXCEPTION 'manager issued an invitation in another user''s name';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO public.organization_invites (organization_id, email, role_id, invited_by)
    VALUES ('10000000-0000-0000-0000-000000000099', 'new@example.test', 'member', '20000000-0000-0000-0000-000000000005');
    RAISE EXCEPTION 'manager issued an invitation for another workspace';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE public.organization_memberships SET role_id = 'owner' WHERE user_id = '20000000-0000-0000-0000-000000000003';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'manager changed a membership directly';
END; $$;
RESET tover.session_key;
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_invites), 'manager rights held without a live sign-in';
END; $$;
RESET ROLE;
UPDATE app_private.sessions SET ended_at = now() WHERE id = 'key-manager';
SET ROLE tover_app;
SET tover.session_key = 'key-manager';
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_invites), 'an ended sign-in kept manager rights';
END; $$;
RESET ROLE;
UPDATE app_private.sessions SET ended_at = NULL WHERE id = 'key-manager';

-- The recipient reads and accepts only offers to a verified email, with exactly the offered role.
SET ROLE tover_app;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000002';
SET tover.session_key = 'key-invitee';
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_invites), 'unverified email read an invitation';
  ASSERT NOT EXISTS (SELECT FROM app_private.bel_received_invitation('40000000-0000-0000-0000-000000000001'));
END; $$;
RESET ROLE;
UPDATE auth.users SET email_confirmed_at = now() WHERE id = '20000000-0000-0000-0000-000000000002';
SET ROLE tover_app;
DO $$ DECLARE n integer; BEGIN
  ASSERT (SELECT count(*) FROM public.organization_invites) = 1, 'recipient read offers to another email';
  ASSERT (SELECT role_id FROM app_private.bel_received_invitation('40000000-0000-0000-0000-000000000001')) = 'member';
  BEGIN
    INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status)
    VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'member', 'active');
    RAISE EXCEPTION 'joined without accepting the offer in the same transaction';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE public.organization_invites SET status = 'accepted' WHERE id = '40000000-0000-0000-0000-000000000002';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'recipient accepted an offer to another email';
  UPDATE public.organization_invites SET status = 'accepted' WHERE id = '40000000-0000-0000-0000-000000000001';
  BEGIN
    INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status)
    VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'admin', 'active');
    RAISE EXCEPTION 'acceptance granted a role that was not offered';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status)
  VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000002', 'member', 'active');
END; $$;
DO $$ DECLARE n integer; BEGIN
  UPDATE public.organization_invites SET status = 'accepted' WHERE id = '40000000-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'an accepted offer was accepted again';
END; $$;
RESET ROLE;
DO $$ BEGIN
  ASSERT (SELECT role_id FROM public.organization_memberships
    WHERE user_id = '20000000-0000-0000-0000-000000000002' AND organization_id = '10000000-0000-0000-0000-000000000001'
      AND status = 'active') = 'member', 'role was not taken from the invitation';
END; $$;

-- Revoked offers grant nothing.
INSERT INTO auth.users (id, email, email_confirmed_at) VALUES
  ('20000000-0000-0000-0000-000000000006', 'revoked@example.test', now());
INSERT INTO public.organization_invites (id, organization_id, email, role_id, status) VALUES
  ('40000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 'revoked@example.test', 'admin', 'revoked');
INSERT INTO app_private.sessions (id, session_id, user_id, data) VALUES
  ('key-revoked', '30000000-0000-0000-0000-000000000006', '20000000-0000-0000-0000-000000000006', '{}');
SET ROLE tover_app;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000006';
SET tover.session_key = 'key-revoked';
DO $$ DECLARE n integer; BEGIN
  UPDATE public.organization_invites SET status = 'accepted' WHERE id = '40000000-0000-0000-0000-000000000003';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a revoked offer was accepted';
  BEGIN
    INSERT INTO public.organization_memberships (organization_id, user_id, role_id, status)
    VALUES ('10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000006', 'admin', 'active');
    RAISE EXCEPTION 'a revoked offer granted membership';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END; $$;
RESET ROLE;
RESET request.jwt.claim.sub;
RESET tover.session_key;
\echo 'PASS: Bel invitation policies: outsider, member, manager and recipient scopes, verified email, offered role, revocation'

DO $$ BEGIN
  ASSERT NOT has_schema_privilege('authenticated','app_private','USAGE');
  ASSERT NOT has_schema_privilege('anon','app_private','USAGE');
  ASSERT NOT has_schema_privilege('service_role','app_private','USAGE');
  ASSERT NOT has_table_privilege('authenticated','app_private.sessions','SELECT');
  ASSERT NOT has_table_privilege('authenticated','app_private.session_boundaries','SELECT');
  ASSERT NOT has_table_privilege('tover_app','auth.users','SELECT');
  ASSERT NOT has_table_privilege('tover_app','public.organization_memberships','DELETE');
  ASSERT NOT has_table_privilege('tover_app','public.organization_invites','DELETE');
  ASSERT NOT has_table_privilege('tover_app','app_private.bel_events','UPDATE');
  ASSERT NOT has_function_privilege('anon','app_private.bel_received_invitation(uuid)','EXECUTE');
  ASSERT NOT has_function_privilege('authenticated','app_private.bel_workspace_team(uuid)','EXECUTE');
  ASSERT NOT EXISTS (SELECT FROM pg_roles WHERE rolname='tover_app'
    AND (rolsuper OR rolbypassrls OR rolinherit OR rolcreaterole OR rolcreatedb));
END; $$;
\echo 'PASS: portable identity contract and restricted session role'
