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
import {
  Sheet,
  SheetContent,
  SheetTitle,
} from "@/components/ui/sheet";
import DataTable from "@/components/DataTable";
import KpiCard from "@/components/KpiCard";
import Pagination from "@/components/Pagination";
import ReportFilterBar from "@/components/ReportFilterBar";
import { FieldLabel } from "@/components/ui/field";
import type { SupplierDebtReport, SupplierDebtRow, OperationType } from "@/types/inventory";

const TYPE_COLORS: Record<string, string> = {
  purchase: "bg-green-100 text-green-800",
  payment: "bg-teal-100 text-teal-800",
};

const DEBT_TYPE_COLORS: Record<string, string> = {
  creditor: "bg-red-100 text-red-800",
  debitor: "bg-green-100 text-green-800",
  settled: "bg-gray-100 text-gray-800",
};

interface DrillDownItem {
  id: string;
  type: OperationType;
  operationDate: string;
  comment: string | null;
  paymentAmount: number | null;
  itemsSummary: { productName: string; quantity: number; unitPrice: number | null }[];
  [key: string]: unknown;
}

const DRILL_PAGE_SIZE = 20;

export default function SupplierDebtPage() {
  const { t, locale } = useI18n();
  const [report, setReport] = useState<SupplierDebtReport | null>(null);
  const [loading, setLoading] = useState(true);

  const today = new Date().toISOString().split("T")[0];
  const monthAgo = new Date(Date.now() - 30 * 86400000).toISOString().split("T")[0];

  const [asOfDate, setAsOfDate] = useState(today);
  const [periodFrom, setPeriodFrom] = useState(monthAgo);
  const [periodTo, setPeriodTo] = useState(today);
  const [debtType, setDebtType] = useState("");

  // Drill-down state
  const [drillSupplier, setDrillSupplier] = useState<SupplierDebtRow | null>(null);
  const [drillItems, setDrillItems] = useState<DrillDownItem[]>([]);
  const [drillTotal, setDrillTotal] = useState<number | null>(null);
  const [drillOffset, setDrillOffset] = useState(0);
  const [drillLoading, setDrillLoading] = useState(false);

  const fetchReport = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({
        asOfDate,
        periodFrom,
        periodTo,
      });
      if (debtType) params.set("debtType", debtType);
      const res = await fetch(`/api/reports/supplier-debt?${params}`);
      const data = await res.json();
      setReport(data);
    } finally {
      setLoading(false);
    }
  }, [asOfDate, periodFrom, periodTo, debtType]);

  useEffect(() => {
    fetchReport();
  }, [fetchReport]);

  const fetchDrillDown = useCallback(async (supplierId: string, off: number) => {
    setDrillLoading(true);
    try {
      const params = new URLSearchParams({
        limit: String(DRILL_PAGE_SIZE),
        offset: String(off),
      });
      const res = await fetch(`/api/reports/supplier-debt/${supplierId}?${params}`);
      const data = await res.json();
      setDrillItems(data.items || []);
      setDrillTotal(data.page?.totalEstimate ?? null);
    } finally {
      setDrillLoading(false);
    }
  }, []);

  const openDrillDown = (row: SupplierDebtRow) => {
    setDrillSupplier(row);
    setDrillOffset(0);
    fetchDrillDown(row.supplierId, 0);
  };

  useEffect(() => {
    if (drillSupplier) {
      fetchDrillDown(drillSupplier.supplierId, drillOffset);
    }
  }, [drillOffset, drillSupplier, fetchDrillDown]);

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleDateString(
      locale === "ru" ? "ru-RU" : "en-US",
      { year: "numeric", month: "short", day: "numeric" }
    );
  };

  const formatNum = (n: number) => n.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", { maximumFractionDigits: 2 });

  const debtTypeLabel = (dt: string) => {
    const map: Record<string, string> = { creditor: t.creditor, debitor: t.debitor, settled: t.settled };
    return map[dt] ?? dt;
  };

  return (
    <div className="p-6">
      <h1 className="text-2xl font-bold mb-6">{t.supplierDebtTitle}</h1>

      <ReportFilterBar>
        <div className="space-y-1">
          <FieldLabel>{t.asOfDate}</FieldLabel>
          <Input type="date" value={asOfDate} onChange={(e) => setAsOfDate(e.target.value)} className="w-40" />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.from}</FieldLabel>
          <Input type="date" value={periodFrom} onChange={(e) => setPeriodFrom(e.target.value)} className="w-40" />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.to}</FieldLabel>
          <Input type="date" value={periodTo} onChange={(e) => setPeriodTo(e.target.value)} className="w-40" />
        </div>
        <div className="space-y-1">
          <FieldLabel>{t.debtType}</FieldLabel>
          <Select value={debtType} onValueChange={(v) => setDebtType(v === "all" ? "" : v)}>
            <SelectTrigger className="w-40">
              <SelectValue placeholder={t.allDebtTypes} />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="all">{t.allDebtTypes}</SelectItem>
              <SelectItem value="creditor">{t.creditor}</SelectItem>
              <SelectItem value="debitor">{t.debitor}</SelectItem>
              <SelectItem value="settled">{t.settled}</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </ReportFilterBar>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : report ? (
        <>
          {/* KPI Cards */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 mb-6">
            <KpiCard title={t.totalPurchased} value={formatNum(report.totals.totalPurchased)} />
            <KpiCard title={t.totalPaid} value={formatNum(report.totals.totalPaid)} />
            <KpiCard title={t.totalDebt} value={formatNum(report.totals.totalDebt)} />
          </div>

          <DataTable<SupplierDebtRow & Record<string, unknown>>
            columns={[
              {
                key: "supplierName",
                header: t.supplier,
              },
              {
                key: "purchasedInPeriod",
                header: t.purchasedInPeriod,
                className: "text-right",
                render: (item) => formatNum(item.purchasedInPeriod),
              },
              {
                key: "paidInPeriod",
                header: t.paidInPeriod,
                className: "text-right",
                render: (item) => formatNum(item.paidInPeriod),
              },
              {
                key: "currentDebt",
                header: t.currentDebt,
                className: "text-right",
                render: (item) => (
                  <span className={item.currentDebt > 0 ? "text-red-600 font-medium" : item.currentDebt < 0 ? "text-green-600 font-medium" : ""}>
                    {formatNum(item.currentDebt)}
                  </span>
                ),
              },
              {
                key: "debtType",
                header: t.debtType,
                render: (item) => (
                  <Badge className={DEBT_TYPE_COLORS[item.debtType]} variant="secondary">
                    {debtTypeLabel(item.debtType)}
                  </Badge>
                ),
              },
            ]}
            data={report.rows as (SupplierDebtRow & Record<string, unknown>)[]}
            onRowClick={(item) => openDrillDown(item)}
            emptyMessage={t.noDebtData}
          />
        </>
      ) : null}

      {/* Drill-down Sheet */}
      <Sheet open={!!drillSupplier} onOpenChange={(open) => { if (!open) setDrillSupplier(null); }}>
        <SheetContent className="w-full sm:max-w-lg overflow-y-auto">
          <SheetTitle>{t.drillDownTitle}: {drillSupplier?.supplierName}</SheetTitle>
          {drillLoading ? (
            <p className="text-muted-foreground mt-4">{t.loading}</p>
          ) : (
            <div className="mt-4">
              <DataTable<DrillDownItem>
                columns={[
                  {
                    key: "operationDate",
                    header: t.date,
                    render: (item) => formatDate(item.operationDate),
                  },
                  {
                    key: "type",
                    header: t.operationType,
                    render: (item) => (
                      <Badge className={TYPE_COLORS[item.type] ?? ""} variant="secondary">
                        {item.type === "purchase" ? t.opPurchase : t.opPayment}
                      </Badge>
                    ),
                  },
                  {
                    key: "amount",
                    header: t.amount,
                    className: "text-right",
                    render: (item) => {
                      if (item.type === "payment") {
                        return item.paymentAmount != null ? formatNum(item.paymentAmount) : "-";
                      }
                      const total = item.itemsSummary.reduce(
                        (s, i) => s + i.quantity * (i.unitPrice ?? 0),
                        0
                      );
                      return formatNum(total);
                    },
                  },
                  {
                    key: "comment",
                    header: t.comment,
                    render: (item) => item.comment || "-",
                  },
                ]}
                data={drillItems}
                emptyMessage={t.noDebtData}
              />
              <Pagination
                offset={drillOffset}
                limit={DRILL_PAGE_SIZE}
                total={drillTotal}
                onPageChange={setDrillOffset}
              />
            </div>
          )}
        </SheetContent>
      </Sheet>
    </div>
  );
}
