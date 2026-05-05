-- Add audited initial inventory adjustment operation type.

ALTER TABLE public.operations
  DROP CONSTRAINT IF EXISTS operations_type_check;

ALTER TABLE public.operations
  ADD CONSTRAINT operations_type_check
  CHECK (type IN (
    'purchase', 'sale', 'return', 'write_off',
    'transfer', 'production', 'defect', 'payment',
    'inventory_adjustment'
  ));
