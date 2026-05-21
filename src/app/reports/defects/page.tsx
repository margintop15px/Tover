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
import type { DefectDynamicsReport, DefectDynamicsRow } from "@/types/inventory";

export default function DefectsReportPage() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
  const [report, setReport] = useState<DefectDynamicsReport | null>(null);
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
      const res = await fetch(`/api/reports/defects?${params}`);
      setReport(await res.json());
    } finally {
      setLoading(false);
    }
  }, [dateFrom, dateTo, groupBy]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const formatNum = (n: number) =>
    n === 0 ? "-" : n.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", { maximumFractionDigits: 2 });
  const formatMoney = (n: number) => formatCurrency(n, locale, settings.currency);

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.defectsTitle}</h1>
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
        <DataTable<DefectDynamicsRow & Record<string, unknown>>
          tableId="defects"
          columns={[
            { key: "groupName", header: t.name },
            ...(groupBy === "product" ? [{ key: "skuCode" as const, header: t.sku, render: (item: DefectDynamicsRow) => item.skuCode || "-" }] : []),
            { key: "defectInQuantity", header: t.defectInQuantity, className: "text-right", render: (item) => formatNum(item.defectInQuantity) },
            { key: "defectOutQuantity", header: t.defectOutQuantity, className: "text-right", render: (item) => formatNum(item.defectOutQuantity) },
            { key: "defectBalanceDelta", header: t.defectBalanceDelta, className: "text-right font-medium", render: (item) => formatNum(item.defectBalanceDelta) },
            { key: "defectCost", header: t.defectCost, className: "text-right", render: (item) => formatMoney(item.defectCost) },
          ]}
          data={(report?.rows || []) as (DefectDynamicsRow & Record<string, unknown>)[]}
          emptyMessage={t.noDefectData}
        />
      )}
    </div>
  );
}
