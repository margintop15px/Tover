"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
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
import { formatCurrency } from "@/lib/format-currency";
import type {
  ReportTemplateSource,
} from "@/types/inventory";
import {
  getPreviewGroupBy,
  REPORT_SOURCE_CONFIGS,
  REPORT_SOURCES,
  type ReportDimension,
  type ReportMeasure,
} from "@/lib/reports/report-constructor";

interface SelectOption {
  id: string;
  name: string;
}

type PreviewReport = {
  rows?: Record<string, unknown>[];
  totals?: Record<string, unknown>;
  [key: string]: unknown;
};

interface PreviewColumn {
  key: string;
  label: string;
  type: "text" | "quantity" | "money" | "percent" | "ratio" | "days";
}

const today = new Date().toISOString().split("T")[0];
const monthAgo = new Date(Date.now() - 30 * 86400000)
  .toISOString()
  .split("T")[0];

const moneyColumns = new Set([
  "totalCost",
  "purchaseInCost",
  "saleOutCost",
  "returnInCost",
  "writeOffOutCost",
  "transferInCost",
  "transferOutCost",
  "productionInCost",
  "productionOutCost",
  "defectOutCost",
  "netCost",
  "outflowCost",
  "averageInventoryCost",
  "defectCost",
  "purchasedInPeriod",
  "paidInPeriod",
  "currentDebt",
  "totalPurchased",
  "totalPaid",
  "totalDebt",
]);

const percentColumns = new Set(["shareOfStoreSales"]);
const ratioColumns = new Set(["turnoverRatio"]);
const dayColumns = new Set(["turnoverDays"]);
const textColumns = new Set(["groupName", "supplierName", "skuCode", "debtType"]);

function previewColumnType(key: string): PreviewColumn["type"] {
  if (textColumns.has(key)) return "text";
  if (moneyColumns.has(key)) return "money";
  if (percentColumns.has(key)) return "percent";
  if (ratioColumns.has(key)) return "ratio";
  if (dayColumns.has(key)) return "days";
  return "quantity";
}

