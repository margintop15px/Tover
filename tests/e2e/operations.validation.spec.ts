import { expect, test } from "@playwright/test";
import { validateOperation } from "../../src/lib/operations/validate-operation";

test.describe("operation validation", () => {
  test("accepts inventory adjustments without a supplier and forces incoming items", () => {
    const result = validateOperation({
      type: "inventory_adjustment",
      operationDate: "2026-02-15",
      comment: "Initial backfill",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 12,
          unitPrice: 34,
        },
      ],
    });

    expect(result.errors).toBeUndefined();
    expect(result.data).toMatchObject({
      type: "inventory_adjustment",
      operationDate: "2026-02-15",
      comment: "Initial backfill",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 12,
          unitPrice: 34,
          direction: "in",
        },
      ],
    });
  });

  test("rejects inventory adjustments without line items or positive unit costs", () => {
    expect(
      validateOperation({
        type: "inventory_adjustment",
        operationDate: "2026-02-15",
        items: [],
      }).errors
    ).toEqual([{ field: "items", message: "At least one item is required" }]);

    expect(
      validateOperation({
        type: "inventory_adjustment",
        operationDate: "2026-02-15",
        items: [
          {
            productId: "product-1",
            warehouseId: "warehouse-1",
            quantity: 2,
            unitPrice: 0,
          },
        ],
      }).errors
    ).toContainEqual({
      field: "items[0].unitPrice",
      message: "Unit cost must be positive",
    });
  });

  test("rejects invalid dates before operation-specific validation succeeds", () => {
    const result = validateOperation({
      type: "inventory_adjustment",
      operationDate: "not-a-date",
      items: [
        {
          productId: "product-1",
          warehouseId: "warehouse-1",
          quantity: 1,
          unitPrice: 1,
        },
      ],
    });

    expect(result.errors).toEqual([
      { field: "operationDate", message: "Valid date is required" },
    ]);
  });
});
