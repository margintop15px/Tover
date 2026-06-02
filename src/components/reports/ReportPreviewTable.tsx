"use client";

import { useCallback, useMemo } from "react";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatCurrency } from "@/lib/format-currency";
import {
  getReportColumnKeys,
  getReportTableRows,
  previewColumnType,
  type PreviewColumnType,
  type PreviewReport,
} from "@/lib/reports/report-display";
import type { ReportTemplateSource } from "@/types/inventory";
import type { ReportDimension, ReportMeasure } from "@/lib/reports/report-constructor";

export interface PreviewColumn {
  key: string;
  label: string;
  type: PreviewColumnType;
}

export function useReportPresentation(
  source: ReportTemplateSource,
  rowDimension: ReportDimension | null | undefined,
  measures: string[],
  report: PreviewReport | null
) {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();

  const dimensionLabel = useCallback(
    (dimension: ReportDimension) => {
      const labels: Record<ReportDimension, string> = {
        product: t.product,
        category: t.productCategory,
        warehouse: t.warehouse,
        store: t.productStore,
        quality: t.qualityStatus,
        supplier: t.supplier,
      };
      return labels[dimension];
    },
    [t]
  );

  const measureLabel = useCallback(
    (measure: ReportMeasure) => {
      const labels: Record<ReportMeasure, string> = {
        quantity: t.displayUnits,
        cost: t.displayCost,
        invoice: t.totalPurchased,
        payments: t.totalPaid,
        debt: t.totalDebt,
        turnover: t.turnoverRatio,
      };
      return labels[measure];
    },
    [t]
  );

  const sourceColumnLabels = useMemo<Record<string, string>>(
    () => ({
      groupName: rowDimension ? dimensionLabel(rowDimension) : t.rowDimensions,
      supplierName: t.supplier,
      skuCode: t.sku,
      totalQuantity: t.displayUnits,
      totalCost: t.displayCost,
      purchaseIn: t.purchaseIn,
      purchaseInCost: `${t.purchaseIn} (${t.displayCost})`,
      saleOut: t.saleOut,
      saleOutCost: `${t.saleOut} (${t.displayCost})`,
      returnIn: t.returnIn,
      returnInCost: `${t.returnIn} (${t.displayCost})`,
      writeOffOut: t.writeOffOut,
      writeOffOutCost: `${t.writeOffOut} (${t.displayCost})`,
      transferIn: t.transferIn,
      transferInCost: `${t.transferIn} (${t.displayCost})`,
      transferOut: t.transferOut,
      transferOutCost: `${t.transferOut} (${t.displayCost})`,
      productionIn: t.productionIn,
      productionInCost: `${t.productionIn} (${t.displayCost})`,
      productionOut: t.productionOut,
      productionOutCost: `${t.productionOut} (${t.displayCost})`,
      defectOut: t.defectOut,
      defectOutCost: `${t.defectOut} (${t.displayCost})`,
      net: t.net,
      netCost: `${t.net} (${t.displayCost})`,
      soldQuantity: t.soldQuantity,
      returnedQuantity: t.returnedQuantity,
      netSoldQuantity: t.netSoldQuantity,
      shareOfStoreSales: t.shareOfSales,
      outflowCost: t.outflowCost,
      averageInventoryCost: t.averageInventoryCost,
      turnoverRatio: t.turnoverRatio,
      turnoverDays: t.turnoverDays,
      defectInQuantity: t.defectInQuantity,
      defectOutQuantity: t.defectOutQuantity,
      defectBalanceDelta: t.defectBalanceDelta,
      defectCost: t.defectCost,
      purchasedInPeriod: t.purchasedInPeriod,
      paidInPeriod: t.paidInPeriod,
      currentDebt: t.currentDebt,
      debtType: t.debtType,
      totalPurchased: t.totalPurchased,
      totalPaid: t.totalPaid,
      totalDebt: t.totalDebt,
    }),
    [dimensionLabel, rowDimension, t]
  );

  const tableRows = useMemo(
    () => getReportTableRows(source, rowDimension, report),
    [report, rowDimension, source]
  );

  const columns = useMemo<PreviewColumn[]>(
    () =>
      getReportColumnKeys(source, rowDimension, measures, tableRows).map(
        (key) => ({
          key,
          label: sourceColumnLabels[key] || key,
          type: previewColumnType(key),
        })
      ),
    [measures, rowDimension, source, sourceColumnLabels, tableRows]
  );

  const formatPlainNumber = useCallback(
    (value: number, maximumFractionDigits = 2) =>
      value.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", {
        maximumFractionDigits,
      }),
    [locale]
  );

  const formatPreviewValue = useCallback(
    (value: unknown, type: PreviewColumnType) => {
      if (value == null || value === "") return "-";

      if (type === "text") return String(value);

      const numeric = Number(value);
      if (!Number.isFinite(numeric)) return String(value);

      if (type === "money") {
        return formatCurrency(numeric, locale, settings.currency);
      }
      if (type === "percent") {
        return numeric.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", {
          style: "percent",
          maximumFractionDigits: 1,
        });
      }
      return formatPlainNumber(numeric);
    },
    [formatPlainNumber, locale, settings.currency]
  );

  return {
    columns,
    dimensionLabel,
    formatPreviewValue,
    measureLabel,
    sourceColumnLabels,
    tableRows,
  };
}

export default function ReportPreviewTable({
  source,
  rowDimension,
  measures,
  report,
  error,
  maxRows = 25,
}: {
  source: ReportTemplateSource;
  rowDimension: ReportDimension | null | undefined;
  measures: string[];
  report: PreviewReport | null;
  error?: string;
  maxRows?: number;
}) {
  const { t } = useI18n();
  const {
    columns,
    formatPreviewValue,
    sourceColumnLabels,
    tableRows,
  } = useReportPresentation(source, rowDimension, measures, report);

  if (error) {
    return (
      <div className="rounded-md border bg-muted/40 p-6 text-sm text-muted-foreground">
        {error}
      </div>
    );
  }

  if (tableRows.length === 0) {
    return (
      <div className="rounded-md border bg-muted/40 p-10 text-center text-sm text-muted-foreground">
        {t.noPreviewData}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="overflow-x-auto rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead
                  key={column.key}
                  className={column.type === "text" ? "" : "text-right"}
                >
                  {column.label}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {tableRows.slice(0, maxRows).map((row, index) => (
              <TableRow key={index}>
                {columns.map((column) => (
                  <TableCell
                    key={column.key}
                    className={
                      column.type === "text" ? "" : "text-right tabular-nums"
                    }
                  >
                    {formatPreviewValue(row[column.key], column.type)}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      {report?.totals && (
        <div className="rounded-lg border bg-card p-4">
          <div className="mb-2 text-sm font-medium">{t.total}</div>
          <div className="flex flex-wrap gap-2">
            {Object.entries(report.totals).map(([key, value]) => (
              <Badge key={key} variant="outline">
                {sourceColumnLabels[key] || key}:{" "}
                {formatPreviewValue(value, previewColumnType(key))}
              </Badge>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
