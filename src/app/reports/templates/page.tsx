"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import DataTable from "@/components/DataTable";
import type { ReportTemplate } from "@/types/inventory";
import { REPORT_SOURCE_CONFIGS } from "@/lib/reports/report-constructor";
import { Plus } from "lucide-react";

export default function ReportTemplatesPage() {
  const { t } = useI18n();
  const [items, setItems] = useState<ReportTemplate[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/report-templates")
      .then((res) => res.json())
      .then((data) => setItems(data.items || []))
      .finally(() => setLoading(false));
  }, []);

  const sourceLabel = (source: ReportTemplate["source"]) => {
    const config = REPORT_SOURCE_CONFIGS[source];
    return config ? String(t[config.labelKey]) : source;
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t.reportTemplates}</h1>
        <Button asChild className="gap-2">
          <Link href="/reports/templates/new">
            <Plus className="h-4 w-4" />
            {t.createReport}
          </Link>
        </Button>
      </div>
      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<ReportTemplate & Record<string, unknown>>
          tableId="report-templates"
          columns={[
            { key: "name", header: t.name },
            {
              key: "source",
              header: t.reportsGroup,
              render: (item) => sourceLabel(item.source),
            },
            {
              key: "rowDimensions",
              header: t.rowDimensions,
              render: (item) => item.rowDimensions.join(", ") || "-",
            },
            {
              key: "measures",
              header: t.measures,
              render: (item) => item.measures.join(", ") || "-",
            },
          ]}
          data={items as (ReportTemplate & Record<string, unknown>)[]}
          emptyMessage={t.noReportTemplates}
        />
      )}
    </div>
  );
}
