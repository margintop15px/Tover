import { createHash } from "node:crypto";
import type { SupabaseClient } from "@supabase/supabase-js";
import {
  canSyncUpdateCandidateStatus,
  normalizeOzonCandidateOperation,
  statusFromValidation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateStatus,
} from "@/lib/ozon/candidates";
import { OzonApiError, OzonClient, type OzonReadOnlyEndpoint } from "./client";
import { decryptOzonCredentials } from "./credentials";
import type {
  LocalProductRef,
  LocalWarehouseRef,
  OzonConnectionRecord,
  OzonSyncOptions,
  OzonSyncStepSummary,
  OzonSyncSummary,
} from "./types";

type JsonRecord = Record<string, unknown>;

interface ExternalProductRef {
  ozonProductId: string;
  offerId: string | null;
  sku: string | null;
  raw: JsonRecord;
}

interface MappingContext {
  productsByExternalKey: Map<string, LocalProductRef>;
  warehousesByName: Map<string, LocalWarehouseRef>;
  ozonProductMappings: Map<string, ExistingMapping>;
  ozonWarehouseMappings: Map<string, ExistingMapping>;
}

interface ExistingMapping {
  localId: string | null;
  status: "unmapped" | "auto_matched" | "manual" | "ignored";
}

const PRODUCT_PAGE_LIMIT = 1000;
const POSTING_PAGE_LIMIT = 100;
const FINANCE_ACCRUAL_PAGE_LIMIT = 200;
const DEFAULT_SYNC_DAYS = 30;

const PII_KEY_PATTERNS = [
  "address",
  "addressee",
  "buyer",
  "client",
  "contact",
  "customer",
  "email",
  "fio",
  "first_name",
  "full_name",
  "last_name",
  "middle_name",
  "mobile",
  "passport",
  "person",
  "phone",
  "recipient",
  "tel",
  "telephone",
  "user",
];

const PERSONAL_NAME_KEYS = new Set(["name"]);

const PERSONAL_CONTEXT_PATTERNS = [
  "buyer",
  "client",
  "contact",
  "customer",
  "person",
  "recipient",
  "user",
];

const SAFE_LEGAL_IDENTIFIER_KEYS = new Set([
  "buyer_company_name",
  "buyer_inn",
  "buyer_kpp",
  "company_name",
  "inn",
  "invoice_number",
  "kpp",
  "legal_company_name",
  "legal_entity_name",
  "organization_name",
]);

type OzonSyncRunStatus = "completed" | "completed_with_errors" | "failed";

export async function syncOzonConnection(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string,
  options: OzonSyncOptions = {}
) {
  const { data: connection, error: connectionError } = await supabase
    .from("marketplace_connections")
    .select("*")
    .eq("id", connectionId)
    .eq("workspace_id", workspaceId)
    .eq("provider", "ozon")
    .maybeSingle();

  if (connectionError) throw new Error(connectionError.message);
  if (!connection) throw new Error("Ozon connection not found");
  if (connection.status === "disabled") {
    throw new Error("Ozon connection is disabled");
  }

  const credentials = decryptOzonCredentials(
    (connection as OzonConnectionRecord).credential_ciphertext
  );
  const client = new OzonClient(credentials);
  const dateWindow = resolveSyncWindow(options);

  const { data: run, error: runError } = await supabase
    .from("marketplace_sync_runs")
    .insert({
      workspace_id: workspaceId,
      connection_id: connectionId,
      provider: "ozon",
      status: "running",
      date_from: dateWindow.dateFrom,
      date_to: dateWindow.dateTo,
    })
    .select("*")
    .single();

  if (runError || !run) {
    throw new Error(runError?.message ?? "Failed to create Ozon sync run");
  }

  await supabase
    .from("marketplace_connections")
    .update({
      last_sync_status: "running",
      last_sync_error: null,
    })
    .eq("id", connectionId);

  const summary: OzonSyncSummary = { errors: [] };
  let successfulSteps = 0;

  try {
    const mapping = await loadMappingContext(supabase, workspaceId, connectionId);

    successfulSteps += await runStep(summary, "warehouses", () =>
      syncWarehouses(supabase, client, workspaceId, connectionId, mapping)
    );
    successfulSteps += await runStep(summary, "products", () =>
      syncProducts(supabase, client, workspaceId, connectionId, mapping)
    );
    successfulSteps += await runStep(summary, "stocks", () =>
      syncStocks(supabase, client, workspaceId, connectionId, mapping)
    );
    successfulSteps += await runStep(summary, "postings", () =>
      syncPostings(
        supabase,
        client,
        workspaceId,
        connectionId,
        mapping,
        dateWindow.dateFrom,
        dateWindow.dateTo
      )
    );
    successfulSteps += await runStep(summary, "returns", () =>
      syncReturns(
        supabase,
        client,
        workspaceId,
        connectionId,
        mapping,
        dateWindow.dateFrom,
        dateWindow.dateTo
      )
    );
    successfulSteps += await runStep(summary, "finance", () =>
      syncFinance(
        supabase,
        client,
        workspaceId,
        connectionId,
        dateWindow.dateFrom,
        dateWindow.dateTo
      )
    );
    successfulSteps += await runStep(summary, "legalEntities", () =>
      syncLegalEntities(
        supabase,
        client,
        workspaceId,
        connectionId,
        mapping,
        dateWindow.dateFrom,
        dateWindow.dateTo
      )
    );
    successfulSteps += await runStep(summary, "reports", () =>
      syncFinanceReports(
        supabase,
        client,
        workspaceId,
        connectionId,
        dateWindow.dateFrom,
        dateWindow.dateTo
      )
    );
    successfulSteps += await runStep(summary, "removals", () =>
      syncRemovals(
        supabase,
        client,
        workspaceId,
        connectionId,
        mapping,
        dateWindow.dateFrom,
        dateWindow.dateTo
      )
    );
    successfulSteps += await runStep(summary, "supplies", () =>
      syncSupplies(supabase, client, workspaceId, connectionId, mapping)
    );
    successfulSteps += await runStep(summary, "analytics", () =>
      syncStockAnalytics(supabase, client, workspaceId, connectionId, mapping)
    );
    successfulSteps += await runStep(summary, "discountedProducts", () =>
      syncDiscountedProducts(supabase, client, workspaceId, connectionId, mapping)
    );

    const status = resolveSyncStatus(successfulSteps, summary);
    const error = summary.errors.length > 0 ? summary.errors.join("; ") : null;

    await supabase
      .from("marketplace_sync_runs")
      .update({
        status,
        completed_at: new Date().toISOString(),
        summary,
        error,
      })
      .eq("id", run.id);

    await supabase
      .from("marketplace_connections")
      .update({
        status: status === "failed" ? "error" : "connected",
        last_sync_at: new Date().toISOString(),
        last_sync_status: status,
        last_sync_error: error,
        health: {
          lastSyncRunId: run.id,
          lastSyncSummary: summary,
        },
      })
      .eq("id", connectionId);

    return { runId: run.id as string, status, summary };
  } catch (error) {
    const message = error instanceof Error ? error.message : "Ozon sync failed";

    await supabase
      .from("marketplace_sync_runs")
      .update({
        status: "failed",
        completed_at: new Date().toISOString(),
        summary,
        error: message,
      })
      .eq("id", run.id);

    await supabase
      .from("marketplace_connections")
      .update({
        status: "error",
        last_sync_status: "failed",
        last_sync_error: message,
      })
      .eq("id", connectionId);

    throw error;
  }
}

async function runStep(
  summary: OzonSyncSummary,
  key: Exclude<keyof OzonSyncSummary, "errors">,
  fn: () => Promise<OzonSyncStepSummary>
) {
  try {
    summary[key] = await fn();
    return 1;
  } catch (error) {
    summary.errors.push(formatError(error));
    return 0;
  }
}

function resolveSyncStatus(
  successfulSteps: number,
  summary: OzonSyncSummary
): OzonSyncRunStatus {
  if (successfulSteps === 0) return "failed";
  return summary.errors.length > 0 ? "completed_with_errors" : "completed";
}

function resolveSyncWindow(options: OzonSyncOptions) {
  const dateTo = options.dateTo ? new Date(options.dateTo) : new Date();
  const dateFrom = options.dateFrom
    ? new Date(options.dateFrom)
    : new Date(dateTo.getTime() - DEFAULT_SYNC_DAYS * 24 * 60 * 60 * 1000);

  return {
    dateFrom: dateFrom.toISOString(),
    dateTo: dateTo.toISOString(),
  };
}

async function loadMappingContext(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string
): Promise<MappingContext> {
  const [productsResult, warehousesResult, ozonProductsResult, ozonWarehousesResult] =
    await Promise.all([
      supabase
        .from("products")
        .select("id, name, sku_code")
        .eq("workspace_id", workspaceId)
        .eq("is_defect_copy", false),
      supabase
        .from("warehouses")
        .select("id, name")
        .eq("workspace_id", workspaceId),
      supabase
        .from("ozon_products")
        .select("ozon_product_id, local_product_id, mapping_status")
        .eq("workspace_id", workspaceId)
        .eq("connection_id", connectionId),
      supabase
        .from("ozon_warehouses")
        .select("ozon_warehouse_id, local_warehouse_id, mapping_status")
        .eq("workspace_id", workspaceId)
        .eq("connection_id", connectionId),
    ]);

  for (const result of [
    productsResult,
    warehousesResult,
    ozonProductsResult,
    ozonWarehousesResult,
  ]) {
    if (result.error) throw new Error(result.error.message);
  }

  const productsByExternalKey = new Map<string, LocalProductRef>();
  for (const product of (productsResult.data || []) as LocalProductRef[]) {
    if (product.sku_code) {
      productsByExternalKey.set(normalizeKey(product.sku_code), product);
    }
  }

  const warehousesByName = new Map<string, LocalWarehouseRef>();
  for (const warehouse of (warehousesResult.data || []) as LocalWarehouseRef[]) {
    warehousesByName.set(normalizeKey(warehouse.name), warehouse);
  }

  const ozonProductMappings = new Map<string, ExistingMapping>();
  for (const product of (ozonProductsResult.data || []) as JsonRecord[]) {
    const id = toStringValue(product.ozon_product_id);
    if (!id) continue;
    ozonProductMappings.set(id, {
      localId: toStringValue(product.local_product_id),
      status: mappingStatus(product.mapping_status),
    });
  }

  const ozonWarehouseMappings = new Map<string, ExistingMapping>();
  for (const warehouse of (ozonWarehousesResult.data || []) as JsonRecord[]) {
    const id = toStringValue(warehouse.ozon_warehouse_id);
    if (!id) continue;
    ozonWarehouseMappings.set(id, {
      localId: toStringValue(warehouse.local_warehouse_id),
      status: mappingStatus(warehouse.mapping_status),
    });
  }

  return {
    productsByExternalKey,
    warehousesByName,
    ozonProductMappings,
    ozonWarehouseMappings,
  };
}

