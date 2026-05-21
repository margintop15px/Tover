"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  AlertTriangle,
  Check,
  Eye,
  FileUp,
  Loader2,
  Upload,
} from "lucide-react";
import { useI18n } from "@/i18n/context";
import Pagination from "@/components/Pagination";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type {
  OperationImportCandidateRecord,
  OperationImportDraft,
  OperationImportItemDraft,
  OperationImportRecord,
} from "@/lib/operation-imports/types";
import { operationImportDateInputValue } from "@/lib/operation-imports/date";
import type {
  OperationType,
  Product,
  Supplier,
  Warehouse,
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

export default function OperationImportPage() {
  const { t } = useI18n();
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
    ]).then(([productData, warehouseData, supplierData]) => {
      setProducts(productData.items || []);
      setWarehouses(warehouseData.items || []);
      setSuppliers(supplierData.items || []);
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
    if (!job) return;
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
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      await refreshJob(job.id);
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
                typeLabel={typeLabel}
                savingCandidateId={savingCandidateId}
                onPatch={patchCandidate}
                onApprove={approveCandidate}
                onEvidence={setEvidenceCandidate}
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
    </div>
  );
}

function CandidateEditor({
  candidates,
  products,
  warehouses,
  suppliers,
  typeLabel,
  savingCandidateId,
  onPatch,
  onApprove,
  onEvidence,
  statusBadge,
  readOnly,
}: {
  candidates: OperationImportCandidateRecord[];
  products: Product[];
  warehouses: Warehouse[];
  suppliers: Supplier[];
  typeLabel: (type: OperationType) => string;
  savingCandidateId: string | null;
  onPatch: (
    candidate: OperationImportCandidateRecord,
    operation: OperationImportDraft
  ) => void;
  onApprove: (candidate: OperationImportCandidateRecord) => void;
  onEvidence: (candidate: OperationImportCandidateRecord) => void;
  statusBadge: (candidate: OperationImportCandidateRecord) => ReactNode;
  readOnly?: boolean;
}) {
  const { t } = useI18n();
  const [pageOffset, setPageOffset] = useState(0);
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

  return (
    <div className="space-y-3">
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
              const saving = savingCandidateId === candidate.id;

              return (
                <TableRow key={candidate.id}>
                <TableCell>
                  <Select
                    value={operation.type || "__none"}
                    disabled={readOnly}
                    onValueChange={(value) =>
                      onPatch(candidate, {
                        ...operation,
                        type:
                          value === "__none" ? undefined : (value as OperationType),
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
                </TableCell>
                <TableCell>
                  <Input
                    type="date"
                    value={operationImportDateInputValue(operation.operationDate)}
                    disabled={readOnly}
                    onChange={(event) =>
                      onPatch(candidate, {
                        ...operation,
                        operationDate: event.target.value || undefined,
                      })
                    }
                    className="h-9"
                  />
                </TableCell>
                <TableCell>
                  <EntitySelect
                    value={operation.supplierId}
                    createValue={operation.createSupplier}
                    rawName={operation.supplierName}
                    items={suppliers}
                    disabled={readOnly}
                    onValueChange={(value) =>
                      onPatch(candidate, {
                        ...operation,
                        supplierId: value || undefined,
                        createSupplier: false,
                      })
                    }
                    onCreateChange={(checked) =>
                      onPatch(candidate, {
                        ...operation,
                        supplierId: undefined,
                        createSupplier: checked,
                      })
                    }
                    onNameChange={(name) =>
                      onPatch(candidate, { ...operation, supplierName: name })
                    }
                  />
                </TableCell>
                <TableCell>
                  <EntitySelect
                    value={firstItem.productId}
                    createValue={firstItem.createProduct}
                    rawName={firstItem.productName || firstItem.skuCode}
                    items={products}
                    disabled={readOnly}
                    onValueChange={(value) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          productId: value || undefined,
                          createProduct: false,
                        })
                      )
                    }
                    onCreateChange={(checked) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          productId: undefined,
                          createProduct: checked,
                        })
                      )
                    }
                    onNameChange={(name) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, { productName: name })
                      )
                    }
                  />
                </TableCell>
                <TableCell>
                  <EntitySelect
                    value={firstItem.warehouseId}
                    createValue={firstItem.createWarehouse}
                    rawName={firstItem.warehouseName}
                    items={warehouses}
                    disabled={readOnly}
                    onValueChange={(value) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          warehouseId: value || undefined,
                          createWarehouse: false,
                        })
                      )
                    }
                    onCreateChange={(checked) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          warehouseId: undefined,
                          createWarehouse: checked,
                        })
                      )
                    }
                    onNameChange={(name) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, { warehouseName: name })
                      )
                    }
                  />
                </TableCell>
                <TableCell className="min-w-36">
                  <Input
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={firstItem.quantity ?? ""}
                    disabled={readOnly}
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
                  <Input
                    type="number"
                    inputMode="decimal"
                    step="any"
                    value={firstItem.unitPrice ?? ""}
                    disabled={readOnly}
                    onChange={(event) =>
                      onPatch(
                        candidate,
                        replaceFirstItem(operation, {
                          unitPrice: event.target.value
                            ? Number(event.target.value)
                            : undefined,
                        })
                      )
                    }
                    className="h-9 min-w-32 tabular-nums"
                  />
                </TableCell>
                <TableCell>
                  <div className="space-y-2">
                    {statusBadge(candidate)}
                    {errors.slice(0, 3).map((error, index) => (
                      <div
                        key={`${error.field}-${index}`}
                        className="text-xs text-destructive"
                      >
                        {error.message}
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
    </div>
  );
}

function EntitySelect<T extends { id: string; name: string }>({
  value,
  createValue,
  rawName,
  items,
  disabled,
  onValueChange,
  onCreateChange,
  onNameChange,
}: {
  value?: string;
  createValue?: boolean;
  rawName?: string;
  items: T[];
  disabled?: boolean;
  onValueChange: (value: string) => void;
  onCreateChange: (checked: boolean) => void;
  onNameChange: (value: string) => void;
}) {
  const { t } = useI18n();
  const selectValue = createValue ? "__create" : value || "__none";

  return (
    <div className="space-y-2">
      <Select
        value={selectValue}
        disabled={disabled}
        onValueChange={(next) => {
          if (next === "__none") {
            onValueChange("");
          } else if (next === "__create") {
            onCreateChange(true);
          } else {
            onValueChange(next);
          }
        }}
      >
        <SelectTrigger className="h-9">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="__none">{t.mapExisting}</SelectItem>
          {rawName && (
            <SelectItem value="__create">
              {t.createMissing}: {rawName}
            </SelectItem>
          )}
          {items.map((item) => (
            <SelectItem key={item.id} value={item.id}>
              {item.name}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {(createValue || !value) && (
        <div className="flex items-center gap-2">
          <Input
            value={rawName || ""}
            disabled={disabled}
            onChange={(event) => onNameChange(event.target.value)}
            className="h-8 text-xs"
          />
          <Label className="flex items-center gap-2 text-xs">
            <Switch
              checked={Boolean(createValue)}
              disabled={disabled}
              onCheckedChange={onCreateChange}
            />
            {t.create}
          </Label>
        </div>
      )}
    </div>
  );
}
