-- Tover baseline: identity, workspaces, invitations and the Bel journal on an initialized Supabase Auth database.
-- Replaces migrations 001-042 (squashed 2026-11-19). Table and column names follow Bel model names, so the
-- PostgreSQL adapter infers every binding from `defmodel`. The runner (supabase/migrate.sh) applies each file once,
-- in one transaction, so migration files contain no BEGIN/COMMIT.

DO $guard$ BEGIN
  IF to_regclass('auth.users') IS NULL OR to_regprocedure('auth.uid()') IS NULL THEN
    RAISE EXCEPTION 'The baseline requires initialized Supabase Auth';
  END IF;
  IF to_regclass('public.organizations') IS NOT NULL OR to_regclass('public.workspaces') IS NOT NULL THEN
    RAISE EXCEPTION 'The baseline installs only into an empty database; swap legacy databases instead';
  END IF;
END $guard$;

-- Roles. Passwords are configured by the deployment, never here.
DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tover_app') THEN
    CREATE ROLE tover_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
  END IF;
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'tover_app' AND
    (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls OR NOT rolcanlogin))
    OR EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.member WHERE r.rolname = 'tover_app') THEN
    RAISE EXCEPTION 'tover_app must be a restricted login role without inherited roles';
  END IF;
  -- The private worker login: journal delivery only, no Auth or session access.
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'tover_journal') THEN
    CREATE ROLE tover_journal NOLOGIN;
  END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS app_private;
REVOKE ALL ON SCHEMA app_private FROM PUBLIC;
-- Bel binds models to unqualified table names; no table name exists in both schemas.
ALTER ROLE tover_app SET search_path = public, app_private;
ALTER ROLE tover_journal SET search_path = public, app_private;
GRANT USAGE ON SCHEMA app_private TO tover_app, tover_journal;
GRANT USAGE ON SCHEMA public TO tover_app, tover_journal;

-- Tables

CREATE TABLE public.roles (
  id          text PRIMARY KEY,
  description text NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.roles (id, description) VALUES
  ('owner', 'Full access to workspace and billing-level controls'),
  ('admin', 'Can manage members and operational data'),
  ('member', 'Can view data and use standard features');

CREATE TABLE public.profiles (
  user_id           uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  display_name      text CHECK (display_name IS NULL OR (char_length(display_name) <= 100 AND btrim(display_name) <> '')),
  language          text CHECK (language IN ('en', 'ru')),
  name_revision     bigint NOT NULL DEFAULT 0,
  language_revision bigint NOT NULL DEFAULT 0,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.workspaces (
  workspace_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name         text NOT NULL,
  created_by   uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE public.memberships (
  membership_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id          uuid NOT NULL REFERENCES public.workspaces(workspace_id) ON DELETE CASCADE,
  user_id               uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  role                  text NOT NULL REFERENCES public.roles(id),
  status                text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'invited', 'suspended', 'removed')),
  created_at            timestamptz NOT NULL DEFAULT now(),
  updated_at            timestamptz NOT NULL DEFAULT now(),
  recovery_requested_at timestamptz,
  removed_at            timestamptz,
  UNIQUE (workspace_id, user_id)
);
CREATE INDEX memberships_workspace_idx ON public.memberships (workspace_id, status);
CREATE INDEX memberships_user_idx ON public.memberships (user_id, status);

CREATE TABLE public.invitations (
  invitation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id  uuid NOT NULL REFERENCES public.workspaces(workspace_id) ON DELETE CASCADE,
  email         text NOT NULL,
  role          text NOT NULL REFERENCES public.roles(id),
  invited_by    uuid REFERENCES auth.users(id) ON DELETE SET NULL,
  status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'expired', 'revoked')),
  expires_at    timestamptz NOT NULL DEFAULT now() + interval '7 days',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX invitations_lookup_idx ON public.invitations (workspace_id, lower(email), status);
CREATE UNIQUE INDEX invitations_one_pending_idx ON public.invitations (workspace_id, lower(btrim(email)))
  WHERE status = 'pending';

