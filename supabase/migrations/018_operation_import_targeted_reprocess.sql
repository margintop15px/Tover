CREATE OR REPLACE FUNCTION public.apply_operation_import_created_entity(
  p_workspace_id UUID,
  p_import_id UUID,
  p_entity_kind TEXT,
  p_entity_id UUID,
  p_entity_name TEXT DEFAULT NULL,
  p_sku_code TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_import_status TEXT;
  v_entity_name TEXT;
  v_sku_code TEXT;
  v_match TEXT;
  v_updated_ids UUID[] := ARRAY[]::UUID[];
  v_total INTEGER;
  v_ready INTEGER;
  v_needs_review INTEGER;
  v_approved INTEGER;
  v_committed INTEGER;
  v_summary JSONB;
BEGIN
  SELECT status INTO v_import_status
  FROM public.operation_imports
  WHERE id = p_import_id AND workspace_id = p_workspace_id;

  IF v_import_status IS NULL THEN
    RAISE EXCEPTION 'Operation import not found';
  END IF;

  IF v_import_status IN ('completed', 'committing') THEN
    RAISE EXCEPTION 'Committed imports cannot be reprocessed';
  END IF;

  IF p_entity_kind = 'product' THEN
    SELECT name, sku_code INTO v_entity_name, v_sku_code
    FROM public.products
    WHERE id = p_entity_id AND workspace_id = p_workspace_id;
  ELSIF p_entity_kind = 'supplier' THEN
    SELECT name, NULL::TEXT INTO v_entity_name, v_sku_code
    FROM public.suppliers
    WHERE id = p_entity_id AND workspace_id = p_workspace_id;
  ELSIF p_entity_kind = 'warehouse' THEN
    SELECT name, NULL::TEXT INTO v_entity_name, v_sku_code
    FROM public.warehouses
    WHERE id = p_entity_id AND workspace_id = p_workspace_id;
  ELSE
    RAISE EXCEPTION 'Unsupported created entity kind %', p_entity_kind;
  END IF;

  IF v_entity_name IS NULL THEN
    RAISE EXCEPTION 'Created entity not found';
  END IF;

  IF p_entity_kind = 'product' AND NULLIF(BTRIM(v_sku_code), '') IS NOT NULL THEN
    v_match := REPLACE(LOWER(BTRIM(v_sku_code)), 'ё', 'е');

    -- ponytail: targeted post-create patch; approval/full reprocess still recomputes fingerprints.
    WITH matched AS (
      SELECT id, operation, COALESCE(NULLIF(normalized_operation, '{}'::JSONB), operation) AS normalized_operation, validation_errors
      FROM public.operation_import_candidates
      WHERE import_id = p_import_id
        AND workspace_id = p_workspace_id
        AND status NOT IN ('approved', 'committed')
        AND NULLIF(operation #>> '{items,0,productId}', '') IS NULL
        AND (operation #>> '{items,0,createProduct}') IS DISTINCT FROM 'false'
        AND REPLACE(LOWER(BTRIM(COALESCE(operation #>> '{items,0,skuCode}', ''))), 'ё', 'е') = v_match
    ),
    patched AS (
      SELECT
        id,
        JSONB_SET(
          JSONB_SET(
            JSONB_SET(
              JSONB_SET(operation, '{items,0,productId}', TO_JSONB(p_entity_id::TEXT), true),
              '{items,0,productName}', TO_JSONB(v_entity_name), true
            ),
            '{items,0,skuCode}', TO_JSONB(v_sku_code), true
          ),
          '{items,0,createProduct}', 'false'::JSONB, true
        ) AS operation,
        JSONB_SET(
          JSONB_SET(
            JSONB_SET(
              JSONB_SET(normalized_operation, '{items,0,productId}', TO_JSONB(p_entity_id::TEXT), true),
              '{items,0,productName}', TO_JSONB(v_entity_name), true
            ),
            '{items,0,skuCode}', TO_JSONB(v_sku_code), true
          ),
          '{items,0,createProduct}', 'false'::JSONB, true
        ) AS normalized_operation,
        COALESCE(
          (
            SELECT JSONB_AGG(error_item.value)
            FROM JSONB_ARRAY_ELEMENTS(validation_errors) AS error_item(value)
            WHERE error_item.value->>'field' <> 'items[0].productId'
          ),
          '[]'::JSONB
        ) AS validation_errors
      FROM matched
    ),
    updated AS (
      UPDATE public.operation_import_candidates c
      SET operation = p.operation,
          normalized_operation = p.normalized_operation,
          validation_errors = p.validation_errors,
          status = CASE WHEN JSONB_ARRAY_LENGTH(p.validation_errors) = 0 THEN 'ready' ELSE 'needs_review' END
      FROM patched p
      WHERE c.id = p.id
      RETURNING c.id
    )
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::UUID[]) INTO v_updated_ids
    FROM updated;
  ELSIF p_entity_kind = 'supplier' THEN
    v_match := REPLACE(LOWER(BTRIM(v_entity_name)), 'ё', 'е');

    -- ponytail: targeted post-create patch; approval/full reprocess still recomputes fingerprints.
    WITH matched AS (
      SELECT id, operation, COALESCE(NULLIF(normalized_operation, '{}'::JSONB), operation) AS normalized_operation, validation_errors
      FROM public.operation_import_candidates
      WHERE import_id = p_import_id
        AND workspace_id = p_workspace_id
        AND status NOT IN ('approved', 'committed')
        AND NULLIF(operation->>'supplierId', '') IS NULL
        AND (operation->>'createSupplier') IS DISTINCT FROM 'false'
        AND REPLACE(LOWER(BTRIM(COALESCE(operation->>'supplierName', ''))), 'ё', 'е') = v_match
    ),
    patched AS (
      SELECT
        id,
        JSONB_SET(
          JSONB_SET(
            JSONB_SET(operation, '{supplierId}', TO_JSONB(p_entity_id::TEXT), true),
            '{supplierName}', TO_JSONB(v_entity_name), true
          ),
          '{createSupplier}', 'false'::JSONB, true
        ) AS operation,
        JSONB_SET(
          JSONB_SET(
            JSONB_SET(normalized_operation, '{supplierId}', TO_JSONB(p_entity_id::TEXT), true),
            '{supplierName}', TO_JSONB(v_entity_name), true
          ),
          '{createSupplier}', 'false'::JSONB, true
        ) AS normalized_operation,
        COALESCE(
          (
            SELECT JSONB_AGG(error_item.value)
            FROM JSONB_ARRAY_ELEMENTS(validation_errors) AS error_item(value)
            WHERE error_item.value->>'field' <> 'supplierId'
          ),
          '[]'::JSONB
        ) AS validation_errors
      FROM matched
    ),
    updated AS (
      UPDATE public.operation_import_candidates c
      SET operation = p.operation,
          normalized_operation = p.normalized_operation,
          validation_errors = p.validation_errors,
          status = CASE WHEN JSONB_ARRAY_LENGTH(p.validation_errors) = 0 THEN 'ready' ELSE 'needs_review' END
      FROM patched p
      WHERE c.id = p.id
      RETURNING c.id
    )
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::UUID[]) INTO v_updated_ids
    FROM updated;
  ELSIF p_entity_kind = 'warehouse' THEN
    v_match := REPLACE(LOWER(BTRIM(v_entity_name)), 'ё', 'е');

    -- ponytail: targeted post-create patch; approval/full reprocess still recomputes fingerprints.
    WITH matched AS (
      SELECT id, operation, COALESCE(NULLIF(normalized_operation, '{}'::JSONB), operation) AS normalized_operation, validation_errors
      FROM public.operation_import_candidates
      WHERE import_id = p_import_id
        AND workspace_id = p_workspace_id
        AND status NOT IN ('approved', 'committed')
        AND NULLIF(operation #>> '{items,0,warehouseId}', '') IS NULL
        AND (operation #>> '{items,0,createWarehouse}') IS DISTINCT FROM 'false'
        AND REPLACE(LOWER(BTRIM(COALESCE(operation #>> '{items,0,warehouseName}', ''))), 'ё', 'е') = v_match
    ),
    patched AS (
      SELECT
        id,
        JSONB_SET(
          JSONB_SET(
            JSONB_SET(operation, '{items,0,warehouseId}', TO_JSONB(p_entity_id::TEXT), true),
            '{items,0,warehouseName}', TO_JSONB(v_entity_name), true
          ),
          '{items,0,createWarehouse}', 'false'::JSONB, true
        ) AS operation,
        JSONB_SET(
          JSONB_SET(
            JSONB_SET(normalized_operation, '{items,0,warehouseId}', TO_JSONB(p_entity_id::TEXT), true),
            '{items,0,warehouseName}', TO_JSONB(v_entity_name), true
          ),
          '{items,0,createWarehouse}', 'false'::JSONB, true
        ) AS normalized_operation,
        COALESCE(
          (
            SELECT JSONB_AGG(error_item.value)
            FROM JSONB_ARRAY_ELEMENTS(validation_errors) AS error_item(value)
            WHERE error_item.value->>'field' <> 'items[0].warehouseId'
          ),
          '[]'::JSONB
        ) AS validation_errors
      FROM matched
    ),
    updated AS (
      UPDATE public.operation_import_candidates c
      SET operation = p.operation,
          normalized_operation = p.normalized_operation,
          validation_errors = p.validation_errors,
          status = CASE WHEN JSONB_ARRAY_LENGTH(p.validation_errors) = 0 THEN 'ready' ELSE 'needs_review' END
      FROM patched p
      WHERE c.id = p.id
      RETURNING c.id
    )
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::UUID[]) INTO v_updated_ids
    FROM updated;
  END IF;

  SELECT
    COUNT(*)::INTEGER,
    COUNT(*) FILTER (WHERE status = 'ready')::INTEGER,
    COUNT(*) FILTER (WHERE validation_errors <> '[]'::JSONB)::INTEGER,
    COUNT(*) FILTER (WHERE status = 'approved')::INTEGER,
    COUNT(*) FILTER (WHERE status = 'committed')::INTEGER
  INTO v_total, v_ready, v_needs_review, v_approved, v_committed
  FROM public.operation_import_candidates
  WHERE import_id = p_import_id AND workspace_id = p_workspace_id;

  v_summary := JSONB_BUILD_OBJECT(
    'total', v_total,
    'ready', v_ready,
    'needsReview', v_needs_review,
    'approved', v_approved,
    'committed', v_committed
  );

  UPDATE public.operation_imports
  SET status = CASE WHEN v_total > 0 AND v_approved = v_total THEN 'ready' ELSE 'needs_review' END,
      summary = v_summary,
      findings = findings || JSONB_BUILD_OBJECT(
        'targetedReprocessedAt', NOW(),
        'targetedReprocessedEntity', JSONB_BUILD_OBJECT(
          'kind', p_entity_kind,
          'id', p_entity_id,
          'name', v_entity_name,
          'skuCode', v_sku_code
        ),
        'targetedReprocessedCount', COALESCE(ARRAY_LENGTH(v_updated_ids, 1), 0)
      )
  WHERE id = p_import_id AND workspace_id = p_workspace_id;

  RETURN JSONB_BUILD_OBJECT(
    'summary', v_summary,
    'updatedCandidateIds', TO_JSONB(v_updated_ids)
  );
END;
$$;

GRANT EXECUTE ON FUNCTION public.apply_operation_import_created_entity(UUID, UUID, TEXT, UUID, TEXT, TEXT) TO authenticated;
