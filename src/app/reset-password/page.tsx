"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";
import { useI18n } from "@/i18n/context";

export default function ResetPasswordPage() {
  const { t } = useI18n();

  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [hasSession, setHasSession] = useState<boolean | null>(null);
  const [email, setEmail] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    async function loadSession() {
      const supabase = createBrowserSupabaseClient();
      try {
        const { data: { user }, error: userError } = await supabase.auth.getUser();
        if (!active) return;
        setHasSession(Boolean(user) && !userError);
        setEmail(user?.email || null);
      } catch {
        if (active) { setHasSession(false); setError(t.authNetworkError); }
      }
    }

    void loadSession();

    return () => {
      active = false;
    };
  }, [t]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading || !hasSession) return;

    if (password !== confirmPassword) {
      setError(t.passwordsMismatch);
      return;
    }

    setLoading(true);
    setError(null);
    setSuccess(null);

    try {
      const supabase = createBrowserSupabaseClient();
      const { error: updateError } = await supabase.auth.updateUser({
        password,
      });

      if (updateError) {
        if (updateError.status === 401 || updateError.status === 403 || updateError.code === "session_not_found") {
          setHasSession(false);
        }
        setError(updateError.message);
        return;
      }

      setSuccess(t.passwordUpdated);
      window.location.replace("/");
    } catch {
      setError(t.passwordUpdateUnconfirmed);
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md items-center px-6 py-10">
      <div className="w-full rounded-lg border border-border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">{t.setNewPasswordTitle}</h1>
        {email && <p className="mt-2 text-sm [overflow-wrap:anywhere]">{email}</p>}

        {hasSession === null ? (
          <p className="mt-4 text-sm text-muted-foreground">{t.loading}</p>
        ) : hasSession === false ? (
          <div className="mt-4 space-y-3 text-sm">
            <p role="alert" className="text-red-600">
              {t.recoverySessionExpired}
            </p>
            <Link className="underline" href="/forgot-password">
              {t.requestPasswordReset}
            </Link>
          </div>
        ) : (
          <form className="mt-6 space-y-4" onSubmit={handleSubmit}>
            <div>
              <label className="mb-1 block text-sm font-medium" htmlFor="password">
                {t.newPassword}
              </label>
              <input
                id="password"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                required
                minLength={8}
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              />
            </div>

            <div>
              <label
                className="mb-1 block text-sm font-medium"
                htmlFor="confirm-password"
              >
                {t.confirmNewPassword}
              </label>
              <input
                id="confirm-password"
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                required
                minLength={8}
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              />
            </div>

            {error ? <p role="alert" className="text-sm text-red-600">{error}</p> : null}
            {success ? <p className="text-sm text-emerald-700">{success}</p> : null}

            <button
              type="submit"
              disabled={loading}
              className="h-10 w-full rounded-md bg-foreground text-sm font-medium text-background disabled:opacity-60"
            >
              {loading ? t.updating : t.updatePassword}
            </button>
          </form>
        )}
        {hasSession && <Link className="mt-4 block text-sm underline" href="/forgot-password">{t.requestPasswordReset}</Link>}
        <Link className="mt-4 block text-sm underline" href="/login">{t.backToLogin}</Link>
      </div>
    </main>
  );
}
