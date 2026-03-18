import { NextRequest, NextResponse } from "next/server";
import { getRouteContext, toRouteErrorResponse } from "@/lib/request-context";
import type { SupplierDebtRow, SupplierDebtReport } from "@/types/inventory";

export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  try {
    const { supabase, workspaceId } = await getRouteContext(request);
    const { searchParams } = new URL(request.url);

    const today = new Date().toISOString().split("T")[0];
    const asOfDate = searchParams.get("asOfDate") || today;
    const periodFrom = searchParams.get("periodFrom") || today;
    const periodTo = searchParams.get("periodTo") || today;
    const debtTypeFilter = searchParams.get("debtType");

    const { data: rpcData, error: rpcError } = await supabase.rpc(
      "report_supplier_debt",
      {
        p_workspace_id: workspaceId,
        p_as_of_date: asOfDate,
        p_period_from: periodFrom,
        p_period_to: periodTo,
      }
    );

    if (rpcError) {
      return NextResponse.json({ error: rpcError.message }, { status: 500 });
    }

    // Fetch supplier names
    const supplierIds = (rpcData || []).map((r: { supplier_id: string }) => r.supplier_id);
    const { data: suppliers } = supplierIds.length > 0
      ? await supabase.from("suppliers").select("id, name").in("id", supplierIds)
      : { data: [] };

    const supplierNames = new Map<string, string>();
    for (const s of suppliers || []) {
      supplierNames.set(s.id, s.name);
    }

    let rows: SupplierDebtRow[] = (rpcData || []).map(
      (r: {
        supplier_id: string;
        purchased_total: number;
        paid_total: number;
        purchased_in_period: number;
        paid_in_period: number;
      }) => {
        const currentDebt = Number(r.purchased_total) - Number(r.paid_total);
        let debtType: "creditor" | "debitor" | "settled";
        if (currentDebt > 0) debtType = "creditor";
        else if (currentDebt < 0) debtType = "debitor";
        else debtType = "settled";

        return {
          supplierId: r.supplier_id,
          supplierName: supplierNames.get(r.supplier_id) ?? "Unknown",
          purchasedInPeriod: Number(r.purchased_in_period),
          paidInPeriod: Number(r.paid_in_period),
          currentDebt,
          debtType,
        };
      }
    );

    if (debtTypeFilter) {
      rows = rows.filter((r) => r.debtType === debtTypeFilter);
    }

    rows.sort((a, b) => a.supplierName.localeCompare(b.supplierName));

    const totals = {
      totalPurchased: rows.reduce((s, r) => s + r.purchasedInPeriod, 0),
      totalPaid: rows.reduce((s, r) => s + r.paidInPeriod, 0),
      totalDebt: rows.reduce((s, r) => s + r.currentDebt, 0),
    };

    const report: SupplierDebtReport = {
      asOfDate,
      periodFrom,
      periodTo,
      rows,
      totals,
    };

    return NextResponse.json(report);
  } catch (error) {
    return toRouteErrorResponse(error);
  }
}
