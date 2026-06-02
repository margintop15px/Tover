import type { OzonCredentials } from "./types";

const DEFAULT_OZON_SELLER_API_BASE_URL = "https://api-seller.ozon.ru";

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

export class OzonApiError extends Error {
  status: number;
  endpoint: string;
  responseBody: unknown;

  constructor(endpoint: string, status: number, responseBody: unknown) {
    super(`Ozon API ${endpoint} failed with status ${status}`);
    this.status = status;
    this.endpoint = endpoint;
    this.responseBody = responseBody;
  }
}

export class OzonClient {
  private credentials: OzonCredentials;

  constructor(credentials: OzonCredentials) {
    this.credentials = credentials;
  }

  async request<T>(
    endpoint: OzonReadOnlyEndpoint,
    body: Record<string, unknown> = {}
  ): Promise<T> {
    if (!READ_ONLY_ENDPOINT_SET.has(endpoint)) {
      throw new Error(`Ozon endpoint is not allowlisted: ${endpoint}`);
    }

    const response = await fetch(`${ozonApiBaseUrl()}${endpoint}`, {
      method: "POST",
      headers: {
        "Client-Id": this.credentials.clientId,
        "Api-Key": this.credentials.apiKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      cache: "no-store",
    });

    const responseBody = await readResponseBody(response);

    if (!response.ok) {
      throw new OzonApiError(endpoint, response.status, responseBody);
    }

    return responseBody as T;
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
