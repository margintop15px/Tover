"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";
import { useI18n } from "@/i18n/context";

export default function SignupPage() {
  const router = useRouter();
  const { t } = useI18n();

  const [fullName, setFullName] = useState("");
  const [organizationName, setOrganizationName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (password !== confirmPassword) {
      setError(t.passwordsMismatch);
      return;
    }

    setLoading(true);
    setError(null);
    setSuccess(null);

    try {
      const supabase = createBrowserSupabaseClient();
      const emailRedirectTo = `${window.location.origin}/auth/callback`;

      const { data, error: signUpError } = await supabase.auth.signUp({
        email,
        password,
        options: {
          emailRedirectTo,
          data: {
            name: fullName,
            organization_name: organizationName,
          },
        },
      });

      if (signUpError) {
        setError(signUpError.message);
        return;
      }

      if (data.session) {
        router.push("/");
        router.refresh();
        return;
      }

      setSuccess(t.checkEmailConfirm);
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md items-center px-6 py-10">
      <div className="w-full rounded-lg border border-border bg-card p-6 shadow-sm">
        <h1 className="text-2xl font-semibold">{t.signupTitle}</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          {t.signupSubtitle}
        </p>

        <form className="mt-6 space-y-4" onSubmit={handleSubmit}>
          <div>
            <label className="mb-1 block text-sm font-medium" htmlFor="full-name">
              {t.fullName}
            </label>
            <input
              id="full-name"
              type="text"
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              required
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium" htmlFor="organization-name">
              {t.organizationName}
            </label>
            <input
              id="organization-name"
              type="text"
              value={organizationName}
              onChange={(event) => setOrganizationName(event.target.value)}
              required
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
            />
          </div>

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
              minLength={8}
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
            />
          </div>

          <div>
            <label className="mb-1 block text-sm font-medium" htmlFor="confirm-password">
              {t.confirmPassword}
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

          {error ? <p className="text-sm text-red-600">{error}</p> : null}
          {success ? <p className="text-sm text-emerald-700">{success}</p> : null}

          <button
            type="submit"
            disabled={loading}
            className="h-10 w-full rounded-md bg-foreground text-sm font-medium text-background disabled:opacity-60"
          >
            {loading ? t.creatingAccount : t.createAccount}
          </button>
        </form>

        <div className="mt-4 text-sm">
          <span>{t.alreadyHaveAccount} </span>
          <Link className="underline" href="/login">
            {t.logIn}
          </Link>
        </div>
      </div>
    </main>
  );
}
