import type { SupabaseClient } from "@supabase/supabase-js";
import type { CandidateValidationError } from "@/lib/operation-imports/types";
import type {
  CreateOperationRequest,
  OperationType,
  Product,
  Warehouse,
} from "@/types/inventory";

export type MarketplaceCandidateStatus =
  | "needs_mapping"
  | "ready"
  | "approved"
  | "committing"
  | "ignored"
  | "committed";

export interface OzonCandidateItem {
  productId?: string | null;
  productName?: string | null;
  warehouseId?: string | null;
  warehouseName?: string | null;
  quantity?: number | null;
  unitPrice?: number | null;
  storeId?: string | null;
  direction?: "in" | "out";
  skuCode?: string | null;
  offerId?: string | null;
  ozonSku?: string | null;
  ozonProductId?: string | null;
  ozonWarehouseId?: string | null;
}

export type OzonCandidateSourceType =
  | "posting"
  | "return"
  | "finance"
  | "legal_entity_sale"
  | "removal"
  | "supply"
  | "stock_reconciliation"
  | "discounted_product"
  | "report";

export type OzonCandidateSupportStatus =
  | "commit_candidate"
  | "reporting_only"
  | "blocked";

export interface OzonCandidateOperation {
  type?: OperationType;
  operationDate?: string | null;
  comment?: string | null;
  items?: OzonCandidateItem[];
  sourceType?: OzonCandidateSourceType;
  supportStatus?: OzonCandidateSupportStatus;
  supportReason?: string | null;
}

export interface MarketplaceCandidateRow {
  id: string;
  workspace_id: string;
  connection_id: string;
  provider: "ozon";
  source_type: OzonCandidateSourceType;
  external_event_id: string;
  status: MarketplaceCandidateStatus;
  operation_type: OperationType | null;
  operation_date: string | null;
  confidence: number;
  operation: OzonCandidateOperation;
  normalized_operation: OzonCandidateOperation;
  validation_errors: CandidateValidationError[];
  raw_payload: Record<string, unknown>;
  created_operation_id: string | null;
  created_at: string;
  updated_at: string;
}

interface ProductRow {
  id: string;
  name: string;
  sku_code: string | null;
}

interface WarehouseRow {
  id: string;
  name: string;
}

interface WorkspaceSettingsRow {
  category_required: boolean;
  default_category_id: string | null;
  store_required: boolean;
  default_store_id: string | null;
}

const FINAL_STATUSES = new Set<MarketplaceCandidateStatus>([
  "approved",
  "committing",
  "ignored",
  "committed",
]);

const SUPPORTED_COMMIT_TYPES = new Set<OperationType>([
  "sale",
  "return",
  "write_off",
  "transfer",
  "defect",
  "inventory_adjustment",
]);

function normalizeItemDirection(
  type: OperationType,
  direction: OzonCandidateItem["direction"]
) {
  if (type === "transfer" || type === "production") {
    return direction || "out";
  }
  if (type === "return" || type === "inventory_adjustment") return "in";
  return "out";
}

export function getOzonCandidateOperation(
  row: Pick<MarketplaceCandidateRow, "normalized_operation" | "operation">
): OzonCandidateOperation {
  return (row.normalized_operation || row.operation || {}) as OzonCandidateOperation;
}

export function normalizeOzonCandidateOperation(
  operation: OzonCandidateOperation
): OzonCandidateOperation {
  const type = operation.type || "sale";

  return {
    type,
    operationDate: operation.operationDate || null,
    comment: operation.comment || null,
    sourceType: operation.sourceType,
    supportStatus: operation.supportStatus || "commit_candidate",
    supportReason: operation.supportReason || null,
    items: (operation.items || []).map((item) => ({
      ...item,
      productId: item.productId || null,
      productName: item.productName || null,
      warehouseId: item.warehouseId || null,
      warehouseName: item.warehouseName || null,
      quantity: numberOrNull(item.quantity),
      unitPrice: numberOrNull(item.unitPrice),
      storeId: item.storeId || null,
      direction: normalizeItemDirection(type, item.direction),
      skuCode: item.skuCode || item.offerId || item.ozonSku || null,
      offerId: item.offerId || null,
      ozonSku: item.ozonSku || null,
      ozonProductId: item.ozonProductId || null,
      ozonWarehouseId: item.ozonWarehouseId || null,
    })),
  };
}

