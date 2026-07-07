import crypto from "node:crypto";
import Papa from "papaparse";
import type { OperationType } from "@/types/inventory";
import { validateOperation } from "@/lib/operations";
import { parseOperationImportDate } from "./date";
import { parseXlsx } from "./xlsx";
import type {
  BuiltCandidate,
  CandidateValidationError,
  ExistingDuplicate,
  ExtractionResult,
  HeaderMapping,
  ImportSourceRef,
  OperationImportDraft,
  OperationImportItemDraft,
  ParsedTable,
  RefData,
  TabularImportColumnKey,
  TabularImportPlan,
  TabularImportSheetPlan,
} from "./types";

const MAX_ROWS_PER_IMPORT = 2000;

const PLAN_COLUMN_KEYS: TabularImportColumnKey[] = [
  "operationDate",
  "type",
  "productName",
  "skuCode",
  "warehouseName",
  "storeName",
  "sourceWarehouseName",
  "destinationWarehouseName",
  "quantity",
  "unitPrice",
  "supplierName",
  "paymentAmount",
  "comment",
  "direction",
];

const FIELD_SYNONYMS: Record<string, string[]> = {
  operationDate: [
    "date",
    "operationdate",
    "operation_date",
    "day",
    "дата",
    "день",
  ],
  type: ["type", "operation", "operationtype", "kind", "тип", "операция"],
  productName: [
    "product",
    "productname",
    "product name",
    "item",
    "itemname",
    "item name",
    "material",
    "ingredient",
    "товар",
    "продукт",
    "материал",
    "наименование",
    "наименование товара",
    "название",
    "название товара",
  ],
  skuCode: [
    "sku",
    "skucode",
    "sku_code",
    "code",
    "offerid",
    "offer_id",
    "offer id",
    "seller sku",
    "seller article",
    "vendor code",
    "артикул",
    "код",
    "код товара",
  ],
  warehouseName: [
    "warehouse",
    "warehouse_name",
    "location",
    "stockroom",
    "склад",
    "локация",
  ],
  storeName: ["store", "store_name", "shop", "marketplace", "магазин"],
  sourceWarehouseName: [
    "sourcewarehouse",
    "source_warehouse",
    "fromwarehouse",
    "from",
    "fromlocation",
    "складисточник",
    "изсклада",
  ],
  destinationWarehouseName: [
    "destinationwarehouse",
    "destination_warehouse",
    "towarehouse",
    "to",
    "tolocation",
    "складназначения",
    "всклад",
  ],
  quantity: [
    "qty",
    "quantity",
    "count",
    "units",
    "pcs",
    "количество",
    "колво",
    "шт",
  ],
  unitPrice: [
    "price",
    "unitprice",
    "unit_price",
    "unitcost",
    "unit_cost",
    "cost",
    "цена",
    "себестоимость",
  ],
  supplierName: [
    "supplier",
    "suppliername",
    "vendor",
    "provider",
    "поставщик",
  ],
  paymentAmount: [
    "payment",
    "paymentamount",
    "payment_amount",
    "paid",
    "paidamount",
    "totalpaid",
    "оплата",
    "суммаплатежа",
  ],
  comment: ["comment", "note", "notes", "description", "комментарий", "заметка"],
  direction: ["direction", "inout", "movement", "направление"],
};

const TYPE_SYNONYMS: Record<OperationType, string[]> = {
  purchase: ["purchase", "buy", "incoming", "receipt", "приход", "закупка"],
  sale: ["sale", "sell", "shipment", "outgoing", "продажа", "расход"],
  return: ["return", "customerreturn", "возврат"],
  write_off: ["writeoff", "write_off", "loss", "scrap", "списание"],
  transfer: ["transfer", "move", "movement", "перемещение"],
  production: ["production", "recipe", "manufacturing", "assembly", "производство"],
  defect: ["defect", "damage", "damaged", "брак", "дефект"],
  payment: ["payment", "paid", "supplierpayment", "оплата", "платеж"],
  inventory_adjustment: [
    "inventoryadjustment",
    "inventory_adjustment",
    "initialbalance",
    "openingbalance",
    "stocktake",
    "корректировка",
    "остатки",
  ],
};

function normalizeKey(value: string) {
  return value
    .trim()
    .toLowerCase()
    .replace(/ё/g, "е")
    .replace(/[^a-z0-9а-я]+/g, "");
}

function normalizeText(value: string | null | undefined) {
  return (value ?? "").normalize("NFKC").trim().toLowerCase().replace(/ё/g, "е");
}

function normalizeEntityName(value: string | null | undefined) {
  return normalizeText(value).replace(/[\s\p{P}\p{S}]+/gu, "");
}

function compact<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined && item !== "")
  ) as T;
}

