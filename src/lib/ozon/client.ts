import type { OzonCredentials } from "./types";

const DEFAULT_OZON_SELLER_API_BASE_URL = "https://api-seller.ozon.ru";
const REQUEST_START_PACING_MS = 25;
const REQUEST_TIMEOUT_MS = 30_000;
const MAX_ATTEMPTS = 4;
const RETRY_DELAY_CAP_MS = 30_000;
const RETRY_DELAY_BASE_MS = 500;
const RETRY_DELAY_JITTER_MS = 250;
const MAX_SAFE_API_MESSAGE_LENGTH = 500;

export const OZON_READ_ONLY_ENDPOINTS = [
  "/v2/warehouse/list",
  "/v1/warehouse/ozon/list",
  "/v3/product/list",
  "/v3/product/info/list",
  "/v4/product/info/attributes",
  "/v4/product/info/stocks",
  "/v5/product/info/prices",
  "/v1/product/info/discounted",
  "/v4/posting/fbs/list",
  "/v3/posting/fbo/list",
  "/v1/returns/list",
  "/v2/returns/rfbs/list",
  "/v2/returns/rfbs/get",
  "/v1/posting/unpaid-legal/product/list",
  "/v1/finance/accrual/types",
  "/v1/finance/accrual/by-day",
  "/v1/finance/document-b2b-sales/json",
  "/v1/finance/cash-flow-statement/list",
  "/v1/finance/mutual-settlement",
  "/v1/finance/products/buyout",
  "/v1/finance/compensation",
  "/v1/finance/decompensation",
  "/v1/report/discounted/create",
  "/v1/report/info",
  "/v1/report/list",
  "/v1/removal/from-stock/list",
  "/v1/removal/from-supply/list",
  "/v3/supply-order/list",
  "/v3/supply-order/get",
  "/v1/supply-order/bundle",
  "/v1/analytics/stocks",
  "/v1/analytics/turnover/stocks",
] as const;

export type OzonReadOnlyEndpoint = (typeof OZON_READ_ONLY_ENDPOINTS)[number];

const READ_ONLY_ENDPOINT_SET = new Set<string>(OZON_READ_ONLY_ENDPOINTS);
const HTTP_RETRY_UNSAFE_ENDPOINTS = new Set<OzonReadOnlyEndpoint>([
  "/v1/finance/mutual-settlement",
  "/v1/finance/compensation",
  "/v1/finance/decompensation",
  "/v1/report/discounted/create",
]);

export interface OzonApiResponseMetadata {
  requestId: string | null;
  retryAfterMs: number | null;
  itemRetryAfterMs: number | null;
}

export class OzonInvariantError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "OzonInvariantError";
  }
}

export class OzonIncompleteResponseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "OzonIncompleteResponseError";
  }
}

export class OzonReportPendingError extends OzonIncompleteResponseError {
  constructor(readonly nextActionAt: string) {
    super("Ozon report is still processing");
    this.name = "OzonReportPendingError";
  }
}

export class OzonApiError extends Error {
  status: number;
  endpoint: string;
  code: string | number | null;
  apiMessage: string | null;
  responseMetadata: OzonApiResponseMetadata;
  retryDelayMs: number;

  constructor(
    endpoint: string,
    status: number,
    responseBody: unknown,
    responseMetadata: OzonApiResponseMetadata = emptyResponseMetadata(),
    retryDelayMs = 0,
    sensitiveValues: string[] = []
  ) {
    super(`Ozon API ${endpoint} failed with status ${status}`);
    const { code, apiMessage } = extractOzonErrorDetails(responseBody, sensitiveValues);
    this.status = status;
    this.endpoint = endpoint;
    this.code = code;
    this.apiMessage = apiMessage;
    this.responseMetadata = responseMetadata;
    this.retryDelayMs = retryDelayMs;
  }
}

