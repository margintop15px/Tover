"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import DataTable from "@/components/DataTable";
import type { OperationType } from "@/types/inventory";
import { Plus } from "lucide-react";

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

export default function OperationsPage() {
  const { t, locale } = useI18n();
  const [items, setItems] = useState<OperationListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [filterType, setFilterType] = useState("");

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
      const params = new URLSearchParams({ limit: "100" });
      if (filterType) params.set("type", filterType);
      const res = await fetch(`/api/operations?${params}`);
      const data = await res.json();
      setItems(data.items || []);
    } finally {
      setLoading(false);
    }
  }, [filterType]);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString(
      locale === "ru" ? "ru-RU" : "en-US",
      { year: "numeric", month: "short", day: "numeric" }
    );
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t.operationsTitle}</h1>
        <Link href="/operations/new">
          <Button className="gap-2">
            <Plus className="h-4 w-4" />
            {t.newOperation}
          </Button>
        </Link>
      </div>

      {/* Filters */}
      <div className="mb-4 flex items-center gap-3">
        <Select
          value={filterType}
          onValueChange={(v) => setFilterType(v === "all" ? "" : v)}
        >
          <SelectTrigger className="w-48">
            <SelectValue placeholder={t.selectType} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t.selectType}</SelectItem>
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

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
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
                <Badge
                  className={TYPE_COLORS[item.type]}
                  variant="secondary"
                >
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
                  .map(
                    (s) =>
                      `${s.productName} (${s.quantity})`
                  )
                  .join(", ");
              },
            },
            {
              key: "warehouse",
              header: t.warehouse,
              render: (item) => {
                if (item.type === "payment") return "-";
                const warehouses = [
                  ...new Set(item.itemsSummary.map((s) => s.warehouseName)),
                ];
                return warehouses.join(", ");
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
          emptyMessage={t.noOperations}
        />
      )}
    </div>
  );
}
