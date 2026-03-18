-- Add unique constraint on product name per workspace (excluding defect copies)
-- Step 1: Deduplicate any existing non-defect products with duplicate names
DO $$
DECLARE
  rec RECORD;
BEGIN
  FOR rec IN
    SELECT id, name,
           ROW_NUMBER() OVER (PARTITION BY workspace_id, name ORDER BY created_at) AS rn
    FROM public.products
    WHERE is_defect_copy = false
      AND (workspace_id, name) IN (
        SELECT workspace_id, name FROM public.products
        WHERE is_defect_copy = false
        GROUP BY workspace_id, name HAVING COUNT(*) > 1
      )
  LOOP
    IF rec.rn > 1 THEN
      UPDATE public.products SET name = rec.name || ' (' || rec.rn || ')'
      WHERE id = rec.id;
    END IF;
  END LOOP;
END $$;

-- Step 2: Create partial unique index (defect copies are excluded)
CREATE UNIQUE INDEX products_workspace_id_name_key
  ON public.products(workspace_id, name)
  WHERE is_defect_copy = false;