interface OzonClientRuntime {
  fetch: typeof fetch;
  now: () => number;
  sleep: (milliseconds: number, signal?: AbortSignal) => Promise<void>;
  random: () => number;
  timeoutSignal: (milliseconds: number) => AbortSignal;
  logAttempt?: (entry: Record<string, unknown>) => void;
}

interface OzonRequestPacingState {
  nextRequestStartAt: number;
  nextTurnoverRequestStartAt: number;
}

const DEFAULT_RUNTIME: OzonClientRuntime = {
  fetch,
  now: Date.now,
  sleep: abortableSleep,
  random: Math.random,
  timeoutSignal: (milliseconds) => AbortSignal.timeout(milliseconds),
  logAttempt: (entry) => console.warn("Ozon API request attempt failed", entry),
};

const PACING_BY_RUNTIME = new WeakMap<
  OzonClientRuntime,
  Map<string, OzonRequestPacingState>
>();

interface OzonClientExecutionOptions {
  maxAttempts?: number;
  signal?: AbortSignal;
  deadlineMs?: number;
}

export class OzonClient {
  private credentials: OzonCredentials;
  private runtime: OzonClientRuntime;
  private maxAttempts: number;
  private executionSignal: AbortSignal | undefined;
  private deadlineMs: number | undefined;
  private pacing: OzonRequestPacingState;

  constructor(credentials: OzonCredentials);
  constructor(credentials: OzonCredentials, runtime: OzonClientRuntime);
  constructor(
    credentials: OzonCredentials,
    runtime: OzonClientRuntime,
    options: OzonClientExecutionOptions
  );
  constructor(
    credentials: OzonCredentials,
    runtime: OzonClientRuntime = DEFAULT_RUNTIME,
    options: OzonClientExecutionOptions = {}
  ) {
    this.credentials = credentials;
    this.runtime = runtime;
    this.maxAttempts = normalizeMaxAttempts(options.maxAttempts);
    this.executionSignal = options.signal;
    this.deadlineMs = options.deadlineMs;
    this.pacing = pacingStateFor(runtime, credentials.clientId);
  }

