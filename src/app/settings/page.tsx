"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  AlertTriangle,
  CheckCircle2,
  PlugZap,
  ShoppingBag,
  ShieldCheck,
  Trash2,
  Unplug,
} from "lucide-react";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
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
import {
  formatOzonDateTime,
  ozonStatusLabel,
  type OzonIntegrationSummary,
} from "@/components/ozon/OzonSummaryShared";
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

interface ResetDataSummary {
  canReset: boolean;
  role: string;
  confirmation: "RESET";
  total: number;
  groups: {
    operations: number;
    imports: number;
    reports: number;
    masterData: number;
    balances: number;
    legacyCommerce: number;
    marketplace: number;
  };
  counts: Record<string, number>;
}

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

  // Integrations tab state
  const [ozonSummary, setOzonSummary] =
    useState<OzonIntegrationSummary | null>(null);
  const [ozonClientId, setOzonClientId] = useState("");
  const [ozonApiKey, setOzonApiKey] = useState("");
  const [ozonLoading, setOzonLoading] = useState(false);
  const [ozonSaving, setOzonSaving] = useState(false);
  const [ozonValidating, setOzonValidating] = useState(false);
  const [ozonDisconnecting, setOzonDisconnecting] = useState(false);
  const [ozonError, setOzonError] = useState("");
  const [ozonSuccess, setOzonSuccess] = useState("");

  // Shared state
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [resetSummary, setResetSummary] = useState<ResetDataSummary | null>(
    null
  );
  const [resetSummaryLoading, setResetSummaryLoading] = useState(false);
  const [resetDialogOpen, setResetDialogOpen] = useState(false);
  const [resetConfirmation, setResetConfirmation] = useState("");
  const [resettingAccountData, setResettingAccountData] = useState(false);
  const [resetError, setResetError] = useState("");
  const [resetSuccess, setResetSuccess] = useState("");

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

  const fetchOzonSummary = useCallback(async (showLoading = true) => {
    if (showLoading) setOzonLoading(true);
    try {
      const res = await fetch(`/api/integrations/ozon?t=${Date.now()}`, {
        cache: "no-store",
      });
      const data = await res.json();
      if (!res.ok) {
        setOzonError(data.error || t.unexpectedError);
        return;
      }
      setOzonSummary(data);
    } finally {
      if (showLoading) setOzonLoading(false);
    }
  }, [t.unexpectedError]);

  useEffect(() => {
    fetchOzonSummary();
  }, [fetchOzonSummary]);

  const fetchResetSummary = useCallback(async () => {
    setResetSummaryLoading(true);
    try {
      const res = await fetch(`/api/settings/reset-data?t=${Date.now()}`, {
        cache: "no-store",
      });
      const data = await res.json();
      if (!res.ok) {
        setResetError(data.error || t.unexpectedError);
        return;
      }
      setResetSummary(data);
    } finally {
      setResetSummaryLoading(false);
    }
  }, [t.unexpectedError]);

  useEffect(() => {
    fetchResetSummary();
  }, [fetchResetSummary]);

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
    setOzonError("");
    setOzonSuccess("");
    setResetError("");
    setResetSuccess("");
  };

  const openResetDialog = () => {
    setResetConfirmation("");
    setResetError("");
    setResetDialogOpen(true);
    fetchResetSummary();
  };

  const resetAccountData = async () => {
    setResettingAccountData(true);
    setResetError("");
    setResetSuccess("");
    try {
      const res = await fetch("/api/settings/reset-data", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ confirmation: resetConfirmation }),
      });
      const data = await res.json();
      if (!res.ok) {
        setResetError(data.error || t.unexpectedError);
        return;
      }
      setResetDialogOpen(false);
      setResetConfirmation("");
      setResetSuccess(t.resetAccountDataSuccess);
      await Promise.all([
        fetchReferenceData(),
        fetchResetSummary(),
        fetchOzonSummary(false),
        refetch(),
      ]);
    } finally {
      setResettingAccountData(false);
    }
  };

  const saveOzonConnection = async () => {
    setOzonSaving(true);
    setOzonError("");
    setOzonSuccess("");
    try {
      const res = await fetch("/api/integrations/ozon", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          clientId: ozonClientId,
          apiKey: ozonApiKey,
        }),
      });
      const data = await res.json();
      if (data.connection || data.counts) {
        setOzonSummary(data);
      }
      if (!res.ok) {
        setOzonError(data.error || t.unexpectedError);
        return;
      }
      setOzonClientId("");
      setOzonApiKey("");
      setOzonSuccess(t.ozonConnectedMessage);
    } finally {
      setOzonSaving(false);
    }
  };

  const validateOzonConnection = async () => {
    setOzonValidating(true);
    setOzonError("");
    setOzonSuccess("");
    try {
      const res = await fetch("/api/integrations/ozon/validate", {
        method: "POST",
      });
      const data = await res.json();
      if (!res.ok) {
        setOzonError(data.error || t.unexpectedError);
        await fetchOzonSummary();
        return;
      }
      setOzonSuccess(t.ozonValidatedMessage);
      await fetchOzonSummary();
    } finally {
      setOzonValidating(false);
    }
  };

  const disconnectOzonConnection = async () => {
    setOzonDisconnecting(true);
    setOzonError("");
    setOzonSuccess("");
    try {
      const res = await fetch("/api/integrations/ozon", {
        method: "DELETE",
      });
      const data = await res.json();
      if (data.connection || data.counts) {
        setOzonSummary(data);
      }
      if (!res.ok) {
        setOzonError(data.error || t.unexpectedError);
        return;
      }
      setOzonSuccess(t.ozonDisconnectedMessage);
    } finally {
      setOzonDisconnecting(false);
    }
  };

  const resetGroupCards = resetSummary
    ? [
        {
          label: t.resetDataGroupOperations,
          value: resetSummary.groups.operations,
        },
        { label: t.resetDataGroupImports, value: resetSummary.groups.imports },
        { label: t.resetDataGroupReports, value: resetSummary.groups.reports },
        {
          label: t.resetDataGroupMasterData,
          value: resetSummary.groups.masterData,
        },
        { label: t.resetDataGroupBalances, value: resetSummary.groups.balances },
        {
          label: t.resetDataGroupLegacyCommerce,
          value: resetSummary.groups.legacyCommerce,
        },
        {
          label: t.resetDataGroupMarketplace,
          value: resetSummary.groups.marketplace,
        },
      ]
    : [];

  return (
    <div className="p-6">
      <h1 className="mb-6 text-2xl font-bold">{t.settings}</h1>

      <Tabs defaultValue={defaultTab} onValueChange={clearMessages}>
        <TabsList>
          <TabsTrigger value="general">{t.settingsGeneral}</TabsTrigger>
          <TabsTrigger value="products">{t.settingsProducts}</TabsTrigger>
          <TabsTrigger value="integrations">{t.settingsIntegrations}</TabsTrigger>
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

          <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="space-y-1">
                <h2 className="text-base font-semibold text-destructive">
                  {t.dangerZone}
                </h2>
                <p className="text-sm text-muted-foreground">
                  {t.resetAccountDataDescription}
                </p>
              </div>
              <Button
                variant="destructive"
                onClick={openResetDialog}
                disabled={!resetSummary?.canReset || resetSummaryLoading}
              >
                <Trash2 className="h-4 w-4" />
                {t.removeAllAccountData}
              </Button>
            </div>

            {resetSummaryLoading ? (
              <p className="mt-3 text-sm text-muted-foreground">{t.loading}</p>
            ) : resetSummary ? (
              <div className="mt-4 space-y-3">
                <div className="text-sm text-muted-foreground">
                  {resetSummary.total > 0
                    ? t.resetAccountDataCount(resetSummary.total)
                    : t.resetAccountDataNothingToDelete}
                </div>
                <div className="grid gap-2 sm:grid-cols-2">
                  {resetGroupCards.map((item) => (
                    <div
                      key={item.label}
                      className="rounded-md border bg-background/70 px-3 py-2"
                    >
                      <div className="text-xs text-muted-foreground">
                        {item.label}
                      </div>
                      <div className="text-sm font-semibold">{item.value}</div>
                    </div>
                  ))}
                </div>
                {!resetSummary.canReset && (
                  <p className="text-sm text-muted-foreground">
                    {t.resetAccountDataOwnerOnly}
                  </p>
                )}
              </div>
            ) : null}

            {resetError && !resetDialogOpen && (
              <p className="mt-3 text-sm text-destructive">{resetError}</p>
            )}
            {resetSuccess && (
              <p className="mt-3 text-sm text-emerald-700">{resetSuccess}</p>
            )}
          </div>

          <Dialog open={resetDialogOpen} onOpenChange={setResetDialogOpen}>
            <DialogContent className="sm:max-w-2xl">
              <DialogHeader>
                <DialogTitle>{t.resetAccountDataDialogTitle}</DialogTitle>
                <DialogDescription>
                  {t.resetAccountDataDialogDescription}
                </DialogDescription>
              </DialogHeader>

              <div className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                <div className="flex items-start gap-2">
                  <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
                  <span>{t.resetAccountDataIrreversible}</span>
                </div>
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div className="rounded-md border p-3">
                  <div className="text-sm font-medium">
                    {t.resetAccountDataDeletedTitle}
                  </div>
                  <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
                    <li>{t.resetAccountDataDeletedOperations}</li>
                    <li>{t.resetAccountDataDeletedReports}</li>
                    <li>{t.resetAccountDataDeletedMasterData}</li>
                    <li>{t.resetAccountDataDeletedMarketplace}</li>
                    <li>{t.resetAccountDataDeletedLegacy}</li>
                  </ul>
                </div>

                <div className="rounded-md border p-3">
                  <div className="text-sm font-medium">
                    {t.resetAccountDataPreservedTitle}
                  </div>
                  <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-muted-foreground">
                    <li>{t.resetAccountDataPreservedTeam}</li>
                    <li>{t.resetAccountDataPreservedSettings}</li>
                    <li>{t.resetAccountDataPreservedIntegrations}</li>
                    <li>{t.resetAccountDataPreservedOrganization}</li>
                  </ul>
                </div>
              </div>

              {resetSummary && (
                <div className="rounded-md border bg-muted/20 p-3 text-sm">
                  {resetSummary.total > 0
                    ? t.resetAccountDataCount(resetSummary.total)
                    : t.resetAccountDataNothingToDelete}
                </div>
              )}

              <Field>
                <FieldLabel>{t.resetAccountDataTypeReset}</FieldLabel>
                <Input
                  aria-label={t.resetAccountDataTypeReset}
                  value={resetConfirmation}
                  onChange={(event) =>
                    setResetConfirmation(event.target.value)
                  }
                  autoComplete="off"
                />
              </Field>

              {resetError && (
                <p className="text-sm text-destructive">{resetError}</p>
              )}

              <DialogFooter>
                <Button
                  variant="outline"
                  onClick={() => setResetDialogOpen(false)}
                  disabled={resettingAccountData}
                >
                  {t.cancel}
                </Button>
                <Button
                  variant="destructive"
                  onClick={resetAccountData}
                  disabled={
                    resetConfirmation !== "RESET" || resettingAccountData
                  }
                >
                  <Trash2 className="h-4 w-4" />
                  {resettingAccountData
                    ? t.resettingAccountData
                    : t.removeAllAccountData}
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
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

        {/* Integrations tab */}
        <TabsContent value="integrations" className="mt-6 max-w-3xl space-y-6">
          <div className="rounded-lg border p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <h2 className="text-base font-semibold">
                    {t.ozonIntegrationTitle}
                  </h2>
                  {ozonSummary?.connection && (
                    <Badge
                      variant={
                        ozonSummary.connection.status === "connected"
                          ? "default"
                          : ozonSummary.connection.status === "invalid" ||
                              ozonSummary.connection.status === "error"
                            ? "destructive"
                            : "secondary"
                      }
                    >
                      {ozonStatusLabel(ozonSummary.connection.status, t)}
                    </Badge>
                  )}
                </div>
                <p className="text-sm text-muted-foreground">
                  {t.ozonIntegrationDescription}
                </p>
              </div>

              {ozonSummary?.connection && (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={disconnectOzonConnection}
                  disabled={ozonDisconnecting}
                >
                  <Unplug className="h-4 w-4" />
                  {t.ozonDisconnect}
                </Button>
              )}
            </div>

            <div className="mt-5 grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t.ozonClientId}</FieldLabel>
                <Input
                  value={ozonClientId}
                  onChange={(event) => setOzonClientId(event.target.value)}
                  placeholder={ozonSummary?.connection?.clientIdHint || ""}
                  autoComplete="off"
                />
              </Field>
              <Field>
                <FieldLabel>{t.ozonApiKey}</FieldLabel>
                <Input
                  type="password"
                  value={ozonApiKey}
                  onChange={(event) => setOzonApiKey(event.target.value)}
                  placeholder={ozonSummary?.connection?.apiKeyHint || ""}
                  autoComplete="off"
                />
              </Field>
            </div>

            <p className="mt-3 text-xs text-muted-foreground">
              {t.ozonCredentialsHelp}
            </p>

            <div className="mt-4 flex flex-wrap gap-2">
              <Button
                onClick={saveOzonConnection}
                disabled={ozonSaving || !ozonClientId || !ozonApiKey}
              >
                <PlugZap className="h-4 w-4" />
                {ozonSummary?.connection
                  ? t.ozonUpdateCredentials
                  : t.ozonConnect}
              </Button>
              <Button
                variant="outline"
                onClick={validateOzonConnection}
                disabled={!ozonSummary?.connection || ozonValidating}
              >
                <ShieldCheck className="h-4 w-4" />
                {ozonValidating ? t.validating : t.ozonValidate}
              </Button>
              {ozonSummary?.connection && !ozonSummary.setupError && (
                <Button variant="outline" asChild>
                  <Link href="/operations/marketplaces">
                    <ShoppingBag className="h-4 w-4" />
                    {t.openMarketplaces}
                  </Link>
                </Button>
              )}
            </div>

            {ozonError && (
              <div className="mt-4 flex items-center gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
                <AlertTriangle className="h-4 w-4" />
                {ozonError}
              </div>
            )}
            {ozonSuccess && (
              <div className="mt-4 flex items-center gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-700">
                <CheckCircle2 className="h-4 w-4" />
                {ozonSuccess}
              </div>
            )}
            {ozonSummary?.setupError ? (
              <p className="mt-3 text-sm text-destructive">
                {t.ozonSetupRequired}
              </p>
            ) : ozonLoading ? (
              <p className="mt-3 text-sm text-muted-foreground">{t.loading}</p>
            ) : !ozonSummary?.connection ? (
              <p className="mt-4 text-sm text-muted-foreground">
                {t.ozonNoConnection}
              </p>
            ) : null}

            {ozonSummary?.connection && (
              <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-muted-foreground">{t.ozonLastValidated}</dt>
                  <dd>
                    {formatOzonDateTime(ozonSummary.connection.lastValidatedAt)}
                  </dd>
                </div>
              </dl>
            )}
          </div>
        </TabsContent>

        {/* Team tab */}
        <TabsContent value="team" className="mt-6 max-w-lg">
          <InviteForm />
        </TabsContent>
      </Tabs>
    </div>
  );
}
