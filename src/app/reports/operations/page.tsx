"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import DataTable from "@/components/DataTable";
import Pagination from "@/components/Pagination";
import ReportFilterBar from "@/components/ReportFilterBar";
import { FieldLabel } from "@/components/ui/field";
import type { OperationType } from "@/types/inventory";

interface OperationListItem {
  id: string;
  type: OperationType;
  operationDate: string;
  comment: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  itemsSummary: { productName: string; warehouseName: string; quantity: number }[];
  [key: string]: unknown;
}

interface SelectOption {
  id: string;
  name: string;
}

const TYPE_COLORS: Record<OperationType, string> = {
  purchase: "bg-green-100 text-green-800",
  sale: "bg-blue-100 text-blue-800",
  return: "bg-yellow-100 text-yellow-800",
  write_off: "bg-red-100 text-red-800",
  transfer: "bg-purple-100 text-purple-800",
  production: "bg-indigo-100 text-indigo-800",
  defect: "bg-orange-100 text-orange-800",
  payment: "bg-teal-100 text-teal-800",
};

const PAGE_SIZE = 30;

export default function OperationsLogPage() {
  const { t, locale } = useI18n();
  const [items, setItems] = useState<OperationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [total, setTotal] = useState<number | null>(null);
  const [offset, setOffset] = useState(0);

  // Filters
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [filterType, setFilterType] = useState("");
  const [filterProductId, setFilterProductId] = useState("");
  const [filterWarehouseId, setFilterWarehouseId] = useState("");
  const [filterSupplierId, setFilterSupplierId] = useState("");

  // Select options
  const [products, setProducts] = useState<SelectOption[]>([]);
  const [warehouses, setWarehouses] = useState<SelectOption[]>([]);
  const [suppliers, setSuppliers] = useState<SelectOption[]>([]);

  useEffect(() => {
    Promise.all([
      fetch("/api/products?limit=500").then((r) => r.json()),
      fetch("/api/warehouses").then((r) => r.json()),
      fetch("/api/suppliers").then((r) => r.json()),
    ]).then(([prodData, whData, supData]) => {
      setProducts((prodData.items || []).map((p: { id: string; name: string }) => ({ id: p.id, name: p.name })));
      setWarehouses((whData.items || []).map((w: { id: string; name: string }) => ({ id: w.id, name: w.name })));
      setSuppliers((supData.items || []).map((s: { id: string; name: string }) => ({ id: s.id, name: s.name })));
    });
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
      };
      return map[type];
    },
    [t]
  );

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ limit: String(PAGE_SIZE), offset: String(offset) });
      if (filterType) params.set("type", filterType);
      if (filterProductId) params.set("productId", filterProductId);
      if (filterWarehouseId) params.set("warehouseId", filterWarehouseId);
      if (filterSupplierId) params.set("supplierId", filterSupplierId);
      if (dateFrom) params.set("from", dateFrom);
      if (dateTo) params.set("to", dateTo);
      const res = await fetch(`/api/operations?${params}`);
      const data = await res.json();
      setItems(data.items || []);
      setTotal(data.page?.totalEstimate ?? null);
    } finally {
      setLoading(false);
    }
  }, [offset, filterType, filterProductId, filterWarehouseId, filterSupplierId, dateFrom, dateTo]);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  // Reset offset when filters change
  useEffect(() => {
    setOffset(0);
  }, [filterType, filterProductId, filterWarehouseId, filterSupplierId, dateFrom, dateTo]);

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString(
      locale === "ru" ? "ru-RU" : "en-US",
      { year: "numeric", month: "short", day: "numeric" }
    );
  };

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-6">{t.operationsLogTitle}</h1>

      <ReportFilterBar>
        <div className="space-y-1">
          <FieldLabel>{t.from}</FieldLabel>
          <Input
            type="date"
            value={dateFrom}
            onChange={(e) => setDateFrom(e.target.value)}
            className="w-40"
          />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.to}</FieldLabel>
          <Input
            type="date"
            value={dateTo}
            onChange={(e) => setDateTo(e.target.value)}
            className="w-40"
          />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.operationType}</FieldLabel>
          <Select value={filterType} onValueChange={(v) => setFilterType(v === "all" ? "" : v)}>
            <SelectTrigger className="w-40">
              <SelectValue placeholder={t.allTypes} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allTypes}</SelectItem>
              <SelectItem value="purchase">{t.opPurchase}</SelectItem>
              <SelectItem value="sale">{t.opSale}</SelectItem>
              <SelectItem value="return">{t.opReturn}</SelectItem>
              <SelectItem value="write_off">{t.opWriteOff}</SelectItem>
              <SelectItem value="transfer">{t.opTransfer}</SelectItem>
              <SelectItem value="production">{t.opProduction}</SelectItem>
              <SelectItem value="defect">{t.opDefect}</SelectItem>
              <SelectItem value="payment">{t.opPayment}</SelectItem>
            </SelectContent>
          </Select>
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
        <div className="space-y-1">
          <FieldLabel>{t.supplier}</FieldLabel>
          <Select value={filterSupplierId} onValueChange={(v) => setFilterSupplierId(v === "all" ? "" : v)}>
            <SelectTrigger className="w-44">
              <SelectValue placeholder={t.allSuppliers} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allSuppliers}</SelectItem>
              {suppliers.map((s) => (
                <SelectItem key={s.id} value={s.id}>{s.name}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </ReportFilterBar>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <>
          <DataTable<OperationListItem>
            columns={[
              {
                key: "operationDate",
                header: t.operationDate,
                render: (item) => formatDate(item.operationDate),
              },
              {
                key: "type",
                header: t.operationType,
                render: (item) => (
                  <Badge className={TYPE_COLORS[item.type]} variant="secondary">
                    {typeLabel(item.type)}
                  </Badge>
                ),
              },
              {
                key: "products",
                header: t.product,
                render: (item) => {
                  if (item.type === "payment") return "-";
                  return item.itemsSummary
                    .map((s) => `${s.productName} (${s.quantity})`)
                    .join(", ");
                },
              },
              {
                key: "warehouse",
                header: t.warehouse,
                render: (item) => {
                  if (item.type === "payment") return "-";
                  const whs = [...new Set(item.itemsSummary.map((s) => s.warehouseName))];
                  return whs.join(", ");
                },
              },
              {
                key: "supplier",
                header: t.supplier,
                render: (item) => item.supplierName || "-",
              },
              {
                key: "amount",
                header: t.amount,
                className: "text-right",
                render: (item) =>
                  item.paymentAmount != null
                    ? item.paymentAmount.toLocaleString()
                    : "-",
              },
              {
                key: "comment",
                header: t.comment,
                render: (item) => item.comment || "-",
              },
            ]}
            data={items}
            emptyMessage={t.noOperationsLogData}
          />
          <Pagination
            offset={offset}
            limit={PAGE_SIZE}
            total={total}
            onPageChange={setOffset}
          />
        </>
      )}
    </div>
  );
}
