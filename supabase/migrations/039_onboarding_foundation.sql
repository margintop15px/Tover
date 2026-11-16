-- Onboarding foundation. Additive and idempotent; apply after 038 (every run re-applies 034 onward).
-- Existing accounts, profiles, workspaces, memberships, invitations and sessions are kept.
--
-- Decisions:
-- * Request identity is read from Postgres on every request (session row + profile + active memberships),
--   so the GoTrue custom access token hook and the legacy PostgREST RPCs are dropped. GoTrue stays the
--   credential provider and keeps sending confirmation and recovery emails.
-- * Signup no longer creates a workspace: bootstrap_new_user only creates the profile.
-- * app_private.sessions gains:
--     session_id  the non-secret Bel session identity (was only inside the EDN `data` as :bel/session-id);
--                 the `id` column stays the secret cookie key and never reaches events or receipts.
--     created_at  when this sign-in was established; compared with the user's reset boundary.
--     ended_at    set when this exact sign-in ends (02b); an ended row grants nothing.
--   The existing `version` column is the selection revision (07): it is regenerated whenever the selected
--   workspace or the session's access changes, and every unsafe request must echo it (x-tover-context).
-- * app_private.session_boundaries holds one reset boundary per user (02a/02c): sessions created at or
--   before `valid_after` are invalid, except `kept_session_id` (the sign-in that performed the reset).
--   Newer sign-ins are never ended by an old boundary.
-- * The journal accepts session-scoped commands for the current, live session of the actor.
-- * Inventory history labels move from the PostgREST RPC to app_private.bel_actor_labels for tover_app.
-- * public.app_is_org_member/app_has_org_role stay: legacy RLS policies still reference them.
BEGIN;

-- Legacy RPCs replaced by Bel slices. Dropped by name so every historical signature goes.
DO $$ DECLARE f regprocedure; BEGIN
  FOR f IN SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE (n.nspname, p.proname) IN (
      ('public', 'create_organization'), ('public', 'accept_my_organization_invites'),
      ('private', 'accept_workspace_invitations'),
      ('public', 'manage_workspace_invitation'), ('private', 'manage_workspace_invitation'),
      ('public', 'revoke_workspace_invitation_reservation'), ('private', 'revoke_workspace_invitation_reservation'),
      ('public', 'workspace_team'), ('private', 'workspace_team'),
      ('public', 'my_workspaces'), ('private', 'my_workspaces'),
      ('public', 'request_member_recovery'), ('private', 'request_member_recovery'),
      ('public', 'custom_access_token_hook'))
  LOOP
    EXECUTE format('DROP FUNCTION %s', f);
  END LOOP;
END $$;

-- Signup metadata is user-controlled: it may name the profile, never create or join a workspace.
CREATE OR REPLACE FUNCTION public.bootstrap_new_user()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  INSERT INTO public.profiles (user_id, display_name)
  VALUES (new.id, nullif(trim(new.raw_user_meta_data ->> 'name'), ''))
  ON CONFLICT (user_id) DO UPDATE
  SET display_name = coalesce(excluded.display_name, public.profiles.display_name),
      updated_at = now();
  RETURN new;
END;
$$;
REVOKE EXECUTE ON FUNCTION public.bootstrap_new_user() FROM PUBLIC, anon, authenticated;

-- Session columns.
ALTER TABLE app_private.sessions ADD COLUMN IF NOT EXISTS session_id uuid;
ALTER TABLE app_private.sessions ADD COLUMN IF NOT EXISTS created_at timestamptz;
ALTER TABLE app_private.sessions ADD COLUMN IF NOT EXISTS ended_at timestamptz;
UPDATE app_private.sessions
  SET session_id = substring(data FROM ':bel/session-id #uuid "([0-9a-f-]{36})"')::uuid
  WHERE session_id IS NULL AND data ~ ':bel/session-id #uuid "[0-9a-f-]{36}"';
UPDATE app_private.sessions SET session_id = gen_random_uuid() WHERE session_id IS NULL;
-- The true start of older sessions is unknown; their last write is the closest bound before any reset.
UPDATE app_private.sessions SET created_at = updated_at WHERE created_at IS NULL;
ALTER TABLE app_private.sessions ALTER COLUMN session_id SET DEFAULT gen_random_uuid();
ALTER TABLE app_private.sessions ALTER COLUMN session_id SET NOT NULL;
ALTER TABLE app_private.sessions ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE app_private.sessions ALTER COLUMN created_at SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS sessions_session_id_idx ON app_private.sessions(session_id);
CREATE INDEX IF NOT EXISTS sessions_user_idx ON app_private.sessions(user_id);

