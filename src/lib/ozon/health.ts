type JsonRecord = Record<string, unknown>;

export interface OzonConnectionHealth {
  validated?: boolean;
  checkedAt?: string;
  warehouseCount?: number;
  error?: string;
  lastSyncRunId?: string;
}

export function successfulValidationHealth(
  validation: unknown,
  checkedAt = new Date().toISOString()
): OzonConnectionHealth {
  return {
    validated: true,
    checkedAt,
    warehouseCount: countWarehouses(validation),
  };
}

export function failedValidationHealth(
  error: string,
  checkedAt = new Date().toISOString()
): OzonConnectionHealth {
  return {
    validated: false,
    checkedAt,
    error,
  };
}

export function publicOzonHealth(value: unknown): OzonConnectionHealth {
  const health = isRecord(value) ? value : {};
  return {
    validated:
      typeof health.validated === "boolean" ? health.validated : undefined,
    checkedAt: typeof health.checkedAt === "string" ? health.checkedAt : undefined,
    warehouseCount:
      typeof health.warehouseCount === "number" ? health.warehouseCount : undefined,
    error: typeof health.error === "string" ? health.error : undefined,
    lastSyncRunId:
      typeof health.lastSyncRunId === "string" ? health.lastSyncRunId : undefined,
  };
}

function countWarehouses(value: unknown) {
  const root = unwrapResult(value);
  const candidates = [
    value,
    root,
    isRecord(root) ? root.items : undefined,
    isRecord(root) ? root.warehouses : undefined,
    isRecord(root) ? root.result : undefined,
  ];

  for (const candidate of candidates) {
    if (Array.isArray(candidate)) return candidate.length;
  }
  return undefined;
}

function unwrapResult(value: unknown): unknown {
  if (!isRecord(value)) return value;
  return value.result ?? value;
}

function isRecord(value: unknown): value is JsonRecord {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}
