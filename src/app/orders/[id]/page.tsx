"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
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
    <div className="min-h-screen bg-background">
      <header className="border-b bg-card px-6 py-4">
        <div className="mx-auto flex max-w-7xl items-center gap-4">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => router.back()}
          >
            <ArrowLeft className="size-4" />
            {t.back}
          </Button>
          <h1 className="text-xl font-bold">{t.orderLines}</h1>
        </div>
      </header>

      <main className="mx-auto max-w-7xl px-6 py-8">
        <p className="mb-4 text-sm text-muted-foreground">
          {t.orderIdLabel}: <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm">{orderId}</code>
        </p>

        {loading ? (
          <div className="rounded-lg border bg-card p-8 text-center text-sm text-muted-foreground">
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
