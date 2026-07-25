"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  CheckCircle2,
  ListChecks,
  PlugZap,
  RefreshCw,
} from "lucide-react";
import { useI18n } from "@/i18n/context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  formatOzonDateTime,
  OzonMetric,
  ozonStatusLabel,
  ozonSyncStatusLabel,
  type OzonIntegrationSummary,
} from "@/components/ozon/OzonSummaryShared";
import {
  getOzonRecoveryAction,
  getOzonRecoveryRequest,
  isOzonRecoveryActive,
  startOzonSummaryPolling,
} from "@/components/ozon/OzonRecoveryUi";

async function requestOzonSummary(signal?: AbortSignal) {
  const response = await fetch(`/api/integrations/ozon?t=${Date.now()}`, {
    cache: "no-store",
    signal,
  });
  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new Error();
  }
  if (!response.ok) {
    const message =
      data &&
      typeof data === "object" &&
      "error" in data &&
      typeof data.error === "string"
        ? data.error
        : "";
    throw new Error(message);
  }
  return data as OzonIntegrationSummary;
}

export default function MarketplacesPage() {
  const { t } = useI18n();
  const [ozonSummary, setOzonSummary] =
    useState<OzonIntegrationSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  const fetchOzonSummary = useCallback(
    async (showLoading = true, showError = true) => {
      if (showLoading) setLoading(true);
      try {
        const data = await requestOzonSummary();
        setOzonSummary(data);
      } catch (fetchError) {
        if (showError) {
          setError(
            fetchError instanceof Error && fetchError.message
              ? fetchError.message
              : t.ozonSummaryLoadFailed
          );
        }
      } finally {
        if (showLoading) setLoading(false);
      }
    },
    [t.ozonSummaryLoadFailed]
  );

  useEffect(() => {
    fetchOzonSummary();
  }, [fetchOzonSummary]);

  const recoveryActive = isOzonRecoveryActive(ozonSummary);

  useEffect(() => {
    if (!recoveryActive || syncing) return;
    return startOzonSummaryPolling({
      loadSummary: requestOzonSummary,
      onSummary: setOzonSummary,
    });
  }, [recoveryActive, syncing]);

  const syncOzonConnection = async () => {
    const action = getOzonRecoveryAction(ozonSummary);
    const request = getOzonRecoveryRequest(
      action,
      ozonSummary?.recovery?.runId ?? null
    );
    setSyncing(true);
    setError("");
    setSuccess("");

    try {
      const res = await fetch(request.endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(request.body),
      });
      let data: { status?: string };
      try {
        data = await res.json();
      } catch {
        throw new Error();
      }
      if (!res.ok) {
        throw new Error();
      }
      setSuccess(data.status === "completed" ? t.ozonSyncedMessage : "");
      await fetchOzonSummary(false);
    } catch {
      setError(t.ozonSyncActionFailed);
      await fetchOzonSummary(false, false);
    } finally {
      setSyncing(false);
    }
  };

  const connection = ozonSummary?.connection;
  const recovery = ozonSummary?.recovery;
  const recoveryAction = getOzonRecoveryAction(ozonSummary);
  const persistedSyncStatus =
    recovery?.status ?? connection?.lastSyncStatus ?? null;
  const hasPartialSync =
    persistedSyncStatus === "completed_with_errors" && !syncing;
  const hasFailedSync = persistedSyncStatus === "failed" && !syncing;
  const syncActionLabel =
    recoveryAction === "resume"
      ? t.ozonRetryNow
      : recoveryAction === "retry_failed"
        ? t.ozonRetryFailedSteps
        : t.ozonSyncNow;

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-bold">{t.marketplacesTitle}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t.marketplacesSubtitle}
        </p>
      </div>

      <section className="max-w-5xl rounded-lg border p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <h2 className="text-base font-semibold">
                {t.ozonIntegrationTitle}
              </h2>
              {connection && (
                <Badge
                  variant={
                    connection.status === "connected"
                      ? "default"
                      : connection.status === "invalid" ||
                          connection.status === "error"
                        ? "destructive"
                        : "secondary"
                  }
                >
                  {ozonStatusLabel(connection.status, t)}
                </Badge>
              )}
            </div>
            <p className="text-sm text-muted-foreground">
              {t.ozonMarketplaceDescription}
            </p>
          </div>

          {connection && !ozonSummary?.setupError && (
            <div className="flex flex-wrap gap-2">
              <Button
                variant="outline"
                onClick={syncOzonConnection}
                disabled={syncing}
              >
                <RefreshCw
                  className={syncing ? "h-4 w-4 animate-spin" : "h-4 w-4"}
                />
                {syncing ? t.syncing : syncActionLabel}
              </Button>
              <Button variant="outline" asChild>
                <Link href="/operations/marketplace/ozon?returnTo=%2Foperations%2Fmarketplaces">
                  <ListChecks className="h-4 w-4" />
                  {t.ozonReviewCandidates}
                </Link>
              </Button>
            </div>
          )}
        </div>

        {syncing && (
          <div
            role="status"
            className="mt-4 flex items-center gap-2 rounded-md border border-blue-500/40 bg-blue-500/10 px-3 py-2 text-sm text-blue-700"
          >
            <RefreshCw className="h-4 w-4 animate-spin" />
            {t.ozonSyncInProgress}
          </div>
        )}
        {recoveryActive && recovery && !syncing && (
          <div
            role="status"
            className="mt-4 flex items-start gap-2 rounded-md border border-blue-500/40 bg-blue-500/10 px-3 py-2 text-sm text-blue-700"
          >
            <RefreshCw className="mt-0.5 h-4 w-4 shrink-0 animate-spin" />
            <div>
              <div className="font-medium">
                {ozonSyncStatusLabel(recovery.status, t)}
              </div>
              <div>{t.ozonRecoveryInProgress}</div>
              {recovery.nextRetryAt && (
                <div className="mt-1 text-xs">
                  {t.ozonRecoveryNextRetry}:{" "}
                  {formatOzonDateTime(recovery.nextRetryAt)}
                </div>
              )}
              {recovery.lastError && (
                <div className="mt-1 text-xs">{recovery.lastError}</div>
              )}
            </div>
          </div>
        )}
        {error && (
          <div className="mt-4 flex items-center gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="h-4 w-4" />
            {error}
          </div>
        )}
        {success && (
          <div className="mt-4 flex items-center gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-700">
            <CheckCircle2 className="h-4 w-4" />
            {success}
          </div>
        )}
        {hasPartialSync && (
          <div className="mt-4 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm text-amber-800">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <div>{t.ozonSyncedWithErrorsMessage}</div>
              {(recovery?.lastError || connection?.lastSyncError) && (
                <div className="mt-1 text-xs">
                  {recovery?.lastError || connection?.lastSyncError}
                </div>
              )}
            </div>
          </div>
        )}
        {hasFailedSync && (
          <div className="mt-4 flex items-start gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <div>{t.ozonSyncStatusFailed}</div>
              {(recovery?.lastError || connection?.lastSyncError) && (
                <div className="mt-1 text-xs">
                  {recovery?.lastError || connection?.lastSyncError}
                </div>
              )}
            </div>
          </div>
        )}

        {ozonSummary?.setupError ? (
          <p className="mt-4 text-sm text-destructive">{t.ozonSetupRequired}</p>
        ) : loading ? (
          <p className="mt-4 text-sm text-muted-foreground">{t.loading}</p>
        ) : connection ? (
          <>
            <dl className="mt-5 grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-3">
              <div>
                <dt className="text-muted-foreground">{t.ozonLastValidated}</dt>
                <dd>{formatOzonDateTime(connection.lastValidatedAt)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">{t.ozonLastSync}</dt>
                <dd>{formatOzonDateTime(connection.lastSyncAt)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">
                  {t.ozonLastSyncStatus}
                </dt>
                <dd>
                  {syncing
                    ? ozonSyncStatusLabel("running", t)
                    : ozonSyncStatusLabel(
                        recovery?.status ?? connection.lastSyncStatus,
                        t
                      )}
                </dd>
              </div>
              {connection.lastSyncError &&
                (hasPartialSync || hasFailedSync) && (
                <div className="sm:col-span-2 lg:col-span-3">
                  <dt className="text-muted-foreground">{t.ozonLastSyncError}</dt>
                  <dd className="text-destructive">{connection.lastSyncError}</dd>
                </div>
                )}
            </dl>

            <div className="mt-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <OzonMetric label={t.ozonProductsSynced} value={ozonSummary.counts.products} />
              <OzonMetric label={t.ozonWarehousesSynced} value={ozonSummary.counts.warehouses} />
              <OzonMetric label={t.ozonPostingsSynced} value={ozonSummary.counts.postings} />
              <OzonMetric label={t.ozonReturnsSynced} value={ozonSummary.counts.returns} />
              <OzonMetric label={t.ozonFinanceTransactionsSynced} value={ozonSummary.counts.financeTransactions} />
              <OzonMetric label={t.ozonLegalEntitySalesSynced} value={ozonSummary.counts.legalEntitySales} />
              <OzonMetric label={t.ozonFinanceReportsSynced} value={ozonSummary.counts.financeReports} />
              <OzonMetric label={t.ozonRemovalsSynced} value={ozonSummary.counts.removals} />
              <OzonMetric label={t.ozonSuppliesSynced} value={ozonSummary.counts.supplies} />
              <OzonMetric label={t.ozonStockAnalyticsSynced} value={ozonSummary.counts.stockAnalytics} />
              <OzonMetric label={t.ozonDiscountedProductsSynced} value={ozonSummary.counts.discountedProducts} />
              <OzonMetric label={t.ozonUnpaidLegalProductsSynced} value={ozonSummary.counts.unpaidLegalProducts} />
              <OzonMetric label={t.ozonReadyCandidates} value={ozonSummary.counts.candidatesReady} />
              <OzonMetric label={t.ozonNeedsMapping} value={ozonSummary.counts.candidatesNeedsMapping} />
              <OzonMetric label={t.ozonUnmappedProducts} value={ozonSummary.counts.unmappedProducts} />
              <OzonMetric label={t.ozonUnmappedWarehouses} value={ozonSummary.counts.unmappedWarehouses} />
            </div>
          </>
        ) : (
          <div className="mt-5 rounded-md border bg-muted/20 p-4">
            <p className="text-sm text-muted-foreground">{t.ozonNoConnection}</p>
            <Button className="mt-3" asChild>
              <Link href="/settings?tab=integrations">
                <PlugZap className="h-4 w-4" />
                {t.ozonConnectInSettings}
              </Link>
            </Button>
          </div>
        )}
      </section>
    </div>
  );
}
