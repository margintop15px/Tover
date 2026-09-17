-- Repair deployments whose operations_type_check still has the pre-007 types.
-- Replace the constraint atomically; existing operations and types are preserved.
SET LOCAL lock_timeout = '5s';

ALTER TABLE public.operations
  DROP CONSTRAINT IF EXISTS operations_type_check,
  ADD CONSTRAINT operations_type_check CHECK (type IN (
    'purchase', 'sale', 'return', 'write_off',
    'transfer', 'production', 'defect', 'payment',
    'inventory_adjustment'
  ));
