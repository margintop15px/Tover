"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Field, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
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
import {
  ArrowLeftRight,
  ArrowUpFromLine,
  CreditCard,
  Factory,
  PackageCheck,
  PackagePlus,
  PackageX,
  Plus,
  RotateCcw,
  ShoppingCart,
  Trash2,
  type LucideIcon,
} from "lucide-react";

interface RefData {
  products: Product[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  stores: Store[];
  categories: Category[];
}

type OperationGroup =
  | "incoming"
  | "movement"
  | "outgoing"
  | "adjustments"
  | "payments";

const OPERATION_GROUPS: {
  id: OperationGroup;
  types: OperationType[];
}[] = [
  { id: "incoming", types: ["purchase", "return"] },
  { id: "movement", types: ["transfer", "production", "defect"] },
  { id: "outgoing", types: ["sale", "write_off"] },
  { id: "adjustments", types: ["inventory_adjustment"] },
  { id: "payments", types: ["payment"] },
];

const OPERATION_ICONS: Record<OperationType, LucideIcon> = {
  purchase: PackagePlus,
  return: RotateCcw,
  transfer: ArrowLeftRight,
  production: Factory,
  defect: PackageX,
  sale: ShoppingCart,
  write_off: ArrowUpFromLine,
  inventory_adjustment: PackageCheck,
  payment: CreditCard,
};

const OPERATION_GROUP_STYLES: Record<OperationGroup, string> = {
  incoming:
    "data-[state=active]:border-emerald-200 data-[state=active]:bg-emerald-50 data-[state=active]:text-emerald-700",
  movement:
    "data-[state=active]:border-sky-200 data-[state=active]:bg-sky-50 data-[state=active]:text-sky-700",
  outgoing:
    "data-[state=active]:border-rose-200 data-[state=active]:bg-rose-50 data-[state=active]:text-rose-700",
  adjustments:
    "data-[state=active]:border-violet-200 data-[state=active]:bg-violet-50 data-[state=active]:text-violet-700",
  payments:
    "data-[state=active]:border-teal-200 data-[state=active]:bg-teal-50 data-[state=active]:text-teal-700",
};

const OPERATION_STYLES: Record<
  OperationType,
  {
    card: string;
    icon: string;
    title: string;
    radio: string;
  }
> = {
  purchase: {
    card: "border-emerald-200 bg-emerald-50/40 hover:bg-emerald-50 has-data-[state=checked]:border-emerald-500 has-data-[state=checked]:bg-emerald-50",
    icon: "text-emerald-600",
    title: "text-emerald-800",
    radio: "border-emerald-300 text-emerald-600",
  },
  return: {
    card: "border-lime-200 bg-lime-50/40 hover:bg-lime-50 has-data-[state=checked]:border-lime-500 has-data-[state=checked]:bg-lime-50",
    icon: "text-lime-600",
    title: "text-lime-800",
    radio: "border-lime-300 text-lime-600",
  },
  transfer: {
    card: "border-sky-200 bg-sky-50/40 hover:bg-sky-50 has-data-[state=checked]:border-sky-500 has-data-[state=checked]:bg-sky-50",
    icon: "text-sky-600",
    title: "text-sky-800",
    radio: "border-sky-300 text-sky-600",
  },
  production: {
    card: "border-blue-200 bg-blue-50/40 hover:bg-blue-50 has-data-[state=checked]:border-blue-500 has-data-[state=checked]:bg-blue-50",
    icon: "text-blue-600",
    title: "text-blue-800",
    radio: "border-blue-300 text-blue-600",
  },
  defect: {
    card: "border-cyan-200 bg-cyan-50/40 hover:bg-cyan-50 has-data-[state=checked]:border-cyan-500 has-data-[state=checked]:bg-cyan-50",
    icon: "text-cyan-600",
    title: "text-cyan-800",
    radio: "border-cyan-300 text-cyan-600",
  },
  sale: {
    card: "border-rose-200 bg-rose-50/40 hover:bg-rose-50 has-data-[state=checked]:border-rose-500 has-data-[state=checked]:bg-rose-50",
    icon: "text-rose-600",
    title: "text-rose-800",
    radio: "border-rose-300 text-rose-600",
  },
  write_off: {
    card: "border-red-200 bg-red-50/40 hover:bg-red-50 has-data-[state=checked]:border-red-500 has-data-[state=checked]:bg-red-50",
    icon: "text-red-600",
    title: "text-red-800",
    radio: "border-red-300 text-red-600",
  },
  inventory_adjustment: {
    card: "border-violet-200 bg-violet-50/40 hover:bg-violet-50 has-data-[state=checked]:border-violet-500 has-data-[state=checked]:bg-violet-50",
    icon: "text-violet-600",
    title: "text-violet-800",
    radio: "border-violet-300 text-violet-600",
  },
  payment: {
    card: "border-teal-200 bg-teal-50/40 hover:bg-teal-50 has-data-[state=checked]:border-teal-500 has-data-[state=checked]:bg-teal-50",
    icon: "text-teal-600",
    title: "text-teal-800",
    radio: "border-teal-300 text-teal-600",
  },
};

export default function NewOperationPage() {
  const { t, locale } = useI18n();
  const { settings } = useWorkspaceSettings();
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
  const [group, setGroup] = useState<OperationGroup>("incoming");
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
        inventory_adjustment: t.opInventoryAdjustment,
      };
      return map[op];
    },
    [t]
  );

  const typeDescription = useCallback(
    (op: OperationType): string => {
      const map: Record<OperationType, string> = {
        purchase: t.opPurchaseDescription,
        sale: t.opSaleDescription,
        return: t.opReturnDescription,
        write_off: t.opWriteOffDescription,
        transfer: t.opTransferDescription,
        production: t.opProductionDescription,
        defect: t.opDefectDescription,
        inventory_adjustment: t.opInventoryAdjustmentDescription,
        payment: t.opPaymentDescription,
      };
      return map[op];
    },
    [t]
  );

  const groupLabel = useCallback(
    (opGroup: OperationGroup): string => {
      const map: Record<OperationGroup, string> = {
        incoming: t.operationGroupIncoming,
        movement: t.operationGroupMovement,
        outgoing: t.operationGroupOutgoing,
        adjustments: t.operationGroupAdjustments,
        payments: t.operationGroupPayments,
      };
      return map[opGroup];
    },
    [t]
  );

  const handleGroupChange = (value: string) => {
    const nextGroup = value as OperationGroup;
    const nextConfig = OPERATION_GROUPS.find((item) => item.id === nextGroup);
    setGroup(nextGroup);
    if (nextConfig) setType(nextConfig.types[0]);
  };

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
        case "inventory_adjustment":
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
    type === "inventory_adjustment" ||
    type === "sale" ||
    type === "return" ||
    type === "write_off";
  const needsPrice = type === "purchase" || type === "inventory_adjustment";
  const activeGroup = OPERATION_GROUPS.find((item) => item.id === group)!;
  const currencySymbol =
    new Intl.NumberFormat(locale === "ru" ? "ru-RU" : "en-US", {
      style: "currency",
      currency: settings.currency,
    })
      .formatToParts(0)
      .find((part) => part.type === "currency")?.value ?? settings.currency;

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.newOperation}</h1>

      {/* Operation group and type selector */}
      <Tabs
        value={group}
        onValueChange={handleGroupChange}
        className="mb-6"
      >
        <TabsList className="flex flex-wrap h-auto gap-1">
          {OPERATION_GROUPS.map((opGroup) => (
            <TabsTrigger
              key={opGroup.id}
              value={opGroup.id}
              className={`border text-xs ${OPERATION_GROUP_STYLES[opGroup.id]}`}
            >
              {groupLabel(opGroup.id)}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>

      <RadioGroup
        value={type}
        onValueChange={(value) => setType(value as OperationType)}
        className="mb-6 grid gap-3 sm:grid-cols-2 xl:grid-cols-4"
      >
        {activeGroup.types.map((op) => {
          const Icon = OPERATION_ICONS[op];
          const style = OPERATION_STYLES[op];
          return (
            <Label
              key={op}
              htmlFor={`operation-type-${op}`}
              className="cursor-pointer"
            >
              <div
                className={`flex min-h-24 w-full items-start gap-3 rounded-md border p-4 transition-colors ${style.card}`}
              >
                <Icon className={`mt-0.5 h-5 w-5 shrink-0 ${style.icon}`} />
                <div className="min-w-0 flex-1">
                  <div className={`text-sm font-medium leading-tight ${style.title}`}>
                    {typeLabel(op)}
                  </div>
                  <div className="mt-1 text-sm font-normal leading-snug text-muted-foreground">
                    {typeDescription(op)}
                  </div>
                </div>
                <RadioGroupItem
                  id={`operation-type-${op}`}
                  value={op}
                  className={style.radio}
                />
              </div>
            </Label>
          );
        })}
      </RadioGroup>

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
            <div className="relative">
              <Input
                type="number"
                step="1"
                value={paymentAmount}
                onChange={(e) => setPaymentAmount(e.target.value)}
                className="pr-10"
              />
              <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-sm text-muted-foreground">
                {currencySymbol}
              </span>
            </div>
          </Field>
        )}

        {/* Items list (purchase, sale, return, write_off) */}
        {needsItems && (
          <div>
            <div className="mb-2 flex items-center justify-between">
              <Label className="text-base font-semibold">
                {t.items}
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
                      step="1"
                      value={item.quantity || ""}
                      onChange={(e) =>
                        updateItem(i, "quantity", parseFloat(e.target.value) || 0)
                      }
                    />
                  </Field>
                  {needsPrice && (
                    <Field className="w-28">
                      <FieldLabel className="text-xs">
                        {type === "inventory_adjustment" ? t.unitCost : t.price}
                      </FieldLabel>
                      <div className="relative">
                        <Input
                          type="number"
                          step="1"
                          value={item.unitPrice || ""}
                          onChange={(e) =>
                            updateItem(
                              i,
                              "unitPrice",
                              parseFloat(e.target.value) || 0
                            )
                          }
                          className="pr-9"
                        />
                        <span className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-xs text-muted-foreground">
                          {currencySymbol}
                        </span>
                      </div>
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
                step="1"
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
                step="1"
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
                  {t.sourceMaterials} ({t.directionOut})
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
                        step="1"
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
                {t.outputProduct} ({t.directionIn})
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
                      step="1"
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
