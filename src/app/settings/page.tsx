"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { Button } from "@/components/ui/button";
import { Field, FieldLabel } from "@/components/ui/field";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import InviteForm from "@/components/InviteForm";
import type { Category, Store } from "@/types/inventory";

const CURRENCIES = [
  "EUR",
  "USD",
  "GBP",
  "RUB",
  "PLN",
  "UAH",
  "TRY",
  "CNY",
  "JPY",
  "KRW",
  "BRL",
  "INR",
  "CAD",
  "AUD",
  "CHF",
];

export default function SettingsPage() {
  return (
    <Suspense>
      <SettingsPageContent />
    </Suspense>
  );
}

function SettingsPageContent() {
  const { t } = useI18n();
  const searchParams = useSearchParams();
  const { settings, refetch } = useWorkspaceSettings();

  const defaultTab = searchParams.get("tab") || "general";

  // General tab state
  const [currency, setCurrency] = useState(settings.currency);

  // Products tab state
  const [categoryRequired, setCategoryRequired] = useState(
    settings.categoryRequired
  );
  const [storeRequired, setStoreRequired] = useState(settings.storeRequired);
  const [defaultCategoryId, setDefaultCategoryId] = useState(
    settings.defaultCategoryId || ""
  );
  const [defaultStoreId, setDefaultStoreId] = useState(
    settings.defaultStoreId || ""
  );
  const [categories, setCategories] = useState<Category[]>([]);
  const [stores, setStores] = useState<Store[]>([]);

  // Shared state
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  // Sync state when settings load/change
  useEffect(() => {
    setCurrency(settings.currency);
    setCategoryRequired(settings.categoryRequired);
    setStoreRequired(settings.storeRequired);
    setDefaultCategoryId(settings.defaultCategoryId || "");
    setDefaultStoreId(settings.defaultStoreId || "");
  }, [settings]);

  // Load reference data for Products tab
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

  useEffect(() => {
    fetchReferenceData();
  }, [fetchReferenceData]);

  const saveGeneral = async () => {
    setSaving(true);
    setError("");
    setSuccess("");
    try {
      const res = await fetch("/api/settings", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ currency }),
      });
      if (!res.ok) {
        const data = await res.json();
        setError(data.error || t.unexpectedError);
        return;
      }
      refetch();
      setSuccess(t.settingsSaved);
    } finally {
      setSaving(false);
    }
  };

  const saveProducts = async () => {
    setSaving(true);
    setError("");
    setSuccess("");
    try {
      const payload: Record<string, unknown> = {
        categoryRequired,
        storeRequired,
      };

      if (categoryRequired) {
        payload.defaultCategoryId = defaultCategoryId || undefined;
      }
      if (storeRequired) {
        payload.defaultStoreId = defaultStoreId || undefined;
      }

      const res = await fetch("/api/settings", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        const data = await res.json();
        setError(data.error || t.unexpectedError);
        return;
      }
      refetch();
      setSuccess(t.settingsSaved);
    } finally {
      setSaving(false);
    }
  };

  const clearMessages = () => {
    setError("");
    setSuccess("");
  };

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.settings}</h1>

      <Tabs defaultValue={defaultTab} onValueChange={clearMessages}>
        <TabsList>
          <TabsTrigger value="general">{t.settingsGeneral}</TabsTrigger>
          <TabsTrigger value="products">{t.settingsProducts}</TabsTrigger>
          <TabsTrigger value="team">{t.settingsTeam}</TabsTrigger>
        </TabsList>

        {/* General tab */}
        <TabsContent value="general" className="mt-6 max-w-lg space-y-6">
          <Field>
            <FieldLabel>{t.currency}</FieldLabel>
            <Select value={currency} onValueChange={setCurrency}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {CURRENCIES.map((c) => (
                  <SelectItem key={c} value={c}>
                    {c}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="mt-1 text-xs text-muted-foreground">
              {t.currencyDisplayNote}
            </p>
          </Field>

          {error && <p className="text-sm text-destructive">{error}</p>}
          {success && <p className="text-sm text-emerald-700">{success}</p>}

          <Button onClick={saveGeneral} disabled={saving}>
            {saving ? t.saving : t.save}
          </Button>
        </TabsContent>

        {/* Products tab */}
        <TabsContent value="products" className="mt-6 max-w-lg space-y-6">
          {/* Category required */}
          <div className="space-y-3 rounded-lg border p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">
                  {t.categoryRequiredLabel}
                </p>
                <p className="text-xs text-muted-foreground">
                  {t.categoryRequiredHelp}
                </p>
              </div>
              <Switch
                checked={categoryRequired}
                onCheckedChange={setCategoryRequired}
              />
            </div>

            {categoryRequired && !settings.categoryRequired && (
              <Field>
                <FieldLabel>{t.defaultCategory}</FieldLabel>
                <Select
                  value={defaultCategoryId}
                  onValueChange={setDefaultCategoryId}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.selectCategory} />
                  </SelectTrigger>
                  <SelectContent>
                    {categories.map((c) => (
                      <SelectItem key={c.id} value={c.id}>
                        {c.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="mt-1 text-xs text-muted-foreground">
                  {t.backfillWarning(t.productCategory.toLowerCase())}
                </p>
              </Field>
            )}
          </div>

          {/* Store required */}
          <div className="space-y-3 rounded-lg border p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm font-medium">{t.storeRequiredLabel}</p>
                <p className="text-xs text-muted-foreground">
                  {t.storeRequiredHelp}
                </p>
              </div>
              <Switch
                checked={storeRequired}
                onCheckedChange={setStoreRequired}
              />
            </div>

            {storeRequired && !settings.storeRequired && (
              <Field>
                <FieldLabel>{t.defaultStore}</FieldLabel>
                <Select
                  value={defaultStoreId}
                  onValueChange={setDefaultStoreId}
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.selectStore} />
                  </SelectTrigger>
                  <SelectContent>
                    {stores.map((s) => (
                      <SelectItem key={s.id} value={s.id}>
                        {s.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="mt-1 text-xs text-muted-foreground">
                  {t.backfillWarning(t.productStore.toLowerCase())}
                </p>
              </Field>
            )}
          </div>

          {error && <p className="text-sm text-destructive">{error}</p>}
          {success && <p className="text-sm text-emerald-700">{success}</p>}

          <Button onClick={saveProducts} disabled={saving}>
            {saving ? t.saving : t.save}
          </Button>
        </TabsContent>

        {/* Team tab */}
        <TabsContent value="team" className="mt-6 max-w-lg">
          <InviteForm />
        </TabsContent>
      </Tabs>
    </div>
  );
}
