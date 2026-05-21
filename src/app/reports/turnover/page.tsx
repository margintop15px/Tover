"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { formatCurrency } from "@/lib/format-currency";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import DataTable from "@/components/DataTable";
import ReportFilterBar from "@/components/ReportFilterBar";
import { FieldLabel } from "@/components/ui/field";
import type { TurnoverReport, TurnoverRow } from "@/types/inventory";

export default function TurnoverReportPage() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
  const [report, setReport] = useState<TurnoverReport | null>(null);
  const [loading, setLoading] = useState(true);
  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().split("T")[0];
  const today = new Date().toISOString().split("T")[0];
  const [dateFrom, setDateFrom] = useState(monthAgo);
  const [dateTo, setDateTo] = useState(today);
  const [groupBy, setGroupBy] = useState("product");

  const fetchReport = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ from: dateFrom, to: dateTo, groupBy });
      const res = await fetch(`/api/reports/turnover?${params}`);
      setReport(await res.json());
    } finally {
      setLoading(false);
    }
  }, [dateFrom, dateTo, groupBy]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const formatMoney = (n: number) => formatCurrency(n, locale, settings.currency);
  const formatNum = (n: number | null) =>
    n == null ? "-" : n.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", { maximumFractionDigits: 2 });

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.turnoverTitle}</h1>
      <ReportFilterBar>
        <div className="space-y-1">
          <FieldLabel>{t.from}</FieldLabel>
          <Input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} className="w-40" />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.to}</FieldLabel>
          <Input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} className="w-40" />
        </div>
      </ReportFilterBar>
      <Tabs value={groupBy} onValueChange={setGroupBy} className="mb-4">
        <TabsList>
          <TabsTrigger value="product">{t.groupByProduct}</TabsTrigger>
          <TabsTrigger value="store">{t.groupByStore}</TabsTrigger>
          <TabsTrigger value="warehouse">{t.groupByWarehouse}</TabsTrigger>
        </TabsList>
      </Tabs>
      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<TurnoverRow & Record<string, unknown>>
          tableId="turnover"
          columns={[
            { key: "groupName", header: t.name },
            ...(groupBy === "product" ? [{ key: "skuCode" as const, header: t.sku, render: (item: TurnoverRow) => item.skuCode || "-" }] : []),
            { key: "outflowCost", header: t.outflowCost, className: "text-right", render: (item) => formatMoney(item.outflowCost) },
            { key: "averageInventoryCost", header: t.averageInventoryCost, className: "text-right", render: (item) => formatMoney(item.averageInventoryCost) },
            { key: "turnoverRatio", header: t.turnoverRatio, className: "text-right", render: (item) => formatNum(item.turnoverRatio) },
            { key: "turnoverDays", header: t.turnoverDays, className: "text-right", render: (item) => formatNum(item.turnoverDays) },
          ]}
          data={(report?.rows || []) as (TurnoverRow & Record<string, unknown>)[]}
          emptyMessage={t.noTurnoverData}
        />
      )}
    </div>
  );
}
