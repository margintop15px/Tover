"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { formatCurrency } from "@/lib/format-currency";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import DataTable from "@/components/DataTable";
import Pagination from "@/components/Pagination";
import type { OperationDirection, OperationType } from "@/types/inventory";
import {
  ArrowDown,
  ArrowUp,
  ArrowUpDown,
  ClipboardCheck,
  Eye,
  Filter,
  Pencil,
  Plus,
  Upload,
  X,
} from "lucide-react";

interface OperationListItem {
  id: string;
  operationId: string;
  itemId: string | null;
  type: OperationType;
  operationDate: string;
  comment: string | null;
  supplierId: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  productId: string | null;
  productName: string | null;
  warehouseId: string | null;
  warehouseName: string | null;
  quantity: number | null;
  unitPrice: number | null;
  direction: OperationDirection | null;
  [key: string]: unknown;
}

interface SelectOption {
  id: string;
  name: string;
}

interface FilterOption {
  value: string;
  label: string;
}

interface OperationDetails {
  id: string;
  type: OperationType;
  operationDate: string;
  comment: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  createdAt: string;
  items: {
    id: string;
    productName: string;
    warehouseName: string;
    quantity: number;
    unitPrice: number | null;
    direction: OperationDirection;
    storeName: string | null;
  }[];
}

interface OzonCandidateSummaryResponse {
  summary?: {
    needsMapping?: number;
    ready?: number;
    approved?: number;
  };
}

type SortBy =
  | "operationDate"
  | "type"
  | "product"
  | "warehouse"
  | "supplier"
  | "quantity"
  | "unitPrice"
  | "paymentAmount";

type SortDir = "asc" | "desc";

const TYPE_COLORS: Record<OperationType, string> = {
  purchase: "bg-green-100 text-green-800",
  sale: "bg-blue-100 text-blue-800",
  return: "bg-yellow-100 text-yellow-800",
  write_off: "bg-red-100 text-red-800",
  transfer: "bg-purple-100 text-purple-800",
  production: "bg-indigo-100 text-indigo-800",
  defect: "bg-orange-100 text-orange-800",
  payment: "bg-teal-100 text-teal-800",
  inventory_adjustment: "bg-violet-100 text-violet-800",
};

const PAGE_SIZE = 30;

function isSortByValue(value: string | null): value is SortBy {
  return (
    value === "operationDate" ||
    value === "type" ||
    value === "product" ||
    value === "warehouse" ||
    value === "supplier" ||
    value === "quantity" ||
    value === "unitPrice" ||
    value === "paymentAmount"
  );
}

function isSortDirValue(value: string | null): value is SortDir {
  return value === "asc" || value === "desc";
}

function parseOffset(value: string | null) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
}

export default function OperationsPage() {
  return (
    <Suspense fallback={<div className="p-6 text-muted-foreground">Loading...</div>}>
      <OperationsPageContent />
    </Suspense>
  );
}

