import { redirect } from "next/navigation";

export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<{ code?: string; next?: string }>;
}) {
  const { code, next } = await searchParams;
  // Supabase's Site URL fallback can deliver a signup code to the root.
  if (typeof code === "string") {
    const query = new URLSearchParams({ code });
    if (typeof next === "string") query.set("next", next);
    redirect(`/auth/callback?${query}`);
  }

  redirect("/operations");
}
