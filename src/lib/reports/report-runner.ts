import type { ReportTemplate, ReportTemplateSource } from "@/types/inventory";
import {
  getPreviewGroupBy,
  REPORT_SOURCE_CONFIGS,
  type ReportDimension,
} from "@/lib/reports/report-constructor";

export const REPORT_DATA_FILTER_KEYS = [
  "productId",
  "categoryId",
  "warehouseId",
  "storeId",
  "supplierId",
  "qualityStatus",
] as const;

export const REPORT_DATE_FILTER_KEYS = [
  "asOfDate",
  "dateFrom",
  "dateTo",
] as const;

export type ReportTemplateFilterKey =
  | (typeof REPORT_DATA_FILTER_KEYS)[number]
  | (typeof REPORT_DATE_FILTER_KEYS)[number];

export type ReportTemplateFilters = Partial<
  Record<ReportTemplateFilterKey, string>
>;

export function getDefaultReportDates(now = new Date()): ReportTemplateFilters {
  const today = now.toISOString().split("T")[0];
  const monthAgo = new Date(now.getTime() - 30 * 86400000)
    .toISOString()
    .split("T")[0];

  return {
    asOfDate: today,
    dateFrom: monthAgo,
    dateTo: today,
  };
}

export function normalizeReportFilters(
  filters: Record<string, unknown> | null | undefined
): ReportTemplateFilters {
  const normalized: ReportTemplateFilters = {};
  const allowed = new Set<string>([
    ...REPORT_DATA_FILTER_KEYS,
    ...REPORT_DATE_FILTER_KEYS,
  ]);

  for (const [key, value] of Object.entries(filters || {})) {
    if (!allowed.has(key) || value == null) continue;
    const text = String(value).trim();
    if (text) normalized[key as ReportTemplateFilterKey] = text;
  }

  return normalized;
}

export function getTemplateRowDimension(
  source: ReportTemplateSource,
  rowDimensions: string[] | null | undefined
): ReportDimension {
  const config = REPORT_SOURCE_CONFIGS[source];
  const first = rowDimensions?.[0] as ReportDimension | undefined;
  if (first && config.previewGroupBy.includes(first)) return first;
  return config.defaultDimensions[0];
}

export function buildReportUrl(
  source: ReportTemplateSource,
  dateMode: "as_of" | "period",
  rowDimensions: string[],
  filters: Record<string, unknown> | null | undefined
) {
  const config = REPORT_SOURCE_CONFIGS[source];
  const normalizedFilters = {
    ...getDefaultReportDates(),
    ...normalizeReportFilters(filters),
  };
  const rowDimension = getTemplateRowDimension(source, rowDimensions);
  const groupBy = getPreviewGroupBy(config, [rowDimension]);
  const params = new URLSearchParams();

  if (dateMode === "as_of") {
    if (source === "supplier_settlements") {
      const asOfDate = normalizedFilters.asOfDate || normalizedFilters.dateTo;
      params.set("asOfDate", asOfDate || "");
      params.set("periodFrom", normalizedFilters.dateFrom || asOfDate || "");
      params.set("periodTo", normalizedFilters.dateTo || asOfDate || "");
    } else {
      params.set("date", normalizedFilters.asOfDate || "");
    }
  } else if (source === "supplier_settlements") {
    params.set("asOfDate", normalizedFilters.dateTo || "");
    params.set("periodFrom", normalizedFilters.dateFrom || "");
    params.set("periodTo", normalizedFilters.dateTo || "");
  } else {
    params.set("from", normalizedFilters.dateFrom || "");
    params.set("to", normalizedFilters.dateTo || "");
  }

  if (groupBy && source !== "inventory_balances") {
    params.set("groupBy", groupBy);
  }

  for (const key of REPORT_DATA_FILTER_KEYS) {
    const value = normalizedFilters[key];
    if (value) params.set(key, value);
  }

  return `${config.endpoint}?${params}`;
}

export function buildReportUrlForTemplate(template: ReportTemplate) {
  return buildReportUrl(
    template.source,
    template.dateMode,
    template.rowDimensions,
    template.filters
  );
}
