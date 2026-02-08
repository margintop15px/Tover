"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import DataTable from "@/components/DataTable";

interface OrderLine {
  id: string;
  sku: string;
  quantity: number;
  unitPriceGross: number;
  discountAmount: number;
  taxAmount: number;
  lineGmv: number;
  [key: string]: unknown;
}

function formatCurrency(value: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
  }).format(value);
}

export default function OrderDetailPage() {
  const params = useParams();
  const router = useRouter();
  const orderId = params.id as string;

  const [lines, setLines] = useState<OrderLine[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function fetchLines() {
      setLoading(true);
      try {
        const res = await fetch(`/api/orders/${orderId}/lines`);
        const data = await res.json();
        setLines(data.items || []);
      } catch {
        setLines([]);
      } finally {
        setLoading(false);
      }
    }

    if (orderId) fetchLines();
  }, [orderId]);

  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-900">
        <div className="mx-auto flex max-w-7xl items-center gap-4">
          <button
            onClick={() => router.back()}
            className="text-sm text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
          >
            &larr; Back
          </button>
          <h1 className="text-xl font-bold text-zinc-900 dark:text-zinc-50">
            Order Lines
          </h1>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-8">
        <p className="mb-4 text-sm text-zinc-500 dark:text-zinc-400">
          Order ID: <code className="font-mono">{orderId}</code>
        </p>

        {loading ? (
          <div className="rounded-lg border border-zinc-200 bg-white p-8 text-center text-sm text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900">
            Loading order lines...
          </div>
        ) : (
          <DataTable<OrderLine>
            columns={[
              { key: "sku", header: "SKU" },
              {
                key: "quantity",
                header: "Qty",
                render: (item) => item.quantity.toLocaleString(),
              },
              {
                key: "unitPriceGross",
                header: "Unit Price",
                render: (item) => formatCurrency(item.unitPriceGross),
              },
              {
                key: "discountAmount",
                header: "Discount",
                render: (item) => formatCurrency(item.discountAmount),
              },
              {
                key: "taxAmount",
                header: "Tax",
                render: (item) => formatCurrency(item.taxAmount),
              },
              {
                key: "lineGmv",
                header: "Line GMV",
                render: (item) => formatCurrency(item.lineGmv),
              },
            ]}
            data={lines}
            emptyMessage="No order lines found"
          />
        )}
      </main>
    </div>
  );
}
