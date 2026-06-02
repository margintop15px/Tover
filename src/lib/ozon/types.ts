export interface OzonCredentials {
  clientId: string;
  apiKey: string;
}

export interface OzonConnectionRecord {
  id: string;
  workspace_id: string;
  provider: "ozon";
  name: string;
  credential_ciphertext: Record<string, unknown>;
  client_id_hint: string | null;
  api_key_hint: string | null;
  status: "draft" | "connected" | "invalid" | "error" | "disabled";
  health: Record<string, unknown>;
  last_validated_at: string | null;
  last_sync_at: string | null;
  last_sync_status: "running" | "completed" | "completed_with_errors" | "failed" | null;
  last_sync_error: string | null;
  created_at: string;
  updated_at: string;
}

export interface OzonSyncOptions {
  dateFrom?: string;
  dateTo?: string;
}

export interface OzonSyncStepSummary {
  fetched: number;
  inserted?: number;
  updated?: number;
  createdCandidates?: number;
  skipped?: number;
}

export interface OzonSyncSummary {
  warehouses?: OzonSyncStepSummary;
  products?: OzonSyncStepSummary;
  stocks?: OzonSyncStepSummary;
  postings?: OzonSyncStepSummary;
  returns?: OzonSyncStepSummary;
  finance?: OzonSyncStepSummary;
  legalEntities?: OzonSyncStepSummary;
  reports?: OzonSyncStepSummary;
  removals?: OzonSyncStepSummary;
  supplies?: OzonSyncStepSummary;
  analytics?: OzonSyncStepSummary;
  discountedProducts?: OzonSyncStepSummary;
  errors: string[];
}

export interface LocalProductRef {
  id: string;
  name: string;
  sku_code: string | null;
}

export interface LocalWarehouseRef {
  id: string;
  name: string;
}
