import assert from "node:assert/strict";
import test from "node:test";

import {
  OzonApiError,
  OzonClient,
  OzonIncompleteResponseError,
  OzonInvariantError,
} from "../../src/lib/ozon/client";
import * as sync from "../../src/lib/ozon/sync";
import {
  buildOzonFixture,
  startOzonMockServer,
} from "../e2e/ozon-mock-server";

type FetchSupplyOrders = (client: {
  request: <T>(endpoint: string, body: Record<string, unknown>) => Promise<T>;
}) => Promise<unknown[]>;

type FetchDiscountedProducts = (
  client: {
    request: <T>(endpoint: string, body: Record<string, unknown>) => Promise<T>;
  },
  discountedSkus: string[]
) => Promise<unknown[]>;

type DiscoverDiscountedSkus = (
  client: {
    request: <T>(endpoint: string, body: Record<string, unknown>) => Promise<T>;
    executionAbortSignal?: () => AbortSignal | undefined;
  },
  runtime: {
    now: () => number;
    fetchText: (url: string, signal?: AbortSignal) => Promise<string>;
  } | undefined
) => Promise<string[]>;

type DownloadOzonReportText = (
  url: string,
  executionSignal: AbortSignal | undefined,
  runtime: {
    fetch: typeof fetch;
    timeoutSignal: (milliseconds: number) => AbortSignal;
    maxBytes?: number;
  }
) => Promise<string>;

const fetchSupplyOrders = (sync as unknown as {
  fetchSupplyOrders: FetchSupplyOrders;
}).fetchSupplyOrders;

const fetchDiscountedProducts = (sync as unknown as {
  fetchDiscountedProducts: FetchDiscountedProducts;
}).fetchDiscountedProducts;

const selectDiscountedSkus = (sync as unknown as {
  selectDiscountedSkus: (products: Record<string, unknown>[]) => string[];
}).selectDiscountedSkus;

const discoverDiscountedSkus = (sync as unknown as {
  discoverDiscountedSkus: DiscoverDiscountedSkus;
}).discoverDiscountedSkus;

const downloadOzonReportText = (sync as unknown as {
  downloadOzonReportText: DownloadOzonReportText;
}).downloadOzonReportText;

const discountedDamageEvidence = (sync as unknown as {
  discountedDamageEvidence: (item: Record<string, unknown>) => string | null;
}).discountedDamageEvidence;

const isDefectReason = (sync as unknown as {
  isDefectReason: (reason: string) => boolean;
}).isDefectReason;

const isMissingFinanceDocumentError = (sync as unknown as {
  isMissingFinanceDocumentError: (error: unknown) => boolean;
}).isMissingFinanceDocumentError;

test("selectDiscountedSkus excludes ordinary products and discounted analog parents", () => {
  const products = [
    {
      sku: "320067758",
      raw_payload: {
        source: {
          sku: 320067758,
          has_discounted_item: true,
          is_discounted: false,
          discounted_stocks: { coming: 0, present: 1, reserved: 0 },
        },
      },
    },
    {
      sku: "635548518",
      raw_payload: {
        source: {
          sku: 635548518,
          has_discounted_item: false,
          is_discounted: true,
          discounted_stocks: { coming: 0, present: 1, reserved: 0 },
        },
      },
    },
    {
      sku: "ordinary-without-details",
      raw_payload: {},
    },
  ];

  assert.deepEqual(selectDiscountedSkus(products), ["635548518"]);
});

test("fetchDiscountedProducts uses Ozon's discounted_skus request contract", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      return {
        items: [
          {
            discounted_sku: 635548518,
            sku: 320067758,
            condition: "used",
          },
        ],
      } as T;
    },
  };

  const products = await fetchDiscountedProducts(client, ["635548518"]);

  assert.deepEqual(requests, [
    {
      endpoint: "/v1/product/info/discounted",
      body: { discounted_skus: ["635548518"] },
    },
  ]);
  assert.deepEqual(products, [
    {
      discounted_sku: 635548518,
      sku: 320067758,
      condition: "used",
    },
  ]);
});

test("fetchDiscountedProducts propagates an Ozon request failure", async () => {
  const failure = new OzonApiError("/v1/product/info/discounted", 400, {
    code: 3,
    message: "Request validation error",
  });
  const client = {
    request: async () => {
      throw failure;
    },
  };

  await assert.rejects(
    fetchDiscountedProducts(client, ["635548518"]),
    (error: unknown) => error === failure
  );
});

