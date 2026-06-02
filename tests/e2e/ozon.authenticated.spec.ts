import {
  expect,
  test,
  type APIRequestContext,
  type APIResponse,
} from "@playwright/test";
import { createClient, type SupabaseClient, type User } from "@supabase/supabase-js";
import {
  authSkipReason,
  getAuthCredentials,
  hasAuthCredentials,
  loadLocalEnv,
} from "./auth-helpers";
import {
  buildOzonFixture,
  startOzonMockServer,
  type OzonMockFixture,
  type OzonMockServer,
} from "./ozon-mock-server";
import type { MarketplaceCandidateRow } from "../../src/lib/ozon/candidates";

const RUN_ID = Date.now().toString(36);
const VALID_CLIENT_ID = "ozon-client";
const VALID_API_KEY = "ozon-api-key";

test.describe.configure({ mode: "serial" });

interface AdminWorkspace {
  admin: SupabaseClient;
  workspaceId: string;
}

interface EntityResponse {
  id: string;
  name: string;
  skuCode?: string | null;
}

interface OzonSummaryResponse {
  connected: boolean;
  connection: {
    id: string;
    status: "draft" | "connected" | "invalid" | "error" | "disabled";
    health?: {
      validated?: boolean;
      warehouseCount?: number;
      error?: string;
      validation?: unknown;
    };
    lastSyncStatus?: "running" | "completed" | "completed_with_errors" | "failed" | null;
  } | null;
  counts: {
    products: number;
    unmappedProducts: number;
    warehouses: number;
    unmappedWarehouses: number;
    postings: number;
    returns: number;
    financeTransactions: number;
    legalEntitySales: number;
    unpaidLegalProducts: number;
    financeReports: number;
    removals: number;
    supplies: number;
    stockAnalytics: number;
    discountedProducts: number;
    candidatesReady: number;
    candidatesNeedsMapping: number;
  };
  setupError?: string;
}

interface CandidateListResponse {
  summary: {
    needsMapping: number;
    ready: number;
    approved: number;
    committing: number;
    ignored: number;
    committed: number;
  };
  items: MarketplaceCandidateRow[];
}

interface CommitResponse {
  committed: { candidateId: string; operationId: string; skipped?: boolean }[];
  failed: { candidateId: string; error: string }[];
  committedCount: number;
  skippedCount: number;
  failedCount: number;
}

interface OperationDetailsResponse {
  id: string;
  type: string;
  operationDate: string;
  items: {
    productId: string;
    warehouseId: string;
    quantity: number;
    direction: string;
  }[];
}

function uniqueName(prefix: string, testInfo: { workerIndex: number }): string {
  return `E2E-Ozon-${prefix}-${RUN_ID}-${testInfo.workerIndex}-${Math.random()
    .toString(36)
    .slice(2, 8)}`;
}

async function expectJson<T>(
  responsePromise: Promise<APIResponse>,
  status: number
): Promise<T> {
  const response = await responsePromise;
  const bodyText = await response.text();
  expect(response.status(), bodyText).toBe(status);
  return JSON.parse(bodyText) as T;
}

async function postJson<T>(
  request: APIRequestContext,
  url: string,
  data: Record<string, unknown>,
  status = 200
): Promise<T> {
  return expectJson<T>(request.post(url, { data }), status);
}

async function patchJson<T>(
  request: APIRequestContext,
  url: string,
  data: Record<string, unknown>,
  status = 200
): Promise<T> {
  return expectJson<T>(request.patch(url, { data }), status);
}

async function createProduct(
  request: APIRequestContext,
  name: string,
  skuCode: string
): Promise<EntityResponse> {
  return postJson<EntityResponse>(
    request,
    "/api/products",
    { name, skuCode },
    201
  );
}

async function createWarehouse(
  request: APIRequestContext,
  name: string
): Promise<EntityResponse> {
  return postJson<EntityResponse>(
    request,
    "/api/warehouses",
    { name, purpose: "storage" },
    201
  );
}

async function connectOzon(
  request: APIRequestContext,
  apiKey = VALID_API_KEY,
  status = 200
) {
  return postJson<OzonSummaryResponse>(
    request,
    "/api/integrations/ozon",
    { clientId: VALID_CLIENT_ID, apiKey },
    status
  );
}

async function syncOzon(request: APIRequestContext, fixture: OzonMockFixture) {
  return postJson<{ status: string; summary: { errors: string[] } }>(
    request,
    "/api/integrations/ozon/sync",
    {
      dateFrom: fixture.dateFrom,
      dateTo: fixture.dateTo,
    }
  );
}

async function listCandidates(request: APIRequestContext) {
  return expectJson<CandidateListResponse>(
    request.get("/api/integrations/ozon/candidates?limit=200"),
    200
  );
}

function findCandidate(
  candidates: CandidateListResponse,
  externalEventId: string
) {
  const candidate = candidates.items.find(
    (item) => item.external_event_id === externalEventId
  );
  expect(candidate, `candidate ${externalEventId}`).toBeTruthy();
  return candidate!;
}

