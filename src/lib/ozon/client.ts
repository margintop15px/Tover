import type { OzonCredentials } from "./types";

const DEFAULT_OZON_SELLER_API_BASE_URL = "https://api-seller.ozon.ru";
const REQUEST_START_PACING_MS = 25;
const REQUEST_TIMEOUT_MS = 30_000;
const MAX_ATTEMPTS = 4;
const RETRY_DELAY_CAP_MS = 30_000;
const RETRY_DELAY_BASE_MS = 1_000;
const RETRY_DELAY_JITTER_MS = 500;

export const OZON_READ_ONLY_ENDPOINTS = [
  "/v2/warehouse/list",
  "/v3/product/list",
  "/v3/product/info/list",
  "/v4/product/info/attributes",
  "/v4/product/info/stocks",
  "/v5/product/info/prices",
  "/v1/product/info/discounted",
  "/v1/product/info/warehouse/stocks",
  "/v4/posting/fbs/list",
  "/v3/posting/fbs/get",
  "/v3/posting/fbo/list",
  "/v2/posting/fbo/get",
  "/v1/returns/list",
  "/v2/returns/rfbs/list",
  "/v2/returns/rfbs/get",
  "/v1/posting/unpaid-legal/product/list",
  "/v1/finance/accrual/postings",
  "/v1/finance/accrual/types",
  "/v1/finance/accrual/by-day",
  "/v2/finance/realization",
  "/v1/finance/realization/posting",
  "/v1/finance/document-b2b-sales",
  "/v1/finance/document-b2b-sales/json",
  "/v1/finance/cash-flow-statement/list",
  "/v1/finance/mutual-settlement",
  "/v1/finance/products/buyout",
  "/v1/finance/compensation",
  "/v1/finance/decompensation",
  "/v1/report/postings/create",
  "/v1/report/products/create",
  "/v2/report/returns/create",
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
  "/v1/description-category/tree",
  "/v1/description-category/attribute",
  "/v1/description-category/attribute/values",
  "/v1/description-category/attribute/values/search",
] as const;

export type OzonReadOnlyEndpoint = (typeof OZON_READ_ONLY_ENDPOINTS)[number];

const READ_ONLY_ENDPOINT_SET = new Set<string>(OZON_READ_ONLY_ENDPOINTS);

export interface OzonApiResponseMetadata {
  requestId: string | null;
  retryAfterMs: number | null;
  itemRetryAfterMs: number | null;
}

export class OzonApiError extends Error {
  status: number;
  endpoint: string;
  responseBody: unknown;
  responseMetadata: OzonApiResponseMetadata;
  retryDelayMs: number;

  constructor(
    endpoint: string,
    status: number,
    responseBody: unknown,
    responseMetadata: OzonApiResponseMetadata,
    retryDelayMs: number
  ) {
    super(`Ozon API ${endpoint} failed with status ${status}`);
    this.status = status;
    this.endpoint = endpoint;
    this.responseBody = responseBody;
    this.responseMetadata = responseMetadata;
    this.retryDelayMs = retryDelayMs;
  }
}

interface OzonClientRuntime {
  fetch: typeof fetch;
  now: () => number;
  sleep: (milliseconds: number) => Promise<void>;
  random: () => number;
  timeoutSignal: (milliseconds: number) => AbortSignal;
}

const DEFAULT_RUNTIME: OzonClientRuntime = {
  fetch,
  now: Date.now,
  sleep: (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  random: Math.random,
  timeoutSignal: (milliseconds) => AbortSignal.timeout(milliseconds),
};

export class OzonClient {
  private credentials: OzonCredentials;
  private runtime: OzonClientRuntime;
  private nextRequestStartAt = 0;

  constructor(credentials: OzonCredentials, runtime: OzonClientRuntime = DEFAULT_RUNTIME) {
    this.credentials = credentials;
    this.runtime = runtime;
  }

  async request<T>(
    endpoint: OzonReadOnlyEndpoint,
    body: Record<string, unknown> = {}
  ): Promise<T> {
    if (!READ_ONLY_ENDPOINT_SET.has(endpoint)) {
      throw new Error(`Ozon endpoint is not allowlisted: ${endpoint}`);
    }

    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt += 1) {
      await this.waitForRequestStart();

      let response: Response;
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
          signal: this.runtime.timeoutSignal(REQUEST_TIMEOUT_MS),
        });
      } catch (error) {
        if (attempt === MAX_ATTEMPTS - 1) throw error;
        await this.runtime.sleep(this.retryDelayFor(attempt, null));
        continue;
      }

      const responseBody = await readResponseBody(response);
      const responseMetadata = responseMetadataFor(response, this.runtime.now());
      const retryDelayMs = this.retryDelayFor(attempt, responseMetadata);

      if (response.ok) return responseBody as T;

      const error = new OzonApiError(
        endpoint,
        response.status,
        responseBody,
        responseMetadata,
        retryDelayMs
      );

      if (!isRetryableStatus(response.status) || attempt === MAX_ATTEMPTS - 1) {
        throw error;
      }

      await this.runtime.sleep(retryDelayMs);
    }

    throw new Error("Ozon request retry loop exited unexpectedly");
  }

  private async waitForRequestStart() {
    const now = this.runtime.now();
    const requestStartAt = Math.max(now, this.nextRequestStartAt);
    this.nextRequestStartAt = requestStartAt + REQUEST_START_PACING_MS;

    if (requestStartAt > now) {
      await this.runtime.sleep(requestStartAt - now);
    }
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
}

function ozonApiBaseUrl() {
  return (
    process.env.OZON_API_BASE_URL || DEFAULT_OZON_SELLER_API_BASE_URL
  ).replace(/\/+$/, "");
}

export async function validateOzonCredentials(credentials: OzonCredentials) {
  const client = new OzonClient(credentials);
  return client.request<Record<string, unknown>>("/v2/warehouse/list", {});
}

async function readResponseBody(response: Response) {
  const text = await response.text();
  if (!text) return {};

  try {
    return JSON.parse(text);
  } catch {
    return { text };
  }
}

function isRetryableStatus(status: number) {
  return status === 408 || status === 425 || status === 429 || status >= 500;
}

function responseMetadataFor(response: Response, now: number): OzonApiResponseMetadata {
  return {
    requestId: response.headers.get("x-request-id") || response.headers.get("request-id"),
    retryAfterMs: parseRetryAfter(response.headers.get("retry-after"), now),
    itemRetryAfterMs: parseRetryAfter(response.headers.get("item-retry-after"), now),
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