test("discoverDiscountedSkus reads authoritative discounted IDs from the latest Ozon report", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const downloadedUrls: string[] = [];
  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      return {
        result: {
          reports: [
            {
              code: "REPORT-DISCOUNTED",
              report_type: "SELLER_PRODUCT_DISCOUNTED",
              status: "success",
              created_at: "2026-07-27T00:00:00.000Z",
              file: "https://cdn1.ozone.ru/reports/discounted.csv",
            },
          ],
        },
      } as T;
    },
  };

  const skus = await discoverDiscountedSkus(client, {
    now: () => Date.parse("2026-07-27T00:05:00.000Z"),
    fetchText: async (url) => {
      downloadedUrls.push(url);
      return [
        "SKU основного товара;SKU уценённого товара",
        "320067758;635548518",
        "320067759;635548519",
      ].join("\n");
    },
  });

  assert.deepEqual(requests, [
    {
      endpoint: "/v1/report/list",
      body: {
        page: 0,
        page_size: 100,
        report_type: "SELLER_PRODUCT_DISCOUNTED",
      },
    },
  ]);
  assert.deepEqual(downloadedUrls, [
    "https://cdn1.ozone.ru/reports/discounted.csv",
  ]);
  assert.deepEqual(skus, ["635548518", "635548519"]);
});

test("discoverDiscountedSkus passes the durable step deadline to the report download", async () => {
  const controller = new AbortController();
  let downloadSignal: AbortSignal | undefined;
  const client = {
    executionAbortSignal: () => controller.signal,
    request: async <T>() =>
      ({
        result: {
          reports: [
            {
              code: "REPORT-DISCOUNTED",
              report_type: "SELLER_PRODUCT_DISCOUNTED",
              status: "success",
              created_at: "2026-07-27T00:00:00.000Z",
              file: "https://cdn1.ozone.ru/reports/discounted.csv",
            },
          ],
        },
      }) as T,
  };

  await discoverDiscountedSkus(client, {
    now: () => Date.parse("2026-07-27T00:05:00.000Z"),
    fetchText: async (_url, signal) => {
      downloadSignal = signal;
      return [
        "SKU основного товара;SKU уценённого товара",
        "320067758;635548518",
      ].join("\n");
    },
  });

  assert.equal(downloadSignal, controller.signal);
});

test("discoverDiscountedSkus creates a missing report and exposes processing as a durable retry", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const responses = [
    { result: { reports: [] } },
    { code: "REPORT-NEW" },
    {
      result: {
        code: "REPORT-NEW",
        report_type: "SELLER_PRODUCT_DISCOUNTED",
        status: "processing",
      },
    },
  ];
  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      return responses.shift() as T;
    },
  };

  await assert.rejects(
    discoverDiscountedSkus(client, {
      now: () => Date.parse("2026-07-27T00:05:00.000Z"),
      fetchText: async () => {
        throw new Error("report should not be downloaded while processing");
      },
    }),
    OzonIncompleteResponseError
  );
  assert.deepEqual(requests, [
    {
      endpoint: "/v1/report/list",
      body: {
        page: 0,
        page_size: 100,
        report_type: "SELLER_PRODUCT_DISCOUNTED",
      },
    },
    {
      endpoint: "/v1/report/discounted/create",
      body: {},
    },
    {
      endpoint: "/v1/report/info",
      body: { code: "REPORT-NEW" },
    },
  ]);
});

test("fetchDiscountedProducts batches 101 IDs and preserves response order", async () => {
  const batchSizes: number[] = [];
  const client = {
    request: async <T>(
      _endpoint: string,
      body: Record<string, unknown>
    ) => {
      const discountedSkus = body.discounted_skus as string[];
      batchSizes.push(discountedSkus.length);
      return {
        result: {
          items: discountedSkus.map((discounted_sku) => ({ discounted_sku })),
        },
      } as T;
    },
  };
  const discountedSkus = Array.from(
    { length: 101 },
    (_, index) => String(635548500 + index)
  );

  const products = await fetchDiscountedProducts(client, discountedSkus);

  assert.deepEqual(batchSizes, [100, 1]);
  assert.deepEqual(
    products.map((product) => (product as Record<string, unknown>).discounted_sku),
    discountedSkus
  );
});