async function syncWarehouses(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
): Promise<OzonSyncStepSummary> {
  const response = await client.request<JsonRecord>("/v2/warehouse/list", {});
  const warehouses = extractItems(response, ["warehouses", "items"]);
  const rows = warehouses
    .map((item) => toWarehouseRow(item, workspaceId, connectionId, mapping))
    .filter(Boolean) as JsonRecord[];

  await upsertRows(supabase, "ozon_warehouses", rows, "connection_id,ozon_warehouse_id");

  return { fetched: warehouses.length };
}

function toWarehouseRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const warehouseId = toStringValue(
    item.warehouse_id ?? item.id ?? item.delivery_method_id ?? item.name
  );
  const name = toStringValue(item.name ?? item.warehouse_name);
  if (!warehouseId || !name) return null;

  const preserved = mapping.ozonWarehouseMappings.get(warehouseId);
  const localWarehouse = mapping.warehousesByName.get(normalizeKey(name));
  const autoLocalId = localWarehouse?.id ?? null;
  const mappingResult = resolveMapping(preserved, autoLocalId);

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    ozon_warehouse_id: warehouseId,
    name,
    fulfillment_schema: toStringValue(
      item.fulfillment_schema ?? item.warehouse_type ?? item.type
    ),
    status: toStringValue(item.status),
    raw_payload: sanitizeOzonPayload(item),
    local_warehouse_id: mappingResult.localId,
    mapping_status: mappingResult.status,
    synced_at: new Date().toISOString(),
  };
}

async function syncProducts(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
): Promise<OzonSyncStepSummary> {
  const refs = await fetchProductRefs(client);
  const [details, prices, attributes] = await Promise.all([
    fetchProductDetails(client, refs).catch(() => []),
    fetchProductPrices(client, refs).catch(() => []),
    fetchProductAttributes(client).catch(() => []),
  ]);

  const detailMap = indexExternalProducts(details);
  const priceMap = indexExternalProducts(prices);
  const attributeMap = indexExternalProducts(attributes);

  const rows = refs.map((ref) => {
    const detail = lookupExternalProduct(detailMap, ref);
    const price = lookupExternalProduct(priceMap, ref);
    const attributesItem = lookupExternalProduct(attributeMap, ref);
    return toProductRow(
      workspaceId,
      connectionId,
      mapping,
      ref,
      detail,
      price,
      attributesItem
    );
  });

  await upsertRows(supabase, "ozon_products", rows, "connection_id,ozon_product_id");

  return { fetched: refs.length };
}

async function fetchProductRefs(client: OzonClient) {
  const refs: ExternalProductRef[] = [];
  let lastId = "";

  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>("/v3/product/list", {
      filter: { visibility: "ALL" },
      limit: PRODUCT_PAGE_LIMIT,
      last_id: lastId,
    });
    const root = unwrapResult(response);
    const items = extractItems(root, ["items", "products"]);

    for (const item of items) {
      const ref = toProductRef(item);
      if (ref) refs.push(ref);
    }

    const nextLastId = toStringValue(root.last_id ?? root.cursor ?? response.cursor);
    if (!nextLastId || nextLastId === lastId || items.length === 0) break;
    lastId = nextLastId;
  }

  return refs;
}

async function fetchProductDetails(client: OzonClient, refs: ExternalProductRef[]) {
  const details: JsonRecord[] = [];

  for (const chunk of chunkArray(refs, 100)) {
    const productIds = chunk
      .map((ref) => numericId(ref.ozonProductId))
      .filter((value): value is number => value !== null);
    const offerIds = chunk
      .map((ref) => ref.offerId)
      .filter((value): value is string => Boolean(value));

    const response = await client.request<JsonRecord>("/v3/product/info/list", {
      product_id: productIds,
      offer_id: productIds.length > 0 ? [] : offerIds,
    });

    details.push(...(extractItems(response, ["items", "products"]) as JsonRecord[]));
  }

  return details;
}

async function fetchProductPrices(client: OzonClient, refs: ExternalProductRef[]) {
  const prices: JsonRecord[] = [];
  let cursor = "";

  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>("/v5/product/info/prices", {
      filter: {
        visibility: "ALL",
      },
      limit: PRODUCT_PAGE_LIMIT,
      cursor,
    });
    const root = unwrapResult(response);
    const items = extractItems(root, ["items", "products"]);
    prices.push(...(items as JsonRecord[]));
    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || nextCursor === cursor || items.length === 0) break;
    cursor = nextCursor;
  }

  if (prices.length > 0 || refs.length === 0) return prices;

  for (const chunk of chunkArray(refs, 100)) {
    const offerIds = chunk
      .map((ref) => ref.offerId)
      .filter((value): value is string => Boolean(value));
    const response = await client.request<JsonRecord>("/v5/product/info/prices", {
      filter: { offer_id: offerIds },
      limit: PRODUCT_PAGE_LIMIT,
    });
    prices.push(...(extractItems(response, ["items", "products"]) as JsonRecord[]));
  }

  return prices;
}

async function fetchProductAttributes(client: OzonClient) {
  const attributes: JsonRecord[] = [];
  let lastId = "";

  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>("/v4/product/info/attributes", {
      filter: { visibility: "ALL" },
      limit: PRODUCT_PAGE_LIMIT,
      last_id: lastId,
    });
    const root = unwrapResult(response);
    const items = extractItems(root, ["items", "products"]);
    attributes.push(...(items as JsonRecord[]));

    const nextLastId = toStringValue(root.last_id ?? root.cursor ?? response.cursor);
    if (!nextLastId || nextLastId === lastId || items.length === 0) break;
    lastId = nextLastId;
  }

  return attributes;
}

function toProductRef(value: unknown): ExternalProductRef | null {
  const item = toRecord(value);
  const ozonProductId = toStringValue(
    item.product_id ?? item.id ?? item.sku ?? item.offer_id
  );
  if (!ozonProductId) return null;

  return {
    ozonProductId,
    offerId: toStringValue(item.offer_id),
    sku: toStringValue(item.sku),
    raw: item,
  };
}

function toProductRow(
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  ref: ExternalProductRef,
  detail: JsonRecord | null,
  price: JsonRecord | null,
  attributes: JsonRecord | null
) {
  const source = {
    ...ref.raw,
    ...(detail ?? {}),
    ...(price ?? {}),
  };
  const offerId = toStringValue(source.offer_id) ?? ref.offerId;
  const sku = toStringValue(source.sku) ?? ref.sku;
  const barcodes = asArray(source.barcodes);
  const localProductId = findLocalProductId(mapping, ref.ozonProductId, [
    offerId,
    sku,
    ...barcodes.map((barcode) => toStringValue(barcode)),
  ]);
  const preserved = mapping.ozonProductMappings.get(ref.ozonProductId);
  const mappingResult = resolveMapping(preserved, localProductId);
  const statuses = toRecord(source.statuses);
  const visibility = toRecord(source.visibility_details);

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    ozon_product_id: ref.ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(source.name),
    currency_code: toStringValue(source.currency_code ?? source.currency),
    price: toNumberValue(source.price),
    old_price: toNumberValue(source.old_price),
    min_price: toNumberValue(source.min_price),
    status: toStringValue(statuses.status ?? source.status),
    visibility: Object.keys(visibility).length > 0 ? JSON.stringify(visibility) : null,
    description_category_id: toStringValue(source.description_category_id),
    type_id: toStringValue(source.type_id),
    barcodes,
    images: [
      ...asArray(source.primary_image),
      ...asArray(source.images),
      ...asArray(source.color_image),
    ],
    attributes: asArray(attributes?.attributes ?? attributes?.items ?? []),
    raw_payload: sanitizeOzonPayload({ source, attributes }),
    local_product_id: mappingResult.localId,
    mapping_status: mappingResult.status,
    synced_at: new Date().toISOString(),
  };
}

async function syncStocks(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
): Promise<OzonSyncStepSummary> {
  const rows: JsonRecord[] = [];
  let cursor = "";
  const snapshotAt = new Date().toISOString();

  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>("/v4/product/info/stocks", {
      filter: { visibility: "ALL" },
      limit: PRODUCT_PAGE_LIMIT,
      cursor,
    });
    const root = unwrapResult(response);
    const items = extractItems(root, ["items", "products"]);

    for (const item of items) {
      rows.push(
        ...toStockRows(item, workspaceId, connectionId, mapping, snapshotAt)
      );
    }

    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || nextCursor === cursor || items.length === 0) break;
    cursor = nextCursor;
  }

  await insertRows(supabase, "ozon_stock_snapshots", rows);

  return { fetched: rows.length };
}

function toStockRows(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  snapshotAt: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(
    item.product_id ?? item.id ?? item.sku ?? item.offer_id
  );
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);
  const stocks = asArray(item.stocks);
  const stockItems = stocks.length > 0 ? stocks : [item];

  return stockItems.map((stock) => {
    const stockRecord = toRecord(stock);
    const warehouseName = toStringValue(
      stockRecord.warehouse_name ?? stockRecord.source ?? stockRecord.name
    );
    const warehouseId = toStringValue(
      stockRecord.warehouse_id ?? stockRecord.source ?? warehouseName
    );

    return {
      workspace_id: workspaceId,
      connection_id: connectionId,
      snapshot_at: snapshotAt,
      ozon_product_id: ozonProductId,
      offer_id: offerId,
      sku,
      warehouse_name: warehouseName,
      ozon_warehouse_id: warehouseId,
      fulfillment_schema: toStringValue(
        stockRecord.fulfillment_schema ?? stockRecord.source
      ),
      present: toNumberValue(stockRecord.present ?? stockRecord.quantity) ?? 0,
      reserved: toNumberValue(stockRecord.reserved) ?? 0,
      raw_payload: sanitizeOzonPayload({ item, stock: stockRecord }),
      local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
      local_warehouse_id: findLocalWarehouseId(mapping, warehouseId, warehouseName),
    };
  });
}

