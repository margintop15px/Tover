import type { ReportTemplateSource } from "@/types/inventory";
import {
  REPORT_SOURCE_CONFIGS,
  type ReportDimension,
  type ReportMeasure,
} from "@/lib/reports/report-constructor";
import {
  REPORT_DATA_FILTER_KEYS,
  REPORT_DATE_FILTER_KEYS,
  normalizeReportFilters,
  type ReportTemplateFilters,
} from "@/lib/reports/report-runner";

export interface ValidatedReportTemplatePayload {
  name: string;
  source: ReportTemplateSource;
  rowDimensions: ReportDimension[];
  columnDimensions: never[];
  measures: ReportMeasure[];
  filters: ReportTemplateFilters;
  dateMode: "as_of" | "period";
}

const sourceValues = new Set(Object.keys(REPORT_SOURCE_CONFIGS));
const datePattern = /^\d{4}-\d{2}-\d{2}$/;
const filterDimensionByKey: Record<
  (typeof REPORT_DATA_FILTER_KEYS)[number],
  ReportDimension
> = {
  productId: "product",
  categoryId: "category",
  warehouseId: "warehouse",
  storeId: "store",
  supplierId: "supplier",
  qualityStatus: "quality",
};

function asStringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === "string")
    : [];
}

export function validateReportTemplatePayload(
  body: Record<string, unknown>
):
  | { ok: true; payload: ValidatedReportTemplatePayload }
  | { ok: false; error: string } {
  const name = typeof body.name === "string" ? body.name.trim() : "";
  if (!name) return { ok: false, error: "Name is required" };

  const source = body.source;
  if (typeof source !== "string" || !sourceValues.has(source)) {
    return { ok: false, error: "Invalid report source" };
  }

  const typedSource = source as ReportTemplateSource;
  const config = REPORT_SOURCE_CONFIGS[typedSource];
  const dateMode =
    body.dateMode === "as_of" || body.dateMode === "period"
      ? body.dateMode
      : config.defaultDateMode;

  if (!config.dateModes.includes(dateMode)) {
    return { ok: false, error: "Date mode is not supported for this report" };
  }

  const rowDimensions = asStringArray(body.rowDimensions);
  if (rowDimensions.length !== 1) {
    return { ok: false, error: "Exactly one row dimension is required" };
  }

  const rowDimension = rowDimensions[0] as ReportDimension;
  if (!config.previewGroupBy.includes(rowDimension)) {
    return { ok: false, error: "Row dimension is not supported for this report" };
  }

  const columnDimensions = asStringArray(body.columnDimensions);
  if (columnDimensions.length > 0) {
    return {
      ok: false,
      error: "Column split is not available for saved reports yet",
    };
  }

  const measures = asStringArray(body.measures) as ReportMeasure[];
  if (measures.length === 0) {
    return { ok: false, error: "At least one measure is required" };
  }

  const uniqueMeasures = [...new Set(measures)];
  if (uniqueMeasures.some((measure) => !config.measures.includes(measure))) {
    return { ok: false, error: "Measure is not supported for this report" };
  }

  const filtersSource =
    body.filters && typeof body.filters === "object" && !Array.isArray(body.filters)
      ? (body.filters as Record<string, unknown>)
      : {};
  const allowedKeys = new Set<string>([
    ...REPORT_DATA_FILTER_KEYS.filter((key) =>
      config.dimensions.includes(filterDimensionByKey[key])
    ),
    ...REPORT_DATE_FILTER_KEYS,
  ]);
  const unknownFilter = Object.keys(filtersSource).find(
    (key) => !allowedKeys.has(key)
  );
  if (unknownFilter) {
    return { ok: false, error: `Filter is not supported: ${unknownFilter}` };
  }

  const filters = normalizeReportFilters(filtersSource);
  if (
    filters.qualityStatus &&
    filters.qualityStatus !== "ordinary" &&
    filters.qualityStatus !== "defect"
  ) {
    return { ok: false, error: "Quality filter is invalid" };
  }

  for (const key of REPORT_DATE_FILTER_KEYS) {
    const value = filters[key];
    if (value && !datePattern.test(value)) {
      return { ok: false, error: `Date filter is invalid: ${key}` };
    }
  }

  return {
    ok: true,
    payload: {
      name,
      source: typedSource,
      rowDimensions: [rowDimension],
      columnDimensions: [],
      measures: uniqueMeasures,
      filters,
      dateMode,
    },
  };
}
