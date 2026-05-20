"use client";

import { useCallback, useEffect, useState } from "react";
import { useI18n } from "@/i18n/context";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Field, FieldLabel } from "@/components/ui/field";
import { Textarea } from "@/components/ui/textarea";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import DataTable from "@/components/DataTable";
import ImportDefaultField from "@/components/ImportDefaultField";
import type { Supplier } from "@/types/inventory";
import { Pencil, Trash2, Plus } from "lucide-react";

export default function SuppliersPage() {
  const { t } = useI18n();
  const [items, setItems] = useState<Supplier[]>([]);
  const [loading, setLoading] = useState(true);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<Supplier | null>(null);
  const [name, setName] = useState("");
  const [address, setAddress] = useState("");
  const [contactInfo, setContactInfo] = useState("");
  const [isImportDefault, setIsImportDefault] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const fetchItems = useCallback(async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/suppliers?limit=200");
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
    setAddress("");
    setContactInfo("");
    setIsImportDefault(false);
    setError("");
    setDialogOpen(true);
  };

  const openEdit = (item: Supplier) => {
    setEditing(item);
    setName(item.name);
    setAddress(item.address || "");
    setContactInfo(item.contactInfo || "");
    setIsImportDefault(item.isImportDefault);
    setError("");
    setDialogOpen(true);
  };

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    setError("");
    try {
      const url = editing
        ? `/api/suppliers/${editing.id}`
        : "/api/suppliers";
      const res = await fetch(url, {
        method: editing ? "PATCH" : "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: name.trim(),
          address: address.trim() || undefined,
          contactInfo: contactInfo.trim() || undefined,
          isImportDefault,
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

  const handleDelete = async (item: Supplier) => {
    if (!confirm(t.confirmDelete)) return;
    await fetch(`/api/suppliers/${item.id}`, { method: "DELETE" });
    fetchItems();
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-bold">{t.suppliersTitle}</h1>
        <Button onClick={openCreate} className="gap-2">
          <Plus className="h-4 w-4" />
          {t.newSupplier}
        </Button>
      </div>

      {loading ? (
        <p className="text-muted-foreground">{t.loading}</p>
      ) : (
        <DataTable<Supplier & Record<string, unknown>>
          tableId="suppliers"
          columns={[
            { key: "name", header: t.name, required: true },
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
              key: "address",
              header: t.supplierAddress,
              render: (item) => item.address || "-",
            },
            {
              key: "contactInfo",
              header: t.supplierContactInfo,
              render: (item) => item.contactInfo || "-",
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
          data={items as (Supplier & Record<string, unknown>)[]}
          emptyMessage={t.noSuppliers}
        />
      )}

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {editing ? t.editSupplier : t.newSupplier}
            </DialogTitle>
          </DialogHeader>
          <div className="space-y-4">
            <Field>
              <FieldLabel htmlFor="sup-name">{t.supplierName}</FieldLabel>
              <Input
                id="sup-name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                autoFocus
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="sup-addr">{t.supplierAddress}</FieldLabel>
              <Input
                id="sup-addr"
                value={address}
                onChange={(e) => setAddress(e.target.value)}
              />
            </Field>
            <Field>
              <FieldLabel htmlFor="sup-contact">{t.supplierContactInfo}</FieldLabel>
              <Textarea
                id="sup-contact"
                value={contactInfo}
                onChange={(e) => setContactInfo(e.target.value)}
                rows={2}
              />
            </Field>
            <ImportDefaultField
              checked={isImportDefault}
              entityLabel={t.supplierEntity}
              onCheckedChange={setIsImportDefault}
            />
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