export default function NewReportTemplatePage() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
  const router = useRouter();
  const [name, setName] = useState("");
  const [source, setSource] =
    useState<ReportTemplateSource>("inventory_balances");
  const config = REPORT_SOURCE_CONFIGS[source];
  const [dateMode, setDateMode] = useState<"as_of" | "period">(
    config.defaultDateMode
  );
  const [asOfDate, setAsOfDate] = useState(today);
  const [dateFrom, setDateFrom] = useState(monthAgo);
  const [dateTo, setDateTo] = useState(today);
  const [rowDimension, setRowDimension] = useState<ReportDimension>(
    config.defaultDimensions[0]
  );
  const [measures, setMeasures] = useState<string[]>(config.defaultMeasures);
  const [productId, setProductId] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [warehouseId, setWarehouseId] = useState("");
  const [storeId, setStoreId] = useState("");
  const [supplierId, setSupplierId] = useState("");
  const [qualityStatus, setQualityStatus] = useState("");
  const [products, setProducts] = useState<SelectOption[]>([]);
  const [categories, setCategories] = useState<SelectOption[]>([]);
  const [warehouses, setWarehouses] = useState<SelectOption[]>([]);
  const [stores, setStores] = useState<SelectOption[]>([]);
  const [suppliers, setSuppliers] = useState<SelectOption[]>([]);
  const [preview, setPreview] = useState<PreviewReport | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState("");
  const [saving, setSaving] = useState(false);

  const dimensionLabel = useCallback((dimension: ReportDimension) => {
    const labels: Record<ReportDimension, string> = {
      product: t.product,
      category: t.productCategory,
      warehouse: t.warehouse,
      store: t.productStore,
      quality: t.qualityStatus,
      supplier: t.supplier,
    };
    return labels[dimension];
  }, [t]);

  const measureLabel = useCallback((measure: ReportMeasure) => {
    const labels: Record<ReportMeasure, string> = {
      quantity: t.displayUnits,
      cost: t.displayCost,
      invoice: t.totalPurchased,
      payments: t.totalPaid,
      debt: t.totalDebt,
      turnover: t.turnoverRatio,
    };
    return labels[measure];
  }, [t]);

  const previewGroupBy = useMemo(
    () => getPreviewGroupBy(config, [rowDimension]),
    [config, rowDimension]
  );

  const sourceColumnLabels = useMemo<Record<string, string>>(
    () => ({
      groupName: previewGroupBy
        ? dimensionLabel(previewGroupBy)
        : t.rowDimensions,
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
    [dimensionLabel, previewGroupBy, t]
  );

  const getPreviewColumns = useCallback(
    (rowSample: Record<string, unknown>[]): PreviewColumn[] => {
      const base = source === "supplier_settlements" ? ["supplierName"] : ["groupName"];
      if (previewGroupBy === "product" && rowSample.some((row) => row.skuCode != null)) {
        base.push("skuCode");
      }

      const measureColumns: Record<ReportTemplateSource, Record<ReportMeasure, string[]>> = {
        inventory_balances: {
          quantity: ["totalQuantity"],
          cost: ["totalCost"],
          invoice: [],
          payments: [],
          debt: [],
          turnover: [],
        },
        product_movement: {
          quantity: [
            "purchaseIn",
            "saleOut",
            "returnIn",
            "writeOffOut",
            "transferIn",
            "transferOut",
            "productionIn",
            "productionOut",
            "defectOut",
            "net",
          ],
          cost: [
            "purchaseInCost",
            "saleOutCost",
            "returnInCost",
            "writeOffOutCost",
            "transferInCost",
            "transferOutCost",
            "productionInCost",
            "productionOutCost",
            "defectOutCost",
            "netCost",
          ],
          invoice: [],
          payments: [],
          debt: [],
          turnover: [],
        },
        sales_volume: {
          quantity: [
            "soldQuantity",
            "returnedQuantity",
            "netSoldQuantity",
            "shareOfStoreSales",
          ],
          cost: [],
          invoice: [],
          payments: [],
          debt: [],
          turnover: [],
        },
        turnover: {
          quantity: [],
          cost: ["outflowCost", "averageInventoryCost"],
          invoice: [],
          payments: [],
          debt: [],
          turnover: ["turnoverRatio", "turnoverDays"],
        },
        defects: {
          quantity: [
            "defectInQuantity",
            "defectOutQuantity",
            "defectBalanceDelta",
          ],
          cost: ["defectCost"],
          invoice: [],
          payments: [],
          debt: [],
          turnover: [],
        },
        supplier_settlements: {
          quantity: [],
          cost: [],
          invoice: ["purchasedInPeriod"],
          payments: ["paidInPeriod"],
          debt: ["currentDebt", "debtType"],
          turnover: [],
        },
      };

      const selected = measures.flatMap(
        (measure) =>
          measureColumns[source][measure as ReportMeasure] || []
      );
      const available = new Set(rowSample.flatMap((row) => Object.keys(row)));
      return [...base, ...selected]
        .filter((key, index, list) => list.indexOf(key) === index)
        .filter((key) => key === "groupName" || key === "supplierName" || available.has(key))
        .map((key) => ({
          key,
          label: sourceColumnLabels[key] || key,
          type: previewColumnType(key),
        }));
    },
    [measures, previewGroupBy, source, sourceColumnLabels]
  );

  const formatPlainNumber = useCallback(
    (value: number, maximumFractionDigits = 2) =>
      value.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", {
        maximumFractionDigits,
      }),
    [locale]
  );

  const formatPreviewValue = useCallback(
    (value: unknown, type: PreviewColumn["type"]) => {
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
      if (type === "ratio") {
        return formatPlainNumber(numeric);
      }
      return formatPlainNumber(numeric);
    },
    [formatPlainNumber, locale, settings.currency]
  );

  useEffect(() => {
    Promise.all([
      fetch("/api/products?limit=500").then((res) => res.json()),
      fetch("/api/categories?limit=500").then((res) => res.json()),
      fetch("/api/warehouses?limit=500").then((res) => res.json()),
      fetch("/api/stores?limit=500").then((res) => res.json()),
      fetch("/api/suppliers?limit=500").then((res) => res.json()),
    ]).then(([productData, categoryData, warehouseData, storeData, supplierData]) => {
      setProducts((productData.items || []).map((item: SelectOption) => ({ id: item.id, name: item.name })));
      setCategories((categoryData.items || []).map((item: SelectOption) => ({ id: item.id, name: item.name })));
      setWarehouses((warehouseData.items || []).map((item: SelectOption) => ({ id: item.id, name: item.name })));
      setStores((storeData.items || []).map((item: SelectOption) => ({ id: item.id, name: item.name })));
      setSuppliers((supplierData.items || []).map((item: SelectOption) => ({ id: item.id, name: item.name })));
    });
  }, []);

  const resetForSource = (nextSource: ReportTemplateSource) => {
    const nextConfig = REPORT_SOURCE_CONFIGS[nextSource];
    setSource(nextSource);
    setDateMode(nextConfig.defaultDateMode);
    setRowDimension(nextConfig.defaultDimensions[0]);
    setMeasures(nextConfig.defaultMeasures);
    setPreviewError("");
  };

  const toggle = (
    value: string,
    values: string[],
    setValues: (next: string[]) => void
  ) => {
    setValues(
      values.includes(value)
        ? values.filter((item) => item !== value)
        : [...values, value]
    );
  };

  const missingPreviewReason = useMemo(() => {
    if (measures.length === 0) return t.selectAtLeastOneMeasure;
    if (!previewGroupBy) return t.previewUnsupportedDimensions;
    if (dateMode === "period" && (!dateFrom || !dateTo)) {
      return t.dateRangeRequired;
    }
    if (dateMode === "as_of" && !asOfDate) return t.asOfDate;
    return "";
  }, [
    asOfDate,
    dateFrom,
    dateMode,
    dateTo,
    measures.length,
    previewGroupBy,
    t,
  ]);

  const filters = useMemo(
    () => ({
      productId,
      categoryId,
      warehouseId,
      storeId,
      supplierId,
      qualityStatus,
    }),
    [categoryId, productId, qualityStatus, storeId, supplierId, warehouseId]
  );

  const buildPreviewUrl = useCallback(() => {
    const params = new URLSearchParams();
    if (dateMode === "as_of") {
      if (source === "supplier_settlements") {
        params.set("asOfDate", asOfDate);
        params.set("periodFrom", dateFrom || asOfDate);
        params.set("periodTo", dateTo || asOfDate);
      } else {
        params.set("date", asOfDate);
      }
    } else {
      if (source === "supplier_settlements") {
        params.set("asOfDate", dateTo);
        params.set("periodFrom", dateFrom);
        params.set("periodTo", dateTo);
      } else {
        params.set("from", dateFrom);
        params.set("to", dateTo);
      }
    }
    if (previewGroupBy && source !== "inventory_balances") {
      params.set("groupBy", previewGroupBy);
    }
    Object.entries(filters).forEach(([key, value]) => {
      if (value) params.set(key, value);
    });
    return `${config.endpoint}?${params}`;
  }, [
    asOfDate,
    config.endpoint,
    dateFrom,
    dateMode,
    dateTo,
    filters,
    previewGroupBy,
    source,
  ]);

  useEffect(() => {
    if (missingPreviewReason) {
      setPreviewError(missingPreviewReason);
      setPreview(null);
      return;
    }

    const controller = new AbortController();
    const timeout = window.setTimeout(async () => {
      setPreviewLoading(true);
      setPreviewError("");
      try {
        const response = await fetch(buildPreviewUrl(), {
          signal: controller.signal,
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Preview failed");
        setPreview(data);
      } catch (error) {
        if (!controller.signal.aborted) {
          setPreviewError(error instanceof Error ? error.message : String(error));
        }
      } finally {
        if (!controller.signal.aborted) setPreviewLoading(false);
      }
    }, 400);

    return () => {
      window.clearTimeout(timeout);
      controller.abort();
    };
  }, [buildPreviewUrl, missingPreviewReason]);

  const saveTemplate = async () => {
    if (!name.trim() || missingPreviewReason) return;
    setSaving(true);
    try {
      const response = await fetch("/api/report-templates", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: name.trim(),
          source,
          dateMode,
          rowDimensions: [rowDimension],
          columnDimensions: [],
          measures,
          filters,
        }),
      });
      if (response.ok) {
        router.push("/reports/templates");
      } else {
        const data = await response.json();
        setPreviewError(data.error || t.unexpectedError);
      }
    } finally {
      setSaving(false);
    }
  };

  const rows = useMemo(
    () => (preview?.rows || []) as Record<string, unknown>[],
    [preview]
  );
  const tableRows = useMemo(() => {
    if (source !== "inventory_balances" || !previewGroupBy) return rows;

    const groupMap = new Map<string, Record<string, unknown>>();
    const addToGroup = (
      groupId: string,
      groupName: string,
      quantity: number,
      cost: number,
      extra: Record<string, unknown> = {}
    ) => {
      const current = groupMap.get(groupId) || {
        groupName,
        totalQuantity: 0,
        totalCost: 0,
        ...extra,
      };
      current.totalQuantity = Number(current.totalQuantity || 0) + quantity;
      current.totalCost = Number(current.totalCost || 0) + cost;
      groupMap.set(groupId, current);
    };

    for (const row of rows) {
      const quantity = Number(row.totalQuantity || 0);
      const cost = Number(row.totalCost || 0);

      if (previewGroupBy === "warehouse") {
        const warehouses = Array.isArray(row.warehouses)
          ? (row.warehouses as Record<string, unknown>[])
          : [];
        for (const warehouse of warehouses) {
          const warehouseId = String(warehouse.warehouseId || "unassigned");
          addToGroup(
            warehouseId,
            String(warehouse.warehouseName || "Unknown"),
            Number(warehouse.quantity || 0),
            Number(warehouse.totalCost || 0)
          );
        }
        continue;
      }

      const groupId =
        previewGroupBy === "category"
          ? String(row.categoryName || "uncategorized")
          : previewGroupBy === "store"
            ? String(row.storeName || "unassigned")
            : previewGroupBy === "quality"
              ? String(row.qualityStatus || "ordinary")
              : String(row.productId || row.productName || "unknown");
      const groupName =
        previewGroupBy === "category"
          ? String(row.categoryName || "No category")
          : previewGroupBy === "store"
            ? String(row.storeName || "No store")
            : previewGroupBy === "quality"
              ? String(row.qualityStatus || "ordinary")
              : String(row.productName || "Unknown");
      addToGroup(groupId, groupName, quantity, cost, {
        skuCode: previewGroupBy === "product" ? row.skuCode : null,
      });
    }

    return Array.from(groupMap.values()).sort((a, b) =>
      String(a.groupName).localeCompare(String(b.groupName))
    );
  }, [previewGroupBy, rows, source]);
  const columns = useMemo(
    () => getPreviewColumns(tableRows),
    [getPreviewColumns, tableRows]
  );
  const activeFilterCount = Object.values(filters).filter(Boolean).length;

  const OptionSelect = ({
    label,
    value,
    allLabel,
    options,
    onValueChange,
  }: {
    label: string;
    value: string;
    allLabel: string;
    options: SelectOption[];
    onValueChange: (value: string) => void;
  }) => (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <Select
        value={value || "all"}
        onValueChange={(next) => onValueChange(next === "all" ? "" : next)}
      >
        <SelectTrigger>
          <SelectValue placeholder={allLabel} />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="all">{allLabel}</SelectItem>
          {options.map((option) => (
            <SelectItem key={option.id} value={option.id}>
              {option.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </Field>
  );

  return (
    <div className="p-6">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{t.createReport}</h1>
          <p className="text-sm text-muted-foreground">{t.reportPreview}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" asChild>
            <Link href="/reports/templates">{t.cancel}</Link>
          </Button>
          <Button
            onClick={saveTemplate}
            disabled={saving || !name.trim() || Boolean(missingPreviewReason)}
          >
            {saving ? t.saving : t.save}
          </Button>
        </div>
      </div>

      <div className="grid gap-5 lg:grid-cols-[340px_minmax(0,1fr)]">
        <aside className="space-y-4 rounded-lg border bg-card p-4 lg:max-h-[calc(100vh-120px)] lg:overflow-y-auto">
          <Field>
            <FieldLabel>{t.name}</FieldLabel>
            <Input value={name} onChange={(event) => setName(event.target.value)} />
          </Field>

          <Field>
            <FieldLabel>{t.sourceReport}</FieldLabel>
            <Select
              value={source}
              onValueChange={(value) =>
                resetForSource(value as ReportTemplateSource)
              }
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {REPORT_SOURCES.map((item) => (
                  <SelectItem key={item.source} value={item.source}>
                    {String(t[item.labelKey])}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>

          <div className="space-y-2 rounded-md border bg-muted/20 p-3">
            <FieldLabel>{t.dateMode}</FieldLabel>
            <Tabs
              value={dateMode}
              onValueChange={(value) => {
                const nextMode = value as "as_of" | "period";
                if (config.dateModes.includes(nextMode)) setDateMode(nextMode);
              }}
            >
              <TabsList className="w-full">
                <TabsTrigger
                  value="period"
                  disabled={!config.dateModes.includes("period")}
                >
                  {t.from} / {t.to}
                </TabsTrigger>
                <TabsTrigger
                  value="as_of"
                  disabled={!config.dateModes.includes("as_of")}
                >
                  {t.asOfDate}
                </TabsTrigger>
              </TabsList>
            </Tabs>

            {dateMode === "as_of" ? (
              <Field>
                <FieldLabel>{t.asOfDate}</FieldLabel>
                <Input type="date" value={asOfDate} onChange={(event) => setAsOfDate(event.target.value)} />
              </Field>
            ) : (
              <div className="grid grid-cols-2 gap-3">
                <Field>
                  <FieldLabel>{t.from}</FieldLabel>
                  <Input type="date" value={dateFrom} onChange={(event) => setDateFrom(event.target.value)} />
                </Field>
                <Field>
                  <FieldLabel>{t.to}</FieldLabel>
                  <Input type="date" value={dateTo} onChange={(event) => setDateTo(event.target.value)} />
                </Field>
              </div>
            )}
          </div>

          <div className="space-y-2 rounded-md border bg-muted/20 p-3">
            <FieldLabel>{t.rowDimensions}</FieldLabel>
            <Select
              value={rowDimension}
              onValueChange={(value) => setRowDimension(value as ReportDimension)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {config.dimensions.map((dimension) => (
                  <SelectItem key={dimension} value={dimension}>
                    {dimensionLabel(dimension)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2 rounded-md border bg-muted/20 p-3 opacity-75">
            <FieldLabel>{t.columnDimensions}</FieldLabel>
            <p className="text-xs text-muted-foreground">
              {t.pivotComingLater}
            </p>
          </div>

          <div className="space-y-2 rounded-md border bg-muted/20 p-3">
            <FieldLabel>{t.measures}</FieldLabel>
            <div className="grid grid-cols-2 gap-2">
              {config.measures.map((measure) => (
                <label key={measure} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={measures.includes(measure)}
                    onChange={() => toggle(measure, measures, setMeasures)}
                  />
                  {measureLabel(measure)}
                </label>
              ))}
            </div>
          </div>

          <div className="space-y-3 rounded-md border bg-muted/20 p-3">
            <FieldLabel>{t.filters}</FieldLabel>
            {config.dimensions.includes("product") && (
              <OptionSelect label={t.product} value={productId} allLabel={t.allProducts} options={products} onValueChange={setProductId} />
            )}
            {config.dimensions.includes("category") && (
              <OptionSelect label={t.productCategory} value={categoryId} allLabel={t.allCategories} options={categories} onValueChange={setCategoryId} />
            )}
            {config.dimensions.includes("warehouse") && (
              <OptionSelect label={t.warehouse} value={warehouseId} allLabel={t.allWarehouses} options={warehouses} onValueChange={setWarehouseId} />
            )}
            {config.dimensions.includes("store") && (
              <OptionSelect label={t.productStore} value={storeId} allLabel={t.allStores} options={stores} onValueChange={setStoreId} />
            )}
            {config.dimensions.includes("supplier") && (
              <OptionSelect label={t.supplier} value={supplierId} allLabel={t.allSuppliers} options={suppliers} onValueChange={setSupplierId} />
            )}
            {config.dimensions.includes("quality") && (
              <Field>
                <FieldLabel>{t.qualityStatus}</FieldLabel>
                <Select
                  value={qualityStatus || "all"}
                  onValueChange={(next) => setQualityStatus(next === "all" ? "" : next)}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.allQualityStatuses} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="all">{t.allQualityStatuses}</SelectItem>
                    <SelectItem value="ordinary">{t.ordinary}</SelectItem>
                    <SelectItem value="defect">{t.defect}</SelectItem>
                  </SelectContent>
                </Select>
              </Field>
            )}
          </div>
        </aside>

        <section className="min-w-0 space-y-4">
          <div className="rounded-lg border bg-card">
            <div className="border-b p-4">
              <div className="mb-3 flex flex-wrap items-center gap-2">
                <Badge variant="secondary">{String(t[config.labelKey])}</Badge>
                <Badge variant="outline">
                  {dateMode === "as_of"
                    ? `${t.asOfDate}: ${asOfDate}`
                    : `${dateFrom} - ${dateTo}`}
                </Badge>
                {previewGroupBy && (
                  <Badge variant="outline">groupBy: {previewGroupBy}</Badge>
                )}
                {activeFilterCount > 0 && (
                  <Badge variant="outline">
                    {t.filters}: {activeFilterCount}
                  </Badge>
                )}
                {previewLoading && (
                  <span className="text-sm text-muted-foreground">
                    {t.loading}
                  </span>
                )}
              </div>
              <div className="text-sm text-muted-foreground">
                {dimensionLabel(rowDimension)}
                {" · "}
                {measures.map((measure) => measureLabel(measure as ReportMeasure)).join(", ")}
              </div>
            </div>
            <div className="p-4">
              {previewError && (
                <div className="rounded-md border bg-muted/40 p-6 text-sm text-muted-foreground">
                  {previewError}
                </div>
              )}
              {!previewError && tableRows.length === 0 && (
                <div className="rounded-md border bg-muted/40 p-10 text-center text-sm text-muted-foreground">
                  {t.noPreviewData}
                </div>
              )}
              {!previewError && tableRows.length > 0 && (
                <div className="overflow-x-auto rounded-md border">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        {columns.map((column) => (
                          <TableHead key={column.key}>{column.label}</TableHead>
                        ))}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {tableRows.slice(0, 25).map((row, index) => (
                        <TableRow key={index}>
                          {columns.map((column) => (
                            <TableCell
                              key={column.key}
                              className={column.type === "text" ? "" : "text-right tabular-nums"}
                            >
                              {formatPreviewValue(row[column.key], column.type)}
                            </TableCell>
                          ))}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </div>
          </div>

          {preview?.totals && (
            <div className="rounded-lg border bg-card p-4">
              <div className="mb-2 text-sm font-medium">{t.total}</div>
              <div className="flex flex-wrap gap-2">
                {Object.entries(preview.totals).map(([key, value]) => (
                  <Badge key={key} variant="outline">
                    {sourceColumnLabels[key] || key}:{" "}
                    {formatPreviewValue(value, previewColumnType(key))}
                  </Badge>
                ))}
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
