"use client";

import { usePathname } from "next/navigation";
import AppSidebar from "@/components/AppSidebar";
import { WorkspaceSettingsProvider } from "@/contexts/WorkspaceSettingsContext";

const PUBLIC_PREFIXES = ["/login", "/signup", "/forgot-password", "/reset-password", "/auth/"];

export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const isPublic = PUBLIC_PREFIXES.some((p) => pathname.startsWith(p));

  if (isPublic) return <>{children}</>;
  return (
    <WorkspaceSettingsProvider>
      <AppSidebar>{children}</AppSidebar>
    </WorkspaceSettingsProvider>
  );
}
