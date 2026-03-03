"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Field, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type {
  OperationType,
  Category,
  Store,
  Supplier,
  Product,
  Warehouse,
  OperationItemInput,
} from "@/types/inventory";
import { Plus, Trash2 } from "lucide-react";

interface RefData {
  products: Product[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  stores: Store[];
  categories: Category[];
}

const OPERATION_TYPES: OperationType[] = [
  "purchase",
  "sale",
  "return",
  "write_off",
  "transfer",
  "production",
  "defect",
  "payment",
];

export default function NewOperationPage() {
  const { t } = useI18n();
  const router = useRouter();

  // Reference data
  const [ref, setRef] = useState<RefData>({
    products: [],
    warehouses: [],
    suppliers: [],
    stores: [],
    categories: [],
  });
  const [refLoading, setRefLoading] = useState(true);

  // Form state
  const [type, setType] = useState<OperationType>("purchase");
  const [operationDate, setOperationDate] = useState(
    new Date().toISOString().split("T")[0]
  );
  const [comment, setComment] = useState("");
  const [supplierId, setSupplierId] = useState("");
  const [paymentAmount, setPaymentAmount] = useState("");

  // Items for purchase/sale/return/write_off/production
  const [items, setItems] = useState<OperationItemInput[]>([
    { productId: "", warehouseId: "", quantity: 0 },
  ]);

  // Transfer-specific
  const [transferProductId, setTransferProductId] = useState("");
  const [sourceWarehouseId, setSourceWarehouseId] = useState("");
  const [destWarehouseId, setDestWarehouseId] = useState("");
  const [transferQty, setTransferQty] = useState("");

  // Defect-specific
  const [defectProductId, setDefectProductId] = useState("");
  const [defectWarehouseId, setDefectWarehouseId] = useState("");
  const [defectQty, setDefectQty] = useState("");

  // Production output
  const [prodOutputProductId, setProdOutputProductId] = useState("");
  const [prodOutputWarehouseId, setProdOutputWarehouseId] = useState("");
  const [prodOutputQty, setProdOutputQty] = useState("");
  const [prodOutputStoreId, setProdOutputStoreId] = useState("");

  const [saving, setSaving] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);

  const typeLabel = useCallback(
    (op: OperationType): string => {
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
      return map[op];
    },
    [t]
  );

  useEffect(() => {
    async function load() {
      setRefLoading(true);
      try {
        const [prodRes, whRes, supRes, storeRes] = await Promise.all([
          fetch("/api/products?limit=500"),
          fetch("/api/warehouses?limit=200"),
          fetch("/api/suppliers?limit=200"),
          fetch("/api/stores?limit=200"),
        ]);
        const [prodData, whData, supData, storeData] = await Promise.all([
          prodRes.json(),
          whRes.json(),
          supRes.json(),
          storeRes.json(),
        ]);
        setRef({
          products: prodData.items || [],
          warehouses: whData.items || [],
          suppliers: supData.items || [],
          stores: storeData.items || [],
          categories: [],
        });
      } finally {
        setRefLoading(false);
      }
    }
    load();
  }, []);

  const updateItem = (
    index: number,
    field: keyof OperationItemInput,
    value: string | number
  ) => {
    setItems((prev) =>
      prev.map((item, i) =>
        i === index ? { ...item, [field]: value } : item
      )
    );
  };

  const addItem = () => {
    setItems((prev) => [
      ...prev,
      { productId: "", warehouseId: "", quantity: 0 },
    ]);
  };

  const removeItem = (index: number) => {
    setItems((prev) => prev.filter((_, i) => i !== index));
  };

