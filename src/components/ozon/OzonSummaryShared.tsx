"use client";

import { useI18n } from "@/i18n/context";

export interface OzonIntegrationSummary {
  connected: boolean;
  setupError?: string;
  connection: {
    id: string;
    name: string;
    status: "draft" | "connected" | "invalid" | "error" | "disabled";
    clientIdHint: string | null;
    apiKeyHint: string | null;
    lastValidatedAt: string | null;
    lastSyncAt: string | null;
    lastSyncStatus:
      | "running"
      | "completed"
      | "completed_with_errors"
      | "failed"
      | null;
    lastSyncError: string | null;
  } | null;
  counts: {
    products: number;
    unmappedProducts: number;
    warehouses: number;
    unmappedWarehouses: number;
    postings: number;
    returns: number;
    financeTransactions: number;
    legalEntitySales: number;
    unpaidLegalProducts: number;
    financeReports: number;
    removals: number;
    supplies: number;
    stockAnalytics: number;
    discountedProducts: number;
    candidatesReady: number;
    candidatesNeedsMapping: number;
  };
}

export function OzonMetric({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-md border bg-muted/20 p-3">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 text-lg font-semibold">{value}</div>
    </div>
  );
}

export function ozonStatusLabel(
  status: NonNullable<OzonIntegrationSummary["connection"]>["status"],
  t: ReturnType<typeof useI18n>["t"]
) {
  switch (status) {
    case "connected":
      return t.ozonStatusConnected;
    case "invalid":
      return t.ozonStatusInvalid;
    case "error":
      return t.ozonStatusError;
    case "disabled":
      return t.ozonStatusDisabled;
    case "draft":
      return t.ozonStatusDraft;
  }
}

export function ozonSyncStatusLabel(
  status: NonNullable<OzonIntegrationSummary["connection"]>["lastSyncStatus"],
  t: ReturnType<typeof useI18n>["t"]
) {
  switch (status) {
    case "running":
      return t.ozonSyncStatusRunning;
    case "completed":
      return t.ozonSyncStatusCompleted;
    case "completed_with_errors":
      return t.ozonSyncStatusCompletedWithErrors;
    case "failed":
      return t.ozonSyncStatusFailed;
    default:
      return "N/A";
  }
}

export function formatOzonDateTime(value: string | null) {
  if (!value) return "N/A";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "N/A";
  return date.toLocaleString();
}
