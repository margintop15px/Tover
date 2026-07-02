import type {
  ExistingDuplicate,
  ExtractionResult,
  OperationImportDraft,
  ParsedTable,
  RefData,
  TabularImportPlan,
  TabularImportPlanResult,
} from "./types";
import { detectFileKind } from "./pipeline";
import { normalizeAiDrafts } from "./pipeline";
import type { OperationType } from "@/types/inventory";

const OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses";
const OPERATION_TYPES = new Set<OperationType>([
  "purchase",
  "sale",
  "return",
  "write_off",
  "transfer",
  "production",
  "defect",
  "payment",
  "inventory_adjustment",
]);

const extractionSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    findings: {
      type: "object",
      additionalProperties: false,
      properties: {
        documentType: { type: "string" },
        detectedOperationTypes: { type: "array", items: { type: "string" } },
        assumptions: { type: "array", items: { type: "string" } },
        unresolvedQuestions: { type: "array", items: { type: "string" } },
      },
      required: [
        "documentType",
        "detectedOperationTypes",
        "assumptions",
        "unresolvedQuestions",
      ],
    },
    generatedCode: {
      type: ["string", "null"],
      description:
        "Optional transformation code or pseudocode used by the model. This is stored for audit and never executed by the application server.",
    },
    operations: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          type: {
            type: ["string", "null"],
            enum: [
              "purchase",
              "sale",
              "return",
              "write_off",
              "transfer",
              "production",
              "defect",
              "payment",
              "inventory_adjustment",
              null,
            ],
          },
          operationDate: {
            type: ["string", "null"],
            description:
              "Use yyyy-MM-dd when the calendar date can be determined. If the date is ambiguous or illegible, preserve the visible source text.",
          },
          comment: {
            type: ["string", "null"],
            description:
              "Only literal visible note/comment text from the document, copied as data. Do not summarize the receipt, describe handwriting, mention uncertainty, or include phrases like appears, difficult to read, illegible, blank, or not visible. Use null when there is no readable note/comment.",
          },
          supplierName: { type: ["string", "null"] },
          paymentAmount: { type: ["number", "string", "null"] },
          items: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              properties: {
                productName: { type: ["string", "null"] },
                skuCode: { type: ["string", "null"] },
                warehouseName: { type: ["string", "null"] },
                storeName: { type: ["string", "null"] },
                quantity: { type: ["number", "string", "null"] },
                unitPrice: { type: ["number", "string", "null"] },
                direction: { type: ["string", "null"], enum: ["in", "out", null] },
              },
              required: [
                "productName",
                "skuCode",
                "warehouseName",
                "storeName",
                "quantity",
                "unitPrice",
                "direction",
              ],
            },
          },
        },
        required: [
          "type",
          "operationDate",
          "comment",
          "supplierName",
          "paymentAmount",
          "items",
        ],
      },
    },
  },
  required: ["findings", "generatedCode", "operations"],
} as const;

const tabularPlanSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    findings: {
      type: "object",
      additionalProperties: false,
      properties: {
        documentType: { type: "string" },
        assumptions: { type: "array", items: { type: "string" } },
        unresolvedQuestions: { type: "array", items: { type: "string" } },
      },
      required: ["documentType", "assumptions", "unresolvedQuestions"],
    },
    generatedCode: {
      type: ["string", "null"],
      description:
        "Optional transformation code or pseudocode used by the model. This is stored for audit and never executed by the application server.",
    },
    plan: {
      type: "object",
      additionalProperties: false,
      properties: {
        dateFormat: { type: ["string", "null"] },
        decimalSeparator: { type: ["string", "null"] },
        thousandsSeparator: { type: ["string", "null"] },
        sheets: {
          type: "array",
          items: {
            type: "object",
            additionalProperties: false,
            properties: {
              sheetName: { type: ["string", "null"] },
              sheetIndex: { type: ["integer", "null"] },
              headerRowIndex: { type: ["integer", "null"] },
              dataStartRowIndex: { type: ["integer", "null"] },
              dataEndRowIndex: { type: ["integer", "null"] },
              columns: {
                type: "object",
                additionalProperties: false,
                properties: {
                  operationDate: { type: ["integer", "null"] },
                  type: { type: ["integer", "null"] },
                  productName: { type: ["integer", "null"] },
                  skuCode: { type: ["integer", "null"] },
                  warehouseName: { type: ["integer", "null"] },
                  storeName: { type: ["integer", "null"] },
                  sourceWarehouseName: { type: ["integer", "null"] },
                  destinationWarehouseName: { type: ["integer", "null"] },
                  quantity: { type: ["integer", "null"] },
                  unitPrice: { type: ["integer", "null"] },
                  supplierName: { type: ["integer", "null"] },
                  paymentAmount: { type: ["integer", "null"] },
                  comment: { type: ["integer", "null"] },
                  direction: { type: ["integer", "null"] },
                },
                required: [
                  "operationDate",
                  "type",
                  "productName",
                  "skuCode",
                  "warehouseName",
                  "storeName",
                  "sourceWarehouseName",
                  "destinationWarehouseName",
                  "quantity",
                  "unitPrice",
                  "supplierName",
                  "paymentAmount",
                  "comment",
                  "direction",
                ],
              },
              defaults: {
                type: "object",
                additionalProperties: false,
                properties: {
                  type: {
                    type: ["string", "null"],
                    enum: [
                      "purchase",
                      "sale",
                      "return",
                      "write_off",
                      "transfer",
                      "production",
                      "defect",
                      "payment",
                      "inventory_adjustment",
                      null,
                    ],
                  },
                  operationDate: {
                    type: ["string", "null"],
                    description:
                      "Use yyyy-MM-dd when the calendar date can be determined. If the date is ambiguous or illegible, preserve the visible source text.",
                  },
                  supplierName: { type: ["string", "null"] },
                  warehouseName: { type: ["string", "null"] },
                  comment: { type: ["string", "null"] },
                },
                required: [
                  "type",
                  "operationDate",
                  "supplierName",
                  "warehouseName",
                  "comment",
                ],
              },
              confidence: { type: ["number", "null"] },
              warnings: { type: "array", items: { type: "string" } },
            },
            required: [
              "sheetName",
              "sheetIndex",
              "headerRowIndex",
              "dataStartRowIndex",
              "dataEndRowIndex",
              "columns",
              "defaults",
              "confidence",
              "warnings",
            ],
          },
        },
      },
      required: ["dateFormat", "decimalSeparator", "thousandsSeparator", "sheets"],
    },
  },
  required: ["findings", "generatedCode", "plan"],
} as const;

function getOutputText(response: Record<string, unknown>) {
  if (typeof response.output_text === "string") return response.output_text;
  const output = response.output;
  if (!Array.isArray(output)) return "";

  const chunks: string[] = [];
  for (const item of output) {
    if (!item || typeof item !== "object") continue;
    const content = (item as { content?: unknown }).content;
    if (!Array.isArray(content)) continue;
    for (const contentItem of content) {
      if (!contentItem || typeof contentItem !== "object") continue;
      const text = (contentItem as { text?: unknown }).text;
      if (typeof text === "string") chunks.push(text);
    }
  }
  return chunks.join("\n");
}

function safeJsonParse<T>(text: string): T {
  try {
    return JSON.parse(text) as T;
  } catch {
    const match = /\{[\s\S]*\}/.exec(text);
    if (!match) throw new Error("OpenAI response did not contain JSON");
    return JSON.parse(match[0]) as T;
  }
}

function coerceString(value: unknown) {
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  return undefined;
}

export function sanitizeExtractedComment(value: unknown) {
  const comment = coerceString(value)?.trim();
  if (!comment) return undefined;

  const lower = comment.toLowerCase();
  const isModelDiscourse =
    /\b(appears?|seems?|difficult to read|hard to read|illegible|unreadable|not visible|blank|handwritten)\b/.test(
      lower
    ) || /\[(blank|illegible|unreadable)\]/.test(lower);
  if (!isModelDiscourse) return comment;

  const quoted = /["“](.+?)["”]/.exec(comment)?.[1]?.trim();
  return quoted || undefined;
}