test("discounted report discovery and detail lookup agree on the same SKU over HTTP", async () => {
  const fixture = buildOzonFixture("unit-discounted-report");
  const previousBaseUrl = process.env.OZON_API_BASE_URL;
  process.env.OZON_API_BASE_URL = "http://127.0.0.1:32124";
  const mock = await startOzonMockServer(fixture);

  try {
    const client = new OzonClient({
      clientId: "ozon-client",
      apiKey: "ozon-api-key",
    });
    const discountedSkus = await discoverDiscountedSkus(client, undefined);
    const products = await fetchDiscountedProducts(client, discountedSkus);

    assert.deepEqual(discountedSkus, [fixture.discountedSku]);
    assert.deepEqual(
      mock.requestBodies["/v1/product/info/discounted"],
      [{ discounted_skus: [fixture.discountedSku] }]
    );
    assert.equal(
      (products[0] as Record<string, unknown>).discounted_sku,
      fixture.discountedSku
    );
  } finally {
    await mock.close();
    if (previousBaseUrl === undefined) {
      delete process.env.OZON_API_BASE_URL;
    } else {
      process.env.OZON_API_BASE_URL = previousBaseUrl;
    }
  }
});

test("discounted report download rejects an untrusted redirect before following it", async () => {
  const requests: Array<{ url: string; redirect: RequestRedirect | undefined }> = [];

  await assert.rejects(
    downloadOzonReportText(
      "https://cdn1.ozone.ru/reports/discounted.csv",
      undefined,
      {
        fetch: async (input, init) => {
          requests.push({
            url: String(input),
            redirect: init?.redirect,
          });
          return new Response(null, {
            status: 302,
            headers: {
              location: "https://internal.example/reports/private.csv",
            },
          });
        },
        timeoutSignal: () => new AbortController().signal,
      }
    ),
    OzonInvariantError
  );

  assert.deepEqual(requests, [
    {
      url: "https://cdn1.ozone.ru/reports/discounted.csv",
      redirect: "manual",
    },
  ]);
});

test("discounted report download enforces its byte limit while streaming", async () => {
  let pulls = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      pulls += 1;
      if (pulls === 1) {
        controller.enqueue(new TextEncoder().encode("1234"));
        return;
      }
      controller.enqueue(new TextEncoder().encode("56"));
      controller.close();
    },
  });

  await assert.rejects(
    downloadOzonReportText(
      "https://cdn1.ozone.ru/reports/discounted.csv",
      undefined,
      {
        fetch: async () => new Response(body, { status: 200 }),
        timeoutSignal: () => new AbortController().signal,
        maxBytes: 5,
      }
    ),
    OzonInvariantError
  );
  assert.equal(pulls, 2);
});

test("discounted report download honors the durable step deadline", async () => {
  const deadline = new DOMException("Step deadline exceeded", "TimeoutError");
  const controller = new AbortController();
  controller.abort(deadline);
  let requested = false;

  await assert.rejects(
    downloadOzonReportText(
      "https://cdn1.ozone.ru/reports/discounted.csv",
      controller.signal,
      {
        fetch: async () => {
          requested = true;
          return new Response("not reached");
        },
        timeoutSignal: () => new AbortController().signal,
      }
    ),
    (error: unknown) => error === deadline
  );
  assert.equal(requested, false);
});

test("discounted damage evidence uses Ozon's damaged-product fields and Russian reasons", () => {
  const evidence = discountedDamageEvidence({
    condition: "used",
    condition_estimation: "good",
    reason_damaged: "Повреждение товара",
    comment_reason_damaged: "Царапины на корпусе",
    defects: "Вмятина",
    mechanical_damage: "Есть",
    package_damage: "Повреждена упаковка",
    packaging_violation: "Есть",
  });

  assert.equal(
    evidence,
    [
      "Повреждение товара",
      "Царапины на корпусе",
      "Вмятина",
      "Есть",
      "Повреждена упаковка",
      "Есть",
      "used",
      "good",
    ].join("; ")
  );
  assert.equal(isDefectReason(evidence ?? ""), true);
});

