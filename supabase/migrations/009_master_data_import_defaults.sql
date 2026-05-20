-- Migration: 009_master_data_import_defaults.sql
-- Per-workspace import defaults for master data and operation import fallback.

ALTER TABLE public.categories
  ADD COLUMN IF NOT EXISTS is_import_default BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE public.stores
  ADD COLUMN IF NOT EXISTS is_import_default BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE public.warehouses
  ADD COLUMN IF NOT EXISTS is_import_default BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE public.suppliers
  ADD COLUMN IF NOT EXISTS is_import_default BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX IF NOT EXISTS idx_categories_one_import_default
  ON public.categories(workspace_id)
  WHERE is_import_default = true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_stores_one_import_default
  ON public.stores(workspace_id)
  WHERE is_import_default = true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_warehouses_one_import_default
  ON public.warehouses(workspace_id)
  WHERE is_import_default = true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_suppliers_one_import_default
  ON public.suppliers(workspace_id)
  WHERE is_import_default = true;

CREATE OR REPLACE FUNCTION public.operation_import_resolve_supplier(
  p_workspace_id UUID,
  p_supplier_id TEXT,
  p_supplier_name TEXT,
  p_create BOOLEAN
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_id UUID;
  v_name TEXT;
BEGIN
  IF p_supplier_id IS NOT NULL AND p_supplier_id <> '' THEN
    SELECT id INTO v_id
    FROM public.suppliers
    WHERE id = p_supplier_id::UUID AND workspace_id = p_workspace_id;

    IF v_id IS NULL THEN
      RAISE EXCEPTION 'Supplier % is not available in this workspace', p_supplier_id;
    END IF;

    RETURN v_id;
  END IF;

  v_name := NULLIF(btrim(COALESCE(p_supplier_name, '')), '');
  IF v_name IS NULL THEN
    SELECT id INTO v_id
    FROM public.suppliers
    WHERE workspace_id = p_workspace_id AND is_import_default = true
    LIMIT 1;

    RETURN v_id;
  END IF;

  SELECT id INTO v_id
  FROM public.suppliers
  WHERE workspace_id = p_workspace_id AND lower(name) = lower(v_name)
  LIMIT 1;

  IF v_id IS NOT NULL THEN
    RETURN v_id;
  END IF;

  IF NOT p_create THEN
    RAISE EXCEPTION 'Supplier "%" must be mapped or approved for creation', v_name;
  END IF;

  INSERT INTO public.suppliers(workspace_id, name)
  VALUES (p_workspace_id, v_name)
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.operation_import_resolve_warehouse(
  p_workspace_id UUID,
  p_warehouse_id TEXT,
  p_warehouse_name TEXT,
  p_create BOOLEAN
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_id UUID;
  v_name TEXT;
BEGIN
  IF p_warehouse_id IS NOT NULL AND p_warehouse_id <> '' THEN
    SELECT id INTO v_id
    FROM public.warehouses
    WHERE id = p_warehouse_id::UUID AND workspace_id = p_workspace_id;

    IF v_id IS NULL THEN
      RAISE EXCEPTION 'Warehouse % is not available in this workspace', p_warehouse_id;
    END IF;

    RETURN v_id;
  END IF;

  v_name := NULLIF(btrim(COALESCE(p_warehouse_name, '')), '');
  IF v_name IS NULL THEN
    SELECT id INTO v_id
    FROM public.warehouses
    WHERE workspace_id = p_workspace_id AND is_import_default = true
    LIMIT 1;

    IF v_id IS NULL THEN
      RAISE EXCEPTION 'Warehouse is required';
    END IF;

    RETURN v_id;
  END IF;

  SELECT id INTO v_id
  FROM public.warehouses
  WHERE workspace_id = p_workspace_id AND lower(name) = lower(v_name)
  LIMIT 1;

  IF v_id IS NOT NULL THEN
    RETURN v_id;
  END IF;

  IF NOT p_create THEN
    RAISE EXCEPTION 'Warehouse "%" must be mapped or approved for creation', v_name;
  END IF;

  INSERT INTO public.warehouses(workspace_id, name)
  VALUES (p_workspace_id, v_name)
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.operation_import_resolve_store(
  p_workspace_id UUID,
  p_store_id TEXT,
  p_store_name TEXT,
  p_create BOOLEAN
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_id UUID;
  v_name TEXT;
BEGIN
  IF p_store_id IS NOT NULL AND p_store_id <> '' THEN
    SELECT id INTO v_id
    FROM public.stores
    WHERE id = p_store_id::UUID AND workspace_id = p_workspace_id;

    IF v_id IS NULL THEN
      RAISE EXCEPTION 'Store % is not available in this workspace', p_store_id;
    END IF;

    RETURN v_id;
  END IF;

  v_name := NULLIF(btrim(COALESCE(p_store_name, '')), '');
  IF v_name IS NULL THEN
    SELECT id INTO v_id
    FROM public.stores
    WHERE workspace_id = p_workspace_id AND is_import_default = true
    LIMIT 1;

    RETURN v_id;
  END IF;

  SELECT id INTO v_id
  FROM public.stores
  WHERE workspace_id = p_workspace_id AND lower(name) = lower(v_name)
  LIMIT 1;

  IF v_id IS NOT NULL THEN
    RETURN v_id;
  END IF;

  IF NOT p_create THEN
    RAISE EXCEPTION 'Store "%" must be mapped or approved for creation', v_name;
  END IF;

  INSERT INTO public.stores(workspace_id, name)
  VALUES (p_workspace_id, v_name)
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.operation_import_resolve_product(
  p_workspace_id UUID,
  p_product_id TEXT,
  p_product_name TEXT,
  p_sku_code TEXT,
  p_store_id UUID,
  p_create BOOLEAN
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_id UUID;
  v_name TEXT;
  v_sku TEXT;
  v_settings public.workspace_settings;
  v_category_id UUID;
  v_store_id UUID;
BEGIN
  IF p_product_id IS NOT NULL AND p_product_id <> '' THEN
    SELECT id INTO v_id
    FROM public.products
    WHERE id = p_product_id::UUID
      AND workspace_id = p_workspace_id
      AND is_defect_copy = false;

    IF v_id IS NULL THEN
      RAISE EXCEPTION 'Product % is not available in this workspace', p_product_id;
    END IF;

    RETURN v_id;
  END IF;

  v_name := NULLIF(btrim(COALESCE(p_product_name, '')), '');
  v_sku := NULLIF(btrim(COALESCE(p_sku_code, '')), '');

  IF v_sku IS NOT NULL THEN
    SELECT id INTO v_id
    FROM public.products
    WHERE workspace_id = p_workspace_id
      AND sku_code = v_sku
      AND is_defect_copy = false
    LIMIT 1;

    IF v_id IS NOT NULL THEN
      RETURN v_id;
    END IF;
  END IF;

  IF v_name IS NOT NULL THEN
    SELECT id INTO v_id
    FROM public.products
    WHERE workspace_id = p_workspace_id
      AND lower(name) = lower(v_name)
      AND is_defect_copy = false
    LIMIT 1;

    IF v_id IS NOT NULL THEN
      RETURN v_id;
    END IF;
  END IF;

  IF v_name IS NULL AND v_sku IS NULL THEN
    RAISE EXCEPTION 'Product is required';
  END IF;

  IF v_name IS NULL THEN
    v_name := v_sku;
  END IF;

  IF NOT p_create THEN
    RAISE EXCEPTION 'Product "%" must be mapped or approved for creation', v_name;
  END IF;

  SELECT * INTO v_settings
  FROM public.workspace_settings
  WHERE workspace_id = p_workspace_id;

  SELECT id INTO v_category_id
  FROM public.categories
  WHERE workspace_id = p_workspace_id AND is_import_default = true
  LIMIT 1;

  SELECT id INTO v_store_id
  FROM public.stores
  WHERE workspace_id = p_workspace_id AND is_import_default = true
  LIMIT 1;

  v_store_id := COALESCE(p_store_id, v_store_id);

  IF COALESCE(v_settings.category_required, false)
    AND COALESCE(v_category_id, v_settings.default_category_id) IS NULL THEN
    RAISE EXCEPTION 'Default category is required before creating products from import';
  END IF;

  IF COALESCE(v_settings.store_required, false)
    AND COALESCE(v_store_id, v_settings.default_store_id) IS NULL THEN
    RAISE EXCEPTION 'Default store is required before creating products from import';
  END IF;

  INSERT INTO public.products(workspace_id, name, sku_code, category_id, store_id)
  VALUES (
    p_workspace_id,
    v_name,
    v_sku,
    COALESCE(
      v_category_id,
      CASE WHEN COALESCE(v_settings.category_required, false)
        THEN v_settings.default_category_id
        ELSE NULL
      END
    ),
    COALESCE(
      v_store_id,
      CASE WHEN COALESCE(v_settings.store_required, false)
        THEN v_settings.default_store_id
        ELSE NULL
      END
    )
  )
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;
