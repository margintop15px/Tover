-- Operation import jobs, candidates, audit links, and atomic commit RPC.

CREATE TABLE public.operation_imports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  file_name TEXT NOT NULL,
  file_type TEXT NOT NULL,
  file_size BIGINT NOT NULL DEFAULT 0,
  file_hash TEXT NOT NULL,
  source_kind TEXT NOT NULL DEFAULT 'upload',
  status TEXT NOT NULL DEFAULT 'uploaded' CHECK (
    status IN ('uploaded', 'extracting', 'needs_review', 'ready', 'committing', 'completed', 'failed')
  ),
  summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  findings JSONB NOT NULL DEFAULT '{}'::jsonb,
  extracted JSONB NOT NULL DEFAULT '{}'::jsonb,
  generated_code TEXT,
  generated_code_result JSONB NOT NULL DEFAULT '{}'::jsonb,
  security_report JSONB NOT NULL DEFAULT '{}'::jsonb,
  approved_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  approved_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE public.operation_import_candidates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  import_id UUID NOT NULL REFERENCES public.operation_imports(id) ON DELETE CASCADE,
  row_index INTEGER NOT NULL,
  fingerprint TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'needs_review' CHECK (
    status IN ('needs_review', 'ready', 'approved', 'blocked', 'committed')
  ),
  confidence NUMERIC(5,4) NOT NULL DEFAULT 0,
  source JSONB NOT NULL DEFAULT '{}'::jsonb,
  raw JSONB NOT NULL DEFAULT '{}'::jsonb,
  operation JSONB NOT NULL DEFAULT '{}'::jsonb,
  normalized_operation JSONB NOT NULL DEFAULT '{}'::jsonb,
  validation_errors JSONB NOT NULL DEFAULT '[]'::jsonb,
  duplicate_of UUID REFERENCES public.operation_import_candidates(id) ON DELETE SET NULL,
  created_operation_id UUID REFERENCES public.operations(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(import_id, row_index)
);

CREATE TABLE public.operation_import_committed_operations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  import_id UUID NOT NULL REFERENCES public.operation_imports(id) ON DELETE CASCADE,
  candidate_id UUID NOT NULL REFERENCES public.operation_import_candidates(id) ON DELETE CASCADE,
  operation_id UUID NOT NULL REFERENCES public.operations(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(candidate_id),
  UNIQUE(operation_id)
);

CREATE TABLE public.operation_import_fingerprints (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  fingerprint TEXT NOT NULL,
  import_id UUID NOT NULL REFERENCES public.operation_imports(id) ON DELETE CASCADE,
  candidate_id UUID NOT NULL REFERENCES public.operation_import_candidates(id) ON DELETE CASCADE,
  operation_id UUID NOT NULL REFERENCES public.operations(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, fingerprint)
);

CREATE INDEX idx_operation_imports_workspace_created
  ON public.operation_imports(workspace_id, created_at DESC);

CREATE INDEX idx_operation_imports_file_hash
  ON public.operation_imports(workspace_id, file_hash);

CREATE INDEX idx_operation_import_candidates_import
  ON public.operation_import_candidates(import_id, row_index);

CREATE INDEX idx_operation_import_candidates_workspace_status
  ON public.operation_import_candidates(workspace_id, status);

CREATE INDEX idx_operation_import_committed_import
  ON public.operation_import_committed_operations(import_id);

CREATE TRIGGER set_operation_imports_updated_at
BEFORE UPDATE ON public.operation_imports
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

CREATE TRIGGER set_operation_import_candidates_updated_at
BEFORE UPDATE ON public.operation_import_candidates
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.operation_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.operation_import_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.operation_import_committed_operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.operation_import_fingerprints ENABLE ROW LEVEL SECURITY;

