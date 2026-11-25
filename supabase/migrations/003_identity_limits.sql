-- Tover's own limits on confirmation, recovery and sign-in requests (board chapters 01, 01a, 02, 02a).
-- Emails are SHA-256 hashes. Expired rows mean nothing; the application removes them as it goes.

CREATE TABLE app_private.identity_email_limits (
  purpose    text NOT NULL CHECK (purpose IN ('confirmation', 'recovery')),
  email_key  text NOT NULL,
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (purpose, email_key)
);

CREATE TABLE app_private.identity_attempts (
  attempt_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email_key     text NOT NULL,
  address       text NOT NULL,
  at            timestamptz NOT NULL DEFAULT clock_timestamp(),
  status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'failed')),
  pair_cleared  boolean NOT NULL DEFAULT false,
  email_cleared boolean NOT NULL DEFAULT false
);
CREATE INDEX identity_email_limits_started_at_idx ON app_private.identity_email_limits (started_at);
CREATE INDEX identity_attempts_email_idx ON app_private.identity_attempts (email_key, at);
CREATE INDEX identity_attempts_address_idx ON app_private.identity_attempts (address, at);
CREATE INDEX identity_attempts_at_idx ON app_private.identity_attempts (at);

ALTER TABLE app_private.identity_email_limits ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_private.identity_attempts ENABLE ROW LEVEL SECURITY;
-- Requests are anonymous: like sessions, the rows are guarded by the application role alone.
CREATE POLICY identity_email_limits_runtime ON app_private.identity_email_limits FOR ALL TO tover_app
  USING (true) WITH CHECK (true);
CREATE POLICY identity_attempts_runtime ON app_private.identity_attempts FOR ALL TO tover_app
  USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE, DELETE ON app_private.identity_email_limits, app_private.identity_attempts TO tover_app;
