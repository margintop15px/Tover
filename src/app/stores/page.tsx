"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldLabel } from "@/components/ui/field";
import { Badge } from "@/components/ui/badge";
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
import ImportDefaultField from "@/components/ImportDefaultField";
import type { Store } from "@/types/inventory";
import { Pencil, Trash2, Plus } from "lucide-react";

export default function StoresPage() {
  const { t } = useI18n();
  const [items, setItems] = useState<Store[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Store | null>(null);
  const [name, setName] = useState("");
  const [defaultWarehouseId, setDefaultWarehouseId] = useState("");
  const [isImportDefault, setIsImportDefault] = useState(false);
  const [warehouses, setWarehouses] = useState<{ id: string; name: string }[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/stores?limit=200");
      const data = await res.json();
      setItems(data.items || []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchItems();
    fetch("/api/warehouses?limit=200")
      .then((res) => res.json())
      .then((data) => {
        setWarehouses(
          (data.items || []).map((w: { id: string; name: string }) => ({
            id: w.id,
            name: w.name,
          }))
        );
      });
  }, [fetchItems]);

  const openCreate = () => {
    setEditing(null);
    setName("");
    setDefaultWarehouseId("");
    setIsImportDefault(false);
    setError("");
    setDialogOpen(true);
  };

  const openEdit = (item: Store) => {
    setEditing(item);
    setName(item.name);
    setDefaultWarehouseId(item.defaultWarehouseId || "");
    setIsImportDefault(item.isImportDefault);
    setError("");
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    setError("");
    try {
      const url = editing ? `/api/stores/${editing.id}` : "/api/stores";
      const res = await fetch(url, {
        method: editing ? "PATCH" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: name.trim(),
          defaultWarehouseId: defaultWarehouseId || null,
          isImportDefault,
        }),
      });
      if (!res.ok) {
        const data = await res.json();
        setError(
          res.status === 409 ? t.duplicateError : data.error || t.unexpectedError
        );
        return;
      }
      setDialogOpen(false);
      fetchItems();
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (item: Store) => {
    if (!confirm(t.confirmDelete)) return;
    await fetch(`/api/stores/${item.id}`, { method: "DELETE" });
    fetchItems();
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t.storesTitle}</h1>
        <Button onClick={openCreate} className="gap-2">
          <Plus className="h-4 w-4" />
          {t.newStore}
        </Button>
      </div>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<Store & Record<string, unknown>>
          columns={[
            { key: "name", header: t.name, required: true },
            {
              key: "defaultWarehouseName",
              header: t.warehouse,
              render: (item) => item.defaultWarehouseName || "-",
            },
            {
              key: "isImportDefault",
              header: t.importDefault,
              className: "w-36",
              render: (item) =>
                item.isImportDefault ? (
                  <Badge variant="secondary">{t.importDefault}</Badge>
                ) : null,
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
          data={items as (Store & Record<string, unknown>)[]}
          emptyMessage={t.noStores}
        />
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{editing ? t.editStore : t.newStore}</DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <Field>
              <FieldLabel htmlFor="name">{t.storeName}</FieldLabel>
              <Input
                id="name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && handleSave()}
                autoFocus
              />
            </Field>
            <ImportDefaultField
              checked={isImportDefault}
              entityLabel={t.storeEntity}
              onCheckedChange={setIsImportDefault}
            />
            <Field>
              <FieldLabel>{t.warehouse}</FieldLabel>
              <Select
                value={defaultWarehouseId}
                onValueChange={(value) =>
                  setDefaultWarehouseId(value === "none" ? "" : value)
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder={t.allWarehouses} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">-</SelectItem>
                  {warehouses.map((warehouse) => (
                    <SelectItem key={warehouse.id} value={warehouse.id}>
                      {warehouse.name}
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
