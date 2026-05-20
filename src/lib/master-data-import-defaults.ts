import type { SupabaseClient } from "@supabase/supabase-js";

export type ImportDefaultTable =
  | "categories"
  | "stores"
  | "warehouses"
  | "suppliers";

export async function applyImportDefaultFlag(
  supabase: SupabaseClient,
  table: ImportDefaultTable,
  workspaceId: string,
  id: string,
  isImportDefault: boolean
) {
  if (isImportDefault) {
    const clearQuery = supabase
      .from(table)
      .update({ is_import_default: false })
      .eq("workspace_id", workspaceId)
      .neq("id", id);

    const { error: clearError } = await clearQuery;
    if (clearError) throw new Error(clearError.message);
  }

  const updateQuery = supabase
    .from(table)
    .update({ is_import_default: isImportDefault })
    .eq("workspace_id", workspaceId)
    .eq("id", id);

  const { error } = await updateQuery;
  if (error) throw new Error(error.message);
}
