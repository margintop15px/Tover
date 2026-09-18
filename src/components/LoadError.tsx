"use client";

import { Button } from "@/components/ui/button";
import { useI18n } from "@/i18n/context";

export function LoadError({ message, onRetry, loading = false }: {
  message?: string;
  onRetry: () => void;
  loading?: boolean;
}) {
  const { t } = useI18n();
  return (
    <div role="alert" className="my-3 flex min-w-0 flex-col items-start gap-3 rounded-md border border-destructive/40 p-3 text-sm sm:flex-row sm:items-center">
      <p className="min-w-0 text-destructive [overflow-wrap:anywhere] sm:flex-1">{message || t.dataLoadFailed}</p>
      <Button type="button" variant="outline" size="sm" onClick={onRetry} disabled={loading}>
        {loading ? t.loading : t.workspaceRetry}
      </Button>
    </div>
  );
}
