import Papa from "papaparse";

export interface ParsedRow {
  rowNumber: number;
  data: Record<string, string>;
}

export interface RowError {
  rowNumber: number;
  errorCode: string;
  errorDetail: string;
  rawRow: Record<string, string>;
}

export interface ValidationResult<T> {
  valid: T[];
  errors: RowError[];
}

export function parseCSV(text: string): ParsedRow[] {
  const result = Papa.parse<Record<string, string>>(text, {
    header: true,
    skipEmptyLines: true,
    transformHeader: (h) => h.trim().toLowerCase(),
  });

  return result.data.map((row, i) => ({ rowNumber: i + 2, data: row }));
}

// --- Orders CSV ---
// Expected columns: source, external_order_id, ordered_at, currency, status

const REQUIRED_ORDER_COLUMNS = [
  "source",
  "external_order_id",
  "ordered_at",
  "currency",
];

export interface ParsedOrder {
  source: string;
  external_order_id: string;
  ordered_at: string;
  currency: string;
  status: string;
}

export interface ParsedOrderLine {
  external_order_id: string;
  source: string;
  sku: string;
  quantity: number;
  unit_price_gross: number;
  discount_amount: number;
  tax_amount: number;
}

export function validateOrderHeaders(headers: string[]): string | null {
  const missing = REQUIRED_ORDER_COLUMNS.filter((c) => !headers.includes(c));
  return missing.length > 0
    ? `Missing required columns: ${missing.join(", ")}`
    : null;
}

export function validateOrderRows(
  rows: ParsedRow[]
): ValidationResult<ParsedOrder> {
  const valid: ParsedOrder[] = [];
  const errors: RowError[] = [];

  for (const { rowNumber, data } of rows) {
    const issues: string[] = [];

    if (!data.source?.trim()) issues.push("source is empty");
    if (!data.external_order_id?.trim())
      issues.push("external_order_id is empty");

    const orderedAt = data.ordered_at?.trim();
    if (!orderedAt) {
      issues.push("ordered_at is empty");
    } else if (isNaN(Date.parse(orderedAt))) {
      issues.push("ordered_at is not a valid date");
    }

    const currency = data.currency?.trim().toUpperCase();
    if (!currency) {
      issues.push("currency is empty");
    } else if (!/^[A-Z]{3}$/.test(currency)) {
      issues.push("currency must be a 3-letter code");
    }

    if (issues.length > 0) {
      errors.push({
        rowNumber,
        errorCode: "VALIDATION_ERROR",
        errorDetail: issues.join("; "),
        rawRow: data,
      });
    } else {
      valid.push({
        source: data.source.trim(),
        external_order_id: data.external_order_id.trim(),
        ordered_at: new Date(orderedAt!).toISOString(),
        currency: currency!,
        status: data.status?.trim() || "created",
      });
    }
  }

  return { valid, errors };
}

// --- Order Lines CSV ---
// Expected columns: external_order_id, source, sku, quantity, unit_price_gross
// Optional: discount_amount, tax_amount

const REQUIRED_LINE_COLUMNS = [
  "external_order_id",
  "source",
  "sku",
  "quantity",
  "unit_price_gross",
];

export function validateOrderLineHeaders(headers: string[]): string | null {
  const missing = REQUIRED_LINE_COLUMNS.filter((c) => !headers.includes(c));
  return missing.length > 0
    ? `Missing required columns: ${missing.join(", ")}`
    : null;
}

export function validateOrderLineRows(
  rows: ParsedRow[]
): ValidationResult<ParsedOrderLine> {
  const valid: ParsedOrderLine[] = [];
  const errors: RowError[] = [];

  for (const { rowNumber, data } of rows) {
    const issues: string[] = [];

    if (!data.external_order_id?.trim())
      issues.push("external_order_id is empty");
    if (!data.source?.trim()) issues.push("source is empty");
    if (!data.sku?.trim()) issues.push("sku is empty");

    const quantity = parseInt(data.quantity, 10);
    if (isNaN(quantity) || quantity <= 0)
      issues.push("quantity must be a positive integer");

    const unitPriceGross = parseFloat(data.unit_price_gross);
    if (isNaN(unitPriceGross) || unitPriceGross < 0)
      issues.push("unit_price_gross must be >= 0");

    const discountAmount = parseFloat(data.discount_amount || "0");
    if (isNaN(discountAmount) || discountAmount < 0)
      issues.push("discount_amount must be >= 0");

    const taxAmount = parseFloat(data.tax_amount || "0");
    if (isNaN(taxAmount) || taxAmount < 0)
      issues.push("tax_amount must be >= 0");

    if (issues.length > 0) {
      errors.push({
        rowNumber,
        errorCode: "VALIDATION_ERROR",
        errorDetail: issues.join("; "),
        rawRow: data,
      });
    } else {
      valid.push({
        external_order_id: data.external_order_id.trim(),
        source: data.source.trim(),
        sku: data.sku.trim(),
        quantity,
        unit_price_gross: unitPriceGross,
        discount_amount: discountAmount,
        tax_amount: taxAmount,
      });
    }
  }

  return { valid, errors };
}

