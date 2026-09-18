"use client";

import { workspaceFetch } from "@/lib/workspace-fetch";
import { readJsonResponse, reportRequestFailure } from "@/lib/api-response";
import { LoadError } from "@/components/LoadError";
import { useI18n } from "@/i18n/context";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import type { WorkspaceSettingsResponse } from "@/types/inventory";

interface WorkspaceSettingsContextValue {
  settings: WorkspaceSettingsResponse;
  loading: boolean;
  refetch: () => Promise<void>;
}

const DEFAULT_SETTINGS: WorkspaceSettingsResponse = {
  currency: "EUR",
  categoryRequired: false,
  defaultCategoryId: null,
  storeRequired: false,
  defaultStoreId: null,
};

const WorkspaceSettingsContext = createContext<WorkspaceSettingsContextValue>({
  settings: DEFAULT_SETTINGS,
  loading: true,
  refetch: async () => {},
});

export function WorkspaceSettingsProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const { t } = useI18n();
  const [settings, setSettings] =
    useState<WorkspaceSettingsResponse>(DEFAULT_SETTINGS);
  const [loading, setLoading] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);

  const fetchSettings = useCallback(async () => {
    setLoading(true);
    try {
      const res = await workspaceFetch("/api/settings", { cache: "no-store" });
      const data = await readJsonResponse<WorkspaceSettingsResponse>(res);
      if (!/^[A-Z]{3}$/.test(data.currency) || typeof data.categoryRequired !== "boolean" || typeof data.storeRequired !== "boolean") {
        throw new Error("Invalid workspace settings response");
      }
      setSettings(data);
      setLoaded(true);
      setFailed(false);
    } catch (error) {
      reportRequestFailure(error, "load_workspace_settings");
      setFailed(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  if (!loaded) {
    return <div className="p-6">{failed
      ? <LoadError message={t.settingsLoadFailed} onRetry={fetchSettings} loading={loading} />
      : <p className="text-muted-foreground">{t.loading}</p>}</div>;
  }

  return (
    <WorkspaceSettingsContext.Provider
      value={{ settings, loading, refetch: fetchSettings }}
    >
      {failed && <div className="px-6"><LoadError message={t.settingsRefreshFailed} onRetry={fetchSettings} loading={loading} /></div>}
      {children}
    </WorkspaceSettingsContext.Provider>
  );
}

export function useWorkspaceSettings() {
  return useContext(WorkspaceSettingsContext);
}
