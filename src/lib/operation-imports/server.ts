import type { SupabaseClient } from "@supabase/supabase-js";
import type {
  BuiltCandidate,
  ExistingDuplicate,
  RefData,
} from "./types";

export async function loadOperationImportRefData(
  supabase: SupabaseClient,
  workspaceId: string
): Promise<RefData> {
  const [categories, products, warehouses, suppliers, stores] = await Promise.all([
    supabase
      .from("categories")
      .select("id, name, is_import_default, created_at")
      .eq("workspace_id", workspaceId)
      .order("name")
      .limit(1000),
    supabase
      .from("products")
      .select("id, name, sku_code, category_id, store_id, is_defect_copy, created_at, categories(name), stores(name)")
      .eq("workspace_id", workspaceId)
      .eq("is_defect_copy", false)
      .order("name")
      .limit(5000),
    supabase
      .from("warehouses")
      .select("id, name, description, purpose, is_default_defect, is_import_default, created_at")
      .eq("workspace_id", workspaceId)
      .order("name")
      .limit(1000),
    supabase
      .from("suppliers")
      .select("id, name, address, contact_info, is_import_default, created_at")
      .eq("workspace_id", workspaceId)
      .order("name")
      .limit(1000),
    supabase
      .from("stores")
      .select("id, name, is_import_default, created_at")
      .eq("workspace_id", workspaceId)
      .order("name")
      .limit(1000),
  ]);

  for (const result of [categories, products, warehouses, suppliers, stores]) {
    if (result.error) throw new Error(result.error.message);
  }

  return {
    categories: (categories.data || []).map((row) => ({
      id: row.id,
      name: row.name,
      isImportDefault: row.is_import_default,
      createdAt: row.created_at,
    })),
    products: (products.data || []).map((row) => ({
      id: row.id,
      name: row.name,
      skuCode: row.sku_code,
      categoryId: row.category_id,
      categoryName:
        (row.categories as unknown as { name: string } | null)?.name ?? null,
      storeId: row.store_id,
      storeName: (row.stores as unknown as { name: string } | null)?.name ?? null,
      isDefectCopy: row.is_defect_copy,
      createdAt: row.created_at,
    })),
    warehouses: (warehouses.data || []).map((row) => ({
      id: row.id,
      name: row.name,
      description: row.description,
      purpose: row.purpose,
      isDefaultDefect: row.is_default_defect,
      isImportDefault: row.is_import_default,
      createdAt: row.created_at,
    })),
    suppliers: (suppliers.data || []).map((row) => ({
      id: row.id,
      name: row.name,
      address: row.address,
      contactInfo: row.contact_info,
      isImportDefault: row.is_import_default,
      createdAt: row.created_at,
    })),
    stores: (stores.data || []).map((row) => ({
      id: row.id,
      name: row.name,
      isImportDefault: row.is_import_default,
      createdAt: row.created_at,
    })),
  };
}

export async function loadOperationImportDuplicates(
  supabase: SupabaseClient,
  workspaceId: string
): Promise<ExistingDuplicate[]> {
  const { data, error } = await supabase
    .from("operation_import_fingerprints")
    .select("fingerprint, operation_id, import_id")
    .eq("workspace_id", workspaceId)
    .limit(10000);

  if (error) throw new Error(error.message);

  return (data || []).map((row) => ({
    fingerprint: row.fingerprint,
    operationId: row.operation_id,
    importId: row.import_id,
  }));
}

export async function insertOperationImportCandidates(
  supabase: SupabaseClient,
  workspaceId: string,
  importId: string,
  candidates: BuiltCandidate[]
) {
  if (candidates.length === 0) return;

  const { error } = await supabase.from("operation_import_candidates").insert(
    candidates.map((candidate, index) => ({
      workspace_id: workspaceId,
      import_id: importId,
      row_index: index,
      fingerprint: candidate.fingerprint,
      status: candidate.status,
      confidence: candidate.confidence,
      source: candidate.source,
      raw: candidate.raw,
      operation: candidate.operation,
      normalized_operation: candidate.normalizedOperation,
      validation_errors: candidate.validationErrors,
      duplicate_of: candidate.duplicateOf ?? null,
    }))
  );

  if (error) throw new Error(error.message);
}