test("fetchSupplyOrders sends the documented list payload, follows cursor pages, and batches canonical IDs by 50", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const listResponses = [
    { order_ids: [1, "2"], last_id: "next" },
    { result: { order_ids: ["3"], last_id: "" } },
  ];

  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      if (endpoint === "/v3/supply-order/list") return listResponses.shift() as T;
      return {
        result: {
          orders: (body.order_ids as string[]).map((order_id) => ({
            order_id,
            order_number: `detail-${order_id}`,
          })),
        },
      } as T;
    },
  };

  const orders = await fetchSupplyOrders(client);

  assert.deepEqual(requests, [
    {
      endpoint: "/v3/supply-order/list",
      body: { filter: {}, last_id: "", limit: 100, sort_by: "ORDER_CREATION", sort_dir: "DESC" },
    },
    {
      endpoint: "/v3/supply-order/list",
      body: { filter: {}, last_id: "next", limit: 100, sort_by: "ORDER_CREATION", sort_dir: "DESC" },
    },
    { endpoint: "/v3/supply-order/get", body: { order_ids: ["1", "2", "3"] } },
  ]);
  assert.deepEqual(orders, [
    { order_id: "1", order_number: "detail-1" },
    { order_id: "2", order_number: "detail-2" },
    { order_id: "3", order_number: "detail-3" },
  ]);
});

test("fetchSupplyOrders stops before requesting a repeated cursor", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      if (endpoint === "/v3/supply-order/list") {
        return { result: { order_ids: ["one"], last_id: "again" } } as T;
      }
      return {
        result: {
          orders: [{ order_id: "one", order_number: "detail-one" }],
        },
      } as T;
    },
  };

  await fetchSupplyOrders(client);

  assert.equal(requests.filter((request) => request.endpoint === "/v3/supply-order/list").length, 2);
  assert.deepEqual(requests.at(-1), {
    endpoint: "/v3/supply-order/get",
    body: { order_ids: ["one"] },
  });
});

test("fetchSupplyOrders stops when a real order_ids page is empty even if it has a cursor", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      return { result: { order_ids: [], last_id: "unexpected-next-page" } } as T;
    },
  };

  assert.deepEqual(await fetchSupplyOrders(client), []);
  assert.deepEqual(requests, [
    {
      endpoint: "/v3/supply-order/list",
      body: { filter: {}, last_id: "", limit: 100, sort_by: "ORDER_CREATION", sort_dir: "DESC" },
    },
  ]);
});

test("fetchSupplyOrders splits more than 50 order IDs into requests of at most 50", async () => {
  const requests: Array<{ endpoint: string; body: Record<string, unknown> }> = [];
  const ids = Array.from({ length: 51 }, (_, index) => index + 1);
  const client = {
    request: async <T>(endpoint: string, body: Record<string, unknown>) => {
      requests.push({ endpoint, body });
      if (endpoint === "/v3/supply-order/list") {
        return { result: { order_ids: ids, last_id: "" } } as T;
      }
      return {
        result: {
          orders: (body.order_ids as string[]).map((order_id) => ({
            order_id,
            order_number: `detail-${order_id}`,
          })),
        },
      } as T;
    },
  };

  await fetchSupplyOrders(client);

  const batches = requests.filter((request) => request.endpoint === "/v3/supply-order/get");
  assert.deepEqual(batches.map((batch) => (batch.body.order_ids as unknown[]).length), [50, 1]);
  assert.deepEqual(batches[0].body.order_ids, ids.slice(0, 50).map(String));
  assert.deepEqual(batches[1].body.order_ids, ["51"]);
});

test("fetchSupplyOrders fails when a partial detail batch leaves an ID unresolved", async () => {
  const client = {
    request: async <T>(endpoint: string) => {
      if (endpoint === "/v3/supply-order/list") {
        return {
          result: {
            order_ids: ["one", 2],
            last_id: "",
          },
        } as T;
      }
      return {
        result: { orders: [{ order_id: "one", order_number: "detail-one" }] },
      } as T;
    },
  };

  await assert.rejects(
    fetchSupplyOrders(client),
    /Ozon supply-order details were incomplete/
  );
});

test("fetchSupplyOrders propagates an entire failed detail batch", async () => {
  const failure = new TypeError("detail request failed");
  const client = {
    request: async <T>(endpoint: string) => {
      if (endpoint === "/v3/supply-order/list") {
        return { result: { order_ids: ["one"], last_id: "" } } as T;
      }
      throw failure;
    },
  };

  await assert.rejects(
    fetchSupplyOrders(client),
    (error: unknown) => error === failure
  );
});

