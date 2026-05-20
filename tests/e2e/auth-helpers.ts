import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import {
  createClient,
  type SupabaseClient,
  type User,
} from "@supabase/supabase-js";

export const AUTH_STATE_PATH = "playwright/.auth/user.json";

const DEFAULT_DEV_EMAIL = "playwright@tover.local";
const DEFAULT_DEV_PASSWORD = "Playwright-dev-password-1";
const DEFAULT_DEV_NAME = "Playwright Dev User";
const DEFAULT_DEV_ORG_NAME = "Playwright Dev Workspace";

interface AuthCredentials {
  email: string;
  password: string;
  source: "explicit" | "dev-default";
}

interface MembershipRow {
  id: string;
  organization_id: string;
  role_id: string;
  status: string;
}

interface WarehouseRow {
  id: string;
  name: string;
}

let loadedEnv = false;
let inventoryAdjustmentSupport: Promise<boolean | null> | null = null;

export function ensureAuthStateDir(): void {
  mkdirSync(dirname(AUTH_STATE_PATH), { recursive: true });
}

export function loadLocalEnv(): void {
  if (loadedEnv) return;
  loadedEnv = true;

  for (const fileName of [".env.local", ".env"]) {
    const filePath = resolve(process.cwd(), fileName);
    if (!existsSync(filePath)) continue;

    for (const rawLine of readFileSync(filePath, "utf8").split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line || line.startsWith("#")) continue;

      const match = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
      if (!match) continue;

      const [, key, rawValue] = match;
      if (process.env[key] !== undefined) continue;
      process.env[key] = parseEnvValue(rawValue);
    }
  }
}

export function getAuthCredentials(): AuthCredentials | null {
  loadLocalEnv();

  const email = process.env.E2E_EMAIL?.trim();
  const password = process.env.E2E_PASSWORD?.trim();

  if (email && password) {
    return { email, password, source: "explicit" };
  }

  if (email || password || !canAutoProvisionDevUser()) {
    return null;
  }

  return {
    email: process.env.E2E_DEV_EMAIL?.trim() || DEFAULT_DEV_EMAIL,
    password: process.env.E2E_DEV_PASSWORD?.trim() || DEFAULT_DEV_PASSWORD,
    source: "dev-default",
  };
}

export function hasAuthCredentials(): boolean {
  return getAuthCredentials() !== null;
}

export function authSkipReason(): string {
  return [
    "Set E2E_EMAIL/E2E_PASSWORD, or run locally with NEXT_PUBLIC_SUPABASE_URL,",
    "NEXT_PUBLIC_SUPABASE_ANON_KEY, and SUPABASE_SERVICE_ROLE_KEY for dev auto-provisioning.",
  ].join(" ");
}

export async function ensureDevAuthUser(
  credentials: AuthCredentials
): Promise<void> {
  if (!canProvisionAuthUser()) return;

  const url = requireEnv("NEXT_PUBLIC_SUPABASE_URL");
  const serviceRoleKey = requireEnv("SUPABASE_SERVICE_ROLE_KEY");
  const admin = createClient(url, serviceRoleKey, {
    auth: {
      autoRefreshToken: false,
      persistSession: false,
    },
  });

  const user = await upsertAuthUser(admin, credentials);
  await ensureOwnerMembership(admin, user.id);
}

export function inventoryAdjustmentSchemaSkipReason(): string {
  return "Local Supabase schema is missing migration 007_inventory_adjustment_operation.sql";
}

export function supportsInventoryAdjustmentOperations(): Promise<boolean | null> {
  inventoryAdjustmentSupport ??= checkInventoryAdjustmentOperationSupport();
  return inventoryAdjustmentSupport;
}

function canAutoProvisionDevUser(): boolean {
  if (!canProvisionAuthUser()) return false;

  return Boolean(
    process.env.NEXT_PUBLIC_SUPABASE_URL &&
      process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY &&
      process.env.SUPABASE_SERVICE_ROLE_KEY
  );
}

