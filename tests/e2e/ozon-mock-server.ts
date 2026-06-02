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

export interface OzonMockServer {
  url: string;
  requests: OzonMockRequest[];
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
    discountedSku: `DISC-${suffix}`,
    dateFrom: "2099-05-01T00:00:00.000Z",
    dateTo: "2099-05-05T00:00:00.000Z",
  };
}

export async function startOzonMockServer(
  fixture: OzonMockFixture,
  options: {
    failPaths?: string[];
    validApiKey?: string;
    validClientId?: string;
  } = {}
): Promise<OzonMockServer> {
  const validApiKey = options.validApiKey || "ozon-api-key";
  const validClientId = options.validClientId || "ozon-client";
  const failPaths = new Set(options.failPaths || []);
  const url = new URL(ozonMockBaseUrl());
  const requests: OzonMockRequest[] = [];

  const server = http.createServer(async (request, response) => {
    const path = new URL(request.url || "/", url).pathname;
    const body = await readJsonBody(request);
    const apiKey = request.headers["api-key"]?.toString();
    const clientId = request.headers["client-id"]?.toString();
    requests.push({ path, body, apiKey, clientId });

    if (request.method !== "POST") {
      writeJson(response, 405, { error: "method not allowed" });
      return;
    }

    if (apiKey !== validApiKey || clientId !== validClientId) {
      writeJson(response, 403, { error: { message: "invalid credentials" } });
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
        result: [
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
      };
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
          items: [productInfo(fixture.autoProduct), productInfo(fixture.missingProduct), productInfo(fixture.returnProduct)],
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
      return { result: { postings: fbsPostings(fixture) } };
    case "/v3/posting/fbo/list":
      return { result: { postings: fboPostings(fixture) } };
    case "/v1/returns/list":
      return { result: { returns: returnsList(fixture) } };
    case "/v2/returns/rfbs/list":
      return { result: { returns: [], cursor: "" } };
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
                  unit_number: fixture.financeTransactionId,
                  accrued_category: "SERVICES",
                  date: "2099-05-02",
                  type_id: 101,
                  total_amount: { amount: "-2.50", currency: "RUB" },
                  posting: {
                    posting_number: fixture.fboPostingNumber,
                    delivery_schema: "FBO",
                    products: [
                      {
                        sku: Number(fixture.autoProduct.productId),
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
              invoice_id: fixture.legalInvoiceId,
              invoice_number: fixture.legalInvoiceId,
              invoice_date: "2099-05-02",
              posting_number: fixture.fboPostingNumber,
              buyer_info: {
                company_name: "Mock B2B Company LLC",
                inn: "1234567890",
                kpp: "123456789",
                contact_name: "Secret Buyer",
                phone: "+79990000000",
              },
              products: [postingProduct(fixture.autoProduct, 1)],
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
              product_id: Number(fixture.autoProduct.productId),
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
    case "/v1/report/info":
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
              status: "disposed",
              reason: "disposal_after_damage",
              date: "2099-05-04T08:00:00.000Z",
              product_id: Number(fixture.autoProduct.productId),
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              quantity: 1,
              warehouse_id: fixture.autoWarehouse.id,
              warehouse_name: fixture.autoWarehouse.name,
              amount: "10.50",
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
          orders: [
            {
              order_id: fixture.supplyOrderId,
              order_number: fixture.supplyOrderId,
              status: "completed",
              created_at: "2099-05-04T09:00:00.000Z",
              warehouse_id: fixture.autoWarehouse.id,
              warehouse_name: fixture.autoWarehouse.name,
              bundle_ids: [fixture.supplyBundleId],
            },
          ],
        },
      };
    case "/v3/supply-order/get":
      return {
        result: {
          orders: [
            {
              order_id: fixture.supplyOrderId,
              order_number: fixture.supplyOrderId,
              status: "completed",
              created_at: "2099-05-04T09:00:00.000Z",
              warehouse_id: fixture.autoWarehouse.id,
              warehouse_name: fixture.autoWarehouse.name,
              bundle_ids: [fixture.supplyBundleId],
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
              product_id: Number(fixture.autoProduct.productId),
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              quantity: 1,
            },
          ],
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
              product_id: Number(fixture.autoProduct.productId),
              offer_id: fixture.autoProduct.offerId,
              sku: fixture.autoProduct.sku,
              name: fixture.autoProduct.name,
              status: "damaged",
              reason: "physical_damage",
              quantity: 1,
              warehouse_id: fixture.autoWarehouse.id,
              warehouse_name: fixture.autoWarehouse.name,
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
    product_id: Number(product.productId),
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
    primary_image: [`https://example.invalid/${product.offerId}.jpg`],
    barcodes: [],
  };
}

function productPrice(product: OzonMockProduct) {
  return {
    ...productRef(product),
    price: product.price,
    old_price: product.price,
    min_price: product.price,
    currency_code: "RUB",
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
  warehouse: OzonMockWarehouse,
  present: number
) {
  return {
    ...productRef(product),
    stocks: [
      {
        warehouse_id: warehouse.id,
        warehouse_name: warehouse.name,
        present,
        reserved: 0,
        fulfillment_schema: "fbs",
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
      delivered_at: "2099-05-01T12:00:00.000Z",
      delivery_method: { warehouse: fixture.autoWarehouse.name },
      products: [
        postingProduct(fixture.autoProduct, 2),
        postingProduct(fixture.missingProduct, 1),
      ],
      financial_data: { products: [] },
      analytics_data: { region: "test" },
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
      delivered_at: "2099-05-02T12:00:00.000Z",
      delivery_method: { warehouse: fixture.autoWarehouse.name },
      products: [postingProduct(fixture.autoProduct, 1)],
      financial_data: { products: [] },
      analytics_data: { region: "test" },
    },
  ];
}

function postingProduct(product: OzonMockProduct, quantity: number) {
  return {
    product_id: Number(product.productId),
    offer_id: product.offerId,
    sku: product.sku,
    name: product.name,
    quantity,
    price: product.price,
    currency_code: "RUB",
  };
}

function returnsList(fixture: OzonMockFixture) {
  return [
    {
      id: fixture.returnId,
      posting_number: fixture.fboPostingNumber,
      status: "returned",
      returned_at: "2099-05-03T12:00:00.000Z",
      warehouse_id: fixture.returnWarehouse.id,
      warehouse_name: fixture.returnWarehouse.name,
      product: {
        product_id: Number(fixture.returnProduct.productId),
        offer_id: fixture.returnProduct.offerId,
        sku: fixture.returnProduct.sku,
        name: fixture.returnProduct.name,
        quantity: 1,
        price: fixture.returnProduct.price,
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

function writeJson(response: ServerResponse, status: number, value: unknown) {
  response.writeHead(status, { "Content-Type": "application/json" });
  response.end(JSON.stringify(value));
}
