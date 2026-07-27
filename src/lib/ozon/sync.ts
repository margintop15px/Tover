import { createHash } from "node:crypto";
import type { SupabaseClient } from "@supabase/supabase-js";
import Papa from "papaparse";
import {
  canSyncUpdateCandidateStatus,
  normalizeOzonCandidateOperation,
  statusFromValidation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateStatus,
} from "@/lib/ozon/candidates";
import {
  OzonApiError,
  OzonClient,
  OzonIncompleteResponseError,
  OzonInvariantError,
  OzonReportPendingError,
  type OzonReadOnlyEndpoint,
} from "./client";
import type {
  LocalProductRef,
  LocalWarehouseRef,
  OzonSyncStepSummary,
} from "./types";

type JsonRecord = Record<string, unknown>;

export class OzonDatabaseError extends Error {
  constructor(
    readonly code: string | null,
    readonly operation: string,
    readonly permanent: boolean
  ) {
    super("Ozon database operation failed");
    this.name = "OzonDatabaseError";
  }
}

export class OzonReportDownloadError extends Error {
  constructor(readonly status: number) {
    super("Ozon report download failed");
    this.name = "OzonReportDownloadError";
  }
}

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
export const OZON_WAREHOUSE_TYPES = [
  "FULL_FILLMENT",
  "FULL_FILLMENT_RETURNS",
  "FULL_FILLMENT_DEFECT",
  "EXPRESS_DARK_STORE",
] as const;
export const OZON_SUPPLY_ORDER_STATES = [
  "DATA_FILLING",
  "READY_TO_SUPPLY",
  "ACCEPTED_AT_SUPPLY_WAREHOUSE",
  "IN_TRANSIT",
  "ACCEPTANCE_AT_STORAGE_WAREHOUSE",
  "REPORTS_CONFIRMATION_AWAITING",
  "REPORT_REJECTED",
  "COMPLETED",
  "REJECTED_AT_SUPPLY_WAREHOUSE",
  "CANCELLED",
  "OVERDUE",
] as const;
const DISCOUNTED_REPORT_REUSE_MS = 10 * 60 * 1000;
const OZON_REPORT_DOWNLOAD_LIMIT_BYTES = 10 * 1024 * 1024;
const OZON_REPORT_DOWNLOAD_TIMEOUT_MS = 30_000;
const OZON_REPORT_MAX_REDIRECTS = 5;
const TRUSTED_OZON_REPORT_DOMAINS = ["ozon.ru", "ozone.ru"] as const;

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

export interface OzonSyncDomainExecutionContext {
  supabase: SupabaseClient;
  client: OzonClient;
  workspaceId: string;
  connectionId: string;
  dateFrom: string;
  dateTo: string;
  runId?: string;
  checkpoint?: JsonRecord;
  saveCheckpoint?: (
    checkpoint: JsonRecord,
    summary?: OzonSyncStepSummary | Record<string, never>
  ) => Promise<void>;
  yieldIfNeeded?: () => void;
}

interface OzonSyncDomainDefinition {
  key: string;
  execute: (
    context: OzonSyncDomainExecutionContext
  ) => Promise<OzonSyncStepSummary>;
}

export const OZON_SYNC_DOMAIN_REGISTRY = [
  {
    key: "warehouses",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncWarehouses(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.runId,
          context
        )
      ),
  },
  {
    key: "products",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncProducts(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.runId,
          context
        )
      ),
  },
  {
    key: "stocks",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncStocks(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.runId,
          context
        )
      ),
  },
  {
    key: "postings",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncPostings(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.dateFrom,
          context.dateTo,
          context.runId,
          context
        )
      ),
  },
  {
    key: "returns",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncReturns(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.dateFrom,
          context.dateTo,
          context
        )
      ),
  },
  {
    key: "finance",
    execute: (context) =>
      executeWithCurrentMapping(context, () =>
        syncFinance(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          context.dateFrom,
          context.dateTo,
          context
        )
      ),
  },
  {
    key: "legalEntities",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncLegalEntities(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.dateFrom,
          context.dateTo,
          context.runId,
          context
        )
      ),
  },
  {
    key: "reports",
    execute: (context) =>
      executeWithCurrentMapping(context, () =>
        syncFinanceReports(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          context.dateFrom,
          context.dateTo,
          context
        )
      ),
  },
  {
    key: "removals",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncRemovals(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context.dateFrom,
          context.dateTo,
          context
        )
      ),
  },
  {
    key: "supplies",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncSupplies(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context
        )
      ),
  },
  {
    key: "analytics",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncStockAnalytics(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context
        )
      ),
  },
  {
    key: "discountedProducts",
    execute: (context) =>
      executeWithCurrentMapping(context, (mapping) =>
        syncDiscountedProducts(
          context.supabase,
          context.client,
          context.workspaceId,
          context.connectionId,
          mapping,
          context
        )
      ),
  },
] as const satisfies readonly OzonSyncDomainDefinition[];

export type OzonSyncDomainKey =
  (typeof OZON_SYNC_DOMAIN_REGISTRY)[number]["key"];

export async function executeOzonSyncDomainStep(
  key: OzonSyncDomainKey,
  context: OzonSyncDomainExecutionContext
) {
  const domain = OZON_SYNC_DOMAIN_REGISTRY.find(
    (entry) => entry.key === key
  );
  if (!domain) throw new Error("Unknown Ozon sync domain");
  return domain.execute(context);
}

async function executeWithCurrentMapping(
  context: OzonSyncDomainExecutionContext,
  execute: (mapping: MappingContext) => Promise<OzonSyncStepSummary>
) {
  const mapping = await loadMappingContext(
    context.supabase,
    context.workspaceId,
    context.connectionId
  );
  return execute(mapping);
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

  for (const [table, result] of [
    ["products", productsResult],
    ["warehouses", warehousesResult],
    ["ozon_products", ozonProductsResult],
    ["ozon_warehouses", ozonWarehousesResult],
  ] as const) {
    if (result.error) {
      throw ozonDatabaseError(result.error, `select:${table}`);
    }
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
  mapping: MappingContext,
  runId?: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(checkpoint.processed) ?? 0;
  let cursor =
    checkpoint.phase === "marketplace"
      ? toStringValue(checkpoint.cursor) ?? ""
      : "";
  let pageIndex =
    checkpoint.phase === "marketplace"
      ? toInteger(checkpoint.pageIndex) ?? 0
      : 0;
  let marketplaceWarehousesComplete =
    checkpoint.phase === "ozon" || checkpoint.phase === "complete";

  while (!marketplaceWarehousesComplete && pageIndex < 100) {
    execution?.yieldIfNeeded?.();
    const response = await client.request<JsonRecord>("/v2/warehouse/list", {
      limit: 200,
      cursor,
    });
    const root = unwrapResult(response);
    const pageWarehouses = requireItems(
      root,
      ["warehouses", "items"],
      "/v2/warehouse/list"
    );
    const rows = decodeWarehouseRows(
      pageWarehouses,
      workspaceId,
      connectionId,
      mapping,
      runId
    );
    await upsertRows(
      supabase,
      "ozon_warehouses",
      rows,
      "connection_id,ozon_warehouse_id"
    );
    fetched += pageWarehouses.length;
    pageIndex += 1;

    if (root.has_next !== true) {
      marketplaceWarehousesComplete = true;
      await execution?.saveCheckpoint?.(
        {
          phase: "ozon",
          cursor: "",
          pageIndex,
          processed: fetched,
          total: null,
        },
        { fetched }
      );
      break;
    }
    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || nextCursor === cursor || pageWarehouses.length === 0) {
      throw new OzonIncompleteResponseError(
        "Ozon warehouse page indicates more data without a new cursor"
      );
    }
    cursor = nextCursor;
    await execution?.saveCheckpoint?.(
      {
        phase: "marketplace",
        cursor,
        pageIndex,
        processed: fetched,
        total: null,
      },
      { fetched }
    );
  }
  if (!marketplaceWarehousesComplete) {
    throw new OzonIncompleteResponseError(
      "Ozon warehouse pagination exceeded the 100-page safety limit"
    );
  }

  if (checkpoint.phase !== "complete") {
    execution?.yieldIfNeeded?.();
    const ozonWarehousesResponse = await client.request<JsonRecord>(
      "/v1/warehouse/ozon/list",
      { warehouse_types: OZON_WAREHOUSE_TYPES }
    );
    const ozonWarehouses = requireItems(
      ozonWarehousesResponse,
      ["warehouses", "items"],
      "/v1/warehouse/ozon/list"
    );
    await upsertRows(
      supabase,
      "ozon_warehouses",
      decodeWarehouseRows(
        ozonWarehouses,
        workspaceId,
        connectionId,
        mapping,
        runId
      ),
      "connection_id,ozon_warehouse_id"
    );
    fetched += ozonWarehouses.length;
    await execution?.saveCheckpoint?.(
      {
        phase: "complete",
        processed: fetched,
        total: fetched,
      },
      { fetched }
    );
  }

  return { fetched };
}

function decodeWarehouseRows(
  warehouses: unknown[],
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
) {
  return [
    ...new Map(
      warehouses
        .map((item) => ({
          ...toWarehouseRow(
            item,
            workspaceId,
            connectionId,
            mapping
          ),
          ...ozonMirrorProvenance(runId),
        }))
        .map((row) => [String(row.ozon_warehouse_id), row])
    ).values(),
  ];
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
  if (!warehouseId || !name) {
    throw new OzonIncompleteResponseError(
      "Ozon warehouse response has no warehouse_id or name"
    );
  }

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
  mapping: MappingContext,
  runId?: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let lastId = toStringValue(checkpoint.lastId) ?? "";
  let pageIndex = toInteger(checkpoint.pageIndex) ?? 0;
  let fetched = toInteger(checkpoint.processed) ?? 0;

  while (pageIndex < 100) {
    execution?.yieldIfNeeded?.();
    const response = await client.request<JsonRecord>("/v3/product/list", {
      filter: { visibility: "ALL" },
      limit: PRODUCT_PAGE_LIMIT,
      last_id: lastId,
    });
    const root = unwrapResult(response);
    const items = requireItems(root, ["items", "products"], "/v3/product/list");
    const refs = items.map((item) => {
      const ref = toProductRef(item);
      if (!ref) {
        throw new OzonIncompleteResponseError(
          "Ozon product list item has no documented identifier"
        );
      }
      return ref;
    });

    const [details, prices, attributes] = await Promise.all([
      fetchProductDetails(client, refs),
      fetchProductPrices(client, refs),
      fetchProductAttributes(client, refs),
    ]);
    const detailMap = indexExternalProducts(details);
    const priceMap = indexExternalProducts(prices);
    const attributeMap = indexExternalProducts(attributes);
    const rows = refs.map((ref) => ({
      ...toProductRow(
        workspaceId,
        connectionId,
        mapping,
        ref,
        lookupExternalProduct(detailMap, ref),
        lookupExternalProduct(priceMap, ref),
        lookupExternalProduct(attributeMap, ref)
      ),
      ...ozonMirrorProvenance(runId),
    }));
    await upsertRows(
      supabase,
      "ozon_products",
      rows,
      "connection_id,ozon_product_id"
    );
    fetched += refs.length;
    pageIndex += 1;

    const nextLastId = toStringValue(root.last_id ?? root.cursor ?? response.cursor);
    if (!nextLastId || items.length === 0) {
      await execution?.saveCheckpoint?.(
        {
          phase: "complete",
          pageIndex,
          processed: fetched,
          total: fetched,
        },
        { fetched }
      );
      return { fetched };
    }
    if (nextLastId === lastId) {
      throw new OzonIncompleteResponseError(
        "Ozon product list repeated its pagination identifier"
      );
    }
    lastId = nextLastId;
    await execution?.saveCheckpoint?.(
      {
        phase: "products",
        lastId,
        pageIndex,
        processed: fetched,
        total: null,
      },
      { fetched }
    );
  }

  throw new OzonIncompleteResponseError(
    "Ozon product list exceeded the 100-page safety limit"
  );
}

export async function fetchProductDetails(
  client: OzonClient,
  refs: ExternalProductRef[]
) {
  const details: JsonRecord[] = [];

  for (const chunk of chunkArray(refs, 100)) {
    const productIds = chunk
      .map((ref) => ref.ozonProductId)
      .filter((value): value is string => /^\d+$/.test(value));
    const refsWithoutProductId = chunk.filter(
      (ref) => !/^\d+$/.test(ref.ozonProductId)
    );
    const offerIds = refsWithoutProductId
      .map((ref) => ref.offerId)
      .filter((value): value is string => Boolean(value));
    const skus = refsWithoutProductId
      .filter((ref) => !ref.offerId)
      .map((ref) => ref.sku)
      .filter((value): value is string => Boolean(value));
    const requestBodies: JsonRecord[] = [];

    if (productIds.length > 0) {
      requestBodies.push({ product_id: [...new Set(productIds)] });
    }
    if (offerIds.length > 0) {
      requestBodies.push({ offer_id: [...new Set(offerIds)] });
    }
    if (skus.length > 0) {
      requestBodies.push({ sku: [...new Set(skus)] });
    }
    if (requestBodies.length === 0) {
      throw new OzonIncompleteResponseError(
        "Ozon product reference has no identifier accepted by the product info endpoint"
      );
    }

    for (const requestBody of requestBodies) {
      const response = await client.request<JsonRecord>(
        "/v3/product/info/list",
        requestBody
      );
      details.push(
        ...(requireItems(
          response,
          ["items", "products"],
          "/v3/product/info/list"
        ) as JsonRecord[])
      );
    }
  }

  return details;
}

async function fetchProductPrices(client: OzonClient, refs: ExternalProductRef[]) {
  const prices: JsonRecord[] = [];
  for (const chunk of chunkArray(refs, 100)) {
    const productIds = chunk
      .map((ref) => ref.ozonProductId)
      .filter((value): value is string => /^\d+$/.test(value));
    const offerIds = chunk
      .map((ref) => ref.offerId)
      .filter((value): value is string => Boolean(value));
    let cursor = "";
    let complete = false;
    for (let page = 0; page < 100; page += 1) {
      const response = await client.request<JsonRecord>(
        "/v5/product/info/prices",
        {
          filter: {
            product_id: productIds,
            offer_id: offerIds,
            visibility: "ALL",
          },
          limit: PRODUCT_PAGE_LIMIT,
          cursor,
        }
      );
      const root = unwrapResult(response);
      const items = requireItems(
        root,
        ["items", "products"],
        "/v5/product/info/prices"
      );
      prices.push(...(items as JsonRecord[]));
      const nextCursor = toStringValue(root.cursor ?? response.cursor);
      if (!nextCursor || items.length === 0) {
        complete = true;
        break;
      }
      if (nextCursor === cursor) {
        throw new OzonIncompleteResponseError(
          "Ozon product price list repeated its cursor"
        );
      }
      cursor = nextCursor;
    }
    if (!complete) {
      throw new OzonIncompleteResponseError(
        "Ozon product price list exceeded the 100-page safety limit"
      );
    }
  }

  return prices;
}

