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
};

export type TranslationKeys = {
  [K in keyof typeof en]: (typeof en)[K] extends (...args: infer A) => string
    ? (...args: A) => string
    : string;
};

export type Locale = "en" | "ru";
