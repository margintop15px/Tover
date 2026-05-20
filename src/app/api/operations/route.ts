import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import { processOperation } from "@/lib/operations";

export const dynamic = "force-dynamic";

type OperationSortBy =
  | "operationDate"
  | "type"
  | "product"
  | "warehouse"
  | "supplier"
  | "quantity"
  | "unitPrice"
  | "paymentAmount";

type OperationSortDir = "asc" | "desc";

interface OperationDisplayRow {
  id: string;
  operationId: string;
  itemId: string | null;
  type: string;
  operationDate: string;
  comment: string | null;
  supplierId: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  createdAt: string;
  productId: string | null;
  productName: string | null;
  warehouseId: string | null;
  warehouseName: string | null;
  quantity: number | null;
  unitPrice: number | null;
  direction: string | null;
  itemsSummary: {
    itemId: string;
    productId: string;
    productName: string;
    warehouseId: string;
    warehouseName: string;
    quantity: number;
    unitPrice: number | null;
    direction: string;
  }[];
}

function isSortBy(value: string | null): value is OperationSortBy {
  return (
    value === "operationDate" ||
    value === "type" ||
    value === "product" ||
    value === "warehouse" ||
    value === "supplier" ||
    value === "quantity" ||
    value === "unitPrice" ||
    value === "paymentAmount"
  );
}

function isSortDir(value: string | null): value is OperationSortDir {
  return value === "asc" || value === "desc";
}

function compareValues(
  a: string | number | null,
  b: string | number | null,
  sortDir: OperationSortDir
) {
  const direction = sortDir === "asc" ? 1 : -1;

  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;

  if (typeof a === "number" && typeof b === "number") {
    return (a - b) * direction;
  }

  return String(a).localeCompare(String(b), undefined, {
    numeric: true,
    sensitivity: "base",
  }) * direction;
}