test.describe("Ozon marketplace integration", () => {
  test.beforeEach(() => {
    test.skip(!hasAuthCredentials(), authSkipReason());
  });

  test("marks invalid credentials without syncing data", async ({ request }, testInfo) => {
    const adminWorkspace = await getAdminWorkspace();
    test.skip(!adminWorkspace, adminSkipReason());
    requireOzonSchema(
      await supportsOzonSchema(adminWorkspace!.admin, adminWorkspace!.workspaceId),
      "Local Supabase schema is missing Ozon marketplace migrations through 015_ozon_commit_hardening.sql"
    );

    const fixture = buildOzonFixture(uniqueName("Invalid", testInfo));
    let mock: OzonMockServer | null = null;

    try {
      await resetOzonState(adminWorkspace!, false);
      mock = await startOzonMockServer(fixture, {
        validClientId: VALID_CLIENT_ID,
        validApiKey: VALID_API_KEY,
      });

      const result = await connectOzon(request, "wrong-api-key", 400);
      expect(result.connection?.status).toBe("invalid");

      const summary = await expectJson<OzonSummaryResponse>(
        request.get("/api/integrations/ozon"),
        200
      );
      expect(summary.connected).toBe(false);
      expect(summary.connection?.status).toBe("invalid");
      expect(summary.counts.postings).toBe(0);
    } finally {
      await mock?.close();
      await resetOzonState(adminWorkspace!, false);
    }
  });

  test("reports partial sync completion when one Ozon step fails", async ({
    page,
    request,
  }, testInfo) => {
    const adminWorkspace = await getAdminWorkspace();
    test.skip(!adminWorkspace, adminSkipReason());
    requireOzonSchema(
      await supportsOzonSchema(adminWorkspace!.admin, adminWorkspace!.workspaceId),
      "Local Supabase schema is missing Ozon marketplace migrations through 015_ozon_commit_hardening.sql"
    );

    const fixture = buildOzonFixture(uniqueName("Partial", testInfo));
    let mock: OzonMockServer | null = null;

    try {
      await resetOzonState(adminWorkspace!, false);
      mock = await startOzonMockServer(fixture, {
        failPaths: ["/v1/finance/accrual/types"],
        validClientId: VALID_CLIENT_ID,
        validApiKey: VALID_API_KEY,
      });

      await connectOzon(request);
      const syncResult = await syncOzon(request, fixture);
      expect(syncResult.status).toBe("completed_with_errors");
      expect(syncResult.summary.errors.join(" ")).toContain(
        "/v1/finance/accrual/types"
      );

      const summary = await expectJson<OzonSummaryResponse>(
        request.get("/api/integrations/ozon"),
        200
      );
      expect(summary.connection?.lastSyncStatus).toBe("completed_with_errors");
      expect(summary.connection?.health?.validation).toBeUndefined();

      await page.goto("/operations/marketplaces");
      await expect(page.getByText("Completed with errors")).toBeVisible();
      await expect(
        page.getByText("/v1/finance/accrual/types", { exact: false })
      ).toBeVisible();
    } finally {
      await mock?.close();
      await resetOzonState(adminWorkspace!, false);
    }
  });

  test("syncs, reviews, maps, approves, commits, and preserves decisions", async ({
    page,
    request,
  }, testInfo) => {
    const adminWorkspace = await getAdminWorkspace();
    test.skip(!adminWorkspace, adminSkipReason());
    requireOzonSchema(
      await supportsOzonSchema(adminWorkspace!.admin, adminWorkspace!.workspaceId),
      "Local Supabase schema is missing Ozon marketplace migrations through 015_ozon_commit_hardening.sql"
    );

    const fixture = buildOzonFixture(uniqueName("Happy", testInfo));
    let mock: OzonMockServer | null = null;

    try {
      await resetOzonState(adminWorkspace!, false);
      requireOzonSchema(
        await supportsApprovedCandidateStatus(adminWorkspace!),
        "Local Supabase schema is missing hardened Ozon candidate statuses"
      );
      mock = await startOzonMockServer(fixture, {
        validClientId: VALID_CLIENT_ID,
        validApiKey: VALID_API_KEY,
      });

      const autoProduct = await createProduct(
        request,
        `${fixture.autoProduct.name} Local`,
        fixture.autoProduct.offerId
      );
      const returnProduct = await createProduct(
        request,
        `${fixture.returnProduct.name} Local`,
        fixture.returnProduct.offerId
      );
      const autoWarehouse = await createWarehouse(
        request,
        fixture.autoWarehouse.name
      );
      const sourceWarehouse = await createWarehouse(
        request,
        `${fixture.autoWarehouse.name} Source`
      );

      const connected = await connectOzon(request);
      expect(connected.connected).toBe(true);
      expect(connected.connection?.status).toBe("connected");
      expect(connected.connection?.health).toMatchObject({
        validated: true,
        warehouseCount: 2,
      });
      expect(connected.connection?.health?.validation).toBeUndefined();

      await page.goto("/settings?tab=integrations");
      await expect(page.getByRole("button", { name: "Sync now" })).toHaveCount(0);
      await expect(page.getByText("Synced data")).toHaveCount(0);
      await expect(
        page.getByRole("link", { name: "Open marketplaces" })
      ).toBeVisible();

      await page.getByRole("link", { name: "Open marketplaces" }).click();
      await expect(page).toHaveURL(/\/operations\/marketplaces$/);
      await expect(
        page.getByRole("heading", { name: "Marketplaces" })
      ).toBeVisible();
      const syncButton = page.getByRole("button", { name: "Sync now" });
      await expect(syncButton).toBeEnabled();
      await syncButton.click();
      await expect(page.getByRole("status")).toContainText(
        "Ozon sync is running"
      );
      await expect(page.getByText("Ozon sync finished.")).toBeVisible({
        timeout: 30_000,
      });
      await expect(syncButton).toBeEnabled();
      await expect(page.getByText("Last sync status")).toBeVisible();
      await expect(page.getByText("Completed")).toBeVisible();
      await expect(page.getByText("Products")).toBeVisible();
      await expect(page.getByText("Ready candidates")).toBeVisible();

      const summary = await expectJson<OzonSummaryResponse>(
        request.get("/api/integrations/ozon"),
        200
      );
      expect(summary.counts).toMatchObject({
        products: 3,
        unmappedProducts: 1,
        warehouses: 2,
        unmappedWarehouses: 1,
        postings: 3,
        returns: 1,
        financeTransactions: 1,
        legalEntitySales: 1,
        unpaidLegalProducts: 1,
        financeReports: 5,
        removals: 1,
        supplies: 1,
        stockAnalytics: 0,
        discountedProducts: 1,
        candidatesReady: 3,
        candidatesNeedsMapping: 3,
      });

      let candidates = await listCandidates(request);
      expect(candidates.summary).toMatchObject({
        needsMapping: 3,
        ready: 3,
        approved: 0,
        ignored: 1,
        committed: 0,
      });

      const fbsCandidate = findCandidate(
        candidates,
        `fbs:${fixture.fbsPostingNumber}:delivered`
      );
      const fboCandidate = findCandidate(
        candidates,
        `fbo:${fixture.fboPostingNumber}:delivered`
      );
      const canceledCandidate = findCandidate(
        candidates,
        `fbs:${fixture.canceledPostingNumber}:cancelled`
      );
      const returnCandidate = findCandidate(
        candidates,
        `return:${fixture.returnId}`
      );
      const removalCandidate = findCandidate(
        candidates,
        `removal:from_stock:${fixture.removalId}`
      );
      const supplyCandidate = findCandidate(
        candidates,
        `supply:${fixture.supplyOrderId}:${fixture.supplyBundleId}-1`
      );
      const defectCandidate = findCandidate(
        candidates,
        `discounted:${fixture.discountedSku}`
      );

      expect(fbsCandidate.status).toBe("needs_mapping");
      expect(fboCandidate.status).toBe("ready");
      expect(canceledCandidate.status).toBe("ignored");
      expect(returnCandidate.status).toBe("needs_mapping");
      expect(removalCandidate.status).toBe("ready");
      expect(supplyCandidate.status).toBe("needs_mapping");
      expect(defectCandidate.status).toBe("ready");
      expect(JSON.stringify(candidates)).not.toContain("Secret Buyer");
      expect(JSON.stringify(candidates)).not.toContain("+79990000000");
      await expectNoPersistedPii(adminWorkspace!);

      await page.goto("/operations");
      const reviewCandidatesButton = page.getByRole("link", {
        name: "Review candidates 6",
      });
      await expect(reviewCandidatesButton).toBeVisible();
      await reviewCandidatesButton.click();
      await expect(page).toHaveURL(
        /\/operations\/marketplace\/ozon\?returnTo=%2Foperations/
      );
      await expect(page.getByRole("heading", { name: "Ozon candidates" })).toBeVisible();
      await expect(page.getByText(fixture.fbsPostingNumber)).toBeVisible();
      await page.getByRole("link", { name: "Back" }).click();
      await expect(page).toHaveURL(/\/operations$/);
      await page.goto("/operations/marketplaces");
      await expect(
        page.getByRole("link", { name: /Marketplaces/ })
      ).toBeVisible();
      await page.getByRole("link", { name: "Review candidates" }).click();
      await expect(page).toHaveURL(
        /\/operations\/marketplace\/ozon\?returnTo=%2Foperations%2Fmarketplaces/
      );
      await page.getByRole("link", { name: "Back" }).click();
      await expect(page).toHaveURL(/\/operations\/marketplaces$/);
      await page.goto(
        "/operations/marketplace/ozon?returnTo=%2Fsettings%3Ftab%3Dintegrations"
      );
      await page.getByRole("link", { name: "Back" }).click();
      await expect(page).toHaveURL(/\/settings\?tab=integrations$/);
      await page.goto("/operations/marketplace/ozon?returnTo=%2Foperations%2Fmarketplaces");
      const firstVisibleCandidate = candidates.items[0];
      const secondVisibleCandidate = candidates.items[1];
      await page
        .locator("tr")
        .filter({ hasText: firstVisibleCandidate.external_event_id })
        .getByRole("button", { name: "Review" })
        .click();
      const drawer = page.locator('[role="dialog"]');
      await expect(drawer.locator('[data-slot="sheet-header"]')).toHaveClass(/px-6/);
      await expect(drawer.getByText("1 / 7")).toBeVisible();
      await expect(drawer.getByRole("button", { name: "Previous" })).toBeDisabled();
      await expect(drawer.getByRole("button", { name: "Next" })).toBeEnabled();
      await drawer.getByRole("button", { name: "Next" }).click();
      await expect(drawer.getByText(secondVisibleCandidate.external_event_id)).toBeVisible();
      await expect(drawer.getByText("2 / 7")).toBeVisible();
      await drawer.getByRole("button", { name: "Previous" }).click();
      await expect(drawer.getByText(firstVisibleCandidate.external_event_id)).toBeVisible();
      await page.keyboard.press("Escape");

      await page
        .locator("tr")
        .filter({ hasText: fixture.fbsPostingNumber })
        .getByRole("button", { name: "Review" })
        .click();
      await expect(drawer.getByText(fixture.autoProduct.name).first()).toBeVisible();
      await expect(drawer.getByText(fixture.missingProduct.name).first()).toBeVisible();
      await expect(drawer.getByText(fixture.autoProduct.offerId).first()).toBeVisible();
      await page.evaluate(() => {
        window.localStorage.setItem("tover-locale", "ru");
        window.dispatchEvent(new Event("tover-locale-change"));
      });
      await expect(drawer.getByText("Проверка кандидата")).toBeVisible();
      await expect(drawer.getByText("Товар в позиции 2: Сопоставьте товар")).toBeVisible();
      await page.evaluate(() => {
        window.localStorage.setItem("tover-locale", "en");
        window.dispatchEvent(new Event("tover-locale-change"));
      });
      await page.keyboard.press("Escape");

      const unapprovedCommit = await postJson<CommitResponse>(
        request,
        "/api/integrations/ozon/candidates/commit",
        { candidateIds: [fboCandidate.id] }
      );
      expect(unapprovedCommit).toMatchObject({
        committedCount: 0,
        failedCount: 1,
      });
      expect(unapprovedCommit.failed[0].error).toContain("approved");

      const reportingOnlyOperation = {
        type: "sale",
        operationDate: "2099-05-02",
        sourceType: "report",
        supportStatus: "reporting_only",
        supportReason: "Synthetic reporting-only test evidence",
        items: [
          {
            productId: autoProduct.id,
            productName: autoProduct.name,
            warehouseId: autoWarehouse.id,
            warehouseName: autoWarehouse.name,
            quantity: 1,
            unitPrice: 1,
            direction: "out",
          },
        ],
      };
      const { data: reportingCandidate, error: reportingInsertError } =
        await adminWorkspace!.admin
          .from("marketplace_operation_candidates")
          .insert({
            workspace_id: adminWorkspace!.workspaceId,
            connection_id: connected.connection!.id,
            provider: "ozon",
            source_type: "report",
            external_event_id: `reporting-only-${fixture.runId}`,
            status: "ready",
            operation_type: "sale",
            operation_date: "2099-05-02",
            confidence: 0.2,
            operation: reportingOnlyOperation,
            normalized_operation: reportingOnlyOperation,
            validation_errors: [],
            raw_payload: {},
          })
          .select("id")
          .single();
      expect(reportingInsertError).toBeNull();

      const reportingApprove = await request.post(
        `/api/integrations/ozon/candidates/${reportingCandidate!.id}/approve`
      );
      expect(reportingApprove.status(), await reportingApprove.text()).toBe(400);

      const { error: reportingForceApproveError } = await adminWorkspace!.admin
        .from("marketplace_operation_candidates")
        .update({ status: "approved", validation_errors: [] })
        .eq("id", reportingCandidate!.id);
      expect(reportingForceApproveError).toBeNull();
      const reportingCommit = await postJson<CommitResponse>(
        request,
        "/api/integrations/ozon/candidates/commit",
        { candidateIds: [reportingCandidate!.id] }
      );
      expect(reportingCommit).toMatchObject({
        committedCount: 0,
        failedCount: 1,
      });
      expect(reportingCommit.failed[0].error).toContain("validation");
      await adminWorkspace!.admin
        .from("marketplace_operation_candidates")
        .delete()
        .eq("id", reportingCandidate!.id);

      const committingOperation = {
        ...reportingOnlyOperation,
        supportStatus: "commit_candidate",
        supportReason: "Synthetic committing test evidence",
      };
      const { data: committingCandidate, error: committingInsertError } =
        await adminWorkspace!.admin
          .from("marketplace_operation_candidates")
          .insert({
            workspace_id: adminWorkspace!.workspaceId,
            connection_id: connected.connection!.id,
            provider: "ozon",
            source_type: "report",
            external_event_id: `committing-${fixture.runId}`,
            status: "committing",
            operation_type: "sale",
            operation_date: "2099-05-02",
            confidence: 0.2,
            operation: committingOperation,
            normalized_operation: committingOperation,
            validation_errors: [],
            raw_payload: {},
          })
          .select("id")
          .single();
      expect(committingInsertError).toBeNull();
      const committingEdit = await request.patch(
        `/api/integrations/ozon/candidates/${committingCandidate!.id}`,
        { data: { operationDate: "2099-05-03" } }
      );
      expect(committingEdit.status(), await committingEdit.text()).toBe(409);
      const committingCommit = await postJson<CommitResponse>(
        request,
        "/api/integrations/ozon/candidates/commit",
        { candidateIds: [committingCandidate!.id] }
      );
      expect(committingCommit).toMatchObject({
        committedCount: 0,
        failedCount: 1,
      });
      expect(committingCommit.failed[0].error).toContain("being committed");
      await adminWorkspace!.admin
        .from("marketplace_operation_candidates")
        .delete()
        .eq("id", committingCandidate!.id);

      await page
        .locator("tr")
        .filter({ hasText: fixture.fboPostingNumber })
        .getByRole("button", { name: "Review" })
        .click();
      await expect(drawer.getByRole("button", { name: "Approve" })).toBeVisible();
      await expect(drawer.getByRole("button", { name: "Commit" })).toHaveCount(0);
      await drawer.getByRole("button", { name: "Approve" }).click();
      await expect(drawer.getByRole("button", { name: "Approve" })).toHaveCount(0);
      await expect(drawer.getByRole("button", { name: "Commit" })).toBeVisible();
      await page.keyboard.press("Escape");

      await page
        .locator("tr")
        .filter({ hasText: fixture.canceledPostingNumber })
        .getByRole("button", { name: "Review" })
        .click();
      await expect(
        drawer.getByRole("button", { name: "Restore to review" })
      ).toBeVisible();
      await expect(drawer.getByRole("button", { name: "Approve" })).toHaveCount(0);
      await drawer.getByRole("button", { name: "Restore to review" }).click();
      await expect(drawer.getByRole("button", { name: "Ignore" })).toBeVisible();
      await drawer.getByRole("button", { name: "Ignore" }).click();
      await expect(drawer.getByText("Ignored")).toBeVisible();
      await page.keyboard.press("Escape");

      const { error: forceApproveError } = await adminWorkspace!.admin
        .from("marketplace_operation_candidates")
        .update({ status: "approved" })
        .eq("id", fbsCandidate.id);
      expect(forceApproveError).toBeNull();
      const invalidCommit = await postJson<CommitResponse>(
        request,
        "/api/integrations/ozon/candidates/commit",
        { candidateIds: [fbsCandidate.id] }
      );
      expect(invalidCommit).toMatchObject({
        committedCount: 0,
        failedCount: 1,
      });
      expect(invalidCommit.failed[0].error).toContain("validation");

      await setProductDefaultsMissing(adminWorkspace!);
      const blockedCreate = await request.post(
        `/api/integrations/ozon/candidates/${fbsCandidate.id}/create-product`,
        { data: { itemIndex: 1 } }
      );
      expect(blockedCreate.status(), await blockedCreate.text()).toBe(500);
      await resetWorkspaceSettings(adminWorkspace!, false);

      const fbsMapped = await postJson<{ candidate: MarketplaceCandidateRow }>(
        request,
        `/api/integrations/ozon/candidates/${fbsCandidate.id}/create-product`,
        { itemIndex: 1 }
      );
      expect(fbsMapped.candidate.status).toBe("ready");
      expect(fbsMapped.candidate.normalized_operation.items?.[1]).toMatchObject({
        productName: fixture.missingProduct.name,
        productId: expect.any(String),
        warehouseId: autoWarehouse.id,
      });

      const createdProduct = await expectJson<{ items: EntityResponse[] }>(
        request.get(`/api/products?search=${encodeURIComponent(fixture.missingProduct.offerId)}`),
        200
      );
      expect(createdProduct.items[0]).toMatchObject({
        name: fixture.missingProduct.name,
        skuCode: fixture.missingProduct.offerId,
      });

      const returnMapped = await postJson<{ candidate: MarketplaceCandidateRow }>(
        request,
        `/api/integrations/ozon/candidates/${returnCandidate.id}/create-warehouse`,
        { itemIndex: 0 }
      );
      expect(returnMapped.candidate.status).toBe("ready");
      expect(returnMapped.candidate.normalized_operation.items?.[0]).toMatchObject({
        productId: returnProduct.id,
        warehouseName: fixture.returnWarehouse.name,
        direction: "in",
      });

      const createdWarehouse = await expectJson<{ items: EntityResponse[] }>(
        request.get("/api/warehouses?limit=1000"),
        200
      );
      expect(
        createdWarehouse.items.find((item) => item.name === fixture.returnWarehouse.name)
      ).toBeTruthy();

      await syncOzon(request, fixture);
      candidates = await listCandidates(request);
      expect(
        findCandidate(candidates, `fbo:${fixture.fboPostingNumber}:delivered`).status
      ).toBe("approved");
      expect(
        findCandidate(candidates, `fbs:${fixture.canceledPostingNumber}:cancelled`).status
      ).toBe("ignored");

      const supplyMapped = await patchJson<{ candidate: MarketplaceCandidateRow }>(
        request,
        `/api/integrations/ozon/candidates/${supplyCandidate.id}`,
        { itemIndex: 0, warehouseId: sourceWarehouse.id }
      );
      expect(supplyMapped.candidate.status).toBe("ready");
      expect(supplyMapped.candidate.normalized_operation.items?.[0]).toMatchObject({
        productId: autoProduct.id,
        warehouseId: sourceWarehouse.id,
        direction: "out",
      });

      const approvedBulk = await postJson<{ approved: number; blocked: number }>(
        request,
        "/api/integrations/ozon/candidates/approve-ready",
        {}
      );
      expect(approvedBulk).toEqual({ approved: 5, blocked: 0 });

      const committed = await postJson<CommitResponse>(
        request,
        "/api/integrations/ozon/candidates/commit",
        {}
      );
      expect(committed).toMatchObject({
        committedCount: 6,
        failedCount: 0,
      });

      const fbsCommit = committed.committed.find(
        (item) => item.candidateId === fbsCandidate.id
      );
      const returnCommit = committed.committed.find(
        (item) => item.candidateId === returnCandidate.id
      );
      const removalCommit = committed.committed.find(
        (item) => item.candidateId === removalCandidate.id
      );
      const supplyCommit = committed.committed.find(
        (item) => item.candidateId === supplyCandidate.id
      );
      const defectCommit = committed.committed.find(
        (item) => item.candidateId === defectCandidate.id
      );
      expect(fbsCommit?.operationId).toBeTruthy();
      expect(returnCommit?.operationId).toBeTruthy();
      expect(removalCommit?.operationId).toBeTruthy();
      expect(supplyCommit?.operationId).toBeTruthy();
      expect(defectCommit?.operationId).toBeTruthy();

      const saleDetails = await expectJson<OperationDetailsResponse>(
        request.get(`/api/operations/${fbsCommit!.operationId}`),
        200
      );
      expect(saleDetails).toMatchObject({
        type: "sale",
        operationDate: "2099-05-01",
      });
      expect(saleDetails.items).toHaveLength(2);
      expect(saleDetails.items).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            productId: autoProduct.id,
            warehouseId: autoWarehouse.id,
            quantity: 2,
            direction: "out",
          }),
          expect.objectContaining({
            quantity: 1,
            direction: "out",
          }),
        ])
      );

      const returnDetails = await expectJson<OperationDetailsResponse>(
        request.get(`/api/operations/${returnCommit!.operationId}`),
        200
      );
      expect(returnDetails).toMatchObject({
        type: "return",
        operationDate: "2099-05-03",
        items: [
          expect.objectContaining({
            productId: returnProduct.id,
            quantity: 1,
            direction: "in",
          }),
        ],
      });

      const removalDetails = await expectJson<OperationDetailsResponse>(
        request.get(`/api/operations/${removalCommit!.operationId}`),
        200
      );
      expect(removalDetails).toMatchObject({
        type: "write_off",
        operationDate: "2099-05-04",
      });

      const supplyDetails = await expectJson<OperationDetailsResponse>(
        request.get(`/api/operations/${supplyCommit!.operationId}`),
        200
      );
      expect(supplyDetails).toMatchObject({
        type: "transfer",
        operationDate: "2099-05-04",
      });
      expect(supplyDetails.items).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            productId: autoProduct.id,
            warehouseId: sourceWarehouse.id,
            direction: "out",
          }),
          expect.objectContaining({
            productId: autoProduct.id,
            warehouseId: autoWarehouse.id,
            direction: "in",
          }),
        ])
      );

      const defectDetails = await expectJson<OperationDetailsResponse>(
        request.get(`/api/operations/${defectCommit!.operationId}`),
        200
      );
      expect(defectDetails).toMatchObject({
        type: "defect",
      });

      const operationsList = await expectJson<{ items: { operationId: string }[] }>(
        request.get("/api/operations?from=2099-05-01&to=2099-05-03"),
        200
      );
      expect(operationsList.items.map((item) => item.operationId)).toEqual(
        expect.arrayContaining([fbsCommit!.operationId, returnCommit!.operationId])
      );

      const repeatedCommit = await postJson<CommitResponse>(
        request,
        "/api/integrations/ozon/candidates/commit",
        { candidateIds: [fbsCandidate.id] }
      );
      expect(repeatedCommit).toMatchObject({
        committedCount: 0,
        skippedCount: 1,
        failedCount: 0,
      });

      await syncOzon(request, fixture);
      candidates = await listCandidates(request);
      expect(candidates.summary).toMatchObject({
        committed: 6,
        ignored: 1,
      });
      expect(
        findCandidate(candidates, `fbs:${fixture.fbsPostingNumber}:delivered`)
          .created_operation_id
      ).toBe(fbsCommit!.operationId);

      const concurrentOperation = {
        type: "sale",
        operationDate: "2099-05-02",
        sourceType: "report",
        supportStatus: "commit_candidate",
        supportReason: "Synthetic concurrent commit test evidence",
        items: [
          {
            productId: autoProduct.id,
            productName: autoProduct.name,
            warehouseId: autoWarehouse.id,
            warehouseName: autoWarehouse.name,
            quantity: 1,
            unitPrice: 1,
            direction: "out",
          },
        ],
      };
      const { data: concurrentCandidate, error: concurrentInsertError } =
        await adminWorkspace!.admin
          .from("marketplace_operation_candidates")
          .insert({
            workspace_id: adminWorkspace!.workspaceId,
            connection_id: connected.connection!.id,
            provider: "ozon",
            source_type: "report",
            external_event_id: `concurrent-${fixture.runId}`,
            status: "approved",
            operation_type: "sale",
            operation_date: "2099-05-02",
            confidence: 0.2,
            operation: concurrentOperation,
            normalized_operation: concurrentOperation,
            validation_errors: [],
            raw_payload: {},
          })
          .select("id")
          .single();
      expect(concurrentInsertError).toBeNull();

      const concurrentResults = await Promise.all([
        postJson<CommitResponse>(
          request,
          "/api/integrations/ozon/candidates/commit",
          { candidateIds: [concurrentCandidate!.id] }
        ),
        postJson<CommitResponse>(
          request,
          "/api/integrations/ozon/candidates/commit",
          { candidateIds: [concurrentCandidate!.id] }
        ),
      ]);
      const concurrentOperationIds = new Set(
        concurrentResults.flatMap((result) =>
          result.committed.map((item) => item.operationId)
        )
      );
      expect(concurrentOperationIds.size).toBe(1);
      const { data: concurrentCommitted } = await adminWorkspace!.admin
        .from("marketplace_operation_candidates")
        .select("created_operation_id")
        .eq("id", concurrentCandidate!.id)
        .single();
      expect(concurrentCommitted?.created_operation_id).toBe(
        Array.from(concurrentOperationIds)[0]
      );

      await page.goto("/operations");
      await expect(
        page.getByRole("link", { name: /Review candidates/ })
      ).toHaveCount(0);
      expect(mock.requests.some((item) => item.path === "/v4/posting/fbs/list")).toBe(
        true
      );
      expect(mock.requests.some((item) => item.path === "/v3/posting/fbs/list")).toBe(
        false
      );
      expect(mock.requests.some((item) => item.path === "/v3/posting/fbo/list")).toBe(
        true
      );
      expect(
        mock.requests.some((item) => item.path === "/v1/finance/accrual/types")
      ).toBe(true);
      expect(
        mock.requests.some((item) => item.path === "/v1/finance/accrual/by-day")
      ).toBe(true);
      expect(
        mock.requests.some((item) => item.path === "/v3/finance/transaction/list")
      ).toBe(false);
    } finally {
      await mock?.close();
      await resetOzonState(adminWorkspace!, false);
    }
  });
});

