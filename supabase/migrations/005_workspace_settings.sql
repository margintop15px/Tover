-- ============================================================
-- Migration 005: Workspace settings
-- ============================================================

CREATE TABLE public.workspace_settings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL UNIQUE REFERENCES public.organizations(id) ON DELETE CASCADE,
  currency TEXT NOT NULL DEFAULT 'EUR',
  category_required BOOLEAN NOT NULL DEFAULT false,
  default_category_id UUID REFERENCES public.categories(id) ON DELETE SET NULL,
  store_required BOOLEAN NOT NULL DEFAULT false,
  default_store_id UUID REFERENCES public.stores(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Auto-update trigger (reuses existing function from 002)
CREATE TRIGGER set_workspace_settings_updated_at
BEFORE UPDATE ON public.workspace_settings
FOR EACH ROW
EXECUTE FUNCTION public.set_updated_at();

-- RLS
ALTER TABLE public.workspace_settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY "ws_settings_select_member" ON public.workspace_settings
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "ws_settings_insert_admin" ON public.workspace_settings
FOR INSERT TO authenticated
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "ws_settings_update_admin" ON public.workspace_settings
FOR UPDATE TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Seed default rows for existing organizations
INSERT INTO public.workspace_settings (workspace_id)
SELECT id FROM public.organizations
ON CONFLICT (workspace_id) DO NOTHING;
