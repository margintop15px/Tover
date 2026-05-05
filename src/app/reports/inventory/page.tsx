"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { formatCurrency } from "@/lib/format-currency";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import KpiCard from "@/components/KpiCard";
import ReportFilterBar from "@/components/ReportFilterBar";
import { FieldLabel } from "@/components/ui/field";
import type { InventoryBalancesReport, InventoryBalanceRow } from "@/types/inventory";

interface SelectOption {
  id: string;
  name: string;
}

export default function InventoryBalancesPage() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
  const [report, setReport] = useState<InventoryBalancesReport | null>(null);
  const [loading, setLoading] = useState(true);

  const today = new Date().toISOString().split("T")[0];

  // Mode & display
  const [mode, setMode] = useState<"current" | "historical">("current");
  const [display, setDisplay] = useState<"units" | "cost">("units");
  const [historicalDate, setHistoricalDate] = useState(today);

  // Filters
  const [search, setSearch] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [storeId, setStoreId] = useState("");
  const [hideZeros, setHideZeros] = useState(false);
  const [negativesOnly, setNegativesOnly] = useState(false);

  // Options
  const [categories, setCategories] = useState<SelectOption[]>([]);
  const [warehouses, setWarehouses] = useState<SelectOption[]>([]);
  const [stores, setStores] = useState<SelectOption[]>([]);

  useEffect(() => {
    Promise.all([
      fetch("/api/categories").then((r) => r.json()),
      fetch("/api/warehouses").then((r) => r.json()),
      fetch("/api/stores").then((r) => r.json()),
    ]).then(([catData, whData, stData]) => {
      setCategories((catData.items || []).map((c: { id: string; name: string }) => ({ id: c.id, name: c.name })));
      setWarehouses((whData.items || []).map((w: { id: string; name: string }) => ({ id: w.id, name: w.name })));
      setStores((stData.items || []).map((s: { id: string; name: string }) => ({ id: s.id, name: s.name })));
    });
  }, []);

  const fetchReport = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (mode === "historical") params.set("date", historicalDate);
      if (categoryId) params.set("categoryId", categoryId);
      if (warehouseId) params.set("warehouseId", warehouseId);
      if (storeId) params.set("storeId", storeId);
      if (search) params.set("search", search);
      if (hideZeros) params.set("hideZeros", "true");
      if (negativesOnly) params.set("negativesOnly", "true");
      const res = await fetch(`/api/reports/inventory-balances?${params}`);
      const data = await res.json();
      setReport(data);
    } finally {
      setLoading(false);
    }
  }, [mode, historicalDate, categoryId, warehouseId, storeId, search, hideZeros, negativesOnly]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const formatNum = (n: number) => {
    return n.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", { maximumFractionDigits: 2 });
  };
  const formatValue = (n: number) => {
    if (display === "cost") return formatCurrency(n, locale, settings.currency);
    return formatNum(n);
  };

  const getCellValue = (row: InventoryBalanceRow, whId: string): number => {
    const cell = row.warehouses.find((w) => w.warehouseId === whId);
    if (!cell) return 0;
    return display === "cost" ? cell.totalCost : cell.quantity;
  };

  const getTotalValue = (row: InventoryBalanceRow): number => {
    return display === "cost" ? row.totalCost : row.totalQuantity;
  };

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-6">{t.inventoryBalancesTitle}</h1>

      {/* Mode toggle */}
      <div className="flex flex-wrap items-center gap-4 mb-4">
        <Tabs value={mode} onValueChange={(v) => setMode(v as "current" | "historical")}>
          <TabsList>
            <TabsTrigger value="current">{t.currentBalances}</TabsTrigger>
            <TabsTrigger value="historical">{t.historicalBalances}</TabsTrigger>
          </TabsList>
        </Tabs>
        <Tabs value={display} onValueChange={(v) => setDisplay(v as "units" | "cost")}>
          <TabsList>
            <TabsTrigger value="units">{t.displayUnits}</TabsTrigger>
            <TabsTrigger value="cost">{t.displayCost}</TabsTrigger>
          </TabsList>
        </Tabs>
        {mode === "historical" && (
          <div className="space-y-1">
            <Input
              type="date"
              value={historicalDate}
              onChange={(e) => setHistoricalDate(e.target.value)}
              className="w-40"
            />
          </div>
        )}
      </div>

      <ReportFilterBar>
        <div className="space-y-1">
          <FieldLabel>{t.searchProducts}</FieldLabel>
          <Input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t.searchProducts}
            className="w-52"
          />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.productCategory}</FieldLabel>
          <Select value={categoryId} onValueChange={(v) => setCategoryId(v === "all" ? "" : v)}>
            <SelectTrigger className="w-44">
              <SelectValue placeholder={t.allCategories} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allCategories}</SelectItem>
              {categories.map((c) => (
                <SelectItem key={c.id} value={c.id}>{c.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.warehouse}</FieldLabel>
          <Select value={warehouseId} onValueChange={(v) => setWarehouseId(v === "all" ? "" : v)}>
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
        <div className="space-y-1">
          <FieldLabel>{t.productStore}</FieldLabel>
          <Select value={storeId} onValueChange={(v) => setStoreId(v === "all" ? "" : v)}>
            <SelectTrigger className="w-44">
              <SelectValue placeholder={t.allStores} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allStores}</SelectItem>
              {stores.map((s) => (
                <SelectItem key={s.id} value={s.id}>{s.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <label className="flex items-center gap-2 text-sm cursor-pointer pt-5">
          <input
            type="checkbox"
            checked={hideZeros}
            onChange={(e) => setHideZeros(e.target.checked)}
            className="rounded"
          />
          {t.hideZeros}
        </label>
        <label className="flex items-center gap-2 text-sm cursor-pointer pt-5">
          <input
            type="checkbox"
            checked={negativesOnly}
            onChange={(e) => setNegativesOnly(e.target.checked)}
            className="rounded"
          />
          {t.showNegativesOnly}
        </label>
      </ReportFilterBar>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : report ? (
        <>
          {/* KPI cards */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
            <KpiCard title={t.total + " (" + t.displayUnits + ")"} value={formatNum(report.totals.totalQuantity)} />
            {mode === "current" && (
              <KpiCard title={t.total + " (" + t.displayCost + ")"} value={formatCurrency(report.totals.totalCost, locale, settings.currency)} />
            )}
          </div>

          {report.rows.length === 0 ? (
            <div className="rounded-lg border bg-card p-8 text-center text-sm text-muted-foreground">
              {t.noBalancesData}
            </div>
          ) : (
            <div className="rounded-lg border overflow-x-auto">
              <Table>
                <TableHeader>
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="sticky left-0 bg-card z-10">{t.product}</TableHead>
                    <TableHead>{t.sku}</TableHead>
                    {report.warehouseColumns.map((wh) => (
                      <TableHead key={wh.id} className="text-right">{wh.name}</TableHead>
                    ))}
                    <TableHead className="text-right font-bold">{t.total}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {report.rows.map((row) => (
                    <TableRow key={row.productId}>
                      <TableCell className="sticky left-0 bg-card z-10 font-medium">
                        {row.productName}
                      </TableCell>
                      <TableCell className="text-muted-foreground">
                        {row.skuCode || "-"}
                      </TableCell>
                      {report.warehouseColumns.map((wh) => {
                        const val = getCellValue(row, wh.id);
                        return (
                          <TableCell key={wh.id} className="text-right">
                            <span className={val < 0 ? "text-destructive font-medium" : ""}>
                              {val === 0 ? "-" : formatValue(val)}
                            </span>
                          </TableCell>
                        );
                      })}
                      <TableCell className="text-right font-bold">
                        <span className={getTotalValue(row) < 0 ? "text-destructive" : ""}>
                          {formatValue(getTotalValue(row))}
                        </span>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </>
      ) : null}
    </div>
  );
}
