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

export interface OperationImport {
  id: string;
  workspace_id: string;
  file_name: string;
  file_type: string;
  file_size: number;
  file_hash: string;
  source_kind: string;
  status: string;
  summary: Record<string, unknown>;
  findings: Record<string, unknown>;
  extracted: Record<string, unknown>;
  generated_code: string | null;
  generated_code_result: Record<string, unknown>;
  security_report: Record<string, unknown>;
  approved_by: string | null;
  approved_at: string | null;
  completed_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface OperationImportCandidate {
  id: string;
  workspace_id: string;
  import_id: string;
  row_index: number;
  fingerprint: string;
  status: string;
  confidence: number;
  source: Record<string, unknown>;
  raw: Record<string, unknown>;
  operation: Record<string, unknown>;
  normalized_operation: Record<string, unknown>;
  validation_errors: Record<string, unknown>[];
  duplicate_of: string | null;
  created_operation_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface WorkspaceSettingsRow {
  id: string;
  workspace_id: string;
  currency: string;
  category_required: boolean;
  default_category_id: string | null;
  store_required: boolean;
  default_store_id: string | null;
  created_at: string;
  updated_at: string;
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