async function syncPostings(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string
): Promise<OzonSyncStepSummary> {
  let fetched = 0;
  let createdCandidates = 0;

  for (const schema of ["fbs", "fbo"] as const) {
    const endpoint =
      schema === "fbs" ? "/v4/posting/fbs/list" : "/v3/posting/fbo/list";
    let cursor = "";

    for (let page = 0; page < 200; page += 1) {
      const response = await client.request<JsonRecord>(endpoint, {
        cursor,
        filter: {
          since: dateFrom,
          to: dateTo,
        },
        limit: POSTING_PAGE_LIMIT,
        sort_dir: "ASC",
        translit: false,
        with: {
          analytics_data: true,
          financial_data: true,
        },
      });
      const items = extractItems(response, ["postings", "items"]);
      fetched += items.length;

      for (const posting of items) {
        const result = await upsertPosting(
          supabase,
          workspaceId,
          connectionId,
          schema,
          posting,
          mapping
        );
        createdCandidates += result.createdCandidate ? 1 : 0;
      }

      if (items.length < POSTING_PAGE_LIMIT) break;
      const root = unwrapResult(response);
      const nextCursor = toStringValue(root.cursor ?? response.cursor);
      if (!nextCursor || nextCursor === cursor) break;
      cursor = nextCursor;
    }
  }

  return { fetched, createdCandidates };
}

async function upsertPosting(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string,
  schema: "fbs" | "fbo",
  value: unknown,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const postingNumber = toStringValue(
    item.posting_number ?? item.postingNumber ?? item.order_number
  );
  if (!postingNumber) return { createdCandidate: false };

  const deliveryMethod = toRecord(item.delivery_method);
  const cancellation = toRecord(item.cancellation);
  const warehouseName = toStringValue(
    deliveryMethod.warehouse ?? item.warehouse_name ?? item.warehouse
  );
  const localWarehouseId = findLocalWarehouseId(mapping, null, warehouseName);

  const postingRow = {
    workspace_id: workspaceId,
    connection_id: connectionId,
    posting_schema: schema,
    posting_number: postingNumber,
    order_id: toStringValue(item.order_id),
    status: toStringValue(item.status),
    substatus: toStringValue(item.substatus),
    in_process_at: toIsoString(item.in_process_at),
    shipment_date: toIsoString(item.shipment_date),
    delivered_at: toIsoString(item.delivered_at),
    cancelled_at: toIsoString(item.cancelled_at ?? cancellation.cancelled_at),
    warehouse_name: warehouseName,
    financial_data: sanitizeOzonPayload(item.financial_data ?? {}),
    analytics_data: sanitizeOzonPayload(item.analytics_data ?? {}),
    raw_payload: sanitizeOzonPayload(item),
    local_warehouse_id: localWarehouseId,
    synced_at: new Date().toISOString(),
  };

  const { data: posting, error } = await supabase
    .from("ozon_postings")
    .upsert(postingRow, {
      onConflict: "connection_id,posting_schema,posting_number",
    })
    .select("*")
    .single();

  if (error || !posting) {
    throw new Error(error?.message ?? "Failed to save Ozon posting");
  }

  await supabase.from("ozon_posting_items").delete().eq("posting_id", posting.id);

  const products = asArray(item.products);
  const itemRows = products.map((product) =>
    toPostingItemRow(
      product,
      workspaceId,
      connectionId,
      posting.id as string,
      mapping
    )
  );

  await insertRows(supabase, "ozon_posting_items", itemRows);

  const candidate = buildPostingCandidate(
    posting as JsonRecord,
    itemRows,
    schema,
    postingNumber
  );

  if (!candidate) return { createdCandidate: false };

  const candidateRow = await upsertCandidatePreservingReview(supabase, candidate);

  await supabase
    .from("ozon_postings")
    .update({ operation_candidate_id: candidateRow.id })
    .eq("id", posting.id);

  return { createdCandidate: true };
}

function toPostingItemRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  postingId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(
    item.product_id ?? item.sku ?? item.offer_id
  );
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    posting_id: postingId,
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name),
    quantity: toNumberValue(item.quantity) ?? 0,
    price: toNumberValue(item.price),
    currency_code: toStringValue(item.currency_code ?? item.currency),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
  };
}

function buildPostingCandidate(
  posting: JsonRecord,
  itemRows: JsonRecord[],
  schema: "fbs" | "fbo",
  postingNumber: string
) {
  const status = normalizeStatus(posting.status);
  const operationDate =
    toDateOnly(posting.delivered_at) ??
    toDateOnly(posting.shipment_date) ??
    toDateOnly(posting.in_process_at);

  if (isCancelledStatus(status)) {
    return {
      workspace_id: posting.workspace_id,
      connection_id: posting.connection_id,
      provider: "ozon",
      source_type: "posting",
      external_event_id: `${schema}:${postingNumber}:cancelled`,
      status: "ignored",
      operation_type: null,
      operation_date: operationDate,
      confidence: 1,
      operation: {},
      normalized_operation: {},
      validation_errors: [
        {
          field: "status",
          message: "Canceled Ozon posting is staged for audit only",
          severity: "warning",
        },
      ],
      raw_payload: sanitizeOzonPayload(posting),
    };
  }

  if (!isDeliveredStatus(status)) return null;

  const operation = {
    type: "sale" as const,
    operationDate,
    comment: `Ozon ${schema.toUpperCase()} posting ${postingNumber}`,
    items: itemRows.map((item) => ({
      productId: toStringValue(item.local_product_id),
      productName: toStringValue(item.name),
      skuCode: toStringValue(item.offer_id ?? item.sku),
      offerId: toStringValue(item.offer_id),
      ozonSku: toStringValue(item.sku),
      ozonProductId: toStringValue(item.ozon_product_id),
      warehouseId: toStringValue(posting.local_warehouse_id),
      warehouseName: toStringValue(posting.warehouse_name),
      quantity: toNumberValue(item.quantity),
      unitPrice: toNumberValue(item.price),
      direction: "out" as const,
    })),
  };
  const normalizedOperation = normalizeOzonCandidateOperation(operation);
  const validationErrors = validateOzonCandidateOperation(normalizedOperation);

  return {
    workspace_id: posting.workspace_id,
    connection_id: posting.connection_id,
    provider: "ozon",
    source_type: "posting",
    external_event_id: `${schema}:${postingNumber}:delivered`,
    status: statusFromValidation(validationErrors),
    operation_type: "sale",
    operation_date: operationDate,
    confidence: validationErrors.length === 0 ? 0.95 : 0.65,
    operation: normalizedOperation,
    normalized_operation: normalizedOperation,
    validation_errors: validationErrors,
    raw_payload: sanitizeOzonPayload(posting),
  };
}

async function syncReturns(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string
): Promise<OzonSyncStepSummary> {
  let fetched = 0;
  let createdCandidates = 0;
  let offset = 0;

  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>("/v1/returns/list", {
      filter: {
        date: {
          from: dateFrom,
          to: dateTo,
        },
      },
      limit: POSTING_PAGE_LIMIT,
      offset,
    });
    const items = extractItems(response, ["returns", "items"]);
    fetched += items.length;

    for (const item of items) {
      const created = await upsertReturn(
        supabase,
        workspaceId,
        connectionId,
        item,
        mapping
      );
      if (created) createdCandidates += 1;
    }

    if (items.length < POSTING_PAGE_LIMIT) break;
    offset += POSTING_PAGE_LIMIT;
  }

  let cursor = "";
  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>("/v2/returns/rfbs/list", {
      cursor,
      limit: POSTING_PAGE_LIMIT,
      filter: {
        created_at_from: dateFrom,
        created_at_to: dateTo,
      },
    });
    const root = unwrapResult(response);
    const items = extractItems(root, ["returns", "items"]);
    fetched += items.length;

    for (const item of items) {
      const returnId = toStringValue(
        toRecord(item).return_id ?? toRecord(item).id ?? toRecord(item).posting_number
      );
      const detail = returnId
        ? await client
            .request<JsonRecord>("/v2/returns/rfbs/get", { return_id: returnId })
            .catch(() => item)
        : item;
      const created = await upsertReturn(
        supabase,
        workspaceId,
        connectionId,
        detail,
        mapping
      );
      if (created) createdCandidates += 1;
    }

    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || nextCursor === cursor || items.length === 0) break;
    cursor = nextCursor;
  }

  return { fetched, createdCandidates };
}

async function upsertReturn(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string,
  value: unknown,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const product = toRecord(item.product ?? item.item ?? {});
  const returnId =
    toStringValue(item.id ?? item.return_id ?? item.return_number) ??
    [
      toStringValue(item.posting_number),
      toStringValue(product.offer_id ?? item.offer_id),
      toStringValue(item.status),
    ]
      .filter(Boolean)
      .join(":");

  if (!returnId) return false;

  const ozonProductId = toStringValue(
    product.product_id ?? item.product_id ?? product.sku ?? item.sku
  );
  const offerId = toStringValue(product.offer_id ?? item.offer_id);
  const sku = toStringValue(product.sku ?? item.sku);
  const localProductId = findLocalProductId(mapping, ozonProductId, [offerId, sku]);
  const warehouse = toRecord(item.warehouse ?? item.destination_warehouse ?? {});
  const warehouseName = toStringValue(
    item.warehouse_name ??
      item.destination_warehouse_name ??
      warehouse.warehouse_name ??
      warehouse.name
  );
  const warehouseId = toStringValue(
    item.warehouse_id ?? warehouse.warehouse_id ?? warehouse.id ?? warehouseName
  );
  const localWarehouseId = findLocalWarehouseId(
    mapping,
    warehouseId,
    warehouseName
  );

  const returnRow = {
    workspace_id: workspaceId,
    connection_id: connectionId,
    ozon_return_id: returnId,
    posting_number: toStringValue(item.posting_number),
    status: toStringValue(item.status),
    return_schema: toStringValue(item.schema ?? item.return_schema),
    returned_at: toIsoString(
      item.returned_at ??
        item.returned_to_seller_date_time ??
        item.accepted_from_customer_at ??
        item.created_at
    ),
    offer_id: offerId,
    sku,
    ozon_product_id: ozonProductId,
    quantity: toNumberValue(item.quantity ?? product.quantity) ?? 1,
    price: toNumberValue(item.price ?? product.price),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: localProductId,
    synced_at: new Date().toISOString(),
  };

  const { data: savedReturn, error } = await supabase
    .from("ozon_returns")
    .upsert(returnRow, { onConflict: "connection_id,ozon_return_id" })
    .select("*")
    .single();

  if (error || !savedReturn) {
    throw new Error(error?.message ?? "Failed to save Ozon return");
  }

  const candidate = buildReturnCandidate({
    ...(savedReturn as JsonRecord),
    product_name: toStringValue(product.name ?? item.name),
    warehouse_name: warehouseName,
    ozon_warehouse_id: warehouseId,
    local_warehouse_id: localWarehouseId,
  });
  if (!candidate) return false;

  const candidateRow = await upsertCandidatePreservingReview(supabase, candidate);

  await supabase
    .from("ozon_returns")
    .update({ operation_candidate_id: candidateRow.id })
    .eq("id", savedReturn.id);

  return true;
}

