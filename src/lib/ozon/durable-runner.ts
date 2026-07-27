import type { SupabaseClient } from "@supabase/supabase-js";
import { createServiceRoleClient } from "../supabase-server";
import {
  createDurableOzonClient,
  OzonApiError,
  OzonClient,
  OzonIncompleteResponseError,
  OzonInvariantError,
  OzonReportPendingError,
} from "./client";
import { decryptOzonCredentials } from "./credentials";
import type { OzonCredentials, OzonSyncStepSummary } from "./types";
import {
  OZON_SYNC_DOMAIN_REGISTRY,
  OzonDatabaseError,
  OzonReportDownloadError,
  executeOzonSyncDomainStep,
  type OzonSyncDomainKey,
} from "./sync";

export interface ClaimedOzonSyncStep {
  id: string;
  run_id: string;
  workspace_id: string;
  connection_id: string;
  provider: "ozon";
  step_key: OzonSyncDomainKey | string;
  step_order: number;
  state: "running";
  attempt_count: number;
  failure_count?: number;
  checkpoint: Record<string, unknown>;
  lease_token: string;
  lease_expires_at: string;
}

export interface FinishOzonSyncStepInput {
  p_step_id: string;
  p_lease_token: string;
  p_state: "completed" | "skipped" | "retry_scheduled" | "failed";
  p_summary: OzonSyncStepSummary | Record<string, never>;
  p_last_error: PersistedOzonSyncError | null;
  p_next_attempt_at: string | null;
}

export interface YieldOzonSyncStepInput {
  p_step_id: string;
  p_lease_token: string;
  p_checkpoint: Record<string, unknown>;
  p_summary: OzonSyncStepSummary | Record<string, never>;
  p_next_attempt_at: string;
}

export type PersistedOzonSyncErrorKind =
  | "transport"
  | "timeout"
  | "rate_limit"
  | "server"
  | "client"
  | "unknown";

export interface PersistedOzonSyncError {
  message: "Ozon sync step failed";
  kind: PersistedOzonSyncErrorKind;
  status?: number;
  retryAfterMs?: number;
  retryable: boolean;
  endpoint?: string;
  code?: string | number;
  reason?: string;
  postgresCode?: string;
  operationName?: string;
}

export interface ClassifiedOzonSyncError {
  retryable: boolean;
  persistedError: PersistedOzonSyncError;
}

export interface OzonSyncStepFailureLog {
  event: "ozon_sync_step_failed";
  runId: string;
  connectionId: string;
  stepKey: string;
  attemptCount: number;
  failureCount: number;
  phase?: string;
  processed?: number;
  total?: number | null;
  kind: PersistedOzonSyncErrorKind;
  retryable: boolean;
  status?: number;
  retryAfterMs?: number;
  endpoint?: string;
  code?: string | number;
  reason?: string;
  postgresCode?: string;
  operationName?: string;
}

export interface DurableOzonRunnerDependencies {
  claimStep: (runId: string | null) => Promise<ClaimedOzonSyncStep | null>;
  executeStep: (
    step: ClaimedOzonSyncStep,
    context: ClaimedOzonStepExecutionDeadline
  ) => Promise<OzonSyncStepSummary>;
  finishStep: (input: FinishOzonSyncStepInput) => Promise<void>;
  yieldStep?: (input: YieldOzonSyncStepInput) => Promise<void>;
  checkpointStep?: (
    step: ClaimedOzonSyncStep,
    checkpoint: Record<string, unknown>,
    summary: OzonSyncStepSummary | Record<string, never>
  ) => Promise<void>;
  failConnection?: (
    step: ClaimedOzonSyncStep,
    error: PersistedOzonSyncError
  ) => Promise<void>;
  now: () => number;
  setTimer?: (callback: () => void, milliseconds: number) => unknown;
  clearTimer?: (timer: unknown) => void;
  logStepFailure?: (entry: OzonSyncStepFailureLog) => void;
}

export interface ClaimedOzonStepExecutionDeadline {
  deadlineMs: number;
  signal: AbortSignal;
}

export interface ClaimedStepExecutionContext {
  connection: {
    status: string;
    credential_ciphertext: Record<string, unknown>;
  } | null;
  dateFrom: string;
  dateTo: string;
}

