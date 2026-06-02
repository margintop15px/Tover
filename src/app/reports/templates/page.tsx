"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import DataTable from "@/components/DataTable";
import type { ReportTemplate } from "@/types/inventory";
import { REPORT_SOURCE_CONFIGS } from "@/lib/reports/report-constructor";
import { Edit, ExternalLink, Plus, Trash2 } from "lucide-react";

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

  const deleteTemplate = async (item: ReportTemplate) => {
    if (!window.confirm(t.confirmDelete)) return;
    const response = await fetch(`/api/report-templates/${item.id}`, {
      method: "DELETE",
    });
    if (response.ok) {
      setItems((current) => current.filter((template) => template.id !== item.id));
    } else {
      const data = await response.json().catch(() => ({}));
      window.alert(data.error || t.unexpectedError);
    }
  };

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
            {
              key: "actions",
              header: t.actions,
              className: "text-right",
              render: (item) => (
                <div className="flex justify-end gap-2">
                  <Button asChild size="sm" variant="outline">
                    <Link href={`/reports/templates/${item.id}`}>
                      <ExternalLink className="h-4 w-4" />
                      {t.openReport}
                    </Link>
                  </Button>
                  <Button asChild size="sm" variant="outline">
                    <Link href={`/reports/templates/${item.id}/edit`}>
                      <Edit className="h-4 w-4" />
                      {t.edit}
                    </Link>
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => deleteTemplate(item)}
                  >
                    <Trash2 className="h-4 w-4" />
                    {t.delete}
                  </Button>
                </div>
              ),
            },
          ]}
          data={items as (ReportTemplate & Record<string, unknown>)[]}
          emptyMessage={t.noReportTemplates}
        />
      )}
    </div>
  );
}
