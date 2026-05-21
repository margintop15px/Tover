function formatDateParts(year: number, month: number, day: number) {
  const date = new Date(Date.UTC(year, month - 1, day));
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return undefined;
  }

  return [
    String(year).padStart(4, "0"),
    String(month).padStart(2, "0"),
    String(day).padStart(2, "0"),
  ].join("-");
}

function excelSerialToDate(serial: number) {
  const epoch = Date.UTC(1899, 11, 30);
  const date = new Date(epoch + serial * 24 * 60 * 60 * 1000);
  return date.toISOString().slice(0, 10);
}

export function parseOperationImportDate(value: string | undefined) {
  const raw = (value ?? "").trim();
  if (!raw) return undefined;
  const asNumber = Number(raw);
  if (Number.isFinite(asNumber) && asNumber > 20000 && asNumber < 80000) {
    return excelSerialToDate(asNumber);
  }

  const iso = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(raw);
  if (iso) {
    const [, year, month, day] = iso;
    return formatDateParts(Number(year), Number(month), Number(day));
  }

  const numeric = /^(\d{1,2})[./\-\s]+(\d{1,2})[./\-\s]+(\d{2,4})$/.exec(
    raw
  );
  if (numeric) {
    const [, first, second, year] = numeric;
    const fullYear = year.length === 2 ? `20${year}` : year;
    return (
      formatDateParts(Number(fullYear), Number(second), Number(first)) ??
      formatDateParts(Number(fullYear), Number(first), Number(second))
    );
  }

  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) return undefined;
  return parsed.toISOString().slice(0, 10);
}

export function operationImportDateInputValue(value: string | undefined) {
  return parseOperationImportDate(value) ?? "";
}
