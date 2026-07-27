import http, { type IncomingMessage, type ServerResponse } from "node:http";

export interface OzonMockProduct {
  productId: string;
  offerId: string;
  sku: string;
  name: string;
  price: string;
}

export interface OzonMockWarehouse {
  id: string;
  name: string;
}

export interface OzonMockFixture {
  runId: string;
  autoWarehouse: OzonMockWarehouse;
  returnWarehouse: OzonMockWarehouse;
  autoProduct: OzonMockProduct;
  missingProduct: OzonMockProduct;
  returnProduct: OzonMockProduct;
  fbsPostingNumber: string;
  fboPostingNumber: string;
  canceledPostingNumber: string;
  returnId: string;
  financeTransactionId: string;
  legalInvoiceId: string;
  unpaidLegalProductId: string;
  removalId: string;
  supplyOrderId: string;
  supplyBundleId: string;
  discountedSku: string;
  dateFrom: string;
  dateTo: string;
}

export interface OzonMockRequest {
  path: string;
  body: Record<string, unknown>;
  apiKey: string | undefined;
  clientId: string | undefined;
}

export interface OzonMockResponse {
  status: number;
  headers?: Record<string, string>;
  body: unknown;
}

export interface OzonMockServerOptions {
  failPaths?: string[];
  responseSequences?: Record<string, OzonMockResponse[]>;
  validApiKey?: string;
  validClientId?: string;
}

export interface OzonMockServer {
  url: string;
  requests: OzonMockRequest[];
  requestCounts: Record<string, number>;
  requestBodies: Record<string, Record<string, unknown>[]>;
  close: () => Promise<void>;
}

const DEFAULT_MOCK_PORT = 32123;

export function ozonMockBaseUrl() {
  return (
    process.env.OZON_API_BASE_URL ||
    `http://127.0.0.1:${process.env.OZON_MOCK_PORT || DEFAULT_MOCK_PORT}`
  ).replace(/\/+$/, "");
}

export function buildOzonFixture(runId: string): OzonMockFixture {
  const suffix = runId.replace(/[^a-z0-9]/gi, "").slice(-10);
  const numericBase = String(Date.now()).slice(-8);

  return {
    runId,
    autoWarehouse: {
      id: `wh-auto-${suffix}`,
      name: `Ozon Main ${suffix}`,
    },
    returnWarehouse: {
      id: `wh-return-${suffix}`,
      name: `Ozon Return ${suffix}`,
    },
    autoProduct: {
      productId: `${numericBase}1`,
      offerId: `AUTO-${suffix}`,
      sku: `AUTO-SKU-${suffix}`,
      name: `Auto Ozon Product ${suffix}`,
      price: "10.50",
    },
    missingProduct: {
      productId: `${numericBase}2`,
      offerId: `MISS-${suffix}`,
      sku: `MISS-SKU-${suffix}`,
      name: `Missing Ozon Product ${suffix}`,
      price: "20.00",
    },
    returnProduct: {
      productId: `${numericBase}3`,
      offerId: `RET-${suffix}`,
      sku: `RET-SKU-${suffix}`,
      name: `Return Ozon Product ${suffix}`,
      price: "7.25",
    },
    fbsPostingNumber: `FBS-${suffix}`,
    fboPostingNumber: `FBO-${suffix}`,
    canceledPostingNumber: `CANCEL-${suffix}`,
    returnId: `RETURN-${suffix}`,
    financeTransactionId: `FIN-${suffix}`,
    legalInvoiceId: `B2B-${suffix}`,
    unpaidLegalProductId: `UNPAID-${suffix}`,
    removalId: `REMOVAL-${suffix}`,
    supplyOrderId: `SUPPLY-${suffix}`,
    supplyBundleId: `BUNDLE-${suffix}`,
    discountedSku: `${numericBase}4`,
    dateFrom: "2099-05-01T00:00:00.000Z",
    dateTo: "2099-05-05T00:00:00.000Z",
  };
}

