-- Onboarding cutover cleanup. Additive and idempotent; no data changes. Applies after 041 (every run
-- re-applies 034 onward, so this file must stay last for the membership lookup below).
BEGIN;

-- app_private.bel_recipient_membership (038) was replaced by app_private.bel_recipient_access (041), which
-- also reports the latest removal. Nothing calls the old lookup, so the definer function is removed.
DROP FUNCTION IF EXISTS app_private.bel_recipient_membership(uuid, text);

-- Since 039 Sign Out marks the exact sign-in ended (ended_at) instead of deleting it. The caller's
-- membership lock, which every manager and workspace policy uses, must not outlive an ended sign-in.
CREATE OR REPLACE FUNCTION app_private.bel_workspace_membership(p_workspace_id uuid)
RETURNS TABLE(role_id text, status text)
LANGUAGE sql SECURITY DEFINER SET search_path = '' AS $$
  SELECT m.role_id, m.status FROM public.organization_memberships m
    WHERE m.organization_id = p_workspace_id AND m.user_id = auth.uid()
      AND EXISTS (SELECT FROM app_private.sessions s
        WHERE s.id = current_setting('tover.session_key', true)
          AND s.user_id = auth.uid() AND s.ended_at IS NULL AND s.expires_at > clock_timestamp())
    FOR SHARE OF m;
$$;
REVOKE ALL ON FUNCTION app_private.bel_workspace_membership(uuid)
  FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION app_private.bel_workspace_membership(uuid) TO tover_app;

COMMIT;
