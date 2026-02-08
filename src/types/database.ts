export interface Order {
  id: string;
  workspace_id: string;
  source: string;
  external_order_id: string;
  ordered_at: string;
  currency: string;
  status: string;
  created_at: string;
  updated_at: string;
}

export interface OrderLine {
  id: string;
  order_id: string;
  sku: string;
  quantity: number;
  unit_price_gross: number;
  discount_amount: number;
  tax_amount: number;
  created_at: string;
}

export interface InventorySnapshot {
  id: string;
  workspace_id: string;
  snapshot_date: string;
  sku: string;
  on_hand_qty: number;
  unit_cost: number;
  created_at: string;
}

export interface Payment {
  id: string;
  workspace_id: string;
  source: string;
  external_payment_id: string;
  order_id: string | null;
  amount: number;
  fee_amount: number;
  currency: string;
  paid_at: string | null;
  status: string;
  created_at: string;
}

export interface Import {
  id: string;
  workspace_id: string;
  file_path: string;
  import_type: string;
  status: string;
  summary: Record<string, unknown>;
  created_at: string;
  completed_at: string | null;
}

export interface ImportError {
  id: string;
  import_id: string;
  row_number: number;
  error_code: string;
  error_detail: string;
  raw_row: Record<string, unknown>;
  created_at: string;
}

// API response types
export interface MetricsSummary {
  workspaceId: string;
  range: { from: string; to: string };
  kpis: {
    gmvGross: number;
    unitsSold: number;
    ordersCount: number;
    stockValueCost: number | null;
    inventorySnapshotDate: string | null;
  };
  meta: { computedAt: string };
}

export interface CriticalStockItem {
  sku: string;
  onHandQty: number;
  avgUnitsPerDay: number;
  daysRemaining: number;
}

export interface OrderWithMetrics {
  id: string;
  source: string;
  externalOrderId: string;
  orderedAt: string;
  currency: string;
  status: string;
  orderGmv: number;
  orderUnits: number;
}

export interface OrderLineDetail {
  id: string;
  sku: string;
  quantity: number;
  unitPriceGross: number;
  discountAmount: number;
  taxAmount: number;
  lineGmv: number;
}
