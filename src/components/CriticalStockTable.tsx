"use client";

import { useEffect, useState } from "react";
import DataTable from "./DataTable";

interface CriticalStockItem {
  sku: string;
  onHandQty: number;
  avgUnitsPerDay: number;
  daysRemaining: number;
}

export default function CriticalStockTable() {
  const [items, setItems] = useState<CriticalStockItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchCriticalStock();
  }, []);

  async function fetchCriticalStock() {
    setLoading(true);
    try {
      const res = await fetch("/api/metrics/critical-stock?days=14&lookback=7");
      const data = await res.json();
      setItems(data.items || []);
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }

  if (loading) {
    return (
      <div className="rounded-lg border border-zinc-200 bg-white p-8 text-center text-sm text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900">
        Loading critical stock...
      </div>
    );
  }

  return (
    <div>
      <h3 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-50">
        Critical Stock (runs out in 14 days)
      </h3>
      <DataTable<CriticalStockItem>
        columns={[
          { key: "sku", header: "SKU" },
          {
            key: "onHandQty",
            header: "On Hand",
            render: (item) => item.onHandQty.toLocaleString(),
          },
          {
            key: "avgUnitsPerDay",
            header: "Avg/Day",
            render: (item) => item.avgUnitsPerDay.toFixed(1),
          },
          {
            key: "daysRemaining",
            header: "Days Left",
            render: (item) => (
              <span
                className={
                  item.daysRemaining <= 3
                    ? "font-bold text-red-600"
                    : item.daysRemaining <= 7
                      ? "font-medium text-amber-600"
                      : ""
                }
              >
                {item.daysRemaining.toFixed(1)}
              </span>
            ),
          },
        ]}
        data={items}
        emptyMessage="No critical stock items"
      />
    </div>
  );
}
