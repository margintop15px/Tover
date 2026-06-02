"use client";

import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Edit } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import ReportExportButton from "@/components/reports/ReportExportButton";
import ReportPreviewTable, {
  useReportPresentation,
} from "@/components/reports/ReportPreviewTable";
import { useI18n } from "@/i18n/context";
import {
  buildReportUrlForTemplate,
  getTemplateRowDimension,
} from "@/lib/reports/report-runner";
import { REPORT_SOURCE_CONFIGS } from "@/lib/reports/report-constructor";
import type { PreviewReport } from "@/lib/reports/report-display";
import type { ReportTemplate } from "@/types/inventory";

export default function SavedReportPage() {
  return (
    <Suspense fallback={<div className="p-6 text-muted-foreground">Loading...</div>}>
      <SavedReportPageContent />
    </Suspense>
  );
}

function SavedReportPageContent() {
  const { t } = useI18n();
  const params = useParams<{ id: string }>();
  const [template, setTemplate] = useState<ReportTemplate | null>(null);
  const [report, setReport] = useState<PreviewReport | null>(null);
  const [loadingTemplate, setLoadingTemplate] = useState(true);
  const [loadingReport, setLoadingReport] = useState(false);
  const [error, setError] = useState("");
  const source = template?.source || "inventory_balances";
  const rowDimension = template
    ? getTemplateRowDimension(template.source, template.rowDimensions)
    : REPORT_SOURCE_CONFIGS.inventory_balances.defaultDimensions[0];
  const measures = template?.measures || REPORT_SOURCE_CONFIGS[source].defaultMeasures;
  const { columns, tableRows } = useReportPresentation(
    source,
    rowDimension,
    measures,
    report
  );

  const exportRows = useMemo(
    () =>
      tableRows.map((row) =>
        Object.fromEntries(columns.map((column) => [column.label, row[column.key]]))
      ),
    [columns, tableRows]
  );

  const loadReport = useCallback(async (nextTemplate: ReportTemplate) => {
    setLoadingReport(true);
    setError("");
    try {
      const response = await fetch(buildReportUrlForTemplate(nextTemplate));
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || t.unexpectedError);
      setReport(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setReport(null);
    } finally {
      setLoadingReport(false);
    }
  }, [t.unexpectedError]);

  useEffect(() => {
    let cancelled = false;
    setLoadingTemplate(true);
    fetch(`/api/report-templates/${params.id}`)
      .then(async (response) => {
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || t.reportNotFound);
        if (!cancelled) {
          setTemplate(data);
          loadReport(data);
        }
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (!cancelled) setLoadingTemplate(false);
      });

    return () => {
      cancelled = true;
    };
  }, [loadReport, params.id, t.reportNotFound]);

  if (loadingTemplate) {
    return <div className="p-6 text-muted-foreground">{t.loading}</div>;
  }

  if (!template) {
    return (
      <div className="p-6">
        <div className="rounded-lg border bg-card p-8 text-sm text-muted-foreground">
          {error || t.reportNotFound}
        </div>
      </div>
    );
  }

  const config = REPORT_SOURCE_CONFIGS[template.source];

  return (
    <div className="p-6">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <Badge variant="secondary">{String(t[config.labelKey])}</Badge>
            <Badge variant="outline">{template.dateMode === "as_of" ? t.asOfDate : `${t.from} / ${t.to}`}</Badge>
          </div>
          <h1 className="text-2xl font-bold">{template.name}</h1>
          <p className="text-sm text-muted-foreground">{t.savedReport}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" size="sm" asChild>
            <Link href="/reports/templates">{t.back}</Link>
          </Button>
          <ReportExportButton
            title={template.name}
            rows={exportRows}
            disabled={loadingReport}
          />
          <Button size="sm" asChild className="gap-2">
            <Link href={`/reports/templates/${template.id}/edit`}>
              <Edit className="h-4 w-4" />
              {t.edit}
            </Link>
          </Button>
        </div>
      </div>

      <div className="rounded-lg border bg-card">
        <div className="border-b p-4">
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Badge variant="outline">groupBy: {rowDimension}</Badge>
            {loadingReport && (
              <span className="text-sm text-muted-foreground">{t.loading}</span>
            )}
          </div>
          <div className="text-sm text-muted-foreground">
            {template.rowDimensions.join(", ")}
            {" · "}
            {template.measures.join(", ")}
          </div>
        </div>
        <div className="p-4">
          <ReportPreviewTable
            source={template.source}
            rowDimension={rowDimension}
            measures={template.measures}
            report={report}
            error={error}
            maxRows={100}
          />
        </div>
      </div>
    </div>
  );
}