CREATE POLICY "operation_imports_select_member" ON public.operation_imports
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "operation_imports_write_admin" ON public.operation_imports
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "operation_import_candidates_select_member" ON public.operation_import_candidates
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "operation_import_candidates_write_admin" ON public.operation_import_candidates
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "operation_import_committed_select_member" ON public.operation_import_committed_operations
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "operation_import_committed_write_admin" ON public.operation_import_committed_operations
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "operation_import_fingerprints_select_member" ON public.operation_import_fingerprints
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "operation_import_fingerprints_write_admin" ON public.operation_import_fingerprints
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

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
    RETURN NULL;
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
    RAISE EXCEPTION 'Warehouse is required';
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
    RETURN NULL;
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

  IF v_name IS NULL THEN
    RAISE EXCEPTION 'Product is required';
  END IF;

  IF NOT p_create THEN
    RAISE EXCEPTION 'Product "%" must be mapped or approved for creation', v_name;
  END IF;

  SELECT * INTO v_settings
  FROM public.workspace_settings
  WHERE workspace_id = p_workspace_id;

  IF v_settings.category_required AND v_settings.default_category_id IS NULL THEN
    RAISE EXCEPTION 'Default category is required before creating products from import';
  END IF;

  IF v_settings.store_required AND COALESCE(p_store_id, v_settings.default_store_id) IS NULL THEN
    RAISE EXCEPTION 'Default store is required before creating products from import';
  END IF;

  INSERT INTO public.products(workspace_id, name, sku_code, category_id, store_id)
  VALUES (
    p_workspace_id,
    v_name,
    v_sku,
    CASE WHEN v_settings.category_required THEN v_settings.default_category_id ELSE NULL END,
    COALESCE(p_store_id, CASE WHEN v_settings.store_required THEN v_settings.default_store_id ELSE NULL END)
  )
  RETURNING id INTO v_id;

  RETURN v_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.commit_operation_import(
  p_workspace_id UUID,
  p_import_id UUID,
  p_approved_by UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_import public.operation_imports;
  v_candidate public.operation_import_candidates;
  v_op JSONB;
  v_item JSONB;
  v_type TEXT;
  v_operation_id UUID;
  v_supplier_id UUID;
  v_product_id UUID;
  v_warehouse_id UUID;
  v_store_id UUID;
  v_direction TEXT;
  v_qty NUMERIC;
  v_unit_price NUMERIC;
  v_created_ids UUID[] := ARRAY[]::UUID[];
  v_sources JSONB := '[]'::jsonb;
  v_output JSONB := '{}'::jsonb;
  v_source_balance public.product_balances;
  v_unit_cost NUMERIC;
  v_original_product public.products;
  v_defect_product_id UUID;
  v_defect_warehouse_id UUID;
BEGIN
  SELECT * INTO v_import
  FROM public.operation_imports
  WHERE id = p_import_id AND workspace_id = p_workspace_id
  FOR UPDATE;

  IF v_import.id IS NULL THEN
    RAISE EXCEPTION 'Operation import not found';
  END IF;

  IF v_import.status = 'completed' THEN
    RAISE EXCEPTION 'Operation import has already been committed';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.operation_import_candidates
    WHERE import_id = p_import_id
      AND status <> 'approved'
  ) THEN
    RAISE EXCEPTION 'All candidates must be approved before commit';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.operation_import_candidates
    WHERE import_id = p_import_id
      AND validation_errors <> '[]'::jsonb
  ) THEN
    RAISE EXCEPTION 'Cannot commit import while validation errors remain';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.operation_import_candidates c
    JOIN public.operation_import_fingerprints f
      ON f.workspace_id = p_workspace_id
     AND f.fingerprint = c.fingerprint
    WHERE c.import_id = p_import_id
  ) THEN
    RAISE EXCEPTION 'Duplicate operations were found in this import';
  END IF;

  UPDATE public.operation_imports
  SET status = 'committing',
      approved_by = p_approved_by,
      approved_at = now()
  WHERE id = p_import_id;

  FOR v_candidate IN
    SELECT *
    FROM public.operation_import_candidates
    WHERE import_id = p_import_id
    ORDER BY row_index ASC
  LOOP
    v_op := v_candidate.normalized_operation;
    v_type := v_op->>'type';
    v_supplier_id := public.operation_import_resolve_supplier(
      p_workspace_id,
      v_op->>'supplierId',
      v_op->>'supplierName',
      COALESCE((v_op->>'createSupplier')::BOOLEAN, false)
    );

    INSERT INTO public.operations(workspace_id, type, operation_date, comment, supplier_id, payment_amount)
    VALUES (
      p_workspace_id,
      v_type,
      (v_op->>'operationDate')::DATE,
      NULLIF(v_op->>'comment', ''),
      v_supplier_id,
      CASE WHEN v_type = 'payment' THEN (v_op->>'paymentAmount')::NUMERIC ELSE NULL END
    )
    RETURNING id INTO v_operation_id;

    IF v_type IN ('purchase', 'inventory_adjustment', 'sale', 'return', 'write_off') THEN
      FOR v_item IN SELECT * FROM jsonb_array_elements(v_op->'items')
      LOOP
        v_store_id := public.operation_import_resolve_store(
          p_workspace_id,
          v_item->>'storeId',
          v_item->>'storeName',
          COALESCE((v_item->>'createStore')::BOOLEAN, false)
        );
        v_product_id := public.operation_import_resolve_product(
          p_workspace_id,
          v_item->>'productId',
          v_item->>'productName',
          v_item->>'skuCode',
          v_store_id,
          COALESCE((v_item->>'createProduct')::BOOLEAN, false)
        );
        v_warehouse_id := public.operation_import_resolve_warehouse(
          p_workspace_id,
          v_item->>'warehouseId',
          v_item->>'warehouseName',
          COALESCE((v_item->>'createWarehouse')::BOOLEAN, false)
        );
        v_direction := CASE WHEN v_type = 'return' OR v_type IN ('purchase', 'inventory_adjustment') THEN 'in' ELSE 'out' END;
        v_qty := (v_item->>'quantity')::NUMERIC;
        v_unit_price := NULLIF(v_item->>'unitPrice', '')::NUMERIC;

        INSERT INTO public.operation_items(operation_id, product_id, warehouse_id, quantity, unit_price, direction, store_id)
        VALUES (v_operation_id, v_product_id, v_warehouse_id, v_qty, v_unit_price, v_direction, v_store_id);

        IF v_type IN ('purchase', 'inventory_adjustment') THEN
          PERFORM public.process_purchase_balance(p_workspace_id, v_product_id, v_warehouse_id, v_qty, v_unit_price);
        ELSE
          PERFORM public.update_product_balance(
            p_workspace_id,
            v_product_id,
            v_warehouse_id,
            CASE WHEN v_direction = 'in' THEN v_qty ELSE -v_qty END,
            NULL
          );
        END IF;
      END LOOP;
    ELSIF v_type = 'transfer' THEN
      v_unit_cost := 0;

      FOR v_item IN SELECT * FROM jsonb_array_elements(v_op->'items')
      LOOP
        v_product_id := public.operation_import_resolve_product(
          p_workspace_id,
          v_item->>'productId',
          v_item->>'productName',
          v_item->>'skuCode',
          NULL,
          COALESCE((v_item->>'createProduct')::BOOLEAN, false)
        );
        v_warehouse_id := public.operation_import_resolve_warehouse(
          p_workspace_id,
          v_item->>'warehouseId',
          v_item->>'warehouseName',
          COALESCE((v_item->>'createWarehouse')::BOOLEAN, false)
        );
        v_direction := v_item->>'direction';
        v_qty := (v_item->>'quantity')::NUMERIC;

        IF v_direction = 'out' THEN
          SELECT * INTO v_source_balance
          FROM public.product_balances
          WHERE workspace_id = p_workspace_id
            AND product_id = v_product_id
            AND warehouse_id = v_warehouse_id
          FOR UPDATE;
          v_unit_cost := COALESCE(v_source_balance.unit_cost, 0);
        END IF;

        INSERT INTO public.operation_items(operation_id, product_id, warehouse_id, quantity, unit_price, direction)
        VALUES (v_operation_id, v_product_id, v_warehouse_id, v_qty, v_unit_cost, v_direction);

        PERFORM public.update_product_balance(
          p_workspace_id,
          v_product_id,
          v_warehouse_id,
          CASE WHEN v_direction = 'in' THEN v_qty ELSE -v_qty END,
          CASE WHEN v_direction = 'in' THEN v_unit_cost ELSE NULL END
        );
      END LOOP;
    ELSIF v_type = 'production' THEN
      v_sources := '[]'::jsonb;
      v_output := '{}'::jsonb;

      FOR v_item IN SELECT * FROM jsonb_array_elements(v_op->'items')
      LOOP
        v_store_id := public.operation_import_resolve_store(
          p_workspace_id,
          v_item->>'storeId',
          v_item->>'storeName',
          COALESCE((v_item->>'createStore')::BOOLEAN, false)
        );
        v_product_id := public.operation_import_resolve_product(
          p_workspace_id,
          v_item->>'productId',
          v_item->>'productName',
          v_item->>'skuCode',
          v_store_id,
          COALESCE((v_item->>'createProduct')::BOOLEAN, false)
        );
        v_warehouse_id := public.operation_import_resolve_warehouse(
          p_workspace_id,
          v_item->>'warehouseId',
          v_item->>'warehouseName',
          COALESCE((v_item->>'createWarehouse')::BOOLEAN, false)
        );
        v_direction := v_item->>'direction';
        v_qty := (v_item->>'quantity')::NUMERIC;
        v_unit_price := NULLIF(v_item->>'unitPrice', '')::NUMERIC;

        INSERT INTO public.operation_items(operation_id, product_id, warehouse_id, quantity, unit_price, direction, store_id)
        VALUES (v_operation_id, v_product_id, v_warehouse_id, v_qty, v_unit_price, v_direction, v_store_id);

        IF v_direction = 'out' THEN
          v_sources := v_sources || jsonb_build_array(jsonb_build_object(
            'product_id', v_product_id,
            'warehouse_id', v_warehouse_id,
            'quantity', v_qty
          ));
        ELSE
          v_output := jsonb_build_object(
            'product_id', v_product_id,
            'warehouse_id', v_warehouse_id,
            'quantity', v_qty
          );
          IF v_store_id IS NOT NULL THEN
            UPDATE public.products
            SET store_id = v_store_id
            WHERE id = v_product_id AND workspace_id = p_workspace_id;
          END IF;
        END IF;
      END LOOP;

      PERFORM public.process_production_balances(p_workspace_id, v_sources, v_output);
    ELSIF v_type = 'defect' THEN
      v_item := (v_op->'items')->0;
      v_product_id := public.operation_import_resolve_product(
        p_workspace_id,
        v_item->>'productId',
        v_item->>'productName',
        v_item->>'skuCode',
        NULL,
        COALESCE((v_item->>'createProduct')::BOOLEAN, false)
      );
      v_warehouse_id := public.operation_import_resolve_warehouse(
        p_workspace_id,
        v_item->>'warehouseId',
        v_item->>'warehouseName',
        COALESCE((v_item->>'createWarehouse')::BOOLEAN, false)
      );
      v_qty := (v_item->>'quantity')::NUMERIC;

      SELECT * INTO v_original_product
      FROM public.products
      WHERE id = v_product_id AND workspace_id = p_workspace_id;

      SELECT id INTO v_defect_product_id
      FROM public.products
      WHERE workspace_id = p_workspace_id
        AND is_defect_copy = true
        AND original_product_id = v_product_id
      LIMIT 1;

      IF v_defect_product_id IS NULL THEN
        INSERT INTO public.products(workspace_id, name, sku_code, is_defect_copy, original_product_id)
        VALUES (
          p_workspace_id,
          '.' || v_original_product.name,
          CASE WHEN v_original_product.sku_code IS NULL THEN NULL ELSE '.' || v_original_product.sku_code END,
          true,
          v_product_id
        )
        RETURNING id INTO v_defect_product_id;
      END IF;

      SELECT id INTO v_defect_warehouse_id
      FROM public.warehouses
      WHERE workspace_id = p_workspace_id AND is_default_defect = true
      LIMIT 1;

      IF v_defect_warehouse_id IS NULL THEN
        RAISE EXCEPTION 'Default defect warehouse not found';
      END IF;

      SELECT * INTO v_source_balance
      FROM public.product_balances
      WHERE workspace_id = p_workspace_id
        AND product_id = v_product_id
        AND warehouse_id = v_warehouse_id
      FOR UPDATE;
      v_unit_cost := COALESCE(v_source_balance.unit_cost, 0);

      INSERT INTO public.operation_items(operation_id, product_id, warehouse_id, quantity, unit_price, direction)
      VALUES
        (v_operation_id, v_product_id, v_warehouse_id, v_qty, v_unit_cost, 'out'),
        (v_operation_id, v_defect_product_id, v_defect_warehouse_id, v_qty, v_unit_cost, 'in');

      PERFORM public.update_product_balance(p_workspace_id, v_product_id, v_warehouse_id, -v_qty, NULL);
      PERFORM public.update_product_balance(p_workspace_id, v_defect_product_id, v_defect_warehouse_id, v_qty, v_unit_cost);
    ELSIF v_type = 'payment' THEN
      -- No operation items.
      NULL;
    ELSE
      RAISE EXCEPTION 'Unsupported operation type %', v_type;
    END IF;

    UPDATE public.operation_import_candidates
    SET status = 'committed',
        created_operation_id = v_operation_id
    WHERE id = v_candidate.id;

    INSERT INTO public.operation_import_committed_operations(workspace_id, import_id, candidate_id, operation_id)
    VALUES (p_workspace_id, p_import_id, v_candidate.id, v_operation_id);

    INSERT INTO public.operation_import_fingerprints(workspace_id, fingerprint, import_id, candidate_id, operation_id)
    VALUES (p_workspace_id, v_candidate.fingerprint, p_import_id, v_candidate.id, v_operation_id);

    v_created_ids := array_append(v_created_ids, v_operation_id);
  END LOOP;

  UPDATE public.operation_imports
  SET status = 'completed',
      completed_at = now(),
      summary = summary || jsonb_build_object(
        'committed', array_length(v_created_ids, 1),
        'operationIds', to_jsonb(v_created_ids)
      )
  WHERE id = p_import_id;

  RETURN jsonb_build_object(
    'importId', p_import_id,
    'operationIds', to_jsonb(v_created_ids),
    'committed', COALESCE(array_length(v_created_ids, 1), 0)
  );
END;
$$;
