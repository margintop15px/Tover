"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { formatCurrency } from "@/lib/format-currency";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import type { OperationDirection, OperationType } from "@/types/inventory";

interface OperationDetails {
  id: string;
  type: OperationType;
  operationDate: string;
  comment: string | null;
  supplierId: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  items: {
    id: string;
    productName: string;
    warehouseName: string;
    quantity: number;
    unitPrice: number | null;
    direction: OperationDirection;
  }[];
}

interface SupplierOption {
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
  inventory_adjustment: "bg-violet-100 text-violet-800",
};

export default function EditOperationPage() {
  return (
    <Suspense fallback={<div className="p-6 text-muted-foreground">Loading...</div>}>
      <EditOperationPageContent />
    </Suspense>
  );
}

function EditOperationPageContent() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
  const router = useRouter();
  const params = useParams<{ id: string }>();
  const searchParams = useSearchParams();
  const id = params.id;
  const requestedReturnTo = searchParams.get("returnTo");
  const returnTo = requestedReturnTo?.startsWith("/operations")
    ? requestedReturnTo
    : "/operations";

  const [operation, setOperation] = useState<OperationDetails | null>(null);
  const [suppliers, setSuppliers] = useState<SupplierOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const [operationDate, setOperationDate] = useState("");
  const [comment, setComment] = useState("");
  const [supplierId, setSupplierId] = useState("");
  const [paymentAmount, setPaymentAmount] = useState("");

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
        inventory_adjustment: t.opInventoryAdjustment,
      };
      return map[type];
    },
    [t]
  );

  const formatNumber = (value: number | null) => {
    if (value == null) return "-";
    return value.toLocaleString(locale === "ru" ? "ru-RU" : "en-US", {
      maximumFractionDigits: 3,
    });
  };

  const formatMoney = (value: number | null) => {
    if (value == null) return "-";
    return formatCurrency(value, locale, settings.currency);
  };

  useEffect(() => {
    async function load() {
      setLoading(true);
      try {
        const [operationRes, suppliersRes] = await Promise.all([
          fetch(`/api/operations/${id}`),
          fetch("/api/suppliers?limit=200"),
        ]);
        const operationData = await operationRes.json();
        const suppliersData = await suppliersRes.json();

        setOperation(operationData);
        setOperationDate(operationData.operationDate || "");
        setComment(operationData.comment || "");
        setSupplierId(operationData.supplierId || "");
        setPaymentAmount(
          operationData.paymentAmount == null
            ? ""
            : String(operationData.paymentAmount)
        );
        setSuppliers(suppliersData.items || []);
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [id]);

  const handleSave = async () => {
    if (!operation) return;

    setSaving(true);
    setError("");

    try {
      const body: Record<string, unknown> = {
        operationDate,
        comment,
      };

      if (operation.type === "purchase" || operation.type === "payment") {
        body.supplierId = supplierId;
      }

      if (operation.type === "payment") {
        body.paymentAmount = paymentAmount
          ? Number(paymentAmount)
          : undefined;
      }

      const res = await fetch(`/api/operations/${operation.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const data = await res.json();
        setError(data.error || t.unexpectedError);
        return;
      }

      router.push(returnTo);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="p-6 text-muted-foreground">{t.loading}</div>;
  }

  if (!operation) {
    return <div className="p-6 text-muted-foreground">{t.noData}</div>;
  }

  const canEditSupplier =
    operation.type === "purchase" || operation.type === "payment";
  const canEditPayment = operation.type === "payment";

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">{t.editOperation}</h1>
          <div className="mt-2">
            <Badge className={TYPE_COLORS[operation.type]} variant="secondary">
              {typeLabel(operation.type)}
            </Badge>
          </div>
        </div>
      </div>

      <div className="max-w-3xl space-y-6">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field>
            <FieldLabel>{t.operationDate}</FieldLabel>
            <Input
              type="date"
              value={operationDate}
              onChange={(event) => setOperationDate(event.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel>{t.operationComment}</FieldLabel>
            <Textarea
              value={comment}
              onChange={(event) => setComment(event.target.value)}
              rows={1}
            />
          </Field>
        </div>

        {canEditSupplier && (
          <Field>
            <FieldLabel>{t.operationSupplier}</FieldLabel>
            <Select value={supplierId} onValueChange={setSupplierId}>
              <SelectTrigger>
                <SelectValue placeholder={t.selectSupplier} />
              </SelectTrigger>
              <SelectContent>
                {suppliers.map((supplier) => (
                  <SelectItem key={supplier.id} value={supplier.id}>
                    {supplier.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        )}

        {canEditPayment && (
          <Field>
            <FieldLabel>{t.paymentAmount}</FieldLabel>
            <Input
              type="number"
              step="1"
              value={paymentAmount}
              onChange={(event) => setPaymentAmount(event.target.value)}
            />
          </Field>
        )}

        <div>
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-base font-semibold">{t.items}</h2>
            <p className="text-sm text-muted-foreground">
              {t.operationItemsReadOnly}
            </p>
          </div>
          {operation.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t.noData}</p>
          ) : (
            <div className="rounded-md border">
              <table className="w-full text-sm">
                <thead className="border-b text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 text-left font-medium">{t.product}</th>
                    <th className="px-3 py-2 text-left font-medium">{t.warehouse}</th>
                    <th className="px-3 py-2 text-right font-medium">{t.quantity}</th>
                    <th className="px-3 py-2 text-right font-medium">{t.price}</th>
                  </tr>
                </thead>
                <tbody>
                  {operation.items.map((item) => (
                    <tr key={item.id} className="border-b last:border-0">
                      <td className="px-3 py-2">{item.productName}</td>
                      <td className="px-3 py-2">{item.warehouseName}</td>
                      <td className="px-3 py-2 text-right">
                        {item.direction === "in" ? "+" : "-"}
                        {formatNumber(item.quantity)}
                      </td>
                      <td className="px-3 py-2 text-right">
                        {formatMoney(item.unitPrice)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {error && (
          <div className="rounded-md border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
            {error}
          </div>
        )}

        <div className="flex gap-3">
          <Button onClick={handleSave} disabled={saving}>
            {saving ? t.saving : t.save}
          </Button>
          <Button variant="outline" onClick={() => router.push(returnTo)}>
            {t.cancel}
          </Button>
        </div>
      </div>
    </div>
  );
}