CREATE TABLE public.invitation_deliveries (
  delivery_id     uuid PRIMARY KEY,
  invitation_id   uuid NOT NULL REFERENCES public.invitations(invitation_id) ON DELETE CASCADE,
  workspace_id    uuid NOT NULL REFERENCES public.workspaces(workspace_id) ON DELETE CASCADE,
  requested_by    uuid NOT NULL,
  requested_at    timestamptz NOT NULL,
  delivery_status text NOT NULL CHECK (delivery_status IN
    ('queued', 'retrying', 'unconfirmed', 'submitted', 'failed', 'exhausted', 'stopped')),
  attempts        integer NOT NULL DEFAULT 0 CHECK (attempts >= 0 AND attempts <= 4),
  next_attempt_at timestamptz,
  last_problem    text CHECK (last_problem IN ('temporary', 'permanent', 'uncertain')),
  attempted_at    timestamptz,
  submitted_at    timestamptz
);
CREATE INDEX invitation_deliveries_due_idx ON public.invitation_deliveries (next_attempt_at)
  WHERE delivery_status IN ('queued', 'retrying');
CREATE INDEX invitation_deliveries_reconcile_idx ON public.invitation_deliveries (next_attempt_at)
  WHERE delivery_status = 'unconfirmed';
CREATE INDEX invitation_deliveries_invitation_idx ON public.invitation_deliveries (invitation_id, requested_at);

CREATE TABLE app_private.sessions (
  id           text PRIMARY KEY,
  data         text NOT NULL DEFAULT '{}',
  tokens       text,
  session_id   uuid NOT NULL DEFAULT gen_random_uuid(),
  user_id      uuid,
  workspace_id uuid,
  version      uuid NOT NULL DEFAULT gen_random_uuid(),
  created_at   timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at   timestamptz NOT NULL DEFAULT now() + interval '30 days',
  updated_at   timestamptz NOT NULL DEFAULT now(),
  ended_at     timestamptz
);
CREATE UNIQUE INDEX sessions_session_id_idx ON app_private.sessions (session_id);
CREATE INDEX sessions_user_idx ON app_private.sessions (user_id);
CREATE INDEX sessions_expires_at_idx ON app_private.sessions (expires_at);

CREATE TABLE app_private.session_boundaries (
  user_id         uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  valid_after     timestamptz NOT NULL,
  kept_session_id uuid
);

CREATE TABLE app_private.password_resets (
  attempt_id   uuid PRIMARY KEY,
  user_id      uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  session_id   uuid NOT NULL,
  status       text NOT NULL CHECK (status IN ('requested', 'rejected', 'completed')),
  requested_at timestamptz NOT NULL,
  valid_after  timestamptz
);
CREATE INDEX password_resets_user_idx ON app_private.password_resets (user_id);

CREATE TABLE app_private.bel_events (
  id          uuid PRIMARY KEY,
  scope_kind  text NOT NULL DEFAULT 'user' CHECK (scope_kind IN ('user', 'workspace', 'session')),
  scope_id    uuid NOT NULL,
  actor_id    uuid NOT NULL,
  command_id  text NOT NULL,
  event_index integer CHECK (event_index >= 0),
  event_type  text NOT NULL,
  occurred_at timestamptz NOT NULL,
  recorded_at timestamptz DEFAULT clock_timestamp(),
  data        jsonb NOT NULL CHECK (jsonb_typeof(data) = 'object')
);
CREATE INDEX bel_events_command_idx ON app_private.bel_events (scope_id, command_id);
CREATE UNIQUE INDEX bel_events_scope_command_order_idx
  ON app_private.bel_events (scope_kind, scope_id, command_id, event_index);

CREATE TABLE app_private.bel_receipts (
  scope_kind text NOT NULL DEFAULT 'user' CHECK (scope_kind IN ('user', 'workspace', 'session')),
  scope_id   uuid NOT NULL,
  command_id text NOT NULL,
  actor_id   uuid NOT NULL,
  payload    bytea NOT NULL,
  PRIMARY KEY (scope_kind, scope_id, command_id)
);

-- Triggers