// --- Inventory CSV ---
// Expected columns: snapshot_date, sku, on_hand_qty, unit_cost

export interface ParsedInventory {
  snapshot_date: string;
  sku: string;
  on_hand_qty: number;
  unit_cost: number;
}

const REQUIRED_INVENTORY_COLUMNS = [
  "snapshot_date",
  "sku",
  "on_hand_qty",
  "unit_cost",
];

export function validateInventoryHeaders(headers: string[]): string | null {
  const missing = REQUIRED_INVENTORY_COLUMNS.filter(
    (c) => !headers.includes(c)
  );
  return missing.length > 0
    ? `Missing required columns: ${missing.join(", ")}`
    : null;
}

export function validateInventoryRows(
  rows: ParsedRow[]
): ValidationResult<ParsedInventory> {
  const valid: ParsedInventory[] = [];
  const errors: RowError[] = [];

  for (const { rowNumber, data } of rows) {
    const issues: string[] = [];

    const snapshotDate = data.snapshot_date?.trim();
    if (!snapshotDate) {
      issues.push("snapshot_date is empty");
    } else if (isNaN(Date.parse(snapshotDate))) {
      issues.push("snapshot_date is not a valid date");
    }

    if (!data.sku?.trim()) issues.push("sku is empty");

    const onHandQty = parseFloat(data.on_hand_qty);
    if (isNaN(onHandQty) || onHandQty < 0)
      issues.push("on_hand_qty must be >= 0");

    const unitCost = parseFloat(data.unit_cost);
    if (isNaN(unitCost) || unitCost < 0)
      issues.push("unit_cost must be >= 0");

    if (issues.length > 0) {
      errors.push({
        rowNumber,
        errorCode: "VALIDATION_ERROR",
        errorDetail: issues.join("; "),
        rawRow: data,
      });
    } else {
      valid.push({
        snapshot_date: snapshotDate!,
        sku: data.sku.trim(),
        on_hand_qty: onHandQty,
        unit_cost: unitCost,
      });
    }
  }

  return { valid, errors };
}

// --- Payments CSV ---
// Expected columns: source, external_payment_id, amount, currency
// Optional: order_external_id, fee_amount, paid_at, status

export interface ParsedPayment {
  source: string;
  external_payment_id: string;
  amount: number;
  fee_amount: number;
  currency: string;
  paid_at: string | null;
  status: string;
}

const REQUIRED_PAYMENT_COLUMNS = [
  "source",
  "external_payment_id",
  "amount",
  "currency",
];

export function validatePaymentHeaders(headers: string[]): string | null {
  const missing = REQUIRED_PAYMENT_COLUMNS.filter(
    (c) => !headers.includes(c)
  );
  return missing.length > 0
    ? `Missing required columns: ${missing.join(", ")}`
    : null;
}

export function validatePaymentRows(
  rows: ParsedRow[]
): ValidationResult<ParsedPayment> {
  const valid: ParsedPayment[] = [];
  const errors: RowError[] = [];

  for (const { rowNumber, data } of rows) {
    const issues: string[] = [];

    if (!data.source?.trim()) issues.push("source is empty");
    if (!data.external_payment_id?.trim())
      issues.push("external_payment_id is empty");

    const amount = parseFloat(data.amount);
    if (isNaN(amount)) issues.push("amount must be a number");

    const feeAmount = parseFloat(data.fee_amount || "0");
    if (isNaN(feeAmount)) issues.push("fee_amount must be a number");

    const currency = data.currency?.trim().toUpperCase();
    if (!currency) {
      issues.push("currency is empty");
    } else if (!/^[A-Z]{3}$/.test(currency)) {
      issues.push("currency must be a 3-letter code");
    }

    const paidAt = data.paid_at?.trim();
    if (paidAt && isNaN(Date.parse(paidAt))) {
      issues.push("paid_at is not a valid date");
    }

    if (issues.length > 0) {
      errors.push({
        rowNumber,
        errorCode: "VALIDATION_ERROR",
        errorDetail: issues.join("; "),
        rawRow: data,
      });
    } else {
      valid.push({
        source: data.source.trim(),
        external_payment_id: data.external_payment_id.trim(),
        amount,
        fee_amount: feeAmount,
        currency: currency!,
        paid_at: paidAt ? new Date(paidAt).toISOString() : null,
        status: data.status?.trim() || "pending",
      });
    }
  }

  return { valid, errors };
}