export function validateOzonCandidateOperation(
  operation: OzonCandidateOperation
): CandidateValidationError[] {
  const normalized = normalizeOzonCandidateOperation(operation);
  const errors: CandidateValidationError[] = [];

  if (!isOzonCandidateCommitSupported(normalized)) {
    errors.push({
      field: "supportStatus",
      message: "This Ozon evidence is not eligible for operation commit",
      severity: "error",
    });
  }

  if (!normalized.type || !SUPPORTED_COMMIT_TYPES.has(normalized.type)) {
    errors.push({
      field: "type",
      message: "This Ozon evidence cannot be committed as a Tover operation",
      severity: "error",
    });
  }

  if (!normalized.operationDate || Number.isNaN(Date.parse(normalized.operationDate))) {
    errors.push({
      field: "operationDate",
      message: "Valid operation date is required",
      severity: "error",
    });
  }

  if (!normalized.items || normalized.items.length === 0) {
    errors.push({
      field: "items",
      message: "At least one item is required",
      severity: "error",
    });
  }

  normalized.items?.forEach((item, index) => {
    if (!item.productId) {
      errors.push({
        field: `items[${index}].productId`,
        message: "Product must be mapped",
        severity: "error",
      });
    }
    if (!item.warehouseId) {
      errors.push({
        field: `items[${index}].warehouseId`,
        message: "Warehouse must be mapped",
        severity: "error",
      });
    }
    if (!item.quantity || item.quantity <= 0) {
      errors.push({
        field: `items[${index}].quantity`,
        message: "Quantity must be positive",
        severity: "error",
      });
    }
    if (item.unitPrice != null && item.unitPrice < 0) {
      errors.push({
        field: `items[${index}].unitPrice`,
        message: "Price cannot be negative",
        severity: "error",
      });
    }
    if (
      normalized.type === "inventory_adjustment" &&
      (!item.unitPrice || item.unitPrice <= 0)
    ) {
      errors.push({
        field: `items[${index}].unitPrice`,
        message: "Unit cost must be positive",
        severity: "error",
      });
    }
  });

  if (normalized.type === "transfer") {
    const outItems = normalized.items?.filter((item) => item.direction === "out") || [];
    const inItems = normalized.items?.filter((item) => item.direction === "in") || [];
    if (outItems.length !== 1 || inItems.length !== 1) {
      errors.push({
        field: "items",
        message: "Transfer requires one source item and one destination item",
        severity: "error",
      });
    } else if (
      outItems[0].productId &&
      inItems[0].productId &&
      outItems[0].productId !== inItems[0].productId
    ) {
      errors.push({
        field: "items",
        message: "Transfer source and destination products must match",
        severity: "error",
      });
    }
  }

  return errors;
}

export function isOzonCandidateCommitSupported(operation: OzonCandidateOperation) {
  const normalized = normalizeOzonCandidateOperation(operation);
  return (
    normalized.supportStatus === "commit_candidate" &&
    Boolean(normalized.type && SUPPORTED_COMMIT_TYPES.has(normalized.type))
  );
}

export function statusFromValidation(
  errors: CandidateValidationError[]
): MarketplaceCandidateStatus {
  return errors.length > 0 ? "needs_mapping" : "ready";
}

export function canSyncUpdateCandidateStatus(status: MarketplaceCandidateStatus) {
  return !FINAL_STATUSES.has(status);
}

export function candidateSummary(rows: MarketplaceCandidateRow[]) {
  return {
    total: rows.length,
    needsMapping: rows.filter((row) => row.status === "needs_mapping").length,
    ready: rows.filter((row) => row.status === "ready").length,
    approved: rows.filter((row) => row.status === "approved").length,
    committing: rows.filter((row) => row.status === "committing").length,
    ignored: rows.filter((row) => row.status === "ignored").length,
    committed: rows.filter((row) => row.status === "committed").length,
  };
}

export function buildOperationRequest(
  candidate: MarketplaceCandidateRow
): CreateOperationRequest {
  const operation = normalizeOzonCandidateOperation(
    getOzonCandidateOperation(candidate)
  );
  const items = operation.items || [];

  if (operation.type === "transfer") {
    const source = items.find((item) => item.direction === "out") || items[0];
    const destination =
      items.find((item) => item.direction === "in") || items[1] || items[0];
    return {
      type: "transfer",
      operationDate: operation.operationDate || "",
      comment: operation.comment || undefined,
      productId: source?.productId || destination?.productId || "",
      sourceWarehouseId: source?.warehouseId || "",
      destinationWarehouseId: destination?.warehouseId || "",
      quantity: source?.quantity || destination?.quantity || 0,
    };
  }

  if (operation.type === "defect") {
    const item = items[0];
    return {
      type: "defect",
      operationDate: operation.operationDate || "",
      comment: operation.comment || undefined,
      productId: item?.productId || "",
      sourceWarehouseId: item?.warehouseId || "",
      quantity: item?.quantity || 0,
    };
  }

  return {
    type: operation.type || "sale",
    operationDate: operation.operationDate || "",
    comment: operation.comment || undefined,
    items: items.map((item) => ({
      productId: item.productId || "",
      warehouseId: item.warehouseId || "",
      quantity: item.quantity || 0,
      unitPrice: item.unitPrice ?? undefined,
      storeId: item.storeId || undefined,
      direction: item.direction,
      qualityStatus: "ordinary",
    })),
  };
}