async function getAdminWorkspace(): Promise<AdminWorkspace | null> {
  loadLocalEnv();
  const credentials = getAuthCredentials();
  if (!credentials) return null;
  if (
    !process.env.NEXT_PUBLIC_SUPABASE_URL ||
    !process.env.SUPABASE_SERVICE_ROLE_KEY
  ) {
    return null;
  }

  const admin = createClient(
    process.env.NEXT_PUBLIC_SUPABASE_URL,
    process.env.SUPABASE_SERVICE_ROLE_KEY,
    {
      auth: {
        autoRefreshToken: false,
        persistSession: false,
      },
    }
  );
  const user = await findUserByEmail(admin, credentials.email);
  if (!user) return null;

  const { data, error } = await admin
    .from("organization_memberships")
    .select("organization_id")
    .eq("user_id", user.id)
    .eq("status", "active")
    .limit(1);

  if (error || !data?.[0]?.organization_id) return null;
  return { admin, workspaceId: data[0].organization_id as string };
}

function adminSkipReason() {
  return [
    "Set NEXT_PUBLIC_SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY so Ozon",
    "authenticated tests can clean and inspect marketplace state.",
  ].join(" ");
}

async function findUserByEmail(
  admin: SupabaseClient,
  email: string
): Promise<User | null> {
  const normalizedEmail = email.toLowerCase();
  const { data, error } = await admin.auth.admin.listUsers({
    page: 1,
    perPage: 1000,
  });
  if (error) return null;
  return (
    data.users.find((user) => user.email?.toLowerCase() === normalizedEmail) ||
    null
  );
}

