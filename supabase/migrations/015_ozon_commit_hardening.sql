-- Harden Ozon candidate commit idempotency and partial sync reporting.

ALTER TABLE public.marketplace_operation_candidates
  DROP CONSTRAINT IF EXISTS marketplace_operation_candidates_status_check;

ALTER TABLE public.marketplace_operation_candidates
  ADD CONSTRAINT marketplace_operation_candidates_status_check
  CHECK (status IN (
    'needs_mapping',
    'ready',
    'approved',
    'committing',
    'ignored',
    'committed'
  ));

ALTER TABLE public.marketplace_connections
  DROP CONSTRAINT IF EXISTS marketplace_connections_last_sync_status_check;

ALTER TABLE public.marketplace_connections
  ADD CONSTRAINT marketplace_connections_last_sync_status_check
  CHECK (
    last_sync_status IS NULL OR last_sync_status IN (
      'running',
      'completed',
      'completed_with_errors',
      'failed'
    )
  );

ALTER TABLE public.marketplace_sync_runs
  DROP CONSTRAINT IF EXISTS marketplace_sync_runs_status_check;

ALTER TABLE public.marketplace_sync_runs
  ADD CONSTRAINT marketplace_sync_runs_status_check
  CHECK (status IN (
    'running',
    'completed',
    'completed_with_errors',
    'failed'
  ));

CREATE TABLE IF NOT EXISTS public.marketplace_operation_commit_claims (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  connection_id UUID NOT NULL REFERENCES public.marketplace_connections(id) ON DELETE CASCADE,
  candidate_id UUID NOT NULL REFERENCES public.marketplace_operation_candidates(id) ON DELETE CASCADE,
  provider TEXT NOT NULL CHECK (provider IN ('ozon')),
  source_type TEXT NOT NULL,
  external_event_id TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'claimed' CHECK (
    status IN ('claimed', 'committed', 'failed')
  ),
  operation_id UUID REFERENCES public.operations(id) ON DELETE SET NULL,
  error TEXT,
  claimed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  committed_at TIMESTAMPTZ,
  failed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(candidate_id),
  UNIQUE(workspace_id, provider, source_type, external_event_id)
);

CREATE INDEX IF NOT EXISTS idx_marketplace_commit_claims_workspace_status
  ON public.marketplace_operation_commit_claims(workspace_id, status, claimed_at DESC);

DROP TRIGGER IF EXISTS set_marketplace_operation_commit_claims_updated_at
  ON public.marketplace_operation_commit_claims;

CREATE TRIGGER set_marketplace_operation_commit_claims_updated_at
BEFORE UPDATE ON public.marketplace_operation_commit_claims
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.marketplace_operation_commit_claims ENABLE ROW LEVEL SECURITY;

CREATE POLICY "marketplace_commit_claims_select_member"
ON public.marketplace_operation_commit_claims
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "marketplace_commit_claims_write_admin"
ON public.marketplace_operation_commit_claims
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));