  async request<T>(
    endpoint: OzonReadOnlyEndpoint,
    body: Record<string, unknown> = {}
  ): Promise<T> {
    if (!READ_ONLY_ENDPOINT_SET.has(endpoint)) {
      throw new OzonInvariantError(
        `Ozon endpoint is not allowlisted: ${endpoint}`
      );
    }

    throwIfAborted(this.executionSignal);

    for (let attempt = 0; attempt < this.maxAttempts; attempt += 1) {
      await this.waitForRequestStart(endpoint);

      let response: Response;
      let responseBody: unknown;
      const attemptStartedAt = this.runtime.now();
      const requestSignal = combineAbortSignals(
        this.runtime.timeoutSignal(REQUEST_TIMEOUT_MS),
        this.executionSignal
      );
      try {
        response = await this.runtime.fetch(`${ozonApiBaseUrl()}${endpoint}`, {
          method: "POST",
          headers: {
            "Client-Id": this.credentials.clientId,
            "Api-Key": this.credentials.apiKey,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(body),
          cache: "no-store",
          signal: requestSignal.signal,
        });
        responseBody = await readResponseBody(response);
      } catch (error) {
        if (this.executionSignal?.aborted) {
          throw abortReason(this.executionSignal);
        }
        const retryAllowed = !HTTP_RETRY_UNSAFE_ENDPOINTS.has(endpoint);
        const retryDelayMs = this.retryDelayFor(attempt, null);
        const willRetry =
          retryAllowed &&
          attempt < this.maxAttempts - 1 &&
          this.retryFitsDeadline(retryDelayMs);
        this.runtime.logAttempt?.({
          event: "ozon_api_attempt_failed",
          endpoint,
          attempt: attempt + 1,
          durationMs: Math.max(0, this.runtime.now() - attemptStartedAt),
          kind: isTimeoutLike(error) ? "timeout" : "transport",
          willRetry,
          retryDelayMs,
        });
        if (!retryAllowed || attempt === this.maxAttempts - 1) throw error;
        if (!this.retryFitsDeadline(retryDelayMs)) throw error;
        await this.sleep(retryDelayMs);
        continue;
      } finally {
        requestSignal.cleanup();
      }

      const responseMetadata = responseMetadataFor(response, this.runtime.now());
      const retryDelayMs = this.retryDelayFor(attempt, responseMetadata);

      if (response.ok) return responseBody as T;

      const error = new OzonApiError(
        endpoint,
        response.status,
        responseBody,
        responseMetadata,
        retryDelayMs,
        [this.credentials.clientId, this.credentials.apiKey]
      );
      const retryAllowed = !HTTP_RETRY_UNSAFE_ENDPOINTS.has(endpoint);
      const willRetry =
        retryAllowed &&
        isRetryableStatus(response.status) &&
        attempt < this.maxAttempts - 1 &&
        this.retryFitsDeadline(retryDelayMs);
      this.runtime.logAttempt?.({
        event: "ozon_api_attempt_failed",
        endpoint,
        attempt: attempt + 1,
        durationMs: Math.max(0, this.runtime.now() - attemptStartedAt),
        kind:
          response.status === 408
            ? "timeout"
            : response.status === 429
              ? "rate_limit"
              : response.status >= 500
                ? "server"
                : "client",
        status: response.status,
        code: error.code,
        reason: error.apiMessage,
        willRetry,
        retryDelayMs,
      });

      if (
        !retryAllowed ||
        !isRetryableStatus(response.status) ||
        attempt === this.maxAttempts - 1
      ) {
        throw error;
      }

      if (!this.retryFitsDeadline(retryDelayMs)) throw error;
      await this.sleep(retryDelayMs);
    }

    throw new Error("Ozon request retry loop exited unexpectedly");
  }

  executionAbortSignal() {
    return this.executionSignal;
  }

  private async waitForRequestStart(endpoint: OzonReadOnlyEndpoint) {
    const now = this.runtime.now();
    const endpointStartAt =
      endpoint === "/v1/analytics/turnover/stocks"
        ? this.pacing.nextTurnoverRequestStartAt
        : 0;
    const requestStartAt = Math.max(
      now,
      this.pacing.nextRequestStartAt,
      endpointStartAt
    );
    this.pacing.nextRequestStartAt = requestStartAt + REQUEST_START_PACING_MS;
    if (endpoint === "/v1/analytics/turnover/stocks") {
      this.pacing.nextTurnoverRequestStartAt = requestStartAt + 60_000;
    }

    if (requestStartAt > now) {
      await this.sleep(requestStartAt - now);
    }
  }

  private async sleep(milliseconds: number) {
    throwIfAborted(this.executionSignal);
    await this.runtime.sleep(milliseconds, this.executionSignal);
    throwIfAborted(this.executionSignal);
  }

  private retryDelayFor(attempt: number, responseMetadata: OzonApiResponseMetadata | null) {
    const exponentialDelay = Math.min(
      RETRY_DELAY_CAP_MS,
      RETRY_DELAY_BASE_MS * 2 ** attempt + Math.floor(this.runtime.random() * RETRY_DELAY_JITTER_MS)
    );

    return Math.min(
      RETRY_DELAY_CAP_MS,
      Math.max(
        exponentialDelay,
        responseMetadata?.retryAfterMs ?? 0,
        responseMetadata?.itemRetryAfterMs ?? 0
      )
    );
  }

  private retryFitsDeadline(delayMs: number) {
    return (
      this.deadlineMs === undefined ||
      this.runtime.now() + delayMs < this.deadlineMs
    );
  }
}

function isTimeoutLike(error: unknown) {
  return (
    error instanceof DOMException &&
    (error.name === "TimeoutError" || error.name === "AbortError")
  );
}

function pacingStateFor(runtime: OzonClientRuntime, clientId: string) {
  let clientStates = PACING_BY_RUNTIME.get(runtime);
  if (!clientStates) {
    clientStates = new Map();
    PACING_BY_RUNTIME.set(runtime, clientStates);
  }
  const key = clientId.trim();
  let pacing = clientStates.get(key);
  if (!pacing) {
    pacing = { nextRequestStartAt: 0, nextTurnoverRequestStartAt: 0 };
    clientStates.set(key, pacing);
  }
  return pacing;
}

export function createDurableOzonClient(
  credentials: OzonCredentials,
  signal: AbortSignal,
  deadlineMs: number
) {
  return new OzonClient(credentials, DEFAULT_RUNTIME, {
    maxAttempts: MAX_ATTEMPTS,
    signal,
    deadlineMs,
  });
}

function normalizeMaxAttempts(value: number | undefined) {
  return Number.isInteger(value) && value !== undefined && value >= 1
    ? Math.min(value, MAX_ATTEMPTS)
    : MAX_ATTEMPTS;
}

function abortableSleep(milliseconds: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortReason(signal));
      return;
    }

    const timer = setTimeout(done, milliseconds);
    const onAbort = () => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      reject(signal ? abortReason(signal) : new DOMException("Aborted", "AbortError"));
    };

    function done() {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }

    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