function canProvisionAuthUser(): boolean {
  if (process.env.E2E_AUTO_PROVISION === "false") return false;
  if (process.env.NODE_ENV === "production") return false;
  if (process.env.CI && process.env.E2E_AUTO_PROVISION !== "true") return false;

  return Boolean(process.env.NEXT_PUBLIC_SUPABASE_URL && process.env.SUPABASE_SERVICE_ROLE_KEY);
}

function parseEnvValue(rawValue: string): string {
  const value = rawValue.trim();
  const quote = value[0];

  if (
    (quote === '"' || quote === "'") &&
    value.length >= 2 &&
    value[value.length - 1] === quote
  ) {
    return value.slice(1, -1).replace(/\\n/g, "\n");
  }

  return value.replace(/\s+#.*$/, "").trim();
}

function requireEnv(key: string): string {
  const value = process.env[key];
  if (!value) {
    throw new Error(`Missing ${key}`);
  }
  return value;
}

async function upsertAuthUser(
  admin: SupabaseClient,
  credentials: AuthCredentials
): Promise<User> {
  const userMetadata = {
    name: process.env.E2E_DEV_NAME?.trim() || DEFAULT_DEV_NAME,
    organization_name:
      process.env.E2E_DEV_ORG_NAME?.trim() || DEFAULT_DEV_ORG_NAME,
  };
  const existing = await findUserByEmail(admin, credentials.email);

  if (existing) {
    const { data, error } = await admin.auth.admin.updateUserById(existing.id, {
      password: credentials.password,
      email_confirm: true,
      user_metadata: {
        ...existing.user_metadata,
        ...userMetadata,
      },
    });

    if (error || !data.user) {
      throw new Error(`Failed to update dev E2E user: ${error?.message}`);
    }

    return data.user;
  }

  const { data, error } = await admin.auth.admin.createUser({
    email: credentials.email,
    password: credentials.password,
    email_confirm: true,
    user_metadata: userMetadata,
  });

  if (error || !data.user) {
    throw new Error(`Failed to create dev E2E user: ${error?.message}`);
  }

  return data.user;
}

async function findUserByEmail(
  admin: SupabaseClient,
  email: string
): Promise<User | null> {
  const normalizedEmail = email.toLowerCase();
  const perPage = 1000;

  for (let page = 1; page <= 20; page += 1) {
    const { data, error } = await admin.auth.admin.listUsers({
      page,
      perPage,
    });

    if (error) {
      throw new Error(`Failed to list auth users: ${error.message}`);
    }

    const user = data.users.find(
      (item) => item.email?.toLowerCase() === normalizedEmail
    );
    if (user) return user;
    if (data.users.length < perPage) return null;
  }

  return null;
}

async function ensureOwnerMembership(
  admin: SupabaseClient,
  userId: string
): Promise<void> {
  const { data: memberships, error } = await admin
    .from("organization_memberships")
    .select("id, organization_id, role_id, status")
    .eq("user_id", userId)
    .eq("status", "active")
    .limit(1);

  if (error) {
    throw new Error(`Failed to read dev E2E memberships: ${error.message}`);
  }

  const membership = (memberships?.[0] as MembershipRow | undefined) ?? null;

  if (membership) {
    if (membership.role_id !== "owner") {
      const { error: updateError } = await admin
        .from("organization_memberships")
        .update({ role_id: "owner", status: "active" })
        .eq("id", membership.id);

      if (updateError) {
        throw new Error(
          `Failed to promote dev E2E membership: ${updateError.message}`
        );
      }
    }

    await ensureWorkspaceDefaults(admin, membership.organization_id);
    return;
  }

  const organizationId = await findOrCreateDevOrganization(admin, userId);
  const { error: insertError } = await admin
    .from("organization_memberships")
    .upsert(
      {
        organization_id: organizationId,
        user_id: userId,
        role_id: "owner",
        status: "active",
      },
      { onConflict: "organization_id,user_id" }
    );

  if (insertError) {
    throw new Error(`Failed to create dev E2E membership: ${insertError.message}`);
  }

  await ensureWorkspaceDefaults(admin, organizationId);
}

async function checkInventoryAdjustmentOperationSupport(): Promise<boolean | null> {
  loadLocalEnv();

  if (!canProvisionAuthUser()) return null;

  const credentials = getAuthCredentials();
  if (!credentials) return null;

  const admin = createClient(
    requireEnv("NEXT_PUBLIC_SUPABASE_URL"),
    requireEnv("SUPABASE_SERVICE_ROLE_KEY"),
    {
      auth: {
        autoRefreshToken: false,
        persistSession: false,
      },
    }
  );
  const user = await findUserByEmail(admin, credentials.email);
  if (!user) return null;

  const { data: memberships, error: membershipError } = await admin
    .from("organization_memberships")
    .select("organization_id")
    .eq("user_id", user.id)
    .eq("status", "active")
    .limit(1);

  if (membershipError) return null;

  const workspaceId = memberships?.[0]?.organization_id as string | undefined;
  if (!workspaceId) return null;

  const { data, error } = await admin
    .from("operations")
    .insert({
      workspace_id: workspaceId,
      type: "inventory_adjustment",
      operation_date: "2099-01-01",
      comment: "E2E schema probe",
    })
    .select("id")
    .single();

  if (error) {
    return error.message.includes("operations_type_check") ? false : null;
  }

  if (data?.id) {
    await admin.from("operations").delete().eq("id", data.id);
  }

  return true;
}

async function findOrCreateDevOrganization(
  admin: SupabaseClient,
  userId: string
): Promise<string> {
  const organizationName =
    process.env.E2E_DEV_ORG_NAME?.trim() || DEFAULT_DEV_ORG_NAME;
  const { data: organizations, error } = await admin
    .from("organizations")
    .select("id")
    .eq("created_by", userId)
    .eq("name", organizationName)
    .limit(1);

  if (error) {
    throw new Error(`Failed to read dev E2E organization: ${error.message}`);
  }

  const organizationId = organizations?.[0]?.id as string | undefined;
  if (organizationId) return organizationId;

  const { data, error: insertError } = await admin
    .from("organizations")
    .insert({ name: organizationName, created_by: userId })
    .select("id")
    .single();

  if (insertError || !data?.id) {
    throw new Error(
      `Failed to create dev E2E organization: ${insertError?.message}`
    );
  }

  return data.id as string;
}

async function ensureWorkspaceDefaults(
  admin: SupabaseClient,
  organizationId: string
): Promise<void> {
  const { error: settingsError } = await admin
    .from("workspace_settings")
    .upsert({ workspace_id: organizationId }, { onConflict: "workspace_id" });

  if (settingsError) {
    throw new Error(
      `Failed to ensure dev E2E workspace settings: ${settingsError.message}`
    );
  }

  const { data: defaultDefectWarehouses, error: readWarehouseError } = await admin
    .from("warehouses")
    .select("id, name")
    .eq("workspace_id", organizationId)
    .eq("is_default_defect", true)
    .limit(10);

  if (readWarehouseError) {
    throw new Error(
      `Failed to read dev E2E defect warehouse: ${readWarehouseError.message}`
    );
  }

  const defaultRows = (defaultDefectWarehouses || []) as WarehouseRow[];
  const helperCreatedDefault = defaultRows.find((row) => row.name === "Defect");
  const hasOtherDefault = defaultRows.some((row) => row.name !== "Defect");

  if (helperCreatedDefault && hasOtherDefault) {
    const { error: updateWarehouseError } = await admin
      .from("warehouses")
      .update({ is_default_defect: false })
      .eq("id", helperCreatedDefault.id);

    if (updateWarehouseError) {
      throw new Error(
        `Failed to dedupe dev E2E defect warehouse: ${updateWarehouseError.message}`
      );
    }
  }

  if (defaultRows.length > 0) {
    return;
  }

  const { error: warehouseError } = await admin
    .from("warehouses")
    .upsert(
      {
        workspace_id: organizationId,
        name: "Defect",
        is_default_defect: true,
      },
      { onConflict: "workspace_id,name" }
    );

  if (warehouseError) {
    throw new Error(
      `Failed to ensure dev E2E defect warehouse: ${warehouseError.message}`
    );
  }
}
