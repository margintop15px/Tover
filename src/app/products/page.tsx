"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldLabel } from "@/components/ui/field";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import DataTable from "@/components/DataTable";
import type { Product, Category, Store } from "@/types/inventory";
import { Pencil, Trash2, Plus } from "lucide-react";

export default function ProductsPage() {
  const { t } = useI18n();
  const { settings } = useWorkspaceSettings();
  const [items, setItems] = useState<Product[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Product | null>(null);
  const [name, setName] = useState("");
  const [skuCode, setSkuCode] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [storeId, setStoreId] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  // Filters
  const [search, setSearch] = useState("");
  const [filterCategory, setFilterCategory] = useState("");
  const [filterStore, setFilterStore] = useState("");

  const fetchReferenceData = useCallback(async () => {
    const [catRes, storeRes] = await Promise.all([
      fetch("/api/categories?limit=200"),
      fetch("/api/stores?limit=200"),
    ]);
    const catData = await catRes.json();
    const storeData = await storeRes.json();
    setCategories(catData.items || []);
    setStores(storeData.items || []);
  }, []);

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ limit: "200" });
      if (search) params.set("search", search);
      if (filterCategory) params.set("categoryId", filterCategory);
      if (filterStore) params.set("storeId", filterStore);
      const res = await fetch(`/api/products?${params}`);
      const data = await res.json();
      setItems(data.items || []);
    } finally {
      setLoading(false);
    }
  }, [search, filterCategory, filterStore]);

  useEffect(() => {
    fetchReferenceData();
  }, [fetchReferenceData]);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  const openCreate = () => {
    setEditing(null);
    setName("");
    setSkuCode("");
    setCategoryId("");
    setStoreId("");
    setError("");
    setDialogOpen(true);
  };

  const openEdit = (item: Product) => {
    setEditing(item);
    setName(item.name);
    setSkuCode(item.skuCode || "");
    setCategoryId(item.categoryId || "");
    setStoreId(item.storeId || "");
    setError("");
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    setError("");
    try {
      const url = editing
        ? `/api/products/${editing.id}`
        : "/api/products";
      const res = await fetch(url, {
        method: editing ? "PATCH" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: name.trim(),
          skuCode: skuCode.trim() || undefined,
          categoryId: categoryId || undefined,
          storeId: storeId || undefined,
        }),
      });
      if (!res.ok) {
        const data = await res.json();
        if (res.status === 409) {
          setError(
            data.field === "sku" ? t.duplicateSkuError : t.duplicateNameError
          );
        } else {
          setError(data.error || t.unexpectedError);
        }
        return;
      }
      setDialogOpen(false);
      fetchItems();
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (item: Product) => {
    if (!confirm(t.confirmDelete)) return;
    await fetch(`/api/products/${item.id}`, { method: "DELETE" });
    fetchItems();
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t.productsTitle}</h1>
        <Button onClick={openCreate} className="gap-2">
          <Plus className="h-4 w-4" />
          {t.newProduct}
        </Button>
      </div>

      {/* Filters */}
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <Input
          placeholder={t.searchProducts}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-64"
        />
        <Select
          value={filterCategory}
          onValueChange={(v) => setFilterCategory(v === "all" ? "" : v)}
        >
          <SelectTrigger className="w-48">
            <SelectValue placeholder={t.allCategories} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t.allCategories}</SelectItem>
            {categories.map((c) => (
              <SelectItem key={c.id} value={c.id}>
                {c.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Select
          value={filterStore}
          onValueChange={(v) => setFilterStore(v === "all" ? "" : v)}
        >
          <SelectTrigger className="w-48">
            <SelectValue placeholder={t.allStores} />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">{t.allStores}</SelectItem>
            {stores.map((s) => (
              <SelectItem key={s.id} value={s.id}>
                {s.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<Product & Record<string, unknown>>
          tableId="products"
          columns={[
            { key: "name", header: t.productName, required: true },
            { key: "skuCode", header: t.productSku, render: (item) => item.skuCode || "-" },
            {
              key: "categoryName",
              header: t.productCategory,
              render: (item) => item.categoryName || "-",
            },
            {
              key: "storeName",
              header: t.productStore,
              render: (item) => item.storeName || "-",
            },
            {
              key: "actions",
              header: t.actions,
              className: "w-24",
              render: (item) => (
                <div className="flex gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={(e) => {
                      e.stopPropagation();
                      openEdit(item);
                    }}
                  >
                    <Pencil className="h-4 w-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={(e) => {
                      e.stopPropagation();
                      handleDelete(item);
                    }}
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </div>
              ),
            },
          ]}
          data={items as (Product & Record<string, unknown>)[]}
          emptyMessage={t.noProducts}
        />
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editing ? t.editProduct : t.newProduct}
            </DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <Field>
              <FieldLabel htmlFor="prod-name">{t.productName}</FieldLabel>
              <Input
                id="prod-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoFocus
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="prod-sku">{t.productSku}</FieldLabel>
              <Input
                id="prod-sku"
                value={skuCode}
                onChange={(e) => setSkuCode(e.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel>
                {t.productCategory}
                {settings.categoryRequired && <span className="ml-1 text-destructive">*</span>}
              </FieldLabel>
              <Select value={categoryId} onValueChange={(v) => setCategoryId(v === "none" ? "" : v)}>
                <SelectTrigger>
                  <SelectValue placeholder={t.purposeNone} />
                </SelectTrigger>
                <SelectContent>
                  {!settings.categoryRequired && (
                    <SelectItem value="none">{t.purposeNone}</SelectItem>
                  )}
                  {categories.map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            <Field>
              <FieldLabel>
                {t.productStore}
                {settings.storeRequired && <span className="ml-1 text-destructive">*</span>}
              </FieldLabel>
              <Select value={storeId} onValueChange={(v) => setStoreId(v === "none" ? "" : v)}>
                <SelectTrigger>
                  <SelectValue placeholder={t.purposeNone} />
                </SelectTrigger>
                <SelectContent>
                  {!settings.storeRequired && (
                    <SelectItem value="none">{t.purposeNone}</SelectItem>
                  )}
                  {stores.map((s) => (
                    <SelectItem key={s.id} value={s.id}>
                      {s.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
            {error && (
              <p className="text-sm text-destructive">{error}</p>
            )}
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                onClick={() => setDialogOpen(false)}
              >
                {t.cancel}
              </Button>
              <Button onClick={handleSave} disabled={saving || !name.trim()}>
                {saving ? t.saving : t.save}
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
