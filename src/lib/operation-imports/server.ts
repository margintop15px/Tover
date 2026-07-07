import type { SupabaseClient } from "@supabase/supabase-js";
import type { OperationType } from "@/types/inventory";
import type {
  BuiltCandidate,
  ExistingDuplicate,
  OperationImportCandidateRecord,
  OperationImportDraft,
  RefData,
} from "./types";

export const OPERATION_IMPORT_CANDIDATE_PAGE_LIMIT = 50;
export const OPERATION_IMPORT_CANDIDATE_PAGE_MAX_LIMIT = 100;

const OPERATION_TYPES: OperationType[] = [
  "purchase",
  "sale",
  "return",
  "write_off",
  "transfer",
  "production",
  "defect",
  "payment",
  "inventory_adjustment",
];

type SummaryCandidateRow = {
  status: string;
  validation_errors?: unknown;
};

export type OperationImportCandidatePage = {
  limit: number;
  offset: number;
  total: number;
};

export type OperationImportLoadPreview = {
  rows: number;
  amount: number;
  missingAmountRows: number;
  typeCounts: { type: OperationType; count: number }[];
};

function candidateErrorCount(row: SummaryCandidateRow) {
  return Array.isArray(row.validation_errors) ? row.validation_errors.length : 0;
}

function finiteNumber(value: unknown) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function candidateOperation(row: {
  normalized_operation?: unknown;
  operation?: unknown;
}) {
  return (
    row.normalized_operation ||
    row.operation ||
    {}
  ) as OperationImportDraft;
}

function candidateValidationErrorCount(row: { validation_errors?: unknown }) {
  return Array.isArray(row.validation_errors) ? row.validation_errors.length : 0;
}

function operationAmount(operation: OperationImportDraft) {
  if (operation.type === "payment") {
    return finiteNumber(operation.paymentAmount);
  }

  const items = operation.items ?? [];
  if (items.length === 0) return null;

  let total = 0;
  for (const item of items) {
    const quantity = finiteNumber(item.quantity);
    const unitPrice = finiteNumber(item.unitPrice);
    if (quantity === null || unitPrice === null) return null;
    total += quantity * unitPrice;
  }
  return total;
}

export function normalizeOperationImportCandidatePage(
  limitValue: unknown,
  offsetValue: unknown
) {
  const rawLimit = Number(limitValue ?? OPERATION_IMPORT_CANDIDATE_PAGE_LIMIT);
  const rawOffset = Number(offsetValue ?? 0);
  const limit = Math.min(
    Math.max(Number.isFinite(rawLimit) ? Math.trunc(rawLimit) : 0, 1),
    OPERATION_IMPORT_CANDIDATE_PAGE_MAX_LIMIT
  );
  const offset = Math.max(Number.isFinite(rawOffset) ? Math.trunc(rawOffset) : 0, 0);

  return { limit, offset };
}

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
      .select("id, name, default_warehouse_id, is_import_default, created_at")
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
      defaultWarehouseId: row.default_warehouse_id ?? null,
      defaultWarehouseName: null,
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

export async function loadOperationImportLoadPreview(
  supabase: SupabaseClient,
  workspaceId: string,
  importId: string
): Promise<OperationImportLoadPreview> {
  const { data, error } = await supabase
    .from("operation_import_candidates")
    .select("status, validation_errors, operation, normalized_operation")
    .eq("workspace_id", workspaceId)
    .eq("import_id", importId)
    .eq("status", "approved");

  if (error) throw new Error(error.message);

  const typeCounts = new Map<OperationType, number>();
  let amount = 0;
  let missingAmountRows = 0;
  let rows = 0;

  for (const row of data || []) {
    if (candidateValidationErrorCount(row) > 0) continue;

    rows += 1;
    const operation = candidateOperation(row);
    if (operation.type && OPERATION_TYPES.includes(operation.type)) {
      typeCounts.set(operation.type, (typeCounts.get(operation.type) ?? 0) + 1);
    }

    const value = operationAmount(operation);
    if (value === null) {
      missingAmountRows += 1;
    } else {
      amount += value;
    }
  }

  return {
    rows,
    amount,
    missingAmountRows,
    typeCounts: OPERATION_TYPES.flatMap((type) => {
      const count = typeCounts.get(type) ?? 0;
      return count > 0 ? [{ type, count }] : [];
    }),
  };
}

export async function loadOperationImportReviewPage(
  supabase: SupabaseClient,
  workspaceId: string,
  importId: string,
  page: { limit: number; offset: number }
) {
  const [candidateResult, loadPreview] = await Promise.all([
    supabase
      .from("operation_import_candidates")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .eq("import_id", importId)
      .order("row_index", { ascending: true })
      .range(page.offset, page.offset + page.limit - 1),
    loadOperationImportLoadPreview(supabase, workspaceId, importId),
  ]);

  if (candidateResult.error) throw new Error(candidateResult.error.message);

  return {
    candidates: (candidateResult.data || []) as OperationImportCandidateRecord[],
    candidatePage: {
      limit: page.limit,
      offset: page.offset,
      total: candidateResult.count ?? 0,
    } satisfies OperationImportCandidatePage,
    loadPreview,
  };
}

export async function recalculateOperationImportSummary(
  supabase: SupabaseClient,
  workspaceId: string,
  importId: string
) {
  const [{ data: candidates, error: candidateError }, { data: links, error: linkError }] =
    await Promise.all([
      supabase
        .from("operation_import_candidates")
        .select("status, validation_errors")
        .eq("workspace_id", workspaceId)
        .eq("import_id", importId),
      supabase
        .from("operation_import_committed_operations")
        .select("operation_id, created_at")
        .eq("workspace_id", workspaceId)
        .eq("import_id", importId)
        .order("created_at", { ascending: true }),
    ]);

  if (candidateError) throw new Error(candidateError.message);
  if (linkError) throw new Error(linkError.message);

  const rows = (candidates || []) as SummaryCandidateRow[];
  const total = rows.length;
  const ready = rows.filter((row) => row.status === "ready").length;
  const approved = rows.filter((row) => row.status === "approved").length;
  const committed = rows.filter((row) => row.status === "committed").length;
  const needsReview = rows.filter(candidateErrorCount).length;
  const operationIds = (links || []).map((link) => link.operation_id);
  const status =
    total > 0 && committed === total
      ? "completed"
      : approved > 0
        ? "ready"
        : "needs_review";
  const summary = {
    total,
    ready,
    needsReview,
    approved,
    committed,
    operationIds,
  };

  const { error: updateError } = await supabase
    .from("operation_imports")
    .update({
      summary,
      status,
      completed_at: status === "completed" ? new Date().toISOString() : null,
    })
    .eq("workspace_id", workspaceId)
    .eq("id", importId);

  if (updateError) throw new Error(updateError.message);

  return { summary, status };
}
