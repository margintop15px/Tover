export function formatCurrency(
  value: number,
  locale: string,
  currency: string = "EUR"
): string {
  return new Intl.NumberFormat(locale === "ru" ? "ru-RU" : "en-US", {
    style: "currency",
    currency,
    minimumFractionDigits: 2,
  }).format(value);
}
