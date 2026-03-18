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
  columns: "Столбцы",

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
  duplicateSkuError: "Продукт с таким артикулом уже существует",
  duplicateNameError: "Продукт с таким названием уже существует",
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

  // --- Reports nav ---
  reportsGroup: "Отчёты",
  reportInventory: "Остатки",
  reportMovement: "Движение товаров",
  reportSupplierDebt: "Долги поставщикам",
  reportOperations: "Журнал операций",

  // --- Inventory Balances report ---
  inventoryBalancesTitle: "Остатки на складах",
  currentBalances: "Текущие",
  historicalBalances: "На дату",
  displayUnits: "Единицы",
  displayCost: "Стоимость",
  total: "Итого",
  hideZeros: "Скрыть нулевые",
  showNegativesOnly: "Только отрицательные",
  noBalancesData: "Нет данных об остатках",
  asOfDate: "На дату",

  // --- Product Movement report ---
  productMovementTitle: "Движение товаров",
  groupByProduct: "По товарам",
  groupByWarehouse: "По складам",
  purchaseIn: "Закупка (приход)",
  saleOut: "Продажа (расход)",
  returnIn: "Возврат (приход)",
  writeOffOut: "Списание (расход)",
  transferIn: "Перемещение (приход)",
  transferOut: "Перемещение (расход)",
  productionIn: "Производство (приход)",
  productionOut: "Производство (расход)",
  defectOut: "Брак (расход)",
  net: "Итого",
  noMovementData: "Нет данных о движении за выбранный период",
  dateRangeRequired: "Необходимо указать период",

  // --- Supplier Debt report ---
  supplierDebtTitle: "Долги поставщикам",
  purchasedInPeriod: "Закупки (период)",
  paidInPeriod: "Оплаты (период)",
  currentDebt: "Текущий долг",
  debtType: "Тип долга",
  creditor: "Кредитор",
  debitor: "Дебитор",
  settled: "Расчёт",
  noDebtData: "Нет данных о долгах",
  allDebtTypes: "Все типы",
  drillDownTitle: "Операции с поставщиком",
  totalPurchased: "Всего закуплено",
  totalPaid: "Всего оплачено",
  totalDebt: "Общий долг",

  // --- Operations Log report ---
  operationsLogTitle: "Журнал операций",
  noOperationsLogData: "Нет операций по выбранным фильтрам",
  allTypes: "Все типы",
  allWarehouses: "Все склады",
  allSuppliers: "Все поставщики",
  allProducts: "Все товары",

  // --- Shared pagination ---
  previousPage: "Назад",
  nextPage: "Вперёд",
  pageInfo: (current: number, total: number) => `Стр. ${current} из ${total}`,

  // --- Auth - Login ---
  loginTitle: "Вход",
  loginSubtitle: "Войдите с помощью email и пароля.",
  email: "Email",
  password: "Пароль",
  signingIn: "Вход...",
  logIn: "Войти",
  forgotPassword: "Забыли пароль?",
  createAccount: "Создать аккаунт",

  // --- Auth - Signup ---
  signupTitle: "Создать аккаунт",
  signupSubtitle: "Создайте организацию и первую учётную запись администратора.",
  fullName: "Полное имя",
  organizationName: "Название организации",
  confirmPassword: "Подтвердите пароль",
  creatingAccount: "Создание аккаунта...",
  passwordsMismatch: "Пароли не совпадают.",
  checkEmailConfirm: "Проверьте почту для подтверждения аккаунта.",
  alreadyHaveAccount: "Уже есть аккаунт?",

  // --- Auth - Forgot password ---
  recoverPasswordTitle: "Восстановление пароля",
  recoverPasswordSubtitle: "Мы отправим ссылку для сброса на вашу почту.",
  sending: "Отправка...",
  sendResetEmail: "Отправить ссылку для сброса",
  backToLogin: "Вернуться к входу",
  recoveryEmailSent: "Письмо для восстановления пароля отправлено.",

  // --- Auth - Reset password ---
  setNewPasswordTitle: "Установить новый пароль",
  newPassword: "Новый пароль",
  confirmNewPassword: "Подтвердите новый пароль",
  updating: "Обновление...",
  updatePassword: "Обновить пароль",
  recoverySessionExpired:
    "Сессия восстановления отсутствует или истекла. Запросите новое письмо для сброса.",
  requestPasswordReset: "Запросить сброс пароля",
  passwordUpdated: "Пароль успешно обновлён.",

  // --- Team page ---
  teamTitle: "Команда",
  backToDashboard: "Вернуться на панель",
  organizationAccess: "Доступ к организации",
  organizationAccessSubtitle: "Выберите организацию и пригласите пользователей.",
  organization: "Организация",
  userEmail: "Email пользователя",
  roleLabel: "Роль",
  memberRole: "участник",
  adminRole: "администратор",
  roleInsufficientWarning: (role: string) =>
    `Ваша роль в этой организации — ${role}. Приглашения доступны владельцам и администраторам.`,
  sendingInvite: "Отправка...",
  sendInvite: "Отправить приглашение",
  invitationSent: "Приглашение отправлено.",
  failedToLoad: "Не удалось загрузить аккаунт",
  failedToSendInvite: "Не удалось отправить приглашение",

  // --- Operations form ---
  items: "Позиции",
  sourceMaterials: "Исходные материалы",
  outputProduct: "Готовый продукт",

  // --- Sidebar ---
  navigation: "Навигация",

  // --- Settings ---
  settings: "Настройки",
  settingsGeneral: "Общие",
  settingsProducts: "Товары",
  settingsTeam: "Команда",
  currency: "Валюта",
  currencyDisplayNote: "Изменяет только валюту отображения. Сохранённые значения не конвертируются.",
  settingsSaved: "Настройки сохранены",
  categoryRequiredLabel: "Категория обязательна для товаров",
  categoryRequiredHelp: "Если включено, все товары должны иметь назначенную категорию.",
  storeRequiredLabel: "Магазин обязателен для товаров",
  storeRequiredHelp: "Если включено, все товары должны иметь назначенный магазин.",
  defaultCategory: "Категория по умолчанию",
  defaultStore: "Магазин по умолчанию",
  selectCategory: "Выберите категорию",
  backfillWarning: (field: string) =>
    `Товарам без ${field} будет назначено выбранное значение по умолчанию.`,
  categoryRequiredError: "Категория обязательна",
  storeRequiredError: "Магазин обязателен",

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