function sortOperationRows(
  rows: OperationDisplayRow[],
  sortBy: OperationSortBy | null,
  sortDir: OperationSortDir
) {
  if (!sortBy) {
    return rows.sort((a, b) => {
      const dateCompare = compareValues(a.operationDate, b.operationDate, "desc");
      if (dateCompare !== 0) return dateCompare;
      return compareValues(a.createdAt, b.createdAt, "desc");
    });
  }

  const getValue = (row: OperationDisplayRow): string | number | null => {
    switch (sortBy) {
      case "operationDate":
        return row.operationDate;
      case "type":
        return row.type;
      case "product":
        return row.productName;
      case "warehouse":
        return row.warehouseName;
      case "supplier":
        return row.supplierName;
      case "quantity":
        return row.quantity;
      case "unitPrice":
        return row.unitPrice;
      case "paymentAmount":
        return row.paymentAmount;
    }
  };

  return rows.sort((a, b) => {
    const valueCompare = compareValues(getValue(a), getValue(b), sortDir);
    if (valueCompare !== 0) return valueCompare;
    const dateCompare = compareValues(a.operationDate, b.operationDate, "desc");
    if (dateCompare !== 0) return dateCompare;
    return compareValues(a.createdAt, b.createdAt, "desc");
  });
}

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const limit = parseInt(searchParams.get("limit") || "50", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);
    const type = searchParams.get("type");
    const includeAudit = searchParams.get("includeAudit") === "true";
    const supplierId = searchParams.get("supplierId");
    const productId = searchParams.get("productId");
    const warehouseId = searchParams.get("warehouseId");
    const importId = searchParams.get("importId");
    const from = searchParams.get("from");
    const to = searchParams.get("to");
    const requestedSortBy = searchParams.get("sortBy");
    const requestedSortDir = searchParams.get("sortDir");
    const sortBy: OperationSortBy | null = isSortBy(requestedSortBy)
      ? requestedSortBy
      : null;
    const sortDir: OperationSortDir = isSortDir(requestedSortDir)
      ? requestedSortDir
      : "desc";

    // If filtering by product, warehouse, or import batch, first find matching operation IDs.
    let operationIdFilter: string[] | null = null;
    if (productId || warehouseId) {
      let itemQuery = supabase
        .from("operation_items")
        .select("operation_id");
      if (productId) itemQuery = itemQuery.eq("product_id", productId);
      if (warehouseId) itemQuery = itemQuery.eq("warehouse_id", warehouseId);
      const { data: matchingItems } = await itemQuery;
      operationIdFilter = [...new Set((matchingItems || []).map((i) => i.operation_id))];
      if (operationIdFilter.length === 0) {
        return NextResponse.json({
          page: { limit, offset, totalEstimate: 0 },
          items: [],
        });
      }
    }

    if (importId) {
      const { data: importLinks, error: importLinkError } = await supabase
        .from("operation_import_committed_operations")
        .select("operation_id")
        .eq("workspace_id", workspaceId)
        .eq("import_id", importId);

      if (importLinkError) {
        return NextResponse.json(
          { error: importLinkError.message },
          { status: 500 }
        );
      }

      const importedOperationIds = [
        ...new Set((importLinks || []).map((link) => link.operation_id)),
      ];
      operationIdFilter = operationIdFilter
        ? operationIdFilter.filter((id) => importedOperationIds.includes(id))
        : importedOperationIds;

      if (operationIdFilter.length === 0) {
        return NextResponse.json({
          page: { limit, offset, totalEstimate: 0 },
          items: [],
        });
      }
    }

    let query = supabase
      .from("operations")
      .select("*, suppliers(name)")
      .eq("workspace_id", workspaceId)
      .order("operation_date", { ascending: false })
      .order("created_at", { ascending: false });

    if (type) {
      query = query.eq("type", type);
    } else if (!includeAudit) {
      query = query.neq("type", "inventory_adjustment");
    }
    if (supplierId) query = query.eq("supplier_id", supplierId);
    if (from) query = query.gte("operation_date", from);
    if (to) query = query.lte("operation_date", to);
    if (operationIdFilter) query = query.in("id", operationIdFilter);

    const { data, error } = await query;

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    const operationIds = (data || []).map((o) => o.id);
    const itemsMap = new Map<
      string,
      {
        itemId: string;
        productId: string;
        productName: string;
        warehouseId: string;
        warehouseName: string;
        quantity: number;
        unitPrice: number | null;
        direction: string;
      }[]
    >();

    if (operationIds.length > 0) {
      let itemsQuery = supabase
        .from("operation_items")
        .select("id, operation_id, product_id, warehouse_id, quantity, unit_price, direction, products(name), warehouses(name)")
        .in("operation_id", operationIds);

      if (productId) itemsQuery = itemsQuery.eq("product_id", productId);
      if (warehouseId) itemsQuery = itemsQuery.eq("warehouse_id", warehouseId);

      const { data: items } = await itemsQuery;

      if (items) {
        for (const item of items) {
          const list = itemsMap.get(item.operation_id) || [];
          const prod = item.products as unknown as { name: string } | null;
          const wh = item.warehouses as unknown as { name: string } | null;
          list.push({
            itemId: item.id,
            productId: item.product_id,
            productName: prod?.name ?? "",
            warehouseId: item.warehouse_id,
            warehouseName: wh?.name ?? "",
            quantity: item.quantity,
            unitPrice: item.unit_price,
            direction: item.direction,
          });
          itemsMap.set(item.operation_id, list);
        }
      }
    }

    const rows: OperationDisplayRow[] = [];

    for (const operation of data || []) {
      const supplierName =
        (operation.suppliers as unknown as { name: string } | null)?.name ?? null;
      const opItems = itemsMap.get(operation.id) || [];

      if (opItems.length === 0) {
        if (operation.type !== "payment") continue;

        rows.push({
          id: operation.id,
          operationId: operation.id,
          itemId: null,
          type: operation.type,
          operationDate: operation.operation_date,
          comment: operation.comment,
          supplierId: operation.supplier_id,
          supplierName,
          paymentAmount: operation.payment_amount,
          createdAt: operation.created_at,
          productId: null,
          productName: null,
          warehouseId: null,
          warehouseName: null,
          quantity: null,
          unitPrice: null,
          direction: null,
          itemsSummary: [],
        });
        continue;
      }

      for (const item of opItems) {
        rows.push({
          id: `${operation.id}:${item.itemId}`,
          operationId: operation.id,
          itemId: item.itemId,
          type: operation.type,
          operationDate: operation.operation_date,
          comment: operation.comment,
          supplierId: operation.supplier_id,
          supplierName,
          paymentAmount: operation.payment_amount,
          createdAt: operation.created_at,
          productId: item.productId,
          productName: item.productName,
          warehouseId: item.warehouseId,
          warehouseName: item.warehouseName,
          quantity: item.quantity,
          unitPrice: item.unitPrice,
          direction: item.direction,
          itemsSummary: [item],
        });
      }
    }

    sortOperationRows(rows, sortBy, sortDir);
    const pagedRows = rows.slice(offset, offset + limit);

    return NextResponse.json({
      page: { limit, offset, totalEstimate: rows.length },
      items: pagedRows,
    });
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}

export async function POST(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request, {
      requireManager: true,
    });

    const body = await request.json();
    const result = await processOperation(supabase, workspaceId, body);

    if (result.errors) {
      return NextResponse.json(
        { errors: result.errors },
        { status: 400 }
      );
    }

    return NextResponse.json(
      { id: result.operation.id },
      { status: 201 }
    );
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}
