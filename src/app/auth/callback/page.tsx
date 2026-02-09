"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";

function getSafeNextPath(path: string | null): string {
  if (!path || !path.startsWith("/")) {
    return "/";
  }

  return path;
}

export default function AuthCallbackPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    async function completeAuth() {
      const supabase = createBrowserSupabaseClient();
      const nextPath = getSafeNextPath(
        new URLSearchParams(window.location.search).get("next")
      );

      const {
        data: { session },
        error: sessionError,
      } = await supabase.auth.getSession();

      if (!active) {
        return;
      }

      if (sessionError) {
        setError(sessionError.message);
        return;
      }

      if (!session) {
        setError("Could not establish a session. Please try logging in again.");
        return;
      }

      router.replace(nextPath);
      router.refresh();
    }

    void completeAuth();

    return () => {
      active = false;
    };
  }, [router]);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md items-center px-6 py-10">
      <div className="w-full rounded-lg border border-border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">Completing sign in</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Finalizing your authentication session.
        </p>

        {error ? <p className="mt-4 text-sm text-red-600">{error}</p> : null}
      </div>
    </main>
  );
}
