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
  columns: "Columns",

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
  clearFilters: "Clear filters",
  actions: "Actions",
  name: "Name",
  description: "Description",
  loading: "Loading...",
  saving: "Saving...",
  confirmDelete: "Are you sure you want to delete this item?",
  deleteConfirm: "Delete",
  duplicateError: "An item with this name already exists",
  duplicateSkuError: "A product with this SKU already exists",
  duplicateNameError: "A product with this name already exists",
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
  editOperation: "Edit Operation",
  operationDetails: "Operation Details",
  viewOperation: "View operation",
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
  opInventoryAdjustment: "Inventory Adjustment",

  // --- Reports nav ---
  reportsGroup: "Reports",
  reportInventory: "Inventory Balances",
  reportMovement: "Product Movement",
  reportSupplierDebt: "Supplier Debt",

  // --- Inventory Balances report ---
  inventoryBalancesTitle: "Inventory Balances",
  currentBalances: "Current",
  historicalBalances: "Historical",
  displayUnits: "Units",
  displayCost: "Cost",
  total: "Total",
  hideZeros: "Hide zeros",
  showNegativesOnly: "Negatives only",
  noBalancesData: "No inventory balances data",
  asOfDate: "As of date",

  // --- Product Movement report ---
  productMovementTitle: "Product Movement",
  groupByProduct: "By Product",
  groupByWarehouse: "By Warehouse",
  purchaseIn: "Purchase In",
  saleOut: "Sale Out",
  returnIn: "Return In",
  writeOffOut: "Write-off Out",
  transferIn: "Transfer In",
  transferOut: "Transfer Out",
  productionIn: "Production In",
  productionOut: "Production Out",
  defectOut: "Defect Out",
  inventoryAdjustmentIn: "Inventory Adjustment In",
  net: "Net",
  noMovementData: "No movement data for the selected period",
  dateRangeRequired: "Date range is required",

  // --- Supplier Debt report ---
  supplierDebtTitle: "Supplier Debt",
  purchasedInPeriod: "Purchased (period)",
  paidInPeriod: "Paid (period)",
  currentDebt: "Current Debt",
  debtType: "Debt Type",
  creditor: "Creditor",
  debitor: "Debitor",
  settled: "Settled",
  noDebtData: "No supplier debt data",
  allDebtTypes: "All types",
  drillDownTitle: "Supplier Transactions",
  totalPurchased: "Total Purchased",
  totalPaid: "Total Paid",
  totalDebt: "Total Debt",

  allTypes: "All types",
  allWarehouses: "All warehouses",
  allSuppliers: "All suppliers",
  allProducts: "All products",

  // --- Shared pagination ---
  previousPage: "Previous",
  nextPage: "Next",
  pageInfo: (current: number, total: number) => `Page ${current} of ${total}`,

  // --- Auth - Login ---
  loginTitle: "Log in",
  loginSubtitle: "Sign in with your email and password.",
  email: "Email",
  password: "Password",
  signingIn: "Signing in...",
  logIn: "Log in",
  forgotPassword: "Forgot password?",
  createAccount: "Create account",

  // --- Auth - Signup ---
  signupTitle: "Create account",
  signupSubtitle: "Create your organization and the first admin account.",
  fullName: "Full name",
  organizationName: "Organization name",
  confirmPassword: "Confirm password",
  creatingAccount: "Creating account...",
  passwordsMismatch: "Passwords do not match.",
  checkEmailConfirm: "Check your email to confirm your account.",
  alreadyHaveAccount: "Already have an account?",

  // --- Auth - Forgot password ---
  recoverPasswordTitle: "Recover password",
  recoverPasswordSubtitle: "We will send a reset link to your email.",
  sending: "Sending...",
  sendResetEmail: "Send reset email",
  backToLogin: "Back to login",
  recoveryEmailSent: "Password recovery email sent.",

  // --- Auth - Reset password ---
  setNewPasswordTitle: "Set new password",
  newPassword: "New password",
  confirmNewPassword: "Confirm new password",
  updating: "Updating...",
  updatePassword: "Update password",
  recoverySessionExpired:
    "Recovery session is missing or expired. Request a new reset email.",
  requestPasswordReset: "Request password reset",
  passwordUpdated: "Password updated successfully.",

  // --- Team page ---
  teamTitle: "Team",
  backToDashboard: "Back to dashboard",
  organizationAccess: "Organization access",
  organizationAccessSubtitle: "Select an organization and invite users.",
  organization: "Organization",
  userEmail: "User email",
  roleLabel: "Role",
  memberRole: "member",
  adminRole: "admin",
  roleInsufficientWarning: (role: string) =>
    `Your role in this organization is ${role}. Invites require owner or admin.`,
  sendingInvite: "Sending...",
  sendInvite: "Send invite",
  invitationSent: "Invitation sent.",
  failedToLoad: "Failed to load account",
  failedToSendInvite: "Failed to send invite",

  // --- Operations form ---
  items: "Items",
  sourceMaterials: "Source Materials",
  outputProduct: "Output Product",
  operationItemsReadOnly: "Line items are read-only for now.",
  operationGroupIncoming: "Incoming",
  operationGroupMovement: "Internal Movement",
  operationGroupOutgoing: "Outgoing",
  operationGroupAdjustments: "Adjustments",
  operationGroupPayments: "Payments",
  opPurchaseDescription: "Stock received from a supplier",
  opReturnDescription: "Returned stock received back",
  opTransferDescription: "Move stock between warehouses",
  opProductionDescription: "Consume materials and create product",
  opDefectDescription: "Move damaged stock to defects",
  opSaleDescription: "Stock sold to a customer",
  opWriteOffDescription: "Stock loss, damage, or disposal",
  opInventoryAdjustmentDescription: "Initial warehouse stock backfill",
  opPaymentDescription: "Money paid to a supplier",
  unitCost: "Unit cost",

  // --- Sidebar ---
  navigation: "Navigation",

  // --- Settings ---
  settings: "Settings",
  settingsGeneral: "General",
  settingsProducts: "Products",
  settingsTeam: "Team",
  currency: "Currency",
  currencyDisplayNote: "Changes display currency only. Stored values are not converted.",
  settingsSaved: "Settings saved",
  categoryRequiredLabel: "Category required for products",
  categoryRequiredHelp: "When enabled, all products must have a category assigned.",
  storeRequiredLabel: "Store required for products",
  storeRequiredHelp: "When enabled, all products must have a store assigned.",
  defaultCategory: "Default category",
  defaultStore: "Default store",
  selectCategory: "Select category",
  backfillWarning: (field: string) =>
    `Products without a ${field} will be assigned the selected default.`,
  categoryRequiredError: "Category is required",
  storeRequiredError: "Store is required",

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