CREATE FUNCTION public.set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION public.set_updated_at() FROM PUBLIC;
CREATE TRIGGER profiles_updated_at BEFORE UPDATE ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER workspaces_updated_at BEFORE UPDATE ON public.workspaces
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER memberships_updated_at BEFORE UPDATE ON public.memberships
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER invitations_updated_at BEFORE UPDATE ON public.invitations
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- A new Auth user gets a profile; workspace and role hints in user metadata are deliberately ignored.
CREATE FUNCTION public.bootstrap_new_user() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  INSERT INTO public.profiles (user_id, display_name)
  VALUES (new.id, nullif(trim(new.raw_user_meta_data ->> 'name'), ''))
  ON CONFLICT (user_id) DO UPDATE
  SET display_name = coalesce(excluded.display_name, public.profiles.display_name), updated_at = now();
  RETURN new;
END;
$$;
REVOKE ALL ON FUNCTION public.bootstrap_new_user() FROM PUBLIC;
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION public.bootstrap_new_user();

CREATE FUNCTION app_private.profile_default_language() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF NEW.language IS NULL THEN
    SELECT u.raw_user_meta_data ->> 'locale' INTO NEW.language
    FROM auth.users u
    WHERE u.id = NEW.user_id AND u.raw_user_meta_data ->> 'locale' IN ('en', 'ru');
  END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION app_private.profile_default_language() FROM PUBLIC;
CREATE TRIGGER profiles_default_language BEFORE INSERT ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION app_private.profile_default_language();

-- Definer lookups. Each runs with an empty search_path and is granted only to the role that needs it.

CREATE FUNCTION app_private.bel_my_email() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT lower(btrim(u.email)) FROM auth.users u
    WHERE u.id = auth.uid() AND u.email_confirmed_at IS NOT NULL;
$$;

CREATE FUNCTION app_private.bel_confirmed_user() RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
DECLARE confirmed boolean;
BEGIN
  IF auth.uid() IS NULL OR NOT EXISTS (
    SELECT FROM app_private.sessions s
    WHERE s.id = current_setting('tover.session_key', true)
      AND s.user_id = auth.uid() AND s.expires_at > clock_timestamp()
  ) THEN RETURN false; END IF;
  SELECT u.email_confirmed_at IS NOT NULL INTO confirmed
    FROM auth.users u WHERE u.id = auth.uid() FOR SHARE;
  PERFORM 1 FROM public.profiles p WHERE p.user_id = auth.uid() FOR SHARE;
  RETURN coalesce(confirmed, false);
END;
$$;

CREATE FUNCTION app_private.bel_current_session(p_session_id uuid, p_user_id uuid) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT p_user_id IS NOT NULL AND p_user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM app_private.sessions s
      WHERE s.id = current_setting('tover.session_key', true) AND s.session_id = p_session_id
        AND s.user_id = p_user_id AND s.ended_at IS NULL AND s.expires_at > clock_timestamp());
$$;

-- The caller's membership, locked for the transaction; it must not outlive an ended sign-in.
CREATE FUNCTION app_private.bel_workspace_membership(p_workspace_id uuid) RETURNS TABLE(role text, status text)
LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.role, m.status FROM public.memberships m
    WHERE m.workspace_id = p_workspace_id AND m.user_id = auth.uid()
      AND EXISTS (SELECT FROM app_private.sessions s
        WHERE s.id = current_setting('tover.session_key', true)
          AND s.user_id = auth.uid() AND s.ended_at IS NULL AND s.expires_at > clock_timestamp())
    FOR SHARE OF m;
$$;

CREATE FUNCTION app_private.bel_manager(p_workspace_id uuid) RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT FROM app_private.bel_workspace_membership(p_workspace_id) w
    WHERE w.role IN ('owner', 'admin') AND w.status = 'active');
$$;

CREATE FUNCTION app_private.bel_new_workspace(p_workspace_id uuid) RETURNS boolean
LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT auth.uid() IS NOT NULL AND EXISTS (
    SELECT FROM public.workspaces w
    WHERE w.workspace_id = p_workspace_id AND w.created_by = auth.uid() AND w.xmin = pg_current_xact_id()::xid);
$$;

-- An invitation accepted in this transaction grants exactly its role.
CREATE FUNCTION app_private.bel_invitation_grant(p_workspace_id uuid, p_role text) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT EXISTS (SELECT FROM public.invitations i
    WHERE i.workspace_id = p_workspace_id AND i.role = p_role AND i.status = 'accepted'
      AND lower(btrim(i.email)) = app_private.bel_my_email() AND i.xmin = pg_current_xact_id()::xid);