export interface ClaimedStepExecutorServices {
  loadExecutionContext: (
    step: ClaimedOzonSyncStep
  ) => Promise<ClaimedStepExecutionContext>;
  decryptCredentials: (
    ciphertext: Record<string, unknown>
  ) => OzonCredentials;
  createClient: (
    credentials: OzonCredentials,
    signal: AbortSignal,
    deadlineMs: number
  ) => OzonClient;
  executeDomainStep: (
    key: OzonSyncDomainKey,
    input: {
      client: OzonClient;
      workspaceId: string;
      connectionId: string;
      dateFrom: string;
      dateTo: string;
      runId: string;
      checkpoint: Record<string, unknown>;
      saveCheckpoint: (
        checkpoint: Record<string, unknown>,
        summary?: OzonSyncStepSummary | Record<string, never>
      ) => Promise<void>;
      yieldIfNeeded: () => void;
    }
  ) => Promise<OzonSyncStepSummary>;
  saveCheckpoint?: (
    step: ClaimedOzonSyncStep,
    checkpoint: Record<string, unknown>,
    summary: OzonSyncStepSummary | Record<string, never>
  ) => Promise<void>;
}

export interface OzonWorkerRpcClient {
  rpc: (
    name: string,
    parameters: Record<string, unknown>
  ) => Promise<{
    data: unknown;
    error: { message?: string } | null;
  }>;
}

export class PermanentOzonSyncError extends Error {
  constructor(message: string, readonly connectionWide = false) {
    super(message);
    this.name = "PermanentOzonSyncError";
  }
}

export class OzonStepYieldError extends Error {
  constructor() {
    super("Ozon sync step yielded to its execution budget");
    this.name = "OzonStepYieldError";
  }
}

const DURABLE_RETRY_DELAYS_MS = [
  60_000,
  5 * 60_000,
  15 * 60_000,
  60 * 60_000,
  3 * 60 * 60_000,
  6 * 60 * 60_000,
  12 * 60 * 60_000,
] as const;

export const OZON_STEP_FINISH_MARGIN_MS = 2_000;

export function durableRetryDelayMs(failureCount: number): number | null {
  return DURABLE_RETRY_DELAYS_MS[failureCount - 1] ?? null;
}

export function classifyOzonSyncError(
  error: unknown
): ClassifiedOzonSyncError {
  if (error instanceof PermanentOzonSyncError) {
    return classification("client", false);
  }

  if (error instanceof OzonInvariantError) {
    return classification("client", false);
  }

  if (error instanceof OzonIncompleteResponseError) {
    return classification("client", false);
  }

  if (error instanceof OzonDatabaseError) {
    const result = classification("unknown", !error.permanent);
    if (error.code) result.persistedError.postgresCode = error.code;
    result.persistedError.operationName = error.operation;
    return result;
  }

  if (error instanceof OzonReportDownloadError) {
    return classification(
      ozonErrorKind(error.status),
      isTransientOzonStatus(error.status),
      error.status
    );
  }

  if (error instanceof OzonApiError) {
    const retryable = isTransientOzonStatus(error.status);
    const kind = ozonErrorKind(error.status);
    const retryAfterMs = safeRetryAfterMs(
      error.responseMetadata.retryAfterMs,
      error.responseMetadata.itemRetryAfterMs
    );

    const result = classification(
      kind,
      retryable,
      error.status,
      retryAfterMs
    );
    result.persistedError.endpoint = error.endpoint;
    if (error.code !== null) result.persistedError.code = error.code;
    if (error.apiMessage) result.persistedError.reason = error.apiMessage;
    return result;
  }

  if (isTimeoutError(error)) {
    return classification("timeout", true);
  }

  if (isTransportError(error)) {
    return classification("transport", true);
  }

  return classification("unknown", true);
}

function classification(
  kind: PersistedOzonSyncErrorKind,
  retryable: boolean,
  status?: number,
  retryAfterMs?: number
): ClassifiedOzonSyncError {
  return {
    retryable,
    persistedError: {
      message: "Ozon sync step failed",
      kind,
      ...(status === undefined ? {} : { status }),
      ...(retryAfterMs === undefined ? {} : { retryAfterMs }),
      retryable,
    },
  };
}

