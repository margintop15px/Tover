"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  AlertTriangle,
  Check,
  ChevronLeft,
  ChevronRight,
  Eye,
  Loader2,
  PackagePlus,
  Undo2,
  Warehouse,
} from "lucide-react";
import { useI18n } from "@/i18n/context";
import Pagination from "@/components/Pagination";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
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
  SheetFooter,
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
import type {
  MarketplaceCandidateRow,
  MarketplaceCandidateStatus,
  OzonCandidateOperation,
  OzonCandidateSourceType,
  OzonCandidateSupportStatus,
} from "@/lib/ozon/candidates";
import type { OperationType, Product, Warehouse as WarehouseType } from "@/types/inventory";

interface CandidateListResponse {
  page: { limit: number; offset: number; total: number };
  summary: {
    total: number;
    needsMapping: number;
    ready: number;
    approved: number;
    committing: number;
    ignored: number;
    committed: number;
  };
  items: MarketplaceCandidateRow[];
}

type MappingState = "all" | "mapped" | "missing";
type OperationFilter = OperationType | "all";
type SourceFilter = OzonCandidateSourceType | "all";
type SupportFilter = OzonCandidateSupportStatus | "all";

const PAGE_SIZE = 50;
const OPERATION_FILTERS: OperationType[] = [
  "sale",
  "return",
  "write_off",
  "transfer",
  "defect",
  "inventory_adjustment",
  "purchase",
  "payment",
  "production",
];
const SOURCE_FILTERS: OzonCandidateSourceType[] = [
  "posting",
  "return",
  "legal_entity_sale",
  "removal",
  "supply",
  "stock_reconciliation",
  "discounted_product",
  "finance",
  "report",
];
const SUPPORT_FILTERS: OzonCandidateSupportStatus[] = [
  "commit_candidate",
  "blocked",
  "reporting_only",
];

function candidateOperation(candidate: MarketplaceCandidateRow) {
  return (candidate.normalized_operation ||
    candidate.operation ||
    {}) as OzonCandidateOperation;
}

function candidateItems(candidate: MarketplaceCandidateRow) {
  return candidateOperation(candidate).items || [];
}

function firstCandidateItem(candidate: MarketplaceCandidateRow) {
  return candidateItems(candidate)[0] || {};
}

function safeBackHref(value: string | null) {
  if (!value) return "/settings?tab=integrations";
  if (value === "/operations" || value.startsWith("/operations?")) return value;
  if (value === "/operations/marketplaces") return value;
  if (value === "/settings?tab=integrations") return value;
  return "/settings?tab=integrations";
}

