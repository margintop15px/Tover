-- Profile preferences and Auth token claims. Additive and idempotent; apply after 036.
-- Run 033 only on a fresh database: it verifies the exact original profiles shape.
BEGIN;

ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS language text;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS name_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS language_revision bigint NOT NULL DEFAULT 0;

DO $$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'public.profiles'::regclass
                 AND conname = 'profiles_language_check') THEN
    ALTER TABLE public.profiles ADD CONSTRAINT profiles_language_check
      CHECK (language IN ('en', 'ru'));
  END IF;
  -- NOT VALID: existing rows are not rewritten or rejected; new writes must comply.
  IF NOT EXISTS (SELECT FROM pg_constraint WHERE conrelid = 'public.profiles'::regclass
                 AND conname = 'profiles_display_name_check') THEN
    ALTER TABLE public.profiles ADD CONSTRAINT profiles_display_name_check
      CHECK (display_name IS NULL OR (char_length(display_name) <= 100 AND btrim(display_name) <> ''))
      NOT VALID;
  END IF;
END $$;

-- Signup keeps its chosen language without editing 033's function.
CREATE OR REPLACE FUNCTION app_private.profile_default_language()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = '' AS $$
BEGIN
  IF NEW.language IS NULL THEN
    SELECT u.raw_user_meta_data ->> 'locale' INTO NEW.language
    FROM auth.users u
    WHERE u.id = NEW.user_id AND u.raw_user_meta_data ->> 'locale' IN ('en', 'ru');
  END IF;
  RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION app_private.profile_default_language() FROM PUBLIC, anon, authenticated;
DROP TRIGGER IF EXISTS profiles_default_language ON public.profiles;
CREATE TRIGGER profiles_default_language BEFORE INSERT ON public.profiles
  FOR EACH ROW EXECUTE FUNCTION app_private.profile_default_language();

UPDATE public.profiles p SET language = u.raw_user_meta_data ->> 'locale'
FROM auth.users u
WHERE u.id = p.user_id AND p.language IS NULL AND u.raw_user_meta_data ->> 'locale' IN ('en', 'ru');

-- The runtime may change only these columns of its own row.
GRANT UPDATE (display_name, name_revision, language, language_revision) ON public.profiles TO tover_app;
DROP POLICY IF EXISTS bel_profile_update ON public.profiles;
CREATE POLICY bel_profile_update ON public.profiles FOR UPDATE TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);

-- GoTrue custom access token hook: the identity claims come from the database at token issue.
CREATE OR REPLACE FUNCTION public.custom_access_token_hook(event jsonb)
RETURNS jsonb LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = '' AS $$
DECLARE
  v_user uuid := (event ->> 'user_id')::uuid;
  v_claims jsonb := coalesce(event -> 'claims', '{}'::jsonb);
  v_language text;
  v_complete boolean;
  v_confirmed boolean;
  v_memberships jsonb;
BEGIN
  SELECT p.language, p.display_name IS NOT NULL AND btrim(p.display_name) <> '' AND char_length(p.display_name) <= 100
    INTO v_language, v_complete
  FROM public.profiles p WHERE p.user_id = v_user;
  SELECT u.email_confirmed_at IS NOT NULL INTO v_confirmed FROM auth.users u WHERE u.id = v_user;
  SELECT coalesce(jsonb_agg(jsonb_build_object(
      'workspace_id', o.id, 'name', o.name, 'role', m.role_id, 'created_at', m.created_at
    ) ORDER BY m.created_at, o.id), '[]'::jsonb)
    INTO v_memberships
  FROM public.organization_memberships m JOIN public.organizations o ON o.id = m.organization_id
  WHERE m.user_id = v_user AND m.status = 'active';
  v_claims := v_claims || jsonb_build_object(
    'language', v_language,
    'profile_complete', coalesce(v_complete, false),
    'email_confirmed', coalesce(v_confirmed, false),
    'memberships', v_memberships);
  RETURN jsonb_set(event, '{claims}', v_claims);
END;
$$;
REVOKE ALL ON FUNCTION public.custom_access_token_hook(jsonb) FROM PUBLIC, anon, authenticated;
DO $$ BEGIN
  IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'supabase_auth_admin') THEN
    GRANT USAGE ON SCHEMA public TO supabase_auth_admin;
    GRANT EXECUTE ON FUNCTION public.custom_access_token_hook(jsonb) TO supabase_auth_admin;
  END IF;
END $$;

COMMIT;
