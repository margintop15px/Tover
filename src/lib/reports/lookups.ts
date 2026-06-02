import type { SupabaseClient } from "@supabase/supabase-js";

export interface ReportMovementLookupInput {
  product_id: string;
  warehouse_id: string;
  store_id: string | null;
}

export interface ReportProductLookup {
  id: string;
  name: string;
  sku_code: string | null;
  is_defect_copy: boolean;
  category_id: string | null;
  store_id: string | null;
}

export interface ReportNameLookup {
  id: string;
  name: string;
}

export interface ReportLookups {
  products: Map<string, ReportProductLookup>;
  warehouses: Map<string, ReportNameLookup>;
  stores: Map<string, ReportNameLookup>;
}

const unique = (values: (string | null | undefined)[]) => [
  ...new Set(values.filter((value): value is string => Boolean(value))),
];

function mapById<T extends { id: string }>(rows: T[] | null | undefined) {
  return new Map((rows || []).map((row) => [row.id, row]));
}

export async function loadReportLookups(
  supabase: SupabaseClient,
  workspaceId: string,
  movements: ReportMovementLookupInput[]
): Promise<ReportLookups> {
  const productIds = unique(movements.map((movement) => movement.product_id));
  const warehouseIds = unique(movements.map((movement) => movement.warehouse_id));
  const movementStoreIds = unique(movements.map((movement) => movement.store_id));

  const productPromise =
    productIds.length > 0
      ? supabase
          .from("products")
          .select("id, name, sku_code, is_defect_copy, category_id, store_id")
          .eq("workspace_id", workspaceId)
          .in("id", productIds)
      : Promise.resolve({ data: [], error: null });

  const warehousePromise =
    warehouseIds.length > 0
      ? supabase
          .from("warehouses")
          .select("id, name")
          .eq("workspace_id", workspaceId)
          .in("id", warehouseIds)
      : Promise.resolve({ data: [], error: null });

  const [productResult, warehouseResult] = await Promise.all([
    productPromise,
    warehousePromise,
  ]);

  if (productResult.error) throw productResult.error;
  if (warehouseResult.error) throw warehouseResult.error;

  const products = mapById(
    productResult.data as ReportProductLookup[] | null
  );
  const storeIds = unique([
    ...movementStoreIds,
    ...(productResult.data || []).map(
      (product) => (product as ReportProductLookup).store_id
    ),
  ]);

  const storeResult =
    storeIds.length > 0
      ? await supabase
          .from("stores")
          .select("id, name")
          .eq("workspace_id", workspaceId)
          .in("id", storeIds)
      : { data: [], error: null };

  if (storeResult.error) throw storeResult.error;

  return {
    products,
    warehouses: mapById(warehouseResult.data as ReportNameLookup[] | null),
    stores: mapById(storeResult.data as ReportNameLookup[] | null),
  };
}
