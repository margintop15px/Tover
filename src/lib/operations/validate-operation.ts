import type {
  CreateOperationRequest,
  OperationItemInput,
  OperationType,
} from "@/types/inventory";

export interface ValidationError {
  field: string;
  message: string;
}

export interface ValidatedOperation {
  type: OperationType;
  operationDate: string;
  comment?: string;
  supplierId?: string;
  paymentAmount?: number;
  items: OperationItemInput[];
}

const VALID_TYPES: OperationType[] = [
  "purchase",
  "sale",
  "return",
  "write_off",
  "transfer",
  "production",
  "defect",
  "payment",
  "inventory_adjustment",
];

export function validateOperation(
  body: CreateOperationRequest
):
  | { data: ValidatedOperation; errors?: never }
  | { data?: never; errors: ValidationError[] } {
  const errors: ValidationError[] = [];

  // Type validation
  if (!body.type || !VALID_TYPES.includes(body.type)) {
    errors.push({ field: "type", message: "Invalid operation type" });
    return { errors };
  }

  // Date validation
  if (!body.operationDate || isNaN(Date.parse(body.operationDate))) {
    errors.push({ field: "operationDate", message: "Valid date is required" });
  }

  const type = body.type;

  switch (type) {
    case "payment":
      return validatePayment(body, errors);
    case "purchase":
      return validatePurchase(body, errors);
    case "inventory_adjustment":
      return validateInventoryAdjustment(body, errors);
    case "sale":
    case "return":
    case "write_off":
      return validateSimpleItemOp(body, errors);
    case "transfer":
      return validateTransfer(body, errors);
    case "production":
      return validateProduction(body, errors);
    case "defect":
      return validateDefect(body, errors);
  }
}

function validatePayment(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  if (!body.supplierId) {
    errors.push({ field: "supplierId", message: "Supplier is required" });
  }
  if (!body.paymentAmount || body.paymentAmount <= 0) {
    errors.push({
      field: "paymentAmount",
      message: "Payment amount must be positive",
    });
  }

  if (errors.length > 0) return { errors };

  return {
    data: {
      type: "payment",
      operationDate: body.operationDate,
      comment: body.comment,
      supplierId: body.supplierId,
      paymentAmount: body.paymentAmount,
      items: [],
    },
  };
}

function validatePurchase(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  if (!body.supplierId) {
    errors.push({ field: "supplierId", message: "Supplier is required" });
  }

  const items = body.items || [];
  if (items.length === 0) {
    errors.push({ field: "items", message: "At least one item is required" });
  }

  items.forEach((item, i) => {
    validateItemFields(item, i, errors);
    if (!item.unitPrice || item.unitPrice <= 0) {
      errors.push({
        field: `items[${i}].unitPrice`,
        message: "Unit price must be positive",
      });
    }
  });

  if (errors.length > 0) return { errors };

  return {
    data: {
      type: "purchase",
      operationDate: body.operationDate,
      comment: body.comment,
      supplierId: body.supplierId,
      items: items.map((item) => ({
          ...item,
          qualityStatus: item.qualityStatus || "ordinary",
          direction: "in" as const,
        })),
    },
  };
}

function validateInventoryAdjustment(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  const items = body.items || [];
  if (items.length === 0) {
    errors.push({ field: "items", message: "At least one item is required" });
  }

  items.forEach((item, i) => {
    validateItemFields(item, i, errors);
    if (!item.unitPrice || item.unitPrice <= 0) {
      errors.push({
        field: `items[${i}].unitPrice`,
        message: "Unit cost must be positive",
      });
    }
  });

  if (errors.length > 0) return { errors };

  return {
    data: {
      type: "inventory_adjustment",
      operationDate: body.operationDate,
      comment: body.comment,
      items: items.map((item) => ({
          ...item,
          qualityStatus: item.qualityStatus || "ordinary",
          direction: "in" as const,
        })),
    },
  };
}