export async function startOzonMockServer(
  fixture: OzonMockFixture,
  options: OzonMockServerOptions = {}
): Promise<OzonMockServer> {
  const validApiKey = options.validApiKey || "ozon-api-key";
  const validClientId = options.validClientId || "ozon-client";
  const failPaths = new Set(options.failPaths || []);
  const responseSequences = new Map(
    Object.entries(options.responseSequences || {}).map(([path, responses]) => [
      path,
      [...responses],
    ])
  );
  const url = new URL(ozonMockBaseUrl());
  const requests: OzonMockRequest[] = [];
  const requestCounts: Record<string, number> = {};
  const requestBodies: Record<string, Record<string, unknown>[]> = {};

  const server = http.createServer(async (request, response) => {
    const path = new URL(request.url || "/", url).pathname;
    if (request.method === "GET" && path === "/reports/discounted.csv") {
      writeText(
        response,
        200,
        [
          "SKU основного товара;SKU уценённого товара",
          `${fixture.autoProduct.sku};${fixture.discountedSku}`,
        ].join("\n"),
        "text/csv; charset=utf-8"
      );
      return;
    }

    const body = await readJsonBody(request);
    const apiKey = request.headers["api-key"]?.toString();
    const clientId = request.headers["client-id"]?.toString();
    requests.push({ path, body, apiKey, clientId });
    requestCounts[path] = (requestCounts[path] || 0) + 1;
    (requestBodies[path] ||= []).push(body);

    if (request.method !== "POST") {
      writeJson(response, 405, { error: "method not allowed" });
      return;
    }

    if (apiKey !== validApiKey || clientId !== validClientId) {
      writeJson(response, 403, { error: { message: "invalid credentials" } });
      return;
    }

    const sequence = responseSequences.get(path);
    const sequenceResponse = sequence?.shift();
    if (sequenceResponse) {
      writeJson(
        response,
        sequenceResponse.status,
        sequenceResponse.body,
        sequenceResponse.headers
      );
      return;
    }

    if (failPaths.has(path)) {
      writeJson(response, 500, { error: { message: `forced failure for ${path}` } });
      return;
    }

    writeJson(response, 200, responseFor(path, body, fixture));
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(Number(url.port), url.hostname, () => {
      server.off("error", reject);
      resolve();
    });
  });

  return {
    url: url.toString().replace(/\/+$/, ""),
    requests,
    requestCounts,
    requestBodies,
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()));
      }),
  };
}