$$;

CREATE FUNCTION app_private.bel_received_invitation(p_invitation_id uuid)
RETURNS TABLE(invitation_id uuid, workspace_id uuid, workspace_name text, email text, role text, inviter text,
              status text, expires_at timestamptz, created_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT i.invitation_id, i.workspace_id, w.name, lower(btrim(i.email)), i.role,
         coalesce(nullif(btrim(p.display_name), ''), u.email), i.status, i.expires_at, i.created_at
    FROM public.invitations i
    JOIN public.workspaces w ON w.workspace_id = i.workspace_id
    LEFT JOIN public.profiles p ON p.user_id = i.invited_by
    LEFT JOIN auth.users u ON u.id = i.invited_by
    WHERE i.invitation_id = p_invitation_id AND lower(btrim(i.email)) = app_private.bel_my_email();
$$;

CREATE FUNCTION app_private.bel_recipient_access(p_workspace_id uuid, p_email text)
RETURNS TABLE(status text, removed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.status, m.removed_at FROM public.memberships m JOIN auth.users u ON u.id = m.user_id
    WHERE m.workspace_id = p_workspace_id AND app_private.bel_manager(p_workspace_id)
      AND lower(btrim(u.email)) = lower(btrim(p_email))
    ORDER BY (m.status = 'active') DESC, m.created_at LIMIT 1;
$$;

CREATE FUNCTION app_private.bel_recipient_language(p_email text) RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT p.language FROM public.profiles p JOIN auth.users u ON u.id = p.user_id
    WHERE lower(btrim(u.email)) = lower(btrim(p_email))
    ORDER BY p.created_at, p.user_id LIMIT 1;
$$;

CREATE FUNCTION app_private.bel_workspace_team(p_workspace_id uuid)
RETURNS TABLE(user_id uuid, name text, email text, role text, status text, removed_at timestamptz)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.user_id, p.display_name, lower(btrim(u.email)), m.role, m.status, m.removed_at
    FROM public.memberships m
    JOIN auth.users u ON u.id = m.user_id
    LEFT JOIN public.profiles p ON p.user_id = m.user_id
    WHERE m.workspace_id = p_workspace_id AND m.status IN ('active', 'suspended', 'removed')
      AND app_private.bel_manager(p_workspace_id)
    ORDER BY lower(btrim(u.email)), m.user_id;
$$;

CREATE FUNCTION app_private.bel_actor_labels(p_workspace_id uuid, p_actor_ids uuid[]) RETURNS jsonb
LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_user uuid := nullif(current_setting('request.jwt.claim.sub', true), '')::uuid; v_labels jsonb;
BEGIN
  IF v_user IS NULL OR NOT EXISTS (SELECT FROM public.memberships m
    WHERE m.workspace_id = p_workspace_id AND m.user_id = v_user AND m.status = 'active') THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_actor_ids IS NULL OR cardinality(p_actor_ids) > 200 OR array_position(p_actor_ids, NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'Expected at most 200 actor IDs' USING ERRCODE = '22023';
  END IF;
  SELECT coalesce(jsonb_object_agg(m.user_id::text,
    coalesce(nullif(btrim(p.display_name), ''), nullif(btrim(u.email), ''))), '{}'::jsonb) INTO v_labels
  FROM public.memberships m
  JOIN auth.users u ON u.id = m.user_id
  LEFT JOIN public.profiles p ON p.user_id = m.user_id
  WHERE m.workspace_id = p_workspace_id AND m.user_id = ANY(p_actor_ids)
    AND coalesce(nullif(btrim(p.display_name), ''), nullif(btrim(u.email), '')) IS NOT NULL;
  RETURN v_labels;
END;
$$;

DO $$ DECLARE f regprocedure; BEGIN
  FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'app_private' LOOP
    EXECUTE format('REVOKE ALL ON FUNCTION %s FROM PUBLIC', f);
  END LOOP;
END $$;
GRANT EXECUTE ON FUNCTION app_private.bel_confirmed_user(), app_private.bel_current_session(uuid, uuid),
  app_private.bel_my_email(), app_private.bel_workspace_membership(uuid), app_private.bel_manager(uuid),
  app_private.bel_new_workspace(uuid), app_private.bel_invitation_grant(uuid, text),
  app_private.bel_received_invitation(uuid), app_private.bel_recipient_access(uuid, text),
  app_private.bel_workspace_team(uuid), app_private.bel_actor_labels(uuid, uuid[]) TO tover_app;
GRANT EXECUTE ON FUNCTION app_private.bel_recipient_language(text) TO tover_journal;

-- Row policies and grants. Only tover_app (signed-in requests) and tover_journal (delivery worker) reach these
-- tables; the Supabase API roles get nothing.

ALTER TABLE public.roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.invitation_deliveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.session_boundaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.password_resets ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.bel_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.bel_receipts ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, anon, authenticated, service_role;

-- Profiles: the owner reads and updates revisioned fields.
CREATE POLICY profile_read ON public.profiles FOR SELECT TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
CREATE POLICY profile_update ON public.profiles FOR UPDATE TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
GRANT SELECT, UPDATE (display_name, language, name_revision, language_revision) ON public.profiles TO tover_app;

-- Workspaces: creators insert; active members read; the worker reads names for email.
CREATE POLICY workspace_create ON public.workspaces FOR INSERT TO tover_app
  WITH CHECK (created_by = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
CREATE POLICY workspace_read ON public.workspaces FOR SELECT TO tover_app
  USING (EXISTS (SELECT FROM public.memberships m
    WHERE m.workspace_id = workspaces.workspace_id AND m.status = 'active'
      AND m.user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid));
CREATE POLICY workspace_delivery_read ON public.workspaces FOR SELECT TO tover_journal USING (true);
GRANT SELECT, INSERT (workspace_id, name, created_by, created_at) ON public.workspaces TO tover_app;
GRANT SELECT ON public.workspaces TO tover_journal;

-- Memberships: own rows only; the first owner of a new workspace; a member joining by an accepted invitation.
CREATE POLICY membership_read ON public.memberships FOR SELECT TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
CREATE POLICY membership_first_owner ON public.memberships FOR INSERT TO tover_app
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND role = 'owner' AND status = 'active' AND app_private.bel_new_workspace(workspace_id));
CREATE POLICY membership_invited_insert ON public.memberships FOR INSERT TO tover_app
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND status = 'active' AND app_private.bel_invitation_grant(workspace_id, role));
CREATE POLICY membership_invited_rejoin ON public.memberships FOR UPDATE TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND status IN ('invited', 'removed'))
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND status = 'active' AND app_private.bel_invitation_grant(workspace_id, role));
GRANT SELECT, INSERT (membership_id, workspace_id, user_id, role, status),
  UPDATE (workspace_id, user_id, role, status) ON public.memberships TO tover_app;