  const handleSubmit = async () => {
    setSaving(true);
    setErrors([]);

    try {
      const body: Record<string, unknown> = {
        type,
        operationDate,
        comment: comment || undefined,
      };

      switch (type) {
        case "payment":
          body.supplierId = supplierId || undefined;
          body.paymentAmount = paymentAmount
            ? parseFloat(paymentAmount)
            : undefined;
          break;

        case "purchase":
          body.supplierId = supplierId || undefined;
          body.items = items.map((item) => ({
            productId: item.productId,
            warehouseId: item.warehouseId,
            quantity: Number(item.quantity),
            unitPrice: Number(item.unitPrice || 0),
          }));
          break;

        case "sale":
        case "return":
        case "write_off":
          body.items = items.map((item) => ({
            productId: item.productId,
            warehouseId: item.warehouseId,
            quantity: Number(item.quantity),
          }));
          break;

        case "transfer":
          body.productId = transferProductId || undefined;
          body.sourceWarehouseId = sourceWarehouseId || undefined;
          body.destinationWarehouseId = destWarehouseId || undefined;
          body.quantity = transferQty ? parseFloat(transferQty) : undefined;
          break;

        case "defect":
          body.productId = defectProductId || undefined;
          body.sourceWarehouseId = defectWarehouseId || undefined;
          body.quantity = defectQty ? parseFloat(defectQty) : undefined;
          break;

        case "production":
          body.items = [
            ...items.map((item) => ({
              productId: item.productId,
              warehouseId: item.warehouseId,
              quantity: Number(item.quantity),
              direction: "out" as const,
            })),
            {
              productId: prodOutputProductId,
              warehouseId: prodOutputWarehouseId,
              quantity: Number(prodOutputQty || 0),
              direction: "in" as const,
              storeId: prodOutputStoreId || undefined,
            },
          ];
          break;
      }

      const res = await fetch("/api/operations", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });

      if (!res.ok) {
        const data = await res.json();
        if (data.errors) {
          setErrors(
            data.errors.map(
              (e: { field: string; message: string }) =>
                `${e.field}: ${e.message}`
            )
          );
        } else {
          setErrors([data.error || t.unexpectedError]);
        }
        return;
      }

      router.push("/operations");
    } finally {
      setSaving(false);
    }
  };

  if (refLoading) {
    return <div className="p-6 text-muted-foreground">{t.loading}</div>;
  }

  const needsSupplier = type === "purchase" || type === "payment";
  const needsItems =
    type === "purchase" ||
    type === "sale" ||
    type === "return" ||
    type === "write_off";
  const needsPrice = type === "purchase";

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.newOperation}</h1>

      {/* Type Tabs */}
      <Tabs
        value={type}
        onValueChange={(v) => setType(v as OperationType)}
        className="mb-6"
      >
        <TabsList className="flex flex-wrap h-auto gap-1">
          {OPERATION_TYPES.map((op) => (
            <TabsTrigger key={op} value={op} className="text-xs">
              {typeLabel(op)}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <div className="space-y-6 max-w-3xl">
        {/* Common fields */}
        <div className="grid grid-cols-2 gap-4">
          <Field>
            <FieldLabel>{t.operationDate}</FieldLabel>
            <Input
              type="date"
              value={operationDate}
              onChange={(e) => setOperationDate(e.target.value)}
            />
          </Field>
          <Field>
            <FieldLabel>{t.operationComment}</FieldLabel>
            <Textarea
              value={comment}
              onChange={(e) => setComment(e.target.value)}
              rows={1}
            />
          </Field>
        </div>

        {/* Supplier select */}
        {needsSupplier && (
          <Field>
            <FieldLabel>{t.operationSupplier}</FieldLabel>
            <Select value={supplierId} onValueChange={setSupplierId}>
              <SelectTrigger>
                <SelectValue placeholder={t.selectSupplier} />
              </SelectTrigger>
              <SelectContent>
                {ref.suppliers.map((s) => (
                  <SelectItem key={s.id} value={s.id}>
                    {s.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        )}

        {/* Payment amount */}
        {type === "payment" && (
          <Field>
            <FieldLabel>{t.paymentAmount}</FieldLabel>
            <Input
              type="number"
              step="0.01"
              value={paymentAmount}
              onChange={(e) => setPaymentAmount(e.target.value)}
            />
          </Field>
        )}

        {/* Items list (purchase, sale, return, write_off) */}
        {needsItems && (
          <div>
            <div className="mb-2 flex items-center justify-between">
              <Label className="text-base font-semibold">
                {type === "purchase" ? t.opPurchase : ""} Items
              </Label>
              <Button variant="outline" size="sm" onClick={addItem}>
                <Plus className="mr-1 h-4 w-4" />
                {t.addItem}
              </Button>
            </div>
            <div className="space-y-3">
              {items.map((item, i) => (
                <div
                  key={i}
                  className="flex items-end gap-2 rounded-md border p-3"
                >
                  <Field className="flex-1">
                    <FieldLabel className="text-xs">{t.product}</FieldLabel>
                    <Select
                      value={item.productId}
                      onValueChange={(v) => updateItem(i, "productId", v)}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={t.selectProduct} />
                      </SelectTrigger>
                      <SelectContent>
                        {ref.products.map((p) => (
                          <SelectItem key={p.id} value={p.id}>
                            {p.name}
                            {p.skuCode ? ` (${p.skuCode})` : ""}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field className="flex-1">
                    <FieldLabel className="text-xs">{t.warehouse}</FieldLabel>
                    <Select
                      value={item.warehouseId}
                      onValueChange={(v) => updateItem(i, "warehouseId", v)}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={t.selectWarehouse} />
                      </SelectTrigger>
                      <SelectContent>
                        {ref.warehouses.map((w) => (
                          <SelectItem key={w.id} value={w.id}>
                            {w.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field className="w-24">
                    <FieldLabel className="text-xs">{t.quantity}</FieldLabel>
                    <Input
                      type="number"
                      step="0.001"
                      value={item.quantity || ""}
                      onChange={(e) =>
                        updateItem(i, "quantity", parseFloat(e.target.value) || 0)
                      }
                    />
                  </Field>
                  {needsPrice && (
                    <Field className="w-28">
                      <FieldLabel className="text-xs">{t.price}</FieldLabel>
                      <Input
                        type="number"
                        step="0.01"
                        value={item.unitPrice || ""}
                        onChange={(e) =>
                          updateItem(
                            i,
                            "unitPrice",
                            parseFloat(e.target.value) || 0
                          )
                        }
                      />
                    </Field>
                  )}
                  {items.length > 1 && (
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => removeItem(i)}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Transfer form */}
        {type === "transfer" && (
          <div className="space-y-4">
            <Field>
              <FieldLabel>{t.product}</FieldLabel>
              <Select
                value={transferProductId}
                onValueChange={setTransferProductId}
              >
                <SelectTrigger>
                  <SelectValue placeholder={t.selectProduct} />
                </SelectTrigger>
                <SelectContent>
                  {ref.products.map((p) => (
                    <SelectItem key={p.id} value={p.id}>
                      {p.name}
                      {p.skuCode ? ` (${p.skuCode})` : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field>
                <FieldLabel>{t.sourceWarehouse}</FieldLabel>
                <Select
                  value={sourceWarehouseId}
                  onValueChange={setSourceWarehouseId}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.selectWarehouse} />
                  </SelectTrigger>
                  <SelectContent>
                    {ref.warehouses.map((w) => (
                      <SelectItem key={w.id} value={w.id}>
                        {w.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel>{t.destinationWarehouse}</FieldLabel>
                <Select
                  value={destWarehouseId}
                  onValueChange={setDestWarehouseId}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.selectWarehouse} />
                  </SelectTrigger>
                  <SelectContent>
                    {ref.warehouses.map((w) => (
                      <SelectItem key={w.id} value={w.id}>
                        {w.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
            </div>
            <Field className="w-32">
              <FieldLabel>{t.quantity}</FieldLabel>
              <Input
                type="number"
                step="0.001"
                value={transferQty}
                onChange={(e) => setTransferQty(e.target.value)}
              />
            </Field>
          </div>
        )}

        {/* Defect form */}
        {type === "defect" && (
          <div className="space-y-4">
            <Field>
              <FieldLabel>{t.product}</FieldLabel>
              <Select
                value={defectProductId}
                onValueChange={setDefectProductId}
              >
                <SelectTrigger>
                  <SelectValue placeholder={t.selectProduct} />
                </SelectTrigger>
                <SelectContent>
                  {ref.products.map((p) => (
                    <SelectItem key={p.id} value={p.id}>
                      {p.name}
                      {p.skuCode ? ` (${p.skuCode})` : ""}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel>{t.sourceWarehouse}</FieldLabel>
              <Select
                value={defectWarehouseId}
                onValueChange={setDefectWarehouseId}
              >
                <SelectTrigger>
                  <SelectValue placeholder={t.selectWarehouse} />
                </SelectTrigger>
                <SelectContent>
                  {ref.warehouses.map((w) => (
                    <SelectItem key={w.id} value={w.id}>
                      {w.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field className="w-32">
              <FieldLabel>{t.quantity}</FieldLabel>
              <Input
                type="number"
                step="0.001"
                value={defectQty}
                onChange={(e) => setDefectQty(e.target.value)}
              />
            </Field>
          </div>
        )}

        {/* Production form */}
        {type === "production" && (
          <div className="space-y-6">
            {/* Source items */}
            <div>
              <div className="mb-2 flex items-center justify-between">
                <Label className="text-base font-semibold">
                  Source Materials ({t.directionOut})
                </Label>
                <Button variant="outline" size="sm" onClick={addItem}>
                  <Plus className="mr-1 h-4 w-4" />
                  {t.addItem}
                </Button>
              </div>
              <div className="space-y-3">
                {items.map((item, i) => (
                  <div
                    key={i}
                    className="flex items-end gap-2 rounded-md border p-3"
                  >
                    <Field className="flex-1">
                      <FieldLabel className="text-xs">{t.product}</FieldLabel>
                      <Select
                        value={item.productId}
                        onValueChange={(v) => updateItem(i, "productId", v)}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder={t.selectProduct} />
                        </SelectTrigger>
                        <SelectContent>
                          {ref.products.map((p) => (
                            <SelectItem key={p.id} value={p.id}>
                              {p.name}
                              {p.skuCode ? ` (${p.skuCode})` : ""}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </Field>
                    <Field className="flex-1">
                      <FieldLabel className="text-xs">{t.warehouse}</FieldLabel>
                      <Select
                        value={item.warehouseId}
                        onValueChange={(v) => updateItem(i, "warehouseId", v)}
                      >
                        <SelectTrigger>
                          <SelectValue placeholder={t.selectWarehouse} />
                        </SelectTrigger>
                        <SelectContent>
                          {ref.warehouses.map((w) => (
                            <SelectItem key={w.id} value={w.id}>
                              {w.name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    </Field>
                    <Field className="w-24">
                      <FieldLabel className="text-xs">{t.quantity}</FieldLabel>
                      <Input
                        type="number"
                        step="0.001"
                        value={item.quantity || ""}
                        onChange={(e) =>
                          updateItem(
                            i,
                            "quantity",
                            parseFloat(e.target.value) || 0
                          )
                        }
                      />
                    </Field>
                    {items.length > 1 && (
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => removeItem(i)}
                      >
                        <Trash2 className="h-4 w-4" />
                      </Button>
                    )}
                  </div>
                ))}
              </div>
            </div>

            {/* Output item */}
            <div>
              <Label className="mb-2 block text-base font-semibold">
                Output Product ({t.directionIn})
              </Label>
              <div className="rounded-md border p-3 space-y-3">
                <div className="grid grid-cols-2 gap-4">
                  <Field>
                    <FieldLabel className="text-xs">{t.product}</FieldLabel>
                    <Select
                      value={prodOutputProductId}
                      onValueChange={setProdOutputProductId}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={t.selectProduct} />
                      </SelectTrigger>
                      <SelectContent>
                        {ref.products.map((p) => (
                          <SelectItem key={p.id} value={p.id}>
                            {p.name}
                            {p.skuCode ? ` (${p.skuCode})` : ""}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>
                  <Field>
                    <FieldLabel className="text-xs">{t.warehouse}</FieldLabel>
                    <Select
                      value={prodOutputWarehouseId}
                      onValueChange={setProdOutputWarehouseId}
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={t.selectWarehouse} />
                      </SelectTrigger>
                      <SelectContent>
                        {ref.warehouses.map((w) => (
                          <SelectItem key={w.id} value={w.id}>
                            {w.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <Field className="w-32">
                    <FieldLabel className="text-xs">{t.quantity}</FieldLabel>
                    <Input
                      type="number"
                      step="0.001"
                      value={prodOutputQty}
                      onChange={(e) => setProdOutputQty(e.target.value)}
                    />
                  </Field>
                  <Field>
                    <FieldLabel className="text-xs">{t.productStore}</FieldLabel>
                    <Select
                      value={prodOutputStoreId}
                      onValueChange={(v) =>
                        setProdOutputStoreId(v === "none" ? "" : v)
                      }
                    >
                      <SelectTrigger>
                        <SelectValue placeholder={t.selectStore} />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="none">{t.purposeNone}</SelectItem>
                        {ref.stores.map((s) => (
                          <SelectItem key={s.id} value={s.id}>
                            {s.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </Field>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* Errors */}
        {errors.length > 0 && (
          <div className="rounded-md border border-destructive bg-destructive/10 p-3">
            {errors.map((err, i) => (
              <p key={i} className="text-sm text-destructive">
                {err}
              </p>
            ))}
          </div>
        )}

        {/* Submit */}
        <div className="flex gap-3">
          <Button onClick={handleSubmit} disabled={saving}>
            {saving ? t.saving : t.save}
          </Button>
          <Button variant="outline" onClick={() => router.push("/operations")}>
            {t.cancel}
          </Button>
        </div>
      </div>
    </div>
  );
}
