-- Migration: 003_inventory_system.sql
-- Inventory Management System: tables, indexes, RLS, triggers, RPC functions

-- ============================================================
-- 1. TABLES
-- ============================================================

-- Product categories (optional grouping)
CREATE TABLE public.categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Stores (optional product assignment)
CREATE TABLE public.stores (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Warehouses
CREATE TABLE public.warehouses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  description TEXT,
  purpose TEXT CHECK (purpose IS NULL OR purpose IN ('storage', 'sales', 'production')),
  is_default_defect BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Suppliers
CREATE TABLE public.suppliers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  address TEXT,
  contact_info TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

-- Products (master catalog)
CREATE TABLE public.products (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  sku_code TEXT,
  category_id UUID REFERENCES public.categories(id) ON DELETE SET NULL,
  store_id UUID REFERENCES public.stores(id) ON DELETE SET NULL,
  is_defect_copy BOOLEAN NOT NULL DEFAULT false,
  original_product_id UUID REFERENCES public.products(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, sku_code)
);

-- Product balances (current inventory per product per warehouse)
CREATE TABLE public.product_balances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES public.products(id) ON DELETE CASCADE,
  warehouse_id UUID NOT NULL REFERENCES public.warehouses(id) ON DELETE CASCADE,
  quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
  unit_cost NUMERIC(14,4) NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, product_id, warehouse_id)
);

