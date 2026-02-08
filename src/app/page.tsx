"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import KpiCard from "@/components/KpiCard";
import DateRangePicker from "@/components/DateRangePicker";
import DataTable from "@/components/DataTable";
import UploadCard from "@/components/UploadCard";
import CriticalStockTable from "@/components/CriticalStockTable";

interface KpiData {
  gmvGross: number;
  unitsSold: number;
  ordersCount: number;
  stockValueCost: number | null;
  inventorySnapshotDate: string | null;
}

interface OrderRow {
  id: string;
  source: string;
  externalOrderId: string;
  orderedAt: string;
  currency: string;
  status: string;
  orderGmv: number;
  orderUnits: number;
  [key: string]: unknown;
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
  }).format(value);
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString("en-US", {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function defaultDateRange() {
  const to = new Date();
  const from = new Date();
  from.setDate(from.getDate() - 30);
  return {
    from: from.toISOString().split("T")[0],
    to: to.toISOString().split("T")[0],
  };
}

export default function Dashboard() {
  const router = useRouter();
  const [dateRange, setDateRange] = useState(defaultDateRange);
  const [kpis, setKpis] = useState<KpiData | null>(null);
  const [orders, setOrders] = useState<OrderRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [showOrders, setShowOrders] = useState(false);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const fromTs = new Date(dateRange.from).toISOString();
      const toTs = new Date(
        dateRange.to + "T23:59:59.999Z"
      ).toISOString();

      const [metricsRes, ordersRes] = await Promise.all([
        fetch(
          `/api/metrics/summary?from=${encodeURIComponent(fromTs)}&to=${encodeURIComponent(toTs)}`
        ),
        fetch(
          `/api/orders?from=${encodeURIComponent(fromTs)}&to=${encodeURIComponent(toTs)}&limit=50`
        ),
      ]);

      const metricsData = await metricsRes.json();
      const ordersData = await ordersRes.json();

      setKpis(metricsData.kpis || null);
      setOrders(ordersData.items || []);
    } catch (err) {
      console.error("Failed to fetch data:", err);
    } finally {
      setLoading(false);
    }
  }, [dateRange]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-900">
        <div className="mx-auto flex max-w-7xl items-center justify-between">
          <h1 className="text-xl font-bold text-zinc-900 dark:text-zinc-50">
            Tover
          </h1>
          <DateRangePicker
            from={dateRange.from}
            to={dateRange.to}
            onChange={(from, to) => setDateRange({ from, to })}
          />
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-8">
        {/* KPI Cards */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <KpiCard
            title="GMV (Gross)"
            value={
              loading
                ? "..."
                : kpis
                  ? formatCurrency(kpis.gmvGross)
                  : formatCurrency(0)
            }
            subtitle="Click to see orders"
            onClick={() => setShowOrders(true)}
          />
          <KpiCard
            title="Units Sold"
            value={
              loading
                ? "..."
                : kpis
                  ? kpis.unitsSold.toLocaleString()
                  : "0"
            }
          />
          <KpiCard
            title="Orders"
            value={
              loading
                ? "..."
                : kpis
                  ? kpis.ordersCount.toLocaleString()
                  : "0"
            }
          />
          <KpiCard
            title="Stock Value"
            value={
              loading
                ? "..."
                : kpis?.stockValueCost !== null && kpis?.stockValueCost !== undefined
                  ? formatCurrency(kpis.stockValueCost)
                  : "N/A"
            }
            subtitle={
              kpis?.inventorySnapshotDate
                ? `Snapshot: ${kpis.inventorySnapshotDate}`
                : "No inventory data"
            }
          />
        </div>

        {/* Orders Drill-down */}
        {showOrders && (
          <section className="mt-8">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-zinc-900 dark:text-zinc-50">
                Orders
              </h2>
              <button
                onClick={() => setShowOrders(false)}
                className="text-sm text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
              >
                Hide
              </button>
            </div>
            <DataTable<OrderRow>
              columns={[
                {
                  key: "orderedAt",
                  header: "Date",
                  render: (item) => formatDate(item.orderedAt),
                },
                { key: "source", header: "Source" },
                { key: "externalOrderId", header: "Order ID" },
                { key: "status", header: "Status" },
                {
                  key: "orderGmv",
                  header: "GMV",
                  render: (item) => formatCurrency(item.orderGmv),
                },
                {
                  key: "orderUnits",
                  header: "Units",
                  render: (item) => item.orderUnits.toLocaleString(),
                },
              ]}
              data={orders}
              onRowClick={(item) => router.push(`/orders/${item.id}`)}
              emptyMessage="No orders in this date range"
            />
          </section>
        )}

        {/* Critical Stock + Import side by side */}
        <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-2">
          <CriticalStockTable />
          <UploadCard onImportComplete={fetchData} />
        </div>
      </main>
    </div>
  );
}
