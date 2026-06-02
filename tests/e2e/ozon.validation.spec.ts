import { expect, test } from "@playwright/test";
import {
  buildOperationRequest,
  canSyncUpdateCandidateStatus,
  isOzonCandidateCommitSupported,
  normalizeOzonCandidateOperation,
  statusFromValidation,
  validateOzonCandidateOperation,
  type MarketplaceCandidateRow,
  type OzonCandidateOperation,
} from "../../src/lib/ozon/candidates";
import { sanitizeOzonPayload } from "../../src/lib/ozon/sync";

function validationFields(operation: OzonCandidateOperation) {
  return validateOzonCandidateOperation(operation).map((error) => error.field);
}

function candidate(operation: OzonCandidateOperation): MarketplaceCandidateRow {
  return {
    id: "candidate-id",
    workspace_id: "workspace-id",
    connection_id: "connection-id",
    provider: "ozon",
    source_type: "posting",
    external_event_id: "posting-id",
    status: "ready",
    operation_type: operation.type || "sale",
    operation_date: operation.operationDate || null,
    confidence: 0.95,
    operation,
    normalized_operation: normalizeOzonCandidateOperation(operation),
    validation_errors: [],
    raw_payload: {},
    created_operation_id: null,
    created_at: "2026-01-01T00:00:00.000Z",
    updated_at: "2026-01-01T00:00:00.000Z",
  };
}