-- Operations (all types in one table, discriminated by type)
CREATE TABLE public.operations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  type TEXT NOT NULL CHECK (type IN (
    'purchase', 'sale', 'return', 'write_off',
    'transfer', 'production', 'defect', 'payment'
  )),
  operation_date DATE NOT NULL,
  comment TEXT,
  supplier_id UUID REFERENCES public.suppliers(id) ON DELETE RESTRICT,
  payment_amount NUMERIC(14,2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Operation items (product movements within an operation)
CREATE TABLE public.operation_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  operation_id UUID NOT NULL REFERENCES public.operations(id) ON DELETE CASCADE,
  product_id UUID NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
  warehouse_id UUID NOT NULL REFERENCES public.warehouses(id) ON DELETE RESTRICT,
  quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
  unit_price NUMERIC(14,4),
  direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
  store_id UUID REFERENCES public.stores(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- 2. INDEXES
-- ============================================================

CREATE INDEX idx_categories_workspace ON public.categories(workspace_id);
CREATE INDEX idx_stores_workspace ON public.stores(workspace_id);

CREATE INDEX idx_products_workspace ON public.products(workspace_id);
CREATE INDEX idx_products_sku ON public.products(workspace_id, sku_code);
CREATE INDEX idx_products_category ON public.products(category_id) WHERE category_id IS NOT NULL;

CREATE INDEX idx_warehouses_workspace ON public.warehouses(workspace_id);

CREATE INDEX idx_suppliers_workspace ON public.suppliers(workspace_id);

CREATE INDEX idx_product_balances_workspace ON public.product_balances(workspace_id);
CREATE INDEX idx_product_balances_product ON public.product_balances(product_id);
CREATE INDEX idx_product_balances_warehouse ON public.product_balances(warehouse_id);

CREATE INDEX idx_operations_workspace_date ON public.operations(workspace_id, operation_date DESC);
CREATE INDEX idx_operations_type ON public.operations(workspace_id, type);
CREATE INDEX idx_operations_supplier ON public.operations(supplier_id) WHERE supplier_id IS NOT NULL;

CREATE INDEX idx_operation_items_operation ON public.operation_items(operation_id);
CREATE INDEX idx_operation_items_product ON public.operation_items(product_id);
CREATE INDEX idx_operation_items_warehouse ON public.operation_items(warehouse_id);

-- ============================================================
-- 3. TRIGGERS (reuse existing set_updated_at function)
-- ============================================================

CREATE TRIGGER set_categories_updated_at
BEFORE UPDATE ON public.categories
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_stores_updated_at
BEFORE UPDATE ON public.stores
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_warehouses_updated_at
BEFORE UPDATE ON public.warehouses
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_suppliers_updated_at
BEFORE UPDATE ON public.suppliers
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_products_updated_at
BEFORE UPDATE ON public.products
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_product_balances_updated_at
BEFORE UPDATE ON public.product_balances
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_operations_updated_at
BEFORE UPDATE ON public.operations
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- ============================================================
-- 4. RLS POLICIES
-- ============================================================

ALTER TABLE public.categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.stores ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.warehouses ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.suppliers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.products ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.product_balances ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.operation_items ENABLE ROW LEVEL SECURITY;

-- Categories
CREATE POLICY "categories_select_member" ON public.categories
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "categories_write_admin" ON public.categories
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Stores
CREATE POLICY "stores_select_member" ON public.stores
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "stores_write_admin" ON public.stores
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Warehouses
CREATE POLICY "warehouses_select_member" ON public.warehouses
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "warehouses_write_admin" ON public.warehouses
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Suppliers
CREATE POLICY "suppliers_select_member" ON public.suppliers
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "suppliers_write_admin" ON public.suppliers
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Products
CREATE POLICY "products_select_member" ON public.products
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "products_write_admin" ON public.products
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Product Balances
CREATE POLICY "product_balances_select_member" ON public.product_balances
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "product_balances_write_admin" ON public.product_balances
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Operations
CREATE POLICY "operations_select_member" ON public.operations
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "operations_write_admin" ON public.operations
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

-- Operation Items (join-based via parent operations table)
CREATE POLICY "operation_items_select_member" ON public.operation_items
FOR SELECT TO authenticated
USING (
  EXISTS (
    SELECT 1 FROM public.operations o
    WHERE o.id = operation_id
      AND public.app_is_org_member(o.workspace_id)
  )
);

CREATE POLICY "operation_items_write_admin" ON public.operation_items
FOR ALL TO authenticated
USING (
  EXISTS (
    SELECT 1 FROM public.operations o
    WHERE o.id = operation_id
      AND public.app_has_org_role(o.workspace_id, array['owner', 'admin'])
  )
)
WITH CHECK (
  EXISTS (
    SELECT 1 FROM public.operations o
    WHERE o.id = operation_id
      AND public.app_has_org_role(o.workspace_id, array['owner', 'admin'])
  )
);

-- ============================================================
-- 5. EXTEND BOOTSTRAP — auto-create defect warehouse
-- ============================================================

CREATE OR REPLACE FUNCTION public.bootstrap_new_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
  v_display_name text;
  v_org_name text;
  v_org_id uuid;
  v_invited_org_id uuid;
  v_invited_role text;
BEGIN
  v_display_name := nullif(trim(new.raw_user_meta_data ->> 'name'), '');

  INSERT INTO public.profiles (user_id, display_name)
  VALUES (new.id, v_display_name)
  ON CONFLICT (user_id) DO UPDATE
  SET
    display_name = coalesce(excluded.display_name, public.profiles.display_name),
    updated_at = now();

  v_org_name := nullif(trim(new.raw_user_meta_data ->> 'organization_name'), '');

  IF v_org_name IS NOT NULL THEN
    INSERT INTO public.organizations (name, created_by)
    VALUES (v_org_name, new.id)
    RETURNING id INTO v_org_id;

    INSERT INTO public.organization_memberships (
      organization_id, user_id, role_id, status
    )
    VALUES (v_org_id, new.id, 'owner', 'active')
    ON CONFLICT (organization_id, user_id) DO NOTHING;

    -- Auto-create default defect warehouse for the new organization
    INSERT INTO public.warehouses (workspace_id, name, is_default_defect)
    VALUES (v_org_id, 'Брак', true);
  END IF;

  BEGIN
    v_invited_org_id := nullif(trim(new.raw_user_meta_data ->> 'organization_id'), '')::uuid;
  EXCEPTION
    WHEN invalid_text_representation THEN
      v_invited_org_id := null;
  END;

  v_invited_role := coalesce(nullif(trim(new.raw_user_meta_data ->> 'organization_role'), ''), 'member');

  IF v_invited_org_id IS NOT NULL THEN
    IF NOT EXISTS (SELECT 1 FROM public.roles WHERE id = v_invited_role) THEN
      v_invited_role := 'member';
    END IF;

    INSERT INTO public.organization_memberships (
      organization_id, user_id, role_id, status
    )
    VALUES (v_invited_org_id, new.id, v_invited_role, 'active')
    ON CONFLICT (organization_id, user_id) DO UPDATE
    SET
      role_id = excluded.role_id,
      status = 'active',
      updated_at = now();

    UPDATE public.organization_invites
    SET status = 'accepted', updated_at = now()
    WHERE organization_id = v_invited_org_id
      AND lower(email) = lower(new.email)
      AND status = 'pending';
  END IF;

  RETURN new;
END;
$$;

-- ============================================================
-- 6. RPC FUNCTIONS for balance operations
-- ============================================================

-- Atomic upsert: adjust quantity and optionally set new unit cost
CREATE OR REPLACE FUNCTION public.update_product_balance(
  p_workspace_id UUID,
  p_product_id UUID,
  p_warehouse_id UUID,
  p_qty_delta NUMERIC,
  p_new_unit_cost NUMERIC DEFAULT NULL
)
RETURNS public.product_balances
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_balance public.product_balances;
BEGIN
  -- Try to lock existing row
  SELECT * INTO v_balance
  FROM public.product_balances
  WHERE workspace_id = p_workspace_id
    AND product_id = p_product_id
    AND warehouse_id = p_warehouse_id
  FOR UPDATE;

  IF v_balance IS NULL THEN
    INSERT INTO public.product_balances (workspace_id, product_id, warehouse_id, quantity, unit_cost)
    VALUES (
      p_workspace_id,
      p_product_id,
      p_warehouse_id,
      GREATEST(p_qty_delta, 0),
      COALESCE(p_new_unit_cost, 0)
    )
    RETURNING * INTO v_balance;
  ELSE
    UPDATE public.product_balances
    SET
      quantity = quantity + p_qty_delta,
      unit_cost = COALESCE(p_new_unit_cost, unit_cost)
    WHERE id = v_balance.id
    RETURNING * INTO v_balance;
  END IF;

  RETURN v_balance;
END;
$$;

-- Purchase: weighted-average cost recalculation
CREATE OR REPLACE FUNCTION public.process_purchase_balance(
  p_workspace_id UUID,
  p_product_id UUID,
  p_warehouse_id UUID,
  p_purchase_qty NUMERIC,
  p_purchase_unit_price NUMERIC
)
RETURNS public.product_balances
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_balance public.product_balances;
  v_new_qty NUMERIC;
  v_new_cost NUMERIC;
BEGIN
  SELECT * INTO v_balance
  FROM public.product_balances
  WHERE workspace_id = p_workspace_id
    AND product_id = p_product_id
    AND warehouse_id = p_warehouse_id
  FOR UPDATE;

  IF v_balance IS NULL THEN
    v_new_qty := p_purchase_qty;
    v_new_cost := p_purchase_unit_price;

    INSERT INTO public.product_balances (workspace_id, product_id, warehouse_id, quantity, unit_cost)
    VALUES (p_workspace_id, p_product_id, p_warehouse_id, v_new_qty, v_new_cost)
    RETURNING * INTO v_balance;
  ELSE
    v_new_qty := v_balance.quantity + p_purchase_qty;
    IF v_new_qty > 0 THEN
      v_new_cost := (v_balance.quantity * v_balance.unit_cost + p_purchase_qty * p_purchase_unit_price) / v_new_qty;
    ELSE
      v_new_cost := p_purchase_unit_price;
    END IF;

    UPDATE public.product_balances
    SET quantity = v_new_qty, unit_cost = v_new_cost
    WHERE id = v_balance.id
    RETURNING * INTO v_balance;
  END IF;

  RETURN v_balance;
END;
$$;

-- Production: multi-source cost rollup
CREATE OR REPLACE FUNCTION public.process_production_balances(
  p_workspace_id UUID,
  p_sources JSONB,
  p_output JSONB
)
RETURNS public.product_balances
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_source JSONB;
  v_total_input_cost NUMERIC := 0;
  v_source_balance public.product_balances;
  v_output_balance public.product_balances;
  v_output_product_id UUID;
  v_output_warehouse_id UUID;
  v_output_qty NUMERIC;
  v_new_qty NUMERIC;
  v_new_cost NUMERIC;
BEGIN
  -- Process each source: deduct quantity, accumulate cost
  FOR v_source IN SELECT * FROM jsonb_array_elements(p_sources)
  LOOP
    SELECT * INTO v_source_balance
    FROM public.product_balances
    WHERE workspace_id = p_workspace_id
      AND product_id = (v_source->>'product_id')::UUID
      AND warehouse_id = (v_source->>'warehouse_id')::UUID
    FOR UPDATE;

    IF v_source_balance IS NOT NULL THEN
      v_total_input_cost := v_total_input_cost + (v_source->>'quantity')::NUMERIC * v_source_balance.unit_cost;

      UPDATE public.product_balances
      SET quantity = quantity - (v_source->>'quantity')::NUMERIC
      WHERE id = v_source_balance.id;
    ELSE
      -- Source has no balance record — cost contribution is 0, create a negative balance
      INSERT INTO public.product_balances (workspace_id, product_id, warehouse_id, quantity, unit_cost)
      VALUES (
        p_workspace_id,
        (v_source->>'product_id')::UUID,
        (v_source->>'warehouse_id')::UUID,
        -((v_source->>'quantity')::NUMERIC),
        0
      );
    END IF;
  END LOOP;

  -- Process output: weighted-average cost with total input cost
  v_output_product_id := (p_output->>'product_id')::UUID;
  v_output_warehouse_id := (p_output->>'warehouse_id')::UUID;
  v_output_qty := (p_output->>'quantity')::NUMERIC;

  SELECT * INTO v_output_balance
  FROM public.product_balances
  WHERE workspace_id = p_workspace_id
    AND product_id = v_output_product_id
    AND warehouse_id = v_output_warehouse_id
  FOR UPDATE;

  IF v_output_balance IS NULL THEN
    v_new_qty := v_output_qty;
    IF v_new_qty > 0 THEN
      v_new_cost := v_total_input_cost / v_new_qty;
    ELSE
      v_new_cost := 0;
    END IF;

    INSERT INTO public.product_balances (workspace_id, product_id, warehouse_id, quantity, unit_cost)
    VALUES (p_workspace_id, v_output_product_id, v_output_warehouse_id, v_new_qty, v_new_cost)
    RETURNING * INTO v_output_balance;
  ELSE
    v_new_qty := v_output_balance.quantity + v_output_qty;
    IF v_new_qty > 0 THEN
      v_new_cost := (v_output_balance.quantity * v_output_balance.unit_cost + v_total_input_cost) / v_new_qty;
    ELSE
      v_new_cost := CASE WHEN v_output_qty > 0 THEN v_total_input_cost / v_output_qty ELSE v_output_balance.unit_cost END;
    END IF;

    UPDATE public.product_balances
    SET quantity = v_new_qty, unit_cost = v_new_cost
    WHERE id = v_output_balance.id
    RETURNING * INTO v_output_balance;
  END IF;

  RETURN v_output_balance;
END;
$$;
