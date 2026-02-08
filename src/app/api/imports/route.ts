import { NextRequest, NextResponse } from "next/server";
import { createServerClient, getWorkspaceId } from "@/lib/supabase-server";
import {
  parseCSV,
  validateOrderHeaders,
  validateOrderRows,
  validateOrderLineHeaders,
  validateOrderLineRows,
  validateInventoryHeaders,
  validateInventoryRows,
  validatePaymentHeaders,
  validatePaymentRows,
} from "@/lib/csv-parsers";
import type { RowError } from "@/lib/csv-parsers";

const VALID_IMPORT_TYPES = [
  "orders_csv",
  "order_lines_csv",
  "inventory_csv",
  "payments_csv",
] as const;
type ImportType = (typeof VALID_IMPORT_TYPES)[number];

export async function POST(request: NextRequest) {
  try {
    const formData = await request.formData();
    const file = formData.get("file") as File | null;
    const importType = formData.get("import_type") as string | null;

    if (!file) {
      return NextResponse.json({ error: "No file provided" }, { status: 400 });
    }
    if (
      !importType ||
      !VALID_IMPORT_TYPES.includes(importType as ImportType)
    ) {
      return NextResponse.json(
        {
          error: `Invalid import_type. Must be one of: ${VALID_IMPORT_TYPES.join(", ")}`,
        },
        { status: 400 }
      );
    }

    const supabase = createServerClient();
    const workspaceId = getWorkspaceId();
    const text = await file.text();

    // Create import record
    const { data: importRecord, error: importError } = await supabase
      .from("imports")
      .insert({
        workspace_id: workspaceId,
        file_path: file.name,
        import_type: importType,
        status: "processing",
      })
      .select()
      .single();

    if (importError || !importRecord) {
      return NextResponse.json(
        { error: "Failed to create import record", detail: importError },
        { status: 500 }
      );
    }

    const importId = importRecord.id;
    const rows = parseCSV(text);

    if (rows.length === 0) {
      await supabase
        .from("imports")
        .update({
          status: "completed",
          summary: { totalRows: 0, inserted: 0, errors: 0 },
          completed_at: new Date().toISOString(),
        })
        .eq("id", importId);

      return NextResponse.json({
        importId,
        status: "completed",
        summary: { totalRows: 0, inserted: 0, errors: 0 },
      });
    }

    // Validate headers
    const headers = Object.keys(rows[0].data);
    let headerError: string | null = null;
    switch (importType) {
      case "orders_csv":
        headerError = validateOrderHeaders(headers);
        break;
      case "order_lines_csv":
        headerError = validateOrderLineHeaders(headers);
        break;
      case "inventory_csv":
        headerError = validateInventoryHeaders(headers);
        break;
      case "payments_csv":
        headerError = validatePaymentHeaders(headers);
        break;
    }

    if (headerError) {
      await supabase
        .from("imports")
        .update({
          status: "failed",
          summary: { error: headerError },
          completed_at: new Date().toISOString(),
        })
        .eq("id", importId);

      return NextResponse.json(
        { importId, status: "failed", error: headerError },
        { status: 400 }
      );
    }

    // Process based on import type
    let inserted = 0;
    let allErrors: RowError[] = [];

    switch (importType) {
      case "orders_csv": {
        const result = validateOrderRows(rows);
        allErrors = result.errors;
        if (result.valid.length > 0) {
          const { error } = await supabase.from("orders").upsert(
            result.valid.map((o) => ({
              workspace_id: workspaceId,
              source: o.source,
              external_order_id: o.external_order_id,
              ordered_at: o.ordered_at,
              currency: o.currency,
              status: o.status,
              updated_at: new Date().toISOString(),
            })),
            { onConflict: "workspace_id,source,external_order_id" }
          );
          if (error) {
            allErrors.push({
              rowNumber: 0,
              errorCode: "DB_ERROR",
              errorDetail: error.message,
              rawRow: {},
            });
          } else {
            inserted = result.valid.length;
          }
        }
        break;
      }

      case "order_lines_csv": {
        const result = validateOrderLineRows(rows);
        allErrors = result.errors;
        if (result.valid.length > 0) {
          // Group by order, look up order IDs
          const orderKeys = [
            ...new Set(
              result.valid.map((l) => `${l.source}::${l.external_order_id}`)
            ),
          ];

          // Fetch order IDs for the referenced orders
          const orderMap = new Map<string, string>();
          for (const key of orderKeys) {
            const [source, extId] = key.split("::");
            const { data } = await supabase
              .from("orders")
              .select("id")
              .eq("workspace_id", workspaceId)
              .eq("source", source)
              .eq("external_order_id", extId)
              .single();
            if (data) {
              orderMap.set(key, data.id);
            }
          }

          const linesToInsert = [];
          for (const line of result.valid) {
            const key = `${line.source}::${line.external_order_id}`;
            const orderId = orderMap.get(key);
            if (!orderId) {
              allErrors.push({
                rowNumber: 0,
                errorCode: "MISSING_ORDER",
                errorDetail: `Order not found: ${line.source} / ${line.external_order_id}`,
                rawRow: line as unknown as Record<string, string>,
              });
              continue;
            }
            linesToInsert.push({
              order_id: orderId,
              sku: line.sku,
              quantity: line.quantity,
              unit_price_gross: line.unit_price_gross,
              discount_amount: line.discount_amount,
              tax_amount: line.tax_amount,
            });
          }

          if (linesToInsert.length > 0) {
            const { error } = await supabase
              .from("order_lines")
              .insert(linesToInsert);
            if (error) {
              allErrors.push({
                rowNumber: 0,
                errorCode: "DB_ERROR",
                errorDetail: error.message,
                rawRow: {},
              });
            } else {
              inserted = linesToInsert.length;
            }
          }
        }
        break;
      }

      case "inventory_csv": {
        const result = validateInventoryRows(rows);
        allErrors = result.errors;
        if (result.valid.length > 0) {
          const { error } = await supabase.from("inventory_snapshots").upsert(
            result.valid.map((inv) => ({
              workspace_id: workspaceId,
              snapshot_date: inv.snapshot_date,
              sku: inv.sku,
              on_hand_qty: inv.on_hand_qty,
              unit_cost: inv.unit_cost,
            })),
            { onConflict: "workspace_id,snapshot_date,sku" }
          );
          if (error) {
            allErrors.push({
              rowNumber: 0,
              errorCode: "DB_ERROR",
              errorDetail: error.message,
              rawRow: {},
            });
          } else {
            inserted = result.valid.length;
          }
        }
        break;
      }

      case "payments_csv": {
        const result = validatePaymentRows(rows);
        allErrors = result.errors;
        if (result.valid.length > 0) {
          const { error } = await supabase.from("payments").upsert(
            result.valid.map((p) => ({
              workspace_id: workspaceId,
              source: p.source,
              external_payment_id: p.external_payment_id,
              amount: p.amount,
              fee_amount: p.fee_amount,
              currency: p.currency,
              paid_at: p.paid_at,
              status: p.status,
            })),
            { onConflict: "workspace_id,source,external_payment_id" }
          );
          if (error) {
            allErrors.push({
              rowNumber: 0,
              errorCode: "DB_ERROR",
              errorDetail: error.message,
              rawRow: {},
            });
          } else {
            inserted = result.valid.length;
          }
        }
        break;
      }
    }

    // Store errors
    if (allErrors.length > 0) {
      await supabase.from("import_errors").insert(
        allErrors.map((e) => ({
          import_id: importId,
          row_number: e.rowNumber,
          error_code: e.errorCode,
          error_detail: e.errorDetail,
          raw_row: e.rawRow,
        }))
      );
    }

    const summary = {
      totalRows: rows.length,
      inserted,
      errors: allErrors.length,
    };

    await supabase
      .from("imports")
      .update({
        status: allErrors.length > 0 && inserted === 0 ? "failed" : "completed",
        summary,
        completed_at: new Date().toISOString(),
      })
      .eq("id", importId);

    return NextResponse.json({ importId, status: "completed", summary });
  } catch (err) {
    console.error("Import error:", err);
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}

export async function GET(request: NextRequest) {
  try {
    const supabase = createServerClient();
    const workspaceId = getWorkspaceId();
    const { searchParams } = new URL(request.url);
    const limit = parseInt(searchParams.get("limit") || "20", 10);
    const offset = parseInt(searchParams.get("offset") || "0", 10);

    const { data, error, count } = await supabase
      .from("imports")
      .select("*", { count: "exact" })
      .eq("workspace_id", workspaceId)
      .order("created_at", { ascending: false })
      .range(offset, offset + limit - 1);

    if (error) {
      return NextResponse.json({ error: error.message }, { status: 500 });
    }

    return NextResponse.json({
      page: { limit, offset, total: count },
      items: data,
    });
  } catch {
    return NextResponse.json(
      { error: "Internal server error" },
      { status: 500 }
    );
  }
}