export async function revalidateAndUpdateCandidate(
  supabase: SupabaseClient,
  candidate: MarketplaceCandidateRow,
  operation: OzonCandidateOperation,
  forcedStatus?: MarketplaceCandidateStatus
) {
  const normalized = normalizeOzonCandidateOperation(operation);
  const validationErrors = validateOzonCandidateOperation(normalized);
  const status = forcedStatus ?? statusFromValidation(validationErrors);

  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .update({
      operation: normalized,
      normalized_operation: normalized,
      operation_type: normalized.type ?? null,
      operation_date: normalized.operationDate ?? null,
      validation_errors: validationErrors,
      status,
    })
    .eq("id", candidate.id)
    .eq("workspace_id", candidate.workspace_id)
    .select("*")
    .single();

  if (error) throw new Error(error.message);
  return data as MarketplaceCandidateRow;
}

export async function applyItemMapping(
  supabase: SupabaseClient,
  candidate: MarketplaceCandidateRow,
  itemIndex: number,
  patch: Partial<Pick<OzonCandidateItem, "productId" | "warehouseId">>
) {
  if (candidate.status === "committed") {
    throw new Error("Committed candidates cannot be edited");
  }
  if (candidate.status === "committing") {
    throw new Error("Committing candidates cannot be edited");
  }

  const operation = normalizeOzonCandidateOperation(
    getOzonCandidateOperation(candidate)
  );
  const items = [...(operation.items || [])];
  if (!items[itemIndex]) throw new Error("Candidate item not found");

  items[itemIndex] = { ...items[itemIndex], ...patch };

  if (patch.productId) {
    await updateOzonProductMapping(supabase, candidate, items[itemIndex], patch.productId);
  }
  if (patch.warehouseId) {
    await updateOzonWarehouseMapping(
      supabase,
      candidate,
      items[itemIndex],
      patch.warehouseId
    );
  }

  return revalidateAndUpdateCandidate(supabase, candidate, { ...operation, items });
}

export async function createProductForCandidateItem(
  supabase: SupabaseClient,
  candidate: MarketplaceCandidateRow,
  itemIndex: number
) {
  const operation = normalizeOzonCandidateOperation(
    getOzonCandidateOperation(candidate)
  );
  const item = operation.items?.[itemIndex];
  if (!item) throw new Error("Candidate item not found");

  const productName = (item.productName || item.skuCode || item.offerId || "").trim();
  if (!productName) throw new Error("Ozon item does not include a product name");

  const skuCode = (item.offerId || item.skuCode || item.ozonSku || "").trim() || null;
  const product = await findOrCreateProduct(
    supabase,
    candidate.workspace_id,
    productName,
    skuCode
  );

  return applyItemMapping(supabase, candidate, itemIndex, {
    productId: product.id,
  });
}

export async function createWarehouseForCandidateItem(
  supabase: SupabaseClient,
  candidate: MarketplaceCandidateRow,
  itemIndex: number
) {
  const operation = normalizeOzonCandidateOperation(
    getOzonCandidateOperation(candidate)
  );
  const item = operation.items?.[itemIndex];
  if (!item) throw new Error("Candidate item not found");

  const warehouseName = (item.warehouseName || "").trim();
  if (!warehouseName) throw new Error("Ozon item does not include a warehouse name");

  const warehouse = await findOrCreateWarehouse(
    supabase,
    candidate.workspace_id,
    warehouseName
  );

  return applyItemMapping(supabase, candidate, itemIndex, {
    warehouseId: warehouse.id,
  });
}

export async function updateOzonProductMapping(
  supabase: SupabaseClient,
  candidate: MarketplaceCandidateRow,
  item: OzonCandidateItem,
  productId: string
) {
  const filters = [
    ["ozon_product_id", item.ozonProductId],
    ["offer_id", item.offerId || item.skuCode],
    ["sku", item.ozonSku],
  ].filter((filter): filter is [string, string] => Boolean(filter[1]));

  for (const [column, value] of filters) {
    const { error } = await supabase
      .from("ozon_products")
      .update({
        local_product_id: productId,
        mapping_status: "manual",
      })
      .eq("workspace_id", candidate.workspace_id)
      .eq("connection_id", candidate.connection_id)
      .eq(column, value);

    if (error) throw new Error(error.message);
  }
}

