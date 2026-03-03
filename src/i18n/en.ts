export const en = {
  // App
  appName: "Tover",

  // Date range
  from: "From",
  to: "To",

  // KPI cards
  gmvGross: "GMV (Gross)",
  unitsSold: "Units Sold",
  orders: "Orders",
  stockValue: "Stock Value",
  clickToSeeOrders: "Click to see orders",
  snapshot: "Snapshot",
  noInventoryData: "No inventory data",
  na: "N/A",

  // Orders table
  hide: "Hide",
  date: "Date",
  source: "Source",
  orderId: "Order ID",
  status: "Status",
  gmv: "GMV",
  units: "Units",
  noOrdersInRange: "No orders in this date range",

  // Order detail
  back: "Back",
  orderLines: "Order Lines",
  orderIdLabel: "Order ID",
  loadingOrderLines: "Loading order lines...",
  sku: "SKU",
  qty: "Qty",
  unitPrice: "Unit Price",
  discount: "Discount",
  tax: "Tax",
  lineGmv: "Line GMV",
  noOrderLinesFound: "No order lines found",

  // Critical stock
  criticalStockTitle: "Critical Stock (runs out in 14 days)",
  loadingCriticalStock: "Loading critical stock...",
  onHand: "On Hand",
  avgPerDay: "Avg/Day",
  daysLeft: "Days Left",
  noCriticalStockItems: "No critical stock items",

  // Upload card
  importCsv: "Import CSV",
  importType: "Import type",
  importTypeOrders: "Orders",
  importTypeOrderLines: "Order Lines",
  importTypeInventory: "Inventory Snapshots",
  importTypePayments: "Payments",
  csvFile: "CSV file",
  uploading: "Uploading...",
  uploadAndImport: "Upload & Import",
  uploadFailed: "Upload failed",
  importedOf: (inserted: number, total: number) =>
    `Imported ${inserted} of ${total} rows.`,
  errorsCount: (count: number) => `${count} errors.`,
  importStatus: (status: string) => `Import ${status}`,

  // DataTable
  noData: "No data",

  // --- Sidebar nav ---
  dashboard: "Dashboard",
  masterData: "Master Data",
  products: "Products",
  warehouses: "Warehouses",
  suppliers: "Suppliers",
  categories: "Categories",
  stores: "Stores",
  operations: "Operations",
  reports: "Reports",
  team: "Team",
  logOut: "Log out",
  loggingOut: "Logging out...",

  // --- Common CRUD actions ---
  create: "Create",
  edit: "Edit",
  delete: "Delete",
  save: "Save",
  cancel: "Cancel",
  actions: "Actions",
  name: "Name",
  description: "Description",
  loading: "Loading...",
  saving: "Saving...",
  confirmDelete: "Are you sure you want to delete this item?",
  deleteConfirm: "Delete",
  duplicateError: "An item with this name already exists",
  unexpectedError: "An unexpected error occurred",

  // --- Categories ---
  categoriesTitle: "Categories",
  newCategory: "New Category",
  editCategory: "Edit Category",
  categoryName: "Category name",
  noCategories: "No categories yet",

  // --- Stores ---
  storesTitle: "Stores",
  newStore: "New Store",
  editStore: "Edit Store",
  storeName: "Store name",
  noStores: "No stores yet",

  // --- Warehouses ---
  warehousesTitle: "Warehouses",
  newWarehouse: "New Warehouse",
  editWarehouse: "Edit Warehouse",
  warehouseName: "Warehouse name",
  warehouseDescription: "Description",
  warehousePurpose: "Purpose",
  purposeStorage: "Storage",
  purposeSales: "Sales",
  purposeProduction: "Production",
  purposeNone: "None",
  defaultDefect: "Default Defect",
  noWarehouses: "No warehouses yet",
  cannotDeleteDefect: "Cannot delete the default defect warehouse",

  // --- Suppliers ---
  suppliersTitle: "Suppliers",
  newSupplier: "New Supplier",
  editSupplier: "Edit Supplier",
  supplierName: "Supplier name",
  supplierAddress: "Address",
  supplierContactInfo: "Contact info",
  noSuppliers: "No suppliers yet",

  // --- Products ---
  productsTitle: "Products",
  newProduct: "New Product",
  editProduct: "Edit Product",
  productName: "Product name",
  productSku: "SKU Code",
  productCategory: "Category",
  productStore: "Store",
  noProducts: "No products yet",
  searchProducts: "Search products...",
  allCategories: "All categories",
  allStores: "All stores",

  // --- Operations ---
  operationsTitle: "Operations",
  newOperation: "New Operation",
  operationDate: "Date",
  operationType: "Type",
  operationComment: "Comment",
  operationSupplier: "Supplier",
  operationAmount: "Amount",
  noOperations: "No operations yet",
  selectType: "Select type",
  selectSupplier: "Select supplier",
  selectProduct: "Select product",
  selectWarehouse: "Select warehouse",
  selectStore: "Select store",
  quantity: "Quantity",
  price: "Price",
  addItem: "Add item",
  removeItem: "Remove",
  sourceWarehouse: "Source warehouse",
  destinationWarehouse: "Destination warehouse",
  paymentAmount: "Payment amount",
  product: "Product",
  warehouse: "Warehouse",
  supplier: "Supplier",
  comment: "Comment",
  amount: "Amount",
  direction: "Direction",
  directionIn: "In",
  directionOut: "Out",

  // --- Operation types ---
  opPurchase: "Purchase",
  opSale: "Sale",
  opReturn: "Return",
  opWriteOff: "Write-off",
  opTransfer: "Transfer",
  opProduction: "Production",
  opDefect: "Defect",
  opPayment: "Payment",

  // --- Validation errors ---
  required: "This field is required",
  invalidDate: "Invalid date",
  quantityMustBePositive: "Quantity must be greater than 0",
  priceMustBePositive: "Price must be greater than 0",
  warehousesMustDiffer: "Source and destination warehouses must be different",
  atLeastOneItem: "At least one item is required",
  supplierRequired: "Supplier is required for this operation type",
  paymentAmountRequired: "Payment amount is required",
};

export type TranslationKeys = {
  [K in keyof typeof en]: (typeof en)[K] extends (...args: infer A) => string
    ? (...args: A) => string
    : string;
};

export type Locale = "en" | "ru";
