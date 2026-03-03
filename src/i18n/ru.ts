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

  // --- Sidebar nav ---
  dashboard: "Панель",
  masterData: "Справочники",
  products: "Товары",
  warehouses: "Склады",
  suppliers: "Поставщики",
  categories: "Категории",
  stores: "Магазины",
  operations: "Операции",
  reports: "Отчёты",
  team: "Команда",
  logOut: "Выйти",
  loggingOut: "Выход...",

  // --- Common CRUD actions ---
  create: "Создать",
  edit: "Редактировать",
  delete: "Удалить",
  save: "Сохранить",
  cancel: "Отмена",
  actions: "Действия",
  name: "Название",
  description: "Описание",
  loading: "Загрузка...",
  saving: "Сохранение...",
  confirmDelete: "Вы уверены, что хотите удалить этот элемент?",
  deleteConfirm: "Удалить",
  duplicateError: "Элемент с таким названием уже существует",
  unexpectedError: "Произошла непредвиденная ошибка",

  // --- Categories ---
  categoriesTitle: "Категории",
  newCategory: "Новая категория",
  editCategory: "Редактировать категорию",
  categoryName: "Название категории",
  noCategories: "Категорий пока нет",

  // --- Stores ---
  storesTitle: "Магазины",
  newStore: "Новый магазин",
  editStore: "Редактировать магазин",
  storeName: "Название магазина",
  noStores: "Магазинов пока нет",

  // --- Warehouses ---
  warehousesTitle: "Склады",
  newWarehouse: "Новый склад",
  editWarehouse: "Редактировать склад",
  warehouseName: "Название склада",
  warehouseDescription: "Описание",
  warehousePurpose: "Назначение",
  purposeStorage: "Хранение",
  purposeSales: "Продажи",
  purposeProduction: "Производство",
  purposeNone: "Не указано",
  defaultDefect: "Склад брака",
  noWarehouses: "Складов пока нет",
  cannotDeleteDefect: "Невозможно удалить склад брака по умолчанию",

  // --- Suppliers ---
  suppliersTitle: "Поставщики",
  newSupplier: "Новый поставщик",
  editSupplier: "Редактировать поставщика",
  supplierName: "Название поставщика",
  supplierAddress: "Адрес",
  supplierContactInfo: "Контактная информация",
  noSuppliers: "Поставщиков пока нет",

  // --- Products ---
  productsTitle: "Товары",
  newProduct: "Новый товар",
  editProduct: "Редактировать товар",
  productName: "Название товара",
  productSku: "Артикул",
  productCategory: "Категория",
  productStore: "Магазин",
  noProducts: "Товаров пока нет",
  searchProducts: "Поиск товаров...",
  allCategories: "Все категории",
  allStores: "Все магазины",

  // --- Operations ---
  operationsTitle: "Операции",
  newOperation: "Новая операция",
  operationDate: "Дата",
  operationType: "Тип",
  operationComment: "Комментарий",
  operationSupplier: "Поставщик",
  operationAmount: "Сумма",
  noOperations: "Операций пока нет",
  selectType: "Выберите тип",
  selectSupplier: "Выберите поставщика",
  selectProduct: "Выберите товар",
  selectWarehouse: "Выберите склад",
  selectStore: "Выберите магазин",
  quantity: "Количество",
  price: "Цена",
  addItem: "Добавить позицию",
  removeItem: "Удалить",
  sourceWarehouse: "Склад-источник",
  destinationWarehouse: "Склад-получатель",
  paymentAmount: "Сумма оплаты",
  product: "Товар",
  warehouse: "Склад",
  supplier: "Поставщик",
  comment: "Комментарий",
  amount: "Сумма",
  direction: "Направление",
  directionIn: "Приход",
  directionOut: "Расход",

  // --- Operation types ---
  opPurchase: "Закупка",
  opSale: "Продажа",
  opReturn: "Возврат",
  opWriteOff: "Списание",
  opTransfer: "Перемещение",
  opProduction: "Производство",
  opDefect: "Брак",
  opPayment: "Оплата",

  // --- Validation errors ---
  required: "Обязательное поле",
  invalidDate: "Некорректная дата",
  quantityMustBePositive: "Количество должно быть больше 0",
  priceMustBePositive: "Цена должна быть больше 0",
  warehousesMustDiffer: "Склады источника и назначения должны различаться",
  atLeastOneItem: "Необходима хотя бы одна позиция",
  supplierRequired: "Для этого типа операции необходим поставщик",
  paymentAmountRequired: "Необходимо указать сумму оплаты",
};