function buildReturnCandidate(returnRow: JsonRecord) {
  const status = normalizeStatus(returnRow.status);
  if (!isReturnedStatus(status)) return null;

  const operationDate = toDateOnly(returnRow.returned_at);
  const operation = {
    type: "return" as const,
    operationDate,
    comment: `Ozon return ${returnRow.ozon_return_id}`,
    items: [
      {
        productId: toStringValue(returnRow.local_product_id),
        productName: toStringValue(
          returnRow.product_name ?? returnRow.offer_id ?? returnRow.sku
        ),
        skuCode: toStringValue(returnRow.offer_id ?? returnRow.sku),
        offerId: toStringValue(returnRow.offer_id),
        ozonSku: toStringValue(returnRow.sku),
        ozonProductId: toStringValue(returnRow.ozon_product_id),
        warehouseId: toStringValue(returnRow.local_warehouse_id),
        warehouseName: toStringValue(returnRow.warehouse_name),
        ozonWarehouseId: toStringValue(returnRow.ozon_warehouse_id),
        quantity: toNumberValue(returnRow.quantity) ?? 1,
        unitPrice: toNumberValue(returnRow.price),
        direction: "in" as const,
      },
    ],
  };
  const normalizedOperation = normalizeOzonCandidateOperation(operation);
  const validationErrors = validateOzonCandidateOperation(normalizedOperation);

  return {
    workspace_id: returnRow.workspace_id,
    connection_id: returnRow.connection_id,
    provider: "ozon",
    source_type: "return",
    external_event_id: `return:${returnRow.ozon_return_id}`,
    status: statusFromValidation(validationErrors),
    operation_type: "return",
    operation_date: operationDate,
    confidence: validationErrors.length === 0 ? 0.9 : 0.6,
    operation: normalizedOperation,
    normalized_operation: normalizedOperation,
    validation_errors: validationErrors,
    raw_payload: sanitizeOzonPayload(returnRow.raw_payload ?? returnRow),
  };
}

async function syncFinance(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string
): Promise<OzonSyncStepSummary> {
  const accrualTypes = await fetchFinanceAccrualTypes(client);
  const rows: JsonRecord[] = [];

  for (const date of datesInRange(dateFrom, dateTo)) {
    let lastId = "";
    for (let page = 1; page <= FINANCE_ACCRUAL_PAGE_LIMIT; page += 1) {
      const response = await client.request<JsonRecord>(
        "/v1/finance/accrual/by-day",
        {
          date,
          last_id: lastId,
        }
      );
      const root = unwrapResult(response);
      const accruals = extractItems(root, ["accruals", "items"]);

      rows.push(
        ...(accruals.map((item, index) =>
          toFinanceAccrualRow(item, workspaceId, connectionId, {
            accrualTypes,
            date,
            index,
            page,
          })
        ) as JsonRecord[])
      );

      const nextLastId = toStringValue(root.last_id);
      if (!nextLastId || nextLastId === lastId || accruals.length === 0) break;
      lastId = nextLastId;
    }
  }

  await upsertRows(
    supabase,
    "ozon_finance_transactions",
    rows,
    "connection_id,transaction_id"
  );

  return { fetched: rows.length };
}

async function fetchFinanceAccrualTypes(client: OzonClient) {
  const response = await client.request<JsonRecord>("/v1/finance/accrual/types", {});
  const root = unwrapResult(response);
  const types = extractItems(root, ["accrual_types", "items"]);
  const map = new Map<string, JsonRecord>();
  for (const value of types) {
    const item = toRecord(value);
    const id = toStringValue(item.id);
    if (id) map.set(id, item);
  }
  return map;
}

function toFinanceAccrualRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  context: {
    accrualTypes: Map<string, JsonRecord>;
    date: string;
    index: number;
    page: number;
  }
) {
  const item = toRecord(value);
  const posting = toRecord(item.posting);
  const nonItemFee = toRecord(item.non_item_fee);
  const typeId = toStringValue(item.type_id ?? nonItemFee.type_id);
  const accrualType = typeId ? context.accrualTypes.get(typeId) ?? null : null;
  const transactionId =
    toStringValue(item.unit_number ?? item.id ?? item.operation_id) ??
    [
      "accrual",
      context.date,
      context.page,
      context.index,
      stableHash(item).slice(0, 16),
    ].join(":");

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    transaction_id: transactionId,
    operation_type:
      toStringValue(item.accrued_category) ??
      toStringValue(accrualType?.name) ??
      toStringValue(accrualType?.description) ??
      typeId,
    operation_date: toIsoString(item.date ?? context.date),
    posting_number: toStringValue(
      item.posting_number ?? posting.posting_number ?? posting.number
    ),
    amount:
      moneyAmount(item.total_amount) ??
      moneyAmount(nonItemFee.accrued) ??
      0,
    currency_code:
      moneyCurrency(item.total_amount) ??
      moneyCurrency(nonItemFee.accrued),
    items: financeAccrualItems(item),
    services: financeAccrualServices(item),
    raw_payload: sanitizeOzonPayload({
      ...item,
      accrual_type: accrualType,
    }),
    synced_at: new Date().toISOString(),
  };
}

async function syncLegalEntities(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string
): Promise<OzonSyncStepSummary> {
  let fetched = 0;
  let createdCandidates = 0;
  const rows: JsonRecord[] = [];

  for (const month of monthsInRange(dateFrom, dateTo)) {
    const response = await client.request<JsonRecord>(
      "/v1/finance/document-b2b-sales/json",
      { date: month }
    );
    const root = unwrapResult(response);
    const invoices = extractItems(root, ["invoices", "items", "rows"]);
    fetched += invoices.length;

    for (const invoice of invoices) {
      const row = toLegalEntitySaleRow(invoice, workspaceId, connectionId, mapping);
      rows.push(row);
    }
  }

  await upsertRows(
    supabase,
    "ozon_legal_entity_sales",
    rows,
    "connection_id,external_id"
  );

  for (const row of rows) {
    const candidate = await buildLegalEntitySaleCandidate(supabase, row, mapping);
    if (!candidate) continue;
    const saved = await upsertCandidatePreservingReview(supabase, candidate);
    await supabase
      .from("ozon_legal_entity_sales")
      .update({ operation_candidate_id: saved.id })
      .eq("connection_id", connectionId)
      .eq("external_id", row.external_id);
    createdCandidates += 1;
  }

  fetched += await syncUnpaidLegalProducts(
    supabase,
    client,
    workspaceId,
    connectionId,
    mapping
  );

  return { fetched, createdCandidates };
}

function toLegalEntitySaleRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const buyer = toRecord(item.buyer_info ?? item.buyer ?? {});
  const order = toRecord(item.order ?? item.posting ?? {});
  const invoiceNumber = toStringValue(
    item.invoice_number ?? item.number ?? item.document_number
  );
  const postingNumber = toStringValue(
    item.posting_number ?? order.posting_number ?? order.number
  );
  const products = extractInvoiceProducts(item);
  const externalId =
    toStringValue(item.invoice_id ?? item.id ?? invoiceNumber ?? postingNumber) ??
    `legal:${stableHash(item).slice(0, 24)}`;

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: externalId,
    invoice_number: invoiceNumber,
    invoice_date: toDateOnly(
      item.invoice_date ?? item.date ?? item.sale_date ?? item.created_at
    ),
    posting_number: postingNumber,
    buyer_company_name: toStringValue(
      buyer.company_name ??
        buyer.organization_name ??
        item.buyer_company_name ??
        item.company_name
    ),
    buyer_inn: toStringValue(buyer.inn ?? buyer.buyer_inn ?? item.buyer_inn),
    buyer_kpp: toStringValue(buyer.kpp ?? buyer.buyer_kpp ?? item.buyer_kpp),
    amount:
      toNumberValue(item.amount ?? item.total_amount ?? item.price) ??
      sumProductsAmount(products),
    currency_code: toStringValue(item.currency_code ?? item.currency),
    products: products.map((product) => {
      const productRecord = toRecord(product);
      return {
        ...toRecord(sanitizeOzonPayload(product)),
        local_product_id: findLocalProductId(
          mapping,
          toStringValue(productRecord.product_id ?? productRecord.sku),
          [
            toStringValue(productRecord.offer_id),
            toStringValue(productRecord.sku),
          ]
        ),
      };
    }),
    raw_payload: sanitizeOzonPayload(item),
    synced_at: new Date().toISOString(),
  };
}