function stableStringify(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stableStringify).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, item]) => `${JSON.stringify(key)}:${stableStringify(item)}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

export function hashBuffer(buffer: Buffer) {
  return crypto.createHash("sha256").update(buffer).digest("hex");
}

export function detectFileKind(
  fileName: string,
  mimeType: string
): ImportSourceRef["kind"] {
  const lower = fileName.toLowerCase();
  if (lower.endsWith(".csv") || mimeType.includes("csv")) return "csv";
  if (
    lower.endsWith(".xlsx") ||
    lower.endsWith(".xlsm") ||
    mimeType.includes("spreadsheet")
  ) {
    return "xlsx";
  }
  if (
    lower.endsWith(".png") ||
    lower.endsWith(".jpg") ||
    lower.endsWith(".jpeg") ||
    lower.endsWith(".webp") ||
    mimeType.startsWith("image/")
  ) {
    return "image";
  }
  if (
    lower.endsWith(".txt") ||
    lower.endsWith(".md") ||
    mimeType.startsWith("text/")
  ) {
    return "text";
  }
  return "unknown";
}

function parseCsv(text: string): ParsedTable[] {
  const parsed = Papa.parse<string[]>(text, {
    skipEmptyLines: true,
  });

  if (parsed.errors.length > 0) {
    throw new Error(parsed.errors.map((error) => error.message).join("; "));
  }

  return [
    {
      kind: "csv",
      sheetName: "CSV",
      rows: parsed.data.map((row) => row.map((value) => String(value ?? ""))),
    },
  ];
}

export function parseTabularFile(
  fileName: string,
  mimeType: string,
  buffer: Buffer
) {
  const fileType = detectFileKind(fileName, mimeType);
  if (fileType === "csv") return { fileType, tables: parseCsv(buffer.toString("utf8")) };
  if (fileType === "xlsx") return { fileType, tables: parseXlsx(buffer) };
  return { fileType, tables: [] as ParsedTable[] };
}

function scoreHeaderRow(row: string[]) {
  const normalized = row.map(normalizeKey);
  let score = 0;
  for (const synonyms of Object.values(FIELD_SYNONYMS)) {
    if (synonyms.some((synonym) => normalized.includes(normalizeKey(synonym)))) {
      score += 1;
    }
  }
  return score + Math.min(row.filter((value) => value.trim()).length, 6) / 10;
}

function skuHeaderPriority(header: string) {
  const normalized = normalizeKey(header);
  if (["sku", "skucode"].includes(normalized)) return 0;
  if (["sellersku", "sellercode", "vendorcode"].includes(normalized)) return 1;
  if (
    [
      "article",
      "sellerarticle",
      "offerid",
      "code",
      "артикул",
      "код",
      "кодтовара",
    ].includes(normalized)
  ) {
    return 2;
  }
  return null;
}

function detectHeaderMapping(rows: string[][]): HeaderMapping | null {
  let bestIndex = -1;
  let bestScore = 0;

  rows.slice(0, 20).forEach((row, index) => {
    const score = scoreHeaderRow(row);
    if (score > bestScore) {
      bestScore = score;
      bestIndex = index;
    }
  });

  if (bestIndex < 0 || bestScore < 1.5) return null;

  const columns: Record<string, number> = {};
  const labels: Record<string, string> = {};
  const headers = rows[bestIndex].map((value) => value.trim());

  headers.forEach((header, index) => {
    const normalized = normalizeKey(header);
    for (const [field, synonyms] of Object.entries(FIELD_SYNONYMS)) {
      if (
        columns[field] === undefined &&
        synonyms.some((synonym) => normalizeKey(synonym) === normalized)
      ) {
        columns[field] = index;
        labels[field] = header;
      }
    }
  });

  const skuCandidate = headers
    .map((header, index) => ({
      header,
      index,
      priority: skuHeaderPriority(header),
    }))
    .filter(
      (candidate): candidate is { header: string; index: number; priority: number } =>
        candidate.priority !== null
    )
    .sort((a, b) => a.priority - b.priority || a.index - b.index)[0];
  if (skuCandidate) {
    columns.skuCode = skuCandidate.index;
    labels.skuCode = skuCandidate.header;
  }

  return {
    headerRowIndex: bestIndex,
    columns,
    labels,
    confidence: Math.min(0.99, bestScore / 8),
  };
}

function getMappedValue(row: string[], mapping: HeaderMapping, field: string) {
  const index = mapping.columns[field];
  if (index === undefined) return "";
  return (row[index] ?? "").trim();
}

function parseNumber(value: string | number | undefined) {
  if (typeof value === "number") return Number.isFinite(value) ? value : undefined;
  const raw = (value ?? "").trim();
  if (!raw) return undefined;
  const normalized = raw
    .replace(/\s/g, "")
    .replace(/(?<=\d),(?=\d{1,2}$)/, ".")
    .replace(/[^\d.-]/g, "");
  const number = Number(normalized);
  return Number.isFinite(number) ? number : undefined;
}

function inferOperationType(rawType: string, row: Record<string, string>) {
  const normalized = normalizeKey(rawType);
  if (normalized) {
    for (const [type, synonyms] of Object.entries(TYPE_SYNONYMS)) {
      if (synonyms.some((synonym) => normalizeKey(synonym) === normalized)) {
        return { type: type as OperationType, confidence: 0.95 };
      }
    }
  }

  if (row.paymentAmount && !row.productName && !row.skuCode) {
    return { type: "payment" as OperationType, confidence: 0.7 };
  }
  if (row.sourceWarehouseName && row.destinationWarehouseName) {
    return { type: "transfer" as OperationType, confidence: 0.75 };
  }
  if (row.supplierName || row.unitPrice) {
    return { type: "purchase" as OperationType, confidence: 0.55 };
  }

  return { type: undefined, confidence: 0.2 };
}

function exactOrFuzzy<T extends { id: string; name: string }>(
  values: T[],
  rawName: string | undefined
) {
  const normalized = normalizeEntityName(rawName);
  if (!normalized) return { match: null, suggestions: [] as T[] };

  const exact = values.find((value) => normalizeEntityName(value.name) === normalized);
  if (exact) return { match: exact, suggestions: [exact] };

  const suggestions = values
    .map((value) => {
      const candidate = normalizeEntityName(value.name);
      return {
        value,
        score:
          candidate.includes(normalized) || normalized.includes(candidate)
            ? 2
            : sharedPrefixLength(candidate, normalized),
      };
    })
    .filter((entry) => entry.score > 2)
    .sort((a, b) => b.score - a.score)
    .slice(0, 5)
    .map((entry) => entry.value);

  return { match: null, suggestions };
}

function matchProduct(ref: RefData, rawName?: string, skuCode?: string) {
  const sku = normalizeText(skuCode);
  if (sku) {
    const exactSku = ref.products.find(
      (product) => normalizeText(product.skuCode) === sku
    );
    return {
      match: exactSku ?? null,
      suggestions: exactSku ? [exactSku] : [],
    };
  }

  const normalizedName = normalizeText(rawName);
  if (!normalizedName) return { match: null, suggestions: [] };
  const normalizedEntityName = normalizeEntityName(rawName);
  const exactNameMatches = ref.products.filter(
    (product) => normalizeEntityName(product.name) === normalizedEntityName
  );
  return {
    match: exactNameMatches.length === 1 ? exactNameMatches[0] : null,
    suggestions: exactNameMatches.slice(0, 5),
  };
}

function importDefault<T extends { isImportDefault?: boolean }>(values: T[]) {
  return values.find((value) => value.isImportDefault) ?? null;
}

function sharedPrefixLength(a: string, b: string) {
  let count = 0;
  while (count < a.length && count < b.length && a[count] === b[count]) count++;
  return count;
}

function buildRawRow(row: string[], mapping: HeaderMapping) {
  const raw: Record<string, string> = {};
  for (const field of Object.keys(FIELD_SYNONYMS)) {
    const value = getMappedValue(row, mapping, field);
    if (value) raw[field] = value;
  }
  return raw;
}

function itemKey(item: OperationImportItemDraft) {
  return {
    product: item.productId ?? normalizeText(item.skuCode) ?? normalizeText(item.productName),
    warehouse: item.warehouseId ?? normalizeText(item.warehouseName),
    quantity: item.quantity ?? null,
    unitPrice: item.unitPrice ?? null,
    direction: item.direction ?? null,
  };
}

export function fingerprintOperation(operation: OperationImportDraft) {
  const canonical = {
    type: operation.type,
    operationDate: operation.operationDate,
    supplier: operation.supplierId ?? normalizeText(operation.supplierName),
    paymentAmount: operation.paymentAmount ?? null,
    items: (operation.items ?? []).map(itemKey).sort((a, b) =>
      stableStringify(a).localeCompare(stableStringify(b))
    ),
  };
  return crypto.createHash("sha256").update(stableStringify(canonical)).digest("hex");
}

export function normalizeAndValidateDraft(
  operation: OperationImportDraft,
  ref: RefData,
  existingDuplicates: ExistingDuplicate[] = []
) {
  const errors: CandidateValidationError[] = [];
  const normalized: OperationImportDraft = {
    ...operation,
    items: (operation.items ?? []).map((item) => ({ ...item })),
  };

  if (!normalized.type) {
    errors.push({
      field: "type",
      message: "Operation type must be selected",
      severity: "error",
    });
  }

  if (!normalized.operationDate) {
    errors.push({
      field: "operationDate",
      message: "Operation date is required",
      severity: "error",
    });
  } else {
    const parsedDate = parseOperationImportDate(normalized.operationDate);
    if (parsedDate) {
      normalized.operationDate = parsedDate;
    } else {
      normalized.operationDate = undefined;
      errors.push({
        field: "operationDate",
        message: "Valid date is required",
        severity: "error",
      });
    }
  }

  if (
    normalized.supplierName &&
    !normalized.supplierId &&
    normalized.createSupplier !== false
  ) {
    const supplier = exactOrFuzzy(ref.suppliers, normalized.supplierName);
    if (supplier.match) {
      normalized.supplierId = supplier.match.id;
      normalized.createSupplier = false;
    } else {
      normalized.createSupplier = false;
    }
  } else if (
    (normalized.type === "purchase" || normalized.type === "payment") &&
    !normalized.supplierId &&
    !normalizeText(normalized.supplierName) &&
    normalized.createSupplier !== false
  ) {
    const defaultSupplier = importDefault(ref.suppliers);
    if (defaultSupplier) normalized.supplierId = defaultSupplier.id;
  }

  if (
    (normalized.type === "purchase" || normalized.type === "payment") &&
    !normalized.supplierId &&
    !(normalized.supplierName && normalized.createSupplier)
  ) {
    errors.push({
      field: "supplierId",
      message: "Supplier is required",
      severity: "error",
    });
  }

  if (normalized.type === "payment") {
    if (!normalized.paymentAmount || normalized.paymentAmount <= 0) {
      errors.push({
        field: "paymentAmount",
        message: "Payment amount must be positive",
        severity: "error",
      });
    }
  } else {
    const items = normalized.items ?? [];
    if (items.length === 0) {
      errors.push({
        field: "items",
        message: "At least one item is required",
        severity: "error",
      });
    }

    items.forEach((item, index) => {
      if (
        (item.productName || item.skuCode) &&
        !item.productId &&
        item.createProduct !== false
      ) {
        const product = matchProduct(ref, item.productName, item.skuCode);
        if (product.match) item.productId = product.match.id;
        if (!product.match) {
          if (!item.productName && item.skuCode) item.productName = item.skuCode;
          item.createProduct = false;
        }
      }

      if (!item.productId && !(item.productName && item.createProduct)) {
        errors.push({
          field: `items[${index}].productId`,
          message: "Product is required",
          severity: "error",
        });
      }

      if (
        item.warehouseName &&
        !item.warehouseId &&
        item.createWarehouse !== false
      ) {
        const warehouse = exactOrFuzzy(ref.warehouses, item.warehouseName);
        if (warehouse.match) {
          item.warehouseId = warehouse.match.id;
          item.createWarehouse = false;
        } else {
          item.createWarehouse = false;
        }
      } else if (
        !item.warehouseId &&
        !normalizeText(item.warehouseName) &&
        item.createWarehouse !== false
      ) {
        const defaultWarehouse = importDefault(ref.warehouses);
        if (defaultWarehouse) item.warehouseId = defaultWarehouse.id;
      }

      if (!item.warehouseId && !(item.warehouseName && item.createWarehouse)) {
        errors.push({
          field: `items[${index}].warehouseId`,
          message: "Warehouse is required",
          severity: "error",
        });
      }

      if (item.storeName && !item.storeId && item.createStore !== false) {
        const store = exactOrFuzzy(ref.stores, item.storeName);
        if (store.match) {
          item.storeId = store.match.id;
          item.createStore = false;
        } else {
          item.createStore = false;
        }
      } else if (
        !item.storeId &&
        !normalizeText(item.storeName) &&
        item.createStore !== false
      ) {
        const defaultStore = importDefault(ref.stores);
        if (defaultStore) item.storeId = defaultStore.id;
      }

      if (!item.quantity || item.quantity <= 0) {
        errors.push({
          field: `items[${index}].quantity`,
          message: "Quantity must be positive",
          severity: "error",
        });
      }

      if (
        (normalized.type === "purchase" ||
          normalized.type === "inventory_adjustment") &&
        (!item.unitPrice || item.unitPrice <= 0)
      ) {
        errors.push({
          field: `items[${index}].unitPrice`,
          message: "Unit price must be positive",
          severity: "error",
        });
      }
    });
  }

  if (normalized.type === "transfer") {
    const outItems = (normalized.items ?? []).filter((item) => item.direction === "out");
    const inItems = (normalized.items ?? []).filter((item) => item.direction === "in");
    if (outItems.length !== 1 || inItems.length !== 1) {
      errors.push({
        field: "items",
        message: "Transfer requires one source and one destination row",
        severity: "error",
      });
    }
    if (
      outItems[0]?.warehouseId &&
      inItems[0]?.warehouseId &&
      outItems[0].warehouseId === inItems[0].warehouseId
    ) {
      errors.push({
        field: "items",
        message: "Source and destination warehouses must differ",
        severity: "error",
      });
    }
  }

  if (normalized.type === "production") {
    const outItems = (normalized.items ?? []).filter((item) => item.direction === "out");
    const inItems = (normalized.items ?? []).filter((item) => item.direction === "in");
    if (outItems.length === 0 || inItems.length !== 1) {
      errors.push({
        field: "items",
        message: "Production requires source items and exactly one output item",
        severity: "error",
      });
    }
  }

  const canRunExistingValidator =
    errors.length === 0 &&
    normalized.type &&
    normalized.operationDate &&
    (normalized.type === "payment" ||
      (normalized.items ?? []).every((item) => item.productId && item.warehouseId));

  if (canRunExistingValidator) {
    const operationType = normalized.type!;
    const body =
      operationType === "transfer"
        ? {
            type: operationType,
            operationDate: normalized.operationDate!,
            comment: normalized.comment,
            productId: normalized.items?.[0]?.productId,
            sourceWarehouseId: normalized.items?.find((item) => item.direction === "out")
              ?.warehouseId,
            destinationWarehouseId: normalized.items?.find(
              (item) => item.direction === "in"
            )?.warehouseId,
            quantity: normalized.items?.[0]?.quantity,
          }
        : {
            type: operationType,
            operationDate: normalized.operationDate!,
            comment: normalized.comment,
            supplierId: normalized.supplierId,
            paymentAmount: normalized.paymentAmount,
            items: normalized.items?.map((item) => ({
              productId: item.productId!,
              warehouseId: item.warehouseId!,
              quantity: item.quantity!,
              unitPrice: item.unitPrice,
              direction: item.direction,
              storeId: item.storeId,
            })),
          };

    const result = validateOperation(body);
    if (result.errors) {
      errors.push(
        ...result.errors.map((error) => ({
          field: error.field,
          message: error.message,
          severity: "error" as const,
        }))
      );
    }
  }

  const fingerprint = fingerprintOperation(normalized);
  const duplicate = existingDuplicates.find((item) => item.fingerprint === fingerprint);
  if (duplicate) {
    errors.push({
      field: "duplicate",
      message: `Likely duplicate of operation ${duplicate.operationId}`,
      severity: "error",
    });
  }

  return {
    normalized,
    fingerprint,
    validationErrors: errors,
    status: errors.length > 0 ? ("needs_review" as const) : ("ready" as const),
  };
}

function buildOperationFromRow(
  raw: Record<string, string>,
  ref: RefData
): { operation: OperationImportDraft; confidence: number } {
  const inferred = inferOperationType(raw.type ?? "", raw);
  const operationDate = parseOperationImportDate(raw.operationDate);
  const quantity = parseNumber(raw.quantity);
  const unitPrice = parseNumber(raw.unitPrice);
  const paymentAmount = parseNumber(raw.paymentAmount);
  const baseItem: OperationImportItemDraft = compact({
    productName: raw.productName,
    skuCode: raw.skuCode,
    warehouseName: raw.warehouseName,
    storeName: raw.storeName,
    quantity,
    unitPrice,
  });

  if (baseItem.productName || baseItem.skuCode) {
    const product = matchProduct(ref, baseItem.productName, baseItem.skuCode);
    if (product.match) baseItem.productId = product.match.id;
  }

  if (baseItem.warehouseName) {
    const warehouse = exactOrFuzzy(ref.warehouses, baseItem.warehouseName);
    if (warehouse.match) baseItem.warehouseId = warehouse.match.id;
  }

  let items: OperationImportItemDraft[] = [];
  if (inferred.type === "payment") {
    items = [];
  } else if (inferred.type === "transfer") {
    const sourceWarehouse = exactOrFuzzy(ref.warehouses, raw.sourceWarehouseName);
    const destinationWarehouse = exactOrFuzzy(
      ref.warehouses,
      raw.destinationWarehouseName
    );
    items = [
      compact({
        ...baseItem,
        warehouseName: raw.sourceWarehouseName,
        warehouseId: sourceWarehouse.match?.id,
        direction: "out" as const,
      }),
      compact({
        ...baseItem,
        warehouseName: raw.destinationWarehouseName,
        warehouseId: destinationWarehouse.match?.id,
        direction: "in" as const,
      }),
    ];
  } else if (inferred.type === "production") {
    items = baseItem.productName || baseItem.skuCode ? [{ ...baseItem, direction: "out" }] : [];
  } else {
    const direction =
      raw.direction === "in" || raw.direction === "out"
        ? raw.direction
        : inferred.type === "return" ||
            inferred.type === "purchase" ||
            inferred.type === "inventory_adjustment"
        ? "in"
        : "out";
    items = baseItem.productName || baseItem.skuCode || quantity ? [{ ...baseItem, direction }] : [];
  }

  const supplier = exactOrFuzzy(ref.suppliers, raw.supplierName);

  return {
    operation: compact({
      type: inferred.type,
      operationDate,
      supplierName: raw.supplierName,
      supplierId: supplier.match?.id,
      paymentAmount,
      comment: raw.comment,
      items,
    }),
    confidence: Math.min(
      0.99,
      inferred.confidence +
        (operationDate ? 0.1 : 0) +
        (items.length > 0 || inferred.type === "payment" ? 0.1 : 0) +
        (items.some((item) => item.productId) ? 0.1 : 0) +
        (items.some((item) => item.warehouseId) ? 0.1 : 0)
    ),
  };
}

function buildCandidatesFromTable(
  table: ParsedTable,
  ref: RefData,
  existingDuplicates: ExistingDuplicate[]
) {
  const mapping = detectHeaderMapping(table.rows);
  if (!mapping) return { candidates: [] as BuiltCandidate[], mapping: null };

  const candidates: BuiltCandidate[] = [];
  const seen = new Map<string, number>();
  const dataRows = table.rows.slice(mapping.headerRowIndex + 1);

  dataRows.slice(0, MAX_ROWS_PER_IMPORT).forEach((row, index) => {
    if (!row.some((value) => value.trim())) return;

    const raw = buildRawRow(row, mapping);
    if (Object.keys(raw).length === 0) return;

    const { operation, confidence } = buildOperationFromRow(raw, ref);
    const validation = normalizeAndValidateDraft(operation, ref, existingDuplicates);
    const previousRow = seen.get(validation.fingerprint);
    if (previousRow !== undefined) {
      validation.validationErrors.push({
        field: "duplicate",
        message: `Duplicate of row ${previousRow}`,
        severity: "error",
      });
    } else {
      seen.set(validation.fingerprint, mapping.headerRowIndex + index + 2);
    }

    candidates.push({
      rowIndex: candidates.length,
      fingerprint: validation.fingerprint,
      status: validation.validationErrors.length > 0 ? "needs_review" : "ready",
      confidence,
      source: {
        kind: table.kind,
        sheetName: table.sheetName,
        rowNumber: mapping.headerRowIndex + index + 2,
        columns: mapping.labels,
        evidence: row.join(" | "),
      },
      raw,
      operation,
      normalizedOperation: validation.normalized,
      validationErrors: validation.validationErrors,
      duplicateOf: null,
    });
  });

  return { candidates, mapping };
}

function columnIndexFromLetter(value: string) {
  const letters = /^[A-Z]+$/i.exec(value.trim())?.[0];
  if (!letters) return null;
  let index = 0;
  for (const letter of letters.toUpperCase()) {
    index = index * 26 + letter.charCodeAt(0) - 64;
  }
  return index - 1;
}

function planColumnIndex(value: number | string | null | undefined) {
  if (typeof value === "number" && Number.isInteger(value) && value >= 0) {
    return value;
  }

  if (typeof value !== "string") return null;
  const trimmed = value.trim();
  if (!trimmed) return null;

  const asNumber = Number(trimmed);
  if (Number.isInteger(asNumber) && asNumber >= 0) return asNumber;
  return columnIndexFromLetter(trimmed);
}

function getPlanTable(
  tables: ParsedTable[],
  plan: TabularImportSheetPlan,
  fallbackIndex: number
) {
  if (plan.sheetName) {
    const byName = tables.find(
      (table) => normalizeText(table.sheetName) === normalizeText(plan.sheetName)
    );
    if (byName) return byName;
  }

  if (
    typeof plan.sheetIndex === "number" &&
    Number.isInteger(plan.sheetIndex) &&
    plan.sheetIndex >= 0
  ) {
    return tables[plan.sheetIndex] ?? null;
  }

  return tables[fallbackIndex] ?? null;
}

function planDefaults(plan: TabularImportSheetPlan) {
  const defaults: Record<string, string> = {};
  if (plan.defaults?.type) defaults.type = plan.defaults.type;
  if (plan.defaults?.operationDate) {
    defaults.operationDate = plan.defaults.operationDate;
  }
  if (plan.defaults?.supplierName) defaults.supplierName = plan.defaults.supplierName;
  if (plan.defaults?.warehouseName) defaults.warehouseName = plan.defaults.warehouseName;
  if (plan.defaults?.comment) defaults.comment = plan.defaults.comment;
  return defaults;
}

function buildRawRowFromPlan(row: string[], plan: TabularImportSheetPlan) {
  const raw: Record<string, string> = {};
  const mappedOnly: Record<string, string> = {};

  for (const field of PLAN_COLUMN_KEYS) {
    const index = planColumnIndex(plan.columns?.[field]);
    if (index === null) continue;
    const value = (row[index] ?? "").trim();
    if (!value) continue;
    raw[field] = value;
    mappedOnly[field] = value;
  }

  return {
    raw: compact({
      ...planDefaults(plan),
      ...raw,
    }),
    mappedValueCount: Object.keys(mappedOnly).length,
  };
}

function planColumnLabels(table: ParsedTable, plan: TabularImportSheetPlan) {
  const labels: Record<string, string> = {};
  const headerRow =
    typeof plan.headerRowIndex === "number" && plan.headerRowIndex >= 0
      ? table.rows[plan.headerRowIndex]
      : undefined;

  for (const field of PLAN_COLUMN_KEYS) {
    const index = planColumnIndex(plan.columns?.[field]);
    if (index === null) continue;
    labels[field] = headerRow?.[index]?.trim() || String(index);
  }

  return labels;
}

function buildCandidatesFromPlannedTable(
  table: ParsedTable,
  plan: TabularImportSheetPlan,
  ref: RefData,
  existingDuplicates: ExistingDuplicate[]
) {
  const candidates: BuiltCandidate[] = [];
  const seen = new Map<string, number>();
  const headerRowIndex =
    typeof plan.headerRowIndex === "number" && plan.headerRowIndex >= 0
      ? plan.headerRowIndex
      : null;
  const dataStartRowIndex =
    typeof plan.dataStartRowIndex === "number" && plan.dataStartRowIndex >= 0
      ? plan.dataStartRowIndex
      : headerRowIndex === null
        ? 0
        : headerRowIndex + 1;
  const dataEndRowIndex =
    typeof plan.dataEndRowIndex === "number" && plan.dataEndRowIndex >= dataStartRowIndex
      ? Math.min(plan.dataEndRowIndex, table.rows.length - 1)
      : table.rows.length - 1;
  const labels = planColumnLabels(table, plan);
  let emptyStreak = 0;

  for (
    let rowIndex = dataStartRowIndex;
    rowIndex <= dataEndRowIndex && candidates.length < MAX_ROWS_PER_IMPORT;
    rowIndex++
  ) {
    const row = table.rows[rowIndex] ?? [];
    const { raw, mappedValueCount } = buildRawRowFromPlan(row, plan);

    if (mappedValueCount === 0) {
      emptyStreak += 1;
      if (emptyStreak >= 20) break;
      continue;
    }
    emptyStreak = 0;

    const { operation, confidence } = buildOperationFromRow(raw, ref);
    const validation = normalizeAndValidateDraft(operation, ref, existingDuplicates);
    const previousRow = seen.get(validation.fingerprint);
    if (previousRow !== undefined) {
      validation.validationErrors.push({
        field: "duplicate",
        message: `Duplicate of row ${previousRow}`,
        severity: "error",
      });
    } else {
      seen.set(validation.fingerprint, rowIndex + 1);
    }

    candidates.push({
      rowIndex: candidates.length,
      fingerprint: validation.fingerprint,
      status: validation.validationErrors.length > 0 ? "needs_review" : "ready",
      confidence: Math.min(0.99, confidence * (plan.confidence ?? 0.8)),
      source: {
        kind: table.kind,
        sheetName: table.sheetName,
        rowNumber: rowIndex + 1,
        columns: labels,
        evidence: row.join(" | "),
      },
      raw,
      operation,
      normalizedOperation: validation.normalized,
      validationErrors: validation.validationErrors,
      duplicateOf: null,
    });
  }

  return { candidates, labels };
}

export async function extractTabularOperations({
  fileName,
  mimeType,
  buffer,
  ref,
  existingDuplicates,
  plan,
}: {
  fileName: string;
  mimeType: string;
  buffer: Buffer;
  ref: RefData;
  existingDuplicates: ExistingDuplicate[];
  plan?: TabularImportPlan | null;
}): Promise<ExtractionResult> {
  const { fileType, tables } = parseTabularFile(fileName, mimeType, buffer);
  const candidates: BuiltCandidate[] = [];
  const mappings: Record<string, HeaderMapping | null> = {};
  const planMappings: Record<string, Record<string, string>> = {};

  if (plan?.sheets?.length) {
    plan.sheets.forEach((sheetPlan, index) => {
      const table = getPlanTable(tables, sheetPlan, index);
      if (!table) return;
      const result = buildCandidatesFromPlannedTable(
        table,
        sheetPlan,
        ref,
        existingDuplicates
      );
      planMappings[table.sheetName] = result.labels;
      candidates.push(...result.candidates);
    });
  } else {
    for (const table of tables) {
      const result = buildCandidatesFromTable(table, ref, existingDuplicates);
      mappings[table.sheetName] = result.mapping;
      candidates.push(...result.candidates);
    }
  }

  return {
    fileType,
    tables,
    candidates: candidates.map((candidate, index) => ({
      ...candidate,
      rowIndex: index,
    })),
    findings: {
      parser: plan?.sheets?.length ? "deterministic_with_tabular_plan" : "deterministic",
      sheets: tables.map((table) => ({
        name: table.sheetName,
        rows: table.rows.length,
        mappedColumns:
          planMappings[table.sheetName] ?? mappings[table.sheetName]?.labels ?? {},
        mappedColumnCount: Object.keys(
          planMappings[table.sheetName] ?? mappings[table.sheetName]?.labels ?? {}
        ).length,
        headerConfidence:
          plan?.sheets?.length ? null : mappings[table.sheetName]?.confidence ?? 0,
      })),
      tabularPlan: plan ?? null,
      totalCandidates: candidates.length,
      needsReview: candidates.filter((candidate) => candidate.validationErrors.length > 0)
        .length,
    },
    extracted: {
      tables: tables.map((table) => ({
        sheetName: table.sheetName,
        rowCount: table.rows.length,
        preview: table.rows.slice(0, 20),
      })),
    },
    securityReport: {
      localGeneratedCodeExecution: false,
      deterministicParsers: ["csv", "xlsx"],
    },
  };
}

function getFindingSheets(extraction: ExtractionResult) {
  const sheets = extraction.findings.sheets;
  return Array.isArray(sheets)
    ? (sheets as {
        mappedColumnCount?: number;
        headerConfidence?: number;
        rows?: number;
      }[])
    : [];
}

export function tabularAiFallbackReasons(extraction: ExtractionResult): string[] {
  if (extraction.fileType !== "csv" && extraction.fileType !== "xlsx") return [];
  if (extraction.tables.length === 0) return [];

  const reasons: string[] = [];
  const candidates = extraction.candidates;
  const sheets = getFindingSheets(extraction);
  const avgHeaderConfidence =
    sheets.length > 0
      ? sheets.reduce((sum, sheet) => sum + (sheet.headerConfidence ?? 0), 0) /
        sheets.length
      : 0;
  const bestMappedColumnCount =
    sheets.length > 0
      ? Math.max(...sheets.map((sheet) => sheet.mappedColumnCount ?? 0))
      : 0;

  if (candidates.length === 0) {
    reasons.push("No operation candidates were detected deterministically.");
  }
  if (avgHeaderConfidence < 0.45 || bestMappedColumnCount < 3) {
    reasons.push("Column mapping confidence is low.");
  }

  if (candidates.length > 0) {
    const averageCandidateConfidence =
      candidates.reduce((sum, candidate) => sum + candidate.confidence, 0) /
      candidates.length;
    const reviewRatio =
      candidates.filter((candidate) => candidate.validationErrors.length > 0)
        .length / candidates.length;

    if (averageCandidateConfidence < 0.65) {
      reasons.push("Candidate confidence is low.");
    }
    if (reviewRatio >= 0.5) {
      reasons.push("Too many candidate operations require clarification.");
    }
  }

  return reasons;
}

export function shouldUseTabularAiFallback(extraction: ExtractionResult) {
  return tabularAiFallbackReasons(extraction).length > 0;
}

export function candidateSummary(candidates: BuiltCandidate[]) {
  const total = candidates.length;
  const ready = candidates.filter((candidate) => candidate.status === "ready").length;
  const blocked = candidates.filter((candidate) => candidate.validationErrors.length > 0)
    .length;

  return {
    total,
    ready,
    needsReview: blocked,
    approved: 0,
    committed: 0,
  };
}

export function normalizeAiDrafts(
  drafts: OperationImportDraft[],
  ref: RefData,
  existingDuplicates: ExistingDuplicate[],
  sourceKind: ImportSourceRef["kind"]
): BuiltCandidate[] {
  return drafts.map((draft, index) => {
    const validation = normalizeAndValidateDraft(draft, ref, existingDuplicates);
    return {
      rowIndex: index,
      fingerprint: validation.fingerprint,
      status: validation.status,
      confidence: 0.55,
      source: {
        kind: sourceKind,
        rowNumber: index + 1,
        evidence: draft.comment,
      },
      raw: draft as Record<string, unknown>,
      operation: draft,
      normalizedOperation: validation.normalized,
      validationErrors: validation.validationErrors,
    };
  });
}