function OperationsPageContent() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
  const router = useRouter();
  const searchParams = useSearchParams();
  const [items, setItems] = useState<OperationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [total, setTotal] = useState<number | null>(null);
  const [offset, setOffset] = useState(() =>
    parseOffset(searchParams.get("offset"))
  );
  const [detailsOpen, setDetailsOpen] = useState(false);
  const [detailsLoading, setDetailsLoading] = useState(false);
  const [details, setDetails] = useState<OperationDetails | null>(null);

  const [dateFrom, setDateFrom] = useState(() => searchParams.get("from") || "");
  const [dateTo, setDateTo] = useState(() => searchParams.get("to") || "");
  const [filterType, setFilterType] = useState(
    () => searchParams.get("type") || ""
  );
  const [filterProductId, setFilterProductId] = useState(
    () => searchParams.get("productId") || ""
  );
  const [filterWarehouseId, setFilterWarehouseId] = useState(
    () => searchParams.get("warehouseId") || ""
  );
  const [filterSupplierId, setFilterSupplierId] = useState(
    () => searchParams.get("supplierId") || ""
  );
  const [filterImportId, setFilterImportId] = useState(
    () => searchParams.get("importId") || ""
  );
  const [sortBy, setSortBy] = useState<SortBy | "">(() => {
    const value = searchParams.get("sortBy");
    return isSortByValue(value) ? value : "";
  });
  const [sortDir, setSortDir] = useState<SortDir>(() => {
    const value = searchParams.get("sortDir");
    return isSortDirValue(value) ? value : "desc";
  });

  const [products, setProducts] = useState<SelectOption[]>([]);
  const [warehouses, setWarehouses] = useState<SelectOption[]>([]);
  const [suppliers, setSuppliers] = useState<SelectOption[]>([]);
  const [pendingOzonCandidates, setPendingOzonCandidates] = useState(0);

  useEffect(() => {
    Promise.all([
      fetch("/api/products?limit=500").then((r) => r.json()),
      fetch("/api/warehouses").then((r) => r.json()),
      fetch("/api/suppliers").then((r) => r.json()),
    ]).then(([prodData, whData, supData]) => {
      setProducts(
        (prodData.items || []).map((p: { id: string; name: string }) => ({
          id: p.id,
          name: p.name,
        }))
      );
      setWarehouses(
        (whData.items || []).map((w: { id: string; name: string }) => ({
          id: w.id,
          name: w.name,
        }))
      );
      setSuppliers(
        (supData.items || []).map((s: { id: string; name: string }) => ({
          id: s.id,
          name: s.name,
        }))
      );
    });
  }, []);

  useEffect(() => {
    let cancelled = false;

    fetch("/api/integrations/ozon/candidates?limit=1", { cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) return null;
        return (await response.json()) as OzonCandidateSummaryResponse;
      })
      .then((data) => {
        if (cancelled || !data?.summary) return;
        setPendingOzonCandidates(
          (data.summary.needsMapping || 0) +
            (data.summary.ready || 0) +
            (data.summary.approved || 0)
        );
      })
      .catch(() => {
        if (!cancelled) setPendingOzonCandidates(0);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const typeLabel = useCallback(
    (type: OperationType): string => {
      const map: Record<OperationType, string> = {
        purchase: t.opPurchase,
        sale: t.opSale,
        return: t.opReturn,
        write_off: t.opWriteOff,
        transfer: t.opTransfer,
        production: t.opProduction,
        defect: t.opDefect,
        payment: t.opPayment,
        inventory_adjustment: t.opInventoryAdjustment,
      };
      return map[type];
    },
    [t]
  );

  const operationTypeOptions: FilterOption[] = [
    { value: "purchase", label: t.opPurchase },
    { value: "sale", label: t.opSale },
    { value: "return", label: t.opReturn },
    { value: "write_off", label: t.opWriteOff },
    { value: "transfer", label: t.opTransfer },
    { value: "production", label: t.opProduction },
    { value: "defect", label: t.opDefect },
    { value: "payment", label: t.opPayment },
  ];

  const toFilterOptions = (options: SelectOption[]): FilterOption[] =>
    options.map((option) => ({ value: option.id, label: option.name }));

  const getSelectedOption = (options: FilterOption[], value: string) =>
    options.find((option) => option.value === value) ?? null;

  const FilterCombobox = ({
    value,
    options,
    allLabel,
    searchPlaceholder,
    onValueChange,
  }: {
    value: string;
    options: FilterOption[];
    allLabel: string;
    searchPlaceholder: string;
    onValueChange: (value: string) => void;
  }) => {
    const items = [{ value: "", label: allLabel }, ...options];

    return (
      <Combobox<FilterOption>
        items={items}
        value={getSelectedOption(items, value)}
        itemToStringValue={(item) => item.label}
        onValueChange={(item) => onValueChange(item.value)}
      >
        <ComboboxInput placeholder={searchPlaceholder} />
        <ComboboxContent>
          <ComboboxEmpty>{t.noData}</ComboboxEmpty>
          <ComboboxList<FilterOption>>
            {(item) => (
              <ComboboxItem<FilterOption>
                key={item.value || "__all"}
                value={item}
              >
                {item.label}
              </ComboboxItem>
            )}
          </ComboboxList>
        </ComboboxContent>
      </Combobox>
    );
  };

  const setFilter = (setter: (value: string) => void, value: string) => {
    setter(value);
    setOffset(0);
  };

  const toggleSort = (key: SortBy) => {
    setSortBy((current) => {
      if (current === key) {
        setSortDir((dir) => (dir === "asc" ? "desc" : "asc"));
        return current;
      }
      setSortDir(key === "operationDate" ? "desc" : "asc");
      return key;
    });
    setOffset(0);
  };

  const clearFilters = () => {
    setDateFrom("");
    setDateTo("");
    setFilterType("");
    setFilterProductId("");
    setFilterWarehouseId("");
    setFilterSupplierId("");
    setFilterImportId("");
    setSortBy("");
    setSortDir("desc");
    setOffset(0);
    router.replace("/operations");
  };

  const getReturnHref = useCallback(() => {
    const params = new URLSearchParams();

    if (filterType) params.set("type", filterType);
    if (filterProductId) params.set("productId", filterProductId);
    if (filterWarehouseId) params.set("warehouseId", filterWarehouseId);
    if (filterSupplierId) params.set("supplierId", filterSupplierId);
    if (filterImportId) params.set("importId", filterImportId);
    if (dateFrom) params.set("from", dateFrom);
    if (dateTo) params.set("to", dateTo);
    if (sortBy) {
      params.set("sortBy", sortBy);
      params.set("sortDir", sortDir);
    }
    if (offset > 0) params.set("offset", String(offset));

    const query = params.toString();
    return query ? `/operations?${query}` : "/operations";
  }, [
    dateFrom,
    dateTo,
    filterProductId,
    filterImportId,
    filterSupplierId,
    filterType,
    filterWarehouseId,
    offset,
    sortBy,
    sortDir,
  ]);

  const openDetails = async (operationId: string) => {
    setDetailsOpen(true);
    setDetailsLoading(true);
    setDetails(null);
    try {
      const res = await fetch(`/api/operations/${operationId}`);
      if (!res.ok) throw new Error("Failed to load operation");
      setDetails(await res.json());
    } finally {
      setDetailsLoading(false);
    }
  };

  const editOperation = (operationId: string) => {
    router.push(
      `/operations/${operationId}/edit?returnTo=${encodeURIComponent(
        getReturnHref()
      )}`
    );
  };

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({
        limit: String(PAGE_SIZE),
        offset: String(offset),
      });
      if (filterType) params.set("type", filterType);
      if (filterProductId) params.set("productId", filterProductId);
      if (filterWarehouseId) params.set("warehouseId", filterWarehouseId);
      if (filterSupplierId) params.set("supplierId", filterSupplierId);
      if (filterImportId) params.set("importId", filterImportId);
      if (dateFrom) params.set("from", dateFrom);
      if (dateTo) params.set("to", dateTo);
      if (sortBy) {
        params.set("sortBy", sortBy);
        params.set("sortDir", sortDir);
      }

      const res = await fetch(`/api/operations?${params}`);
      const data = await res.json();
      setItems(data.items || []);
      setTotal(data.page?.totalEstimate ?? null);
    } finally {
      setLoading(false);
    }
  }, [
    offset,
    filterType,
    filterProductId,
    filterImportId,
    filterWarehouseId,
    filterSupplierId,
    dateFrom,
    dateTo,
    sortBy,
    sortDir,
  ]);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString(
      locale === "ru" ? "ru-RU" : "en-US",
      { year: "numeric", month: "short", day: "numeric" }
    );
  };

  const formatNumber = (value: number | null) => {
    if (value == null) return "-";
    return value.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", {
      maximumFractionDigits: 3,
    });
  };

  const formatMoney = (value: number | null) => {
    if (value == null) return "-";
    return formatCurrency(value, locale, settings.currency);
  };

  const SortIcon = ({ sortKey }: { sortKey: SortBy }) => {
    if (sortBy !== sortKey) return <ArrowUpDown className="h-3.5 w-3.5" />;
    return sortDir === "asc" ? (
      <ArrowUp className="h-3.5 w-3.5" />
    ) : (
      <ArrowDown className="h-3.5 w-3.5" />
    );
  };

  const SortableHeader = ({
    label,
    sortKey,
    children,
    activeFilter,
  }: {
    label: string;
    sortKey: SortBy;
    children?: React.ReactNode;
    activeFilter?: boolean;
  }) => (
    <div className="flex items-center gap-1 py-1">
      <span className="whitespace-nowrap">{label}</span>
      <div className="flex items-center">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-6 w-6 p-0"
          aria-label={`Sort ${label}`}
          onClick={() => toggleSort(sortKey)}
        >
          <SortIcon sortKey={sortKey} />
        </Button>
        {children && (
          <Popover>
            <PopoverTrigger asChild>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                className="h-6 w-6 p-0"
                aria-label={`Filter ${label}`}
              >
                <Filter
                  className={
                    activeFilter
                      ? "h-3.5 w-3.5 text-foreground"
                      : "h-3.5 w-3.5 text-muted-foreground"
                  }
                />
              </Button>
            </PopoverTrigger>
            <PopoverContent
              align="start"
              className="w-56 space-y-2 p-3"
            >
              {children}
            </PopoverContent>
          </Popover>
        )}
      </div>
    </div>
  );

  const hasActiveControls =
    Boolean(dateFrom) ||
    Boolean(dateTo) ||
    Boolean(filterType) ||
    Boolean(filterProductId) ||
    Boolean(filterWarehouseId) ||
    Boolean(filterSupplierId) ||
    Boolean(filterImportId) ||
    Boolean(sortBy);

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between gap-4">
        <h1 className="text-2xl font-bold">{t.operationsTitle}</h1>
        <div className="flex flex-wrap items-center justify-end gap-2">
          {pendingOzonCandidates > 0 && (
            <Link
              href={`/operations/marketplace/ozon?returnTo=${encodeURIComponent(
                getReturnHref()
              )}`}
            >
              <Button variant="outline" className="gap-2">
                <ClipboardCheck className="h-4 w-4" />
                {t.ozonReviewCandidates}
                <Badge variant="secondary">{pendingOzonCandidates}</Badge>
              </Button>
            </Link>
          )}
          <Link href="/operations/import">
            <Button variant="outline" className="gap-2">
              <Upload className="h-4 w-4" />
              {t.importOperations}
            </Button>
          </Link>
          <Link href="/operations/new">
            <Button className="gap-2">
              <Plus className="h-4 w-4" />
              {t.newOperation}
            </Button>
          </Link>
        </div>
      </div>

      {filterImportId && (
        <div className="mb-4 flex items-center justify-between rounded-md border bg-muted/40 px-3 py-2 text-sm">
          <span className="text-muted-foreground">
            {t.showingImportedOperations}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => {
              setFilterImportId("");
              setOffset(0);
              router.replace("/operations");
            }}
          >
            <X className="h-4 w-4" />
            {t.clearFilters}
          </Button>
        </div>
      )}

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <>
          <DataTable<OperationListItem>
            tableId="operations-unified"
            toolbarActions={
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="gap-2"
                disabled={!hasActiveControls}
                onClick={clearFilters}
              >
                <X className="h-4 w-4" />
                {t.clearFilters}
              </Button>
            }
            columns={[
              {
                key: "operationDate",
                headerLabel: t.operationDate,
                header: (
                  <SortableHeader
                    label={t.operationDate}
                    sortKey="operationDate"
                    activeFilter={Boolean(dateFrom) || Boolean(dateTo)}
                  >
                    <div className="flex flex-col gap-2">
                      <Input
                        type="date"
                        value={dateFrom}
                        onChange={(e) => setFilter(setDateFrom, e.target.value)}
                        className="h-8 text-xs"
                        aria-label={`${t.from} ${t.operationDate}`}
                      />
                      <Input
                        type="date"
                        value={dateTo}
                        onChange={(e) => setFilter(setDateTo, e.target.value)}
                        className="h-8 text-xs"
                        aria-label={`${t.to} ${t.operationDate}`}
                      />
                    </div>
                  </SortableHeader>
                ),
                required: true,
                render: (item) => formatDate(item.operationDate),
              },
              {
                key: "type",
                headerLabel: t.operationType,
                header: (
                  <SortableHeader
                    label={t.operationType}
                    sortKey="type"
                    activeFilter={Boolean(filterType)}
                  >
                    <FilterCombobox
                      value={filterType}
                      options={operationTypeOptions}
                      allLabel={t.allTypes}
                      onValueChange={(value) =>
                        setFilter(setFilterType, value)
                      }
                      searchPlaceholder={t.operationType}
                    />
                  </SortableHeader>
                ),
                required: true,
                render: (item) => (
                  <Badge className={TYPE_COLORS[item.type]} variant="secondary">
                    {typeLabel(item.type)}
                  </Badge>
                ),
              },
              {
                key: "product",
                headerLabel: t.product,
                header: (
                  <SortableHeader
                    label={t.product}
                    sortKey="product"
                    activeFilter={Boolean(filterProductId)}
                  >
                    <FilterCombobox
                      value={filterProductId}
                      options={toFilterOptions(products)}
                      allLabel={t.allProducts}
                      onValueChange={(value) =>
                        setFilter(setFilterProductId, value)
                      }
                      searchPlaceholder={t.product}
                    />
                  </SortableHeader>
                ),
                render: (item) => item.productName || "-",
              },
              {
                key: "quantity",
                headerLabel: t.quantity,
                header: (
                  <SortableHeader label={t.quantity} sortKey="quantity" />
                ),
                className: "text-right",
                render: (item) => formatNumber(item.quantity),
              },
              {
                key: "unitPrice",
                headerLabel: t.price,
                header: <SortableHeader label={t.price} sortKey="unitPrice" />,
                className: "text-right",
                render: (item) => formatMoney(item.unitPrice),
              },
              {
                key: "warehouse",
                headerLabel: t.warehouse,
                header: (
                  <SortableHeader
                    label={t.warehouse}
                    sortKey="warehouse"
                    activeFilter={Boolean(filterWarehouseId)}
                  >
                    <FilterCombobox
                      value={filterWarehouseId}
                      options={toFilterOptions(warehouses)}
                      allLabel={t.allWarehouses}
                      onValueChange={(value) =>
                        setFilter(setFilterWarehouseId, value)
                      }
                      searchPlaceholder={t.warehouse}
                    />
                  </SortableHeader>
                ),
                render: (item) => item.warehouseName || "-",
              },
              {
                key: "supplier",
                headerLabel: t.supplier,
                header: (
                  <SortableHeader
                    label={t.supplier}
                    sortKey="supplier"
                    activeFilter={Boolean(filterSupplierId)}
                  >
                    <FilterCombobox
                      value={filterSupplierId}
                      options={toFilterOptions(suppliers)}
                      allLabel={t.allSuppliers}
                      onValueChange={(value) =>
                        setFilter(setFilterSupplierId, value)
                      }
                      searchPlaceholder={t.supplier}
                    />
                  </SortableHeader>
                ),
                render: (item) => item.supplierName || "-",
              },
              {
                key: "direction",
                headerLabel: t.direction,
                header: t.direction,
                render: (item) => {
                  if (!item.direction) return "-";
                  return item.direction === "in" ? t.directionIn : t.directionOut;
                },
              },
              {
                key: "paymentAmount",
                headerLabel: t.paymentAmount,
                header: (
                  <SortableHeader
                    label={t.paymentAmount}
                    sortKey="paymentAmount"
                  />
                ),
                className: "text-right",
                render: (item) => formatMoney(item.paymentAmount),
              },
              {
                key: "comment",
                headerLabel: t.comment,
                header: t.comment,
                render: (item) => item.comment || "-",
              },
              {
                key: "actions",
                header: t.actions,
                className: "w-24 text-right",
                render: (item) => (
                  <div className="flex justify-end gap-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      aria-label={t.viewOperation}
                      onClick={() => openDetails(item.operationId)}
                    >
                      <Eye className="h-4 w-4" />
                    </Button>
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      aria-label={t.editOperation}
                      onClick={() => editOperation(item.operationId)}
                    >
                      <Pencil className="h-4 w-4" />
                    </Button>
                  </div>
                ),
              },
            ]}
            data={items}
            emptyMessage={t.noOperations}
          />
          <Pagination
            offset={offset}
            limit={PAGE_SIZE}
            total={total}
            onPageChange={setOffset}
          />
        </>
      )}

      <Sheet open={detailsOpen} onOpenChange={setDetailsOpen}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
          <SheetHeader>
            <SheetTitle>{t.operationDetails}</SheetTitle>
            <SheetDescription>
              {details ? (
                <>
                  {typeLabel(details.type)} · {formatDate(details.operationDate)}
                </>
              ) : (
                t.loading
              )}
            </SheetDescription>
          </SheetHeader>

          <div className="space-y-5 px-4">
            {detailsLoading ? (
              <p className="text-sm text-muted-foreground">{t.loading}</p>
            ) : details ? (
              <>
                <div className="grid grid-cols-2 gap-3 text-sm">
                  <div>
                    <div className="text-muted-foreground">{t.operationType}</div>
                    <Badge className={TYPE_COLORS[details.type]} variant="secondary">
                      {typeLabel(details.type)}
                    </Badge>
                  </div>
                  <div>
                    <div className="text-muted-foreground">{t.operationDate}</div>
                    <div>{formatDate(details.operationDate)}</div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">{t.supplier}</div>
                    <div>{details.supplierName || "-"}</div>
                  </div>
                  <div>
                    <div className="text-muted-foreground">{t.paymentAmount}</div>
                    <div>{formatMoney(details.paymentAmount)}</div>
                  </div>
                  <div className="col-span-2">
                    <div className="text-muted-foreground">{t.comment}</div>
                    <div className="whitespace-pre-wrap">{details.comment || "-"}</div>
                  </div>
                </div>

                <div>
                  <h3 className="mb-2 text-sm font-medium">{t.items}</h3>
                  {details.items.length === 0 ? (
                    <p className="text-sm text-muted-foreground">{t.noData}</p>
                  ) : (
                    <div className="rounded-md border">
                      <table className="w-full text-sm">
                        <thead className="border-b text-muted-foreground">
                          <tr>
                            <th className="px-3 py-2 text-left font-medium">{t.product}</th>
                            <th className="px-3 py-2 text-left font-medium">{t.warehouse}</th>
                            <th className="px-3 py-2 text-right font-medium">{t.quantity}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {details.items.map((item) => (
                            <tr key={item.id} className="border-b last:border-0">
                              <td className="px-3 py-2">{item.productName}</td>
                              <td className="px-3 py-2">{item.warehouseName}</td>
                              <td className="px-3 py-2 text-right">
                                {item.direction === "in" ? "+" : "-"}
                                {formatNumber(item.quantity)}
                              </td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </>
            ) : (
              <p className="text-sm text-muted-foreground">{t.noData}</p>
            )}
          </div>

          <SheetFooter>
            <Button
              type="button"
              className="gap-2"
              disabled={!details}
              onClick={() => details && editOperation(details.id)}
            >
              <Pencil className="h-4 w-4" />
              {t.edit}
            </Button>
          </SheetFooter>
        </SheetContent>
      </Sheet>
    </div>
  );
}