async function fetchProductAttributes(
  client: OzonClient,
  refs: ExternalProductRef[]
) {
  const attributes: JsonRecord[] = [];
  for (const chunk of chunkArray(refs, 100)) {
    const productIds = chunk
      .map((ref) => ref.ozonProductId)
      .filter((value): value is string => /^\d+$/.test(value));
    const offerIds = chunk
      .map((ref) => ref.offerId)
      .filter((value): value is string => Boolean(value));
    let lastId = "";
    let complete = false;
    for (let page = 0; page < 100; page += 1) {
      const response = await client.request<JsonRecord>(
        "/v4/product/info/attributes",
        {
          filter: {
            product_id: productIds,
            offer_id: offerIds,
            visibility: "ALL",
          },
          limit: PRODUCT_PAGE_LIMIT,
          last_id: lastId,
        }
      );
      const root = unwrapResult(response);
      const items = requireItems(
        root,
        ["items", "products"],
        "/v4/product/info/attributes"
      );
      attributes.push(...(items as JsonRecord[]));

      const nextLastId = toStringValue(
        root.last_id ?? root.cursor ?? response.cursor
      );
      if (!nextLastId || items.length === 0) {
        complete = true;
        break;
      }
      if (nextLastId === lastId) {
        throw new OzonIncompleteResponseError(
          "Ozon product attributes repeated their pagination identifier"
        );
      }
      lastId = nextLastId;
    }
    if (!complete) {
      throw new OzonIncompleteResponseError(
        "Ozon product attributes exceeded the 100-page safety limit"
      );
    }
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
  const priceFields = toRecord(source.price);

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    ozon_product_id: ref.ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(source.name),
    currency_code: toStringValue(
      priceFields.currency_code ??
        priceFields.currency ??
        source.currency_code ??
        source.currency
    ),
    price: decimalString(priceFields.marketing_price ?? priceFields.price ?? source.price),
    old_price: decimalString(priceFields.old_price ?? source.old_price),
    min_price: decimalString(priceFields.min_price ?? source.min_price),
    status: toStringValue(statuses.status ?? source.status),
    visibility: Object.keys(visibility).length > 0 ? JSON.stringify(visibility) : null,
    description_category_id: toStringValue(source.description_category_id),
    type_id: toStringValue(source.type_id),
    barcodes,
    images: [
      ...stringOrArray(source.primary_image),
      ...stringOrArray(source.images),
      ...stringOrArray(source.color_image),
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
  mapping: MappingContext,
  runId?: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let cursor = toStringValue(checkpoint.cursor) ?? "";
  const snapshotAt =
    toStringValue(checkpoint.snapshotAt) ?? new Date().toISOString();
  let fetched = toInteger(checkpoint.processed) ?? 0;
  let pageIndex = toInteger(checkpoint.pageIndex) ?? 0;
  let complete = checkpoint.phase === "complete";

  while (!complete && pageIndex < 100) {
    execution?.yieldIfNeeded?.();
    const response = await client.request<JsonRecord>("/v4/product/info/stocks", {
      filter: { visibility: "ALL" },
      limit: PRODUCT_PAGE_LIMIT,
      cursor,
    });
    const root = unwrapResult(response);
    const items = requireItems(
      root,
      ["items", "products"],
      "/v4/product/info/stocks"
    );

    const rows: JsonRecord[] = [];
    for (const item of items) {
      rows.push(
        ...toStockRows(
          item,
          workspaceId,
          connectionId,
          mapping,
          snapshotAt,
          runId
        )
      );
    }
    await insertRows(supabase, "ozon_stock_snapshots", rows);
    fetched += rows.length;
    pageIndex += 1;

    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || items.length === 0) {
      complete = true;
      await execution?.saveCheckpoint?.(
        {
          phase: "complete",
          cursor: "",
          snapshotAt,
          pageIndex,
          processed: fetched,
          total: fetched,
        },
        { fetched }
      );
      break;
    }
    if (nextCursor === cursor) {
      throw new OzonIncompleteResponseError(
        "Ozon stock list repeated its cursor"
      );
    }
    cursor = nextCursor;
    await execution?.saveCheckpoint?.(
      {
        phase: "stocks",
        cursor,
        snapshotAt,
        pageIndex,
        processed: fetched,
        total: null,
      },
      { fetched }
    );
  }
  if (!complete) {
    throw new OzonIncompleteResponseError(
      "Ozon stock list exceeded the 100-page safety limit"
    );
  }

  return { fetched };
}

function toStockRows(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  snapshotAt: string,
  runId?: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(
    item.product_id ?? item.id ?? item.sku ?? item.offer_id
  );
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);
  const stockItems = decodeProductStockEntries(item);

  return stockItems.map((stock) => {
    const stockRecord = toRecord(stock);
    const stockType = toStringValue(stockRecord.type);

    return {
      workspace_id: workspaceId,
      connection_id: connectionId,
      snapshot_at: snapshotAt,
      ozon_product_id: ozonProductId,
      offer_id: offerId,
      sku,
      warehouse_name: null,
      ozon_warehouse_id: null,
      fulfillment_schema: stockType,
      present: requiredDecimal(
        stockRecord.present,
        "/v4/product/info/stocks",
        "stocks.present"
      ),
      reserved: requiredDecimal(
        stockRecord.reserved,
        "/v4/product/info/stocks",
        "stocks.reserved"
      ),
      raw_payload: sanitizeOzonPayload({ item, stock: stockRecord }),
      local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
      local_warehouse_id: null,
      ...ozonMirrorProvenance(runId),
    };
  });
}

export function decodeProductStockEntries(value: unknown) {
  const stocks = toRecord(value).stocks;
  if (!Array.isArray(stocks)) {
    throw new OzonIncompleteResponseError(
      "Ozon /v4/product/info/stocks response has no stocks array"
    );
  }
  return stocks;
}

async function syncPostings(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string,
  runId?: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(checkpoint.processed) ?? 0;
  let createdCandidates = toInteger(checkpoint.createdCandidates) ?? 0;
  const startSchemaIndex =
    checkpoint.phase === "postings"
      ? toInteger(checkpoint.schemaIndex) ?? 0
      : checkpoint.phase === "complete"
        ? 2
        : 0;

  for (
    let schemaIndex = startSchemaIndex;
    schemaIndex < 2;
    schemaIndex += 1
  ) {
    const schema = (["fbs", "fbo"] as const)[schemaIndex];
    const endpoint =
      schema === "fbs" ? "/v4/posting/fbs/list" : "/v3/posting/fbo/list";
    let cursor =
      schemaIndex === startSchemaIndex
        ? toStringValue(checkpoint.cursor) ?? ""
        : "";
    let pageIndex =
      schemaIndex === startSchemaIndex
        ? toInteger(checkpoint.pageIndex) ?? 0
        : 0;
    let complete = false;

    while (!complete && pageIndex < 200) {
      execution?.yieldIfNeeded?.();
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
      const items = requireItems(response, ["postings", "items"], endpoint);
      fetched += items.length;

      for (const posting of items) {
        const result = await upsertPosting(
          supabase,
          workspaceId,
          connectionId,
          schema,
          posting,
          mapping,
          runId
        );
        createdCandidates += result.createdCandidate ? 1 : 0;
      }
      pageIndex += 1;

      const root = unwrapResult(response);
      if (root.has_next !== true) {
        complete = true;
        const nextSchemaIndex = schemaIndex + 1;
        await execution?.saveCheckpoint?.(
          {
            phase: nextSchemaIndex < 2 ? "postings" : "complete",
            schemaIndex: nextSchemaIndex,
            cursor: "",
            pageIndex: 0,
            processed: fetched,
            createdCandidates,
            total: null,
          },
          { fetched, createdCandidates }
        );
        break;
      }
      const nextCursor = toStringValue(root.cursor ?? response.cursor);
      if (!nextCursor || nextCursor === cursor) {
        throw new OzonIncompleteResponseError(
          `Ozon ${endpoint} response indicates more postings without a new cursor`
        );
      }
      cursor = nextCursor;
      await execution?.saveCheckpoint?.(
        {
          phase: "postings",
          schemaIndex,
          cursor,
          pageIndex,
          processed: fetched,
          createdCandidates,
          total: null,
        },
        { fetched, createdCandidates }
      );
    }
    if (!complete) {
      throw new OzonIncompleteResponseError(
        `Ozon ${endpoint} exceeded the 200-page safety limit`
      );
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
  mapping: MappingContext,
  runId?: string
) {
  const item = toRecord(value);
  const postingNumber = toStringValue(
    item.posting_number ?? item.postingNumber ?? item.order_number
  );
  if (!postingNumber) {
    throw new OzonIncompleteResponseError(
      `Ozon ${schema.toUpperCase()} posting has no posting_number`
    );
  }

  const deliveryMethod = toRecord(item.delivery_method);
  const cancellation = toRecord(item.cancellation);
  const analyticsData = toRecord(item.analytics_data);
  const warehouseName = toStringValue(
    schema === "fbs"
      ? analyticsData.warehouse ?? deliveryMethod.warehouse
      : analyticsData.warehouse_name
  );
  const warehouseId = toStringValue(
    schema === "fbo"
      ? analyticsData.warehouse_id
      : analyticsData.warehouse_id ?? warehouseName
  );
  const localWarehouseId = findLocalWarehouseId(
    mapping,
    warehouseId,
    warehouseName
  );

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
    ...ozonMirrorProvenance(runId),
  };

  const products = asArray(item.products);
  const itemRows = products.map((product) =>
    toPostingItemRow(
      product,
      workspaceId,
      connectionId,
      PERSISTENCE_PLACEHOLDER_ID,
      mapping,
      runId,
      schema
    )
  );

  const { data: postingId, error: replaceError } = await supabase.rpc(
    "replace_ozon_posting_with_items_v2",
    {
      p_parent: postingRow,
      p_rows: itemRows,
    }
  );
  if (replaceError || typeof postingId !== "string") {
    throw ozonDatabaseError(
      replaceError ?? {},
      "rpc:replace_ozon_posting_with_items_v2"
    );
  }
  const { data: posting, error } = await supabase
    .from("ozon_postings")
    .select("*")
    .eq("id", postingId)
    .single();
  if (error || !posting) {
    throw ozonDatabaseError(error ?? {}, "select:ozon_postings");
  }

  const candidate = buildPostingCandidate(
    posting as JsonRecord,
    itemRows,
    schema,
    postingNumber
  );

  if (!candidate) return { createdCandidate: false };

  const candidateRow = await upsertCandidatePreservingReview(supabase, candidate);

  const { error: linkError } = await supabase
    .from("ozon_postings")
    .update({ operation_candidate_id: candidateRow.id })
    .eq("id", posting.id);
  if (linkError) {
    throw ozonDatabaseError(linkError, "update:ozon_postings");
  }

  return { createdCandidate: true };
}

function toPostingItemRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  postingId: string,
  mapping: MappingContext,
  runId: string | undefined,
  schema: "fbs" | "fbo"
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
    quantity: requiredDecimal(
      item.quantity,
      schema === "fbs" ? "/v4/posting/fbs/list" : "/v3/posting/fbo/list",
      "products.quantity"
    ),
    price: requiredMoneyAmount(
      item.price,
      schema === "fbs" ? "/v4/posting/fbs/list" : "/v3/posting/fbo/list",
      "products.price"
    ),
    currency_code: requiredMoneyCurrency(
      item.price,
      schema === "fbs" ? "/v4/posting/fbs/list" : "/v3/posting/fbo/list",
      "products.price"
    ),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    ...ozonMirrorProvenance(runId),
  };
}

function buildPostingCandidate(
  posting: JsonRecord,
  itemRows: JsonRecord[],
  schema: "fbs" | "fbo",
  postingNumber: string
) {
  const status = normalizeStatus(posting.status);
  // The list contracts do not expose a `delivered_at` field. Finality comes
  // from the documented delivered status; use only the documented posting
  // processing timestamp as the source event date.
  const operationDate = toDateOnly(posting.in_process_at);

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
      raw_payload: sanitizeOzonPayload(posting.raw_payload ?? posting),
    };
  }

  if (!isDeliveredStatus(status) || !operationDate) return null;

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
      quantity: decimalString(item.quantity),
      unitPrice: decimalString(item.price),
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
    raw_payload: sanitizeOzonPayload(posting.raw_payload ?? posting),
  };
}

