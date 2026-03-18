"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import DataTable from "@/components/DataTable";
import ReportFilterBar from "@/components/ReportFilterBar";
import { FieldLabel } from "@/components/ui/field";
import type { ProductMovementReport, ProductMovementRow } from "@/types/inventory";

interface SelectOption {
  id: string;
  name: string;
}

export default function ProductMovementPage() {
  const { t, locale } = useI18n();
  const [report, setReport] = useState<ProductMovementReport | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().split("T")[0];
  const today = new Date().toISOString().split("T")[0];

  const [dateFrom, setDateFrom] = useState(monthAgo);
  const [dateTo, setDateTo] = useState(today);
  const [groupBy, setGroupBy] = useState("product");
  const [filterProductId, setFilterProductId] = useState("");
  const [filterWarehouseId, setFilterWarehouseId] = useState("");

  const [products, setProducts] = useState<SelectOption[]>([]);
  const [warehouses, setWarehouses] = useState<SelectOption[]>([]);

  useEffect(() => {
    Promise.all([
      fetch("/api/products?limit=500").then((r) => r.json()),
      fetch("/api/warehouses").then((r) => r.json()),
    ]).then(([prodData, whData]) => {
      setProducts((prodData.items || []).map((p: { id: string; name: string }) => ({ id: p.id, name: p.name })));
      setWarehouses((whData.items || []).map((w: { id: string; name: string }) => ({ id: w.id, name: w.name })));
    });
  }, []);

  const fetchReport = useCallback(async () => {
    if (!dateFrom || !dateTo) {
      setError(t.dateRangeRequired);
      return;
    }
    setError("");
    setLoading(true);
    try {
      const params = new URLSearchParams({ from: dateFrom, to: dateTo, groupBy });
      if (filterProductId) params.set("productId", filterProductId);
      if (filterWarehouseId) params.set("warehouseId", filterWarehouseId);
      const res = await fetch(`/api/reports/product-movement?${params}`);
      const data = await res.json();
      setReport(data);
    } finally {
      setLoading(false);
    }
  }, [dateFrom, dateTo, groupBy, filterProductId, filterWarehouseId, t.dateRangeRequired]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const formatNum = (n: number) => {
    if (n === 0) return "-";
    return n.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", { maximumFractionDigits: 2 });
  };

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-6">{t.productMovementTitle}</h1>

      <ReportFilterBar>
        <div className="space-y-1">
          <FieldLabel>{t.from}</FieldLabel>
          <Input type="date" value={dateFrom} onChange={(e) => setDateFrom(e.target.value)} className="w-40" />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.to}</FieldLabel>
          <Input type="date" value={dateTo} onChange={(e) => setDateTo(e.target.value)} className="w-40" />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.product}</FieldLabel>
          <Select value={filterProductId} onValueChange={(v) => setFilterProductId(v === "all" ? "" : v)}>
            <SelectTrigger className="w-48">
              <SelectValue placeholder={t.allProducts} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allProducts}</SelectItem>
              {products.map((p) => (
                <SelectItem key={p.id} value={p.id}>{p.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.warehouse}</FieldLabel>
          <Select value={filterWarehouseId} onValueChange={(v) => setFilterWarehouseId(v === "all" ? "" : v)}>
            <SelectTrigger className="w-44">
              <SelectValue placeholder={t.allWarehouses} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allWarehouses}</SelectItem>
              {warehouses.map((w) => (
                <SelectItem key={w.id} value={w.id}>{w.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </ReportFilterBar>

      <Tabs value={groupBy} onValueChange={setGroupBy} className="mb-4">
        <TabsList>
          <TabsTrigger value="product">{t.groupByProduct}</TabsTrigger>
          <TabsTrigger value="warehouse">{t.groupByWarehouse}</TabsTrigger>
        </TabsList>
      </Tabs>

      {error && <p className="text-destructive mb-4">{error}</p>}

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : report ? (
        <DataTable<ProductMovementRow & Record<string, unknown>>
          columns={[
            { key: "groupName", header: groupBy === "product" ? t.product : t.warehouse },
            ...(groupBy === "product" ? [{ key: "skuCode" as const, header: t.sku, render: (item: ProductMovementRow) => item.skuCode || "-" }] : []),
            { key: "purchaseIn", header: t.purchaseIn, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.purchaseIn) },
            { key: "saleOut", header: t.saleOut, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.saleOut) },
            { key: "returnIn", header: t.returnIn, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.returnIn) },
            { key: "writeOffOut", header: t.writeOffOut, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.writeOffOut) },
            { key: "transferIn", header: t.transferIn, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.transferIn) },
            { key: "transferOut", header: t.transferOut, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.transferOut) },
            { key: "productionIn", header: t.productionIn, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.productionIn) },
            { key: "productionOut", header: t.productionOut, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.productionOut) },
            { key: "defectOut", header: t.defectOut, className: "text-right", render: (item: ProductMovementRow) => formatNum(item.defectOut) },
            {
              key: "net",
              header: t.net,
              className: "text-right font-medium",
              render: (item: ProductMovementRow) => (
                <span className={item.net > 0 ? "text-green-600" : item.net < 0 ? "text-red-600" : ""}>
                  {formatNum(item.net)}
                </span>
              ),
            },
          ]}
          data={report.rows as (ProductMovementRow & Record<string, unknown>)[]}
          emptyMessage={t.noMovementData}
        />
      ) : null}
    </div>
  );
}