test("fetchSupplyOrders uses a genuinely detailed legacy list record when its detail is missing", async () => {
  const client = {
    request: async <T>(endpoint: string) => {
      if (endpoint === "/v3/supply-order/list") {
        return {
          result: {
            orders: [{ order_id: "legacy", order_number: "legacy-order" }],
            last_id: "",
          },
        } as T;
      }
      return { result: { orders: [] } } as T;
    },
  };

  assert.deepEqual(await fetchSupplyOrders(client), [
    { order_id: "legacy", order_number: "legacy-order" },
  ]);
});

test("isMissingFinanceDocumentError accepts only a safe 404 finance-document-not-found error", () => {
  const matching = new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "FINANCE_DOCUMENT_NOT_FOUND", message: "Finance document not found" },
  });
  const wrongStatus = new OzonApiError("/v1/finance/mutual-settlement", 500, {
    error: { code: "FINANCE_DOCUMENT_NOT_FOUND", message: "Finance document not found" },
  });
  const wrongMessage = new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "NOT_FOUND", message: "Supply order not found" },
  });
  const nestedTerminalDescription = new OzonApiError(
    "/v1/finance/mutual-settlement",
    404,
    {
      error: {
        code: "NOT_FOUND",
        message:
          "service.CreateMutualSettlementReport: createMetazonMarketplaceSSRS: getFinanceDocumentID: rpc error: code = NotFound desc = finance document not found",
      },
    }
  );
  const prefixedMessage = new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "NOT_FOUND", message: "temporary finance document not found" },
  });
  const suffixedMessage = new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "NOT_FOUND", message: "finance document not found later" },
  });
  const suffixedDescription = new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "NOT_FOUND", message: "rpc error: desc = finance document not found later" },
  });
  const prefixedCode = new OzonApiError("/v1/finance/mutual-settlement", 404, {
    error: { code: "X_FINANCE_DOCUMENT_NOT_FOUND", message: "not found" },
  });

  assert.equal(isMissingFinanceDocumentError(matching), true);
  assert.equal(isMissingFinanceDocumentError(wrongStatus), false);
  assert.equal(isMissingFinanceDocumentError(wrongMessage), false);
  assert.equal(isMissingFinanceDocumentError(nestedTerminalDescription), true);
  assert.equal(isMissingFinanceDocumentError(prefixedMessage), false);
  assert.equal(isMissingFinanceDocumentError(suffixedMessage), false);
  assert.equal(isMissingFinanceDocumentError(suffixedDescription), false);
  assert.equal(isMissingFinanceDocumentError(prefixedCode), false);
});

test("Ozon mock consumes per-path response sequences and records request counts and bodies", async () => {
  const fixture = buildOzonFixture("unit-response-sequence");
  const mock = await startOzonMockServer(fixture, {
    responseSequences: {
      "/v1/finance/mutual-settlement": [
        {
          status: 404,
          headers: { "x-request-id": "missing-finance-document" },
          body: {
            error: {
              code: "FINANCE_DOCUMENT_NOT_FOUND",
              message: "finance document not found",
            },
          },
        },
        { status: 200, body: { result: { code: "second-attempt" } } },
      ],
    },
  });

  try {
    const request = (body: Record<string, unknown>) =>
      fetch(`${mock.url}/v1/finance/mutual-settlement`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Client-Id": "ozon-client",
          "Api-Key": "ozon-api-key",
        },
        body: JSON.stringify(body),
      });

    const first = await request({ date: "2099-05", language: "DEFAULT" });
    const second = await request({ date: "2099-06", language: "DEFAULT" });

    assert.equal(first.status, 404);
    assert.equal(first.headers.get("x-request-id"), "missing-finance-document");
    assert.deepEqual(await first.json(), {
      error: {
        code: "FINANCE_DOCUMENT_NOT_FOUND",
        message: "finance document not found",
      },
    });
    assert.equal(second.status, 200);
    assert.equal(mock.requestCounts["/v1/finance/mutual-settlement"], 2);
    assert.deepEqual(mock.requestBodies["/v1/finance/mutual-settlement"], [
      { date: "2099-05", language: "DEFAULT" },
      { date: "2099-06", language: "DEFAULT" },
    ]);
  } finally {
    await mock.close();
  }
});