function ozonErrorKind(status: number): PersistedOzonSyncErrorKind {
  if (status === 408) return "timeout";
  if (status === 429) return "rate_limit";
  if (status >= 500) return "server";
  return "client";
}

function isTransientOzonStatus(status: number) {
  return (
    status === 408 ||
    status === 425 ||
    status === 429 ||
    (status >= 500 && status <= 599)
  );
}

function safeRetryAfterMs(...values: Array<number | null>) {
  const safeValues = values.filter(
    (value): value is number =>
      Number.isInteger(value) && value !== null && value >= 0 && value <= 86_400_000
  );

  return safeValues.length > 0 ? Math.max(...safeValues) : undefined;
}

function isTimeoutError(error: unknown) {
  if (!error || typeof error !== "object") return false;
  const name = "name" in error ? error.name : undefined;
  const code = "code" in error ? error.code : undefined;
  return (
    name === "TimeoutError" ||
    name === "AbortError" ||
    code === "ETIMEDOUT" ||
    code === "ESOCKETTIMEDOUT"
  );
}

function isTransportError(error: unknown) {
  if (error instanceof TypeError) return true;
  if (!error || typeof error !== "object" || !("code" in error)) return false;
  return [
    "ECONNABORTED",
    "ECONNREFUSED",
    "ECONNRESET",
    "EHOSTUNREACH",
    "ENETUNREACH",
    "ENOTFOUND",
    "EPIPE",
  ].includes(String(error.code));
}

async function processClaimedStep(
  step: ClaimedOzonSyncStep,
  dependencies: DurableOzonRunnerDependencies,
  executionDeadlineMs: number
) {
  let summary: OzonSyncStepSummary;
  try {
    summary = await executeClaimedStepUntilDeadline(
      step,
      executionDeadlineMs,
      dependencies
    );
  } catch (error) {
    if (
      error instanceof OzonStepYieldError ||
      error instanceof OzonReportPendingError
    ) {
      const nextAttemptAt =
        error instanceof OzonReportPendingError
          ? error.nextActionAt
          : new Date(dependencies.now()).toISOString();
      if (dependencies.yieldStep) {
        await dependencies.yieldStep({
          p_step_id: step.id,
          p_lease_token: step.lease_token,
          p_checkpoint: step.checkpoint ?? {},
          p_summary: {},
          p_next_attempt_at: nextAttemptAt,
        });
      } else {
        await dependencies.finishStep({
          p_step_id: step.id,
          p_lease_token: step.lease_token,
          p_state: "retry_scheduled",
          p_summary: {},
          p_last_error: classification("timeout", true).persistedError,
          p_next_attempt_at: nextAttemptAt,
        });
      }
      return;
    }
    const classified = classifyOzonSyncError(error);
    if (isConnectionWideOzonError(error) && dependencies.failConnection) {
      dependencies.logStepFailure?.(
        buildOzonSyncStepFailureLog(step, error, classified)
      );
      await dependencies.failConnection(step, classified.persistedError);
      return;
    }
    const failedAttempt =
      (Number.isInteger(step.failure_count) ? Number(step.failure_count) : 0) + 1;
    const retryDelayMs = classified.retryable
      ? durableRetryDelayMs(failedAttempt)
      : null;

    dependencies.logStepFailure?.(
      buildOzonSyncStepFailureLog(step, error, classified)
    );
    await dependencies.finishStep({
      p_step_id: step.id,
      p_lease_token: step.lease_token,
      p_state: retryDelayMs === null ? "failed" : "retry_scheduled",
      p_summary: {},
      p_last_error: classified.persistedError,
      p_next_attempt_at:
        retryDelayMs === null
          ? null
          : new Date(dependencies.now() + retryDelayMs).toISOString(),
    });
    return;
  }

  await dependencies.finishStep({
    p_step_id: step.id,
    p_lease_token: step.lease_token,
    p_state: "completed",
    p_summary: summary,
    p_last_error: null,
    p_next_attempt_at: null,
  });
}

