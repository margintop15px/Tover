"use client";

import { useState } from "react";
import { Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/context";

function safeFileName(title: string) {
  return title
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9а-яё]+/gi, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 80);
}

export default function ReportExportButton({
  title,
  rows,
  disabled,
}: {
  title: string;
  rows: Record<string, unknown>[];
  disabled?: boolean;
}) {
  const { t } = useI18n();
  const [exporting, setExporting] = useState(false);

  const exportCsv = async () => {
    setExporting(true);
    try {
      const response = await fetch("/api/reports/export", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          format: "csv",
          title,
          report: { rows },
        }),
      });
      if (!response.ok) throw new Error(t.exportFailed);

      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `${safeFileName(title) || "report"}.csv`;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (error) {
      window.alert(error instanceof Error ? error.message : t.exportFailed);
    } finally {
      setExporting(false);
    }
  };

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className="gap-2"
      disabled={disabled || exporting}
      onClick={exportCsv}
    >
      <Download className="h-4 w-4" />
      {exporting ? t.exporting : t.exportCsv}
    </Button>
  );
}
