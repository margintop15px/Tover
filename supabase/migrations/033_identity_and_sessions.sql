-- Tover identity contract. Fresh Auth database OR identity schema through 032.
-- Run as the migration owner. No account, legacy inventory or session is deleted.
BEGIN;

DO $guard$
DECLARE n integer; t text; expected text[]; actual text[];
BEGIN
  IF to_regclass('auth.users') IS NULL OR to_regprocedure('auth.uid()') IS NULL
    OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated')
    OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon')
    OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'service_role') THEN
    RAISE EXCEPTION '033 requires initialized Supabase Auth, auth.uid(), anon and authenticated';
  END IF;
  SELECT count(*) INTO n FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
  WHERE s.nspname = 'public' AND c.relkind = 'r' AND c.relname = ANY
    (ARRAY['profiles','roles','organizations','organization_memberships','organization_invites']);
  IF n NOT IN (0, 5) THEN RAISE EXCEPTION '033 rejects a partial identity schema'; END IF;
  IF n = 0 AND (to_regclass('public.warehouses') IS NOT NULL
    OR to_regclass('public.orders') IS NOT NULL) THEN
    RAISE EXCEPTION '033 rejects legacy inventory without its identity schema';
  END IF;
  IF n = 0 AND to_regclass('app_private.sessions') IS NOT NULL THEN
    RAISE EXCEPTION '033 rejects sessions without its identity schema';
  END IF;
  IF to_regclass('app_private.sessions') IS NOT NULL THEN
    SELECT array_agg(a.attname || ':' || format_type(a.atttypid,a.atttypmod) || ':' || a.attnotnull ORDER BY a.attnum)
    INTO actual FROM pg_attribute a WHERE a.attrelid='app_private.sessions'::regclass
      AND a.attnum>0 AND NOT a.attisdropped;
    IF actual IS DISTINCT FROM ARRAY['id:text:true','data:text:true','tokens:text:false',
      'user_id:uuid:false','workspace_id:uuid:false','version:uuid:true',
      'expires_at:timestamp with time zone:true','updated_at:timestamp with time zone:true']
      OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid='app_private.sessions'::regclass
        AND contype='p' AND pg_get_constraintdef(oid)='PRIMARY KEY (id)') THEN
      RAISE EXCEPTION '033 rejects an incompatible private session table';
    END IF;
  END IF;
  IF n = 5 THEN
    FOR t, expected IN SELECT * FROM (VALUES
      ('profiles', ARRAY['user_id:uuid:true','display_name:text:false','created_at:timestamp with time zone:true','updated_at:timestamp with time zone:true']),
      ('roles', ARRAY['id:text:true','description:text:true','created_at:timestamp with time zone:true']),
      ('organizations', ARRAY['id:uuid:true','name:text:true','created_by:uuid:false','created_at:timestamp with time zone:true','updated_at:timestamp with time zone:true']),
      ('organization_memberships', ARRAY['id:uuid:true','organization_id:uuid:true','user_id:uuid:true','role_id:text:true','status:text:true','created_at:timestamp with time zone:true','updated_at:timestamp with time zone:true','recovery_requested_at:timestamp with time zone:false']),
      ('organization_invites', ARRAY['id:uuid:true','organization_id:uuid:true','email:text:true','role_id:text:true','invited_by:uuid:false','status:text:true','expires_at:timestamp with time zone:true','created_at:timestamp with time zone:true','updated_at:timestamp with time zone:true'])
    ) AS spec(table_name, columns)
    LOOP
      SELECT array_agg(a.attname || ':' || format_type(a.atttypid,a.atttypmod) || ':' || a.attnotnull ORDER BY a.attnum)
      INTO actual FROM pg_attribute a WHERE a.attrelid = ('public.' || t)::regclass
        AND a.attnum > 0 AND NOT a.attisdropped;
      IF actual IS DISTINCT FROM expected THEN
        RAISE EXCEPTION '033 rejects incompatible identity table %', t;
      END IF;
    END LOOP;
    IF to_regprocedure('private.workspace_team(uuid)') IS NULL
      OR to_regprocedure('private.manage_workspace_invitation(uuid,text,uuid,text,text)') IS NULL
      OR to_regprocedure('private.request_member_recovery(uuid,uuid)') IS NULL
      OR to_regprocedure('private.accept_workspace_invitations()') IS NULL THEN
      RAISE EXCEPTION '033 requires the complete 032 identity contract';
    END IF;
    IF (SELECT count(*) FROM pg_constraint WHERE connamespace = 'public'::regnamespace
      AND conrelid = ANY(ARRAY['public.profiles'::regclass,'public.roles'::regclass,
        'public.organizations'::regclass,'public.organization_memberships'::regclass,
        'public.organization_invites'::regclass]) AND contype = 'p') <> 5
      OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'public.organization_memberships'::regclass
        AND contype = 'u' AND pg_get_constraintdef(oid) = 'UNIQUE (organization_id, user_id)') THEN
      RAISE EXCEPTION '033 requires identity primary and membership uniqueness constraints';
    END IF;
    IF (SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace
      AND conrelid=ANY(ARRAY['public.profiles'::regclass,'public.organizations'::regclass,
        'public.organization_memberships'::regclass,'public.organization_invites'::regclass]) AND contype='f') <> 8
      OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid='public.organization_memberships'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) =
          'CHECK ((status = ANY (ARRAY[''active''::text, ''invited''::text, ''suspended''::text])))')
      OR NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid='public.organization_invites'::regclass
        AND contype='c' AND pg_get_constraintdef(oid) =
          'CHECK ((status = ANY (ARRAY[''pending''::text, ''accepted''::text, ''expired''::text, ''revoked''::text])))') THEN
      RAISE EXCEPTION '033 requires identity foreign keys and status constraints';
    END IF;
  END IF;
