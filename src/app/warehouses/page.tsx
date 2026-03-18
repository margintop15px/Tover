"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
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
import type { Warehouse, WarehousePurpose } from "@/types/inventory";
import { Pencil, Trash2, Plus } from "lucide-react";

export default function WarehousesPage() {
  const { t } = useI18n();
  const [items, setItems] = useState<Warehouse[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Warehouse | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [purpose, setPurpose] = useState<WarehousePurpose | "">("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/warehouses?limit=200");
      const data = await res.json();
      setItems(data.items || []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchItems();
  }, [fetchItems]);

  const openCreate = () => {
    setEditing(null);
    setName("");
    setDescription("");
    setPurpose("");
    setError("");
    setDialogOpen(true);
  };

  const openEdit = (item: Warehouse) => {
    setEditing(item);
    setName(item.name);
    setDescription(item.description || "");
    setPurpose(item.purpose || "");
    setError("");
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    setError("");
    try {
      const url = editing
        ? `/api/warehouses/${editing.id}`
        : "/api/warehouses";
      const res = await fetch(url, {
        method: editing ? "PATCH" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: name.trim(),
          description: description.trim() || undefined,
          purpose: purpose || undefined,
        }),
      });
      if (!res.ok) {
        const data = await res.json();
        setError(
          res.status === 409
            ? t.duplicateError
            : data.error || t.unexpectedError
        );
        return;
      }
      setDialogOpen(false);
      fetchItems();
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (item: Warehouse) => {
    if (item.isDefaultDefect) {
      alert(t.cannotDeleteDefect);
      return;
    }
    if (!confirm(t.confirmDelete)) return;
    await fetch(`/api/warehouses/${item.id}`, { method: "DELETE" });
    fetchItems();
  };

  const purposeLabel = (p: WarehousePurpose | null) => {
    if (!p) return t.purposeNone;
    const map: Record<WarehousePurpose, string> = {
      storage: t.purposeStorage,
      sales: t.purposeSales,
      production: t.purposeProduction,
    };
    return map[p];
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t.warehousesTitle}</h1>
        <Button onClick={openCreate} className="gap-2">
          <Plus className="h-4 w-4" />
          {t.newWarehouse}
        </Button>
      </div>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<Warehouse & Record<string, unknown>>
          tableId="warehouses"
          columns={[
            { key: "name", header: t.name, required: true },
            {
              key: "purpose",
              header: t.warehousePurpose,
              defaultVisible: true,
              render: (item) => purposeLabel(item.purpose),
            },
            {
              key: "isDefaultDefect",
              header: t.defaultDefect,
              className: "w-32",
              render: (item) =>
                item.isDefaultDefect ? (
                  <Badge variant="secondary">{t.defaultDefect}</Badge>
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
                    disabled={item.isDefaultDefect}
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
          data={items as (Warehouse & Record<string, unknown>)[]}
          emptyMessage={t.noWarehouses}
        />
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editing ? t.editWarehouse : t.newWarehouse}
            </DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <Field>
              <FieldLabel htmlFor="wh-name">{t.warehouseName}</FieldLabel>
              <Input
                id="wh-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoFocus
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="wh-desc">{t.warehouseDescription}</FieldLabel>
              <Textarea
                id="wh-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={2}
              />
            </Field>
            <Field>
              <FieldLabel>{t.warehousePurpose}</FieldLabel>
              <Select
                value={purpose}
                onValueChange={(v) =>
                  setPurpose(v as WarehousePurpose | "")
                }
              >
                <SelectTrigger>
                  <SelectValue placeholder={t.purposeNone} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="none">{t.purposeNone}</SelectItem>
                  <SelectItem value="storage">{t.purposeStorage}</SelectItem>
                  <SelectItem value="sales">{t.purposeSales}</SelectItem>
                  <SelectItem value="production">
                    {t.purposeProduction}
                  </SelectItem>
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
