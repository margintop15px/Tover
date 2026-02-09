"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { useI18n } from "@/i18n/context";
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

function formatCurrency(value: number, locale: string): string {
  return new Intl.NumberFormat(locale === "ru" ? "ru-RU" : "en-US", {
    style: "currency",
    currency: "EUR",
    minimumFractionDigits: 2,
  }).format(value);
}

export default function OrderDetailPage() {
  const params = useParams();
  const router = useRouter();
  const { t, locale } = useI18n();
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

  const fmtCur = (v: number) => formatCurrency(v, locale);

  return (
    <div className="min-h-screen bg-zinc-50 dark:bg-zinc-950">
      <header className="border-b border-zinc-200 bg-white px-6 py-4 dark:border-zinc-800 dark:bg-zinc-900">
        <div className="mx-auto flex max-w-7xl items-center gap-4">
          <button
            onClick={() => router.back()}
            className="text-sm text-zinc-500 hover:text-zinc-700 dark:text-zinc-400 dark:hover:text-zinc-200"
          >
            &larr; {t.back}
          </button>
          <h1 className="text-xl font-bold text-zinc-900 dark:text-zinc-50">
            {t.orderLines}
          </h1>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-8">
        <p className="mb-4 text-sm text-zinc-500 dark:text-zinc-400">
          {t.orderIdLabel}: <code className="font-mono">{orderId}</code>
        </p>

        {loading ? (
          <div className="rounded-lg border border-zinc-200 bg-white p-8 text-center text-sm text-zinc-500 dark:border-zinc-800 dark:bg-zinc-900">
            {t.loadingOrderLines}
          </div>
        ) : (
          <DataTable<OrderLine>
            columns={[
              { key: "sku", header: t.sku },
              {
                key: "quantity",
                header: t.qty,
                render: (item) => item.quantity.toLocaleString(),
              },
              {
                key: "unitPriceGross",
                header: t.unitPrice,
                render: (item) => fmtCur(item.unitPriceGross),
              },
              {
                key: "discountAmount",
                header: t.discount,
                render: (item) => fmtCur(item.discountAmount),
              },
              {
                key: "taxAmount",
                header: t.tax,
                render: (item) => fmtCur(item.taxAmount),
              },
              {
                key: "lineGmv",
                header: t.lineGmv,
                render: (item) => fmtCur(item.lineGmv),
              },
            ]}
            data={lines}
            emptyMessage={t.noOrderLinesFound}
          />
        )}
      </main>
    </div>
  );
}