function combineAbortSignals(
  timeoutSignal: AbortSignal,
  executionSignal?: AbortSignal
) {
  if (!executionSignal) {
    return { signal: timeoutSignal, cleanup: () => {} };
  }

  const controller = new AbortController();
  const signals = [timeoutSignal, executionSignal];
  const listeners = signals.map((source) => {
    const listener = () => controller.abort(abortReason(source));
    if (source.aborted && !controller.signal.aborted) listener();
    else source.addEventListener("abort", listener, { once: true });
    return { source, listener };
  });

  return {
    signal: controller.signal,
    cleanup: () => {
      for (const { source, listener } of listeners) {
        source.removeEventListener("abort", listener);
      }
    },
  };
}

function throwIfAborted(signal?: AbortSignal) {
  if (signal?.aborted) throw abortReason(signal);
}

function abortReason(signal: AbortSignal) {
  return signal.reason instanceof Error
    ? signal.reason
    : new DOMException("Aborted", "AbortError");
}

function ozonApiBaseUrl() {
  return (
    process.env.OZON_API_BASE_URL || DEFAULT_OZON_SELLER_API_BASE_URL
  ).replace(/\/+$/, "");
}

export async function validateOzonCredentials(credentials: OzonCredentials) {
  const client = new OzonClient(credentials);
  return client.request<Record<string, unknown>>("/v2/warehouse/list", {
    limit: 1,
  });
}

async function readResponseBody(response: Response) {
  const text = await response.text();
  if (!text) return {};

  try {
    return JSON.parse(quoteJsonNumbers(text));
  } catch {
    return { text };
  }
}

function quoteJsonNumbers(text: string) {
  let result = "";
  let index = 0;
  let inString = false;
  let escaped = false;

  while (index < text.length) {
    const character = text[index];
    if (inString) {
      result += character;
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      index += 1;
      continue;
    }

    if (character === '"') {
      inString = true;
      result += character;
      index += 1;
      continue;
    }

    if (character === "-" || (character >= "0" && character <= "9")) {
      const match = text
        .slice(index)
        .match(/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/);
      if (match) {
        result += JSON.stringify(match[0]);
        index += match[0].length;
        continue;
      }
    }

    result += character;
    index += 1;
  }

  return result;
}

function isRetryableStatus(status: number) {
  return status === 408 || status === 425 || status === 429 || status >= 500;
}