function responseFor(
  path: string,
  body: Record<string, unknown>,
  fixture: OzonMockFixture
) {
  switch (path) {
    case "/v2/warehouse/list":
      return {
        result: {
          warehouses: [
          {
            warehouse_id: fixture.autoWarehouse.id,
            name: fixture.autoWarehouse.name,
            status: "active",
          },
          {
            warehouse_id: fixture.returnWarehouse.id,
            name: fixture.returnWarehouse.name,
            status: "active",
          },
          ],
          has_next: false,
          cursor: "",
        },
      };
    case "/v1/warehouse/ozon/list":
      return { result: { warehouses: [] } };
    case "/v3/product/list":
      return {
        result: {
          items: [productRef(fixture.autoProduct), productRef(fixture.missingProduct), productRef(fixture.returnProduct)],
          last_id: "",
        },
      };
    case "/v3/product/info/list":
      return {
        result: {
          items: [
            productInfo(fixture.autoProduct),
            productInfo(fixture.missingProduct),
            productInfo(fixture.returnProduct),
          ],
        },
      };
    case "/v4/product/info/attributes":
      return {
        result: {
          items: [
            productAttributes(fixture.autoProduct),
            productAttributes(fixture.missingProduct),
            productAttributes(fixture.returnProduct),
          ],
          last_id: "",
        },
      };
    case "/v5/product/info/prices":
      return {
        result: {
          items: [
            productPrice(fixture.autoProduct),
            productPrice(fixture.missingProduct),
            productPrice(fixture.returnProduct),
          ],
          cursor: "",
        },
      };
    case "/v4/product/info/stocks":
      return {
        result: {
          items: [
            stockItem(fixture.autoProduct, fixture.autoWarehouse, 12),
            stockItem(fixture.missingProduct, fixture.autoWarehouse, 3),
            stockItem(fixture.returnProduct, fixture.returnWarehouse, 1),
          ],
          cursor: "",
        },
      };
    case "/v4/posting/fbs/list":
      return {
        result: { postings: fbsPostings(fixture), has_next: false, cursor: "" },
      };
    case "/v3/posting/fbo/list":
      return {
        result: { postings: fboPostings(fixture), has_next: false, cursor: "" },
      };
    case "/v1/returns/list":
      return { returns: returnsList(fixture), has_next: false };
    case "/v2/returns/rfbs/list":
      return { returns: [] };
    case "/v1/finance/accrual/types":
      return {
        accrual_types: [
          {
            id: 101,
            name: "Marketplace delivery service",
            description: "Delivery fee",
          },
        ],
      };
    case "/v1/finance/accrual/by-day":
      return {
        accruals:
          body.date === "2099-05-02" && !body.last_id
            ? [
                {
                  accrual_id: fixture.financeTransactionId,
                  accrued_category: "SERVICES",
                  date: "2099-05-02",
                  type_id: 101,
                  total_amount: { amount: "-2.50", currency: "RUB" },
                  posting: {
                    posting_number: fixture.fboPostingNumber,
                    delivery_schema: "FBO",
                    products: [
                      {
                        sku: fixture.autoProduct.productId,
                        delivery: {
                          services: [
                            {
                              type_id: 101,
                              accrued: { amount: "-2.50", currency: "RUB" },
                            },
                          ],
                        },
                      },
                    ],
                  },
                  buyer_phone: "+79990000000",
                },
              ]
            : [],
        last_id: "",
      };
    case "/v1/finance/document-b2b-sales/json":
      return {
        result: {
          invoices: [
            {
              info: {
                number: fixture.legalInvoiceId,
                date: "2099-05-02",
              },
              buyer_info: {
                name: "Mock B2B Company LLC",
                inn: "1234567890",
                kpp: "123456789",
                contact_name: "Secret Buyer",
                phone: "+79990000000",
              },
              currency: "RUB",
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              product_name: fixture.autoProduct.name,
              operations: [
                {
                  posting_number: fixture.fboPostingNumber,
                  amount: "10.50",
                  date: "2099-05-02",
                  price: "10.50",
                  quantity: "1",
                  type: "sale",
                },
              ],
            },
          ],
        },
      };
    case "/v1/posting/unpaid-legal/product/list":
      return {
        result: {
          products: [
            {
              id: fixture.unpaidLegalProductId,
              posting_number: fixture.fboPostingNumber,
              product_id: fixture.autoProduct.productId,
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              quantity: 1,
              amount: "10.50",
              customer_phone: "+79990000000",
            },
          ],
          cursor: "",
        },
      };
    case "/v1/finance/mutual-settlement":
      return { result: { code: `MUTUAL-${fixture.runId}` } };
    case "/v1/finance/compensation":
      return { result: { code: `COMP-${fixture.runId}` } };
    case "/v1/finance/decompensation":
      return { result: { code: `DECOMP-${fixture.runId}` } };
    case "/v1/report/list":
      return {
        result: {
          reports: [
            {
              code: `DISCOUNTED-${fixture.runId}`,
              status: "success",
              error: "",
              file: `${ozonMockBaseUrl()}/reports/discounted.csv`,
              report_type: "SELLER_DISCOUNTED",
              created_at: new Date().toISOString(),
            },
          ],
          total: 1,
        },
      };
    case "/v1/report/discounted/create":
      return { code: `DISCOUNTED-${fixture.runId}` };
    case "/v1/report/info":
      if (body.code === `DISCOUNTED-${fixture.runId}`) {
        return {
          result: {
            code: body.code,
            status: "success",
            error: "",
            file: `${ozonMockBaseUrl()}/reports/discounted.csv`,
            report_type: "SELLER_DISCOUNTED",
            created_at: new Date().toISOString(),
          },
        };
      }
      return {
        result: {
          code: body.code,
          status: "success",
          file: `https://example.invalid/reports/${body.code}.csv`,
        },
      };
    case "/v1/finance/cash-flow-statement/list":
      return {
        result: {
          page_count: 1,
          cash_flows:
            body.page === 1
              ? [
                  {
                    id: `CASH-${fixture.runId}`,
                    period: { id: `P-${fixture.runId}`, begin: "2099-05-01", end: "2099-05-31" },
                    orders_amount: "31.50",
                    currency_code: "RUB",
                    recipient_phone: "+79990000000",
                  },
                ]
              : [],
        },
      };
    case "/v1/finance/products/buyout":
      return {
        result: {
          products: [
            {
              posting_number: fixture.fboPostingNumber,
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              amount: "10.50",
              currency_code: "RUB",
            },
          ],
        },
      };
    case "/v1/removal/from-stock/list":
      return {
        result: {
          rows: [
            {
              id: fixture.removalId,
              state: "utilized",
              stock_type: "DEFECT",
              utilization_date: "2099-05-04T08:00:00.000Z",
              product_id: fixture.autoProduct.productId,
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              quantity_for_return: "1",
              warehouse_id: fixture.autoWarehouse.id,
              warehouse_name: fixture.autoWarehouse.name,
              preliminary_delivery_price: "10.50",
              buyer_name: "Secret Buyer",
            },
          ],
          last_id: "",
        },
      };
    case "/v1/removal/from-supply/list":
      return { result: { rows: [], last_id: "" } };
    case "/v3/supply-order/list":
      return {
        result: {
          order_ids: [fixture.supplyOrderId],
          last_id: "",
        },
      };
    case "/v3/supply-order/get":
      return {
        result: {
          orders: [
            {
              order_id: fixture.supplyOrderId,
              order_number: fixture.supplyOrderId,
              state: "completed",
              created_date: "2099-05-04T09:00:00.000Z",
              state_updated_date: "2099-05-04T12:00:00.000Z",
              supplies: [
                {
                  supply_id: `${fixture.supplyOrderId}-SUPPLY`,
                  bundle_id: fixture.supplyBundleId,
                  state: "completed",
                  storage_warehouse_id: fixture.autoWarehouse.id,
                  storage_warehouse_name: fixture.autoWarehouse.name,
                },
              ],
            },
          ],
        },
      };
    case "/v1/supply-order/bundle":
      return {
        result: {
          items: [
            {
              id: `${fixture.supplyBundleId}-1`,
              product_id: fixture.autoProduct.productId,
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              quantity: "1",
            },
          ],
          has_next: false,
          last_id: "",
        },
      };
    case "/v1/analytics/stocks":
    case "/v1/analytics/turnover/stocks":
      return { result: { items: [] } };
    case "/v1/product/info/discounted":
      return {
        result: {
          items: [
            {
              discounted_sku: fixture.discountedSku,
              product_id: fixture.autoProduct.productId,
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              condition: "used",
              condition_estimation: "good",
              reason_damaged: "Повреждение товара",
              comment_reason_damaged: "Царапины на корпусе",
              defects: "Вмятина",
              mechanical_damage: "Есть",
              package_damage: "Повреждена упаковка",
              packaging_violation: "Есть",
            },
          ],
        },
      };
    default:
      return { result: { items: [] } };
  }
}