async function supportsOzonSchema(admin: SupabaseClient, workspaceId: string) {
  const base = await admin
    .from("marketplace_connections")
    .select("id")
    .eq("workspace_id", workspaceId)
    .limit(1);
  if (base.error) return false;

  const expanded = await admin
    .from("ozon_removals")
    .select("id")
    .eq("workspace_id", workspaceId)
    .limit(1);
  if (expanded.error) return false;

  const claims = await admin
    .from("marketplace_operation_commit_claims")
    .select("id")
    .eq("workspace_id", workspaceId)
    .limit(1);

  return !claims.error;
}

function requireOzonSchema(supported: boolean, message: string) {
  if (!supported && (process.env.CI || process.env.OZON_E2E_STRICT_SCHEMA === "1")) {
    throw new Error(message);
  }
  test.skip(!supported, message);
}

async function supportsApprovedCandidateStatus({
  admin,
  workspaceId,
}: AdminWorkspace) {
  const { data: connection, error: connectionError } = await admin
    .from("marketplace_connections")
    .insert({
      workspace_id: workspaceId,
      provider: "ozon",
      name: "Ozon schema probe",
      credential_ciphertext: {},
      status: "draft",
    })
    .select("id")
    .single();

  if (connectionError || !connection?.id) return false;

  const { error: candidateError } = await admin
    .from("marketplace_operation_candidates")
    .insert({
      workspace_id: workspaceId,
      connection_id: connection.id,
      provider: "ozon",
      source_type: "posting",
      external_event_id: `schema-probe-${Date.now()}`,
      status: "committing",
      operation_type: "sale",
      operation_date: "2099-01-01",
      confidence: 1,
      operation: {},
      normalized_operation: {},
      validation_errors: [],
      raw_payload: {},
    });

  await admin.from("marketplace_connections").delete().eq("id", connection.id);
  return !candidateError;
}

