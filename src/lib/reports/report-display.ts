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
  _source: ReportTemplateSource,
  _rowDimension: ReportDimension | null | undefined,
  report: PreviewReport | null
): Record<string, unknown>[] {
  return (report?.rows || []) as Record<string, unknown>[];
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