async function syncReturns(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const savedCheckpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(savedCheckpoint.processed) ?? 0;
  let createdCandidates = 0;
  let lastId =
    savedCheckpoint.phase === "returns"
      ? toStringValue(savedCheckpoint.lastId) ?? ""
      : "";
  let standardReturnsComplete = savedCheckpoint.phase === "rfbs";

  if (!standardReturnsComplete) {
    for (let page = 0; page < 100; page += 1) {
      execution?.yieldIfNeeded?.();
      const response = await client.request<JsonRecord>(
        "/v1/returns/list",
        buildReturnsListRequest(lastId, dateFrom, dateTo)
      );
      const root = toRecord(response);
      const items = requireArrayMember(root, "returns", "/v1/returns/list");
      fetched += items.length;

      for (const item of items) {
        const created = await upsertReturn(
          supabase,
          workspaceId,
          connectionId,
          item,
          mapping,
          "fbo_fbs",
          execution?.runId
        );
        if (created) createdCandidates += 1;
      }

      if (root.has_next !== true) {
        standardReturnsComplete = true;
        await execution?.saveCheckpoint?.(
          { phase: "rfbs", lastId: "", processed: fetched, total: null },
          { fetched, createdCandidates }
        );
        break;
      }
      const nextLastId = returnPageCursor(
        items,
        "id",
        "/v1/returns/list"
      );
      if (nextLastId === lastId) {
        throw new OzonIncompleteResponseError(
          "Ozon returns page repeated its last return identifier"
        );
      }
      lastId = nextLastId;
      await execution?.saveCheckpoint?.(
        { phase: "returns", lastId, processed: fetched, total: null },
        { fetched, createdCandidates }
      );
    }
    if (!standardReturnsComplete) {
      throw new OzonIncompleteResponseError(
        "Ozon returns pagination exceeded the 100-page safety limit"
      );
    }
  }

  lastId =
    savedCheckpoint.phase === "rfbs"
      ? toStringValue(savedCheckpoint.lastId) ?? ""
      : "";
  for (let page = 0; page < 100; page += 1) {
    execution?.yieldIfNeeded?.();
    const response = await client.request<JsonRecord>(
      "/v2/returns/rfbs/list",
      buildRfbsReturnsListRequest(lastId, dateFrom, dateTo)
    );
    const root = unwrapResult(response);
    const items = requireArrayMember(root, "returns", "/v2/returns/rfbs/list");
    const startDetailIndex =
      page === 0 && savedCheckpoint.phase === "rfbs"
        ? toInteger(savedCheckpoint.detailIndex) ?? 0
        : 0;

    for (
      let detailIndex = startDetailIndex;
      detailIndex < items.length;
      detailIndex += 1
    ) {
      const item = items[detailIndex];
      const returnId = toStringValue(
        toRecord(item).return_id ?? toRecord(item).id ?? toRecord(item).posting_number
      );
      if (!returnId) {
        throw new OzonIncompleteResponseError(
          "Ozon rFBS return list item has no return identifier"
        );
      }
      const detailResponse = await client.request<JsonRecord>(
        "/v2/returns/rfbs/get",
        { return_id: returnId }
      );
      const detailMember = toRecord(detailResponse).returns;
      const detail = Array.isArray(detailMember)
        ? detailMember[0]
        : detailMember ?? unwrapResult(detailResponse);
      if (!isRecord(detail)) {
        throw new OzonIncompleteResponseError(
          "Ozon rFBS return detail has no returns member"
        );
      }
      const created = await upsertReturn(
        supabase,
        workspaceId,
        connectionId,
        detail,
        mapping,
        "rfbs",
        execution?.runId
      );
      if (created) createdCandidates += 1;
      fetched += 1;
      await execution?.saveCheckpoint?.(
        {
          phase: "rfbs",
          lastId,
          detailIndex: detailIndex + 1,
          processed: fetched,
          total: null,
        },
        { fetched, createdCandidates }
      );
    }

    if (items.length < POSTING_PAGE_LIMIT) return { fetched, createdCandidates };
    const nextLastId = returnPageCursor(
      items,
      "return_id",
      "/v2/returns/rfbs/list"
    );
    if (nextLastId === lastId) {
      throw new OzonIncompleteResponseError(
        "Ozon rFBS returns page repeated its last return identifier"
      );
    }
    lastId = nextLastId;
    await execution?.saveCheckpoint?.(
      {
        phase: "rfbs",
        lastId,
        detailIndex: 0,
        processed: fetched,
        total: null,
      },
      { fetched, createdCandidates }
    );
  }

  throw new OzonIncompleteResponseError(
    "Ozon rFBS returns pagination exceeded the 100-page safety limit"
  );
}

export function buildReturnsListRequest(
  lastId: string,
  dateFrom: string,
  dateTo: string
) {
  return {
    filter: {
      logistic_return_date: {
        time_from: dateFrom,
        time_to: dateTo,
      },
    },
    limit: POSTING_PAGE_LIMIT,
    last_id: lastId || 0,
  };
}

export function buildRfbsReturnsListRequest(
  lastId: string,
  dateFrom: string,
  dateTo: string
) {
  return {
    last_id: lastId || 0,
    limit: POSTING_PAGE_LIMIT,
    filter: {
      created_at: {
        from: dateFrom,
        to: dateTo,
      },
    },
  };
}

async function upsertReturn(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string,
  value: unknown,
  mapping: MappingContext,
  source: "fbo_fbs" | "rfbs",
  runId?: string
) {
  const decoded = decodeOzonReturn(value, source);
  const item = toRecord(value);
  const returnId = decoded.returnId;
  const ozonProductId = decoded.ozonProductId;
  const offerId = decoded.offerId;
  const sku = decoded.sku;
  const localProductId = findLocalProductId(mapping, ozonProductId, [offerId, sku]);
  const warehouseName = decoded.warehouseName;
  const warehouseId = decoded.warehouseId;
  const localWarehouseId = findLocalWarehouseId(
    mapping,
    warehouseId,
    warehouseName
  );

  const returnRow = {
    workspace_id: workspaceId,
    connection_id: connectionId,
    ozon_return_id: returnId,
    posting_number: decoded.postingNumber,
    status: decoded.status,
    return_schema: decoded.schema,
    logistic_return_date: decoded.logisticReturnDate,
    logistic_final_moment: decoded.logisticFinalMoment,
    returned_at: decoded.returnedAt,
    offer_id: offerId,
    sku,
    ozon_product_id: ozonProductId,
    quantity: decoded.quantity,
    price: decoded.price,
    currency_code: decoded.currencyCode,
    ozon_warehouse_id: warehouseId,
    warehouse_name: warehouseName,
    local_warehouse_id: localWarehouseId,
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: localProductId,
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };

  const { data: savedReturn, error } = await supabase
    .from("ozon_returns")
    .upsert(returnRow, { onConflict: "connection_id,ozon_return_id" })
    .select("*")
    .single();

  if (error || !savedReturn) {
    throw ozonDatabaseError(error ?? {}, "upsert:ozon_returns");
  }

  const candidate = buildReturnCandidate({
    ...(savedReturn as JsonRecord),
    product_name: decoded.productName,
    warehouse_name: warehouseName,
    ozon_warehouse_id: warehouseId,
    local_warehouse_id: localWarehouseId,
  });
  if (!candidate) return false;

  const candidateRow = await upsertCandidatePreservingReview(supabase, candidate);

  const { error: linkError } = await supabase
    .from("ozon_returns")
    .update({ operation_candidate_id: candidateRow.id })
    .eq("id", savedReturn.id);
  if (linkError) {
    throw ozonDatabaseError(linkError, "update:ozon_returns");
  }

  return true;
}

interface DecodedOzonReturn {
  returnId: string;
  postingNumber: string | null;
  status: string | null;
  schema: string | null;
  logisticReturnDate: string | null;
  logisticFinalMoment: string | null;
  returnedAt: string | null;
  offerId: string | null;
  sku: string | null;
  ozonProductId: string | null;
  productName: string | null;
  quantity: string | null;
  price: string | null;
  currencyCode: string | null;
  warehouseId: string | null;
  warehouseName: string | null;
}

export function decodeOzonReturn(
  value: unknown,
  source: "fbo_fbs" | "rfbs"
): DecodedOzonReturn {
  const item = toRecord(value);
  const product = toRecord(item.product ?? item.item);
  const logistic = toRecord(item.logistic);
  const visualStatus = toRecord(toRecord(item.visual).status);
  const state = toRecord(item.state);
  const returnId = toStringValue(
    source === "fbo_fbs"
      ? item.id
      : item.return_id ?? item.return_number
  );
  if (!returnId) {
    throw new OzonIncompleteResponseError(
      `Ozon ${source === "fbo_fbs" ? "/v1/returns/list" : "/v2/returns/rfbs/get"} response has no return identifier`
    );
  }

  const returnPlace =
    source === "fbo_fbs"
      ? toRecord(item.target_place ?? item.place)
      : toRecord(item.warehouse);
  const warehouseName = toStringValue(
    item.warehouse_name ??
      item.destination_warehouse_name ??
      returnPlace.name ??
      returnPlace.warehouse_name
  );
  const warehouseId = toStringValue(
    item.warehouse_id ??
      returnPlace.warehouse_id ??
      returnPlace.id ??
      warehouseName
  );
  const price =
    source === "fbo_fbs"
      ? toRecord(product.price)
      : product;
  const logisticReturnDate =
    source === "fbo_fbs" ? toIsoString(logistic.return_date) : null;
  const logisticFinalMoment =
    source === "fbo_fbs" ? toIsoString(logistic.final_moment) : null;

  return {
    returnId,
    postingNumber: toStringValue(item.posting_number),
    status: toStringValue(
      source === "fbo_fbs"
        ? visualStatus.sys_name
        : state.state ?? state.group_state
    ),
    schema:
      toStringValue(item.schema ?? item.return_schema) ??
      (source === "rfbs" ? "Rfbs" : null),
    logisticReturnDate,
    logisticFinalMoment,
    returnedAt: logisticFinalMoment ?? logisticReturnDate,
    offerId: toStringValue(product.offer_id ?? item.offer_id),
    sku: toStringValue(product.sku ?? item.sku),
    ozonProductId: toStringValue(
      product.product_id ?? item.product_id ?? product.sku ?? item.sku
    ),
    productName: toStringValue(product.name ?? item.name),
    quantity: decimalString(product.quantity ?? item.quantity),
    price:
      source === "fbo_fbs"
        ? decimalString(price.price)
        : decimalString(product.price ?? item.price),
    currencyCode:
      source === "fbo_fbs"
        ? toStringValue(price.currency_code)
        : toStringValue(product.currency_code ?? item.currency_code),
    warehouseId,
    warehouseName,
  };
}

function returnPageCursor(
  items: unknown[],
  field: "id" | "return_id",
  endpoint: string
) {
  const cursor = toStringValue(toRecord(items.at(-1))[field]);
  if (!cursor) {
    throw new OzonIncompleteResponseError(
      `Ozon ${endpoint} response cannot continue without ${field}`
    );
  }
  return cursor;
}

function buildReturnCandidate(returnRow: JsonRecord) {
  const status = normalizeStatus(returnRow.status);
  if (!isReturnReceivedBySellerStatus(status)) return null;

  const operationDate = toDateOnly(returnRow.logistic_final_moment);
  if (
    !operationDate ||
    !toStringValue(
      returnRow.ozon_product_id ?? returnRow.offer_id ?? returnRow.sku
    ) ||
    !toStringValue(
      returnRow.ozon_warehouse_id ?? returnRow.warehouse_name
    ) ||
    !positiveDecimal(returnRow.quantity)
  ) {
    return null;
  }
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
        quantity: decimalString(returnRow.quantity),
        unitPrice: decimalString(returnRow.price),
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
  dateTo: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const accrualTypes = await fetchFinanceAccrualTypes(client);
  const savedCheckpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(savedCheckpoint.processed) ?? 0;
  const dates = datesInRange(dateFrom, dateTo);
  const startDateIndex = toInteger(savedCheckpoint.dateIndex) ?? 0;

  for (let dateIndex = startDateIndex; dateIndex < dates.length; dateIndex += 1) {
    const date = dates[dateIndex];
    let lastId =
      dateIndex === startDateIndex
        ? toStringValue(savedCheckpoint.lastId) ?? ""
        : "";
    let dateComplete = false;
    for (let page = 1; page <= FINANCE_ACCRUAL_PAGE_LIMIT; page += 1) {
      execution?.yieldIfNeeded?.();
      const response = await client.request<JsonRecord>(
        "/v1/finance/accrual/by-day",
        {
          date,
          last_id: lastId,
        }
      );
      const root = unwrapResult(response);
      const accruals = requireItems(
        root,
        ["accruals", "items"],
        "/v1/finance/accrual/by-day"
      );

      const uniqueAccruals = deduplicateFinanceAccruals(accruals);
      const pageRows = uniqueAccruals.map((item, index) =>
          toFinanceAccrualRow(item, workspaceId, connectionId, {
            accrualTypes,
            date,
            index,
            page,
          }, execution?.runId)
        ) as JsonRecord[];
      await upsertRows(
        supabase,
        "ozon_finance_transactions",
        pageRows,
        "connection_id,transaction_id"
      );
      fetched += pageRows.length;

      const nextLastId = toStringValue(root.last_id);
      if (!nextLastId || accruals.length === 0) {
        await execution?.saveCheckpoint?.(
          {
            phase: "accruals",
            dateIndex: dateIndex + 1,
            lastId: "",
            processed: fetched,
            total: null,
          },
          { fetched }
        );
        dateComplete = true;
        break;
      }
      if (nextLastId === lastId) {
        throw new OzonIncompleteResponseError(
          `Ozon finance accruals for ${date} repeated their last_id`
        );
      }
      lastId = nextLastId;
      await execution?.saveCheckpoint?.(
        {
          phase: "accruals",
          dateIndex,
          lastId,
          processed: fetched,
          total: null,
        },
        { fetched }
      );
    }
    if (!dateComplete) {
      throw new OzonIncompleteResponseError(
        `Ozon finance accruals for ${date} exceeded the page safety limit`
      );
    }
  }

  return { fetched };
}