function productRef(product: OzonMockProduct) {
  return {
    product_id: product.productId,
    offer_id: product.offerId,
    sku: product.sku,
  };
}

function productInfo(product: OzonMockProduct) {
  return {
    ...productRef(product),
    name: product.name,
    currency_code: "RUB",
    statuses: { status: "published" },
    visibility_details: { has_price: true },
    has_discounted_item: false,
    is_discounted: false,
    discounted_stocks: { coming: 0, present: 0, reserved: 0 },
    primary_image: [`https://example.invalid/${product.offerId}.jpg`],
    barcodes: [],
  };
}

function productPrice(product: OzonMockProduct) {
  return {
    ...productRef(product),
    price: {
      marketing_price: product.price,
      old_price: product.price,
      min_price: product.price,
      currency_code: "RUB",
    },
  };
}

function productAttributes(product: OzonMockProduct) {
  return {
    ...productRef(product),
    attributes: [{ id: 1, values: [{ value: "Mock category" }] }],
  };
}

function stockItem(
  product: OzonMockProduct,
  _warehouse: OzonMockWarehouse,
  present: number
) {
  return {
    ...productRef(product),
    stocks: [
      {
        type: "fbs",
        present,
        reserved: 0,
      },
    ],
  };
}

function fbsPostings(fixture: OzonMockFixture) {
  return [
    {
      posting_number: fixture.fbsPostingNumber,
      order_id: `${fixture.fbsPostingNumber}-ORDER`,
      status: "delivered",
      in_process_at: "2099-05-01T09:00:00.000Z",
      shipment_date: "2099-05-01T10:00:00.000Z",
      delivery_method: { warehouse: fixture.autoWarehouse.name },
      products: [
        postingProduct(fixture.autoProduct, 2),
        postingProduct(fixture.missingProduct, 1),
      ],
      financial_data: { products: [] },
      analytics_data: {
        region: "test",
        warehouse: fixture.autoWarehouse.name,
      },
      buyer_name: "Secret Buyer",
      customer_phone: "+79990000000",
      address_tail: "Secret address",
    },
    {
      posting_number: fixture.canceledPostingNumber,
      order_id: `${fixture.canceledPostingNumber}-ORDER`,
      status: "cancelled",
      in_process_at: "2099-05-01T09:00:00.000Z",
      shipment_date: "2099-05-01T10:00:00.000Z",
      cancellation: { cancelled_at: "2099-05-01T11:00:00.000Z" },
      delivery_method: { warehouse: fixture.autoWarehouse.name },
      products: [postingProduct(fixture.autoProduct, 1)],
      buyer: { name: "Canceled Secret Buyer" },
    },
  ];
}

