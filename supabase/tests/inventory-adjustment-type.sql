-- Run with psql -v ON_ERROR_STOP=1 -f in an empty, disposable database.
-- CREATE TABLE intentionally fails if an operations table already exists.
BEGIN;
CREATE TABLE public.operations (
  type text NOT NULL CHECK (type IN (
    'purchase', 'sale', 'return', 'write_off',
    'transfer', 'production', 'defect', 'payment'
  ))
);
INSERT INTO public.operations (type)
SELECT unnest(ARRAY['purchase', 'sale', 'return', 'write_off',
  'transfer', 'production', 'defect', 'payment']);

DO $$ BEGIN
  BEGIN
    INSERT INTO public.operations VALUES ('inventory_adjustment');
    RAISE EXCEPTION 'pre-repair constraint unexpectedly accepted inventory_adjustment';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END; $$;

\ir ../migrations/030_restore_inventory_adjustment_type.sql

DO $$ BEGIN
  ASSERT (SELECT count(*) = 8 FROM public.operations), 'existing operations changed';
  INSERT INTO public.operations VALUES ('inventory_adjustment');
  ASSERT (SELECT count(DISTINCT type) = 9 FROM public.operations), 'operation type missing';
  BEGIN
    INSERT INTO public.operations VALUES ('invalid');
    RAISE EXCEPTION 'constraint accepted an unknown operation type';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
END; $$;
ROLLBACK;