async function resetOzonState(
  { admin, workspaceId }: AdminWorkspace,
  categoryRequired: boolean
) {
  await admin
    .from("marketplace_connections")
    .delete()
    .eq("workspace_id", workspaceId)
    .eq("provider", "ozon");
  await resetWorkspaceSettings({ admin, workspaceId }, categoryRequired);
}

async function resetWorkspaceSettings(
  { admin, workspaceId }: AdminWorkspace,
  categoryRequired: boolean
) {
  const { error } = await admin.from("workspace_settings").upsert(
    {
      workspace_id: workspaceId,
      currency: "EUR",
      category_required: categoryRequired,
      default_category_id: null,
      store_required: false,
      default_store_id: null,
    },
    { onConflict: "workspace_id" }
  );
  if (error) throw new Error(error.message);
}

async function setProductDefaultsMissing(workspace: AdminWorkspace) {
  await resetWorkspaceSettings(workspace, true);
}

async function expectNoPersistedPii({ admin, workspaceId }: AdminWorkspace) {
  const checks = await Promise.all([
    admin
      .from("marketplace_operation_candidates")
      .select("raw_payload")
      .eq("workspace_id", workspaceId)
      .eq("provider", "ozon"),
    admin.from("ozon_postings").select("raw_payload").eq("workspace_id", workspaceId),
    admin.from("ozon_returns").select("raw_payload").eq("workspace_id", workspaceId),
    admin
      .from("ozon_finance_transactions")
      .select("raw_payload")
      .eq("workspace_id", workspaceId),
    admin
      .from("ozon_legal_entity_sales")
      .select("raw_payload")
      .eq("workspace_id", workspaceId),
    admin
      .from("ozon_unpaid_legal_products")
      .select("raw_payload")
      .eq("workspace_id", workspaceId),
    admin.from("ozon_finance_reports").select("raw_payload").eq("workspace_id", workspaceId),
    admin.from("ozon_removals").select("raw_payload").eq("workspace_id", workspaceId),
    admin.from("ozon_supply_orders").select("raw_payload").eq("workspace_id", workspaceId),
    admin
      .from("ozon_discounted_products")
      .select("raw_payload")
      .eq("workspace_id", workspaceId),
  ]);

  for (const result of checks) {
    if (result.error) throw new Error(result.error.message);
    const text = JSON.stringify(result.data || []);
    expect(text).not.toContain("Secret Buyer");
    expect(text).not.toContain("+79990000000");
    expect(text).not.toContain("Secret address");
  }
}
