"use client";

import { useCallback, useEffect, useState } from "react";
import { usePathname, useRouter } from "next/navigation";
import AppSidebar from "@/components/AppSidebar";
import { WorkspaceSettingsProvider } from "@/contexts/WorkspaceSettingsContext";
import { useI18n } from "@/i18n/context";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";

const PUBLIC_PREFIXES = ["/login", "/signup", "/forgot-password", "/reset-password", "/auth/"];

export default function AppShell({ children }: { children: React.ReactNode }) {
  const { t } = useI18n();
  const pathname = usePathname();
  const router = useRouter();
  const isPublic = PUBLIC_PREFIXES.some((p) => pathname.startsWith(p));
  const bypassShell = isPublic || pathname === "/";
  const [verifiedAuth, setVerifiedAuth] = useState<{
    pathname: string;
    authorized: boolean;
  } | null>(null);

  const redirectToLogin = useCallback(() => {
    const next =
      typeof window === "undefined"
        ? pathname
        : `${window.location.pathname}${window.location.search}`;
    router.replace(`/login?next=${encodeURIComponent(next)}`);
    router.refresh();
  }, [pathname, router]);

  useEffect(() => {
    if (bypassShell) {
      return;
    }

    let cancelled = false;
    const supabase = createBrowserSupabaseClient();

    supabase.auth.getUser().then(({ data, error }) => {
      if (cancelled) return;

      if (error || !data.user) {
        setVerifiedAuth({ pathname, authorized: false });
        redirectToLogin();
        return;
      }

      setVerifiedAuth({ pathname, authorized: true });
    });

    const {
      data: { subscription },
    } = supabase.auth.onAuthStateChange((event, session) => {
      if (cancelled || bypassShell) return;
      if (event === "SIGNED_OUT" || (event === "TOKEN_REFRESHED" && !session)) {
        setVerifiedAuth({ pathname, authorized: false });
        redirectToLogin();
      }
    });

    return () => {
      cancelled = true;
      subscription.unsubscribe();
    };
  }, [bypassShell, pathname, redirectToLogin]);

  if (bypassShell) return <>{children}</>;

  return (
    <WorkspaceSettingsProvider>
      <AppSidebar>
        {!verifiedAuth?.authorized || verifiedAuth.pathname !== pathname ? (
          <div className="p-6 text-muted-foreground">{t.loading}</div>
        ) : (
          children
        )}
      </AppSidebar>
    </WorkspaceSettingsProvider>
  );
}
