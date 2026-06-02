"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
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
import ReportPreviewTable, {
  useReportPresentation,
} from "@/components/reports/ReportPreviewTable";
import { useI18n } from "@/i18n/context";
import {
  buildReportUrl,
  getDefaultReportDates,
  getTemplateRowDimension,
  normalizeReportFilters,
} from "@/lib/reports/report-runner";
import {
  REPORT_SOURCE_CONFIGS,
  REPORT_SOURCES,
  type ReportDimension,
  type ReportMeasure,
} from "@/lib/reports/report-constructor";
import type { ReportTemplate, ReportTemplateSource } from "@/types/inventory";
import type { PreviewReport } from "@/lib/reports/report-display";

interface SelectOption {
  id: string;
  name: string;
}

const defaultDates = getDefaultReportDates();

function filterRecord(filters: Record<string, string>) {
  return Object.fromEntries(
    Object.entries(filters).filter(([, value]) => Boolean(value))
  );
}

export default function ReportTemplateForm({
  templateId,
}: {
  templateId?: string;
}) {
  const { t } = useI18n();
  const router = useRouter();
  const isEditing = Boolean(templateId);
  const [initialLoading, setInitialLoading] = useState(Boolean(templateId));
  const [name, setName] = useState("");
  const [source, setSource] =
    useState<ReportTemplateSource>("inventory_balances");
  const config = REPORT_SOURCE_CONFIGS[source];
  const supportedRowDimensions = config.previewGroupBy;
  const [dateMode, setDateMode] = useState<"as_of" | "period">(
    config.defaultDateMode
  );
  const [asOfDate, setAsOfDate] = useState(defaultDates.asOfDate || "");
  const [dateFrom, setDateFrom] = useState(defaultDates.dateFrom || "");
  const [dateTo, setDateTo] = useState(defaultDates.dateTo || "");
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

  const { dimensionLabel, measureLabel } = useReportPresentation(
    source,
    rowDimension,
    measures,
    preview
  );

  const applyTemplate = useCallback((template: ReportTemplate) => {
    const nextConfig = REPORT_SOURCE_CONFIGS[template.source];
    const filters = normalizeReportFilters(template.filters);
    const nextRowDimension = getTemplateRowDimension(
      template.source,
      template.rowDimensions
    );

    setName(template.name);
    setSource(template.source);
    setDateMode(
      nextConfig.dateModes.includes(template.dateMode)
        ? template.dateMode
        : nextConfig.defaultDateMode
    );
    setRowDimension(nextRowDimension);
    setMeasures(
      template.measures.filter((measure) =>
        nextConfig.measures.includes(measure as ReportMeasure)
      )
    );
    setAsOfDate(filters.asOfDate || defaultDates.asOfDate || "");
    setDateFrom(filters.dateFrom || defaultDates.dateFrom || "");
    setDateTo(filters.dateTo || defaultDates.dateTo || "");
    setProductId(filters.productId || "");
    setCategoryId(filters.categoryId || "");
    setWarehouseId(filters.warehouseId || "");
    setStoreId(filters.storeId || "");
    setSupplierId(filters.supplierId || "");
    setQualityStatus(filters.qualityStatus || "");
  }, []);

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

  useEffect(() => {
    if (!templateId) return;

    let cancelled = false;
    setInitialLoading(true);
    fetch(`/api/report-templates/${templateId}`)
      .then(async (response) => {
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || t.unexpectedError);
        if (!cancelled) applyTemplate(data);
      })
      .catch((error) => {
        if (!cancelled) setPreviewError(error instanceof Error ? error.message : String(error));
      })
      .finally(() => {
        if (!cancelled) setInitialLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [applyTemplate, t.unexpectedError, templateId]);

  const resetForSource = (nextSource: ReportTemplateSource) => {
    const nextConfig = REPORT_SOURCE_CONFIGS[nextSource];
    setSource(nextSource);
    setDateMode(nextConfig.defaultDateMode);
    setRowDimension(nextConfig.defaultDimensions[0]);
    setMeasures(nextConfig.defaultMeasures);
    setProductId("");
    setCategoryId("");
    setWarehouseId("");
    setStoreId("");
    setSupplierId("");
    setQualityStatus("");
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

  const dataFilters = useMemo(
    () =>
      filterRecord({
        productId,
        categoryId,
        warehouseId,
        storeId,
        supplierId,
        qualityStatus,
      }),
    [categoryId, productId, qualityStatus, storeId, supplierId, warehouseId]
  );

  const templateFilters = useMemo(
    () =>
      filterRecord({
        ...dataFilters,
        asOfDate,
        dateFrom,
        dateTo,
      }),
    [asOfDate, dataFilters, dateFrom, dateTo]
  );

  const missingPreviewReason = useMemo(() => {
    if (measures.length === 0) return t.selectAtLeastOneMeasure;
    if (!supportedRowDimensions.includes(rowDimension)) {
      return t.previewUnsupportedDimensions;
    }
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
    rowDimension,
    supportedRowDimensions,
    t,
  ]);

  const buildPreviewUrl = useCallback(
    () =>
      buildReportUrl(source, dateMode, [rowDimension], templateFilters),
    [dateMode, rowDimension, source, templateFilters]
  );

  useEffect(() => {
    if (initialLoading) return;
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
        if (!response.ok) throw new Error(data.error || t.unexpectedError);
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
  }, [buildPreviewUrl, initialLoading, missingPreviewReason, t.unexpectedError]);

  const saveTemplate = async () => {
    if (!name.trim() || missingPreviewReason) return;
    setSaving(true);
    try {
      const response = await fetch(
        templateId ? `/api/report-templates/${templateId}` : "/api/report-templates",
        {
          method: templateId ? "PATCH" : "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            name: name.trim(),
            source,
            dateMode,
            rowDimensions: [rowDimension],
            columnDimensions: [],
            measures,
            filters: templateFilters,
          }),
        }
      );
      const data = await response.json().catch(() => ({}));
      if (response.ok) {
        router.push(templateId ? `/reports/templates/${templateId}` : "/reports/templates");
      } else {
        setPreviewError(data.error || t.unexpectedError);
      }
    } finally {
      setSaving(false);
    }
  };

  const activeFilterCount = Object.values(dataFilters).filter(Boolean).length;

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

  if (initialLoading) {
    return <div className="p-6 text-muted-foreground">{t.loading}</div>;
  }

  return (
    <div className="p-6">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">
            {isEditing ? t.editReport : t.createReport}
          </h1>
          <p className="text-sm text-muted-foreground">{t.reportPreview}</p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" asChild>
            <Link href={templateId ? `/reports/templates/${templateId}` : "/reports/templates"}>
              {t.cancel}
            </Link>
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
                {supportedRowDimensions.map((dimension) => (
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
                <Badge variant="outline">groupBy: {rowDimension}</Badge>
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
              <ReportPreviewTable
                source={source}
                rowDimension={rowDimension}
                measures={measures}
                report={preview}
                error={previewError}
              />
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