-- Invitations: managers issue, read and close; the recipient reads and accepts; the worker reads.
CREATE POLICY invitation_manager_insert ON public.invitations FOR INSERT TO tover_app
  WITH CHECK (invited_by = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND app_private.bel_manager(workspace_id));
CREATE POLICY invitation_manager_read ON public.invitations FOR SELECT TO tover_app
  USING (app_private.bel_manager(workspace_id));
CREATE POLICY invitation_manager_update ON public.invitations FOR UPDATE TO tover_app
  USING (app_private.bel_manager(workspace_id)) WITH CHECK (app_private.bel_manager(workspace_id));
CREATE POLICY invitation_recipient_read ON public.invitations FOR SELECT TO tover_app
  USING (lower(btrim(email)) = app_private.bel_my_email());
CREATE POLICY invitation_recipient_accept ON public.invitations FOR UPDATE TO tover_app
  USING (status = 'pending' AND lower(btrim(email)) = app_private.bel_my_email())
  WITH CHECK (status = 'accepted' AND lower(btrim(email)) = app_private.bel_my_email());
CREATE POLICY invitation_delivery_read ON public.invitations FOR SELECT TO tover_journal USING (true);
GRANT SELECT, INSERT (invitation_id, workspace_id, email, role, invited_by, status, expires_at, created_at),
  UPDATE (status) ON public.invitations TO tover_app;
