import assert from "node:assert/strict";
import test from "node:test";

import { OzonApiError } from "../../src/lib/ozon/client";
import * as sync from "../../src/lib/ozon/sync";
import {
  buildOzonFixture,
  startOzonMockServer,
} from "../e2e/ozon-mock-server";

type FetchSupplyOrders = (client: {
  request: <T>(endpoint: string, body: Record<string, unknown>) => Promise<T>;
}) => Promise<unknown[]>;

const fetchSupplyOrders = (sync as unknown as {
  fetchSupplyOrders: FetchSupplyOrders;
}).fetchSupplyOrders;

const isMissingFinanceDocumentError = (sync as unknown as {
  isMissingFinanceDocumentError: (error: unknown) => boolean;
}).isMissingFinanceDocumentError;

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
      return { result: { orders: [{ order_id: "one" }] } } as T;
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
          orders: (body.order_ids as string[]).map((order_id) => ({ order_id })),
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

test("fetchSupplyOrders skips IDs omitted from a partial detail batch", async () => {
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

  assert.deepEqual(await fetchSupplyOrders(client), [
    { order_id: "one", order_number: "detail-one" },
  ]);
});

test("fetchSupplyOrders skips an entire failed detail batch", async () => {
  const client = {
    request: async <T>(endpoint: string) => {
      if (endpoint === "/v3/supply-order/list") {
        return { result: { order_ids: ["one"], last_id: "" } } as T;
      }
      throw new Error("detail request failed");
    },
  };

  assert.deepEqual(await fetchSupplyOrders(client), []);
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
