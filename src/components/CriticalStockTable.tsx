"use client";

import { useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import DataTable from "./DataTable";

interface CriticalStockItem {
  sku: string;
  onHandQty: number;
  avgUnitsPerDay: number;
  daysRemaining: number;
}

export default function CriticalStockTable() {
  const { t } = useI18n();
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
        {t.loadingCriticalStock}
      </div>
    );
  }

  return (
    <div>
      <h3 className="mb-3 text-sm font-semibold text-zinc-900 dark:text-zinc-50">
        {t.criticalStockTitle}
      </h3>
      <DataTable<CriticalStockItem>
        columns={[
          { key: "sku", header: t.sku },
          {
            key: "onHandQty",
            header: t.onHand,
            render: (item) => item.onHandQty.toLocaleString(),
          },
          {
            key: "avgUnitsPerDay",
            header: t.avgPerDay,
            render: (item) => item.avgUnitsPerDay.toFixed(1),
          },
          {
            key: "daysRemaining",
            header: t.daysLeft,
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
        emptyMessage={t.noCriticalStockItems}
      />
    </div>
  );
}
