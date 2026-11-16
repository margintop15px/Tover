-- Onboarding cutover cleanup. Additive and idempotent; no data changes.
-- app_private.bel_recipient_membership (038) was replaced by app_private.bel_recipient_access (042), which
-- also reports the latest removal. Nothing calls the old lookup, so the definer function is removed.
DROP FUNCTION IF EXISTS app_private.bel_recipient_membership(uuid, text);
