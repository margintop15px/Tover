-- Operation-based reporting foundation: quality status, costed movement ledger,
-- report templates, and deterministic workspace rebuild.

ALTER TABLE public.stores
  ADD COLUMN IF NOT EXISTS default_warehouse_id UUID REFERENCES public.warehouses(id) ON DELETE SET NULL;

ALTER TABLE public.operation_items
  ADD COLUMN IF NOT EXISTS quality_status TEXT NOT NULL DEFAULT 'ordinary'
  CHECK (quality_status IN ('ordinary', 'defect'));

ALTER TABLE public.product_balances
  ADD COLUMN IF NOT EXISTS quality_status TEXT NOT NULL DEFAULT 'ordinary'
  CHECK (quality_status IN ('ordinary', 'defect'));

ALTER TABLE public.product_balances
  DROP CONSTRAINT IF EXISTS product_balances_workspace_id_product_id_warehouse_id_key;

CREATE UNIQUE INDEX IF NOT EXISTS product_balances_workspace_product_warehouse_quality_key
  ON public.product_balances(workspace_id, product_id, warehouse_id, quality_status);

CREATE TABLE IF NOT EXISTS public.inventory_movements (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  operation_id UUID NOT NULL REFERENCES public.operations(id) ON DELETE CASCADE,
  operation_item_id UUID REFERENCES public.operation_items(id) ON DELETE SET NULL,
  operation_date DATE NOT NULL,
  operation_type TEXT NOT NULL,
  product_id UUID NOT NULL REFERENCES public.products(id) ON DELETE RESTRICT,
  warehouse_id UUID NOT NULL REFERENCES public.warehouses(id) ON DELETE RESTRICT,
  store_id UUID REFERENCES public.stores(id) ON DELETE SET NULL,
  supplier_id UUID REFERENCES public.suppliers(id) ON DELETE SET NULL,
  quality_status TEXT NOT NULL DEFAULT 'ordinary'
    CHECK (quality_status IN ('ordinary', 'defect')),
  direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
  quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
  unit_cost NUMERIC(14,4) NOT NULL DEFAULT 0,
  total_cost NUMERIC(14,4) NOT NULL DEFAULT 0,
  invoice_unit_price NUMERIC(14,4),
  invoice_amount NUMERIC(14,4),
  balance_quantity_after NUMERIC(14,3) NOT NULL DEFAULT 0,
  is_negative_after BOOLEAN NOT NULL DEFAULT false,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_inventory_movements_workspace_date
  ON public.inventory_movements(workspace_id, operation_date, operation_id);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_product
  ON public.inventory_movements(workspace_id, product_id, operation_date);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_warehouse
  ON public.inventory_movements(workspace_id, warehouse_id, operation_date);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_store
  ON public.inventory_movements(workspace_id, store_id, operation_date)
  WHERE store_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_inventory_movements_quality
  ON public.inventory_movements(workspace_id, quality_status, operation_date);
CREATE INDEX IF NOT EXISTS idx_inventory_movements_supplier
  ON public.inventory_movements(workspace_id, supplier_id, operation_date)
  WHERE supplier_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.report_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workspace_id UUID NOT NULL REFERENCES public.organizations(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  source TEXT NOT NULL CHECK (source IN (
    'inventory_balances',
    'product_movement',
    'sales_volume',
    'turnover',
    'defects',
    'supplier_settlements'
  )),
  row_dimensions JSONB NOT NULL DEFAULT '[]'::jsonb,
  column_dimensions JSONB NOT NULL DEFAULT '[]'::jsonb,
  measures JSONB NOT NULL DEFAULT '[]'::jsonb,
  filters JSONB NOT NULL DEFAULT '{}'::jsonb,
  date_mode TEXT NOT NULL DEFAULT 'period' CHECK (date_mode IN ('as_of', 'period')),
  created_by UUID REFERENCES auth.users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(workspace_id, name)
);

DROP TRIGGER IF EXISTS set_report_templates_updated_at ON public.report_templates;

CREATE TRIGGER set_report_templates_updated_at
BEFORE UPDATE ON public.report_templates
FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

ALTER TABLE public.inventory_movements ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.report_templates ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "inventory_movements_select_member" ON public.inventory_movements;
DROP POLICY IF EXISTS "inventory_movements_write_admin" ON public.inventory_movements;
DROP POLICY IF EXISTS "report_templates_select_member" ON public.report_templates;
DROP POLICY IF EXISTS "report_templates_write_admin" ON public.report_templates;

CREATE POLICY "inventory_movements_select_member" ON public.inventory_movements
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "inventory_movements_write_admin" ON public.inventory_movements
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

CREATE POLICY "report_templates_select_member" ON public.report_templates
FOR SELECT TO authenticated
USING (public.app_is_org_member(workspace_id));

CREATE POLICY "report_templates_write_admin" ON public.report_templates
FOR ALL TO authenticated
USING (public.app_has_org_role(workspace_id, array['owner', 'admin']))
WITH CHECK (public.app_has_org_role(workspace_id, array['owner', 'admin']));

DROP FUNCTION IF EXISTS public.update_product_balance(UUID, UUID, UUID, NUMERIC, NUMERIC);
DROP FUNCTION IF EXISTS public.update_product_balance(UUID, UUID, UUID, NUMERIC, NUMERIC, TEXT);
DROP FUNCTION IF EXISTS public.process_purchase_balance(UUID, UUID, UUID, NUMERIC, NUMERIC);
DROP FUNCTION IF EXISTS public.process_purchase_balance(UUID, UUID, UUID, NUMERIC, NUMERIC, TEXT);
DROP FUNCTION IF EXISTS public.report_inventory_balances_at_date(UUID, DATE);
DROP FUNCTION IF EXISTS public.report_product_movement(UUID, DATE, DATE);

CREATE OR REPLACE FUNCTION public.update_product_balance(
  p_workspace_id UUID,
  p_product_id UUID,
  p_warehouse_id UUID,
  p_qty_delta NUMERIC,
  p_new_unit_cost NUMERIC DEFAULT NULL,
  p_quality_status TEXT DEFAULT 'ordinary'
)
RETURNS public.product_balances
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_balance public.product_balances;
BEGIN
  SELECT * INTO v_balance
  FROM public.product_balances
  WHERE workspace_id = p_workspace_id
    AND product_id = p_product_id
    AND warehouse_id = p_warehouse_id
    AND quality_status = COALESCE(p_quality_status, 'ordinary')
  FOR UPDATE;

  IF v_balance IS NULL THEN
    INSERT INTO public.product_balances (
      workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
    )
    VALUES (
      p_workspace_id,
      p_product_id,
      p_warehouse_id,
      COALESCE(p_quality_status, 'ordinary'),
      p_qty_delta,
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

CREATE OR REPLACE FUNCTION public.process_purchase_balance(
  p_workspace_id UUID,
  p_product_id UUID,
  p_warehouse_id UUID,
  p_purchase_qty NUMERIC,
  p_purchase_unit_price NUMERIC,
  p_quality_status TEXT DEFAULT 'ordinary'
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
    AND quality_status = COALESCE(p_quality_status, 'ordinary')
  FOR UPDATE;

  IF v_balance IS NULL THEN
    INSERT INTO public.product_balances (
      workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
    )
    VALUES (
      p_workspace_id,
      p_product_id,
      p_warehouse_id,
      COALESCE(p_quality_status, 'ordinary'),
      p_purchase_qty,
      p_purchase_unit_price
    )
    RETURNING * INTO v_balance;
  ELSE
    v_new_qty := v_balance.quantity + p_purchase_qty;
    IF v_balance.quantity > 0 AND v_new_qty > 0 THEN
      v_new_cost :=
        (v_balance.quantity * v_balance.unit_cost + p_purchase_qty * p_purchase_unit_price)
        / v_new_qty;
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

CREATE OR REPLACE FUNCTION public.rebuild_inventory_reporting(p_workspace_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
  v_op RECORD;
  v_item RECORD;
  v_balance public.product_balances;
  v_qty_after NUMERIC;
  v_new_cost NUMERIC;
  v_unit_cost NUMERIC;
  v_transfer_cost NUMERIC;
  v_production_total NUMERIC;
  v_output_qty NUMERIC;
BEGIN
  DELETE FROM public.inventory_movements WHERE workspace_id = p_workspace_id;
  DELETE FROM public.product_balances WHERE workspace_id = p_workspace_id;

  FOR v_op IN
    SELECT *
    FROM public.operations
    WHERE workspace_id = p_workspace_id
    ORDER BY operation_date ASC, created_at ASC, id ASC
  LOOP
    IF v_op.type = 'payment' THEN
      CONTINUE;
    END IF;

    IF v_op.type IN ('purchase', 'inventory_adjustment') THEN
      FOR v_item IN
        SELECT oi.*, p.store_id AS product_store_id
        FROM public.operation_items oi
        JOIN public.products p ON p.id = oi.product_id
        WHERE oi.operation_id = v_op.id
          AND oi.direction = 'in'
        ORDER BY oi.created_at ASC, oi.id ASC
      LOOP
        SELECT * INTO v_balance
        FROM public.product_balances
        WHERE workspace_id = p_workspace_id
          AND product_id = v_item.product_id
          AND warehouse_id = v_item.warehouse_id
          AND quality_status = v_item.quality_status
        FOR UPDATE;

        v_unit_cost := COALESCE(v_item.unit_price, 0);
        IF v_balance IS NULL THEN
          v_qty_after := v_item.quantity;
          v_new_cost := v_unit_cost;
          INSERT INTO public.product_balances (
            workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
          )
          VALUES (
            p_workspace_id, v_item.product_id, v_item.warehouse_id,
            v_item.quality_status, v_qty_after, v_new_cost
          );
        ELSE
          v_qty_after := v_balance.quantity + v_item.quantity;
          IF v_balance.quantity > 0 AND v_qty_after > 0 THEN
            v_new_cost :=
              (v_balance.quantity * v_balance.unit_cost + v_item.quantity * v_unit_cost)
              / v_qty_after;
          ELSE
            v_new_cost := v_unit_cost;
          END IF;
          UPDATE public.product_balances
          SET quantity = v_qty_after, unit_cost = v_new_cost
          WHERE id = v_balance.id;
        END IF;

        INSERT INTO public.inventory_movements (
          workspace_id, operation_id, operation_item_id, operation_date, operation_type,
          product_id, warehouse_id, store_id, supplier_id, quality_status, direction,
          quantity, unit_cost, total_cost, invoice_unit_price, invoice_amount,
          balance_quantity_after, is_negative_after
        )
        VALUES (
          p_workspace_id, v_op.id, v_item.id, v_op.operation_date, v_op.type,
          v_item.product_id, v_item.warehouse_id, COALESCE(v_item.store_id, v_item.product_store_id),
          v_op.supplier_id, v_item.quality_status, 'in',
          v_item.quantity, v_unit_cost, v_item.quantity * v_unit_cost,
          CASE WHEN v_op.type = 'purchase' THEN v_item.unit_price ELSE NULL END,
          CASE WHEN v_op.type = 'purchase' THEN v_item.quantity * COALESCE(v_item.unit_price, 0) ELSE NULL END,
          v_qty_after, v_qty_after < 0
        );
      END LOOP;
      CONTINUE;
    END IF;

    IF v_op.type IN ('sale', 'write_off', 'return') THEN
      FOR v_item IN
        SELECT oi.*, p.store_id AS product_store_id
        FROM public.operation_items oi
        JOIN public.products p ON p.id = oi.product_id
        WHERE oi.operation_id = v_op.id
        ORDER BY oi.created_at ASC, oi.id ASC
      LOOP
        SELECT * INTO v_balance
        FROM public.product_balances
        WHERE workspace_id = p_workspace_id
          AND product_id = v_item.product_id
          AND warehouse_id = v_item.warehouse_id
          AND quality_status = v_item.quality_status
        FOR UPDATE;

        v_unit_cost := COALESCE(v_balance.unit_cost, COALESCE(v_item.unit_price, 0));

        IF v_item.direction = 'out' THEN
          v_qty_after := COALESCE(v_balance.quantity, 0) - v_item.quantity;
        ELSE
          v_qty_after := COALESCE(v_balance.quantity, 0) + v_item.quantity;
        END IF;

        INSERT INTO public.product_balances (
          workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
        )
        VALUES (
          p_workspace_id, v_item.product_id, v_item.warehouse_id,
          v_item.quality_status, v_qty_after, v_unit_cost
        )
        ON CONFLICT (workspace_id, product_id, warehouse_id, quality_status)
        DO UPDATE SET quantity = EXCLUDED.quantity, unit_cost = EXCLUDED.unit_cost;

        INSERT INTO public.inventory_movements (
          workspace_id, operation_id, operation_item_id, operation_date, operation_type,
          product_id, warehouse_id, store_id, supplier_id, quality_status, direction,
          quantity, unit_cost, total_cost, balance_quantity_after, is_negative_after
        )
        VALUES (
          p_workspace_id, v_op.id, v_item.id, v_op.operation_date, v_op.type,
          v_item.product_id, v_item.warehouse_id, COALESCE(v_item.store_id, v_item.product_store_id),
          v_op.supplier_id, v_item.quality_status, v_item.direction,
          v_item.quantity, v_unit_cost, v_item.quantity * v_unit_cost,
          v_qty_after, v_qty_after < 0
        );
      END LOOP;
      CONTINUE;
    END IF;

    IF v_op.type IN ('transfer', 'defect') THEN
      v_transfer_cost := 0;
      FOR v_item IN
        SELECT oi.*, p.store_id AS product_store_id
        FROM public.operation_items oi
        JOIN public.products p ON p.id = oi.product_id
        WHERE oi.operation_id = v_op.id
        ORDER BY CASE WHEN oi.direction = 'out' THEN 0 ELSE 1 END, oi.created_at ASC, oi.id ASC
      LOOP
        SELECT * INTO v_balance
        FROM public.product_balances
        WHERE workspace_id = p_workspace_id
          AND product_id = v_item.product_id
          AND warehouse_id = v_item.warehouse_id
          AND quality_status = v_item.quality_status
        FOR UPDATE;

        IF v_item.direction = 'out' THEN
          v_unit_cost := COALESCE(v_balance.unit_cost, COALESCE(v_item.unit_price, 0));
          v_transfer_cost := v_unit_cost;
          v_qty_after := COALESCE(v_balance.quantity, 0) - v_item.quantity;
          v_new_cost := v_unit_cost;
        ELSE
          v_unit_cost := v_transfer_cost;
          v_qty_after := COALESCE(v_balance.quantity, 0) + v_item.quantity;
          IF v_balance IS NOT NULL AND v_balance.quantity > 0 AND v_qty_after > 0 THEN
            v_new_cost :=
              (v_balance.quantity * v_balance.unit_cost + v_item.quantity * v_unit_cost)
              / v_qty_after;
          ELSE
            v_new_cost := v_unit_cost;
          END IF;
        END IF;

        INSERT INTO public.product_balances (
          workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
        )
        VALUES (
          p_workspace_id, v_item.product_id, v_item.warehouse_id,
          v_item.quality_status, v_qty_after, v_new_cost
        )
        ON CONFLICT (workspace_id, product_id, warehouse_id, quality_status)
        DO UPDATE SET quantity = EXCLUDED.quantity, unit_cost = EXCLUDED.unit_cost;

        INSERT INTO public.inventory_movements (
          workspace_id, operation_id, operation_item_id, operation_date, operation_type,
          product_id, warehouse_id, store_id, supplier_id, quality_status, direction,
          quantity, unit_cost, total_cost, balance_quantity_after, is_negative_after
        )
        VALUES (
          p_workspace_id, v_op.id, v_item.id, v_op.operation_date, v_op.type,
          v_item.product_id, v_item.warehouse_id, COALESCE(v_item.store_id, v_item.product_store_id),
          v_op.supplier_id, v_item.quality_status, v_item.direction,
          v_item.quantity, v_unit_cost, v_item.quantity * v_unit_cost,
          v_qty_after, v_qty_after < 0
        );
      END LOOP;
      CONTINUE;
    END IF;

    IF v_op.type = 'production' THEN
      v_production_total := 0;
      FOR v_item IN
        SELECT oi.*, p.store_id AS product_store_id
        FROM public.operation_items oi
        JOIN public.products p ON p.id = oi.product_id
        WHERE oi.operation_id = v_op.id
          AND oi.direction = 'out'
        ORDER BY oi.created_at ASC, oi.id ASC
      LOOP
        SELECT * INTO v_balance
        FROM public.product_balances
        WHERE workspace_id = p_workspace_id
          AND product_id = v_item.product_id
          AND warehouse_id = v_item.warehouse_id
          AND quality_status = v_item.quality_status
        FOR UPDATE;

        v_unit_cost := COALESCE(v_balance.unit_cost, 0);
        v_production_total := v_production_total + v_item.quantity * v_unit_cost;
        v_qty_after := COALESCE(v_balance.quantity, 0) - v_item.quantity;

        INSERT INTO public.product_balances (
          workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
        )
        VALUES (
          p_workspace_id, v_item.product_id, v_item.warehouse_id,
          v_item.quality_status, v_qty_after, v_unit_cost
        )
        ON CONFLICT (workspace_id, product_id, warehouse_id, quality_status)
        DO UPDATE SET quantity = EXCLUDED.quantity, unit_cost = EXCLUDED.unit_cost;

        INSERT INTO public.inventory_movements (
          workspace_id, operation_id, operation_item_id, operation_date, operation_type,
          product_id, warehouse_id, store_id, supplier_id, quality_status, direction,
          quantity, unit_cost, total_cost, balance_quantity_after, is_negative_after
        )
        VALUES (
          p_workspace_id, v_op.id, v_item.id, v_op.operation_date, v_op.type,
          v_item.product_id, v_item.warehouse_id, COALESCE(v_item.store_id, v_item.product_store_id),
          v_op.supplier_id, v_item.quality_status, 'out',
          v_item.quantity, v_unit_cost, v_item.quantity * v_unit_cost,
          v_qty_after, v_qty_after < 0
        );
      END LOOP;

      SELECT COALESCE(SUM(quantity), 0) INTO v_output_qty
      FROM public.operation_items
      WHERE operation_id = v_op.id AND direction = 'in';

      FOR v_item IN
        SELECT oi.*, p.store_id AS product_store_id
        FROM public.operation_items oi
        JOIN public.products p ON p.id = oi.product_id
        WHERE oi.operation_id = v_op.id
          AND oi.direction = 'in'
        ORDER BY oi.created_at ASC, oi.id ASC
      LOOP
        v_unit_cost := CASE WHEN v_output_qty > 0 THEN v_production_total / v_output_qty ELSE 0 END;

        SELECT * INTO v_balance
        FROM public.product_balances
        WHERE workspace_id = p_workspace_id
          AND product_id = v_item.product_id
          AND warehouse_id = v_item.warehouse_id
          AND quality_status = v_item.quality_status
        FOR UPDATE;

        v_qty_after := COALESCE(v_balance.quantity, 0) + v_item.quantity;
        IF v_balance IS NOT NULL AND v_balance.quantity > 0 AND v_qty_after > 0 THEN
          v_new_cost :=
            (v_balance.quantity * v_balance.unit_cost + v_item.quantity * v_unit_cost)
            / v_qty_after;
        ELSE
          v_new_cost := v_unit_cost;
        END IF;

        INSERT INTO public.product_balances (
          workspace_id, product_id, warehouse_id, quality_status, quantity, unit_cost
        )
        VALUES (
          p_workspace_id, v_item.product_id, v_item.warehouse_id,
          v_item.quality_status, v_qty_after, v_new_cost
        )
        ON CONFLICT (workspace_id, product_id, warehouse_id, quality_status)
        DO UPDATE SET quantity = EXCLUDED.quantity, unit_cost = EXCLUDED.unit_cost;

        INSERT INTO public.inventory_movements (
          workspace_id, operation_id, operation_item_id, operation_date, operation_type,
          product_id, warehouse_id, store_id, supplier_id, quality_status, direction,
          quantity, unit_cost, total_cost, balance_quantity_after, is_negative_after
        )
        VALUES (
          p_workspace_id, v_op.id, v_item.id, v_op.operation_date, v_op.type,
          v_item.product_id, v_item.warehouse_id, COALESCE(v_item.store_id, v_item.product_store_id),
          v_op.supplier_id, v_item.quality_status, 'in',
          v_item.quantity, v_unit_cost, v_item.quantity * v_unit_cost,
          v_qty_after, v_qty_after < 0
        );
      END LOOP;
    END IF;
  END LOOP;
END;
$$;

CREATE OR REPLACE FUNCTION public.report_inventory_balances_at_date(
  p_workspace_id UUID,
  p_target_date DATE
)
RETURNS TABLE(
  product_id UUID,
  warehouse_id UUID,
  store_id UUID,
  quality_status TEXT,
  quantity NUMERIC,
  total_cost NUMERIC,
  has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  WITH movement AS (
    SELECT
      im.product_id,
      im.warehouse_id,
      im.store_id,
      im.quality_status,
      CASE WHEN im.direction = 'in' THEN im.quantity ELSE -im.quantity END AS signed_qty,
      CASE WHEN im.direction = 'in' THEN im.total_cost ELSE -im.total_cost END AS signed_cost,
      im.is_negative_after
    FROM public.inventory_movements im
    WHERE im.workspace_id = p_workspace_id
      AND im.operation_date <= p_target_date
  )
  SELECT
    movement.product_id,
    movement.warehouse_id,
    movement.store_id,
    movement.quality_status,
    SUM(movement.signed_qty) AS quantity,
    SUM(movement.signed_cost) AS total_cost,
    BOOL_OR(movement.is_negative_after) AS has_negative
  FROM movement
  GROUP BY
    movement.product_id,
    movement.warehouse_id,
    movement.store_id,
    movement.quality_status;
$$;

CREATE OR REPLACE FUNCTION public.report_product_movement(
  p_workspace_id UUID,
  p_from DATE,
  p_to DATE
)
RETURNS TABLE(
  product_id UUID,
  warehouse_id UUID,
  store_id UUID,
  quality_status TEXT,
  operation_type TEXT,
  direction TEXT,
  total_quantity NUMERIC,
  total_cost NUMERIC,
  has_negative BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
  SELECT
    im.product_id,
    im.warehouse_id,
    im.store_id,
    im.quality_status,
    im.operation_type,
    im.direction,
    SUM(im.quantity) AS total_quantity,
    SUM(im.total_cost) AS total_cost,
    BOOL_OR(im.is_negative_after) AS has_negative
  FROM public.inventory_movements
  AS im
  WHERE im.workspace_id = p_workspace_id
    AND im.operation_date >= p_from
    AND im.operation_date <= p_to
  GROUP BY
    im.product_id,
    im.warehouse_id,
    im.store_id,
    im.quality_status,
    im.operation_type,
    im.direction;
$$;

DO $$
DECLARE
  v_workspace_id UUID;
BEGIN
  FOR v_workspace_id IN
    SELECT DISTINCT workspace_id
    FROM public.operations
  LOOP
    PERFORM public.rebuild_inventory_reporting(v_workspace_id);
  END LOOP;
END;
$$;

NOTIFY pgrst, 'reload schema';