async function fetchFinanceAccrualTypes(client: OzonClient) {
  const response = await client.request<JsonRecord>("/v1/finance/accrual/types", {});
  const root = unwrapResult(response);
  const types = requireItems(
    root,
    ["accrual_types", "items"],
    "/v1/finance/accrual/types"
  );
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
  },
  runId?: string
) {
  const item = toRecord(value);
  const posting = toRecord(item.posting);
  const nonItemFee = toRecord(item.non_item_fee);
  const typeId = toStringValue(item.type_id ?? nonItemFee.type_id);
  const accrualType = typeId ? context.accrualTypes.get(typeId) ?? null : null;
  const transactionId = toStringValue(item.accrual_id);
  if (!transactionId) {
    throw new OzonIncompleteResponseError(
      "Ozon finance accrual has no accrual_id"
    );
  }
  const amount = moneyAmount(item.total_amount);
  const currencyCode = moneyCurrency(item.total_amount);
  if (amount === null || !currencyCode) {
    throw new OzonIncompleteResponseError(
      "Ozon finance accrual has no total_amount Money value"
    );
  }

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
    amount,
    currency_code: currencyCode,
    items: financeAccrualItems(item),
    services: financeAccrualServices(item),
    raw_payload: sanitizeOzonPayload({
      ...item,
      accrual_type: accrualType,
    }),
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

async function syncLegalEntities(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string,
  runId?: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(checkpoint.processed) ?? 0;
  const months = monthsInRange(dateFrom, dateTo);
  const startMonthIndex =
    checkpoint.phase === "legal_months"
      ? toInteger(checkpoint.monthIndex) ?? 0
      : checkpoint.phase === "unpaid" || checkpoint.phase === "complete"
        ? months.length
        : 0;

  for (
    let monthIndex = startMonthIndex;
    monthIndex < months.length;
    monthIndex += 1
  ) {
    execution?.yieldIfNeeded?.();
    const month = months[monthIndex];
    const response = await client.request<JsonRecord>(
      "/v1/finance/document-b2b-sales/json",
      { date: month }
    );
    const root = unwrapResult(response);
    const invoices = requireItems(
      root,
      ["invoices", "items", "rows"],
      "/v1/finance/document-b2b-sales/json"
    );
    fetched += invoices.length;

    await upsertRows(
      supabase,
      "ozon_legal_entity_sales",
      invoices.map((invoice) => toLegalEntitySaleRow(
        invoice,
        workspaceId,
        connectionId,
        mapping,
        runId
      )),
      "connection_id,external_id"
    );
    await execution?.saveCheckpoint?.(
      {
        phase: monthIndex + 1 < months.length ? "legal_months" : "unpaid",
        monthIndex: monthIndex + 1,
        cursor: "",
        pageIndex: 0,
        processed: fetched,
        total: null,
      },
      { fetched, createdCandidates: 0 }
    );
  }

  if (checkpoint.phase !== "complete") {
    fetched += await syncUnpaidLegalProducts(
      supabase,
      client,
      workspaceId,
      connectionId,
      mapping,
      runId,
      execution,
      fetched
    );
  }

  return { fetched, createdCandidates: 0 };
}

function toLegalEntitySaleRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
) {
  const item = toRecord(value);
  const buyer = toRecord(item.buyer_info ?? item.buyer ?? {});
  const info = toRecord(item.info);
  const operations = asArray(item.operations).map(toRecord);
  const invoiceNumber = toStringValue(
    item.invoice_number ??
      info.invoice_number ??
      info.number ??
      item.number ??
      item.document_number
  );
  const postingNumbers = [
    ...new Set(
      operations
        .map((operation) => toStringValue(operation.posting_number))
        .filter((value): value is string => Boolean(value))
    ),
  ];
  const postingNumber =
    toStringValue(item.posting_number) ??
    (postingNumbers.length === 1 ? postingNumbers[0] : null);
  const products = extractInvoiceProducts(item);
  if (
    products.length === 0 &&
    (item.offer_id !== undefined || item.sku !== undefined)
  ) {
    products.push({
      product_id: item.product_id,
      offer_id: item.offer_id,
      sku: item.sku,
      name: item.product_name,
      quantity: item.quantity,
      seller_price_per_instance: item.seller_price_per_instance,
      operations: item.operations,
    });
  }
  const productIdentity = toStringValue(item.sku ?? item.offer_id);
  if (!invoiceNumber || !productIdentity) {
    throw new OzonIncompleteResponseError(
      "Ozon B2B sale has no invoice number or product identifier"
    );
  }
  const externalId =
    [invoiceNumber, productIdentity].join(":");
  const singleOperation = operations.length === 1 ? operations[0] : null;

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: externalId,
    invoice_number: invoiceNumber,
    invoice_date: toDateOnly(
      item.invoice_date ??
        info.invoice_date ??
        info.date ??
        item.date ??
        item.sale_date ??
        item.created_at
    ),
    posting_number: postingNumber,
    buyer_company_name: toStringValue(
      buyer.company_name ??
        buyer.organization_name ??
        buyer.name ??
        item.buyer_company_name ??
        item.company_name
    ),
    buyer_inn: toStringValue(buyer.inn ?? buyer.buyer_inn ?? item.buyer_inn),
    buyer_kpp: toStringValue(buyer.kpp ?? buyer.buyer_kpp ?? item.buyer_kpp),
    amount: singleOperation
      ? moneyAmount(singleOperation.amount) ??
        decimalString(singleOperation.amount)
      : null,
    currency_code:
      (singleOperation ? moneyCurrency(singleOperation.amount) : null) ??
      toStringValue(item.currency ?? item.currency_code),
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
    ...ozonMirrorProvenance(runId),
  };
}

async function syncUnpaidLegalProducts(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string,
  execution?: OzonSyncDomainExecutionContext,
  baseProcessed = 0
) {
  const checkpoint = toRecord(execution?.checkpoint);
  let cursor =
    checkpoint.phase === "unpaid"
      ? toStringValue(checkpoint.cursor) ?? ""
      : "";
  let pageIndex =
    checkpoint.phase === "unpaid"
      ? toInteger(checkpoint.pageIndex) ?? 0
      : 0;
  let fetched = 0;
  let complete = checkpoint.phase === "complete";

  while (!complete && pageIndex < 50) {
    execution?.yieldIfNeeded?.();
    const response = await client.request<JsonRecord>(
      "/v1/posting/unpaid-legal/product/list",
      { cursor, limit: 1000 }
    );
    const root = unwrapResult(response);
    const products = requireItems(
      root,
      ["products", "items", "rows"],
      "/v1/posting/unpaid-legal/product/list"
    );
    const rows = products.map((product) =>
        toUnpaidLegalProductRow(
          product,
          workspaceId,
          connectionId,
          mapping,
          runId
        )
    );
    await upsertRows(
      supabase,
      "ozon_unpaid_legal_products",
      rows,
      "connection_id,external_id"
    );
    fetched += rows.length;
    pageIndex += 1;
    const nextCursor = toStringValue(root.cursor ?? response.cursor);
    if (!nextCursor || products.length === 0) {
      complete = true;
      await execution?.saveCheckpoint?.(
        {
          phase: "complete",
          cursor: "",
          pageIndex,
          processed: baseProcessed + fetched,
          total: baseProcessed + fetched,
        },
        { fetched: baseProcessed + fetched, createdCandidates: 0 }
      );
      break;
    }
    if (nextCursor === cursor) {
      throw new OzonIncompleteResponseError(
        "Ozon unpaid-legal product list repeated its cursor"
      );
    }
    cursor = nextCursor;
    await execution?.saveCheckpoint?.(
      {
        phase: "unpaid",
        cursor,
        pageIndex,
        processed: baseProcessed + fetched,
        total: null,
      },
      { fetched: baseProcessed + fetched, createdCandidates: 0 }
    );
  }
  if (!complete) {
    throw new OzonIncompleteResponseError(
      "Ozon unpaid-legal product list exceeded the 50-page safety limit"
    );
  }

  return fetched;
}

function toUnpaidLegalProductRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.sku);
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);
  const postingNumber = toStringValue(item.posting_number);
  const externalIdentity = toStringValue(item.id ?? item.product_id ?? item.sku);
  if (!externalIdentity) {
    throw new OzonIncompleteResponseError(
      "Ozon unpaid-legal product has no documented identifier"
    );
  }

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: [postingNumber, externalIdentity].filter(Boolean).join(":"),
    posting_number: postingNumber,
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name),
    quantity: decimalString(item.quantity),
    amount:
      moneyAmount(item.amount ?? item.price) ??
      decimalString(item.amount ?? item.price),
    currency_code:
      moneyCurrency(item.amount ?? item.price) ??
      toStringValue(item.currency_code ?? item.currency),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

async function syncFinanceReports(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const months = monthsInRange(dateFrom, dateTo);
  const savedCheckpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(savedCheckpoint.processed) ?? 0;
  const startMonthIndex =
    savedCheckpoint.phase === "monthly_report"
      ? toInteger(savedCheckpoint.monthIndex) ?? 0
      : savedCheckpoint.phase
        ? months.length
        : 0;
  const startReportIndex =
    savedCheckpoint.phase === "monthly_report"
      ? toInteger(savedCheckpoint.reportIndex) ?? 0
      : 0;
  let skipped = toInteger(savedCheckpoint.skipped) ?? 0;

  for (
    let monthIndex = startMonthIndex;
    monthIndex < months.length;
    monthIndex += 1
  ) {
    const month = months[monthIndex];
    const reports = [
      {
        type: "mutual_settlement",
        endpoint: "/v1/finance/mutual-settlement" as const,
        payload: { date: month, language: "DEFAULT" },
      },
      {
        type: "compensation",
        endpoint: "/v1/finance/compensation" as const,
        payload: { date: month, language: "RU" },
      },
      {
        type: "decompensation",
        endpoint: "/v1/finance/decompensation" as const,
        payload: { date: month, language: "RU" },
      },
    ];
    for (
      let reportIndex = monthIndex === startMonthIndex ? startReportIndex : 0;
      reportIndex < reports.length;
      reportIndex += 1
    ) {
      execution?.yieldIfNeeded?.();
      const report = reports[reportIndex];
      try {
        const reportRows = await requestReportCode(
          supabase,
          client,
          workspaceId,
          connectionId,
          report.type,
          report.endpoint,
          report.payload,
          execution,
          {
            monthIndex,
            reportIndex,
            processed: fetched,
            skipped,
            code:
              monthIndex === startMonthIndex &&
              reportIndex === startReportIndex
                ? toStringValue(savedCheckpoint.code)
                : null,
          }
        );
        fetched += reportRows.length;
      } catch (error) {
        if (!isMissingFinanceDocumentError(error)) throw error;
        skipped += 1;
      }
      const nextReportIndex = reportIndex + 1;
      await execution?.saveCheckpoint?.(
        nextReportIndex < reports.length
          ? {
              phase: "monthly_report",
              monthIndex,
              reportIndex: nextReportIndex,
              processed: fetched,
              skipped,
              total: null,
            }
          : monthIndex + 1 < months.length
            ? {
                phase: "monthly_report",
                monthIndex: monthIndex + 1,
                reportIndex: 0,
                processed: fetched,
                skipped,
                total: null,
              }
            : {
                phase: "cash_flow",
                windowIndex: 0,
                page: 1,
                processed: fetched,
                skipped,
                total: null,
              },
        { fetched, skipped }
      );
    }
  }

  if (savedCheckpoint.phase !== "buyout" && savedCheckpoint.phase !== "complete") {
    fetched += await syncCashFlowRows(
      supabase,
      client,
      workspaceId,
      connectionId,
      dateFrom,
      dateTo,
      execution,
      fetched,
      skipped
    );
  }
  if (savedCheckpoint.phase !== "complete") {
    fetched += await syncBuyoutRows(
      supabase,
      client,
      workspaceId,
      connectionId,
      dateFrom,
      dateTo,
      execution,
      fetched,
      skipped
    );
  }

  return { fetched, skipped };
}

async function persistFinanceReportRows(
  supabase: SupabaseClient,
  rows: JsonRecord[]
) {
  await upsertRows(
    supabase,
    "ozon_finance_reports",
    rows,
    "connection_id,external_id"
  );
}

export function isMissingFinanceDocumentError(error: unknown) {
  if (!(error instanceof OzonApiError) || error.status !== 404) return false;

  const expectedIdentity = MISSING_FINANCE_DOCUMENT_BY_ENDPOINT[error.endpoint];
  if (!expectedIdentity) return false;

  return [error.code, error.apiMessage]
    .filter((value): value is string | number => value !== null)
    .some((value) =>
      isExactMissingFinanceDocumentIdentity(String(value), expectedIdentity)
    );
}

const MISSING_FINANCE_DOCUMENT_BY_ENDPOINT: Readonly<Record<string, string>> = {
  "/v1/finance/mutual-settlement": "finance document not found",
  "/v1/finance/compensation": "compensation document not found",
  "/v1/finance/decompensation": "decompensation document not found",
};
const PERSISTENCE_PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000";

function isExactMissingFinanceDocumentIdentity(
  value: string,
  expectedIdentity: string
) {
  const normalized = value
    .trim()
    .toLowerCase()
    .replace(/[_-]+/g, " ")
    .replace(/\s+/g, " ");
  if (normalized === expectedIdentity) return true;

  return normalized.match(/\bdesc\s*=\s*(.+)$/)?.[1] === expectedIdentity;
}

async function requestReportCode(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  reportType: string,
  endpoint: OzonReadOnlyEndpoint,
  payload: JsonRecord,
  execution?: OzonSyncDomainExecutionContext,
  resume?: {
    monthIndex: number;
    reportIndex: number;
    processed: number;
    skipped: number;
    code: string | null;
  }
) {
  let response: JsonRecord = {};
  let root: JsonRecord = {};
  let code = resume?.code ?? null;
  if (!code) {
    response = await client.request<JsonRecord>(endpoint, payload);
    root = unwrapResult(response);
    code = toStringValue(root.code ?? response.code);
    if (!code) {
      throw new OzonIncompleteResponseError(
        "Ozon finance report creation returned no code"
      );
    }
    await execution?.saveCheckpoint?.({
      phase: "monthly_report",
      monthIndex: resume?.monthIndex ?? 0,
      reportIndex: resume?.reportIndex ?? 0,
      code,
      processed: resume?.processed ?? 0,
      skipped: resume?.skipped ?? 0,
      total: null,
    });
  }

  const info = await client.request<JsonRecord>("/v1/report/info", { code });
  const reportInfo = unwrapResult(info);
  const reportStatus = normalizeStatus(reportInfo.status ?? root.status);

  await upsertRows(
    supabase,
    "ozon_report_runs",
    [
      {
        workspace_id: workspaceId,
        connection_id: connectionId,
        report_type: reportType,
        ozon_report_code: code,
        status: reportStatus,
        request_payload: sanitizeOzonPayload(payload),
        response_payload: sanitizeOzonPayload(reportInfo),
        file_url: toStringValue(reportInfo.file),
        completed_at: reportInfo.file ? new Date().toISOString() : null,
        ...ozonMirrorProvenance(execution?.runId),
      },
    ],
    "connection_id,ozon_report_code"
  );

  if (reportStatus === "waiting" || reportStatus === "processing") {
    throw new OzonReportPendingError(
      new Date(Date.now() + 60_000).toISOString()
    );
  }
  if (reportStatus && reportStatus !== "success") {
    throw new OzonInvariantError("Ozon finance report generation failed");
  }
  if (!reportStatus || !reportInfo.file) {
    throw new OzonIncompleteResponseError(
      "Ozon finance report result is incomplete"
    );
  }

  const reportRow = {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: `${reportType}:${code}`,
    report_type: reportType,
    period_start: monthStartDate(payload.date),
    period_end: monthEndDate(payload.date),
    status: reportStatus,
    ozon_report_code: code,
    file_url: toStringValue(reportInfo.file),
    raw_payload: sanitizeOzonPayload({ response, reportInfo }),
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(execution?.runId),
  };
  await persistFinanceReportRows(supabase, [reportRow]);
  return [reportRow];
}