CREATE TABLE IF NOT EXISTS app_private.session_boundaries (
  user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  valid_after timestamptz NOT NULL,
  kept_session_id uuid
);
ALTER TABLE app_private.session_boundaries ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.session_boundaries FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT, UPDATE (valid_after, kept_session_id) ON app_private.session_boundaries TO tover_app;
DROP POLICY IF EXISTS session_boundaries_own ON app_private.session_boundaries;
CREATE POLICY session_boundaries_own ON app_private.session_boundaries TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);

-- Session-scoped journal entries belong to the actor's current live session.
ALTER TABLE app_private.bel_events DROP CONSTRAINT IF EXISTS bel_events_scope_kind_check;
ALTER TABLE app_private.bel_events ADD CONSTRAINT bel_events_scope_kind_check
  CHECK (scope_kind IN ('user', 'workspace', 'session'));
ALTER TABLE app_private.bel_receipts DROP CONSTRAINT IF EXISTS bel_receipts_scope_kind_check;
ALTER TABLE app_private.bel_receipts ADD CONSTRAINT bel_receipts_scope_kind_check
  CHECK (scope_kind IN ('user', 'workspace', 'session'));

CREATE OR REPLACE FUNCTION app_private.bel_current_session(p_session_id uuid, p_user_id uuid)
RETURNS boolean LANGUAGE sql STABLE SECURITY DEFINER SET search_path = '' AS $$
  SELECT p_user_id IS NOT NULL AND p_user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid
    AND EXISTS (SELECT FROM app_private.sessions s
      WHERE s.id = current_setting('tover.session_key', true) AND s.session_id = p_session_id
        AND s.user_id = p_user_id AND s.ended_at IS NULL AND s.expires_at > clock_timestamp());
$$;
REVOKE ALL ON FUNCTION app_private.bel_current_session(uuid, uuid) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_current_session(uuid, uuid) TO tover_app;

DROP POLICY IF EXISTS bel_events_session ON app_private.bel_events;
CREATE POLICY bel_events_session ON app_private.bel_events TO tover_app
  USING (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id))
  WITH CHECK (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id));
DROP POLICY IF EXISTS bel_receipts_session ON app_private.bel_receipts;
CREATE POLICY bel_receipts_session ON app_private.bel_receipts TO tover_app
  USING (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id))
  WITH CHECK (scope_kind = 'session' AND app_private.bel_current_session(scope_id, actor_id));

-- History attribution for active members, called by the runtime with the request user's claim set.
CREATE OR REPLACE FUNCTION app_private.bel_actor_labels(p_workspace_id uuid, p_actor_ids uuid[])
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE v_user uuid := nullif(current_setting('request.jwt.claim.sub', true), '')::uuid; v_labels jsonb;
BEGIN
  IF v_user IS NULL OR NOT EXISTS (SELECT FROM public.organization_memberships m
    WHERE m.organization_id = p_workspace_id AND m.user_id = v_user AND m.status = 'active') THEN
    RAISE EXCEPTION 'Forbidden' USING ERRCODE = '42501';
  END IF;
  IF p_actor_ids IS NULL OR cardinality(p_actor_ids) > 200 OR array_position(p_actor_ids, NULL) IS NOT NULL THEN
    RAISE EXCEPTION 'Expected at most 200 actor IDs' USING ERRCODE = '22023';
  END IF;
  SELECT coalesce(jsonb_object_agg(m.user_id::text,
    coalesce(nullif(btrim(p.display_name), ''), nullif(btrim(u.email), ''))), '{}'::jsonb) INTO v_labels
  FROM public.organization_memberships m
  JOIN auth.users u ON u.id = m.user_id
  LEFT JOIN public.profiles p ON p.user_id = m.user_id
  WHERE m.organization_id = p_workspace_id AND m.user_id = ANY(p_actor_ids)
    AND coalesce(nullif(btrim(p.display_name), ''), nullif(btrim(u.email), '')) IS NOT NULL;
  RETURN v_labels;
END;
$$;
REVOKE ALL ON FUNCTION app_private.bel_actor_labels(uuid, uuid[]) FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_actor_labels(uuid, uuid[]) TO tover_app;

NOTIFY pgrst, 'reload schema';
COMMIT;
