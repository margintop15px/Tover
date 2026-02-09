import type { TranslationKeys } from "./en";

export const ru: TranslationKeys = {
  // App
  appName: "Tover",

  // Date range
  from: "С",
  to: "По",

  // KPI cards
  gmvGross: "GMV (валовый)",
  unitsSold: "Продано единиц",
  orders: "Заказы",
  stockValue: "Стоимость склада",
  clickToSeeOrders: "Нажмите для просмотра заказов",
  snapshot: "Снимок",
  noInventoryData: "Нет данных о складе",
  na: "Н/Д",

  // Orders table
  hide: "Скрыть",
  date: "Дата",
  source: "Источник",
  orderId: "ID заказа",
  status: "Статус",
  gmv: "GMV",
  units: "Единицы",
  noOrdersInRange: "Нет заказов за этот период",

  // Order detail
  back: "Назад",
  orderLines: "Позиции заказа",
  orderIdLabel: "ID заказа",
  loadingOrderLines: "Загрузка позиций заказа...",
  sku: "Артикул",
  qty: "Кол-во",
  unitPrice: "Цена за единицу",
  discount: "Скидка",
  tax: "Налог",
  lineGmv: "GMV позиции",
  noOrderLinesFound: "Позиции заказа не найдены",

  // Critical stock
  criticalStockTitle: "Критический запас (закончится за 14 дней)",
  loadingCriticalStock: "Загрузка критического запаса...",
  onHand: "На складе",
  avgPerDay: "Ср./день",
  daysLeft: "Дней осталось",
  noCriticalStockItems: "Нет товаров с критическим запасом",

  // Upload card
  importCsv: "Импорт CSV",
  importType: "Тип импорта",
  importTypeOrders: "Заказы",
  importTypeOrderLines: "Позиции заказов",
  importTypeInventory: "Снимки остатков",
  importTypePayments: "Платежи",
  csvFile: "CSV файл",
  uploading: "Загрузка...",
  uploadAndImport: "Загрузить и импортировать",
  uploadFailed: "Ошибка загрузки",
  importedOf: (inserted: number, total: number) =>
    `Импортировано ${inserted} из ${total} строк.`,
  errorsCount: (count: number) => `${count} ошибок.`,
  importStatus: (status: string) => `Импорт ${status}`,

  // DataTable
  noData: "Нет данных",
};
