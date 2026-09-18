"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { isAuthError } from "@supabase/supabase-js";
import { getSafeNextPath } from "@/lib/auth-redirect";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";
import { useI18n } from "@/i18n/context";
import type { TranslationKeys } from "@/i18n/en";

async function completeAuth(t: TranslationKeys): Promise<string> {
  const url = new URL(window.location.href);
  const fragment = new URLSearchParams(url.hash.slice(1));
  const code = url.searchParams.get("code");
  const accessToken = fragment.get("access_token");
  const refreshToken = fragment.get("refresh_token");
  const type = fragment.get("type");
  const callbackError = fragment.get("error_description") || url.searchParams.get("error_description") ||
    fragment.get("error") || url.searchParams.get("error");

  // Consume credentials explicitly: SSR's PKCE client rejects invite token fragments.
  // Remove them before client initialization to avoid an automatic second exchange.
  url.hash = "";
  url.searchParams.delete("code");
  window.history.replaceState(window.history.state, "", url.pathname + url.search);

  if (callbackError) throw new Error(callbackError);

  const supabase = createBrowserSupabaseClient();
  if (accessToken || refreshToken) {
    if (!accessToken || !refreshToken) {
      throw new Error(t.authLinkIncomplete);
    }
    const { error } = await supabase.auth.setSession({
      access_token: accessToken,
      refresh_token: refreshToken,
    });
    if (error) throw error;
  } else if (code) {
    const { error } = await supabase.auth.exchangeCodeForSession(code);
    if (error) {
      if (isAuthError(error) && error.code === "pkce_code_verifier_not_found") {
        throw new Error(t.authSameBrowser);
      }
      throw error;
    }
  } else {
    throw new Error(t.authLinkIncomplete);
  }

  const { data: { session }, error } = await supabase.auth.getSession();
  if (error) throw error;
  if (!session) throw new Error(t.authSessionFailed);

  return type === "invite" || type === "recovery"
    ? "/reset-password"
    : getSafeNextPath(url.searchParams.get("next"));
}

export default function AuthCallbackPage() {
  const { t } = useI18n();
  const completion = useRef<Promise<string> | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    // React Strict Mode replays effects; an auth code must only be exchanged once.
    completion.current ??= completeAuth(t);
    void completion.current.then((nextPath) => {
      // A fresh request ensures middleware receives the newly saved session cookies.
      if (active) window.location.replace(nextPath);
    }).catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : t.authSessionFailed);
    });

    return () => {
      active = false;
    };
  }, [t]);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md items-center px-6 py-10">
      <div className="w-full rounded-lg border border-border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">{t.authCompleting}</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {t.authFinalizing}
        </p>

        {error ? (
          <div className="mt-4 space-y-3 text-sm">
            <p role="alert" className="text-red-600">{error}</p>
            <Link className="block underline" href="/forgot-password">{t.resetOrSetPassword}</Link>
            <Link className="block underline" href="/login">{t.backToLogin}</Link>
          </div>
        ) : null}
      </div>
    </main>
  );
}