GRANT SELECT ON public.invitations TO tover_journal;

-- Deliveries: managers queue and read; only the worker records attempts.
CREATE POLICY delivery_manager_insert ON public.invitation_deliveries FOR INSERT TO tover_app
  WITH CHECK (requested_by = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM public.invitations i
      WHERE i.invitation_id = invitation_deliveries.invitation_id AND i.workspace_id = invitation_deliveries.workspace_id)
    AND app_private.bel_manager(workspace_id));
CREATE POLICY delivery_manager_read ON public.invitation_deliveries FOR SELECT TO tover_app
  USING (app_private.bel_manager(workspace_id));
CREATE POLICY delivery_worker_read ON public.invitation_deliveries FOR SELECT TO tover_journal USING (true);
CREATE POLICY delivery_worker_update ON public.invitation_deliveries FOR UPDATE TO tover_journal
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT ON public.invitation_deliveries TO tover_app;
GRANT SELECT, UPDATE (delivery_status, attempts, next_attempt_at, last_problem, attempted_at, submitted_at)
  ON public.invitation_deliveries TO tover_journal;

-- Sessions: the encrypted store owns its rows; boundaries and resets belong to their user.
CREATE POLICY sessions_runtime ON app_private.sessions FOR ALL TO tover_app USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON app_private.sessions TO tover_app;
CREATE POLICY session_boundaries_own ON app_private.session_boundaries TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE (valid_after, kept_session_id) ON app_private.session_boundaries TO tover_app;
CREATE POLICY password_resets_own ON app_private.password_resets TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE (status, valid_after) ON app_private.password_resets TO tover_app;

-- Journal: user and session scopes belong to their actor; workspace scopes to active members; the worker
-- delivers workspace scopes only.
CREATE POLICY bel_events_user ON app_private.bel_events TO tover_app
  USING (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND actor_id = scope_id)
  WITH CHECK (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND actor_id = scope_id);
CREATE POLICY bel_events_session ON app_private.bel_events TO tover_app
  USING (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id))
  WITH CHECK (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id));
CREATE POLICY bel_events_workspace_read ON app_private.bel_events FOR SELECT TO tover_app
  USING (scope_kind = 'workspace' AND EXISTS (SELECT FROM public.memberships m
    WHERE m.workspace_id = scope_id AND m.status = 'active'
      AND m.user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid));
CREATE POLICY bel_events_workspace_write ON app_private.bel_events FOR INSERT TO tover_app
  WITH CHECK (scope_kind = 'workspace' AND actor_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM public.memberships m
      WHERE m.workspace_id = scope_id AND m.user_id = actor_id AND m.status = 'active'));
CREATE POLICY bel_events_delivery ON app_private.bel_events TO tover_journal
  USING (scope_kind = 'workspace') WITH CHECK (scope_kind = 'workspace');

CREATE POLICY bel_receipts_user ON app_private.bel_receipts TO tover_app
  USING (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND actor_id = scope_id)
  WITH CHECK (scope_kind = 'user' AND scope_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND actor_id = scope_id);
CREATE POLICY bel_receipts_session ON app_private.bel_receipts TO tover_app
  USING (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id))
  WITH CHECK (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id));
CREATE POLICY bel_receipts_workspace_read ON app_private.bel_receipts FOR SELECT TO tover_app
  USING (scope_kind = 'workspace' AND EXISTS (SELECT FROM public.memberships m
    WHERE m.workspace_id = scope_id AND m.status = 'active'
      AND m.user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid));
CREATE POLICY bel_receipts_workspace_write ON app_private.bel_receipts FOR INSERT TO tover_app
  WITH CHECK (scope_kind = 'workspace' AND actor_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM public.memberships m
      WHERE m.workspace_id = scope_id AND m.user_id = actor_id AND m.status = 'active'));
CREATE POLICY bel_receipts_delivery ON app_private.bel_receipts TO tover_journal
  USING (scope_kind = 'workspace') WITH CHECK (scope_kind = 'workspace');

GRANT SELECT, INSERT ON app_private.bel_events, app_private.bel_receipts TO tover_app, tover_journal;
