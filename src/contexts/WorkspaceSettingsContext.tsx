"use client";

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
  refetch: () => void;
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
  refetch: () => {},
});

export function WorkspaceSettingsProvider({
  children,
}: {
  children: React.ReactNode;
}) {
  const [settings, setSettings] =
    useState<WorkspaceSettingsResponse>(DEFAULT_SETTINGS);
  const [loading, setLoading] = useState(true);

  const fetchSettings = useCallback(async () => {
    try {
      const res = await fetch("/api/settings", { cache: "no-store" });
      if (res.ok) {
        const data = await res.json();
        setSettings(data);
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  return (
    <WorkspaceSettingsContext.Provider
      value={{ settings, loading, refetch: fetchSettings }}
    >
      {children}
    </WorkspaceSettingsContext.Provider>
  );
}

export function useWorkspaceSettings() {
  return useContext(WorkspaceSettingsContext);
}