async function buildLegalEntitySaleCandidate(
  supabase: SupabaseClient,
  row: JsonRecord,
  mapping: MappingContext
) {
  const postingNumber = toStringValue(row.posting_number);
  if (postingNumber) {
    const { data, error } = await supabase
      .from("ozon_postings")
      .select("id, operation_candidate_id")
      .eq("connection_id", row.connection_id)
      .eq("posting_number", postingNumber)
      .maybeSingle();
    if (error) throw new Error(error.message);
    if (data?.operation_candidate_id) return null;
  }

  const products = asArray(row.products);
  if (products.length === 0) return null;

  const operation = {
    type: "sale" as const,
    operationDate: toDateOnly(row.invoice_date),
    comment: `Ozon legal-entity sale ${row.invoice_number || row.external_id}`,
    sourceType: "legal_entity_sale" as const,
    supportStatus: "commit_candidate" as const,
    supportReason:
      "Legal-entity sale has no matching Ozon posting candidate, so it is staged as a fallback sale.",
    items: products.map((product) => {
      const item = toRecord(product);
      const ozonProductId = toStringValue(item.product_id ?? item.sku);
      const offerId = toStringValue(item.offer_id);
      const sku = toStringValue(item.sku);
      return {
        productId:
          toStringValue(item.local_product_id) ??
          findLocalProductId(mapping, ozonProductId, [offerId, sku]),
        productName: toStringValue(item.name),
        skuCode: offerId ?? sku,
        offerId,
        ozonSku: sku,
        ozonProductId,
        warehouseId: null,
        warehouseName: null,
        quantity: toNumberValue(item.quantity) ?? 1,
        unitPrice: toNumberValue(item.seller_price_per_instance ?? item.price),
        direction: "out" as const,
      };
    }),
  };
  const normalizedOperation = normalizeOzonCandidateOperation(operation);
  const validationErrors = validateOzonCandidateOperation(normalizedOperation);

  return {
    workspace_id: row.workspace_id,
    connection_id: row.connection_id,
    provider: "ozon",
    source_type: "legal_entity_sale",
    external_event_id: `legal:${row.external_id}`,
    status: statusFromValidation(validationErrors),
    operation_type: "sale",
    operation_date: operation.operationDate,
    confidence: validationErrors.length === 0 ? 0.8 : 0.45,
    operation: normalizedOperation,
    normalized_operation: normalizedOperation,
    validation_errors: validationErrors,
    raw_payload: row.raw_payload,
  };
}

async function syncUnpaidLegalProducts(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const rows: JsonRecord[] = [];
  let cursor = "";

  for (let page = 0; page < 50; page += 1) {
    const response = await client.request<JsonRecord>(
      "/v1/posting/unpaid-legal/product/list",
      { cursor, limit: 1000 }
    );
    const root = unwrapResult(response);
    const products = extractItems(root, ["products", "items", "rows"]);
    rows.push(
      ...products.map((product) =>
        toUnpaidLegalProductRow(product, workspaceId, connectionId, mapping)
      )
    );
    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || nextCursor === cursor || products.length === 0) break;
    cursor = nextCursor;
  }

  await upsertRows(
    supabase,
    "ozon_unpaid_legal_products",
    rows,
    "connection_id,external_id"
  );
  return rows.length;
}

function toUnpaidLegalProductRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.sku);
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);
  const postingNumber = toStringValue(item.posting_number);
  const externalId =
    toStringValue(item.id ?? item.product_id ?? item.sku) ??
    `unpaid:${stableHash(item).slice(0, 24)}`;

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: [postingNumber, externalId].filter(Boolean).join(":"),
    posting_number: postingNumber,
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name),
    quantity: toNumberValue(item.quantity),
    amount: toNumberValue(item.amount ?? item.price),
    currency_code: toStringValue(item.currency_code ?? item.currency),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    synced_at: new Date().toISOString(),
  };
}

async function syncFinanceReports(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string
): Promise<OzonSyncStepSummary> {
  const rows: JsonRecord[] = [];
  const months = monthsInRange(dateFrom, dateTo);

  for (const month of months) {
    rows.push(
      ...(await requestReportCode(
        supabase,
        client,
        workspaceId,
        connectionId,
        "mutual_settlement",
        "/v1/finance/mutual-settlement",
        { date: month, language: "DEFAULT" }
      ))
    );
    rows.push(
      ...(await requestReportCode(
        supabase,
        client,
        workspaceId,
        connectionId,
        "compensation",
        "/v1/finance/compensation",
        { date: month, language: "RU" }
      ))
    );
    rows.push(
      ...(await requestReportCode(
        supabase,
        client,
        workspaceId,
        connectionId,
        "decompensation",
        "/v1/finance/decompensation",
        { date: month, language: "RU" }
      ))
    );
  }

  rows.push(
    ...(await syncCashFlowRows(client, workspaceId, connectionId, dateFrom, dateTo))
  );
  rows.push(
    ...(await syncBuyoutRows(client, workspaceId, connectionId, dateFrom, dateTo))
  );

  await upsertRows(
    supabase,
    "ozon_finance_reports",
    rows,
    "connection_id,external_id"
  );

  return { fetched: rows.length };
}

async function requestReportCode(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  reportType: string,
  endpoint: OzonReadOnlyEndpoint,
  payload: JsonRecord
) {
  const response = await client.request<JsonRecord>(endpoint, payload);
  const root = unwrapResult(response);
  const code = toStringValue(root.code ?? response.code);
  if (!code) return [];

  const info = await client
    .request<JsonRecord>("/v1/report/info", { code })
    .catch(() => ({}));
  const reportInfo = unwrapResult(info);

  await upsertRows(
    supabase,
    "ozon_report_runs",
    [
      {
        workspace_id: workspaceId,
        connection_id: connectionId,
        report_type: reportType,
        ozon_report_code: code,
        status: toStringValue(reportInfo.status ?? root.status),
        request_payload: sanitizeOzonPayload(payload),
        response_payload: sanitizeOzonPayload(reportInfo),
        file_url: toStringValue(reportInfo.file),
        completed_at: reportInfo.file ? new Date().toISOString() : null,
      },
    ],
    "connection_id,ozon_report_code"
  );

  return [
    {
      workspace_id: workspaceId,
      connection_id: connectionId,
      external_id: `${reportType}:${code}`,
      report_type: reportType,
      period_start: monthStartDate(payload.date),
      period_end: monthEndDate(payload.date),
      status: toStringValue(reportInfo.status ?? root.status),
      ozon_report_code: code,
      file_url: toStringValue(reportInfo.file),
      raw_payload: sanitizeOzonPayload({ response, reportInfo }),
      synced_at: new Date().toISOString(),
    },
  ];
}

async function syncCashFlowRows(
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string
) {
  const rows: JsonRecord[] = [];
  for (let page = 1; page <= 100; page += 1) {
    const response = await client.request<JsonRecord>(
      "/v1/finance/cash-flow-statement/list",
      {
        date: { from: dateFrom, to: dateTo },
        with_details: true,
        page,
        page_size: 1000,
      }
    );
    const root = unwrapResult(response);
    const flows = extractItems(root, ["cash_flows", "items", "rows"]);
    rows.push(
      ...flows.map((flow) => {
        const item = toRecord(flow);
        const period = toRecord(item.period);
        const id =
          toStringValue(period.id ?? item.id) ??
          `cash-flow:${page}:${stableHash(item).slice(0, 16)}`;
        return {
          workspace_id: workspaceId,
          connection_id: connectionId,
          external_id: id,
          report_type: "cash_flow",
          period_start: toDateOnly(period.begin),
          period_end: toDateOnly(period.end),
          amount: toNumberValue(item.orders_amount ?? item.amount),
          currency_code: toStringValue(item.currency_code),
          raw_payload: sanitizeOzonPayload(item),
          synced_at: new Date().toISOString(),
        };
      })
    );
    if (flows.length < 1000) break;
  }
  return rows;
}

async function syncBuyoutRows(
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string
) {
  const response = await client.request<JsonRecord>("/v1/finance/products/buyout", {
    date_from: dateFrom.slice(0, 10),
    date_to: dateTo.slice(0, 10),
  });
  const products = extractItems(response, ["products", "items", "rows"]);
  return products.map((product) => {
    const item = toRecord(product);
    const id =
      [
        "buyout",
        toStringValue(item.posting_number),
        toStringValue(item.offer_id ?? item.sku),
      ]
        .filter(Boolean)
        .join(":") || `buyout:${stableHash(item).slice(0, 24)}`;
    return {
      workspace_id: workspaceId,
      connection_id: connectionId,
      external_id: id,
      report_type: "buyout",
      period_start: dateFrom.slice(0, 10),
      period_end: dateTo.slice(0, 10),
      amount: toNumberValue(item.amount ?? item.buyout_price),
      currency_code: toStringValue(item.currency_code ?? item.currency),
      raw_payload: sanitizeOzonPayload(item),
      synced_at: new Date().toISOString(),
    };
  });
}

async function syncRemovals(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string
): Promise<OzonSyncStepSummary> {
  let fetched = 0;
  let createdCandidates = 0;

  for (const source of [
    { type: "from_stock" as const, endpoint: "/v1/removal/from-stock/list" as const },
    {
      type: "from_supply" as const,
      endpoint: "/v1/removal/from-supply/list" as const,
    },
  ]) {
    let lastId = "";
    for (let page = 0; page < 100; page += 1) {
      const response = await client.request<JsonRecord>(source.endpoint, {
        date_from: dateFrom.slice(0, 10),
        date_to: dateTo.slice(0, 10),
        last_id: lastId,
        limit: 500,
      });
      const root = unwrapResult(response);
      const items = extractItems(root, [
        "returns_summary_report_rows",
        "rows",
        "items",
      ]);
      fetched += items.length;

      for (const item of items) {
        const row = toRemovalRow(
          item,
          source.type,
          workspaceId,
          connectionId,
          mapping
        );
        const { data, error } = await supabase
          .from("ozon_removals")
          .upsert(row, { onConflict: "connection_id,external_id" })
          .select("*")
          .single();
        if (error || !data) {
          throw new Error(error?.message ?? "Failed to save Ozon removal");
        }
        const candidate = buildRemovalCandidate(data as JsonRecord);
        if (!candidate) continue;
        const saved = await upsertCandidatePreservingReview(supabase, candidate);
        await supabase
          .from("ozon_removals")
          .update({ operation_candidate_id: saved.id })
          .eq("id", data.id);
        createdCandidates += 1;
      }

      const nextLastId = toStringValue(root.last_id ?? response.last_id);
      if (!nextLastId || nextLastId === lastId || items.length === 0) break;
      lastId = nextLastId;
    }
  }

  return { fetched, createdCandidates };
}

