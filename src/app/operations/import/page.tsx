"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  AlertTriangle,
  Check,
  Copy,
  Eye,
  FileUp,
  Loader2,
  Upload,
} from "lucide-react";
import { useI18n } from "@/i18n/context";
import { useWorkspaceSettings } from "@/contexts/WorkspaceSettingsContext";
import Pagination from "@/components/Pagination";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import ImportDefaultField from "@/components/ImportDefaultField";
import type {
  OperationImportCandidateRecord,
  OperationImportDraft,
  OperationImportItemDraft,
  OperationImportRecord,
} from "@/lib/operation-imports/types";
import { operationImportDateInputValue } from "@/lib/operation-imports/date";
import type {
  Category,
  OperationType,
  Product,
  Store,
  Supplier,
  Warehouse,
  WarehousePurpose,
} from "@/types/inventory";

type Step = "upload" | "approve";

interface ImportJobResponse {
  import: OperationImportRecord;
  candidates: OperationImportCandidateRecord[];
}

const OPERATION_TYPES: OperationType[] = [
  "purchase",
  "sale",
  "return",
  "write_off",
  "transfer",
  "production",
  "defect",
  "payment",
  "inventory_adjustment",
];
const CANDIDATES_PER_PAGE = 50;
const BULK_SAVING_ID = "__bulk";

type CandidateUpdate = {
  candidateId: string;
  operation: OperationImportDraft;
};

type CreateEntityRequest = {
  kind: "supplier" | "product" | "warehouse";
  candidate: OperationImportCandidateRecord;
  operation: OperationImportDraft;
  item?: OperationImportItemDraft;
  rawName?: string;
};

type BulkAction = {
  fieldLabel: string;
  valueLabel: string;
  buildOperation: (operation: OperationImportDraft) => OperationImportDraft;
  isBlank: (operation: OperationImportDraft) => boolean;
  shouldInclude?: (operation: OperationImportDraft) => boolean;
};

function getSummaryNumber(
  summary: Record<string, unknown> | undefined,
  key: string
) {
  const value = summary?.[key];
  return typeof value === "number" ? value : 0;
}

function getOperation(candidate: OperationImportCandidateRecord) {
  return (
    candidate.normalized_operation ||
    candidate.operation ||
    {}
  ) as OperationImportDraft;
}

function getFirstItem(operation: OperationImportDraft): OperationImportItemDraft {
  return operation.items?.[0] ?? {};
}

function replaceFirstItem(
  operation: OperationImportDraft,
  patch: Partial<OperationImportItemDraft>
): OperationImportDraft {
  const items = operation.items?.length ? [...operation.items] : [{}];
  items[0] = { ...items[0], ...patch };
  return { ...operation, items };
}

function stringifyEvidence(value: unknown) {
  if (!value) return "";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}

function upsertById<T extends { id: string; name: string }>(items: T[], item: T) {
  const next = items.some((existing) => existing.id === item.id)
    ? items.map((existing) => (existing.id === item.id ? item : existing))
    : [...items, item];
  return next.sort((a, b) => a.name.localeCompare(b.name));
}

function optionName<T extends { id: string; name: string }>(
  items: T[],
  id?: string,
  fallback?: string
) {
  return items.find((item) => item.id === id)?.name || fallback || "";
}

function mergeCandidatesById(
  current: OperationImportCandidateRecord[],
  updates: OperationImportCandidateRecord[]
) {
  if (updates.length === 0) return current;
  const byId = new Map(updates.map((candidate) => [candidate.id, candidate]));
  return current.map((candidate) => byId.get(candidate.id) ?? candidate);
}

function patchOperationForCreatedEntity(
  operation: OperationImportDraft,
  kind: CreateEntityRequest["kind"],
  item: Product | Supplier | Warehouse
) {
  if (kind === "supplier") {
    const supplier = item as Supplier;
    return {
      ...operation,
      supplierId: supplier.id,
      supplierName: supplier.name,
      createSupplier: false,
    };
  }

  if (kind === "warehouse") {
    const warehouse = item as Warehouse;
    return replaceFirstItem(operation, {
      warehouseId: warehouse.id,
      warehouseName: warehouse.name,
      createWarehouse: false,
    });
  }

  const product = item as Product;
  const firstItem = getFirstItem(operation);
  return replaceFirstItem(operation, {
    productId: product.id,
    productName: product.name,
    skuCode: product.skuCode || firstItem.skuCode,
    storeId: product.storeId || firstItem.storeId,
    createProduct: false,
  });
}

function createdEntityErrorField(kind: CreateEntityRequest["kind"]) {
  if (kind === "supplier") return "supplierId";
  if (kind === "warehouse") return "items[0].warehouseId";
  return "items[0].productId";
}

function patchCandidateForCreatedEntity(
  candidate: OperationImportCandidateRecord,
  kind: CreateEntityRequest["kind"],
  item: Product | Supplier | Warehouse
) {
  const validationErrors = candidate.validation_errors.filter(
    (error) => error.field !== createdEntityErrorField(kind)
  );
  return {
    ...candidate,
    operation: patchOperationForCreatedEntity(candidate.operation || {}, kind, item),
    normalized_operation: patchOperationForCreatedEntity(
      getOperation(candidate),
      kind,
      item
    ),
    validation_errors: validationErrors,
    status: validationErrors.length === 0 ? "ready" : "needs_review",
  } satisfies OperationImportCandidateRecord;
}

