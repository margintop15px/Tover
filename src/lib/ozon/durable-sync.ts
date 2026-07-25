import type { SupabaseClient } from "@supabase/supabase-js";
import { runOzonSyncRunUntilDeadline } from "./durable-runner";
import type { OzonSyncOptions, OzonSyncStepSummary, OzonSyncSummary } from "./types";
import {
  OZON_SYNC_DOMAIN_REGISTRY,
  type OzonSyncDomainKey,
} from "./sync";

export type OzonSyncRunStatus =
  | "running"
  | "retrying"
  | "completed"
  | "completed_with_errors"
  | "failed";

export interface OzonSyncRunRow {
  id: string;
  workspace_id: string;
  connection_id: string;
  provider: "ozon";
  status: OzonSyncRunStatus;
  date_from: string | null;
  date_to: string | null;
}

export interface OzonSyncRunStepRow {
  run_id: string;
  step_key: string;
  step_order: number;
  state:
    | "pending"
    | "running"
    | "retry_scheduled"
    | "completed"
    | "skipped"
    | "failed";
  summary: unknown;
  last_error: unknown;
  next_attempt_at: string | null;
  updated_at: string;
}

export interface OzonSyncRecoveryCounts {
  pendingStepCount: number;
  scheduledRetryCount: number;
  failedStepCount: number;
  nextRetryAt: string | null;
}

export interface OzonSyncResult {
  runId: string;
  status: OzonSyncRunStatus;
  summary: OzonSyncSummary;
  recovery: OzonSyncRecoveryCounts;
}

interface BeginOrResumeInput {
  connectionId: string;
  dateFrom: string;
  dateTo: string;
}

export interface OzonDurableSyncCoordinatorDependencies {
  beginOrResumeRun: (input: BeginOrResumeInput) => Promise<OzonSyncRunRow>;
  retryFailedRun: (runId: string) => Promise<OzonSyncRunRow>;
  executeUntilDeadline: (runId: string, deadlineMs: number) => Promise<unknown>;
  loadRunSnapshot: (runId: string) => Promise<{
    run: OzonSyncRunRow;
    steps: OzonSyncRunStepRow[];
  }>;
  now: () => number;
}

export interface OzonSyncWorkerRpcClient {
  rpc: (
    name: string,
    parameters: Record<string, unknown>
  ) => Promise<{
    data: unknown;
    error: { message?: string } | null;
  }>;
}

export const OZON_MANUAL_SYNC_BUDGET_MS = 25_000;

const DEFAULT_SYNC_DAYS = 30;
const DOMAIN_KEYS = new Set<string>(
  OZON_SYNC_DOMAIN_REGISTRY.map(({ key }) => key)
);
const STEP_SUMMARY_KEYS = [
  "fetched",
  "inserted",
  "updated",
  "createdCandidates",
  "skipped",
] as const;
const ERROR_KINDS = new Set([
  "transport",
  "timeout",
  "rate_limit",
  "server",
  "client",
  "unknown",
]);

export function createOzonDurableSyncCoordinator(
  dependencies: OzonDurableSyncCoordinatorDependencies
) {
  async function executeAndLoad(runId: string, budgetMs: number) {
    await dependencies.executeUntilDeadline(
      runId,
      dependencies.now() + budgetMs
    );
    const snapshot = await dependencies.loadRunSnapshot(runId);
    return deriveOzonSyncResult(snapshot.run, snapshot.steps);
  }

  return {
    async beginOrResume(
      input: BeginOrResumeInput & { budgetMs: number }
    ): Promise<OzonSyncResult> {
      const run = await dependencies.beginOrResumeRun(input);
      return executeAndLoad(run.id, input.budgetMs);
    },
    async retryFailed(input: {
      runId: string;
      budgetMs: number;
    }): Promise<OzonSyncResult> {
      const run = await dependencies.retryFailedRun(input.runId);
      return executeAndLoad(run.id, input.budgetMs);
    },
  };
}

export function createOzonSyncWorkerRpcOperations(
  client: OzonSyncWorkerRpcClient
) {
  return {
    async beginOrResumeRun(
      input: BeginOrResumeInput
    ): Promise<OzonSyncRunRow> {
      const { data, error } = await client.rpc(
        "begin_or_resume_ozon_sync_run",
        {
          p_connection_id: input.connectionId,
          p_date_from: input.dateFrom,
          p_date_to: input.dateTo,
        }
      );
      if (error) throw new Error("Failed to begin or resume Ozon sync run");
      return parseOzonSyncRunComposite(data);
    },
    async retryFailedRun(runId: string): Promise<OzonSyncRunRow> {
      const { data, error } = await client.rpc(
        "retry_failed_ozon_sync_run_steps",
        { p_run_id: runId }
      );
      if (error) throw new Error("Failed to retry Ozon sync run");
      return parseOzonSyncRunComposite(data);
    },
  };
}