function fboPostings(fixture: OzonMockFixture) {
  return [
    {
      posting_number: fixture.fboPostingNumber,
      order_id: `${fixture.fboPostingNumber}-ORDER`,
      status: "delivered",
      in_process_at: "2099-05-02T09:00:00.000Z",
      shipment_date: "2099-05-02T10:00:00.000Z",
      delivery_method: { warehouse: fixture.autoWarehouse.name },
      products: [postingProduct(fixture.autoProduct, 1)],
      financial_data: { products: [] },
      analytics_data: {
        region: "test",
        warehouse_id: fixture.autoWarehouse.id,
        warehouse_name: fixture.autoWarehouse.name,
      },
    },
  ];
}

function postingProduct(product: OzonMockProduct, quantity: number) {
  return {
    product_id: product.productId,
    offer_id: product.offerId,
    sku: product.sku,
    name: product.name,
    quantity,
    price: { amount: product.price, currency: "RUB" },
  };
}

function returnsList(fixture: OzonMockFixture) {
  return [
    {
      id: fixture.returnId,
      posting_number: fixture.fboPostingNumber,
      schema: "FBO",
      visual: { status: { sys_name: "ReturnedToSeller" } },
      logistic: {
        return_date: "2099-05-03T10:00:00.000Z",
        final_moment: "2099-05-03T12:00:00.000Z",
      },
      target_place: {
        id: fixture.returnWarehouse.id,
        name: fixture.returnWarehouse.name,
      },
      product: {
        product_id: fixture.returnProduct.productId,
        offer_id: fixture.returnProduct.offerId,
        sku: fixture.returnProduct.sku,
        name: fixture.returnProduct.name,
        quantity: 1,
        price: { price: fixture.returnProduct.price, currency_code: "RUB" },
      },
      buyer_fio: "Return Secret Buyer",
      phone: "+79990000000",
    },
  ];
}

async function readJsonBody(request: IncomingMessage) {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }

  const text = Buffer.concat(chunks).toString("utf8");
  if (!text) return {};

  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function writeJson(
  response: ServerResponse,
  status: number,
  value: unknown,
  headers: Record<string, string> = {}
) {
  response.writeHead(status, { "Content-Type": "application/json", ...headers });
  response.end(JSON.stringify(value));
}

function writeText(
  response: ServerResponse,
  status: number,
  value: string,
  contentType: string
) {
  response.writeHead(status, { "Content-Type": contentType });
  response.end(value);
}
