-- Run only via scripts/test-auth-security.sh, which creates a disposable cluster.
\set QUIET 1
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role BYPASSRLS;
CREATE SCHEMA auth;
CREATE TABLE auth.users (
  id uuid PRIMARY KEY,
  email text,
  email_confirmed_at timestamptz,
  raw_user_meta_data jsonb DEFAULT '{}'::jsonb
);
CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;
CREATE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT current_setting('request.jwt.claim.role', true);
$$;
GRANT USAGE ON SCHEMA auth TO authenticated, anon;

\ir ../migrations/001_initial_schema.sql
\ir ../migrations/002_auth_orgs_rbac.sql
\ir ../migrations/003_inventory_system.sql
\ir ../migrations/029_secure_workspace_invitations.sql

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO authenticated;

-- A victim workspace exists; the attacker knows its UUID.
INSERT INTO public.organizations (id, name)
VALUES ('10000000-0000-0000-0000-000000000001', 'Target workspace');
INSERT INTO auth.users (id, email, email_confirmed_at, raw_user_meta_data)
VALUES ('20000000-0000-0000-0000-000000000001', 'attacker@example.test', now(),
  '{"organization_id":"10000000-0000-0000-0000-000000000001","organization_role":"owner","organization_name":"My own workspace"}');
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_memberships
    WHERE organization_id = '10000000-0000-0000-0000-000000000001'),
    'forged signup metadata granted membership';
  ASSERT EXISTS (SELECT FROM public.organizations o
    JOIN public.organization_memberships m ON m.organization_id = o.id
    JOIN public.warehouses w ON w.workspace_id = o.id AND w.is_default_defect
    WHERE o.name = 'My own workspace' AND m.role_id = 'owner'
      AND m.user_id = '20000000-0000-0000-0000-000000000001'),
    'legitimate self-service workspace creation broke';
END;
$$;

-- Direct table writes and the acceptance RPC cannot bypass invitations.
SET ROLE authenticated;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000001';
DO $$
BEGIN
  ASSERT public.accept_my_organization_invites() = 0;
  BEGIN
    INSERT INTO public.organization_memberships (organization_id, user_id, role_id)
    VALUES ('10000000-0000-0000-0000-000000000001', auth.uid(), 'owner');
    RAISE EXCEPTION 'direct unauthorized membership insert succeeded';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    INSERT INTO public.organization_invites (organization_id, email, role_id)
    VALUES ('10000000-0000-0000-0000-000000000001', 'attacker@example.test', 'owner');
    RAISE EXCEPTION 'attacker created their own invitation';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END;
$$;
RESET ROLE;

-- A genuine member invitation is not accepted until Auth verifies the email.
INSERT INTO public.organization_invites (organization_id, email, role_id)
VALUES ('10000000-0000-0000-0000-000000000001', 'Invitee@example.test', 'member');
INSERT INTO auth.users (id, email, raw_user_meta_data)
VALUES ('20000000-0000-0000-0000-000000000002', 'invitee@example.test',
  '{"organization_id":"10000000-0000-0000-0000-000000000001","organization_role":"owner","email_verified":true}');
SET ROLE authenticated;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000002';
DO $$ BEGIN
  ASSERT public.accept_my_organization_invites() = 0,
    'unverified email accepted an invitation';
END; $$;
RESET ROLE;
DO $$ BEGIN
  ASSERT NOT EXISTS (SELECT FROM public.organization_memberships
    WHERE user_id = '20000000-0000-0000-0000-000000000002');
  ASSERT EXISTS (SELECT FROM public.organization_invites
    WHERE email = 'Invitee@example.test' AND status = 'pending');
END; $$;

UPDATE auth.users SET email_confirmed_at = now()
WHERE id = '20000000-0000-0000-0000-000000000002';
SET ROLE authenticated;
DO $$ BEGIN
  ASSERT public.accept_my_organization_invites() = 1;
  ASSERT public.accept_my_organization_invites() = 0, 'acceptance is not idempotent';
END; $$;
RESET ROLE;
DO $$ BEGIN
  ASSERT EXISTS (SELECT FROM public.organization_memberships
    WHERE user_id = '20000000-0000-0000-0000-000000000002'
      AND organization_id = '10000000-0000-0000-0000-000000000001'
      AND role_id = 'member' AND status = 'active'),
    'role was not taken from the invitation';
END; $$;

-- Revoked, expired and wrong-email invitations must not grant membership.
INSERT INTO public.organization_invites (organization_id, email, role_id, status, expires_at)
VALUES
  ('10000000-0000-0000-0000-000000000001', 'attacker@example.test', 'admin', 'revoked', now() + interval '1 day'),
  ('10000000-0000-0000-0000-000000000001', 'attacker@example.test', 'admin', 'pending', now() - interval '1 day'),
  ('10000000-0000-0000-0000-000000000001', 'somebody-else@example.test', 'admin', 'pending', now() + interval '1 day');
SET ROLE authenticated;
SET request.jwt.claim.sub = '20000000-0000-0000-0000-000000000001';
DO $$ BEGIN
  ASSERT public.accept_my_organization_invites() = 0;
END; $$;
RESET ROLE;

-- Resends use the latest role, consume exactly the eligible records and do not
-- let a stale invitation change the role on a later request.
INSERT INTO public.organization_invites (organization_id, email, role_id, created_at)
VALUES
  ('10000000-0000-0000-0000-000000000001', 'attacker@example.test', 'admin', now() - interval '1 hour'),
  ('10000000-0000-0000-0000-000000000001', 'attacker@example.test', 'member', now());
SET ROLE authenticated;
DO $$ BEGIN
  ASSERT public.accept_my_organization_invites() = 2;
  ASSERT public.accept_my_organization_invites() = 0;
END; $$;
RESET ROLE;
DO $$ BEGIN
  ASSERT EXISTS (SELECT FROM public.organization_memberships
    WHERE user_id = '20000000-0000-0000-0000-000000000001'
      AND organization_id = '10000000-0000-0000-0000-000000000001'
      AND role_id = 'member');
  ASSERT NOT has_function_privilege('anon', 'public.accept_my_organization_invites()', 'EXECUTE');
  ASSERT NOT has_function_privilege('authenticated', 'public.bootstrap_new_user()', 'EXECUTE');
END; $$;
\echo 'PASS: forged metadata, RLS, verified-email acceptance, role authority, expiry, revocation, resends, self-signup and function privileges'
