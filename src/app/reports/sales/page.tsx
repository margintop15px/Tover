"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import DataTable from "@/components/DataTable";
import ReportFilterBar from "@/components/ReportFilterBar";
import { FieldLabel } from "@/components/ui/field";
import type { SalesVolumeReport, SalesVolumeRow } from "@/types/inventory";

export default function SalesVolumePage() {
  const { t, locale } = useI18n();
  const [report, setReport] = useState<SalesVolumeReport | null>(null);
  const [loading, setLoading] = useState(true);
  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().split("T")[0];
  const today = new Date().toISOString().split("T")[0];
  const [dateFrom, setDateFrom] = useState(monthAgo);
  const [dateTo, setDateTo] = useState(today);
  const [groupBy, setGroupBy] = useState("store");

  const fetchReport = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ from: dateFrom, to: dateTo, groupBy });
      const res = await fetch(`/api/reports/sales-volume?${params}`);
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
  const formatPercent = (n: number) =>
    n.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", { style: "percent", maximumFractionDigits: 1 });

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.salesVolumeTitle}</h1>
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
          <TabsTrigger value="store">{t.groupByStore}</TabsTrigger>
          <TabsTrigger value="product">{t.groupByProduct}</TabsTrigger>
          <TabsTrigger value="warehouse">{t.groupByWarehouse}</TabsTrigger>
        </TabsList>
      </Tabs>
      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<SalesVolumeRow & Record<string, unknown>>
          tableId="sales-volume"
          columns={[
            { key: "groupName", header: groupBy === "store" ? t.productStore : groupBy === "warehouse" ? t.warehouse : t.product },
            ...(groupBy === "product" ? [{ key: "skuCode" as const, header: t.sku, render: (item: SalesVolumeRow) => item.skuCode || "-" }] : []),
            { key: "soldQuantity", header: t.soldQuantity, className: "text-right", render: (item) => formatNum(item.soldQuantity) },
            { key: "returnedQuantity", header: t.returnedQuantity, className: "text-right", render: (item) => formatNum(item.returnedQuantity) },
            { key: "netSoldQuantity", header: t.netSoldQuantity, className: "text-right font-medium", render: (item) => formatNum(item.netSoldQuantity) },
            { key: "shareOfStoreSales", header: t.shareOfSales, className: "text-right", render: (item) => formatPercent(item.shareOfStoreSales) },
          ]}
          data={(report?.rows || []) as (SalesVolumeRow & Record<string, unknown>)[]}
          emptyMessage={t.noSalesData}
        />
      )}
    </div>
  );
}