END;
$guard$;

create table if not exists public.profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  display_name text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.roles (
  id text primary key,
  description text not null,
  created_at timestamptz not null default now()
);

insert into public.roles (id, description)
values
  ('owner', 'Full access to organization and billing-level controls'),
  ('admin', 'Can manage members and operational data'),
  ('member', 'Can view data and use standard features')
on conflict (id) do nothing;

create table if not exists public.organizations (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  created_by uuid references auth.users(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.organization_memberships (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  role_id text not null references public.roles(id),
  status text not null default 'active' check (status in ('active', 'invited', 'suspended')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, user_id)
);

create index if not exists idx_org_memberships_user
  on public.organization_memberships (user_id, status);

create index if not exists idx_org_memberships_org
  on public.organization_memberships (organization_id, status);

create table if not exists public.organization_invites (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  email text not null,
  role_id text not null references public.roles(id),
  invited_by uuid references auth.users(id) on delete set null,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'expired', 'revoked')),
  expires_at timestamptz not null default (now() + interval '7 days'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_org_invites_lookup
  on public.organization_invites (organization_id, lower(email), status);

create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists set_profiles_updated_at on public.profiles;
create trigger set_profiles_updated_at
before update on public.profiles
for each row
execute function public.set_updated_at();

drop trigger if exists set_organizations_updated_at on public.organizations;
create trigger set_organizations_updated_at
before update on public.organizations
for each row
execute function public.set_updated_at();

drop trigger if exists set_org_memberships_updated_at on public.organization_memberships;
create trigger set_org_memberships_updated_at
before update on public.organization_memberships
for each row
execute function public.set_updated_at();

drop trigger if exists set_org_invites_updated_at on public.organization_invites;
create trigger set_org_invites_updated_at
before update on public.organization_invites
for each row
execute function public.set_updated_at();

create or replace function public.app_is_org_member(p_organization_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.organization_memberships m
    where m.organization_id = p_organization_id
      and m.user_id = auth.uid()
      and m.status = 'active'
  );
$$;

create or replace function public.app_has_org_role(
  p_organization_id uuid,
  p_roles text[]
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.organization_memberships m
    where m.organization_id = p_organization_id
      and m.user_id = auth.uid()
      and m.status = 'active'
      and m.role_id = any(p_roles)
  );
$$;

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

  END IF;

  -- Deliberately ignore organization_id and organization_role in user metadata.
  RETURN new;
END;
$$;

REVOKE EXECUTE ON FUNCTION public.bootstrap_new_user() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created AFTER INSERT ON auth.users
FOR EACH ROW EXECUTE FUNCTION public.bootstrap_new_user();

create or replace function public.create_organization(p_name text)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_org_id uuid;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated' USING ERRCODE = '42501';
  end if;

  if p_name is null or char_length(trim(p_name)) < 2 then
    raise exception 'Organization name must be at least 2 characters';
  end if;

  insert into public.organizations (name, created_by)
  values (trim(p_name), auth.uid())
  returning id into v_org_id;

  insert into public.organization_memberships (
    organization_id,
    user_id,
    role_id,
    status
  )
  values (v_org_id, auth.uid(), 'owner', 'active')
  on conflict (organization_id, user_id) do nothing;

  return v_org_id;
end;
$$;

grant execute on function public.create_organization(text) to authenticated;

alter table public.profiles enable row level security;
alter table public.roles enable row level security;
alter table public.organizations enable row level security;
alter table public.organization_memberships enable row level security;
alter table public.organization_invites enable row level security;

drop policy if exists "profiles_select_own" on public.profiles;
create policy "profiles_select_own"
on public.profiles
for select
to authenticated
using (auth.uid() = user_id);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own"
on public.profiles
for insert
to authenticated
with check (auth.uid() = user_id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own"
on public.profiles
for update
to authenticated
using (auth.uid() = user_id)
with check (auth.uid() = user_id);

drop policy if exists "roles_select_all" on public.roles;
create policy "roles_select_all"
on public.roles
for select
to authenticated
using (true);

drop policy if exists "organizations_select_member" on public.organizations;
create policy "organizations_select_member"
on public.organizations
for select
to authenticated
using (public.app_is_org_member(id));

drop policy if exists "organizations_insert_self" on public.organizations;
create policy "organizations_insert_self"
on public.organizations
for insert
to authenticated
with check (auth.uid() = created_by);

drop policy if exists "organizations_update_admin" on public.organizations;
create policy "organizations_update_admin"
on public.organizations
for update
to authenticated
using (public.app_has_org_role(id, array['owner', 'admin']))
with check (public.app_has_org_role(id, array['owner', 'admin']));

drop policy if exists "organizations_delete_owner" on public.organizations;
create policy "organizations_delete_owner"
on public.organizations
for delete
to authenticated
using (public.app_has_org_role(id, array['owner']));

drop policy if exists "memberships_select_self_or_admin" on public.organization_memberships;
create policy "memberships_select_self_or_admin"
on public.organization_memberships
for select
to authenticated
using (
  auth.uid() = user_id
  or public.app_has_org_role(organization_id, array['owner', 'admin'])
);

drop policy if exists "memberships_insert_admin" on public.organization_memberships;
create policy "memberships_insert_admin"
on public.organization_memberships
for insert
to authenticated
with check (public.app_has_org_role(organization_id, array['owner', 'admin']));

drop policy if exists "memberships_update_admin" on public.organization_memberships;
create policy "memberships_update_admin"
on public.organization_memberships
for update
to authenticated
using (public.app_has_org_role(organization_id, array['owner', 'admin']))
with check (public.app_has_org_role(organization_id, array['owner', 'admin']));

drop policy if exists "memberships_delete_admin" on public.organization_memberships;
create policy "memberships_delete_admin"
on public.organization_memberships
for delete
to authenticated
using (public.app_has_org_role(organization_id, array['owner', 'admin']));

drop policy if exists "invites_select_admin" on public.organization_invites;
create policy "invites_select_admin"
on public.organization_invites
for select
to authenticated
using (public.app_has_org_role(organization_id, array['owner', 'admin']));

drop policy if exists "invites_insert_admin" on public.organization_invites;
create policy "invites_insert_admin"
on public.organization_invites
for insert
to authenticated
with check (public.app_has_org_role(organization_id, array['owner', 'admin']));

drop policy if exists "invites_update_admin" on public.organization_invites;
create policy "invites_update_admin"
on public.organization_invites
for update
to authenticated
using (public.app_has_org_role(organization_id, array['owner', 'admin']))
with check (public.app_has_org_role(organization_id, array['owner', 'admin']));

drop policy if exists "invites_delete_admin" on public.organization_invites;
create policy "invites_delete_admin"
on public.organization_invites
for delete
to authenticated
using (public.app_has_org_role(organization_id, array['owner', 'admin']));

-- No existing accounts, invitations or memberships are removed or reclassified.
ALTER TABLE public.organization_memberships
  ADD COLUMN IF NOT EXISTS recovery_requested_at timestamptz;

CREATE SCHEMA IF NOT EXISTS private;
REVOKE ALL ON SCHEMA private FROM PUBLIC;
GRANT USAGE ON SCHEMA private TO authenticated;

-- Auth details are exposed only for members of a workspace managed by the caller.
CREATE OR REPLACE FUNCTION private.workspace_team(p_workspace_id uuid)
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
CREATE OR REPLACE FUNCTION private.manage_workspace_invitation(
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

CREATE OR REPLACE FUNCTION private.request_member_recovery(p_workspace_id uuid, p_user_id uuid)
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

CREATE OR REPLACE FUNCTION private.accept_workspace_invitations()
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
CREATE OR REPLACE FUNCTION public.workspace_team(p_workspace_id uuid)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.workspace_team(p_workspace_id);
$$;
CREATE OR REPLACE FUNCTION public.manage_workspace_invitation(
  p_workspace_id uuid, p_action text, p_invite_id uuid DEFAULT NULL,
  p_email text DEFAULT NULL, p_role text DEFAULT 'member'
)
RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.manage_workspace_invitation(p_workspace_id, p_action, p_invite_id, p_email, p_role);
$$;
CREATE OR REPLACE FUNCTION public.request_member_recovery(p_workspace_id uuid, p_user_id uuid)
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

-- Workspace selection reads current active memberships, never JWT role metadata.
CREATE OR REPLACE FUNCTION private.my_workspaces()
RETURNS TABLE(organization_id uuid, name text, role_id text, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF auth.uid() IS NULL THEN RAISE EXCEPTION 'Not authenticated' USING ERRCODE = '42501'; END IF;
  RETURN QUERY SELECT o.id,o.name,m.role_id,m.created_at
  FROM public.organization_memberships m JOIN public.organizations o ON o.id=m.organization_id
  WHERE m.user_id=auth.uid() AND m.status='active' ORDER BY m.created_at,o.id;
END;
$$;
CREATE OR REPLACE FUNCTION public.my_workspaces()
RETURNS TABLE(organization_id uuid, name text, role_id text, created_at timestamptz)
LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$ SELECT * FROM private.my_workspaces(); $$;
REVOKE ALL ON FUNCTION private.my_workspaces(), public.my_workspaces() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.my_workspaces(), public.my_workspaces() TO authenticated;

-- Delivery failure cancels only its reservation, never other pending invites.
CREATE OR REPLACE FUNCTION private.revoke_workspace_invitation_reservation(
  p_workspace_id uuid, p_invite_id uuid
) RETURNS jsonb LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_email text;
BEGIN
  IF auth.uid() IS NULL OR NOT public.app_has_org_role(p_workspace_id, ARRAY['owner','admin']) THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  SELECT lower(btrim(email)) INTO v_email FROM public.organization_invites
  WHERE id = p_invite_id AND organization_id = p_workspace_id;
  IF NOT FOUND THEN RETURN jsonb_build_object('code','NOT_FOUND'); END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended(p_workspace_id::text || ':' || v_email, 0));
  UPDATE public.organization_invites SET status = 'revoked'
  WHERE id = p_invite_id AND organization_id = p_workspace_id AND status = 'pending';
  RETURN jsonb_build_object('ok',true);
END;
$$;
CREATE OR REPLACE FUNCTION public.revoke_workspace_invitation_reservation(
  p_workspace_id uuid, p_invite_id uuid
) RETURNS jsonb LANGUAGE sql SECURITY INVOKER SET search_path = '' AS $$
  SELECT private.revoke_workspace_invitation_reservation(p_workspace_id,p_invite_id);
$$;
REVOKE ALL ON FUNCTION private.revoke_workspace_invitation_reservation(uuid,uuid),
  public.revoke_workspace_invitation_reservation(uuid,uuid) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION private.revoke_workspace_invitation_reservation(uuid,uuid),
  public.revoke_workspace_invitation_reservation(uuid,uuid) TO authenticated;

-- RLS is retained, but manager writes must use the authorized RPCs above.
REVOKE ALL ON public.profiles, public.roles, public.organizations,
  public.organization_memberships, public.organization_invites FROM PUBLIC, anon, authenticated;
GRANT USAGE ON SCHEMA public TO authenticated;
GRANT SELECT ON public.profiles, public.roles, public.organizations,
  public.organization_memberships, public.organization_invites TO authenticated;
REVOKE ALL ON FUNCTION public.create_organization(text), public.app_is_org_member(uuid),
  public.app_has_org_role(uuid,text[]), public.set_updated_at() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.create_organization(text), public.app_is_org_member(uuid),
  public.app_has_org_role(uuid,text[]) TO authenticated;

CREATE SCHEMA IF NOT EXISTS app_private;
REVOKE ALL ON SCHEMA app_private FROM PUBLIC, anon, authenticated, service_role;
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tover_app') THEN
    CREATE ROLE tover_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'tover_app' AND
    (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls OR NOT rolcanlogin))
    OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = 'tover_app') THEN
    RAISE EXCEPTION 'tover_app must be a restricted login role without inherited roles';
  END IF;
END; $$;
CREATE TABLE IF NOT EXISTS app_private.sessions (
  id text PRIMARY KEY,
  data text NOT NULL DEFAULT '{}',
  tokens text,
  user_id uuid,
  workspace_id uuid,
  version uuid NOT NULL DEFAULT gen_random_uuid(),
  expires_at timestamptz NOT NULL DEFAULT now() + interval '30 days',
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sessions_expires_at_idx ON app_private.sessions(expires_at);
ALTER TABLE app_private.sessions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS sessions_runtime ON app_private.sessions;
CREATE POLICY sessions_runtime ON app_private.sessions FOR ALL TO tover_app USING (true) WITH CHECK (true);
REVOKE ALL ON app_private.sessions FROM PUBLIC, anon, authenticated, service_role;
GRANT USAGE ON SCHEMA app_private TO tover_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON app_private.sessions TO tover_app;

-- NOINHERIT does not remove PostgreSQL PUBLIC privileges. Keep explicit
-- authenticated/service grants, but prevent the session role from invoking
-- retained legacy SECURITY DEFINER functions by way of PUBLIC.
DO $$ DECLARE f regprocedure; BEGIN
  FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname='public' AND p.prosecdef
  LOOP
    EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC',f);
  END LOOP;
END; $$;

NOTIFY pgrst, 'reload schema';
COMMIT;