async function syncCashFlowRows(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string,
  execution: OzonSyncDomainExecutionContext | undefined,
  baseProcessed: number,
  skipped: number
) {
  const windows = splitCashFlowPeriods(dateFrom, dateTo);
  const checkpoint = toRecord(execution?.checkpoint);
  const startWindowIndex =
    checkpoint.phase === "cash_flow"
      ? toInteger(checkpoint.windowIndex) ?? 0
      : 0;
  const startPage =
    checkpoint.phase === "cash_flow"
      ? toInteger(checkpoint.page) ?? 1
      : 1;
  let persisted = 0;
  for (
    let windowIndex = startWindowIndex;
    windowIndex < windows.length;
    windowIndex += 1
  ) {
    const window = windows[windowIndex];
    for (
      let page = windowIndex === startWindowIndex ? startPage : 1;
      page <= 100;
      page += 1
    ) {
      execution?.yieldIfNeeded?.();
      const response = await client.request<JsonRecord>(
        "/v1/finance/cash-flow-statement/list",
        {
          date: {
            from: `${window.from}T00:00:00.000Z`,
            to: `${window.to}T23:59:59.999Z`,
          },
          with_details: true,
          page,
          page_size: 1000,
        }
      );
      const root = unwrapResult(response);
      const flows = requireItems(
        root,
        ["cash_flows", "items", "rows"],
        "/v1/finance/cash-flow-statement/list"
      );
      const rows = decodeCashFlowReportRows(
        flows,
        workspaceId,
        connectionId,
        execution?.runId
      );
      await persistFinanceReportRows(supabase, rows);
      persisted += rows.length;
      const pageCount = toInteger(root.page_count);
      if (pageCount === null || pageCount < 0) {
        throw new OzonIncompleteResponseError(
          "Ozon cash-flow report has no valid page_count"
        );
      }
      const pageComplete = pageCount === 0 || page >= pageCount;
      if (!pageComplete && page === 100) {
        throw new OzonIncompleteResponseError(
          "Ozon cash-flow report exceeded the page safety limit"
        );
      }
      const nextCheckpoint = pageComplete
        ? windowIndex + 1 < windows.length
          ? {
              phase: "cash_flow",
              windowIndex: windowIndex + 1,
              page: 1,
              processed: baseProcessed + persisted,
              skipped,
              total: null,
            }
          : {
              phase: "buyout",
              windowIndex: 0,
              processed: baseProcessed + persisted,
              skipped,
              total: null,
            }
        : {
            phase: "cash_flow",
            windowIndex,
            page: page + 1,
            processed: baseProcessed + persisted,
            skipped,
            total: null,
          };
      await execution?.saveCheckpoint?.(nextCheckpoint, {
        fetched: baseProcessed + persisted,
        skipped,
      });
      if (pageComplete) break;
    }
  }
  return persisted;
}

export function decodeCashFlowReportRows(
  flows: unknown[],
  workspaceId: string,
  connectionId: string,
  runId?: string
) {
  const rows = new Map<string, { hash: string; row: JsonRecord }>();

  for (const flow of flows) {
    const item = toRecord(flow);
    const period = toRecord(item.period);
    const periodId = toStringValue(period.id);
    const periodStart = toDateOnly(period.begin);
    const periodEnd = toDateOnly(period.end);
    const currencyCode = toStringValue(item.currency_code);
    if (!periodId || !periodStart || !periodEnd || !currencyCode) {
      throw new OzonIncompleteResponseError(
        "Ozon cash-flow row has incomplete period or currency evidence"
      );
    }

    const externalId =
      `cash-flow:${periodId}:${periodStart}:${periodEnd}:${currencyCode}`;
    const hash = stableHash(item);
    const existing = rows.get(externalId);
    if (existing && existing.hash !== hash) {
      throw new OzonInvariantError(
        "Ozon returned conflicting cash-flow rows for one period and currency"
      );
    }
    if (existing) continue;

    rows.set(externalId, {
      hash,
      row: {
        workspace_id: workspaceId,
        connection_id: connectionId,
        external_id: externalId,
        report_type: "cash_flow",
        period_start: periodStart,
        period_end: periodEnd,
        amount: decimalString(item.orders_amount),
        currency_code: currencyCode,
        raw_payload: sanitizeOzonPayload(item),
        synced_at: new Date().toISOString(),
        ...ozonMirrorProvenance(runId),
      },
    });
  }

  return [...rows.values()].map(({ row }) => row);
}

async function syncBuyoutRows(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  dateFrom: string,
  dateTo: string,
  execution: OzonSyncDomainExecutionContext | undefined,
  baseProcessed: number,
  skipped: number
) {
  const windows = splitDateWindows(dateFrom, dateTo, 31);
  const checkpoint = toRecord(execution?.checkpoint);
  const startWindowIndex =
    checkpoint.phase === "buyout"
      ? toInteger(checkpoint.windowIndex) ?? 0
      : 0;
  let persisted = 0;
  for (
    let windowIndex = startWindowIndex;
    windowIndex < windows.length;
    windowIndex += 1
  ) {
    execution?.yieldIfNeeded?.();
    const window = windows[windowIndex];
    const response = await client.request<JsonRecord>(
      "/v1/finance/products/buyout",
      {
        date_from: window.from,
        date_to: window.to,
      }
    );
    const products = requireItems(
      response,
      ["products", "items", "rows"],
      "/v1/finance/products/buyout"
    );
    const rows = products.map((product) => {
        const item = toRecord(product);
        const id =
          [
            "buyout",
            toStringValue(item.posting_number),
            toStringValue(item.offer_id ?? item.sku),
            window.from,
            window.to,
          ]
            .filter(Boolean)
            .join(":") || `buyout:${stableHash(item).slice(0, 24)}`;
        return {
          workspace_id: workspaceId,
          connection_id: connectionId,
          external_id: id,
          report_type: "buyout",
          period_start: window.from,
          period_end: window.to,
          amount: decimalString(item.amount ?? item.buyout_price),
          currency_code: toStringValue(item.currency_code ?? item.currency),
          raw_payload: sanitizeOzonPayload(item),
          synced_at: new Date().toISOString(),
          ...ozonMirrorProvenance(execution?.runId),
        };
      });
    await persistFinanceReportRows(supabase, rows);
    persisted += rows.length;
    await execution?.saveCheckpoint?.(
      {
        phase: windowIndex + 1 < windows.length ? "buyout" : "complete",
        windowIndex: windowIndex + 1,
        processed: baseProcessed + persisted,
        skipped,
        total: null,
      },
      { fetched: baseProcessed + persisted, skipped }
    );
  }
  return persisted;
}

async function syncRemovals(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  dateFrom: string,
  dateTo: string,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let fetched = toInteger(checkpoint.processed) ?? 0;
  let createdCandidates = 0;
  const sources = [
    { type: "from_stock" as const, endpoint: "/v1/removal/from-stock/list" as const },
    {
      type: "from_supply" as const,
      endpoint: "/v1/removal/from-supply/list" as const,
    },
  ];
  const startSourceIndex =
    checkpoint.phase === "removals"
      ? toInteger(checkpoint.sourceIndex) ?? 0
      : 0;

  for (
    let sourceIndex = startSourceIndex;
    sourceIndex < sources.length;
    sourceIndex += 1
  ) {
    const source = sources[sourceIndex];
    let lastId =
      sourceIndex === startSourceIndex
        ? toStringValue(checkpoint.lastId) ?? ""
        : "";
    for (let page = 0; page < 100; page += 1) {
      execution?.yieldIfNeeded?.();
      const response = await client.request<JsonRecord>(source.endpoint, {
        date_from: dateFrom.slice(0, 10),
        date_to: dateTo.slice(0, 10),
        last_id: lastId,
        limit: 500,
      });
      const root = unwrapResult(response);
      const items = requireItems(
        root,
        ["returns_summary_report_rows", "rows", "items"],
        source.endpoint
      );
      fetched += items.length;

      for (const item of items) {
        const row = toRemovalRow(
          item,
          source.type,
          workspaceId,
          connectionId,
          mapping,
          execution?.runId
        );
        const { data, error } = await supabase
          .from("ozon_removals")
          .upsert(row, { onConflict: "connection_id,external_id" })
          .select("*")
          .single();
        if (error || !data) {
          throw ozonDatabaseError(error ?? {}, "upsert:ozon_removals");
        }
        const candidate = buildRemovalCandidate(data as JsonRecord);
        if (!candidate) continue;
        const saved = await upsertCandidatePreservingReview(supabase, candidate);
        const { error: linkError } = await supabase
          .from("ozon_removals")
          .update({ operation_candidate_id: saved.id })
          .eq("id", data.id);
        if (linkError) {
          throw ozonDatabaseError(linkError, "update:ozon_removals");
        }
        createdCandidates += 1;
      }

      const nextLastId = toStringValue(root.last_id ?? response.last_id);
      if (!nextLastId || nextLastId === lastId || items.length === 0) {
        await execution?.saveCheckpoint?.(
          {
            phase:
              sourceIndex + 1 < sources.length ? "removals" : "complete",
            sourceIndex: sourceIndex + 1,
            lastId: "",
            processed: fetched,
            total: null,
          },
          { fetched, createdCandidates }
        );
        break;
      }
      if (page === 99) {
        throw new OzonIncompleteResponseError(
          "Ozon removals exceeded the page safety limit"
        );
      }
      lastId = nextLastId;
      await execution?.saveCheckpoint?.(
        {
          phase: "removals",
          sourceIndex,
          lastId,
          processed: fetched,
          total: null,
        },
        { fetched, createdCandidates }
      );
    }
  }

  return { fetched, createdCandidates };
}

function toRemovalRow(
  value: unknown,
  removalType: "from_stock" | "from_supply",
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
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
    status: toStringValue(item.state ?? item.status),
    reason: toStringValue(item.stock_type ?? item.reason),
    event_date: toIsoString(
      item.utilization_date ??
        item.given_out_date ??
        item.delivery_date
    ),
    posting_number: toStringValue(
      item.return_id ?? item.return_number ?? item.box_id
    ),
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name ?? product.name),
    quantity: decimalString(item.quantity_for_return ?? item.quantity),
    warehouse_name: warehouseName,
    ozon_warehouse_id: warehouseId,
    amount:
      moneyAmount(item.preliminary_delivery_price) ??
      decimalString(item.preliminary_delivery_price),
    currency_code:
      moneyCurrency(item.preliminary_delivery_price) ??
      toStringValue(item.currency_code ?? item.currency),
    return_id: toStringValue(item.return_id ?? item.return_number),
    box_id: toStringValue(item.box_id ?? item.return_box_id),
    stock_type: toStringValue(item.stock_type),
    delivery_date: toIsoString(item.delivery_date),
    given_out_date: toIsoString(item.given_out_date),
    utilization_date: toIsoString(item.utilization_date),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    local_warehouse_id: findLocalWarehouseId(mapping, warehouseId, warehouseName),
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

