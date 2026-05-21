import type { ReportTemplateSource } from "@/types/inventory";

export type ReportDimension =
  | "product"
  | "category"
  | "warehouse"
  | "store"
  | "quality"
  | "supplier";

export type ReportMeasure =
  | "quantity"
  | "cost"
  | "invoice"
  | "payments"
  | "debt"
  | "turnover";

export interface ReportSourceConfig {
  source: ReportTemplateSource;
  labelKey:
    | "reportInventory"
    | "reportMovement"
    | "reportSales"
    | "reportTurnover"
    | "reportDefects"
    | "reportSupplierDebt";
  endpoint: string;
  defaultDateMode: "as_of" | "period";
  dateModes: ("as_of" | "period")[];
  defaultDimensions: ReportDimension[];
  dimensions: ReportDimension[];
  previewGroupBy: ReportDimension[];
  defaultMeasures: ReportMeasure[];
  measures: ReportMeasure[];
}

export const REPORT_SOURCE_CONFIGS: Record<
  ReportTemplateSource,
  ReportSourceConfig
> = {
  inventory_balances: {
    source: "inventory_balances",
    labelKey: "reportInventory",
    endpoint: "/api/reports/inventory-balances",
    defaultDateMode: "as_of",
    dateModes: ["as_of"],
    defaultDimensions: ["product"],
    dimensions: ["product", "category", "warehouse", "store", "quality"],
    previewGroupBy: ["product", "category", "warehouse", "store", "quality"],
    defaultMeasures: ["quantity", "cost"],
    measures: ["quantity", "cost"],
  },
  product_movement: {
    source: "product_movement",
    labelKey: "reportMovement",
    endpoint: "/api/reports/product-movement",
    defaultDateMode: "period",
    dateModes: ["period"],
    defaultDimensions: ["product"],
    dimensions: ["product", "category", "warehouse", "store", "quality"],
    previewGroupBy: ["product", "warehouse", "store", "quality"],
    defaultMeasures: ["quantity", "cost"],
    measures: ["quantity", "cost"],
  },
  sales_volume: {
    source: "sales_volume",
    labelKey: "reportSales",
    endpoint: "/api/reports/sales-volume",
    defaultDateMode: "period",
    dateModes: ["period"],
    defaultDimensions: ["store"],
    dimensions: ["store", "product", "category", "warehouse"],
    previewGroupBy: ["store", "product", "warehouse"],
    defaultMeasures: ["quantity"],
    measures: ["quantity"],
  },
  turnover: {
    source: "turnover",
    labelKey: "reportTurnover",
    endpoint: "/api/reports/turnover",
    defaultDateMode: "period",
    dateModes: ["period"],
    defaultDimensions: ["product"],
    dimensions: ["product", "category", "warehouse", "store"],
    previewGroupBy: ["product", "warehouse", "store"],
    defaultMeasures: ["cost", "turnover"],
    measures: ["cost", "turnover"],
  },
  defects: {
    source: "defects",
    labelKey: "reportDefects",
    endpoint: "/api/reports/defects",
    defaultDateMode: "period",
    dateModes: ["period"],
    defaultDimensions: ["product"],
    dimensions: ["product", "category", "warehouse", "store"],
    previewGroupBy: ["product", "warehouse", "store"],
    defaultMeasures: ["quantity", "cost"],
    measures: ["quantity", "cost"],
  },
  supplier_settlements: {
    source: "supplier_settlements",
    labelKey: "reportSupplierDebt",
    endpoint: "/api/reports/supplier-debt",
    defaultDateMode: "period",
    dateModes: ["period", "as_of"],
    defaultDimensions: ["supplier"],
    dimensions: ["supplier"],
    previewGroupBy: ["supplier"],
    defaultMeasures: ["invoice", "payments", "debt"],
    measures: ["invoice", "payments", "debt"],
  },
};

export const REPORT_SOURCES = Object.values(REPORT_SOURCE_CONFIGS);

export function getPreviewGroupBy(
  config: ReportSourceConfig,
  dimensions: string[]
) {
  return dimensions.find((dimension): dimension is ReportDimension =>
    config.previewGroupBy.includes(dimension as ReportDimension)
  );
}
