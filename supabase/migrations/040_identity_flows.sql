-- Identity flows (01–02c). Additive and idempotent; apply after 039.
--
-- app_private.password_resets retains each non-secret password reset attempt (02a) before the provider is
-- asked to change the password, so an interrupted reset can be read and completed (02c) without a password
-- or recovery secret. Completion writes app_private.session_boundaries in the same acceptance.
-- The acting Tover sign-in writes only its own rows (request.jwt.claim.sub, set by the session authority).
BEGIN;

CREATE TABLE IF NOT EXISTS app_private.password_resets (
  attempt_id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  session_id uuid NOT NULL,
  status text NOT NULL CHECK (status IN ('requested', 'rejected', 'completed')),
  requested_at timestamptz NOT NULL,
  valid_after timestamptz
);
CREATE INDEX IF NOT EXISTS password_resets_user_idx ON app_private.password_resets(user_id);
ALTER TABLE app_private.password_resets ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON app_private.password_resets FROM PUBLIC, anon, authenticated, service_role;
GRANT SELECT, INSERT, UPDATE (status, valid_after) ON app_private.password_resets TO tover_app;
DROP POLICY IF EXISTS password_resets_own ON app_private.password_resets;
CREATE POLICY password_resets_own ON app_private.password_resets TO tover_app
  USING (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid)
  WITH CHECK (user_id = nullif(current_setting('request.jwt.claim.sub', true), '')::uuid);

COMMIT;