function buildRemovalCandidate(row: JsonRecord) {
  const utilizationDate = toDateOnly(row.utilization_date);
  if (!utilizationDate || !positiveDecimal(row.quantity)) return null;

  const operation = {
    type: "write_off" as const,
    operationDate: utilizationDate,
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
        quantity: decimalString(row.quantity),
        unitPrice: decimalString(row.amount),
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
  mapping: MappingContext,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const checkpoint = toRecord(execution?.checkpoint);
  let phase = checkpoint.phase === "details" ? "details" : "list";
  let listCursor =
    phase === "list" ? toStringValue(checkpoint.cursor) ?? "" : "";
  let nextCursor =
    phase === "details" ? toStringValue(checkpoint.nextCursor) : null;
  let pageIndex = toInteger(checkpoint.pageIndex) ?? 0;
  let pendingOrderIds =
    phase === "details"
      ? asArray(checkpoint.pendingOrderIds)
          .map(toStringValue)
          .filter((value): value is string => Boolean(value))
      : [];
  let detailOffset =
    phase === "details" ? toInteger(checkpoint.detailOffset) ?? 0 : 0;
  let fetched = toInteger(checkpoint.processed) ?? 0;
  let createdCandidates = toInteger(checkpoint.createdCandidates) ?? 0;

  while (pageIndex < 100) {
    execution?.yieldIfNeeded?.();

    if (phase === "list") {
      const response = await client.request<JsonRecord>(
        "/v3/supply-order/list",
        buildSupplyOrderListRequest(listCursor)
      );
      const root = unwrapResult(response);
      pendingOrderIds = [
        ...new Set(
          requireArrayMember(root, "order_ids", "/v3/supply-order/list")
            .map(toStringValue)
            .filter((value): value is string => Boolean(value))
        ),
      ];
      nextCursor = toStringValue(root.last_id ?? response.last_id);
      if (nextCursor && nextCursor === listCursor) {
        throw new OzonIncompleteResponseError(
          "Ozon supply-order list repeated its pagination identifier"
        );
      }
      detailOffset = 0;
      phase = "details";
      await execution?.saveCheckpoint?.(
        {
          phase,
          cursor: listCursor,
          nextCursor,
          pageIndex,
          pendingOrderIds,
          detailOffset,
          processed: fetched,
          total: null,
          createdCandidates,
        },
        { fetched, createdCandidates }
      );
    }

    while (detailOffset < pendingOrderIds.length) {
      execution?.yieldIfNeeded?.();
      const batchOrderIds = pendingOrderIds.slice(
        detailOffset,
        detailOffset + 50
      );
      const orders = await fetchSupplyOrderDetails(client, batchOrderIds);

      for (const order of orders) {
        execution?.yieldIfNeeded?.();
        createdCandidates += await persistSupplyOrder(
          supabase,
          client,
          workspaceId,
          connectionId,
          mapping,
          order,
          execution?.runId
        );
        fetched += 1;
      }
      detailOffset += batchOrderIds.length;
      await execution?.saveCheckpoint?.(
        {
          phase: "details",
          cursor: listCursor,
          nextCursor,
          pageIndex,
          pendingOrderIds,
          detailOffset,
          processed: fetched,
          total: null,
          createdCandidates,
        },
        { fetched, createdCandidates }
      );
    }

    pageIndex += 1;
    if (!nextCursor || pendingOrderIds.length === 0) {
      await execution?.saveCheckpoint?.(
        {
          phase: "complete",
          pageIndex,
          processed: fetched,
          total: fetched,
          createdCandidates,
        },
        { fetched, createdCandidates }
      );
      return { fetched, createdCandidates };
    }

    listCursor = nextCursor;
    pendingOrderIds = [];
    detailOffset = 0;
    phase = "list";
    await execution?.saveCheckpoint?.(
      {
        phase,
        cursor: listCursor,
        pageIndex,
        processed: fetched,
        total: null,
        createdCandidates,
      },
      { fetched, createdCandidates }
    );
  }

  throw new OzonIncompleteResponseError(
    "Ozon supply-order pagination exceeded the 100-page safety limit"
  );
}

type OzonRequestClient = Pick<OzonClient, "request"> & {
  executionAbortSignal?: () => AbortSignal | undefined;
};

async function fetchSupplyOrderDetails(
  client: OzonRequestClient,
  batchOrderIds: string[]
) {
  const response = await client.request<JsonRecord>("/v3/supply-order/get", {
    order_ids: batchOrderIds,
  });
  const details = requireItems(
    unwrapResult(response),
    ["orders", "items"],
    "/v3/supply-order/get"
  ).map(toRecord);
  const detailsById = new Map(
    details.flatMap((detail) => {
      const orderId = toStringValue(
        detail.order_id ?? detail.id ?? detail.supply_order_id
      );
      return orderId && isUsableSupplyOrderRecord(detail)
        ? [[orderId, detail] as const]
        : [];
    })
  );
  const resolvedOrders = batchOrderIds.map((id) => detailsById.get(id));
  if (resolvedOrders.some((order) => !order)) {
    throw new OzonIncompleteResponseError(
      "Ozon supply-order details were incomplete"
    );
  }
  return resolvedOrders as JsonRecord[];
}

async function persistSupplyOrder(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  order: JsonRecord,
  runId?: string
) {
  const orderId = toStringValue(
    order.order_id ?? order.id ?? order.supply_order_id
  );
  if (!orderId) {
    throw new OzonIncompleteResponseError(
      "Ozon supply order detail has no order_id"
    );
  }
  const orderRow = toSupplyOrderRow(
    order,
    workspaceId,
    connectionId,
    mapping,
    runId
  );
  const bundleRows = await fetchSupplyItems(
    client,
    { ...orderRow, id: PERSISTENCE_PLACEHOLDER_ID },
    workspaceId,
    connectionId,
    mapping,
    runId
  );
  const { data: savedOrderId, error: replaceError } = await supabase.rpc(
    "replace_ozon_supply_order_with_items_v2",
    {
      p_parent: orderRow,
      p_rows: bundleRows,
    }
  );
  if (replaceError || typeof savedOrderId !== "string") {
    throw ozonDatabaseError(
      replaceError ?? {},
      "rpc:replace_ozon_supply_order_with_items_v2"
    );
  }
  const { data: savedOrder, error } = await supabase
    .from("ozon_supply_orders")
    .select("*")
    .eq("id", savedOrderId)
    .single();
  if (error || !savedOrder) {
    throw ozonDatabaseError(error ?? {}, "select:ozon_supply_orders");
  }

  const candidates = buildSupplyTransferCandidates(
    savedOrder as JsonRecord,
    bundleRows
  );
  for (const candidate of candidates) {
    const saved = await upsertCandidatePreservingReview(supabase, candidate);
    const { error: linkError } = await supabase
      .from("ozon_supply_orders")
      .update({ operation_candidate_id: saved.id })
      .eq("id", savedOrder.id);
    if (linkError) {
      throw ozonDatabaseError(linkError, "update:ozon_supply_orders");
    }
  }
  return candidates.length;
}

export async function fetchSupplyOrders(client: OzonRequestClient): Promise<JsonRecord[]> {
  const orderIds = new Set<string>();
  let lastId = "";
  const seenLastIds = new Set<string>();
  let listComplete = false;

  for (let page = 0; page < 100; page += 1) {
    const response = await client.request<JsonRecord>(
      "/v3/supply-order/list",
      buildSupplyOrderListRequest(lastId)
    );
    const root = unwrapResult(response);
    const pageOrderIds = new Set<string>();

    for (const value of requireArrayMember(
      root,
      "order_ids",
      "/v3/supply-order/list"
    )) {
      const orderId = toStringValue(value);
      if (orderId) pageOrderIds.add(orderId);
    }

    for (const orderId of pageOrderIds) orderIds.add(orderId);

    const nextLastId = toStringValue(root.last_id ?? response.last_id);
    if (!nextLastId || pageOrderIds.size === 0 || seenLastIds.has(nextLastId)) {
      listComplete = true;
      break;
    }
    seenLastIds.add(nextLastId);
    lastId = nextLastId;
  }
  if (!listComplete) {
    throw new OzonIncompleteResponseError(
      "Ozon supply-order pagination exceeded the 100-page safety limit"
    );
  }

  const detailedOrders: JsonRecord[] = [];
  for (const batchOrderIds of chunkArray([...orderIds], 50)) {
    detailedOrders.push(...(await fetchSupplyOrderDetails(client, batchOrderIds)));
  }

  return detailedOrders;
}

export function buildSupplyOrderListRequest(lastId: string) {
  return {
    filter: { states: OZON_SUPPLY_ORDER_STATES },
    last_id: lastId,
    limit: 100,
    sort_by: "ORDER_CREATION",
    sort_dir: "DESC",
  };
}

function isUsableSupplyOrderRecord(order: JsonRecord) {
  return Object.entries(order).some(
    ([key, value]) =>
      !["order_id", "id", "supply_order_id"].includes(key) &&
      value !== null &&
      value !== undefined &&
      value !== ""
  );
}

function toSupplyOrderRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
) {
  const item = toRecord(value);
  const supplies = asArray(item.supplies).map(toRecord);
  const primarySupply = supplies.length === 1 ? supplies[0] : {};
  const warehouse = toRecord(
    primarySupply.storage_warehouse ??
      item.warehouse ??
      item.destination_warehouse ??
      item.dropoff_warehouse ??
      {}
  );
  const orderId = toStringValue(item.order_id ?? item.id ?? item.supply_order_id);
  const warehouseName = toStringValue(
    primarySupply.storage_warehouse_name ??
      item.warehouse_name ??
      warehouse.name ??
      warehouse.warehouse_name
  );
  const warehouseId = toStringValue(
    primarySupply.storage_warehouse_id ??
      item.warehouse_id ??
      warehouse.id ??
      warehouse.warehouse_id ??
      warehouseName
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
    bundle_ids: [
      ...supplies.map((supply) => toStringValue(supply.bundle_id)),
      ...asArray(item.bundle_ids ?? item.bundles).map((bundle) =>
        toStringValue(toRecord(bundle).bundle_id ?? toRecord(bundle).id ?? bundle)
      ),
    ].filter((value): value is string => Boolean(value)),
    raw_payload: sanitizeOzonPayload(item),
    local_destination_warehouse_id: findLocalWarehouseId(
      mapping,
      warehouseId,
      warehouseName
    ),
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

async function fetchSupplyItems(
  client: OzonClient,
  order: JsonRecord,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
) {
  const bundleIds = asArray(order.bundle_ids)
    .map(toStringValue)
    .filter((value): value is string => Boolean(value));
  const rows: JsonRecord[] = [];

  if (bundleIds.length === 0) {
    throw new OzonIncompleteResponseError(
      "Ozon supply order has no documented supplies or bundle identifiers"
    );
  }

  for (const bundleId of bundleIds) {
    let lastId = "";
    let bundleComplete = false;
    for (let page = 0; page < 100; page += 1) {
      const response = await client.request<JsonRecord>(
        "/v1/supply-order/bundle",
        {
        bundle_ids: [bundleId],
        last_id: lastId,
        limit: 100,
        is_asc: true,
        }
      );
      const root = unwrapResult(response);
      const items = requireItems(
        root,
        ["items", "products", "rows"],
        "/v1/supply-order/bundle"
      );
      rows.push(
        ...items.map((item) =>
          toSupplyItemRow(
            item,
            order.id as string,
            workspaceId,
            connectionId,
            mapping,
            bundleId,
            supplyForBundle(order.raw_payload, bundleId),
            runId
          )
        )
      );
      if (root.has_next !== true) {
        bundleComplete = true;
        break;
      }
      const nextLastId = toStringValue(root.last_id);
      if (!nextLastId || nextLastId === lastId) {
        throw new OzonIncompleteResponseError(
          "Ozon supply bundle indicates more data without a new last_id"
        );
      }
      lastId = nextLastId;
    }
    if (!bundleComplete) {
      throw new OzonIncompleteResponseError(
        "Ozon supply bundle pagination exceeded the 100-page safety limit"
      );
    }
  }

  return rows;
}

function toSupplyItemRow(
  value: unknown,
  supplyOrderId: string,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  bundleId?: string,
  supply: JsonRecord = {},
  runId?: string
) {
  const item = toRecord(value);
  const product = toRecord(item.product ?? {});
  const ozonProductId = toStringValue(
    item.product_id ?? product.product_id ?? item.sku ?? product.sku
  );
  const offerId = toStringValue(item.offer_id ?? product.offer_id);
  const sku = toStringValue(item.sku ?? product.sku);
  const itemIdentity = toStringValue(item.id ?? item.item_id ?? item.sku);
  if (!itemIdentity) {
    throw new OzonIncompleteResponseError(
      "Ozon supply bundle item has no documented identifier"
    );
  }
  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    supply_order_id: supplyOrderId,
    external_id: [
      bundleId ?? "bundle",
      itemIdentity,
    ].join(":"),
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name ?? product.name),
    quantity: requiredDecimal(
      item.quantity ?? item.count,
      "/v1/supply-order/bundle",
      "items.quantity"
    ),
    bundle_id: bundleId ?? null,
    ozon_supply_id: toStringValue(supply.supply_id ?? supply.id),
    supply_state: toStringValue(supply.state ?? supply.status),
    storage_warehouse_name: toStringValue(
      supply.storage_warehouse_name ??
        toRecord(supply.storage_warehouse).name
    ),
    ozon_storage_warehouse_id: toStringValue(
      supply.storage_warehouse_id ??
        toRecord(supply.storage_warehouse).warehouse_id ??
        toRecord(supply.storage_warehouse).id
    ),
    local_destination_warehouse_id: findLocalWarehouseId(
      mapping,
      toStringValue(
        supply.storage_warehouse_id ??
          toRecord(supply.storage_warehouse).warehouse_id ??
          toRecord(supply.storage_warehouse).id
      ),
      toStringValue(
        supply.storage_warehouse_name ??
          toRecord(supply.storage_warehouse).name
      )
    ),
    completed_at_ozon: toIsoString(
      normalizeStatus(supply.state) === "completed" &&
        normalizeStatus(supply.order_state) === "completed"
        ? supply.order_state_updated_date
        : null
    ),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    ...ozonMirrorProvenance(runId),
  };
}

function buildSupplyTransferCandidates(order: JsonRecord, items: JsonRecord[]) {
  if (items.length === 0) return [];

  return items.flatMap((item) => {
    const status = normalizeStatus(item.supply_state);
    const completedAt = toDateOnly(item.completed_at_ozon);
    const destinationWarehouseId = toStringValue(
      item.local_destination_warehouse_id
    );
    if (
      !isCompletedSupplyStatus(status) ||
      !completedAt ||
      !positiveDecimal(item.quantity) ||
      !destinationWarehouseId
    ) {
      return [];
    }
    const productId = toStringValue(item.local_product_id);
    const quantity = decimalString(item.quantity);
    const operation = {
      type: "transfer" as const,
      operationDate: completedAt,
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
          warehouseId: destinationWarehouseId,
          warehouseName: toStringValue(item.storage_warehouse_name),
          ozonWarehouseId: toStringValue(item.ozon_storage_warehouse_id),
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

    return [{
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
    }];
  });
}

async function syncStockAnalytics(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const products = await loadOzonProductRefs(supabase, workspaceId, connectionId);
  const checkpoint = toRecord(execution?.checkpoint);
  const snapshotDate =
    toStringValue(checkpoint.snapshotDate) ??
    new Date().toISOString().slice(0, 10);
  const chunks = chunkArray(products, 100);
  const startBatchIndex = toInteger(checkpoint.batchIndex) ?? 0;
  let fetched = toInteger(checkpoint.processed) ?? 0;

  for (
    let batchIndex = startBatchIndex;
    batchIndex < chunks.length;
    batchIndex += 1
  ) {
    execution?.yieldIfNeeded?.();
    const chunk = chunks[batchIndex];
    const skus = selectPositiveAnalyticsSkus(chunk);
    if (skus.length === 0) continue;

    const resumeTurnover =
      checkpoint.phase === "turnover" && batchIndex === startBatchIndex;
    if (!resumeTurnover) {
      const stocksResponse = await client.request<JsonRecord>(
        "/v1/analytics/stocks",
        { skus }
      );
      const stockRows = requireItems(
        stocksResponse,
        ["items", "rows", "stocks"],
        "/v1/analytics/stocks"
      );
      fetched += stockRows.length;
      for (const item of stockRows) {
        const row = toStockAnalyticsRow(
          item,
          workspaceId,
          connectionId,
          mapping,
          snapshotDate,
          execution?.runId
        );
        const { error } = await supabase
          .from("ozon_stock_analytics")
          .upsert(row, { onConflict: "connection_id,external_id,snapshot_date" });
        if (error) {
          throw ozonDatabaseError(error, "upsert:ozon_stock_analytics");
        }
      }
      await execution?.saveCheckpoint?.(
        {
          phase: "turnover",
          batchIndex,
          snapshotDate,
          processed: fetched,
          total: null,
        },
        { fetched, createdCandidates: 0 }
      );
    }

    execution?.yieldIfNeeded?.();
    const turnoverResponse = await client.request<JsonRecord>(
      "/v1/analytics/turnover/stocks",
      {
        sku: skus,
        limit: skus.length,
      }
    );

    const turnoverRows = requireItems(
      turnoverResponse,
      ["items", "rows"],
      "/v1/analytics/turnover/stocks"
    );
    await upsertRows(
      supabase,
      "ozon_turnover_analytics",
      turnoverRows.map((item) =>
        toTurnoverAnalyticsRow(
          item,
          workspaceId,
          connectionId,
          mapping,
          snapshotDate,
          execution?.runId
        )
      ),
      "connection_id,external_id,snapshot_date"
    );
    fetched += turnoverRows.length;
    await execution?.saveCheckpoint?.(
      {
        phase: batchIndex + 1 < chunks.length ? "stocks" : "complete",
        batchIndex: batchIndex + 1,
        snapshotDate,
        processed: fetched,
        total: null,
      },
      { fetched, createdCandidates: 0 }
    );
  }

  return { fetched, createdCandidates: 0 };
}

export function selectPositiveAnalyticsSkus(products: JsonRecord[]) {
  return [
    ...new Set(
      products
        .map((product) => toStringValue(product.sku))
        .filter(
          (value): value is string =>
            value !== null && /^[1-9]\d*$/.test(value)
        )
    ),
  ];
}

async function loadOzonProductRefs(
  supabase: SupabaseClient,
  workspaceId: string,
  connectionId: string
) {
  const { data, error } = await supabase
    .from("ozon_products")
    .select(
      "ozon_product_id, offer_id, sku, name, price, raw_payload, local_product_id"
    )
    .eq("workspace_id", workspaceId)
    .eq("connection_id", connectionId);
  if (error) throw ozonDatabaseError(error, "select:ozon_products");
  return (data || []) as JsonRecord[];
}

function toStockAnalyticsRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  snapshotDate: string,
  runId?: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.sku);
  const offerId = toStringValue(item.offer_id);
  const sku = toStringValue(item.sku);
  if (!sku) {
    throw new OzonIncompleteResponseError(
      "Ozon analytics stock row has no sku"
    );
  }
  const clusterId = toStringValue(item.cluster_id);
  const externalId =
    [
      toStringValue(item.id ?? item.sku ?? item.product_id),
      clusterId,
    ]
      .filter(Boolean)
      .join(":") || `stock:${stableHash(item).slice(0, 24)}`;
  const validStockCount = requiredDecimal(
    item.valid_stock_count,
    "/v1/analytics/stocks",
    "valid_stock_count"
  );
  const availableStockCount = requiredDecimal(
    item.available_stock_count,
    "/v1/analytics/stocks",
    "available_stock_count"
  );

  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: externalId,
    snapshot_date: snapshotDate,
    ozon_product_id: ozonProductId,
    offer_id: offerId,
    sku,
    name: toStringValue(item.name ?? item.product_name),
    warehouse_name: null,
    ozon_warehouse_id: null,
    cluster_id: clusterId,
    macrolocal_cluster_id: toStringValue(item.macrolocal_cluster_id),
    stock: validStockCount,
    available_stock: availableStockCount,
    reserved_stock: null,
    valid_stock_count: validStockCount,
    available_stock_count: availableStockCount,
    requested_stock_count: decimalString(item.requested_stock_count),
    transit_stock_count: decimalString(item.transit_stock_count),
    return_from_customer_stock_count: decimalString(
      item.return_from_customer_stock_count
    ),
    return_to_seller_stock_count: decimalString(
      item.return_to_seller_stock_count
    ),
    stock_defect_stock_count: decimalString(item.stock_defect_stock_count),
    transit_defect_stock_count: decimalString(item.transit_defect_stock_count),
    other_stock_count: decimalString(item.other_stock_count),
    excess_stock_count: decimalString(item.excess_stock_count),
    expiring_stock_count: decimalString(item.expiring_stock_count),
    waiting_docs_stock_count: decimalString(item.waiting_docs_stock_count),
    ads: decimalString(item.ads),
    ads_cluster: decimalString(item.ads_cluster),
    idc: decimalString(item.idc),
    idc_cluster: decimalString(item.idc_cluster),
    turnover_grade: toStringValue(item.turnover_grade),
    turnover_grade_cluster: toStringValue(item.turnover_grade_cluster),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    local_warehouse_id: null,
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

function toTurnoverAnalyticsRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  snapshotDate: string,
  runId?: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.sku);
  const sku = toStringValue(item.sku);
  if (!sku) {
    throw new OzonIncompleteResponseError(
      "Ozon turnover stock row has no sku"
    );
  }
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
    offer_id: toStringValue(item.offer_id),
    name: toStringValue(item.name ?? item.product_name),
    current_stock: decimalString(item.current_stock ?? item.stock),
    ads: decimalString(item.ads),
    days_to_stock_out: decimalString(item.idc),
    idc: decimalString(item.idc),
    idc_grade: toStringValue(item.idc_grade),
    turnover: decimalString(item.turnover),
    turnover_grade: toStringValue(item.turnover_grade),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [sku]),
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

async function syncDiscountedProducts(
  supabase: SupabaseClient,
  client: OzonClient,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  execution?: OzonSyncDomainExecutionContext
): Promise<OzonSyncStepSummary> {
  const products = await loadOzonProductRefs(supabase, workspaceId, connectionId);
  let fetched = 0;

  const discountedSkus = new Set(selectDiscountedSkus(products));
  execution?.yieldIfNeeded?.();
  for (const sku of await discoverDiscountedSkus(client)) {
    discountedSkus.add(sku);
  }
  const items = await fetchDiscountedProducts(client, [...discountedSkus]);
  const checkpoint = toRecord(execution?.checkpoint);
  const startItemIndex =
    checkpoint.phase === "items"
      ? toInteger(checkpoint.itemIndex) ?? 0
      : 0;
  fetched = startItemIndex;

  for (
    let itemIndex = startItemIndex;
    itemIndex < items.length;
    itemIndex += 1
  ) {
    execution?.yieldIfNeeded?.();
    const item = items[itemIndex];
    const row = toDiscountedProductRow(
      item,
      workspaceId,
      connectionId,
      mapping,
      execution?.runId
    );
    const { data, error } = await supabase
      .from("ozon_discounted_products")
      .upsert(row, { onConflict: "connection_id,external_id" })
      .select("*")
      .single();
    if (error || !data) {
      throw ozonDatabaseError(
        error ?? {},
        "upsert:ozon_discounted_products"
      );
    }
    fetched += 1;
    await execution?.saveCheckpoint?.(
      {
        phase: itemIndex + 1 < items.length ? "items" : "complete",
        itemIndex: itemIndex + 1,
        processed: fetched,
        total: items.length,
      },
      { fetched, createdCandidates: 0 }
    );
  }

  return { fetched, createdCandidates: 0 };
}

export function selectDiscountedSkus(products: JsonRecord[]) {
  const discountedSkus = new Set<string>();

  for (const product of products) {
    const rawPayload = toRecord(product.raw_payload);
    const source = toRecord(rawPayload.source);
    if (source.is_discounted !== true) continue;

    const sku = toStringValue(product.sku ?? source.sku);
    if (sku) discountedSkus.add(sku);
  }

  return [...discountedSkus];
}

interface DiscountedReportRuntime {
  now: () => number;
  fetchText: (url: string, executionSignal?: AbortSignal) => Promise<string>;
}

const DEFAULT_DISCOUNTED_REPORT_RUNTIME: DiscountedReportRuntime = {
  now: Date.now,
  fetchText: downloadOzonReportText,
};

export async function discoverDiscountedSkus(
  client: OzonRequestClient,
  runtime: DiscountedReportRuntime = DEFAULT_DISCOUNTED_REPORT_RUNTIME
) {
  const listResponse = await client.request<JsonRecord>("/v1/report/list", {
    page: 1,
    page_size: 100,
    report_type: "SELLER_DISCOUNTED",
  });
  const reports = requireItems(
    listResponse,
    ["reports", "items"],
    "/v1/report/list"
  )
    .map(toRecord)
    .filter((report) => {
      const reportType = toStringValue(report.report_type);
      return (
        !reportType ||
        reportType.toUpperCase() === "SELLER_DISCOUNTED"
      );
    })
    .sort(
      (left, right) =>
        reportCreatedAt(right) - reportCreatedAt(left)
    );

  let report = reports[0] ?? null;
  if (!isReusableDiscountedReport(report, runtime.now())) {
    const createResponse = await client.request<JsonRecord>(
      "/v1/report/discounted/create",
      {}
    );
    const createRoot = unwrapResult(createResponse);
    const code = toStringValue(
      createRoot.code ?? createResponse.code ?? createResponse.result
    );
    if (!code) {
      throw new OzonIncompleteResponseError(
        "Ozon discounted report creation returned no code"
      );
    }
    report = await fetchOzonReportInfo(client, code);
  } else if (normalizeStatus(report?.status) !== "success") {
    const code = toStringValue(report?.code);
    if (!code) {
      throw new OzonIncompleteResponseError(
        "Ozon discounted report has no code"
      );
    }
    report = await fetchOzonReportInfo(client, code);
  }

  const status = normalizeStatus(report?.status);
  if (status === "waiting" || status === "processing") {
    throw new OzonReportPendingError(
      new Date(runtime.now() + 60_000).toISOString()
    );
  }
  if (status !== "success") {
    throw new OzonInvariantError("Ozon discounted report failed");
  }

  const fileUrl = toStringValue(report?.file);
  if (!fileUrl) {
    throw new OzonIncompleteResponseError(
      "Ozon discounted report has no file"
    );
  }

  return extractDiscountedSkusFromReportCsv(
    await runtime.fetchText(fileUrl, client.executionAbortSignal?.())
  );
}

async function fetchOzonReportInfo(
  client: OzonRequestClient,
  code: string
) {
  const response = await client.request<JsonRecord>("/v1/report/info", {
    code,
  });
  const root = unwrapResult(response);
  const nestedReport = toRecord(toRecord(response).report);
  return Object.keys(nestedReport).length > 0 ? nestedReport : root;
}

function reportCreatedAt(report: JsonRecord) {
  const createdAt = toStringValue(report.created_at);
  if (!createdAt) return 0;
  const timestamp = Date.parse(createdAt);
  return Number.isFinite(timestamp) ? timestamp : 0;
}

function isReusableDiscountedReport(
  report: JsonRecord | null,
  now: number
) {
  if (!report) return false;
  const status = normalizeStatus(report.status);
  if (status === "waiting" || status === "processing") return true;
  if (status !== "success" || !toStringValue(report.file)) return false;

  const createdAt = reportCreatedAt(report);
  return createdAt > 0 && now - createdAt <= DISCOUNTED_REPORT_REUSE_MS;
}

export function extractDiscountedSkusFromReportCsv(text: string) {
  const parsed = Papa.parse<string[]>(text, {
    skipEmptyLines: true,
  });
  if (parsed.errors.length > 0) {
    throw new OzonInvariantError("Ozon discounted report CSV is invalid");
  }

  const [headers = [], ...rows] = parsed.data;
  const discountedSkuIndex = headers.findIndex(isDiscountedSkuHeader);
  if (discountedSkuIndex < 0) {
    throw new OzonInvariantError(
      "Ozon discounted report has no discounted SKU column"
    );
  }

  const skus = new Set<string>();
  for (const row of rows) {
    const sku = String(row[discountedSkuIndex] ?? "").trim();
    if (/^\d+$/.test(sku)) skus.add(sku);
  }
  return [...skus];
}

function isDiscountedSkuHeader(value: string) {
  const header = value
    .normalize("NFKC")
    .trim()
    .toLowerCase()
    .replace(/ё/g, "е")
    .replace(/[^a-zа-я0-9]+/g, "");
  return (
    header.includes("sku") &&
    (header.includes("discount") ||
      header.includes("markdown") ||
      header.includes("уцен"))
  );
}

interface OzonReportDownloadRuntime {
  fetch: typeof fetch;
  timeoutSignal: (milliseconds: number) => AbortSignal;
  maxBytes?: number;
}

const DEFAULT_OZON_REPORT_DOWNLOAD_RUNTIME: OzonReportDownloadRuntime = {
  fetch,
  timeoutSignal: (milliseconds) => AbortSignal.timeout(milliseconds),
};

export async function downloadOzonReportText(
  url: string,
  executionSignal?: AbortSignal,
  runtime: OzonReportDownloadRuntime = DEFAULT_OZON_REPORT_DOWNLOAD_RUNTIME
) {
  const requestSignal = combineOzonReportAbortSignals(
    runtime.timeoutSignal(OZON_REPORT_DOWNLOAD_TIMEOUT_MS),
    executionSignal
  );

  try {
    throwIfOzonReportDownloadAborted(requestSignal.signal);
    let currentUrl = assertSafeOzonReportUrl(url);

    for (let redirectCount = 0; ; redirectCount += 1) {
      const response = await runtime.fetch(currentUrl, {
        cache: "no-store",
        redirect: "manual",
        signal: requestSignal.signal,
      });

      if (isRedirectResponse(response.status)) {
        if (redirectCount >= OZON_REPORT_MAX_REDIRECTS) {
          await response.body?.cancel();
          throw new OzonInvariantError(
            "Ozon discounted report has too many redirects"
          );
        }
        const location = response.headers.get("location");
        await response.body?.cancel();
        if (!location) {
          throw new OzonInvariantError(
            "Ozon discounted report redirect has no location"
          );
        }
        currentUrl = assertSafeOzonReportUrl(
          new URL(location, currentUrl).toString()
        );
        continue;
      }

      if (!response.ok) {
        await response.body?.cancel();
        throw new OzonReportDownloadError(response.status);
      }

      return readBoundedOzonReportText(
        response,
        runtime.maxBytes ?? OZON_REPORT_DOWNLOAD_LIMIT_BYTES
      );
    }
  } finally {
    requestSignal.cleanup();
  }
}

function assertSafeOzonReportUrl(value: string) {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new OzonInvariantError("Ozon discounted report URL is invalid");
  }

  if (url.protocol === "https:" && isOzonOwnedHostname(url.hostname)) {
    return url;
  }

  const configuredApiUrl = process.env.OZON_API_BASE_URL;
  if (
    configuredApiUrl &&
    url.origin === safeUrlOrigin(configuredApiUrl)
  ) {
    return url;
  }
  throw new OzonInvariantError("Ozon discounted report URL is not trusted");
}

function isOzonOwnedHostname(value: string) {
  const hostname = value.toLowerCase();
  return TRUSTED_OZON_REPORT_DOMAINS.some(
    (domain) => hostname === domain || hostname.endsWith(`.${domain}`)
  );
}

function safeUrlOrigin(value: string) {
  try {
    return new URL(value).origin;
  } catch {
    return null;
  }
}

function isRedirectResponse(status: number) {
  return [301, 302, 303, 307, 308].includes(status);
}

async function readBoundedOzonReportText(
  response: Response,
  maxBytes: number
) {
  const contentLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(contentLength) && contentLength > maxBytes) {
    await response.body?.cancel();
    throw new OzonInvariantError("Ozon discounted report is too large");
  }

  if (!response.body) return "";

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let byteCount = 0;
  let text = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      byteCount += value.byteLength;
      if (byteCount > maxBytes) {
        await reader.cancel();
        throw new OzonInvariantError("Ozon discounted report is too large");
      }
      text += decoder.decode(value, { stream: true });
    }
    return text + decoder.decode();
  } finally {
    reader.releaseLock();
  }
}

function combineOzonReportAbortSignals(
  timeoutSignal: AbortSignal,
  executionSignal?: AbortSignal
) {
  if (!executionSignal) {
    return { signal: timeoutSignal, cleanup: () => {} };
  }

  const controller = new AbortController();
  const sources = [timeoutSignal, executionSignal];
  const listeners = sources.map((source) => {
    const listener = () => {
      if (!controller.signal.aborted) {
        controller.abort(ozonReportAbortReason(source));
      }
    };
    if (source.aborted) listener();
    else source.addEventListener("abort", listener, { once: true });
    return { source, listener };
  });

  return {
    signal: controller.signal,
    cleanup: () => {
      for (const { source, listener } of listeners) {
        source.removeEventListener("abort", listener);
      }
    },
  };
}

function throwIfOzonReportDownloadAborted(signal: AbortSignal) {
  if (signal.aborted) throw ozonReportAbortReason(signal);
}

function ozonReportAbortReason(signal: AbortSignal) {
  return signal.reason instanceof Error
    ? signal.reason
    : new DOMException("Ozon report download aborted", "AbortError");
}