export function createServiceRoleOzonSyncCoordinator(
  client: SupabaseClient,
  scope: { workspaceId: string; connectionId: string }
) {
  const rpc = createOzonSyncWorkerRpcOperations(
    client as unknown as OzonSyncWorkerRpcClient
  );
  return createOzonDurableSyncCoordinator({
    ...rpc,
    executeUntilDeadline: runOzonSyncRunUntilDeadline,
    loadRunSnapshot: (runId) =>
      loadOzonSyncRunSnapshot(client, {
        ...scope,
        runId,
      }),
    now: Date.now,
  });
}

export function parseOzonSyncRunComposite(data: unknown): OzonSyncRunRow {
  const row = Array.isArray(data)
    ? data.length === 1
      ? data[0]
      : null
    : data;

  if (!isOzonSyncRunRow(row)) {
    throw new Error("Ozon sync run RPC returned an invalid row");
  }
  return row;
}

export function deriveOzonSyncResult(
  run: OzonSyncRunRow,
  steps: OzonSyncRunStepRow[]
): OzonSyncResult {
  return {
    runId: run.id,
    status: run.status,
    summary: deriveOzonSyncSummary(steps),
    recovery: deriveRecoveryCounts(steps),
  };
}

export function derivePublicOzonSyncSummary(
  storedSummary: unknown,
  steps: OzonSyncRunStepRow[]
): OzonSyncSummary {
  if (steps.length > 0) return deriveOzonSyncSummary(steps);

  const summary: OzonSyncSummary = { errors: [] };
  if (!isRecord(storedSummary)) return summary;
  for (const key of DOMAIN_KEYS) {
    const publicStepSummary = publicStepSummaryFrom(storedSummary[key]);
    if (publicStepSummary) {
      summary[key as OzonSyncDomainKey] = publicStepSummary;
    }
  }
  return summary;
}

export function authorizeOzonRetryTarget(
  request: { workspaceId: string; runId: string },
  run: unknown,
  connection: unknown
): { workspaceId: string; connectionId: string } | null {
  if (!isRecord(run) || !isRecord(connection)) return null;
  if (
    run.id !== request.runId ||
    run.workspace_id !== request.workspaceId ||
    run.provider !== "ozon" ||
    typeof run.connection_id !== "string" ||
    connection.id !== run.connection_id ||
    connection.workspace_id !== request.workspaceId ||
    connection.provider !== "ozon"
  ) {
    return null;
  }
  return {
    workspaceId: request.workspaceId,
    connectionId: run.connection_id,
  };
}

export function deriveOzonSyncSummary(
  steps: OzonSyncRunStepRow[]
): OzonSyncSummary {
  const summary: OzonSyncSummary = { errors: [] };
  const orderedSteps = [...steps].sort(
    (left, right) => left.step_order - right.step_order
  );

  for (const step of orderedSteps) {
    if (!isOzonSyncDomainKey(step.step_key)) continue;
    const publicStepSummary = publicStepSummaryFrom(step.summary);
    if (publicStepSummary) {
      summary[step.step_key] = publicStepSummary;
    }
    if (step.state === "retry_scheduled" || step.state === "failed") {
      summary.errors.push(formatNormalizedStepError(step));
    }
  }

  return summary;
}

export function deriveOzonIntegrationRecovery(
  run: Pick<OzonSyncRunRow, "id" | "status">,
  steps: OzonSyncRunStepRow[]
) {
  const failures = steps
    .filter(
      (step) =>
        isOzonSyncDomainKey(step.step_key) &&
        (step.state === "retry_scheduled" || step.state === "failed")
    )
    .sort((left, right) => {
      const byUpdatedAt =
        Date.parse(right.updated_at) - Date.parse(left.updated_at);
      return Number.isFinite(byUpdatedAt) && byUpdatedAt !== 0
        ? byUpdatedAt
        : right.step_order - left.step_order;
    });

  return {
    runId: run.id,
    status: run.status,
    ...deriveRecoveryCounts(steps),
    lastError:
      failures.length > 0 ? formatNormalizedStepError(failures[0]) : null,
  };
}

export function durableSyncHttpStatus(status: OzonSyncRunStatus): 200 | 202 {
  return status === "running" || status === "retrying" ? 202 : 200;
}

