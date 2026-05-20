import type {
  Category,
  OperationType,
  Product,
  Store,
  Supplier,
  Warehouse,
} from "@/types/inventory";

export type OperationImportStatus =
  | "uploaded"
  | "extracting"
  | "needs_review"
  | "ready"
  | "committing"
  | "completed"
  | "failed";

export type OperationImportCandidateStatus =
  | "needs_review"
  | "ready"
  | "approved"
  | "blocked"
  | "committed";

export interface OperationImportRecord {
  id: string;
  workspace_id: string;
  file_name: string;
  file_type: string;
  file_size: number;
  file_hash: string;
  source_kind: string;
  status: OperationImportStatus;
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

export interface OperationImportCandidateRecord {
  id: string;
  workspace_id: string;
  import_id: string;
  row_index: number;
  fingerprint: string;
  status: OperationImportCandidateStatus;
  confidence: number;
  source: ImportSourceRef;
  raw: Record<string, unknown>;
  operation: OperationImportDraft;
  normalized_operation: OperationImportDraft;
  validation_errors: CandidateValidationError[];
  duplicate_of: string | null;
  created_operation_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface ImportSourceRef {
  kind: "csv" | "xlsx" | "image" | "text" | "unknown";
  sheetName?: string;
  rowNumber?: number;
  pageNumber?: number;
  region?: string;
  columns?: Record<string, string>;
  evidence?: string;
}

export interface ParsedTable {
  kind: "csv" | "xlsx";
  sheetName: string;
  rows: string[][];
}

export interface HeaderMapping {
  headerRowIndex: number;
  columns: Record<string, number>;
  labels: Record<string, string>;
  confidence: number;
}

export type TabularImportColumnKey =
  | "operationDate"
  | "type"
  | "productName"
  | "skuCode"
  | "warehouseName"
  | "storeName"
  | "sourceWarehouseName"
  | "destinationWarehouseName"
  | "quantity"
  | "unitPrice"
  | "supplierName"
  | "paymentAmount"
  | "comment"
  | "direction";

export interface TabularImportSheetPlan {
  sheetName?: string | null;
  sheetIndex?: number | null;
  headerRowIndex?: number | null;
  dataStartRowIndex?: number | null;
  dataEndRowIndex?: number | null;
  columns: Record<TabularImportColumnKey, number | string | null>;
  defaults?: {
    type?: OperationType | null;
    operationDate?: string | null;
    supplierName?: string | null;
    warehouseName?: string | null;
    comment?: string | null;
  };
  confidence?: number | null;
  warnings?: string[];
}

export interface TabularImportPlan {
  sheets: TabularImportSheetPlan[];
  dateFormat?: string | null;
  decimalSeparator?: string | null;
  thousandsSeparator?: string | null;
}

export interface TabularImportPlanResult {
  fileType: "csv" | "xlsx";
  plan: TabularImportPlan | null;
  findings: Record<string, unknown>;
  extracted: Record<string, unknown>;
  generatedCode?: string | null;
  generatedCodeResult?: Record<string, unknown>;
  securityReport?: Record<string, unknown>;
}

export interface OperationImportItemDraft {
  productId?: string;
  productName?: string;
  skuCode?: string;
  createProduct?: boolean;
  warehouseId?: string;
  warehouseName?: string;
  createWarehouse?: boolean;
  storeId?: string;
  storeName?: string;
  createStore?: boolean;
  quantity?: number;
  rawQuantity?: string;
  unitPrice?: number;
  rawUnitPrice?: string;
  direction?: "in" | "out";
}

export interface OperationImportDraft {
  type?: OperationType;
  operationDate?: string;
  comment?: string;
  supplierId?: string;
  supplierName?: string;
  createSupplier?: boolean;
  paymentAmount?: number;
  rawPaymentAmount?: string;
  items?: OperationImportItemDraft[];
}

export interface CandidateValidationError {
  field: string;
  message: string;
  severity: "error" | "warning";
  suggestions?: { id: string; label: string; detail?: string }[];
}

export interface RefData {
  categories: Category[];
  products: Product[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  stores: Store[];
}

export interface ExistingDuplicate {
  fingerprint: string;
  operationId: string;
  importId: string;
}

export interface BuiltCandidate {
  rowIndex: number;
  fingerprint: string;
  status: OperationImportCandidateStatus;
  confidence: number;
  source: ImportSourceRef;
  raw: Record<string, unknown>;
  operation: OperationImportDraft;
  normalizedOperation: OperationImportDraft;
  validationErrors: CandidateValidationError[];
  duplicateOf?: string | null;
}

export interface ExtractionResult {
  fileType: ImportSourceRef["kind"];
  tables: ParsedTable[];
  candidates: BuiltCandidate[];
  findings: Record<string, unknown>;
  extracted: Record<string, unknown>;
  generatedCode?: string | null;
  generatedCodeResult?: Record<string, unknown>;
  securityReport?: Record<string, unknown>;
}