export async function fetchDiscountedProducts(
  client: OzonRequestClient,
  discountedSkus: string[]
): Promise<JsonRecord[]> {
  const items: JsonRecord[] = [];

  for (const chunk of chunkArray(discountedSkus, 100)) {
    const response = await client.request<JsonRecord>(
      "/v1/product/info/discounted",
      { discounted_skus: chunk }
    );
    items.push(
      ...(requireItems(
        response,
        ["items", "products", "rows"],
        "/v1/product/info/discounted"
      ) as JsonRecord[])
    );
  }

  return items;
}

function toDiscountedProductRow(
  value: unknown,
  workspaceId: string,
  connectionId: string,
  mapping: MappingContext,
  runId?: string
) {
  const item = toRecord(value);
  const ozonProductId = toStringValue(item.product_id ?? item.ozon_product_id);
  const sku = toStringValue(item.sku);
  const discountedSku = toStringValue(item.discounted_sku);
  if (!sku || !discountedSku) {
    throw new OzonIncompleteResponseError(
      "Ozon discounted product has no sku or discounted_sku"
    );
  }
  const offerId = toStringValue(item.offer_id);
  return {
    workspace_id: workspaceId,
    connection_id: connectionId,
    external_id: discountedSku,
    ozon_product_id: ozonProductId,
    discounted_sku: discountedSku,
    sku,
    offer_id: offerId,
    name: toStringValue(item.name ?? item.product_name),
    status: toStringValue(item.status),
    condition: toStringValue(item.condition),
    condition_estimation: toStringValue(item.condition_estimation),
    defects: toStringValue(item.defects),
    mechanical_damage: toStringValue(item.mechanical_damage),
    package_damage: toStringValue(item.package_damage),
    packaging_violation: toStringValue(item.packaging_violation),
    shortage: toStringValue(item.shortage),
    repair: toStringValue(item.repair),
    reason_damaged: toStringValue(item.reason_damaged),
    comment_reason_damaged: toStringValue(item.comment_reason_damaged),
    warranty_type: toStringValue(item.warranty_type),
    reason: discountedDamageEvidence(item),
    quantity: null,
    discount_percent: decimalString(item.discount_percent),
    raw_payload: sanitizeOzonPayload(item),
    local_product_id: findLocalProductId(mapping, ozonProductId, [offerId, sku]),
    local_warehouse_id: null,
    synced_at: new Date().toISOString(),
    ...ozonMirrorProvenance(runId),
  };
}

export function discountedDamageEvidence(item: JsonRecord) {
  const parts = [
    item.reason_damaged,
    item.comment_reason_damaged,
    item.defects,
    item.mechanical_damage,
    item.package_damage,
    item.packaging_violation,
    item.shortage,
    item.repair,
    item.reason,
    item.discount_reason,
    item.comment,
    item.condition,
    item.condition_estimation,
  ]
    .map(toStringValue)
    .filter((value): value is string => Boolean(value));

  return parts.length > 0 ? parts.join("; ") : null;
}

function ozonMirrorProvenance(runId?: string) {
  return {
    source_contract_version: "seller-api-2026-07-27",
    ...(runId ? { last_sync_run_id: runId } : {}),
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
    if (error) throw ozonDatabaseError(error, `upsert:${table}`);
  }
}

async function upsertCandidatePreservingReview(
  supabase: SupabaseClient,
  candidate: JsonRecord
) {
  const candidateWithEvidence = {
    ...candidate,
    evidence_version: 1,
    evidence_hash: stableHash({
      provider: candidate.provider,
      source_type: candidate.source_type,
      external_event_id: candidate.external_event_id,
      raw_payload: candidate.raw_payload,
    }),
  };
  const { data: existing, error: existingError } = await supabase
    .from("marketplace_operation_candidates")
    .select("id, status, evidence_hash")
    .eq("connection_id", candidate.connection_id)
    .eq("source_type", candidate.source_type)
    .eq("external_event_id", candidate.external_event_id)
    .maybeSingle();

  if (existingError) {
    throw ozonDatabaseError(
      existingError,
      "select:marketplace_operation_candidates"
    );
  }

  if (
    existing &&
    !canSyncUpdateCandidateStatus(existing.status as MarketplaceCandidateStatus)
  ) {
    if (
      existing.status === "approved" &&
      existing.evidence_hash !== candidateWithEvidence.evidence_hash
    ) {
      const { error: staleError } = await supabase
        .from("marketplace_operation_candidates")
        .update({ evidence_version: 0, evidence_hash: null })
        .eq("id", existing.id)
        .eq("status", "approved");
      if (staleError) {
        throw ozonDatabaseError(
          staleError,
          "invalidate:marketplace_operation_candidates"
        );
      }
    }
    return existing;
  }

  const { data, error } = await supabase
    .from("marketplace_operation_candidates")
    .upsert(candidateWithEvidence, {
      onConflict: "connection_id,source_type,external_event_id",
    })
    .select("id")
    .single();

  if (error || !data) {
    throw ozonDatabaseError(
      error ?? {},
      "upsert:marketplace_operation_candidates"
    );
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
    if (error) throw ozonDatabaseError(error, `insert:${table}`);
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

function requireItems(value: unknown, keys: string[], endpoint: string) {
  if (Array.isArray(value)) return value;

  const root = unwrapResult(value);
  if (Array.isArray(root)) return root;

  for (const key of keys) {
    const direct = toRecord(value)[key];
    if (Array.isArray(direct)) return direct;
    const nested = toRecord(root)[key];
    if (Array.isArray(nested)) return nested;
  }

  throw new OzonIncompleteResponseError(
    `Ozon ${endpoint} response has no ${keys.join("/")} array`
  );
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
  ].includes(status);
}

function isCancelledStatus(status: string) {
  return status.includes("cancel");
}

function isReturnReceivedBySellerStatus(status: string) {
  return status.replace(/[^a-z0-9]/g, "") === "returnedtoseller";
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

export function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (isRecord(value)) {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}

function stableHash(value: unknown) {
  return createHash("sha256")
    .update(canonicalJson(sanitizeOzonPayload(value)))
    .digest("hex");
}

function deduplicateFinanceAccruals(rows: unknown[]) {
  const byId = new Map<string, { row: unknown; hash: string }>();
  for (const row of rows) {
    const id = toStringValue(toRecord(row).accrual_id);
    if (!id) {
      throw new OzonIncompleteResponseError(
        "Ozon finance accrual row has no transaction identity"
      );
    }
    const hash = createHash("sha256")
      .update(canonicalJson(row))
      .digest("hex");
    const existing = byId.get(id);
    if (existing && existing.hash !== hash) {
      throw new OzonInvariantError(
        "Ozon returned conflicting payloads for one accrual_id"
      );
    }
    if (!existing) byId.set(id, { row, hash });
  }
  return [...byId.values()].map(({ row }) => row);
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

function isCompletedSupplyStatus(status: string) {
  return status === "completed";
}

export function isDefectReason(reason: string) {
  const normalizedReason = normalizeStatus(reason).replace(/ё/g, "е");
  return [
    "defect",
    "damage",
    "damaged",
    "broken",
    "brak",
    "брак",
    "вмят",
    "дефект",
    "нарушен",
    "повреж",
    "сломан",
    "трещ",
    "царап",
  ].some((marker) => normalizedReason.includes(marker));
}

function normalizeKey(value: string) {
  return value.trim().toLowerCase();
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

function stringOrArray(value: unknown) {
  if (Array.isArray(value)) return value;
  const text = toStringValue(value);
  return text ? [text] : [];
}

function toStringValue(value: unknown) {
  if (typeof value === "string") return value.trim() || null;
  if (typeof value === "number" && Number.isFinite(value)) {
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
      throw new OzonInvariantError(
        "Ozon identifier exceeded JavaScript safe integer precision"
      );
    }
    return String(value);
  }
  if (typeof value === "bigint") return String(value);
  return null;
}

function moneyAmount(value: unknown) {
  return decimalString(toRecord(value).amount);
}

function moneyCurrency(value: unknown) {
  return toStringValue(toRecord(value).currency);
}

function requiredDecimal(
  value: unknown,
  endpoint: string,
  field: string
) {
  const parsed = decimalString(value);
  if (parsed === null) {
    throw new OzonIncompleteResponseError(
      `Ozon ${endpoint} response has no valid ${field}`
    );
  }
  return parsed;
}

function requiredMoneyAmount(
  value: unknown,
  endpoint: string,
  field: string
) {
  const amount = moneyAmount(value);
  if (amount === null) {
    throw new OzonIncompleteResponseError(
      `Ozon ${endpoint} response has no valid ${field}.amount`
    );
  }
  return amount;
}

function requiredMoneyCurrency(
  value: unknown,
  endpoint: string,
  field: string
) {
  const currency = moneyCurrency(value);
  if (!currency) {
    throw new OzonIncompleteResponseError(
      `Ozon ${endpoint} response has no valid ${field}.currency`
    );
  }
  return currency;
}

function financeAccrualItems(item: JsonRecord) {
  const itemFeesMember = item.item_fees;
  const itemFees = Array.isArray(itemFeesMember)
    ? itemFeesMember
    : asArray(toRecord(itemFeesMember).fees);
  const postingProducts = asArray(toRecord(item.posting).products);
  return [
    ...postingProducts.map((product) => ({
      kind: "posting_product",
      product: sanitizeOzonPayload(product),
      commissions: sanitizeOzonPayload(
        asArray(
          toRecord(product).commissions ??
            toRecord(product).commission
        )
      ),
    })),
    ...itemFees.map((fee) => ({
      kind: "item_fee",
      fee: sanitizeOzonPayload(fee),
    })),
  ];
}

function financeAccrualServices(item: JsonRecord) {
  const services: unknown[] = [];
  const nonItemFees = Array.isArray(item.non_item_fee)
    ? item.non_item_fee
    : Object.keys(toRecord(item.non_item_fee)).length > 0
      ? [item.non_item_fee]
      : [];
  services.push(
    ...nonItemFees.map((fee) => ({
      kind: "non_item_fee",
      fee: sanitizeOzonPayload(fee),
    }))
  );

  for (const commission of asArray(item.commissions ?? item.commission)) {
    services.push({
      kind: "commission",
      commission: sanitizeOzonPayload(commission),
    });
  }

  for (const product of asArray(toRecord(item.posting).products)) {
    const productRecord = toRecord(product);
    for (const commission of asArray(
      productRecord.commissions ?? productRecord.commission
    )) {
      services.push({
        kind: "commission",
        commission: sanitizeOzonPayload(commission),
      });
    }
    const delivery = toRecord(productRecord.delivery);
    for (const service of asArray(delivery.services)) {
      services.push({
        kind: "delivery_service",
        service: sanitizeOzonPayload(service),
      });
    }
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

export function decimalString(value: unknown) {
  if (typeof value !== "string" && typeof value !== "number") return null;
  const normalized = String(value).trim().replace(/\s/g, "").replace(",", ".");
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(normalized)) return null;
  const negative = normalized.startsWith("-");
  const unsigned = normalized.replace(/^[+-]/, "");
  const [integerPart, fractionPart = ""] = unsigned.split(".");
  const integer = integerPart.replace(/^0+(?=\d)/, "") || "0";
  const fraction = fractionPart.replace(/0+$/, "");
  const canonical = fraction ? `${integer}.${fraction}` : integer;
  return negative && canonical !== "0" ? `-${canonical}` : canonical;
}

function positiveDecimal(value: unknown) {
  const parsed = decimalString(value);
  return parsed !== null && parsed !== "0" && !parsed.startsWith("-");
}

function toInteger(value: unknown) {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : null;
}

function requireArrayMember(
  value: JsonRecord,
  key: string,
  endpoint: string
) {
  const member = value[key];
  if (!Array.isArray(member)) {
    throw new OzonIncompleteResponseError(
      `Ozon ${endpoint} response has no ${key} array`
    );
  }
  return member;
}

function supplyForBundle(value: unknown, bundleId: string) {
  const order = toRecord(value);
  const supply =
    asArray(toRecord(value).supplies)
      .map(toRecord)
      .find((candidate) => toStringValue(candidate.bundle_id) === bundleId) ?? {};
  return {
    ...supply,
    order_state: order.state,
    order_state_updated_date: order.state_updated_date,
  };
}

export function splitDateWindows(
  dateFrom: string,
  dateTo: string,
  maximumDays: number
) {
  const start = new Date(`${dateFrom.slice(0, 10)}T00:00:00.000Z`);
  const end = new Date(`${dateTo.slice(0, 10)}T00:00:00.000Z`);
  if (
    Number.isNaN(start.getTime()) ||
    Number.isNaN(end.getTime()) ||
    start > end ||
    maximumDays < 1
  ) {
    return [];
  }
  const windows: Array<{ from: string; to: string }> = [];
  for (let cursor = new Date(start); cursor <= end; ) {
    const windowEnd = new Date(cursor);
    windowEnd.setUTCDate(windowEnd.getUTCDate() + maximumDays - 1);
    if (windowEnd > end) windowEnd.setTime(end.getTime());
    windows.push({
      from: cursor.toISOString().slice(0, 10),
      to: windowEnd.toISOString().slice(0, 10),
    });
    cursor = new Date(windowEnd);
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return windows;
}

export function splitCashFlowPeriods(dateFrom: string, dateTo: string) {
  const start = new Date(`${dateFrom.slice(0, 10)}T00:00:00.000Z`);
  const end = new Date(`${dateTo.slice(0, 10)}T00:00:00.000Z`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || start > end) {
    return [];
  }
  const periods: Array<{ from: string; to: string }> = [];
  const month = new Date(
    Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), 1)
  );
  while (month <= end) {
    const monthEnd = new Date(
      Date.UTC(month.getUTCFullYear(), month.getUTCMonth() + 1, 0)
    );
    for (const [periodStartDay, periodEndDay] of [
      [1, 15],
      [16, monthEnd.getUTCDate()],
    ] as const) {
      const periodStart = new Date(
        Date.UTC(month.getUTCFullYear(), month.getUTCMonth(), periodStartDay)
      );
      const periodEnd = new Date(
        Date.UTC(month.getUTCFullYear(), month.getUTCMonth(), periodEndDay)
      );
      if (periodEnd >= start && periodStart <= end) {
        periods.push({
          from: periodStart.toISOString().slice(0, 10),
          to: periodEnd.toISOString().slice(0, 10),
        });
      }
    }
    month.setUTCMonth(month.getUTCMonth() + 1);
  }
  return periods;
}

function ozonDatabaseError(
  error: { code?: string | null },
  operation: string
) {
  const code = typeof error.code === "string" ? error.code : null;
  return new OzonDatabaseError(
    code,
    operation,
    code === "21000" ||
      code?.startsWith("22") === true ||
      code?.startsWith("23") === true
  );
}
