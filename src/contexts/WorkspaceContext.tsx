"use client";

import { createContext, useContext, useEffect, useRef, useState } from "react";
import type { AuthMeResponse } from "@/types/auth";
import { useI18n } from "@/i18n/context";
import { setRequestWorkspace, workspaceFetch } from "@/lib/workspace-fetch";
import { createBrowserSupabaseClient } from "@/lib/supabase-browser";
import { Button } from "@/components/ui/button";

interface WorkspaceContextValue {
  me: AuthMeResponse;
  switching: boolean;
  switchError: string | null;
  switchWorkspace: (workspaceId: string) => Promise<void>;
}

const WorkspaceContext = createContext<WorkspaceContextValue | null>(null);
const storageKey = (userId: string) => `tover-workspace-change-${userId}`;

async function loadMe(): Promise<AuthMeResponse> {
  const response = await fetch("/api/auth/me", { cache: "no-store" });
  if (!response.ok) throw new Error("Could not load workspaces");
  return response.json();
}

export function WorkspaceProvider({ children }: { children: React.ReactNode }) {
  const { t } = useI18n();
  const [me, setMe] = useState<AuthMeResponse | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [switching, setSwitching] = useState(false);
  const switchInProgress = useRef(false);
  const [switchError, setSwitchError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadMe().then((payload) => {
      if (cancelled) return;
      setRequestWorkspace(payload.activeWorkspaceId);
      setMe(payload);
    }).catch(() => {
      if (!cancelled) setLoadError(true);
    });
    return () => {
      cancelled = true;
      setRequestWorkspace(null);
    };
  }, [attempt]);

  const userId = me?.user.id;
  const activeWorkspaceId = me?.activeWorkspaceId;
  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    let checking = false;
    const onStorage = (event: StorageEvent) => {
      if (event.key === storageKey(userId) && event.newValue) {
        window.location.assign("/operations");
      }
    };
    const checkWorkspace = async () => {
      if (document.visibilityState === "hidden" || checking || switchInProgress.current) return;
      checking = true;
      try {
        const payload = await loadMe();
        if (cancelled) return;
        if (payload.user.id !== userId || payload.activeWorkspaceId !== activeWorkspaceId) {
          window.location.assign("/operations");
        } else {
          setMe(payload);
        }
      } catch {
        // A temporary network failure must not discard the current page.
        // Every API request still checks membership and the workspace snapshot.
      } finally {
        checking = false;
      }
    };
    window.addEventListener("storage", onStorage);
    window.addEventListener("focus", checkWorkspace);
    window.addEventListener("pageshow", checkWorkspace);
    document.addEventListener("visibilitychange", checkWorkspace);
    return () => {
      cancelled = true;
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("focus", checkWorkspace);
      window.removeEventListener("pageshow", checkWorkspace);
      document.removeEventListener("visibilitychange", checkWorkspace);
    };
  }, [userId, activeWorkspaceId]);

  async function switchWorkspace(workspaceId: string) {
    if (!me?.activeWorkspaceId || workspaceId === me.activeWorkspaceId || switchInProgress.current) return;
    switchInProgress.current = true;
    setSwitching(true);
    setSwitchError(null);
    try {
      const response = await workspaceFetch("/api/auth/workspace", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ workspaceId }),
      });
      if (!response.ok) throw new Error("Could not switch workspace");
      try {
        localStorage.setItem(storageKey(me.user.id), JSON.stringify({ workspaceId, time: Date.now() }));
      } catch {
        // Tabs also check on focus when storage is unavailable.
      }
      window.location.assign("/operations");
    } catch {
      setSwitchError(t.workspaceSwitchFailed);
      switchInProgress.current = false;
      setSwitching(false);
    }
  }

  if (!me || !me.activeWorkspaceId) {
    return (
      <div className="space-y-4 p-6">
        <p role={loadError || me ? "alert" : "status"}>
          {loadError ? t.workspaceLoadFailed : me ? t.workspaceAccessRequired : t.loading}
        </p>
        {(loadError || me) && <Button variant="outline" onClick={() => {
          setLoadError(false);
          setMe(null);
          setAttempt((value) => value + 1);
        }}>{t.workspaceRetry}</Button>}
        {me && <Button variant="ghost" onClick={async () => {
          await createBrowserSupabaseClient().auth.signOut();
          window.location.assign("/login");
        }}>{t.logOut}</Button>}
      </div>
    );
  }

  return (
    <WorkspaceContext.Provider value={{ me, switching, switchError, switchWorkspace }}>
      {children}
    </WorkspaceContext.Provider>
  );
}

export function useWorkspace() {
  const context = useContext(WorkspaceContext);
  if (!context) throw new Error("WorkspaceProvider is required");
  return context;
}