export default function OperationImportPage() {
  const { t } = useI18n();
  const { settings } = useWorkspaceSettings();
  const router = useRouter();
  const [step, setStep] = useState<Step>("upload");
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [savingCandidateId, setSavingCandidateId] = useState<string | null>(null);
  const [approving, setApproving] = useState(false);
  const [committing, setCommitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [job, setJob] = useState<OperationImportRecord | null>(null);
  const [candidates, setCandidates] = useState<OperationImportCandidateRecord[]>(
    []
  );
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<Warehouse[]>([]);
  const [suppliers, setSuppliers] = useState<Supplier[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [createRequest, setCreateRequest] =
    useState<CreateEntityRequest | null>(null);
  const [evidenceCandidate, setEvidenceCandidate] =
    useState<OperationImportCandidateRecord | null>(null);

  const typeLabel = useCallback(
    (type: OperationType): string => {
      const map: Record<OperationType, string> = {
        purchase: t.opPurchase,
        sale: t.opSale,
        return: t.opReturn,
        write_off: t.opWriteOff,
        transfer: t.opTransfer,
        production: t.opProduction,
        defect: t.opDefect,
        payment: t.opPayment,
        inventory_adjustment: t.opInventoryAdjustment,
      };
      return map[type];
    },
    [t]
  );

  useEffect(() => {
    Promise.all([
      fetch("/api/products?limit=5000").then((res) => res.json()),
      fetch("/api/warehouses?limit=1000").then((res) => res.json()),
      fetch("/api/suppliers?limit=1000").then((res) => res.json()),
      fetch("/api/categories?limit=1000").then((res) => res.json()),
      fetch("/api/stores?limit=1000").then((res) => res.json()),
    ]).then(([productData, warehouseData, supplierData, categoryData, storeData]) => {
      setProducts(productData.items || []);
      setWarehouses(warehouseData.items || []);
      setSuppliers(supplierData.items || []);
      setCategories(categoryData.items || []);
      setStores(storeData.items || []);
    });
  }, []);

  const refreshJob = useCallback(async (id: string) => {
    const res = await fetch(`/api/operation-imports/${id}`);
    const data = (await res.json()) as ImportJobResponse;
    if (!res.ok) throw new Error((data as unknown as { error?: string }).error);
    setJob(data.import);
    setCandidates(data.candidates || []);
  }, []);

  const uploadFile = async () => {
    if (!file) return;
    setUploading(true);
    setError(null);
    const formData = new FormData();
    formData.append("file", file);

    try {
      const res = await fetch("/api/operation-imports", {
        method: "POST",
        body: formData,
      });
      const data = (await res.json()) as ImportJobResponse & {
        error?: string;
        detail?: string;
      };
      if (!res.ok) {
        throw new Error(
          data.detail
            ? `${data.error || t.uploadFailed}: ${data.detail}`
            : data.error || t.uploadFailed
        );
      }
      setJob(data.import);
      setCandidates(data.candidates || []);
      setStep("approve");
    } catch (err) {
      setError(err instanceof Error ? err.message : t.uploadFailed);
    } finally {
      setUploading(false);
    }
  };

  const patchCandidate = async (
    candidate: OperationImportCandidateRecord,
    operation: OperationImportDraft
  ) => {
    if (!job) return false;
    setSavingCandidateId(candidate.id);
    setError(null);
    setCandidates((current) =>
      current.map((item) =>
        item.id === candidate.id
          ? { ...item, operation, normalized_operation: operation }
          : item
      )
    );

    try {
      const res = await fetch(`/api/operation-imports/${job.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ candidateId: candidate.id, operation }),
      });
      const data = (await res.json()) as {
        candidate?: OperationImportCandidateRecord;
        summary?: Record<string, unknown>;
        error?: string;
      };
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      const updatedCandidate = data.candidate;
      if (updatedCandidate) {
        setCandidates((current) => mergeCandidatesById(current, [updatedCandidate]));
      }
      const updatedSummary = data.summary;
      if (updatedSummary) {
        setJob((current) =>
          current ? { ...current, summary: updatedSummary } : current
        );
      }
      return true;
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
      return false;
    } finally {
      setSavingCandidateId(null);
    }
  };

  const reprocessCreatedEntity = async (
    kind: CreateEntityRequest["kind"],
    item: Product | Supplier | Warehouse
  ) => {
    if (!job) return;
    setSavingCandidateId(BULK_SAVING_ID);
    setError(null);
    try {
      const product = kind === "product" ? (item as Product) : null;
      const res = await fetch(`/api/operation-imports/${job.id}/reprocess`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          createdEntity: {
            kind,
            id: item.id,
            name: item.name,
            skuCode: product?.skuCode ?? null,
          },
        }),
      });
      const data = (await res.json()) as {
        updatedCandidateIds?: string[];
        summary?: Record<string, unknown>;
        error?: string;
      };
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      const ids = new Set(data.updatedCandidateIds || []);
      if (ids.size > 0) {
        setCandidates((current) =>
          current.map((candidate) =>
            ids.has(candidate.id)
              ? patchCandidateForCreatedEntity(candidate, kind, item)
              : candidate
          )
        );
      }
      if (data.summary) {
        setJob((current) =>
          current ? { ...current, summary: data.summary! } : current
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
    } finally {
      setSavingCandidateId(null);
    }
  };

  const patchCandidates = async (updates: CandidateUpdate[]) => {
    if (!job || updates.length === 0) return;
    setSavingCandidateId(BULK_SAVING_ID);
    setError(null);
    const updateById = new Map(
      updates.map((update) => [update.candidateId, update.operation])
    );
    setCandidates((current) =>
      current.map((item) => {
        const operation = updateById.get(item.id);
        return operation
          ? { ...item, operation, normalized_operation: operation }
          : item;
      })
    );

    try {
      const res = await fetch(`/api/operation-imports/${job.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ candidateUpdates: updates }),
      });
      const data = (await res.json()) as {
        candidates?: OperationImportCandidateRecord[];
        summary?: Record<string, unknown>;
        error?: string;
      };
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      const updatedCandidates = data.candidates;
      if (updatedCandidates) {
        setCandidates((current) => mergeCandidatesById(current, updatedCandidates));
      }
      const updatedSummary = data.summary;
      if (updatedSummary) {
        setJob((current) =>
          current ? { ...current, summary: updatedSummary } : current
        );
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
    } finally {
      setSavingCandidateId(null);
    }
  };

  const approveCandidate = async (candidate: OperationImportCandidateRecord) => {
    if (!job) return;
    setSavingCandidateId(candidate.id);
    setError(null);
    try {
      const res = await fetch(`/api/operation-imports/${job.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ approveCandidateId: candidate.id }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      await refreshJob(job.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
    } finally {
      setSavingCandidateId(null);
    }
  };

  const approveAll = async () => {
    if (!job) return;
    setApproving(true);
    setError(null);
    try {
      const res = await fetch(`/api/operation-imports/${job.id}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ approveAll: true }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      await refreshJob(job.id);
      setStep("approve");
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
    } finally {
      setApproving(false);
    }
  };

  const commitImport = async () => {
    if (!job) return;
    setCommitting(true);
    setError(null);
    try {
      const res = await fetch(`/api/operation-imports/${job.id}/commit`, {
        method: "POST",
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      await refreshJob(job.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
    } finally {
      setCommitting(false);
    }
  };

  const handleCreatedEntity = async (
    item: Product | Supplier | Warehouse
  ) => {
    const request = createRequest;
    if (!request) return;

    if (request.kind === "supplier") {
      const supplier = item as Supplier;
      setSuppliers((current) => upsertById(current, supplier));
      const patched = await patchCandidate(request.candidate, {
        ...request.operation,
        supplierId: supplier.id,
        supplierName: supplier.name,
        createSupplier: false,
      });
      if (!patched) return;
    }

    if (request.kind === "product") {
      const product = item as Product;
      setProducts((current) => upsertById(current, product));
      const patched = await patchCandidate(
        request.candidate,
        replaceFirstItem(request.operation, {
          productId: product.id,
          productName: product.name,
          skuCode: product.skuCode || request.item?.skuCode,
          storeId: product.storeId || request.item?.storeId,
          createProduct: false,
        })
      );
      if (!patched) return;
    }

    if (request.kind === "warehouse") {
      const warehouse = item as Warehouse;
      setWarehouses((current) => upsertById(current, warehouse));
      const patched = await patchCandidate(
        request.candidate,
        replaceFirstItem(request.operation, {
          warehouseId: warehouse.id,
          warehouseName: warehouse.name,
          createWarehouse: false,
        })
      );
      if (!patched) return;
    }

    setCreateRequest(null);
    void reprocessCreatedEntity(request.kind, item);
  };

  const summary = job?.summary ?? {};
  const total = getSummaryNumber(summary, "total");
  const needsReview = getSummaryNumber(summary, "needsReview");
  const ready = getSummaryNumber(summary, "ready");
  const approved = getSummaryNumber(summary, "approved");
  const committed = getSummaryNumber(summary, "committed");
  const allApproved = candidates.length > 0 && approved === candidates.length;
  const committedIds = useMemo(() => {
    const ids = summary.operationIds;
    return Array.isArray(ids) ? ids.length : committed;
  }, [summary.operationIds, committed]);

  const statusBadge = (candidate: OperationImportCandidateRecord) => {
    if (candidate.status === "approved" || candidate.status === "committed") {
      return <Badge className="bg-emerald-600">{candidate.status}</Badge>;
    }
    if ((candidate.validation_errors || []).length > 0) {
      return <Badge variant="destructive">{t.clarify}</Badge>;
    }
    return <Badge variant="secondary">{candidate.status}</Badge>;
  };

  return (
    <div className="p-6">
      <div className="mb-6 flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold">{t.operationsImportTitle}</h1>
          {job && (
            <p className="mt-1 text-sm text-muted-foreground">{job.file_name}</p>
          )}
        </div>
        <Link href="/operations">
          <Button variant="outline">{t.back}</Button>
        </Link>
      </div>

      <Tabs value={step} onValueChange={(value) => setStep(value as Step)}>
        <TabsList className="mb-6 flex h-auto flex-wrap gap-1">
          <TabsTrigger value="upload">{t.uploadFile}</TabsTrigger>
          <TabsTrigger value="approve" disabled={!job}>
            {t.approve}
          </TabsTrigger>
        </TabsList>
      </Tabs>

      {error && (
        <div className="mb-4 flex items-center gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4" />
          {error}
        </div>
      )}

      {step === "upload" && (
        <div className="max-w-2xl">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <FileUp className="h-5 w-5" />
                {t.uploadOperationsFile}
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <p className="text-sm text-muted-foreground">
                {t.operationImportFileHelp}
              </p>
              <Field>
                <FieldLabel>{t.chooseFile}</FieldLabel>
                <Input
                  type="file"
                  accept=".csv,.xlsx,.xlsm,.txt,.md,image/png,image/jpeg,image/webp"
                  onChange={(event) => setFile(event.target.files?.[0] || null)}
                />
              </Field>
              <Button
                type="button"
                disabled={!file || uploading}
                onClick={uploadFile}
                className="gap-2"
              >
                {uploading ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <Upload className="h-4 w-4" />
                )}
                {uploading ? t.extracting : t.uploadAndImport}
              </Button>
            </CardContent>
          </Card>
        </div>
      )}

      {job && step !== "upload" && (
        <div className="space-y-6">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Card>
              <CardContent className="p-4">
                <div className="text-2xl font-semibold">{total}</div>
                <div className="text-sm text-muted-foreground">
                  {t.extractedCandidates(total)}
                </div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <div className="text-2xl font-semibold">{needsReview}</div>
                <div className="text-sm text-muted-foreground">
                  {t.needsReviewCount(needsReview)}
                </div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <div className="text-2xl font-semibold">{ready}</div>
                <div className="text-sm text-muted-foreground">
                  {t.readyCount(ready)}
                </div>
              </CardContent>
            </Card>
            <Card>
              <CardContent className="p-4">
                <div className="text-2xl font-semibold">{approved}</div>
                <div className="text-sm text-muted-foreground">
                  {t.approvedCount(approved)}
                </div>
              </CardContent>
            </Card>
          </div>

          {step === "approve" && (
            <>
              <CandidateEditor
                candidates={candidates}
                products={products}
                warehouses={warehouses}
                suppliers={suppliers}
                currency={settings.currency}
                typeLabel={typeLabel}
                savingCandidateId={savingCandidateId}
                onPatch={patchCandidate}
                onBulkPatch={patchCandidates}
                onApprove={approveCandidate}
                onEvidence={setEvidenceCandidate}
                onCreateEntity={setCreateRequest}
                statusBadge={statusBadge}
                readOnly={job.status === "completed"}
              />

              <Card>
                <CardHeader>
                  <CardTitle className="text-base">{t.approve}</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                  {job.status === "completed" ? (
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                      <div className="flex items-center gap-2 text-sm text-emerald-700">
                        <Check className="h-4 w-4" />
                        {t.importCommitted}: {committedIds}
                      </div>
                      <Button
                        type="button"
                        onClick={() => router.push(`/operations?importId=${job.id}`)}
                      >
                        {t.viewImportedOperations}
                      </Button>
                    </div>
                  ) : (
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
                      <Button
                        type="button"
                        variant="outline"
                        disabled={approving || ready === 0}
                        onClick={approveAll}
                        className="gap-2"
                      >
                        {approving && <Loader2 className="h-4 w-4 animate-spin" />}
                        {t.approveReadyRows}
                      </Button>
                      <Button
                        type="button"
                        disabled={!allApproved || committing}
                        onClick={commitImport}
                        className="gap-2"
                      >
                        {committing && <Loader2 className="h-4 w-4 animate-spin" />}
                        {committing ? t.committing : t.commitImport}
                      </Button>
                    </div>
                  )}
                </CardContent>
              </Card>
            </>
          )}

          {candidates.length === 0 && (
            <div className="rounded-md border p-8 text-center text-sm text-muted-foreground">
              {t.noImportCandidates}
            </div>
          )}

        </div>
      )}

      <Sheet
        open={Boolean(evidenceCandidate)}
        onOpenChange={(open) => {
          if (!open) setEvidenceCandidate(null);
        }}
      >
        <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
          <SheetHeader>
            <SheetTitle>{t.sourceEvidence}</SheetTitle>
            <SheetDescription>
              {evidenceCandidate?.source.sheetName
                ? `${evidenceCandidate.source.sheetName} · ${evidenceCandidate.source.rowNumber ?? ""}`
                : evidenceCandidate?.source.kind}
            </SheetDescription>
          </SheetHeader>
          <div className="mt-4 space-y-4">
            <pre className="rounded-md bg-muted p-3 text-xs whitespace-pre-wrap">
              {stringifyEvidence(evidenceCandidate?.source)}
            </pre>
            <pre className="rounded-md bg-muted p-3 text-xs whitespace-pre-wrap">
              {stringifyEvidence(evidenceCandidate?.raw)}
            </pre>
          </div>
        </SheetContent>
      </Sheet>

      <CreateEntityDialog
        request={createRequest}
        categories={categories}
        stores={stores}
        settings={settings}
        onCreated={handleCreatedEntity}
        onOpenChange={(open) => {
          if (!open) setCreateRequest(null);
        }}
      />
    </div>
  );
}

function CandidateEditor({
  candidates,
  products,
  warehouses,
  suppliers,
  currency,
  typeLabel,
  savingCandidateId,
  onPatch,
  onBulkPatch,
  onApprove,
  onEvidence,
  onCreateEntity,
  statusBadge,
  readOnly,
}: {
  candidates: OperationImportCandidateRecord[];
  products: Product[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  currency: string;
  typeLabel: (type: OperationType) => string;
  savingCandidateId: string | null;
  onPatch: (
    candidate: OperationImportCandidateRecord,
    operation: OperationImportDraft
  ) => void;
  onBulkPatch: (updates: CandidateUpdate[]) => void;
  onApprove: (candidate: OperationImportCandidateRecord) => void;
  onEvidence: (candidate: OperationImportCandidateRecord) => void;
  onCreateEntity: (request: CreateEntityRequest) => void;
  statusBadge: (candidate: OperationImportCandidateRecord) => ReactNode;
  readOnly?: boolean;
}) {
  const { t } = useI18n();
  const [pageOffset, setPageOffset] = useState(0);
  const [bulkAction, setBulkAction] = useState<BulkAction | null>(null);
  const lastPageOffset =
    Math.max(0, Math.ceil(candidates.length / CANDIDATES_PER_PAGE) - 1) *
    CANDIDATES_PER_PAGE;
  const clampedPageOffset = Math.min(pageOffset, lastPageOffset);
  const pageCandidates = useMemo(
    () =>
      candidates.slice(
        clampedPageOffset,
        clampedPageOffset + CANDIDATES_PER_PAGE
      ),
    [candidates, clampedPageOffset]
  );
  const bulkCandidates = bulkAction
    ? candidates.filter((candidate) => {
        const operation = getOperation(candidate);
        return bulkAction.shouldInclude ? bulkAction.shouldInclude(operation) : true;
      })
    : [];
  const blankBulkCandidates = bulkAction
    ? bulkCandidates.filter((candidate) =>
        bulkAction.isBlank(getOperation(candidate))
      )
    : [];
  const bulkSaving = savingCandidateId === BULK_SAVING_ID;
  const runBulkAction = (replaceAll: boolean) => {
    if (!bulkAction) return;
    const source = replaceAll ? bulkCandidates : blankBulkCandidates;
    onBulkPatch(
      source.map((candidate) => ({
        candidateId: candidate.id,
        operation: bulkAction.buildOperation(getOperation(candidate)),
      }))
    );
    setBulkAction(null);
  };

  return (
    <div className="space-y-3">
      <div className="text-xs text-muted-foreground">
        {t.workspaceCurrency(currency)}
      </div>
      <div className="overflow-x-auto rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="min-w-32">{t.operationType}</TableHead>
              <TableHead className="min-w-36">{t.operationDate}</TableHead>
              <TableHead className="min-w-44">{t.supplier}</TableHead>
              <TableHead className="min-w-56">{t.product}</TableHead>
              <TableHead className="min-w-44">{t.warehouse}</TableHead>
              <TableHead className="min-w-36">{t.quantity}</TableHead>
              <TableHead className="min-w-40">{t.price}</TableHead>
              <TableHead className="min-w-48">{t.validation}</TableHead>
              <TableHead className="w-32 text-right">{t.actions}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {pageCandidates.map((candidate) => {
              const operation = getOperation(candidate);
              const firstItem = getFirstItem(operation);
              const errors = candidate.validation_errors || [];
              const saving =
                savingCandidateId === candidate.id ||
                savingCandidateId === BULK_SAVING_ID;
              const rowDisabled = readOnly || saving;

              return (
                <TableRow key={candidate.id}>
                <TableCell>
                  <div className="flex items-center gap-1">
                    <Select
                      value={operation.type || "__none"}
                      disabled={rowDisabled}
                      onValueChange={(value) =>
                        onPatch(candidate, {
                          ...operation,
                          type:
                            value === "__none"
                              ? undefined
                              : (value as OperationType),
                        })
                      }
                    >
                      <SelectTrigger className="h-9">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="__none">-</SelectItem>
                        {OPERATION_TYPES.map((type) => (
                          <SelectItem key={type} value={type}>
                            {typeLabel(type)}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <ApplyValueButton
                      disabled={rowDisabled || !operation.type}
                      onClick={() => {
                        if (!operation.type) return;
                        const sourceType = operation.type;
                        setBulkAction({
                          fieldLabel: t.operationType,
                          valueLabel: typeLabel(sourceType),
                          buildOperation: (target) => ({
                            ...target,
                            type: sourceType,
                          }),
                          isBlank: (target) => !target.type,
                        });
                      }}
                    />
                  </div>
                </TableCell>
                <TableCell>
                  <div className="flex items-center gap-1">
                    <Input
                      type="date"
                      value={operationImportDateInputValue(operation.operationDate)}
                      disabled={rowDisabled}
                      onChange={(event) =>
                        onPatch(candidate, {
                          ...operation,
                          operationDate: event.target.value || undefined,
                        })
                      }
                      className="h-9"
                    />
                    <ApplyValueButton
                      disabled={
                        rowDisabled ||
                        !operationImportDateInputValue(operation.operationDate)
                      }
                      onClick={() => {
                        const sourceDate = operationImportDateInputValue(
                          operation.operationDate
                        );
                        if (!sourceDate) return;
                        setBulkAction({
                          fieldLabel: t.operationDate,
                          valueLabel: sourceDate,
                          buildOperation: (target) => ({
                            ...target,
                            operationDate: sourceDate,
                          }),
                          isBlank: (target) => !target.operationDate,
                        });
                      }}
                    />
                  </div>
                </TableCell>
                <TableCell>
                  <EntityResolver
                    value={operation.supplierId}
                    rawName={operation.supplierName}
                    items={suppliers}
                    disabled={rowDisabled}
                    onValueChange={(value) =>
                      onPatch(candidate, {
                        ...operation,
                        supplierId: value || undefined,
                        createSupplier: false,
                      })
                    }
                    onCreate={() =>
                      onCreateEntity({
                        kind: "supplier",
                        candidate,
                        operation: {
                          ...operation,
                          supplierId: undefined,
                          createSupplier: true,
                        },
                        rawName: operation.supplierName,
                      })
                    }
                    onApply={() => {
                      if (!operation.supplierId) return;
                      const supplierName = optionName(
                        suppliers,
                        operation.supplierId,
                        operation.supplierName
                      );
                      setBulkAction({
                        fieldLabel: t.supplier,
                        valueLabel: supplierName,
                        buildOperation: (target) => ({
                          ...target,
                          supplierId: operation.supplierId,
                          supplierName,
                          createSupplier: false,
                        }),
                        isBlank: (target) =>
                          !target.supplierId && !target.supplierName,
                      });
                    }}
                  />
                </TableCell>
                <TableCell>
                  <EntityResolver
                    value={firstItem.productId}
                    rawName={firstItem.productName || firstItem.skuCode}
                    items={products}
                    disabled={rowDisabled}
                    getItemLabel={(product) =>
                      product.skuCode
                        ? `${product.name} (${product.skuCode})`
                        : product.name
                    }
                    onValueChange={(value) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          productId: value || undefined,
                          createProduct: false,
                        })
                      )
                    }
                    onCreate={() =>
                      onCreateEntity({
                        kind: "product",
                        candidate,
                        operation: replaceFirstItem(operation, {
                          productId: undefined,
                          createProduct: true,
                        }),
                        item: firstItem,
                        rawName: firstItem.productName || firstItem.skuCode,
                      })
                    }
                    onApply={() => {
                      if (!firstItem.productId) return;
                      const product = products.find(
                        (item) => item.id === firstItem.productId
                      );
                      const productName =
                        product?.name ||
                        firstItem.productName ||
                        firstItem.skuCode ||
                        "";
                      setBulkAction({
                        fieldLabel: t.product,
                        valueLabel: productName,
                        buildOperation: (target) =>
                          replaceFirstItem(target, {
                            productId: firstItem.productId,
                            productName,
                            skuCode: product?.skuCode || firstItem.skuCode,
                            storeId: product?.storeId || firstItem.storeId,
                            createProduct: false,
                          }),
                        isBlank: (target) => {
                          const item = getFirstItem(target);
                          return !item.productId && !item.productName && !item.skuCode;
                        },
                        shouldInclude: (target) => target.type !== "payment",
                      });
                    }}
                  />
                </TableCell>
                <TableCell>
                  <EntityResolver
                    value={firstItem.warehouseId}
                    rawName={firstItem.warehouseName}
                    items={warehouses}
                    disabled={rowDisabled}
                    onValueChange={(value) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          warehouseId: value || undefined,
                          createWarehouse: false,
                        })
                      )
                    }
                    onCreate={() =>
                      onCreateEntity({
                        kind: "warehouse",
                        candidate,
                        operation: replaceFirstItem(operation, {
                          warehouseId: undefined,
                          createWarehouse: true,
                        }),
                        item: firstItem,
                        rawName: firstItem.warehouseName,
                      })
                    }
                    onApply={() => {
                      if (!firstItem.warehouseId) return;
                      const warehouseName = optionName(
                        warehouses,
                        firstItem.warehouseId,
                        firstItem.warehouseName
                      );
                      setBulkAction({
                        fieldLabel: t.warehouse,
                        valueLabel: warehouseName,
                        buildOperation: (target) =>
                          replaceFirstItem(target, {
                            warehouseId: firstItem.warehouseId,
                            warehouseName,
                            createWarehouse: false,
                          }),
                        isBlank: (target) => {
                          const item = getFirstItem(target);
                          return !item.warehouseId && !item.warehouseName;
                        },
                        shouldInclude: (target) => target.type !== "payment",
                      });
                    }}
                  />
                </TableCell>
                <TableCell className="min-w-36">
                  <Input
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={firstItem.quantity ?? ""}
                    disabled={rowDisabled}
                    onChange={(event) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          quantity: event.target.value
                            ? Number(event.target.value)
                            : undefined,
                        })
                      )
                    }
                    className="h-9 min-w-28 tabular-nums"
                  />
                </TableCell>
                <TableCell className="min-w-40">
                  <div className="relative">
                    <Input
                      type="number"
                      inputMode="decimal"
                      step="any"
                      value={
                        operation.type === "payment"
                          ? operation.paymentAmount ?? ""
                          : firstItem.unitPrice ?? ""
                      }
                      disabled={rowDisabled}
                      onChange={(event) => {
                        const value = event.target.value
                          ? Number(event.target.value)
                          : undefined;
                        onPatch(
                          candidate,
                          operation.type === "payment"
                            ? { ...operation, paymentAmount: value }
                            : replaceFirstItem(operation, { unitPrice: value })
                        );
                      }}
                      className="h-9 min-w-32 pr-14 tabular-nums"
                    />
                    <span className="pointer-events-none absolute inset-y-0 right-2 flex items-center text-xs text-muted-foreground">
                      {currency}
                    </span>
                  </div>
                </TableCell>
                <TableCell>
                  <div className="space-y-2">
                    {statusBadge(candidate)}
                    {errors.slice(0, 3).map((error, index) => (
                      <div
                        key={`${error.field}-${index}`}
                        className="text-xs text-destructive"
                      >
                        {t.operationImportValidationField(error.field)}:{" "}
                        {t.operationImportValidationMessage(error.message)}
                      </div>
                    ))}
                  </div>
                </TableCell>
                <TableCell className="text-right">
                  <div className="flex justify-end gap-1">
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => onEvidence(candidate)}
                      aria-label={t.evidence}
                    >
                      <Eye className="h-4 w-4" />
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={
                        readOnly ||
                        saving ||
                        errors.length > 0 ||
                        candidate.status === "approved" ||
                        candidate.status === "committed"
                      }
                      onClick={() => onApprove(candidate)}
                    >
                      {saving ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        t.approveRow
                      )}
                    </Button>
                  </div>
                </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>
      {candidates.length > CANDIDATES_PER_PAGE && (
        <Pagination
          offset={clampedPageOffset}
          limit={CANDIDATES_PER_PAGE}
          total={candidates.length}
          onPageChange={setPageOffset}
        />
      )}
      <Dialog
        open={Boolean(bulkAction)}
        onOpenChange={(open) => {
          if (!open) setBulkAction(null);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{t.applyToAllRows}</DialogTitle>
          </DialogHeader>
          {bulkAction && (
            <div className="space-y-4">
              <p className="text-sm text-muted-foreground">
                {t.applyToAllDescription(
                  bulkAction.fieldLabel,
                  bulkAction.valueLabel
                )}
              </p>
              <div className="flex justify-end gap-2">
                <Button
                  type="button"
                  variant="outline"
                  disabled={blankBulkCandidates.length === 0 || bulkSaving}
                  onClick={() => runBulkAction(false)}
                >
                  {t.fillBlanks}
                </Button>
                <Button
                  type="button"
                  disabled={bulkCandidates.length === 0 || bulkSaving}
                  onClick={() => runBulkAction(true)}
                >
                  {bulkSaving ? t.saving : t.replaceAll}
                </Button>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function ApplyValueButton({
  disabled,
  onClick,
}: {
  disabled?: boolean;
  onClick: () => void;
}) {
  const { t } = useI18n();
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      disabled={disabled}
      onClick={onClick}
      aria-label={t.applyValueToRows}
      title={t.applyValueToRows}
      className="h-9 w-9 shrink-0"
    >
      <Copy className="h-4 w-4" />
    </Button>
  );
}

function EntityResolver<T extends { id: string; name: string }>({
  value,
  rawName,
  items,
  disabled,
  getItemLabel,
  onValueChange,
  onCreate,
  onApply,
}: {
  value?: string;
  rawName?: string;
  items: T[];
  disabled?: boolean;
  getItemLabel?: (item: T) => string;
  onValueChange: (value: string) => void;
  onCreate: () => void;
  onApply: () => void;
}) {
  const { t } = useI18n();
  const selectValue = value || "__none";
  const createSourceName = !value ? rawName : undefined;

  return (
    <div className="flex items-center gap-1">
      <Select
        value={selectValue}
        disabled={disabled}
        onValueChange={(next) => {
          if (next === "__none") {
            onValueChange("");
          } else if (next === "__create") {
            onCreate();
          } else {
            onValueChange(next);
          }
        }}
      >
        <SelectTrigger className="h-9">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="__none">-</SelectItem>
          {createSourceName && (
            <SelectItem value="__create">
              {t.createFromSource(createSourceName)}
            </SelectItem>
          )}
          {items.map((item) => (
            <SelectItem key={item.id} value={item.id}>
              {getItemLabel ? getItemLabel(item) : item.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <ApplyValueButton disabled={disabled || !value} onClick={onApply} />
    </div>
  );
}

function CreateEntityDialog({
  request,
  categories,
  stores,
  settings,
  onCreated,
  onOpenChange,
}: {
  request: CreateEntityRequest | null;
  categories: Category[];
  stores: Store[];
  settings: {
    categoryRequired: boolean;
    defaultCategoryId: string | null;
    storeRequired: boolean;
    defaultStoreId: string | null;
  };
  onCreated: (item: Product | Supplier | Warehouse) => void | Promise<void>;
  onOpenChange: (open: boolean) => void;
}) {
  const { t } = useI18n();
  const [name, setName] = useState("");
  const [skuCode, setSkuCode] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [storeId, setStoreId] = useState("");
  const [address, setAddress] = useState("");
  const [contactInfo, setContactInfo] = useState("");
  const [description, setDescription] = useState("");
  const [purpose, setPurpose] = useState<WarehousePurpose | "">("");
  const [isImportDefault, setIsImportDefault] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!request) return;
    setName(request.rawName || "");
    setSkuCode(request.kind === "product" ? request.item?.skuCode || "" : "");
    setCategoryId(settings.defaultCategoryId || "");
    setStoreId(request.item?.storeId || settings.defaultStoreId || "");
    setAddress("");
    setContactInfo("");
    setDescription("");
    setPurpose("");
    setIsImportDefault(false);
    setError("");
  }, [request, settings.defaultCategoryId, settings.defaultStoreId]);

  if (!request) return null;

  const title =
    request.kind === "supplier"
      ? t.newSupplier
      : request.kind === "warehouse"
        ? t.newWarehouse
        : t.newProduct;
  const missingRequiredProductField =
    request.kind === "product" &&
    ((settings.categoryRequired && !categoryId) ||
      (settings.storeRequired && !storeId));

  const save = async () => {
    if (!name.trim() || missingRequiredProductField) return;
    setSaving(true);
    setError("");
    try {
      const url =
        request.kind === "supplier"
          ? "/api/suppliers"
          : request.kind === "warehouse"
            ? "/api/warehouses"
            : "/api/products";
      const body =
        request.kind === "supplier"
          ? {
              name: name.trim(),
              address: address.trim() || undefined,
              contactInfo: contactInfo.trim() || undefined,
              isImportDefault,
            }
          : request.kind === "warehouse"
            ? {
                name: name.trim(),
                description: description.trim() || undefined,
                purpose: purpose || undefined,
                isImportDefault,
              }
            : {
                name: name.trim(),
                skuCode: skuCode.trim() || undefined,
                categoryId: categoryId || undefined,
                storeId: storeId || undefined,
              };

      const res = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) {
        if (request.kind === "product" && res.status === 409) {
          setError(
            data.field === "sku" ? t.duplicateSkuError : t.duplicateNameError
          );
        } else {
          setError(
            res.status === 409
              ? t.duplicateError
              : data.error || t.unexpectedError
          );
        }
        return;
      }

      await onCreated(data as Product | Supplier | Warehouse);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={Boolean(request)} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="space-y-4">
          <Field>
            <FieldLabel>{t.name}</FieldLabel>
            <Input
              value={name}
              onChange={(event) => setName(event.target.value)}
              autoFocus
            />
          </Field>

          {request.kind === "product" && (
            <>
              <Field>
                <FieldLabel>{t.productSku}</FieldLabel>
                <Input
                  value={skuCode}
                  onChange={(event) => setSkuCode(event.target.value)}
                />
              </Field>
              <Field>
                <FieldLabel>
                  {t.productCategory}
                  {settings.categoryRequired && (
                    <span className="ml-1 text-destructive">*</span>
                  )}
                </FieldLabel>
                <Select
                  value={categoryId}
                  onValueChange={(value) =>
                    setCategoryId(value === "none" ? "" : value)
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.purposeNone} />
                  </SelectTrigger>
                  <SelectContent>
                    {!settings.categoryRequired && (
                      <SelectItem value="none">{t.purposeNone}</SelectItem>
                    )}
                    {categories.map((category) => (
                      <SelectItem key={category.id} value={category.id}>
                        {category.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field>
                <FieldLabel>
                  {t.productStore}
                  {settings.storeRequired && (
                    <span className="ml-1 text-destructive">*</span>
                  )}
                </FieldLabel>
                <Select
                  value={storeId}
                  onValueChange={(value) =>
                    setStoreId(value === "none" ? "" : value)
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.purposeNone} />
                  </SelectTrigger>
                  <SelectContent>
                    {!settings.storeRequired && (
                      <SelectItem value="none">{t.purposeNone}</SelectItem>
                    )}
                    {stores.map((store) => (
                      <SelectItem key={store.id} value={store.id}>
                        {store.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
            </>
          )}

          {request.kind === "supplier" && (
            <>
              <Field>
                <FieldLabel>{t.supplierAddress}</FieldLabel>
                <Input
                  value={address}
                  onChange={(event) => setAddress(event.target.value)}
                />
              </Field>
              <Field>
                <FieldLabel>{t.supplierContactInfo}</FieldLabel>
                <Textarea
                  value={contactInfo}
                  onChange={(event) => setContactInfo(event.target.value)}
                  rows={2}
                />
              </Field>
              <ImportDefaultField
                checked={isImportDefault}
                entityLabel={t.supplierEntity}
                onCheckedChange={setIsImportDefault}
              />
            </>
          )}

          {request.kind === "warehouse" && (
            <>
              <Field>
                <FieldLabel>{t.warehouseDescription}</FieldLabel>
                <Textarea
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  rows={2}
                />
              </Field>
              <Field>
                <FieldLabel>{t.warehousePurpose}</FieldLabel>
                <Select
                  value={purpose || "none"}
                  onValueChange={(value) =>
                    setPurpose(value === "none" ? "" : (value as WarehousePurpose))
                  }
                >
                  <SelectTrigger>
                    <SelectValue placeholder={t.purposeNone} />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="none">{t.purposeNone}</SelectItem>
                    <SelectItem value="storage">{t.purposeStorage}</SelectItem>
                    <SelectItem value="sales">{t.purposeSales}</SelectItem>
                    <SelectItem value="production">{t.purposeProduction}</SelectItem>
                  </SelectContent>
                </Select>
              </Field>
              <ImportDefaultField
                checked={isImportDefault}
                entityLabel={t.warehouseEntity}
                onCheckedChange={setIsImportDefault}
              />
            </>
          )}

          {error && <p className="text-sm text-destructive">{error}</p>}
          <div className="flex justify-end gap-2">
            <Button
              type="button"
              variant="outline"
              onClick={() => onOpenChange(false)}
              disabled={saving}
            >
              {t.cancel}
            </Button>
            <Button
              type="button"
              onClick={save}
              disabled={saving || !name.trim() || missingRequiredProductField}
            >
              {saving ? t.saving : t.save}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}