export function resolveOzonSyncWindow(
  options: OzonSyncOptions,
  nowMs = Date.now()
) {
  const dateTo = options.dateTo ? new Date(options.dateTo) : new Date(nowMs);
  const dateFrom = options.dateFrom
    ? new Date(options.dateFrom)
    : new Date(dateTo.getTime() - DEFAULT_SYNC_DAYS * 24 * 60 * 60 * 1000);

  return {
    dateFrom: dateFrom.toISOString(),
    dateTo: dateTo.toISOString(),
  };
}

export async function loadOzonSyncRunSnapshot(
  client: SupabaseClient,
  scope: {
    runId: string;
    workspaceId: string;
    connectionId: string;
  }
) {
  const [runResult, stepsResult] = await Promise.all([
    client
      .from("marketplace_sync_runs")
      .select(
        "id, workspace_id, connection_id, provider, status, date_from, date_to"
      )
      .eq("id", scope.runId)
      .eq("workspace_id", scope.workspaceId)
      .eq("connection_id", scope.connectionId)
      .eq("provider", "ozon")
      .maybeSingle(),
    client
      .from("marketplace_sync_run_steps")
      .select(
        "run_id, step_key, step_order, state, summary, last_error, next_attempt_at, updated_at"
      )
      .eq("run_id", scope.runId)
      .eq("workspace_id", scope.workspaceId)
      .eq("connection_id", scope.connectionId)
      .eq("provider", "ozon")
      .order("step_order", { ascending: true }),
  ]);

  if (runResult.error || !runResult.data) {
    throw new Error("Failed to load Ozon sync run");
  }
  if (stepsResult.error) {
    throw new Error("Failed to load Ozon sync run steps");
  }

  return {
    run: parseOzonSyncRunComposite(runResult.data),
    steps: (stepsResult.data ?? []) as OzonSyncRunStepRow[],
  };
}

function deriveRecoveryCounts(
  steps: OzonSyncRunStepRow[]
): OzonSyncRecoveryCounts {
  const persistedSteps = steps.filter((step) =>
    isOzonSyncDomainKey(step.step_key)
  );
  const retryDates = persistedSteps
    .filter((step) => step.state === "retry_scheduled")
    .map((step) => step.next_attempt_at)
    .filter(
      (value): value is string =>
        typeof value === "string" && Number.isFinite(Date.parse(value))
    )
    .sort((left, right) => Date.parse(left) - Date.parse(right));

  return {
    pendingStepCount: persistedSteps.filter((step) => step.state === "pending")
      .length,
    scheduledRetryCount: persistedSteps.filter(
      (step) => step.state === "retry_scheduled"
    ).length,
    failedStepCount: persistedSteps.filter((step) => step.state === "failed")
      .length,
    nextRetryAt: retryDates[0] ?? null,
  };
}

function publicStepSummaryFrom(value: unknown): OzonSyncStepSummary | null {
  if (!isRecord(value) || !isSafeCount(value.fetched)) return null;
  const summary: Record<string, number> = {};
  for (const key of STEP_SUMMARY_KEYS) {
    const count = value[key];
    if (isSafeCount(count)) summary[key] = count;
  }
  return summary as unknown as OzonSyncStepSummary;
}

function formatNormalizedStepError(step: OzonSyncRunStepRow) {
  const error = isRecord(step.last_error) ? step.last_error : {};
  const kind =
    typeof error.kind === "string" && ERROR_KINDS.has(error.kind)
      ? error.kind
      : "unknown";
  const status =
    Number.isInteger(error.status) &&
    Number(error.status) >= 100 &&
    Number(error.status) <= 599
      ? `, HTTP ${error.status}`
      : "";
  return `${step.step_key}: Ozon sync step failed (${kind}${status})`;
}

function isOzonSyncDomainKey(value: string): value is OzonSyncDomainKey {
  return DOMAIN_KEYS.has(value);
}

function isOzonSyncRunRow(value: unknown): value is OzonSyncRunRow {
  if (!isRecord(value)) return false;
  return (
    typeof value.id === "string" &&
    typeof value.workspace_id === "string" &&
    typeof value.connection_id === "string" &&
    value.provider === "ozon" &&
    typeof value.status === "string" &&
    isOzonSyncRunStatus(value.status) &&
    (typeof value.date_from === "string" || value.date_from === null) &&
    (typeof value.date_to === "string" || value.date_to === null)
  );
}

function isOzonSyncRunStatus(value: string): value is OzonSyncRunStatus {
  return [
    "running",
    "retrying",
    "completed",
    "completed_with_errors",
    "failed",
  ].includes(value);
}

function isSafeCount(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return (
    value !== null && typeof value === "object" && !Array.isArray(value)
  );
}