test.describe("Ozon candidate validation", () => {
  test("accepts ready sales and builds normal operation requests", () => {
    const operation: OzonCandidateOperation = {
      type: "sale",
      operationDate: "2099-05-01",
      comment: "Ozon sale",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 2,
          unitPrice: 10.5,
        },
      ],
    };

    const errors = validateOzonCandidateOperation(operation);
    expect(errors).toEqual([]);
    expect(statusFromValidation(errors)).toBe("ready");
    expect(buildOperationRequest(candidate(operation))).toMatchObject({
      type: "sale",
      operationDate: "2099-05-01",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 2,
          unitPrice: 10.5,
          direction: "out",
          qualityStatus: "ordinary",
        },
      ],
    });
  });

  test("reports missing mappings, invalid dates, and invalid quantities/prices", () => {
    expect(
      validationFields({
        type: "sale",
        operationDate: "2099-05-01",
        items: [{ warehouseId: "warehouse-1", quantity: 1 }],
      })
    ).toContain("items[0].productId");

    expect(
      validationFields({
        type: "sale",
        operationDate: "2099-05-01",
        items: [{ productId: "product-1", quantity: 1 }],
      })
    ).toContain("items[0].warehouseId");

    expect(
      validationFields({
        type: "sale",
        operationDate: "not a date",
        items: [
          {
            productId: "product-1",
            warehouseId: "warehouse-1",
            quantity: 0,
            unitPrice: -1,
          },
        ],
      })
    ).toEqual([
      "operationDate",
      "items[0].quantity",
      "items[0].unitPrice",
    ]);
  });

  test("supports multi-line postings", () => {
    const operation = normalizeOzonCandidateOperation({
      type: "sale",
      operationDate: "2099-05-01",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: "2" as unknown as number,
        },
        {
          productId: "product-2",
          warehouseId: "warehouse-1",
          quantity: "3" as unknown as number,
        },
      ],
    });

    expect(validateOzonCandidateOperation(operation)).toEqual([]);
    expect(operation.items).toMatchObject([
      { productId: "product-1", direction: "out", quantity: 2 },
      { productId: "product-2", direction: "out", quantity: 3 },
    ]);
  });

  test("normalizes returns as inbound operations", () => {
    const operation = normalizeOzonCandidateOperation({
      type: "return",
      operationDate: "2099-05-03",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 1,
        },
      ],
    });

    expect(validateOzonCandidateOperation(operation)).toEqual([]);
    expect(operation.items?.[0]).toMatchObject({
      direction: "in",
      quantity: 1,
    });
    expect(buildOperationRequest(candidate(operation)).type).toBe("return");
  });

  test("accepts write-off and defect candidates as outbound inventory evidence", () => {
    const writeOff = normalizeOzonCandidateOperation({
      type: "write_off",
      operationDate: "2099-05-04",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 1,
        },
      ],
    });
    const defect = normalizeOzonCandidateOperation({
      type: "defect",
      operationDate: "2099-05-04",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 1,
        },
      ],
    });

    expect(validateOzonCandidateOperation(writeOff)).toEqual([]);
    expect(writeOff.items?.[0].direction).toBe("out");
    expect(validateOzonCandidateOperation(defect)).toEqual([]);
    expect(buildOperationRequest(candidate(defect))).toMatchObject({
      type: "defect",
      productId: "product-1",
      sourceWarehouseId: "warehouse-1",
      quantity: 1,
    });
  });

  test("validates transfer candidates as one source and one destination item", () => {
    const operation = normalizeOzonCandidateOperation({
      type: "transfer",
      operationDate: "2099-05-04",
      items: [
        {
          productId: "product-1",
          warehouseId: "source-warehouse",
          quantity: 2,
          direction: "out",
        },
        {
          productId: "product-1",
          warehouseId: "destination-warehouse",
          quantity: 2,
          direction: "in",
        },
      ],
    });

    expect(validateOzonCandidateOperation(operation)).toEqual([]);
    expect(buildOperationRequest(candidate(operation))).toMatchObject({
      type: "transfer",
      productId: "product-1",
      sourceWarehouseId: "source-warehouse",
      destinationWarehouseId: "destination-warehouse",
      quantity: 2,
    });

    expect(
      validateOzonCandidateOperation({
        ...operation,
        items: operation.items?.slice(0, 1),
      }).map((error) => error.message)
    ).toContain("Transfer requires one source item and one destination item");
  });

  test("requires unit cost for inventory adjustment candidates", () => {
    expect(
      validationFields({
        type: "inventory_adjustment",
        operationDate: "2099-05-04",
        items: [
          {
            productId: "product-1",
            warehouseId: "warehouse-1",
            quantity: 3,
          },
        ],
      })
    ).toContain("items[0].unitPrice");

    expect(
      validateOzonCandidateOperation({
        type: "inventory_adjustment",
        operationDate: "2099-05-04",
        items: [
          {
            productId: "product-1",
            warehouseId: "warehouse-1",
            quantity: 3,
            unitPrice: 4,
          },
        ],
      })
    ).toEqual([]);
  });

  test("keeps purchase, payment, and production evidence out of commit flow", () => {
    for (const type of ["purchase", "payment", "production"] as const) {
      expect(
        validateOzonCandidateOperation({
          type,
          operationDate: "2099-05-04",
          items: [
            {
              productId: "product-1",
              warehouseId: "warehouse-1",
              quantity: 1,
            },
          ],
        }).map((error) => error.message)
      ).toContain("This Ozon evidence cannot be committed as a Tover operation");
    }
  });

  test("blocks reporting-only and blocked evidence from commit flow", () => {
    const reportingOnly = normalizeOzonCandidateOperation({
      type: "sale",
      operationDate: "2099-05-04",
      supportStatus: "reporting_only",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 1,
        },
      ],
    });
    const blocked = normalizeOzonCandidateOperation({
      ...reportingOnly,
      supportStatus: "blocked",
    });
    const supported = normalizeOzonCandidateOperation({
      ...reportingOnly,
      supportStatus: "commit_candidate",
    });

    expect(isOzonCandidateCommitSupported(reportingOnly)).toBe(false);
    expect(isOzonCandidateCommitSupported(blocked)).toBe(false);
    expect(isOzonCandidateCommitSupported(supported)).toBe(true);
    expect(validationFields(reportingOnly)).toContain("supportStatus");
    expect(validationFields(blocked)).toContain("supportStatus");
    expect(validateOzonCandidateOperation(supported)).toEqual([]);
  });

  test("sanitizes personal Ozon payload fields while keeping product and legal identifiers", () => {
    const sanitized = sanitizeOzonPayload({
      product: {
        name: "Marketplace SKU",
        offer_id: "OFFER-1",
      },
      buyer: {
        name: "Ivan Buyer",
        phone: "+79990000000",
        address: "Private address",
      },
      buyer_company_name: "Acme LLC",
      buyerCompanyName: "Camel Legal LLC",
      buyer_inn: "7700000000",
      buyer_kpp: "770001001",
      firstName: "Private first name",
      clientName: "Personal client",
      recipientMobile: "+79991111111",
      lines: [{ name: "Line product" }],
    }) as Record<string, unknown>;
    const serialized = JSON.stringify(sanitized);

    expect(serialized).toContain("Marketplace SKU");
    expect(serialized).toContain("Line product");
    expect(serialized).toContain("Acme LLC");
    expect(serialized).toContain("Camel Legal LLC");
    expect(serialized).toContain("7700000000");
    expect(serialized).not.toContain("Ivan Buyer");
    expect(serialized).not.toContain("Private first name");
    expect(serialized).not.toContain("+79990000000");
    expect(serialized).not.toContain("Private address");
    expect(serialized).not.toContain("Personal client");
    expect(serialized).not.toContain("+79991111111");
  });

  test("preserves explicit review decisions across sync", () => {
    expect(canSyncUpdateCandidateStatus("needs_mapping")).toBe(true);
    expect(canSyncUpdateCandidateStatus("ready")).toBe(true);
    expect(canSyncUpdateCandidateStatus("approved")).toBe(false);
    expect(canSyncUpdateCandidateStatus("committing")).toBe(false);
    expect(canSyncUpdateCandidateStatus("ignored")).toBe(false);
    expect(canSyncUpdateCandidateStatus("committed")).toBe(false);
  });
});