function buildOzonSyncStepFailureLog(
  step: ClaimedOzonSyncStep,
  error: unknown,
  classified: ClassifiedOzonSyncError
): OzonSyncStepFailureLog {
  const entry: OzonSyncStepFailureLog = {
    event: "ozon_sync_step_failed",
    runId: step.run_id,
    connectionId: step.connection_id,
    stepKey: step.step_key,
    attemptCount: step.attempt_count,
    failureCount:
      (Number.isInteger(step.failure_count) ? Number(step.failure_count) : 0) +
      (classified.retryable ? 1 : 0),
    kind: classified.persistedError.kind,
    retryable: classified.retryable,
    ...(classified.persistedError.status === undefined
      ? {}
      : { status: classified.persistedError.status }),
    ...(classified.persistedError.retryAfterMs === undefined
      ? {}
      : { retryAfterMs: classified.persistedError.retryAfterMs }),
  };
  const progress = safeCheckpointProgress(step.checkpoint);
  if (progress) {
    entry.phase = progress.phase;
    entry.processed = progress.processed;
    entry.total = progress.total;
  }

  if (error instanceof OzonApiError) {
    entry.endpoint = error.endpoint;
    if (error.code !== null) entry.code = error.code;
    if (error.apiMessage !== null) entry.reason = error.apiMessage;
  } else if (error instanceof OzonDatabaseError) {
    if (error.code) entry.postgresCode = error.code;
    entry.operationName = error.operation;
  } else if (
    error instanceof PermanentOzonSyncError ||
    error instanceof OzonInvariantError ||
    error instanceof OzonIncompleteResponseError
  ) {
    entry.reason = error.message;
  }

  return entry;
}

function safeCheckpointProgress(value: unknown) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const checkpoint = value as Record<string, unknown>;
  if (typeof checkpoint.phase !== "string") return null;
  const processed =
    typeof checkpoint.processed === "number" &&
    Number.isInteger(checkpoint.processed) &&
    checkpoint.processed >= 0
      ? checkpoint.processed
      : 0;
  const total =
    checkpoint.total === null
      ? null
      : typeof checkpoint.total === "number" &&
          Number.isInteger(checkpoint.total) &&
          checkpoint.total >= 0
        ? checkpoint.total
        : null;
  return {
    phase: checkpoint.phase.slice(0, 80),
    processed,
    total,
  };
}

async function executeClaimedStepUntilDeadline(
  step: ClaimedOzonSyncStep,
  deadlineMs: number,
  dependencies: DurableOzonRunnerDependencies
) {
  const controller = new AbortController();
  const delayMs = deadlineMs - dependencies.now();
  const setTimer =
    dependencies.setTimer ??
    ((callback: () => void, milliseconds: number) =>
      setTimeout(callback, milliseconds));
  const clearTimer =
    dependencies.clearTimer ??
    ((timer: unknown) =>
      clearTimeout(timer as ReturnType<typeof setTimeout>));
  let timer: unknown = null;

  if (delayMs <= 0) {
    controller.abort(stepDeadlineError());
  } else {
    timer = setTimer(
      () => controller.abort(stepDeadlineError()),
      delayMs
    );
  }

  try {
    throwIfStepDeadlineExceeded(controller.signal);
    const summary = await dependencies.executeStep(step, {
      deadlineMs,
      signal: controller.signal,
    });
    throwIfStepDeadlineExceeded(controller.signal);
    return summary;
  } finally {
    if (timer !== null) clearTimer(timer);
  }
}

function stepDeadlineError() {
  return new OzonStepYieldError();
}

function throwIfStepDeadlineExceeded(signal: AbortSignal) {
  if (!signal.aborted) return;
  throw signal.reason instanceof Error ? signal.reason : stepDeadlineError();
}