function coerceNumber(value: unknown) {
  if (typeof value === "number") return Number.isFinite(value) ? value : undefined;
  if (typeof value !== "string") return undefined;
  const normalized = value
    .trim()
    .replace(/\s/g, "")
    .replace(/(?<=\d),(?=\d{1,2}$)/, ".")
    .replace(/[^\d.-]/g, "");
  if (!normalized) return undefined;
  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function coerceDraft(value: Record<string, unknown>): OperationImportDraft {
  const type =
    typeof value.type === "string" && OPERATION_TYPES.has(value.type as OperationType)
      ? (value.type as OperationType)
      : undefined;

  return {
    type,
    operationDate: coerceString(value.operationDate),
    comment: sanitizeExtractedComment(value.comment),
    supplierName: coerceString(value.supplierName),
    paymentAmount: coerceNumber(value.paymentAmount),
    rawPaymentAmount: coerceString(value.paymentAmount),
    items: Array.isArray(value.items)
      ? value.items.map((item) => {
          const raw = item as Record<string, unknown>;
          const direction =
            raw.direction === "in" || raw.direction === "out"
              ? raw.direction
              : undefined;

          return {
            productName: coerceString(raw.productName),
            skuCode: coerceString(raw.skuCode),
            warehouseName: coerceString(raw.warehouseName),
            storeName: coerceString(raw.storeName),
            quantity: coerceNumber(raw.quantity),
            rawQuantity: coerceString(raw.quantity),
            unitPrice: coerceNumber(raw.unitPrice),
            rawUnitPrice: coerceString(raw.unitPrice),
            direction,
          };
        })
      : [],
  };
}

function tableContext(tables: ParsedTable[]) {
  return tables
    .map((table) => {
      const rows = table.rows.slice(0, 120).map((row, index) => ({
        rowNumber: index + 1,
        cells: row.slice(0, 40),
      }));
      return {
        sheetName: table.sheetName,
        kind: table.kind,
        rowCount: table.rows.length,
        rows,
      };
    })
    .slice(0, 12);
}

function refContext(ref: RefData) {
  return {
    products: ref.products.slice(0, 1000).map((product) => ({
      name: product.name,
      skuCode: product.skuCode,
    })),
    warehouses: ref.warehouses.slice(0, 300).map((warehouse) => ({
      name: warehouse.name,
    })),
    suppliers: ref.suppliers.slice(0, 300).map((supplier) => ({
      name: supplier.name,
    })),
    stores: ref.stores.slice(0, 300).map((store) => ({
      name: store.name,
    })),
  };
}

function normalizeTabularPlan(value: unknown): TabularImportPlan | null {
  if (!value || typeof value !== "object") return null;
  const raw = value as {
    dateFormat?: unknown;
    decimalSeparator?: unknown;
    thousandsSeparator?: unknown;
    sheets?: unknown;
  };
  if (!Array.isArray(raw.sheets)) return null;

  return {
    dateFormat: coerceString(raw.dateFormat) ?? null,
    decimalSeparator: coerceString(raw.decimalSeparator) ?? null,
    thousandsSeparator: coerceString(raw.thousandsSeparator) ?? null,
    sheets: raw.sheets
      .filter((sheet) => sheet && typeof sheet === "object")
      .map((sheet) => sheet as TabularImportPlan["sheets"][number]),
  };
}

async function callOpenAIExtraction({
  model,
  apiKey,
  fileType,
  content,
  ref,
  existingDuplicates,
  imageDataUrl,
  textPreview,
  instructions,
}: {
  model: string;
  apiKey: string;
  fileType: ExtractionResult["fileType"];
  content: { type: string; text?: string; image_url?: string }[];
  ref: RefData;
  existingDuplicates: ExistingDuplicate[];
  imageDataUrl?: string | null;
  textPreview?: string;
  instructions: string;
}): Promise<ExtractionResult> {
  const body = {
    model,
    reasoning: { effort: "medium" },
    tools: [
      {
        type: "code_interpreter",
        container: { type: "auto", memory_limit: "1g" },
      },
    ],
    instructions,
    input: [
      {
        role: "user",
        content,
      },
    ],
    text: {
      format: {
        type: "json_schema",
        name: "operation_import_extraction",
        strict: true,
        schema: extractionSchema,
      },
    },
  };

  const response = await fetch(OPENAI_RESPONSES_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  const payload = (await response.json()) as Record<string, unknown>;
  if (!response.ok) {
    throw new Error(
      typeof payload.error === "object" && payload.error !== null
        ? String((payload.error as { message?: unknown }).message ?? "OpenAI error")
        : "OpenAI extraction failed"
    );
  }

  const parsed = safeJsonParse<{
    findings?: Record<string, unknown>;
    generatedCode?: string | null;
    operations?: Record<string, unknown>[];
  }>(getOutputText(payload));
  const drafts = (parsed.operations ?? []).map(coerceDraft);

  return {
    fileType,
    tables: [],
    candidates: normalizeAiDrafts(drafts, ref, existingDuplicates, fileType),
    findings: {
      parser: "openai",
      model,
      ...(parsed.findings ?? {}),
    },
    extracted: {
      textPreview: textPreview?.slice(0, 4000) ?? "",
      imageIncluded: Boolean(imageDataUrl),
      responseId: payload.id,
    },
    generatedCode: parsed.generatedCode ?? null,
    generatedCodeResult: {
      rawToolCalls: Array.isArray(payload.output)
        ? payload.output.filter(
            (item) =>
              item &&
              typeof item === "object" &&
              (item as { type?: unknown }).type === "code_interpreter_call"
          )
        : [],
    },
    securityReport: {
      localGeneratedCodeExecution: false,
      openAiCodeInterpreter: true,
      sandbox: "OpenAI Responses API code_interpreter container",
      appSecretsExposed: false,
      databaseCredentialsExposed: false,
      networkAccessGrantedByApp: false,
      localFilesystemAccess: false,
    },
  };
}

export async function extractWithOpenAI({
  fileName,
  mimeType,
  buffer,
  ref,
  existingDuplicates,
}: {
  fileName: string;
  mimeType: string;
  buffer: Buffer;
  ref: RefData;
  existingDuplicates: ExistingDuplicate[];
}): Promise<ExtractionResult> {
  const apiKey = process.env.OPENAI_API_KEY;
  const fileType = detectFileKind(fileName, mimeType);

  if (!apiKey) {
    return {
      fileType,
      tables: [],
      candidates: [],
      findings: {
        parser: "openai",
        error: "OPENAI_API_KEY is not configured",
      },
      extracted: {},
      generatedCode: null,
      generatedCodeResult: {},
      securityReport: {
        localGeneratedCodeExecution: false,
        openAiCodeInterpreter: false,
        reason: "No API key configured",
      },
    };
  }

  const model = process.env.OPENAI_OPERATION_IMPORT_MODEL || "gpt-5.5";
  const isImage = mimeType.startsWith("image/");
  const text = !isImage ? buffer.toString("utf8").slice(0, 120_000) : "";
  const dataUrl = isImage
    ? `data:${mimeType || "image/png"};base64,${buffer.toString("base64")}`
    : null;

  return callOpenAIExtraction({
    model,
    apiKey,
    fileType,
    ref,
    existingDuplicates,
    imageDataUrl: dataUrl,
    textPreview: text,
    instructions:
      "Extract inventory operations from the user's file. Return only structured data, not prose. Do not guess IDs. Preserve uncertainty in findings, not operation fields. For visible names, return the source text as-is even if it looks misspelled, incomplete, or not like a valid word. For comment, return only literal visible note/comment text; never describe handwriting, uncertainty, blanks, unreadable text, or what appears in the image. If no readable comment/note exists, use null. For dates, return yyyy-MM-dd when the calendar date is clear; otherwise return the visible source text rather than null. If transformation code is useful, include it as generatedCode for audit; the application will not execute it locally.",
    content: [
      {
        type: "input_text",
        text:
          "Convert this document into candidate operations for the Tover inventory system. Supported operation types: purchase, sale, return, write_off, transfer, production, defect, payment, inventory_adjustment.",
      },
      ...(dataUrl
        ? [{ type: "input_image", image_url: dataUrl }]
        : [
            {
              type: "input_text",
              text,
            },
          ]),
    ],
  });
}

export async function inferTabularImportPlan({
  fileType,
  tables,
  deterministicFindings,
  ref,
}: {
  fileType: "csv" | "xlsx";
  tables: ParsedTable[];
  deterministicFindings: Record<string, unknown>;
  ref: RefData;
}): Promise<TabularImportPlanResult> {
  const apiKey = process.env.OPENAI_API_KEY;

  if (!apiKey) {
    return {
      fileType,
      plan: null,
      findings: {
        parser: "openai_tabular_plan",
        error: "OPENAI_API_KEY is not configured",
        deterministicFindings,
      },
      extracted: {
        tables: tableContext(tables),
      },
      generatedCode: null,
      generatedCodeResult: {},
      securityReport: {
        localGeneratedCodeExecution: false,
        openAiCodeInterpreter: false,
        reason: "No API key configured",
      },
    };
  }

  const model = process.env.OPENAI_OPERATION_IMPORT_MODEL || "gpt-5.5";
  const context = {
    deterministicFindings,
    knownEntities: refContext(ref),
    tables: tableContext(tables),
  };
  const text = JSON.stringify(context);

  const body = {
    model,
    reasoning: { effort: "medium" },
    instructions:
      "Infer a deterministic import plan for messy CSV/XLSX inventory operation imports. The application will execute this plan locally against the full parsed file. Return column indexes as zero-based integers. Do not return candidate operations. Use null only when a column is absent. Preserve visible source text by mapping columns/defaults instead of discarding uncertain values. Product identity is SKU-based: map the stable product identifier column to skuCode. If both SKU and Артикул/seller article/offer ID are present, prefer the SKU column; if only article/offer ID is present, map that column to skuCode.",
    input: [
      {
        role: "user",
        content: [
          {
            type: "input_text",
            text:
              "Infer sheet/header/data row and column mappings for these parsed workbook/table rows. Supported fields: operationDate, type, productName, skuCode, warehouseName, storeName, sourceWarehouseName, destinationWarehouseName, quantity, unitPrice, supplierName, paymentAmount, comment, direction. Map visible product names/titles to productName. Map SKU, seller SKU, article, offer ID, or product code to skuCode using the SKU preference rule from the system instructions. Supported default operation types: purchase, sale, return, write_off, transfer, production, defect, payment, inventory_adjustment.",
          },
          {
            type: "input_text",
            text,
          },
        ],
      },
    ],
    text: {
      format: {
        type: "json_schema",
        name: "operation_import_tabular_plan",
        strict: true,
        schema: tabularPlanSchema,
      },
    },
  };

  const response = await fetch(OPENAI_RESPONSES_URL, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  const payload = (await response.json()) as Record<string, unknown>;
  if (!response.ok) {
    throw new Error(
      typeof payload.error === "object" && payload.error !== null
        ? String((payload.error as { message?: unknown }).message ?? "OpenAI error")
        : "OpenAI tabular plan inference failed"
    );
  }

  const parsed = safeJsonParse<{
    findings?: Record<string, unknown>;
    generatedCode?: string | null;
    plan?: unknown;
  }>(getOutputText(payload));

  return {
    fileType,
    plan: normalizeTabularPlan(parsed.plan),
    findings: {
      parser: "openai_tabular_plan",
      model,
      deterministicFindings,
      ...(parsed.findings ?? {}),
    },
    extracted: {
      tables: tableContext(tables),
      responseId: payload.id,
    },
    generatedCode: parsed.generatedCode ?? null,
    generatedCodeResult: {},
    securityReport: {
      localGeneratedCodeExecution: false,
      openAiCodeInterpreter: false,
      appSecretsExposed: false,
      databaseCredentialsExposed: false,
      networkAccessGrantedByApp: false,
      localFilesystemAccess: false,
    },
  };
}
