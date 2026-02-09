"use client";

import { useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Badge } from "@/components/ui/badge";
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
      <div className="rounded-lg border bg-card p-8 text-center text-sm text-muted-foreground">
        {t.loadingCriticalStock}
      </div>
    );
  }

  return (
    <div>
      <h3 className="mb-3 text-sm font-semibold">
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
              <Badge
                variant={
                  item.daysRemaining <= 3
                    ? "destructive"
                    : item.daysRemaining <= 7
                      ? "outline"
                      : "secondary"
                }
              >
                {item.daysRemaining.toFixed(1)}d
              </Badge>
            ),
          },
        ]}
        data={items}
        emptyMessage={t.noCriticalStockItems}
      />
    </div>
  );
}
