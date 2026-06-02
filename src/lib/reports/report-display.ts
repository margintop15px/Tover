import type { ReportTemplateSource } from "@/types/inventory";
import type { ReportDimension, ReportMeasure } from "@/lib/reports/report-constructor";

export type PreviewReport = {
  rows?: Record<string, unknown>[];
  totals?: Record<string, unknown>;
  [key: string]: unknown;
};

export type PreviewColumnType =
  | "text"
  | "quantity"
  | "money"
  | "percent"
  | "ratio"
  | "days";

const moneyColumns = new Set([
  "totalCost",
  "purchaseInCost",
  "saleOutCost",
  "returnInCost",
  "writeOffOutCost",
  "transferInCost",
  "transferOutCost",
  "productionInCost",
  "productionOutCost",
  "defectOutCost",
  "netCost",
  "outflowCost",
  "averageInventoryCost",
  "defectCost",
  "purchasedInPeriod",
  "paidInPeriod",
  "currentDebt",
  "totalPurchased",
  "totalPaid",
  "totalDebt",
]);

const percentColumns = new Set(["shareOfStoreSales"]);
const ratioColumns = new Set(["turnoverRatio"]);
const dayColumns = new Set(["turnoverDays"]);
const textColumns = new Set(["groupName", "supplierName", "skuCode", "debtType"]);

const measureColumns: Record<
  ReportTemplateSource,
  Record<ReportMeasure, string[]>
> = {
  inventory_balances: {
    quantity: ["totalQuantity"],
    cost: ["totalCost"],
    invoice: [],
    payments: [],
    debt: [],
    turnover: [],
  },
  product_movement: {
    quantity: [
      "purchaseIn",
      "saleOut",
      "returnIn",
      "writeOffOut",
      "transferIn",
      "transferOut",
      "productionIn",
      "productionOut",
      "defectOut",
      "net",
    ],
    cost: [
      "purchaseInCost",
      "saleOutCost",
      "returnInCost",
      "writeOffOutCost",
      "transferInCost",
      "transferOutCost",
      "productionInCost",
      "productionOutCost",
      "defectOutCost",
      "netCost",
    ],
    invoice: [],
    payments: [],
    debt: [],
    turnover: [],
  },
  sales_volume: {
    quantity: [
      "soldQuantity",
      "returnedQuantity",
      "netSoldQuantity",
      "shareOfStoreSales",
    ],
    cost: [],
    invoice: [],
    payments: [],
    debt: [],
    turnover: [],
  },
  turnover: {
    quantity: [],
    cost: ["outflowCost", "averageInventoryCost"],
    invoice: [],
    payments: [],
    debt: [],
    turnover: ["turnoverRatio", "turnoverDays"],
  },
  defects: {
    quantity: [
      "defectInQuantity",
      "defectOutQuantity",
      "defectBalanceDelta",
    ],
    cost: ["defectCost"],
    invoice: [],
    payments: [],
    debt: [],
    turnover: [],
  },
  supplier_settlements: {
    quantity: [],
    cost: [],
    invoice: ["purchasedInPeriod"],
    payments: ["paidInPeriod"],
    debt: ["currentDebt", "debtType"],
    turnover: [],
  },
};

export function previewColumnType(key: string): PreviewColumnType {
  if (textColumns.has(key)) return "text";
  if (moneyColumns.has(key)) return "money";
  if (percentColumns.has(key)) return "percent";
  if (ratioColumns.has(key)) return "ratio";
  if (dayColumns.has(key)) return "days";
  return "quantity";
}

export function getReportTableRows(
  source: ReportTemplateSource,
  rowDimension: ReportDimension | null | undefined,
  report: PreviewReport | null
): Record<string, unknown>[] {
  const rows = (report?.rows || []) as Record<string, unknown>[];
  if (source !== "inventory_balances" || !rowDimension) return rows;

  const groupMap = new Map<string, Record<string, unknown>>();
  const addToGroup = (
    groupId: string,
    groupName: string,
    quantity: number,
    cost: number,
    extra: Record<string, unknown> = {}
  ) => {
    const current = groupMap.get(groupId) || {
      groupName,
      totalQuantity: 0,
      totalCost: 0,
      ...extra,
    };
    current.totalQuantity = Number(current.totalQuantity || 0) + quantity;
    current.totalCost = Number(current.totalCost || 0) + cost;
    groupMap.set(groupId, current);
  };

  for (const row of rows) {
    const quantity = Number(row.totalQuantity || 0);
    const cost = Number(row.totalCost || 0);

    if (rowDimension === "warehouse") {
      const warehouses = Array.isArray(row.warehouses)
        ? (row.warehouses as Record<string, unknown>[])
        : [];
      for (const warehouse of warehouses) {
        const warehouseId = String(warehouse.warehouseId || "unassigned");
        addToGroup(
          warehouseId,
          String(warehouse.warehouseName || "Unknown"),
          Number(warehouse.quantity || 0),
          Number(warehouse.totalCost || 0)
        );
      }
      continue;
    }

    const groupId =
      rowDimension === "category"
        ? String(row.categoryName || "uncategorized")
        : rowDimension === "store"
          ? String(row.storeName || "unassigned")
          : rowDimension === "quality"
            ? String(row.qualityStatus || "ordinary")
            : String(row.productId || row.productName || "unknown");
    const groupName =
      rowDimension === "category"
        ? String(row.categoryName || "No category")
        : rowDimension === "store"
          ? String(row.storeName || "No store")
          : rowDimension === "quality"
            ? String(row.qualityStatus || "ordinary")
            : String(row.productName || "Unknown");

    addToGroup(groupId, groupName, quantity, cost, {
      skuCode: rowDimension === "product" ? row.skuCode : null,
    });
  }

  return Array.from(groupMap.values()).sort((a, b) =>
    String(a.groupName).localeCompare(String(b.groupName))
  );
}

export function getReportColumnKeys(
  source: ReportTemplateSource,
  rowDimension: ReportDimension | null | undefined,
  measures: string[],
  rows: Record<string, unknown>[]
) {
  const base = source === "supplier_settlements" ? ["supplierName"] : ["groupName"];
  if (rowDimension === "product" && rows.some((row) => row.skuCode != null)) {
    base.push("skuCode");
  }

  const selected = measures.flatMap(
    (measure) => measureColumns[source][measure as ReportMeasure] || []
  );
  const available = new Set(rows.flatMap((row) => Object.keys(row)));

  return [...base, ...selected]
    .filter((key, index, list) => list.indexOf(key) === index)
    .filter(
      (key) =>
        key === "groupName" || key === "supplierName" || available.has(key)
    );
}
