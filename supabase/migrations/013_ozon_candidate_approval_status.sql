-- Allow Ozon marketplace candidates to require explicit approval before commit.

ALTER TABLE public.marketplace_operation_candidates
  DROP CONSTRAINT IF EXISTS marketplace_operation_candidates_status_check;

ALTER TABLE public.marketplace_operation_candidates
  ADD CONSTRAINT marketplace_operation_candidates_status_check
  CHECK (status IN ('needs_mapping', 'ready', 'approved', 'ignored', 'committed'));

NOTIFY pgrst, 'reload schema';
