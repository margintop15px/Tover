create extension if not exists "pgcrypto";

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
on conflict (id) do update
set description = excluded.description;

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
set search_path = public
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
set search_path = public
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

create or replace function public.bootstrap_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_display_name text;
  v_org_name text;
  v_org_id uuid;
  v_invited_org_id uuid;
  v_invited_role text;
begin
  v_display_name := nullif(trim(new.raw_user_meta_data ->> 'name'), '');

  insert into public.profiles (user_id, display_name)
  values (new.id, v_display_name)
  on conflict (user_id) do update
  set
    display_name = coalesce(excluded.display_name, public.profiles.display_name),
    updated_at = now();

  v_org_name := nullif(trim(new.raw_user_meta_data ->> 'organization_name'), '');

  if v_org_name is not null then
    insert into public.organizations (name, created_by)
    values (v_org_name, new.id)
    returning id into v_org_id;

    insert into public.organization_memberships (
      organization_id,
      user_id,
      role_id,
      status
    )
    values (v_org_id, new.id, 'owner', 'active')
    on conflict (organization_id, user_id) do nothing;
  end if;

  begin
    v_invited_org_id := nullif(trim(new.raw_user_meta_data ->> 'organization_id'), '')::uuid;
  exception
    when invalid_text_representation then
      v_invited_org_id := null;
  end;

  v_invited_role := coalesce(nullif(trim(new.raw_user_meta_data ->> 'organization_role'), ''), 'member');

  if v_invited_org_id is not null then
    if not exists (select 1 from public.roles where id = v_invited_role) then
      v_invited_role := 'member';
    end if;

    insert into public.organization_memberships (
      organization_id,
      user_id,
      role_id,
      status
    )
    values (v_invited_org_id, new.id, v_invited_role, 'active')
    on conflict (organization_id, user_id) do update
    set
      role_id = excluded.role_id,
      status = 'active',
      updated_at = now();

    update public.organization_invites
    set status = 'accepted', updated_at = now()
    where organization_id = v_invited_org_id
      and lower(email) = lower(new.email)
      and status = 'pending';
  end if;

  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row
execute function public.bootstrap_new_user();

create or replace function public.create_organization(p_name text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_org_id uuid;
begin
  if auth.uid() is null then
    raise exception 'Not authenticated';
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

create or replace function public.accept_my_organization_invites()
returns integer
language plpgsql
security definer
set search_path = public, auth
as $$
declare
  v_email text;
  v_user_id uuid;
  v_count integer;
begin
  v_user_id := auth.uid();

  if v_user_id is null then
    raise exception 'Not authenticated';
  end if;

  select lower(u.email)
  into v_email
  from auth.users u
  where u.id = v_user_id;

  if v_email is null then
    return 0;
  end if;

  insert into public.organization_memberships (
    organization_id,
    user_id,
    role_id,
    status
  )
  select
    i.organization_id,
    v_user_id,
    i.role_id,
    'active'
  from public.organization_invites i
  where lower(i.email) = v_email
    and i.status = 'pending'
    and i.expires_at > now()
  on conflict (organization_id, user_id) do update
  set
    role_id = excluded.role_id,
    status = 'active',
    updated_at = now();

  update public.organization_invites
  set status = 'accepted', updated_at = now()
  where lower(email) = v_email
    and status = 'pending'
    and expires_at > now();

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

grant execute on function public.accept_my_organization_invites() to authenticated;

alter table public.profiles enable row level security;
alter table public.roles enable row level security;
alter table public.organizations enable row level security;
alter table public.organization_memberships enable row level security;
alter table public.organization_invites enable row level security;

alter table public.orders enable row level security;
alter table public.order_lines enable row level security;
alter table public.inventory_snapshots enable row level security;
alter table public.payments enable row level security;
alter table public.imports enable row level security;
alter table public.import_errors enable row level security;

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

drop policy if exists "orders_select_member" on public.orders;
create policy "orders_select_member"
on public.orders
for select
to authenticated
using (public.app_is_org_member(workspace_id));

drop policy if exists "orders_write_admin" on public.orders;
create policy "orders_write_admin"
on public.orders
for all
to authenticated
using (public.app_has_org_role(workspace_id, array['owner', 'admin']))
with check (public.app_has_org_role(workspace_id, array['owner', 'admin']));

drop policy if exists "order_lines_select_member" on public.order_lines;
create policy "order_lines_select_member"
on public.order_lines
for select
to authenticated
using (
  exists (
    select 1
    from public.orders o
    where o.id = order_id
      and public.app_is_org_member(o.workspace_id)
  )
);

drop policy if exists "order_lines_write_admin" on public.order_lines;
create policy "order_lines_write_admin"
on public.order_lines
for all
to authenticated
using (
  exists (
    select 1
    from public.orders o
    where o.id = order_id
      and public.app_has_org_role(o.workspace_id, array['owner', 'admin'])
  )
)
with check (
  exists (
    select 1
    from public.orders o
    where o.id = order_id
      and public.app_has_org_role(o.workspace_id, array['owner', 'admin'])
  )
);

drop policy if exists "inventory_select_member" on public.inventory_snapshots;
create policy "inventory_select_member"
on public.inventory_snapshots
for select
to authenticated
using (public.app_is_org_member(workspace_id));

drop policy if exists "inventory_write_admin" on public.inventory_snapshots;
create policy "inventory_write_admin"
on public.inventory_snapshots
for all
to authenticated
using (public.app_has_org_role(workspace_id, array['owner', 'admin']))
with check (public.app_has_org_role(workspace_id, array['owner', 'admin']));

drop policy if exists "payments_select_member" on public.payments;
create policy "payments_select_member"
on public.payments
for select
to authenticated
using (public.app_is_org_member(workspace_id));

drop policy if exists "payments_write_admin" on public.payments;
create policy "payments_write_admin"
on public.payments
for all
to authenticated
using (public.app_has_org_role(workspace_id, array['owner', 'admin']))
with check (public.app_has_org_role(workspace_id, array['owner', 'admin']));

drop policy if exists "imports_select_member" on public.imports;
create policy "imports_select_member"
on public.imports
for select
to authenticated
using (public.app_is_org_member(workspace_id));

drop policy if exists "imports_write_admin" on public.imports;
create policy "imports_write_admin"
on public.imports
for all
to authenticated
using (public.app_has_org_role(workspace_id, array['owner', 'admin']))
with check (public.app_has_org_role(workspace_id, array['owner', 'admin']));

drop policy if exists "import_errors_select_member" on public.import_errors;
create policy "import_errors_select_member"
on public.import_errors
for select
to authenticated
using (
  exists (
    select 1
    from public.imports i
    where i.id = import_id
      and public.app_is_org_member(i.workspace_id)
  )
);

drop policy if exists "import_errors_write_admin" on public.import_errors;
create policy "import_errors_write_admin"
on public.import_errors
for all
to authenticated
using (
  exists (
    select 1
    from public.imports i
    where i.id = import_id
      and public.app_has_org_role(i.workspace_id, array['owner', 'admin'])
  )
)
with check (
  exists (
    select 1
    from public.imports i
    where i.id = import_id
      and public.app_has_org_role(i.workspace_id, array['owner', 'admin'])
  )
);