function responseMetadataFor(response: Response, now: number): OzonApiResponseMetadata {
  return {
    requestId: response.headers.get("x-request-id") || response.headers.get("request-id"),
    retryAfterMs: parseRetryAfter(response.headers.get("retry-after"), now),
    itemRetryAfterMs: parseItemRetryAfter(response.headers.get("item-retry-after")),
  };
}

function parseRetryAfter(value: string | null, now: number) {
  if (!value) return null;

  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.min(RETRY_DELAY_CAP_MS, Math.round(seconds * 1_000));
  }

  const date = Date.parse(value);
  if (Number.isNaN(date)) return null;
  return Math.min(RETRY_DELAY_CAP_MS, Math.max(0, date - now));
}

function parseItemRetryAfter(value: string | null) {
  if (!value) return null;

  const minutes = Number(value);
  if (!Number.isFinite(minutes) || minutes < 0) return null;
  return Math.min(RETRY_DELAY_CAP_MS, Math.round(minutes * 60_000));
}

function emptyResponseMetadata(): OzonApiResponseMetadata {
  return { requestId: null, retryAfterMs: null, itemRetryAfterMs: null };
}

function extractOzonErrorDetails(responseBody: unknown, sensitiveValues: string[]) {
  const topLevel = recordOrNull(responseBody);
  const nestedError = recordOrNull(topLevel?.error);
  const nestedDetails = recordOrNull(topLevel?.details);
  const nestedErrorDetails = recordOrNull(nestedError?.details);
  const candidates = [topLevel, nestedError, nestedDetails, nestedErrorDetails];

  return {
    code: firstSafeCode(candidates),
    apiMessage: firstSafeMessage(candidates, sensitiveValues),
  };
}

function recordOrNull(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function firstSafeCode(candidates: Array<Record<string, unknown> | null>) {
  for (const candidate of candidates) {
    const code = candidate?.code;
    if (typeof code === "number" && Number.isFinite(code)) return code;
    if (
      typeof code === "string" &&
      /^[A-Za-z0-9._:-]{1,80}$/.test(code)
    ) {
      return code;
    }
  }
  return null;
}

function firstSafeMessage(
  candidates: Array<Record<string, unknown> | null>,
  sensitiveValues: string[]
) {
  for (const candidate of candidates) {
    const message = candidate?.message;
    if (typeof message === "string") return sanitizeApiMessage(message, sensitiveValues);
  }
  return null;
}

function sanitizeApiMessage(message: string, sensitiveValues: string[]) {
  let safeMessage = message
    .replace(/[\u0000-\u001F\u007F-\u009F]/g, " ")
    .replace(/\s+/g, " ")
    .trim();

  for (const value of [...sensitiveValues].sort((left, right) => right.length - left.length)) {
    if (value) safeMessage = safeMessage.split(value).join("[REDACTED]");
  }

  safeMessage = safeMessage
    .replace(/\bauthorization\b\s*(?:=|:)\s*(?:basic|bearer)\s+[^\s,;]+/gi, "authorization=[REDACTED]")
    .replace(
      /\b(authorization|api[-_ ]?key|client[-_ ]?id|token|jwt)\b\s*(?:=|:)\s*(?:bearer\s+)?(?:"[^"]*"|'[^']*'|[^\s,;]+)/gi,
      "$1=[REDACTED]"
    )
    .replace(/\bBearer\s+[A-Za-z0-9\-._~+/=]{8,}\b/gi, "Bearer [REDACTED]")
    .replace(/\beyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, "[REDACTED]")
    .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, "[REDACTED]")
    .replace(/(^|[^\w])\+?\d[\d(). -]{7,}\d/g, "$1[REDACTED]")
    .replace(/([?&][^=&\s]+)=([^&#\s]*)/g, "$1=[REDACTED]")
    .replace(/\b[A-Za-z0-9_-]{32,}\b/g, "[REDACTED]");

  return safeMessage.slice(0, MAX_SAFE_API_MESSAGE_LENGTH);
}