function toRemovalRow(
  value: unknown,
  removalType: "from_stock" | "from_supply",
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const product = toRecord(item.product ?? item.item ?? {});
  const warehouse = toRecord(item.warehouse ?? {});
  const ozonProductId = toStringValue(
    item.product_id ?? product.product_id ?? item.sku ?? product.sku
  );
  const offerId = toStringValue(item.offer_id ?? product.offer_id);
  const sku = toStringValue(item.sku ?? product.sku);
  const warehouseName = toStringValue(
    item.warehouse_name ?? warehouse.name ?? warehouse.warehouse_name
  );
  const warehouseId = toStringValue(
    item.warehouse_id ?? warehouse.id ?? warehouse.warehouse_id ?? warehouseName
  );
  const externalId =
    toStringValue(item.id ?? item.removal_id ?? item.posting_number) ??
    `${removalType}:${stableHash(item).slice(0, 24)}`;

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: `${removalType}:${externalId}`,
    removal_type: removalType,
    status: toStringValue(item.status),
    reason: toStringValue(item.reason ?? item.type ?? item.operation_type),
    event_date: toIsoString(item.date ?? item.created_at ?? item.operation_date),
    posting_number: toStringValue(item.posting_number),
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name ?? product.name),
    quantity: toNumberValue(item.quantity ?? product.quantity) ?? 0,
    warehouse_name: warehouseName,
    ozon_warehouse_id: warehouseId,
    amount: toNumberValue(item.amount ?? item.price),
    currency_code: toStringValue(item.currency_code ?? item.currency),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    local_warehouse_id: findLocalWarehouseId(mapping, warehouseId, warehouseName),
    synced_at: new Date().toISOString(),
  };
}

function buildRemovalCandidate(row: JsonRecord) {
  const reason = normalizeStatus(`${row.reason ?? ""} ${row.status ?? ""}`);
  if (!isDisposalReason(reason)) return null;

  const operation = {
    type: "write_off" as const,
    operationDate: toDateOnly(row.event_date),
    comment: `Ozon removal/disposal ${row.external_id}`,
    sourceType: "removal" as const,
    supportStatus: "commit_candidate" as const,
    supportReason: "Ozon removal row explicitly indicates disposal or loss.",
    items: [
      {
        productId: toStringValue(row.local_product_id),
        productName: toStringValue(row.name ?? row.offer_id ?? row.sku),
        skuCode: toStringValue(row.offer_id ?? row.sku),
        offerId: toStringValue(row.offer_id),
        ozonSku: toStringValue(row.sku),
        ozonProductId: toStringValue(row.ozon_product_id),
        warehouseId: toStringValue(row.local_warehouse_id),
        warehouseName: toStringValue(row.warehouse_name),
        ozonWarehouseId: toStringValue(row.ozon_warehouse_id),
        quantity: toNumberValue(row.quantity),
        unitPrice: toNumberValue(row.amount),
        direction: "out" as const,
      },
    ],
  };
  const normalizedOperation = normalizeOzonCandidateOperation(operation);
  const validationErrors = validateOzonCandidateOperation(normalizedOperation);
  return {
    workspace_id: row.workspace_id,
    connection_id: row.connection_id,
    provider: "ozon",
    source_type: "removal",
    external_event_id: `removal:${row.external_id}`,
    status: statusFromValidation(validationErrors),
    operation_type: "write_off",
    operation_date: operation.operationDate,
    confidence: validationErrors.length === 0 ? 0.9 : 0.55,
    operation: normalizedOperation,
    normalized_operation: normalizedOperation,
    validation_errors: validationErrors,
    raw_payload: row.raw_payload,
  };
}

async function syncSupplies(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
): Promise<OzonSyncStepSummary> {
  const response = await client.request<JsonRecord>("/v3/supply-order/list", {
    limit: 100,
  });
  const orders = extractItems(response, ["orders", "items", "supplies"]);
  let createdCandidates = 0;

  for (const order of orders) {
    const orderId = toStringValue(
      toRecord(order).order_id ?? toRecord(order).id ?? toRecord(order).supply_order_id
    );
    if (!orderId) continue;
    const detail = await client
      .request<JsonRecord>("/v3/supply-order/get", { order_ids: [orderId] })
      .catch(() => order as JsonRecord);
    const detailOrder =
      extractItems(detail, ["orders", "items"])[0] ?? unwrapResult(detail) ?? order;
    const orderRow = toSupplyOrderRow(
      detailOrder,
      workspaceId,
      connectionId,
      mapping
    );
    const { data: savedOrder, error } = await supabase
      .from("ozon_supply_orders")
      .upsert(orderRow, { onConflict: "connection_id,ozon_supply_order_id" })
      .select("*")
      .single();
    if (error || !savedOrder) {
      throw new Error(error?.message ?? "Failed to save Ozon supply order");
    }

    await supabase
      .from("ozon_supply_order_items")
      .delete()
      .eq("supply_order_id", savedOrder.id);

    const bundleRows = await fetchSupplyItems(
      client,
      savedOrder as JsonRecord,
      workspaceId,
      connectionId,
      mapping
    );
    await insertRows(supabase, "ozon_supply_order_items", bundleRows);

    const candidates = buildSupplyTransferCandidates(
      savedOrder as JsonRecord,
      bundleRows
    );
    for (const candidate of candidates) {
      const saved = await upsertCandidatePreservingReview(supabase, candidate);
      await supabase
        .from("ozon_supply_orders")
        .update({ operation_candidate_id: saved.id })
        .eq("id", savedOrder.id);
      createdCandidates += 1;
    }
  }

  return { fetched: orders.length, createdCandidates };
}

function toSupplyOrderRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const warehouse = toRecord(
    item.warehouse ?? item.destination_warehouse ?? item.dropoff_warehouse ?? {}
  );
  const orderId = toStringValue(item.order_id ?? item.id ?? item.supply_order_id);
  const warehouseName = toStringValue(
    item.warehouse_name ?? warehouse.name ?? warehouse.warehouse_name
  );
  const warehouseId = toStringValue(
    item.warehouse_id ?? warehouse.id ?? warehouse.warehouse_id ?? warehouseName
  );

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    ozon_supply_order_id: orderId,
    order_number: toStringValue(item.order_number ?? item.number),
    state: toStringValue(item.state ?? item.status),
    created_at_ozon: toIsoString(item.created_date ?? item.created_at),
    warehouse_name: warehouseName,
    ozon_warehouse_id: warehouseId,
    bundle_ids: asArray(item.bundle_ids ?? item.bundles).map((bundle) =>
      toStringValue(toRecord(bundle).bundle_id ?? toRecord(bundle).id ?? bundle)
    ),
    raw_payload: sanitizeOzonPayload(item),
    local_destination_warehouse_id: findLocalWarehouseId(
      mapping,
      warehouseId,
      warehouseName
    ),
    synced_at: new Date().toISOString(),
  };
}

async function fetchSupplyItems(
  client: OzonClient,
  order: JsonRecord,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const bundleIds = asArray(order.bundle_ids)
    .map(toStringValue)
    .filter((value): value is string => Boolean(value));
  const rows: JsonRecord[] = [];

  if (bundleIds.length === 0) {
    rows.push(
      ...extractItems(order.raw_payload, ["items", "products"]).map((item, index) =>
        toSupplyItemRow(item, order.id as string, index, workspaceId, connectionId, mapping)
      )
    );
    return rows;
  }

  for (const bundleId of bundleIds) {
    const response = await client
      .request<JsonRecord>("/v1/supply-order/bundle", {
        bundle_ids: [bundleId],
        limit: 100,
        is_asc: true,
      })
      .catch(() => ({}));
    const items = extractItems(response, ["items", "products", "rows"]);
    rows.push(
      ...items.map((item, index) =>
        toSupplyItemRow(
          item,
          order.id as string,
          index,
          workspaceId,
          connectionId,
          mapping,
          bundleId
        )
      )
    );
  }

  return rows;
}

function toSupplyItemRow(
  value: unknown,
  supplyOrderId: string,
  index: number,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  bundleId?: string
) {
  const item = toRecord(value);
  const product = toRecord(item.product ?? {});
  const ozonProductId = toStringValue(
    item.product_id ?? product.product_id ?? item.sku ?? product.sku
  );
  const offerId = toStringValue(item.offer_id ?? product.offer_id);
  const sku = toStringValue(item.sku ?? product.sku);
  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    supply_order_id: supplyOrderId,
    external_id:
      toStringValue(item.id ?? item.item_id ?? item.sku) ??
      `${bundleId || "bundle"}:${index}`,
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name ?? product.name),
    quantity: toNumberValue(item.quantity ?? item.count) ?? 0,
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
  };
}

function buildSupplyTransferCandidates(order: JsonRecord, items: JsonRecord[]) {
  const status = normalizeStatus(order.state);
  if (!isCompletedSupplyStatus(status) || items.length === 0) return [];

  return items.map((item) => {
    const productId = toStringValue(item.local_product_id);
    const quantity = toNumberValue(item.quantity);
    const operation = {
      type: "transfer" as const,
      operationDate:
        toDateOnly(order.created_at_ozon) ??
        new Date().toISOString().slice(0, 10),
      comment: `Ozon FBO supply ${order.order_number || order.ozon_supply_order_id}`,
      sourceType: "supply" as const,
      supportStatus: "commit_candidate" as const,
      supportReason:
        "Ozon supply proves movement into Ozon. Select the local source warehouse before commit if it is missing.",
      items: [
        {
          productId,
          productName: toStringValue(item.name ?? item.offer_id ?? item.sku),
          skuCode: toStringValue(item.offer_id ?? item.sku),
          offerId: toStringValue(item.offer_id),
          ozonSku: toStringValue(item.sku),
          ozonProductId: toStringValue(item.ozon_product_id),
          warehouseId: null,
          warehouseName: null,
          quantity,
          direction: "out" as const,
        },
        {
          productId,
          productName: toStringValue(item.name ?? item.offer_id ?? item.sku),
          skuCode: toStringValue(item.offer_id ?? item.sku),
          offerId: toStringValue(item.offer_id),
          ozonSku: toStringValue(item.sku),
          ozonProductId: toStringValue(item.ozon_product_id),
          warehouseId: toStringValue(order.local_destination_warehouse_id),
          warehouseName: toStringValue(order.warehouse_name),
          ozonWarehouseId: toStringValue(order.ozon_warehouse_id),
          quantity,
          direction: "in" as const,
        },
      ],
    };
    const normalizedOperation = normalizeOzonCandidateOperation(operation);
    const validationErrors = validateOzonCandidateOperation(normalizedOperation);
    const itemExternalId =
      toStringValue(item.external_id ?? item.ozon_product_id ?? item.sku) ??
      stableHash(item).slice(0, 16);

    return {
      workspace_id: order.workspace_id,
      connection_id: order.connection_id,
      provider: "ozon",
      source_type: "supply",
      external_event_id: `supply:${order.ozon_supply_order_id}:${itemExternalId}`,
      status: statusFromValidation(validationErrors),
      operation_type: "transfer",
      operation_date: operation.operationDate,
      confidence: validationErrors.length === 0 ? 0.85 : 0.5,
      operation: normalizedOperation,
      normalized_operation: normalizedOperation,
      validation_errors: validationErrors,
      raw_payload: order.raw_payload,
    };
  });
}