export const durableOzonRunnerTestSeam = {
  createRpcOperations(client: OzonWorkerRpcClient) {
    return {
      claimStep: async (runId: string | null) => {
        const { data, error } = await client.rpc(
          "claim_ozon_sync_run_step_v2",
          { p_run_id: runId }
        );
        if (error) throw new Error("Failed to claim Ozon sync step");
        if (!Array.isArray(data) || data.length === 0) return null;
        if (data.length > 1) {
          throw new PermanentOzonSyncError(
            "Ozon step claim returned multiple rows"
          );
        }
        return data[0] as ClaimedOzonSyncStep;
      },
      finishStep: async (input: FinishOzonSyncStepInput) => {
        const { error } = await client.rpc(
          "finish_ozon_sync_run_step_v2",
          input as unknown as Record<string, unknown>
        );
        if (error) throw new Error("Failed to finish Ozon sync step");
      },
      yieldStep: async (input: YieldOzonSyncStepInput) => {
        const { error } = await client.rpc(
          "yield_ozon_sync_run_step_v2",
          input as unknown as Record<string, unknown>
        );
        if (error) throw new Error("Failed to yield Ozon sync step");
      },
      checkpointStep: async (
        step: ClaimedOzonSyncStep,
        checkpoint: Record<string, unknown>,
        summary: OzonSyncStepSummary | Record<string, never>
      ) => {
        const { error } = await client.rpc(
          "checkpoint_ozon_sync_run_step_v2",
          {
            p_step_id: step.id,
            p_lease_token: step.lease_token,
            p_checkpoint: checkpoint,
            p_summary: summary,
          }
        );
        if (error) throw new Error("Failed to checkpoint Ozon sync step");
        step.checkpoint = checkpoint;
      },
      failConnection: async (
        step: ClaimedOzonSyncStep,
        persistedError: PersistedOzonSyncError
      ) => {
        const { error } = await client.rpc(
          "fail_ozon_sync_connection_v2",
          {
            p_step_id: step.id,
            p_lease_token: step.lease_token,
            p_last_error: persistedError,
          }
        );
        if (error) throw new Error("Failed to terminate Ozon connection sync");
      },
    };
  },
  createClaimExecutor(services: ClaimedStepExecutorServices) {
    return async (
      step: ClaimedOzonSyncStep,
      deadline: ClaimedOzonStepExecutionDeadline
    ) => {
      throwIfStepDeadlineExceeded(deadline.signal);
      const context = await services.loadExecutionContext(step);
      throwIfStepDeadlineExceeded(deadline.signal);
      if (!context.connection) {
        throw new PermanentOzonSyncError("Ozon connection not found");
      }
      if (
        context.connection.status === "disabled" ||
        context.connection.status === "draft" ||
        context.connection.status === "invalid"
      ) {
        throw new PermanentOzonSyncError("Ozon connection is not runnable");
      }
      if (!isOzonSyncDomainKey(step.step_key)) {
        throw new PermanentOzonSyncError("Unknown Ozon sync domain");
      }
      if (!isValidDateWindow(context.dateFrom, context.dateTo)) {
        throw new PermanentOzonSyncError("Ozon sync run date window is invalid");
      }

      let credentials: OzonCredentials;
      try {
        credentials = services.decryptCredentials(
          context.connection.credential_ciphertext
        );
      } catch {
        throw new PermanentOzonSyncError(
          "Stored Ozon credentials are invalid",
          true
        );
      }
      if (!credentials.clientId.trim() || !credentials.apiKey.trim()) {
        throw new PermanentOzonSyncError(
          "Stored Ozon credentials are invalid",
          true
        );
      }

      throwIfStepDeadlineExceeded(deadline.signal);
      const client = services.createClient(
        credentials,
        deadline.signal,
        deadline.deadlineMs
      );
      return services.executeDomainStep(step.step_key, {
        client,
        workspaceId: step.workspace_id,
        connectionId: step.connection_id,
        dateFrom: context.dateFrom,
        dateTo: context.dateTo,
        runId: step.run_id,
        checkpoint: step.checkpoint ?? {},
        saveCheckpoint: (checkpoint, summary = {}) =>
          services.saveCheckpoint?.(step, checkpoint, summary) ??
          Promise.resolve(),
        yieldIfNeeded: () => {
          if (deadline.signal.aborted) throw new OzonStepYieldError();
        },
      });
    };
  },
  async runManual(
    runId: string,
    deadlineMs: number,
    dependencies: DurableOzonRunnerDependencies
  ) {
    let processed = 0;
    const executionDeadlineMs = deadlineMs - OZON_STEP_FINISH_MARGIN_MS;

    while (dependencies.now() < executionDeadlineMs) {
      const step = await dependencies.claimStep(runId);
      if (!step) break;
      await processClaimedStep(step, dependencies, executionDeadlineMs);
      processed += 1;
    }

    return processed;
  },
  async recoverOne(
    deadlineMs: number,
    dependencies: DurableOzonRunnerDependencies
  ) {
    const step = await dependencies.claimStep(null);
    if (!step) return false;
    await processClaimedStep(
      step,
      dependencies,
      deadlineMs - OZON_STEP_FINISH_MARGIN_MS
    );
    return true;
  },
};

