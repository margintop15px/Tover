// ============================================================
// Union types
// ============================================================

export type OperationType =
  | "purchase"
  | "sale"
  | "return"
  | "write_off"
  | "transfer"
  | "production"
  | "defect"
  | "payment";

export type WarehousePurpose = "storage" | "sales" | "production";

export type OperationDirection = "in" | "out";

// ============================================================
// DB row interfaces (snake_case, matching Supabase)
// ============================================================

export interface CategoryRow {
  id: string;
  workspace_id: string;
  name: string;
  created_at: string;
  updated_at: string;
}

export interface StoreRow {
  id: string;
  workspace_id: string;
  name: string;
  created_at: string;
  updated_at: string;
}

export interface WarehouseRow {
  id: string;
  workspace_id: string;
  name: string;
  description: string | null;
  purpose: WarehousePurpose | null;
  is_default_defect: boolean;
  created_at: string;
  updated_at: string;
}

export interface SupplierRow {
  id: string;
  workspace_id: string;
  name: string;
  address: string | null;
  contact_info: string | null;
  created_at: string;
  updated_at: string;
}

export interface ProductRow {
  id: string;
  workspace_id: string;
  name: string;
  sku_code: string | null;
  category_id: string | null;
  store_id: string | null;
  is_defect_copy: boolean;
  original_product_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface ProductBalanceRow {
  id: string;
  workspace_id: string;
  product_id: string;
  warehouse_id: string;
  quantity: number;
  unit_cost: number;
  created_at: string;
  updated_at: string;
}

export interface OperationRow {
  id: string;
  workspace_id: string;
  type: OperationType;
  operation_date: string;
  comment: string | null;
  supplier_id: string | null;
  payment_amount: number | null;
  created_at: string;
  updated_at: string;
}

export interface OperationItemRow {
  id: string;
  operation_id: string;
  product_id: string;
  warehouse_id: string;
  quantity: number;
  unit_price: number | null;
  direction: OperationDirection;
  store_id: string | null;
  created_at: string;
}

// ============================================================
// API response interfaces (camelCase)
// ============================================================

export interface Category {
  id: string;
  name: string;
  createdAt: string;
}

export interface Store {
  id: string;
  name: string;
  createdAt: string;
}

export interface Warehouse {
  id: string;
  name: string;
  description: string | null;
  purpose: WarehousePurpose | null;
  isDefaultDefect: boolean;
  createdAt: string;
}

export interface Supplier {
  id: string;
  name: string;
  address: string | null;
  contactInfo: string | null;
  createdAt: string;
}

export interface Product {
  id: string;
  name: string;
  skuCode: string | null;
  categoryId: string | null;
  categoryName: string | null;
  storeId: string | null;
  storeName: string | null;
  isDefectCopy: boolean;
  createdAt: string;
}

export interface ProductBalance {
  id: string;
  productId: string;
  productName: string;
  warehouseId: string;
  warehouseName: string;
  quantity: number;
  unitCost: number;
}

export interface Operation {
  id: string;
  type: OperationType;
  operationDate: string;
  comment: string | null;
  supplierId: string | null;
  supplierName: string | null;
  paymentAmount: number | null;
  createdAt: string;
  items?: OperationItem[];
}

export interface OperationItem {
  id: string;
  productId: string;
  productName: string;
  warehouseId: string;
  warehouseName: string;
  quantity: number;
  unitPrice: number | null;
  direction: OperationDirection;
  storeId: string | null;
  storeName: string | null;
}

// ============================================================
// Request body interfaces
// ============================================================

export interface CreateCategoryRequest {
  name: string;
}

export interface CreateStoreRequest {
  name: string;
}

export interface CreateWarehouseRequest {
  name: string;
  description?: string;
  purpose?: WarehousePurpose;
}

export interface CreateSupplierRequest {
  name: string;
  address?: string;
  contactInfo?: string;
}

export interface CreateProductRequest {
  name: string;
  skuCode?: string;
  categoryId?: string;
  storeId?: string;
}

export interface OperationItemInput {
  productId: string;
  warehouseId: string;
  quantity: number;
  unitPrice?: number;
  direction?: OperationDirection;
  storeId?: string;
}

export interface CreateOperationRequest {
  type: OperationType;
  operationDate: string;
  comment?: string;
  supplierId?: string;
  paymentAmount?: number;
  items?: OperationItemInput[];
  // Transfer-specific
  sourceWarehouseId?: string;
  destinationWarehouseId?: string;
  productId?: string;
  quantity?: number;
}

// ============================================================
// Paginated response
// ============================================================

export interface PaginatedResponse<T> {
  page: {
    limit: number;
    offset: number;
    totalEstimate: number | null;
  };
  items: T[];
}
