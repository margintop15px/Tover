"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { isAuthRetryableFetchError } from "@supabase/supabase-js";
import { createEmailSupabaseClient } from "@/lib/supabase-email";
import { useEmailCooldown } from "@/hooks/use-email-cooldown";
import { useI18n } from "@/i18n/context";

export default function ForgotPasswordPage() {
  const { t } = useI18n();

  const [email, setEmail] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const cooldown = useEmailCooldown();
  const emailKey = email.trim().toLowerCase();
  const remaining = cooldown.remaining(emailKey);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (loading || remaining > 0) return;
    setLoading(true);
    setError(null);
    setSuccess(null);
    cooldown.start(emailKey);

    try {
      const supabase = createEmailSupabaseClient();
      const redirectTo = `${window.location.origin}/auth/callback?next=/reset-password`;

      const { error: resetError } = await supabase.auth.resetPasswordForEmail(
        emailKey,
        {
          redirectTo,
        }
      );

      if (resetError && resetError.code !== "user_not_found") {
        setError(isAuthRetryableFetchError(resetError) ? t.emailDeliveryUnconfirmed
          : resetError.status === 429 ? t.emailRateLimited : t.teamActionFailed);
        return;
      }

      setSuccess(t.recoveryEmailSent);
    } catch {
      setError(t.emailDeliveryUnconfirmed);
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md items-center px-6 py-10">
      <div className="w-full rounded-lg border border-border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">{t.recoverPasswordTitle}</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {t.recoverPasswordSubtitle}
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

          {error ? <p role="alert" className="text-sm text-red-600">{error}</p> : null}
          {success ? <p role="status" className="text-sm text-emerald-700">{success}</p> : null}

          <button
            type="submit"
            disabled={loading || remaining > 0}
            className="h-10 w-full rounded-md bg-foreground text-sm font-medium text-background disabled:opacity-60"
          >
            {loading ? t.sending : t.sendResetEmail}
          </button>
          {remaining > 0 && <p className="text-sm text-muted-foreground">{t.emailCooldown(remaining)}</p>}
        </form>

        <div className="mt-4 text-sm">
          <Link className="underline" href="/login">
            {t.backToLogin}
          </Link>
        </div>
      </div>
    </main>
  );
}