export default function OzonCandidateReviewPage() {
  const { t } = useI18n();
  const searchParams = useSearchParams();
  const backHref = safeBackHref(searchParams.get("returnTo"));
  const [items, setItems] = useState<MarketplaceCandidateRow[]>([]);
  const [summary, setSummary] =
    useState<CandidateListResponse["summary"] | null>(null);
  const [page, setPage] = useState({ limit: PAGE_SIZE, offset: 0, total: 0 });
  const [status, setStatus] = useState<MarketplaceCandidateStatus | "all">("all");
  const [operationType, setOperationType] = useState<OperationFilter>("all");
  const [sourceType, setSourceType] = useState<SourceFilter>("all");
  const [supportStatus, setSupportStatus] = useState<SupportFilter>("all");
  const [mappingState, setMappingState] = useState<MappingState>("all");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);
  const [selected, setSelected] = useState<MarketplaceCandidateRow | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const selectedIndex = useMemo(
    () => (selected ? items.findIndex((item) => item.id === selected.id) : -1),
    [items, selected]
  );
  const selectedPosition =
    selectedIndex >= 0 ? t.ozonCandidatePosition(selectedIndex + 1, items.length) : null;

  const fetchReferenceData = useCallback(async () => {
    const [productRes, warehouseRes] = await Promise.all([
      fetch("/api/products?limit=5000"),
      fetch("/api/warehouses?limit=1000"),
    ]);
    const [productData, warehouseData] = await Promise.all([
      productRes.json(),
      warehouseRes.json(),
    ]);
    setProducts(productData.items || []);
    setWarehouses(warehouseData.items || []);
  }, []);

  const fetchCandidates = useCallback(
    async (nextOffset = page.offset) => {
      setLoading(true);
      setError("");
      try {
        const params = new URLSearchParams({
          limit: String(PAGE_SIZE),
          offset: String(nextOffset),
          status,
          operationType,
          sourceType,
          supportStatus,
          mappingState,
        });
        if (from) params.set("from", from);
        if (to) params.set("to", to);

        const res = await fetch(`/api/integrations/ozon/candidates?${params}`);
        const data = (await res.json()) as CandidateListResponse & {
          error?: string;
        };
        if (!res.ok) throw new Error(data.error || t.unexpectedError);

        setItems(data.items || []);
        setSummary(data.summary);
        setPage(data.page);
        if (selected) {
          const refreshed = (data.items || []).find(
            (candidate) => candidate.id === selected.id
          );
          if (refreshed) setSelected(refreshed);
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : t.unexpectedError);
      } finally {
        setLoading(false);
      }
    },
    [
      from,
      mappingState,
      operationType,
      page.offset,
      selected,
      sourceType,
      status,
      supportStatus,
      t.unexpectedError,
      to,
    ]
  );

  useEffect(() => {
    fetchReferenceData();
  }, [fetchReferenceData]);

  useEffect(() => {
    fetchCandidates(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, operationType, sourceType, supportStatus, mappingState, from, to]);

  const runAction = async (
    key: string,
    action: () => Promise<MarketplaceCandidateRow | null | undefined>,
    message?: string
  ) => {
    setBusy(key);
    setError("");
    setSuccess("");
    try {
      const updated = await action();
      if (updated && selected?.id === updated.id) setSelected(updated);
      if (message) setSuccess(message);
      await fetchCandidates();
      await fetchReferenceData();
    } catch (err) {
      setError(err instanceof Error ? err.message : t.unexpectedError);
    } finally {
      setBusy(null);
    }
  };

  const patchCandidate = async (
    candidate: MarketplaceCandidateRow,
    body: Record<string, unknown>
  ) => {
    const res = await fetch(`/api/integrations/ozon/candidates/${candidate.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || t.unexpectedError);
    return data.candidate as MarketplaceCandidateRow;
  };

  const postCandidateAction = async (
    candidate: MarketplaceCandidateRow,
    path: string,
    body: Record<string, unknown> = {}
  ) => {
    const res = await fetch(
      `/api/integrations/ozon/candidates/${candidate.id}/${path}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      }
    );
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || t.unexpectedError);
    return data.candidate as MarketplaceCandidateRow;
  };

  const approveReady = async () => {
    await runAction("approve-ready", async () => {
      const res = await fetch("/api/integrations/ozon/candidates/approve-ready", {
        method: "POST",
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      setSuccess(t.ozonCandidatesApproved(data.approved, data.blocked));
      return null;
    });
  };

  const commitApproved = async (candidateIds?: string[]) => {
    await runAction(candidateIds?.[0] || "commit-approved", async () => {
      const res = await fetch("/api/integrations/ozon/candidates/commit", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(candidateIds ? { candidateIds } : {}),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || t.unexpectedError);
      setSuccess(
        t.ozonCandidatesCommitted(data.committedCount || 0, data.failedCount || 0)
      );
      return null;
    });
  };

  const summaryCards = useMemo(
    () => [
      { label: t.ozonCandidateNeedsMapping, value: summary?.needsMapping || 0 },
      { label: t.ready, value: summary?.ready || 0 },
      { label: t.ozonCandidateApproved, value: summary?.approved || 0 },
      { label: t.ozonCandidateCommitting, value: summary?.committing || 0 },
      { label: t.ozonCandidateIgnored, value: summary?.ignored || 0 },
      { label: t.ozonCandidateCommitted, value: summary?.committed || 0 },
    ],
    [summary, t]
  );

  return (
    <div className="p-6">
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold">{t.ozonCandidatesTitle}</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t.ozonCandidatesSubtitle}
          </p>
        </div>
        <Link href={backHref}>
          <Button variant="outline">{t.back}</Button>
        </Link>
      </div>

      {error && (
        <div className="mb-4 flex items-center gap-2 rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
          <AlertTriangle className="h-4 w-4" />
          {error}
        </div>
      )}
      {success && (
        <div className="mb-4 flex items-center gap-2 rounded-md border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-700">
          <Check className="h-4 w-4" />
          {success}
        </div>
      )}

      <div className="mb-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
        {summaryCards.map((card) => (
          <Card key={card.label}>
            <CardContent className="p-4">
              <div className="text-2xl font-semibold">{card.value}</div>
              <div className="text-sm text-muted-foreground">{card.label}</div>
            </CardContent>
          </Card>
        ))}
      </div>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="text-base">{t.filters}</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4 sm:grid-cols-2 lg:grid-cols-7">
          <Field>
            <FieldLabel>{t.status}</FieldLabel>
            <Select
              value={status}
              onValueChange={(value) =>
                setStatus(value as MarketplaceCandidateStatus | "all")
              }
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t.allStatuses}</SelectItem>
                <SelectItem value="needs_mapping">
                  {t.ozonCandidateNeedsMapping}
                </SelectItem>
                <SelectItem value="ready">{t.ready}</SelectItem>
                <SelectItem value="approved">{t.ozonCandidateApproved}</SelectItem>
                <SelectItem value="committing">
                  {t.ozonCandidateCommitting}
                </SelectItem>
                <SelectItem value="ignored">{t.ozonCandidateIgnored}</SelectItem>
                <SelectItem value="committed">{t.ozonCandidateCommitted}</SelectItem>
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>{t.operationType}</FieldLabel>
            <Select
              value={operationType}
              onValueChange={(value) => setOperationType(value as OperationFilter)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t.allTypes}</SelectItem>
                {OPERATION_FILTERS.map((type) => (
                  <SelectItem key={type} value={type}>
                    {operationTypeLabel(type, t)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>{t.ozonSourceType}</FieldLabel>
            <Select
              value={sourceType}
              onValueChange={(value) => setSourceType(value as SourceFilter)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t.allTypes}</SelectItem>
                {SOURCE_FILTERS.map((type) => (
                  <SelectItem key={type} value={type}>
                    {t.ozonSourceLabel(type)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>{t.ozonEvidence}</FieldLabel>
            <Select
              value={supportStatus}
              onValueChange={(value) => setSupportStatus(value as SupportFilter)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t.allStatuses}</SelectItem>
                {SUPPORT_FILTERS.map((type) => (
                  <SelectItem key={type} value={type}>
                    {t.ozonSupportLabel(type)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>{t.ozonMappingState}</FieldLabel>
            <Select
              value={mappingState}
              onValueChange={(value) => setMappingState(value as MappingState)}
            >
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">{t.ozonMappingAll}</SelectItem>
                <SelectItem value="mapped">{t.ozonMappingMapped}</SelectItem>
                <SelectItem value="missing">{t.ozonMappingMissing}</SelectItem>
              </SelectContent>
            </Select>
          </Field>
          <Field>
            <FieldLabel>{t.from}</FieldLabel>
            <Input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          </Field>
          <Field>
            <FieldLabel>{t.to}</FieldLabel>
            <Input type="date" value={to} onChange={(event) => setTo(event.target.value)} />
          </Field>
        </CardContent>
      </Card>

      <div className="mb-4 flex flex-wrap gap-2">
        <Button
          variant="outline"
          disabled={busy === "approve-ready"}
          onClick={approveReady}
        >
          {busy === "approve-ready" && <Loader2 className="h-4 w-4 animate-spin" />}
          {t.ozonApproveReady}
        </Button>
        <Button
          disabled={busy === "commit-approved"}
          onClick={() => commitApproved()}
        >
          {busy === "commit-approved" && <Loader2 className="h-4 w-4 animate-spin" />}
          {t.ozonCommitApproved}
        </Button>
      </div>

      <div className="overflow-x-auto rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t.operationDate}</TableHead>
              <TableHead>{t.operationType}</TableHead>
              <TableHead>{t.ozonSourceType}</TableHead>
              <TableHead>{t.ozonEvidence}</TableHead>
              <TableHead>{t.status}</TableHead>
              <TableHead>{t.ozonSourceEvent}</TableHead>
              <TableHead>{t.ozonItems}</TableHead>
              <TableHead>{t.validation}</TableHead>
              <TableHead className="text-right">{t.actions}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((candidate) => {
              const operation = candidateOperation(candidate);
              const firstItem = firstCandidateItem(candidate);
              const rowBusy = busy === candidate.id;

              return (
                <TableRow key={candidate.id}>
                  <TableCell>{operation.operationDate || "N/A"}</TableCell>
                  <TableCell>
                    {operationTypeLabel(candidate.operation_type, t)}
                  </TableCell>
                  <TableCell>
                    {t.ozonSourceLabel(candidate.source_type)}
                  </TableCell>
                  <TableCell>
                    {supportBadge(operation.supportStatus || "commit_candidate", t)}
                  </TableCell>
                  <TableCell>{statusBadge(candidate.status, t)}</TableCell>
                  <TableCell className="max-w-56 truncate">
                    {candidate.external_event_id}
                  </TableCell>
                  <TableCell>
                    <div className="max-w-64 truncate">
                      {firstItem.productName || firstItem.skuCode || "N/A"}
                      {candidateItems(candidate).length > 1
                        ? ` +${candidateItems(candidate).length - 1}`
                        : ""}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {firstItem.warehouseName || "N/A"}
                    </div>
                  </TableCell>
                  <TableCell>
                    {(candidate.validation_errors || []).length > 0 ? (
                      <Badge variant="destructive">
                        {candidate.validation_errors.length}
                      </Badge>
                    ) : (
                      <Badge variant="secondary">{t.ready}</Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex justify-end gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setSelected(candidate)}
                      >
                        <Eye className="h-4 w-4" />
                        {t.review}
                      </Button>
                      {candidate.status === "approved" && (
                        <Button
                          size="sm"
                          disabled={rowBusy}
                          onClick={() => commitApproved([candidate.id])}
                        >
                          {rowBusy && <Loader2 className="h-4 w-4 animate-spin" />}
                          {t.commit}
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </div>

      {items.length === 0 && !loading && (
        <div className="rounded-md border border-t-0 p-8 text-center text-sm text-muted-foreground">
          {t.ozonNoCandidates}
        </div>
      )}

      <div className="mt-4">
        <Pagination
          limit={page.limit}
          offset={page.offset}
          total={page.total}
          onPageChange={(nextOffset) => fetchCandidates(nextOffset)}
        />
      </div>

      <CandidateSheet
        candidate={selected}
        positionLabel={selectedPosition}
        canGoPrevious={selectedIndex > 0}
        canGoNext={selectedIndex >= 0 && selectedIndex < items.length - 1}
        products={products}
        warehouses={warehouses}
        busy={busy}
        onClose={() => setSelected(null)}
        onPrevious={() => {
          if (selectedIndex > 0) setSelected(items[selectedIndex - 1]);
        }}
        onNext={() => {
          if (selectedIndex >= 0 && selectedIndex < items.length - 1) {
            setSelected(items[selectedIndex + 1]);
          }
        }}
        onPatch={(candidate, body) =>
          runAction(candidate.id, () => patchCandidate(candidate, body))
        }
        onCreateProduct={(candidate, itemIndex) =>
          runAction(candidate.id, () =>
            postCandidateAction(candidate, "create-product", { itemIndex })
          )
        }
        onCreateWarehouse={(candidate, itemIndex) =>
          runAction(candidate.id, () =>
            postCandidateAction(candidate, "create-warehouse", { itemIndex })
          )
        }
        onApprove={(candidate) =>
          runAction(candidate.id, () =>
            postCandidateAction(candidate, "approve")
          )
        }
        onCommit={(candidate) => commitApproved([candidate.id])}
      />
    </div>
  );
}

function CandidateSheet({
  candidate,
  positionLabel,
  canGoPrevious,
  canGoNext,
  products,
  warehouses,
  busy,
  onClose,
  onPrevious,
  onNext,
  onPatch,
  onCreateProduct,
  onCreateWarehouse,
  onApprove,
  onCommit,
}: {
  candidate: MarketplaceCandidateRow | null;
  positionLabel: string | null;
  canGoPrevious: boolean;
  canGoNext: boolean;
  products: Product[];
  warehouses: WarehouseType[];
  busy: string | null;
  onClose: () => void;
  onPrevious: () => void;
  onNext: () => void;
  onPatch: (candidate: MarketplaceCandidateRow, body: Record<string, unknown>) => void;
  onCreateProduct: (candidate: MarketplaceCandidateRow, itemIndex: number) => void;
  onCreateWarehouse: (candidate: MarketplaceCandidateRow, itemIndex: number) => void;
  onApprove: (candidate: MarketplaceCandidateRow) => void;
  onCommit: (candidate: MarketplaceCandidateRow) => void;
}) {
  const { t } = useI18n();
  const operation = candidate ? candidateOperation(candidate) : {};
  const items = candidate ? candidateItems(candidate) : [];
  const readOnly =
    candidate?.status === "committed" || candidate?.status === "committing";
  const rowBusy = Boolean(candidate && busy === candidate.id);
  const canApprove =
    candidate?.status === "ready" || candidate?.status === "needs_mapping";

  return (
    <Sheet open={Boolean(candidate)} onOpenChange={(open) => !open && onClose()}>
      <SheetContent className="w-full gap-0 overflow-hidden p-0 sm:max-w-3xl">
        <SheetHeader className="border-b px-6 py-5 pr-12">
          <div className="flex items-start justify-between gap-4">
            <div>
              <SheetTitle>{t.ozonReviewDetails}</SheetTitle>
              <SheetDescription>{candidate?.external_event_id}</SheetDescription>
            </div>
            {positionLabel && (
              <Badge variant="outline" className="shrink-0">
                {positionLabel}
              </Badge>
            )}
          </div>
        </SheetHeader>

        {candidate && (
          <div className="flex-1 space-y-5 overflow-y-auto px-6 py-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t.operationDate}</FieldLabel>
                <Input
                  type="date"
                  value={operation.operationDate || ""}
                  disabled={readOnly}
                  onChange={(event) =>
                    onPatch(candidate, { operationDate: event.target.value })
                  }
                />
              </Field>
              <Field>
                <FieldLabel>{t.status}</FieldLabel>
                <div>{statusBadge(candidate.status, t)}</div>
              </Field>
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <Field>
                <FieldLabel>{t.ozonSourceType}</FieldLabel>
                <div>{candidate ? t.ozonSourceLabel(candidate.source_type) : "N/A"}</div>
              </Field>
              <Field>
                <FieldLabel>{t.ozonEvidence}</FieldLabel>
                <div>{supportBadge(operation.supportStatus || "commit_candidate", t)}</div>
              </Field>
            </div>

            {operation.supportReason && (
              <div className="rounded-md border bg-muted/40 p-3 text-sm">
                <div className="font-medium">{t.ozonSupportReason}</div>
                <p className="mt-1 text-muted-foreground">
                  {operation.supportReason}
                </p>
              </div>
            )}

            <div className="space-y-3">
              {items.map((item, index) => (
                <div key={index} className="rounded-md border p-3">
                  <div className="mb-3 flex items-start justify-between gap-3">
                    <div>
                      <div className="font-medium">
                        {item.productName || item.skuCode || t.product}
                      </div>
                      <div className="text-xs text-muted-foreground">
                        {item.offerId || item.skuCode || item.ozonSku || "N/A"} ·{" "}
                        {item.quantity || 0}
                      </div>
                    </div>
                    <Badge variant="secondary">
                      {item.direction === "in" ? t.directionIn : t.directionOut}
                    </Badge>
                  </div>

                  <div className="grid gap-3 sm:grid-cols-2">
                    <Field>
                      <FieldLabel>{t.product}</FieldLabel>
                      <Select
                        value={item.productId || "__none"}
                        disabled={readOnly}
                        onValueChange={(value) =>
                          onPatch(candidate, {
                            itemIndex: index,
                            productId: value === "__none" ? null : value,
                          })
                        }
                      >
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="__none">{t.mapExisting}</SelectItem>
                          {products.map((product) => (
                            <SelectItem key={product.id} value={product.id}>
                              {product.name}
                              {product.skuCode ? ` (${product.skuCode})` : ""}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      {!readOnly && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={rowBusy}
                          onClick={() => onCreateProduct(candidate, index)}
                        >
                          <PackagePlus className="h-4 w-4" />
                          {t.ozonCreateProduct}
                        </Button>
                      )}
                    </Field>

                    <Field>
                      <FieldLabel>{t.warehouse}</FieldLabel>
                      <Select
                        value={item.warehouseId || "__none"}
                        disabled={readOnly}
                        onValueChange={(value) =>
                          onPatch(candidate, {
                            itemIndex: index,
                            warehouseId: value === "__none" ? null : value,
                          })
                        }
                      >
                        <SelectTrigger>
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          <SelectItem value="__none">{t.mapExisting}</SelectItem>
                          {warehouses.map((warehouse) => (
                            <SelectItem key={warehouse.id} value={warehouse.id}>
                              {warehouse.name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                      {!readOnly && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          disabled={rowBusy}
                          onClick={() => onCreateWarehouse(candidate, index)}
                        >
                          <Warehouse className="h-4 w-4" />
                          {t.ozonCreateWarehouse}
                        </Button>
                      )}
                    </Field>
                  </div>
                </div>
              ))}
            </div>

            {(candidate.validation_errors || []).length > 0 && (
              <div className="rounded-md border border-destructive/40 bg-destructive/10 p-3 text-sm text-destructive">
                <div className="font-medium">{t.ozonValidationTitle}</div>
                <ul className="mt-2 list-disc space-y-1 pl-5">
                  {candidate.validation_errors.map((error, index) => (
                    <li key={`${error.field}-${index}`}>
                      {t.ozonValidationField(error.field)}:{" "}
                      {t.ozonValidationMessage(error.message)}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <details className="rounded-md border p-3">
              <summary className="cursor-pointer text-sm font-medium">
                {t.ozonRawPayload}
              </summary>
              <pre className="mt-3 overflow-x-auto rounded-md bg-muted p-3 text-xs">
                {JSON.stringify(candidate.raw_payload, null, 2)}
              </pre>
            </details>
          </div>
        )}

        {candidate && (
          <SheetFooter className="border-t px-6 py-4 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                disabled={!canGoPrevious}
                onClick={onPrevious}
              >
                <ChevronLeft className="h-4 w-4" />
                {t.previousPage}
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={!canGoNext}
                onClick={onNext}
              >
                {t.nextPage}
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>

            <div className="flex flex-wrap justify-end gap-2">
              {!readOnly && candidate.status !== "ignored" && (
                <Button
                  variant="outline"
                  disabled={rowBusy}
                  onClick={() => onPatch(candidate, { action: "ignore" })}
                >
                  {t.ozonIgnore}
                </Button>
              )}
              {!readOnly && candidate.status === "ignored" && (
                <Button
                  variant="outline"
                  disabled={rowBusy}
                  onClick={() => onPatch(candidate, { action: "unignore" })}
                >
                  <Undo2 className="h-4 w-4" />
                  {t.ozonUnignore}
                </Button>
              )}
              {!readOnly && canApprove && (
                <Button disabled={rowBusy} onClick={() => onApprove(candidate)}>
                  {rowBusy && <Loader2 className="h-4 w-4 animate-spin" />}
                  {t.approve}
                </Button>
              )}
              {candidate.status === "approved" && (
                <Button disabled={rowBusy} onClick={() => onCommit(candidate)}>
                  {rowBusy && <Loader2 className="h-4 w-4 animate-spin" />}
                  {t.commit}
                </Button>
              )}
            </div>
          </SheetFooter>
        )}
      </SheetContent>
    </Sheet>
  );
}

function statusBadge(
  status: MarketplaceCandidateStatus,
  t: ReturnType<typeof useI18n>["t"]
) {
  const label = statusLabel(status, t);

  switch (status) {
    case "approved":
    case "committed":
      return <Badge className="bg-emerald-600">{label}</Badge>;
    case "committing":
      return <Badge className="bg-amber-600">{label}</Badge>;
    case "ignored":
      return <Badge variant="outline">{label}</Badge>;
    case "needs_mapping":
      return <Badge variant="destructive">{label}</Badge>;
    case "ready":
      return <Badge variant="secondary">{label}</Badge>;
  }
}

function supportBadge(
  status: OzonCandidateSupportStatus,
  t: ReturnType<typeof useI18n>["t"]
) {
  switch (status) {
    case "commit_candidate":
      return <Badge variant="secondary">{t.ozonSupportLabel(status)}</Badge>;
    case "blocked":
      return <Badge variant="destructive">{t.ozonSupportLabel(status)}</Badge>;
    case "reporting_only":
      return <Badge variant="outline">{t.ozonSupportLabel(status)}</Badge>;
  }
}

function statusLabel(
  status: MarketplaceCandidateStatus,
  t: ReturnType<typeof useI18n>["t"]
) {
  switch (status) {
    case "approved":
      return t.ozonCandidateApproved;
    case "committed":
      return t.ozonCandidateCommitted;
    case "committing":
      return t.ozonCandidateCommitting;
    case "ignored":
      return t.ozonCandidateIgnored;
    case "needs_mapping":
      return t.ozonCandidateNeedsMapping;
    case "ready":
      return t.ready;
  }
}

function operationTypeLabel(
  type: OperationType | null,
  t: ReturnType<typeof useI18n>["t"]
) {
  switch (type) {
    case "purchase":
      return t.opPurchase;
    case "sale":
      return t.opSale;
    case "return":
      return t.opReturn;
    case "write_off":
      return t.opWriteOff;
    case "transfer":
      return t.opTransfer;
    case "production":
      return t.opProduction;
    case "defect":
      return t.opDefect;
    case "payment":
      return t.opPayment;
    case "inventory_adjustment":
      return t.opInventoryAdjustment;
    default:
      return "N/A";
  }
}