async function syncStockAnalytics(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
): Promise<OzonSyncStepSummary> {
  const products = await loadOzonProductRefs(supabase, workspaceId, connectionId);
  const snapshotDate = new Date().toISOString().slice(0, 10);
  let fetched = 0;

  for (const chunk of chunkArray(products, 100)) {
    const skus = chunk
      .map((product) => product.sku)
      .filter((value): value is string => Boolean(value));
    if (skus.length === 0) continue;

    const [stocksResponse, turnoverResponse] = await Promise.all([
      client.request<JsonRecord>("/v1/analytics/stocks", { skus }).catch(() => ({})),
      client
        .request<JsonRecord>("/v1/analytics/turnover/stocks", {
          sku: skus,
          limit: skus.length,
        })
        .catch(() => ({})),
    ]);

    const stockRows = extractItems(stocksResponse, ["items", "rows", "stocks"]);
    fetched += stockRows.length;
    for (const item of stockRows) {
      const row = toStockAnalyticsRow(
        item,
        workspaceId,
        connectionId,
        mapping,
        snapshotDate
      );
      const { data, error } = await supabase
        .from("ozon_stock_analytics")
        .upsert(row, { onConflict: "connection_id,external_id,snapshot_date" })
        .select("*")
        .single();
      if (error || !data) {
        throw new Error(error?.message ?? "Failed to save Ozon stock analytics");
      }
    }

    const turnoverRows = extractItems(turnoverResponse, ["items", "rows"]);
    await upsertRows(
      supabase,
      "ozon_turnover_analytics",
      turnoverRows.map((item) =>
        toTurnoverAnalyticsRow(item, workspaceId, connectionId, mapping, snapshotDate)
      ),
      "connection_id,external_id,snapshot_date"
    );
    fetched += turnoverRows.length;
  }

  return { fetched, createdCandidates: 0 };
}

async function loadOzonProductRefs(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string
) {
  const { data, error } = await supabase
    .from("ozon_products")
    .select("ozon_product_id, offer_id, sku, name, price, local_product_id")
    .eq("workspace_id", workspaceId)
    .eq("connection_id", connectionId);
  if (error) throw new Error(error.message);
  return (data || []) as JsonRecord[];
}

function toStockAnalyticsRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  snapshotDate: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.sku);
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);
  const warehouseName = toStringValue(
    item.warehouse_name ?? item.cluster_name ?? item.name
  );
  const warehouseId = toStringValue(
    item.warehouse_id ?? item.cluster_id ?? warehouseName
  );
  const externalId =
    [
      toStringValue(item.id ?? item.sku ?? item.product_id),
      warehouseId,
    ]
      .filter(Boolean)
      .join(":") || `stock:${stableHash(item).slice(0, 24)}`;

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: externalId,
    snapshot_date: snapshotDate,
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name ?? item.product_name),
    warehouse_name: warehouseName,
    ozon_warehouse_id: warehouseId,
    cluster_id: toStringValue(item.cluster_id),
    stock: toNumberValue(item.stock ?? item.current_stock ?? item.available_stock) ?? 0,
    available_stock: toNumberValue(item.available_stock),
    reserved_stock: toNumberValue(item.reserved_stock ?? item.reserved),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    local_warehouse_id: findLocalWarehouseId(mapping, warehouseId, warehouseName),
    synced_at: new Date().toISOString(),
  };
}

function toTurnoverAnalyticsRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  snapshotDate: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.sku);
  const sku = toStringValue(item.sku);
  const externalId =
    toStringValue(item.id ?? item.sku ?? item.product_id) ??
    `turnover:${stableHash(item).slice(0, 24)}`;
  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: externalId,
    snapshot_date: snapshotDate,
    ozon_product_id: ozonProductId,
    sku,
    name: toStringValue(item.name ?? item.product_name),
    current_stock: toNumberValue(item.current_stock ?? item.stock),
    ads: toNumberValue(item.ads),
    days_to_stock_out: toNumberValue(
      item.days_to_stock_out ?? item.turnover_days ?? item.days
    ),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [sku]),
    synced_at: new Date().toISOString(),
  };
}

async function syncDiscountedProducts(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
): Promise<OzonSyncStepSummary> {
  const products = await loadOzonProductRefs(supabase, workspaceId, connectionId);
  let fetched = 0;
  let createdCandidates = 0;

  for (const chunk of chunkArray(products, 100)) {
    const skus = chunk
      .map((product) => product.sku)
      .filter((value): value is string => Boolean(value));
    if (skus.length === 0) continue;
    const response = await client
      .request<JsonRecord>("/v1/product/info/discounted", { skus })
      .catch(() => ({}));
    const items = extractItems(response, ["items", "products", "rows"]);
    fetched += items.length;
    for (const item of items) {
      const row = toDiscountedProductRow(
        item,
        workspaceId,
        connectionId,
        mapping
      );
      const { data, error } = await supabase
        .from("ozon_discounted_products")
        .upsert(row, { onConflict: "connection_id,external_id" })
        .select("*")
        .single();
      if (error || !data) {
        throw new Error(error?.message ?? "Failed to save discounted product");
      }
      const candidate = buildDefectCandidate(data as JsonRecord);
      if (!candidate) continue;
      const saved = await upsertCandidatePreservingReview(supabase, candidate);
      await supabase
        .from("ozon_discounted_products")
        .update({ operation_candidate_id: saved.id })
        .eq("id", data.id);
      createdCandidates += 1;
    }
  }

  return { fetched, createdCandidates };
}

function toDiscountedProductRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(
    item.product_id ?? item.ozon_product_id ?? item.sku
  );
  const sku = toStringValue(item.sku);
  const discountedSku = toStringValue(item.discounted_sku ?? item.discount_sku);
  const offerId = toStringValue(item.offer_id);
  const warehouseName = toStringValue(item.warehouse_name);
  const warehouseId = toStringValue(item.warehouse_id ?? warehouseName);
  const externalId =
    discountedSku ??
    toStringValue(item.id ?? item.product_id ?? item.sku) ??
    `discounted:${stableHash(item).slice(0, 24)}`;
  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: externalId,
    ozon_product_id: ozonProductId,
    discounted_sku: discountedSku,
    sku,
    offer_id: offerId,
    name: toStringValue(item.name ?? item.product_name),
    status: toStringValue(item.status),
    reason: toStringValue(item.reason ?? item.discount_reason ?? item.comment),
    quantity: toNumberValue(item.quantity ?? item.stock),
    discount_percent: toNumberValue(item.discount_percent),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    local_warehouse_id: findLocalWarehouseId(mapping, warehouseId, warehouseName),
    synced_at: new Date().toISOString(),
  };
}

function buildDefectCandidate(row: JsonRecord) {
  const reason = normalizeStatus(`${row.reason ?? ""} ${row.status ?? ""}`);
  if (!isDefectReason(reason)) return null;
  const operation = {
    type: "defect" as const,
    operationDate: new Date().toISOString().slice(0, 10),
    comment: `Ozon discounted/damaged product ${row.external_id}`,
    sourceType: "discounted_product" as const,
    supportStatus: "commit_candidate" as const,
    supportReason: "Ozon discounted product reason explicitly indicates damage or defect.",
    items: [
      {
        productId: toStringValue(row.local_product_id),
        productName: toStringValue(row.name ?? row.offer_id ?? row.sku),
        warehouseId: toStringValue(row.local_warehouse_id),
        warehouseName: null,
        skuCode: toStringValue(row.offer_id ?? row.sku),
        offerId: toStringValue(row.offer_id),
        ozonSku: toStringValue(row.sku),
        ozonProductId: toStringValue(row.ozon_product_id),
        quantity: toNumberValue(row.quantity) ?? 1,
        direction: "out" as const,
      },
    ],
  };
  const normalizedOperation = normalizeOzonCandidateOperation(operation);
  const validationErrors = validateOzonCandidateOperation(normalizedOperation);
  return {
    workspace_id: row.workspace_id,
    connection_id: row.connection_id,
    provider: "ozon",
    source_type: "discounted_product",
    external_event_id: `discounted:${row.external_id}`,
    status: statusFromValidation(validationErrors),
    operation_type: "defect",
    operation_date: operation.operationDate,
    confidence: validationErrors.length === 0 ? 0.75 : 0.4,
    operation: normalizedOperation,
    normalized_operation: normalizedOperation,
    validation_errors: validationErrors,
    raw_payload: row.raw_payload,
  };
}

async function upsertRows(
  supabase: SupabaseClient,
  table: string,
  rows: JsonRecord[],
  onConflict: string
) {
  for (const chunk of chunkArray(rows, 500)) {
    if (chunk.length === 0) continue;
    const { error } = await supabase
      .from(table)
      .upsert(chunk, { onConflict });
    if (error) throw new Error(error.message);
  }
}

async function upsertCandidatePreservingReview(
  supabase: SupabaseClient,
  candidate: JsonRecord
) {
  const { data: existing, error: existingError } = await supabase
    .from("marketplace_operation_candidates")
    .select("id, status")
    .eq("connection_id", candidate.connection_id)
    .eq("source_type", candidate.source_type)
    .eq("external_event_id", candidate.external_event_id)
    .maybeSingle();

  if (existingError) throw new Error(existingError.message);

  if (
    existing &&
    !canSyncUpdateCandidateStatus(existing.status as MarketplaceCandidateStatus)
  ) {
    const { data, error } = await supabase
      .from("marketplace_operation_candidates")
      .update({
        raw_payload: candidate.raw_payload,
      })
      .eq("id", existing.id)
      .select("id")
      .single();

    if (error || !data) {
      throw new Error(error?.message ?? "Failed to preserve Ozon candidate");
    }
    return data;
  }

  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .upsert(candidate, {
      onConflict: "connection_id,source_type,external_event_id",
    })
    .select("id")
    .single();

  if (error || !data) {
    throw new Error(error?.message ?? "Failed to save Ozon candidate");
  }

  return data;
}

async function insertRows(
  supabase: SupabaseClient,
  table: string,
  rows: JsonRecord[]
) {
  for (const chunk of chunkArray(rows, 500)) {
    if (chunk.length === 0) continue;
    const { error } = await supabase.from(table).insert(chunk);
    if (error) throw new Error(error.message);
  }
}