export async function updateOzonWarehouseMapping(
  supabase: SupabaseClient,
  candidate: MarketplaceCandidateRow,
  item: OzonCandidateItem,
  warehouseId: string
) {
  const filters = [
    ["ozon_warehouse_id", item.ozonWarehouseId],
    ["name", item.warehouseName],
  ].filter((filter): filter is [string, string] => Boolean(filter[1]));

  for (const [column, value] of filters) {
    const { error } = await supabase
      .from("ozon_warehouses")
      .update({
        local_warehouse_id: warehouseId,
        mapping_status: "manual",
      })
      .eq("workspace_id", candidate.workspace_id)
      .eq("connection_id", candidate.connection_id)
      .eq(column, value);

    if (error) throw new Error(error.message);
  }
}

async function findOrCreateProduct(
  supabase: SupabaseClient,
  workspaceId: string,
  name: string,
  skuCode: string | null
): Promise<Product> {
  if (skuCode) {
    const { data: bySku, error: skuError } = await supabase
      .from("products")
      .select("*")
      .eq("workspace_id", workspaceId)
      .eq("sku_code", skuCode)
      .eq("is_defect_copy", false)
      .maybeSingle();
    if (skuError) throw new Error(skuError.message);
    if (bySku) return productFromRow(bySku as ProductRow);
  }

  const { data: byName, error: nameError } = await supabase
    .from("products")
    .select("*")
    .eq("workspace_id", workspaceId)
    .ilike("name", name)
    .eq("is_defect_copy", false)
    .maybeSingle();
  if (nameError) throw new Error(nameError.message);
  if (byName) return productFromRow(byName as ProductRow);

  const settings = await loadWorkspaceSettings(supabase, workspaceId);
  if (settings.category_required && !settings.default_category_id) {
    throw new Error("Default category is required before creating Ozon products");
  }
  if (settings.store_required && !settings.default_store_id) {
    throw new Error("Default store is required before creating Ozon products");
  }

  const { data, error } = await supabase
    .from("products")
    .insert({
      workspace_id: workspaceId,
      name,
      sku_code: skuCode,
      category_id: settings.category_required ? settings.default_category_id : null,
      store_id: settings.store_required ? settings.default_store_id : null,
    })
    .select("*")
    .single();

  if (error) throw new Error(error.message);
  return productFromRow(data as ProductRow);
}

async function findOrCreateWarehouse(
  supabase: SupabaseClient,
  workspaceId: string,
  name: string
): Promise<Warehouse> {
  const { data: byName, error: findError } = await supabase
    .from("warehouses")
    .select("*")
    .eq("workspace_id", workspaceId)
    .ilike("name", name)
    .maybeSingle();

  if (findError) throw new Error(findError.message);
  if (byName) return warehouseFromRow(byName as WarehouseRow);

  const { data, error } = await supabase
    .from("warehouses")
    .insert({
      workspace_id: workspaceId,
      name,
    })
    .select("*")
    .single();

  if (error) throw new Error(error.message);
  return warehouseFromRow(data as WarehouseRow);
}

async function loadWorkspaceSettings(
  supabase: SupabaseClient,
  workspaceId: string
): Promise<WorkspaceSettingsRow> {
  const { data, error } = await supabase
    .from("workspace_settings")
    .select("category_required, default_category_id, store_required, default_store_id")
    .eq("workspace_id", workspaceId)
    .maybeSingle();

  if (error) throw new Error(error.message);

  return {
    category_required: data?.category_required ?? false,
    default_category_id: data?.default_category_id ?? null,
    store_required: data?.store_required ?? false,
    default_store_id: data?.default_store_id ?? null,
  };
}

function productFromRow(row: ProductRow): Product {
  return {
    id: row.id,
    name: row.name,
    skuCode: row.sku_code,
    categoryId: null,
    categoryName: null,
    storeId: null,
    storeName: null,
    isDefectCopy: false,
    createdAt: "",
  };
}

function warehouseFromRow(row: WarehouseRow): Warehouse {
  return {
    id: row.id,
    name: row.name,
    description: null,
    purpose: null,
    isDefaultDefect: false,
    isImportDefault: false,
    createdAt: "",
  };
}

function numberOrNull(value: unknown) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value.replace(",", "."));
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}