function validateSimpleItemOp(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  const items = body.items || [];
  if (items.length === 0) {
    errors.push({ field: "items", message: "At least one item is required" });
  }

  items.forEach((item, i) => validateItemFields(item, i, errors));

  if (errors.length > 0) return { errors };

  const direction = body.type === "return" ? "in" : "out";

  return {
    data: {
      type: body.type,
      operationDate: body.operationDate,
      comment: body.comment,
      items: items.map((item) => ({
          ...item,
          qualityStatus: item.qualityStatus || "ordinary",
          direction: direction as "in" | "out",
        })),
    },
  };
}

function validateTransfer(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  if (!body.productId) {
    errors.push({ field: "productId", message: "Product is required" });
  }
  if (!body.sourceWarehouseId) {
    errors.push({
      field: "sourceWarehouseId",
      message: "Source warehouse is required",
    });
  }
  if (!body.destinationWarehouseId) {
    errors.push({
      field: "destinationWarehouseId",
      message: "Destination warehouse is required",
    });
  }
  if (
    body.sourceWarehouseId &&
    body.destinationWarehouseId &&
    body.sourceWarehouseId === body.destinationWarehouseId
  ) {
    errors.push({
      field: "destinationWarehouseId",
      message: "Source and destination must differ",
    });
  }
  if (!body.quantity || body.quantity <= 0) {
    errors.push({
      field: "quantity",
      message: "Quantity must be positive",
    });
  }

  if (errors.length > 0) return { errors };

  return {
    data: {
      type: "transfer",
      operationDate: body.operationDate,
      comment: body.comment,
      items: [
        {
          productId: body.productId!,
          warehouseId: body.sourceWarehouseId!,
          quantity: body.quantity!,
          qualityStatus: "ordinary",
          direction: "out",
        },
        {
          productId: body.productId!,
          warehouseId: body.destinationWarehouseId!,
          quantity: body.quantity!,
          qualityStatus: "ordinary",
          direction: "in",
        },
      ],
    },
  };
}

function validateProduction(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  const items = body.items || [];

  const outItems = items.filter((i) => i.direction === "out");
  const inItems = items.filter((i) => i.direction === "in");

  if (outItems.length === 0) {
    errors.push({
      field: "items",
      message: "At least one source (out) item is required",
    });
  }
  if (inItems.length !== 1) {
    errors.push({
      field: "items",
      message: "Exactly one output (in) item is required",
    });
  }

  items.forEach((item, i) => validateItemFields(item, i, errors));

  if (errors.length > 0) return { errors };

  return {
    data: {
      type: "production",
      operationDate: body.operationDate,
      comment: body.comment,
      items: items.map((item) => ({
        ...item,
        qualityStatus: item.qualityStatus || "ordinary",
      })),
    },
  };
}

function validateDefect(
  body: CreateOperationRequest,
  errors: ValidationError[]
): ReturnType<typeof validateOperation> {
  if (!body.productId) {
    errors.push({ field: "productId", message: "Product is required" });
  }
  if (!body.sourceWarehouseId) {
    errors.push({
      field: "sourceWarehouseId",
      message: "Source warehouse is required",
    });
  }
  if (!body.quantity || body.quantity <= 0) {
    errors.push({
      field: "quantity",
      message: "Quantity must be positive",
    });
  }

  if (errors.length > 0) return { errors };

  // Items will be constructed server-side (defect product + defect warehouse)
  return {
    data: {
      type: "defect",
      operationDate: body.operationDate,
      comment: body.comment,
      items: [
        {
          productId: body.productId!,
          warehouseId: body.sourceWarehouseId!,
          quantity: body.quantity!,
          qualityStatus: "ordinary",
          direction: "out",
        },
      ],
    },
  };
}

function validateItemFields(
  item: OperationItemInput,
  index: number,
  errors: ValidationError[]
) {
  if (!item.productId) {
    errors.push({
      field: `items[${index}].productId`,
      message: "Product is required",
    });
  }
  if (!item.warehouseId) {
    errors.push({
      field: `items[${index}].warehouseId`,
      message: "Warehouse is required",
    });
  }
  if (!item.quantity || item.quantity <= 0) {
    errors.push({
      field: `items[${index}].quantity`,
      message: "Quantity must be positive",
    });
  }
}