function findLocalProductId(
  mapping: MappingContext,
  ozonProductId: string | null | undefined,
  keys: Array<string | null | undefined>
) {
  if (ozonProductId) {
    const preserved = mapping.ozonProductMappings.get(ozonProductId);
    if (preserved?.localId) return preserved.localId;
  }

  for (const key of keys) {
    if (!key) continue;
    const product = mapping.productsByExternalKey.get(normalizeKey(key));
    if (product) return product.id;
  }

  return null;
}

function findLocalWarehouseId(
  mapping: MappingContext,
  ozonWarehouseId: string | null | undefined,
  warehouseName: string | null | undefined
) {
  if (ozonWarehouseId) {
    const preserved = mapping.ozonWarehouseMappings.get(ozonWarehouseId);
    if (preserved?.localId) return preserved.localId;
  }

  if (!warehouseName) return null;
  return mapping.warehousesByName.get(normalizeKey(warehouseName))?.id ?? null;
}

function resolveMapping(
  preserved: ExistingMapping | undefined,
  autoLocalId: string | null
): ExistingMapping {
  if (preserved?.status === "manual" || preserved?.status === "ignored") {
    return preserved;
  }

  return {
    localId: autoLocalId,
    status: autoLocalId ? "auto_matched" : "unmapped",
  };
}

function indexExternalProducts(items: JsonRecord[]) {
  const map = new Map<string, JsonRecord>();
  for (const item of items) {
    for (const key of externalProductKeys(item)) {
      map.set(key, item);
    }
  }
  return map;
}

function lookupExternalProduct(
  map: Map<string, JsonRecord>,
  ref: ExternalProductRef
) {
  for (const key of [
    ref.ozonProductId,
    ref.offerId,
    ref.sku,
  ]) {
    if (!key) continue;
    const item = map.get(normalizeKey(key));
    if (item) return item;
  }

  return null;
}

function externalProductKeys(item: JsonRecord) {
  return [
    item.product_id,
    item.id,
    item.offer_id,
    item.sku,
  ]
    .map(toStringValue)
    .filter((value): value is string => Boolean(value))
    .map(normalizeKey);
}

function extractItems(value: unknown, keys: string[]) {
  if (Array.isArray(value)) return value;

  const root = unwrapResult(value);
  if (Array.isArray(root)) return root;

  for (const key of keys) {
    const direct = toRecord(value)[key];
    if (Array.isArray(direct)) return direct;
    const nested = toRecord(root)[key];
    if (Array.isArray(nested)) return nested;
  }

  return [];
}

function unwrapResult(value: unknown): JsonRecord {
  const record = toRecord(value);
  const result = record.result;
  if (Array.isArray(result)) return { items: result };
  if (isRecord(result)) return result;
  return record;
}

export function sanitizeOzonPayload(value: unknown, path: string[] = []): unknown {
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeOzonPayload(item, path));
  }
  if (!isRecord(value)) return value;

  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => !isPiiKey(key, path))
      .map(([key, item]) => [key, sanitizeOzonPayload(item, [...path, key])])
  );
}

function isPiiKey(key: string, path: string[] = []) {
  const normalized = normalizePayloadKey(key);
  const compact = compactPayloadKey(key);
  if (
    SAFE_LEGAL_IDENTIFIER_KEYS.has(normalized) ||
    Array.from(SAFE_LEGAL_IDENTIFIER_KEYS).some(
      (safeKey) => compactPayloadKey(safeKey) === compact
    )
  ) {
    return false;
  }

  if (
    (PERSONAL_NAME_KEYS.has(normalized) || PERSONAL_NAME_KEYS.has(compact)) &&
    path.some((pathKey) =>
      PERSONAL_CONTEXT_PATTERNS.some((pattern) =>
        compactPayloadKey(pathKey).includes(compactPayloadKey(pattern))
      )
    )
  ) {
    return true;
  }

  return PII_KEY_PATTERNS.some((pattern) => {
    const normalizedPattern = normalizePayloadKey(pattern);
    const compactPattern = compactPayloadKey(pattern);
    return normalized.includes(normalizedPattern) || compact.includes(compactPattern);
  });
}

function normalizePayloadKey(key: string) {
  return key.toLowerCase().replace(/[^a-zа-я0-9]+/g, "_").replace(/^_+|_+$/g, "");
}

function compactPayloadKey(key: string) {
  return key.toLowerCase().replace(/[^a-zа-я0-9]+/g, "");
}

function normalizeStatus(value: unknown) {
  return String(value ?? "").trim().toLowerCase();
}

function isDeliveredStatus(status: string) {
  return [
    "delivered",
    "posting_transferred_to_client",
    "received",
  ].includes(status);
}

function isCancelledStatus(status: string) {
  return status.includes("cancel");
}

function isReturnedStatus(status: string) {
  return (
    status.includes("return") ||
    status.includes("accepted") ||
    status.includes("received") ||
    status.includes("done")
  );
}

function mappingStatus(value: unknown): ExistingMapping["status"] {
  if (
    value === "auto_matched" ||
    value === "manual" ||
    value === "ignored" ||
    value === "unmapped"
  ) {
    return value;
  }
  return "unmapped";
}

function stableHash(value: unknown) {
  return createHash("sha256")
    .update(JSON.stringify(sanitizeOzonPayload(value)))
    .digest("hex");
}

function datesInRange(dateFrom: string, dateTo: string) {
  const start = new Date(dateFrom);
  const end = new Date(dateTo);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return [];

  start.setUTCHours(0, 0, 0, 0);
  end.setUTCHours(0, 0, 0, 0);

  const dates: string[] = [];
  for (
    const current = new Date(start);
    current.getTime() <= end.getTime();
    current.setUTCDate(current.getUTCDate() + 1)
  ) {
    dates.push(current.toISOString().slice(0, 10));
  }
  return dates;
}

function monthsInRange(dateFrom: string, dateTo: string) {
  const start = new Date(dateFrom);
  const end = new Date(dateTo);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return [];

  const months: string[] = [];
  const current = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), 1));
  const last = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth(), 1));

  while (current.getTime() <= last.getTime()) {
    months.push(current.toISOString().slice(0, 7));
    current.setUTCMonth(current.getUTCMonth() + 1);
  }

  return months;
}

function monthStartDate(value: unknown) {
  const text = toStringValue(value);
  if (!text) return null;
  const date = new Date(`${text.slice(0, 7)}-01T00:00:00.000Z`);
  return Number.isNaN(date.getTime()) ? null : date.toISOString().slice(0, 10);
}

function monthEndDate(value: unknown) {
  const text = toStringValue(value);
  if (!text) return null;
  const start = new Date(`${text.slice(0, 7)}-01T00:00:00.000Z`);
  if (Number.isNaN(start.getTime())) return null;
  const end = new Date(start);
  end.setUTCMonth(end.getUTCMonth() + 1);
  end.setUTCDate(0);
  return end.toISOString().slice(0, 10);
}

function extractInvoiceProducts(item: JsonRecord) {
  for (const value of [
    item.products,
    item.items,
    item.rows,
    item.invoice_products,
    toRecord(item.invoice).products,
    toRecord(item.invoice).items,
  ]) {
    if (Array.isArray(value) && value.length > 0) return value;
  }
  return [];
}

function sumProductsAmount(products: unknown[]) {
  return products.reduce<number>((sum, product) => {
    const item = toRecord(product);
    const quantity = toNumberValue(item.quantity ?? item.count) ?? 1;
    const price =
      toNumberValue(
        item.seller_price_per_instance ??
          item.price ??
          item.amount ??
          item.total_amount
      ) ?? 0;
    return sum + quantity * price;
  }, 0);
}

function isDisposalReason(reason: string) {
  return [
    "dispos",
    "utiliz",
    "write_off",
    "write-off",
    "loss",
    "lost",
  ].some((marker) => reason.includes(marker));
}

function isCompletedSupplyStatus(status: string) {
  return ["completed", "accepted", "done", "supplied", "closed", "received"].some(
    (marker) => status.includes(marker)
  );
}

function isDefectReason(reason: string) {
  return ["defect", "damage", "damaged", "broken", "brak"].some((marker) =>
    reason.includes(marker)
  );
}

function normalizeKey(value: string) {
  return value.trim().toLowerCase();
}

function numericId(value: string) {
  if (!/^\d+$/.test(value)) return null;
  return Number(value);
}

function toRecord(value: unknown): JsonRecord {
  return isRecord(value) ? value : {};
}

function isRecord(value: unknown): value is JsonRecord {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function asArray(value: unknown) {
  return Array.isArray(value) ? value : [];
}

function toStringValue(value: unknown) {
  if (typeof value === "string") return value.trim() || null;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  if (typeof value === "bigint") return String(value);
  return null;
}

function toNumberValue(value: unknown) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string") {
    const normalized = value.replace(/\s/g, "").replace(",", ".");
    const parsed = Number(normalized);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}

function moneyAmount(value: unknown) {
  return toNumberValue(toRecord(value).amount);
}

function moneyCurrency(value: unknown) {
  return toStringValue(toRecord(value).currency);
}

function financeAccrualItems(item: JsonRecord) {
  const postingProducts = asArray(toRecord(item.posting).products);
  if (postingProducts.length > 0) return postingProducts;
  return asArray(toRecord(item.item_fees).fees);
}

function financeAccrualServices(item: JsonRecord) {
  const services: unknown[] = [];
  const nonItemFee = toRecord(item.non_item_fee);
  if (Object.keys(nonItemFee).length > 0) services.push(nonItemFee);

  for (const product of asArray(toRecord(item.posting).products)) {
    const delivery = toRecord(toRecord(product).delivery);
    services.push(...asArray(delivery.services));
  }

  return services;
}

function toIsoString(value: unknown) {
  const text = toStringValue(value);
  if (!text) return null;
  const date = new Date(text);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function toDateOnly(value: unknown) {
  const iso = toIsoString(value);
  return iso ? iso.slice(0, 10) : null;
}

function chunkArray<T>(items: T[], size: number) {
  const chunks: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    chunks.push(items.slice(index, index + size));
  }
  return chunks;
}

function formatError(error: unknown) {
  if (error instanceof OzonApiError) {
    return `${error.endpoint}: ${error.status} ${JSON.stringify(error.responseBody).slice(0, 500)}`;
  }
  return error instanceof Error ? error.message : String(error);
}