function isOzonSyncDomainKey(value: string): value is OzonSyncDomainKey {
  return OZON_SYNC_DOMAIN_REGISTRY.some(({ key }) => key === value);
}

function isValidDateWindow(dateFrom: string, dateTo: string) {
  const from = Date.parse(dateFrom);
  const to = Date.parse(dateTo);
  return Number.isFinite(from) && Number.isFinite(to) && from <= to;
}

function isConnectionWideOzonError(error: unknown) {
  return (
    (error instanceof PermanentOzonSyncError && error.connectionWide) ||
    (error instanceof OzonApiError &&
      (error.status === 401 || error.status === 403))
  );
}

export async function runOzonSyncRunUntilDeadline(
  runId: string,
  deadlineMs: number
) {
  return durableOzonRunnerTestSeam.runManual(
    runId,
    deadlineMs,
    createProductionDependencies()
  );
}

export async function recoverOneOzonSyncStep(deadlineMs: number) {
  return durableOzonRunnerTestSeam.recoverOne(
    deadlineMs,
    createProductionDependencies()
  );
}

function createProductionDependencies(): DurableOzonRunnerDependencies {
  const supabase = createServiceRoleClient();
  const rpcOperations = durableOzonRunnerTestSeam.createRpcOperations(
    supabase as unknown as OzonWorkerRpcClient
  );
  const executeStep = durableOzonRunnerTestSeam.createClaimExecutor({
    loadExecutionContext: (step) =>
      loadClaimedStepExecutionContext(supabase, step),
    decryptCredentials: decryptOzonCredentials,
    createClient: createDurableOzonClient,
    saveCheckpoint: rpcOperations.checkpointStep,
    executeDomainStep: (key, input) =>
      executeOzonSyncDomainStep(key, {
        supabase,
        ...input,
      }),
  });

  return {
    ...rpcOperations,
    executeStep,
    now: Date.now,
    logStepFailure: (entry) =>
      console.error("Ozon sync step execution failed", entry),
  };
}

async function loadClaimedStepExecutionContext(
  supabase: SupabaseClient,
  step: ClaimedOzonSyncStep
): Promise<ClaimedStepExecutionContext> {
  const [connectionResult, runResult] = await Promise.all([
    supabase
      .from("marketplace_connections")
      .select("status, credential_ciphertext")
      .eq("id", step.connection_id)
      .eq("workspace_id", step.workspace_id)
      .eq("provider", "ozon")
      .maybeSingle(),
    supabase
      .from("marketplace_sync_runs")
      .select("date_from, date_to")
      .eq("id", step.run_id)
      .eq("workspace_id", step.workspace_id)
      .eq("connection_id", step.connection_id)
      .eq("provider", "ozon")
      .maybeSingle(),
  ]);

  if (connectionResult.error) {
    throw new Error("Failed to load Ozon connection");
  }
  if (runResult.error) {
    throw new Error("Failed to load Ozon sync run");
  }
  if (!runResult.data) {
    throw new PermanentOzonSyncError("Ozon sync run context not found");
  }

  const connection = connectionResult.data
    ? {
        status: String(connectionResult.data.status ?? ""),
        credential_ciphertext:
          connectionResult.data.credential_ciphertext &&
          typeof connectionResult.data.credential_ciphertext === "object" &&
          !Array.isArray(connectionResult.data.credential_ciphertext)
            ? (connectionResult.data
                .credential_ciphertext as Record<string, unknown>)
            : {},
      }
    : null;

  return {
    connection,
    dateFrom: String(runResult.data.date_from ?? ""),
    dateTo: String(runResult.data.date_to ?? ""),
  };
}
