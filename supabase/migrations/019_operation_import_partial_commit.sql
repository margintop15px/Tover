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
  v_all_operation_ids UUID[] := ARRAY[]::UUID[];
  v_sources JSONB := '[]'::jsonb;
  v_output JSONB := '{}'::jsonb;
  v_source_balance public.product_balances;
  v_unit_cost NUMERIC;
  v_original_product public.products;
  v_defect_product_id UUID;
  v_defect_warehouse_id UUID;
  v_total INTEGER;
  v_ready INTEGER;
  v_needs_review INTEGER;
  v_approved INTEGER;
  v_committed INTEGER;
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

  IF NOT EXISTS (
    SELECT 1
    FROM public.operation_import_candidates
    WHERE import_id = p_import_id
      AND workspace_id = p_workspace_id
      AND status = 'approved'
      AND validation_errors = '[]'::jsonb
  ) THEN
    RAISE EXCEPTION 'No approved candidates to commit';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM public.operation_import_candidates c
    JOIN public.operation_import_fingerprints f
      ON f.workspace_id = p_workspace_id
     AND f.fingerprint = c.fingerprint
    WHERE c.import_id = p_import_id
      AND c.workspace_id = p_workspace_id
      AND c.status = 'approved'
      AND c.validation_errors = '[]'::jsonb
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
      AND workspace_id = p_workspace_id
      AND status = 'approved'
      AND validation_errors = '[]'::jsonb
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

  SELECT
    COUNT(*)::INTEGER,
    COUNT(*) FILTER (WHERE status = 'ready')::INTEGER,
    COUNT(*) FILTER (WHERE validation_errors <> '[]'::jsonb)::INTEGER,
    COUNT(*) FILTER (WHERE status = 'approved')::INTEGER,
    COUNT(*) FILTER (WHERE status = 'committed')::INTEGER
  INTO v_total, v_ready, v_needs_review, v_approved, v_committed
  FROM public.operation_import_candidates
  WHERE import_id = p_import_id AND workspace_id = p_workspace_id;

  SELECT COALESCE(ARRAY_AGG(operation_id ORDER BY created_at), ARRAY[]::UUID[])
  INTO v_all_operation_ids
  FROM public.operation_import_committed_operations
  WHERE import_id = p_import_id AND workspace_id = p_workspace_id;

  UPDATE public.operation_imports
  SET status = CASE
        WHEN v_total > 0 AND v_committed = v_total THEN 'completed'
        WHEN v_approved > 0 THEN 'ready'
        ELSE 'needs_review'
      END,
      completed_at = CASE
        WHEN v_total > 0 AND v_committed = v_total THEN now()
        ELSE NULL
      END,
      summary = summary || jsonb_build_object(
        'total', v_total,
        'ready', v_ready,
        'needsReview', v_needs_review,
        'approved', v_approved,
        'committed', v_committed,
        'operationIds', to_jsonb(v_all_operation_ids)
      )
  WHERE id = p_import_id;

  RETURN jsonb_build_object(
    'importId', p_import_id,
    'operationIds', to_jsonb(v_created_ids),
    'committed', COALESCE(array_length(v_created_ids, 1), 0),
    'totalCommitted', v_committed
  );
END;
$$;
