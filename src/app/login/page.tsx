"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";
import { useI18n } from "@/i18n/context";

function getSafeNextPath(path: string | null): string {
  if (!path || !path.startsWith("/")) {
    return "/";
  }

  return path;
}

export default function LoginPage() {
  const router = useRouter();
  const { t } = useI18n();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setLoading(true);
    setError(null);

    try {
      const supabase = createBrowserSupabaseClient();
      const { error: signInError } = await supabase.auth.signInWithPassword({
        email,
        password,
      });

      if (signInError) {
        setError(signInError.message);
        return;
      }

      const nextPath = getSafeNextPath(
        new URLSearchParams(window.location.search).get("next")
      );

      router.push(nextPath);
      router.refresh();
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md items-center px-6 py-10">
      <div className="w-full rounded-lg border border-border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">{t.loginTitle}</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {t.loginSubtitle}
        </p>

        <form className="mt-6 space-y-4" onSubmit={handleSubmit}>
          <div>
            <label className="mb-1 block text-sm font-medium" htmlFor="email">
              {t.email}
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              required
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium" htmlFor="password">
              {t.password}
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
            />
          </div>

          {error ? <p className="text-sm text-red-600">{error}</p> : null}

          <button
            type="submit"
            disabled={loading}
            className="h-10 w-full rounded-md bg-foreground text-sm font-medium text-background disabled:opacity-60"
          >
            {loading ? t.signingIn : t.logIn}
          </button>
        </form>

        <div className="mt-4 flex items-center justify-between text-sm">
          <Link className="underline" href="/forgot-password">
            {t.forgotPassword}
          </Link>
          <Link className="underline" href="/signup">
            {t.createAccount}
          </Link>
        </div>
      </div>
    </main>
  );
}
