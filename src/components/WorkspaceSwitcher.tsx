"use client";

import { useId } from "react";
import { useWorkspace } from "@/contexts/WorkspaceContext";
import { useI18n } from "@/i18n/context";

export default function WorkspaceSwitcher() {
  const { t } = useI18n();
  const id = useId();
  const { me, switching, switchError, switchWorkspace } = useWorkspace();
  const active = me.memberships.find((item) => item.organizationId === me.activeWorkspaceId)!;
  const name = active.organizationName || active.organizationId;

  return (
    <div className="min-w-0 space-y-1.5 px-4 pb-4">
      <label htmlFor={me.memberships.length > 1 ? id : undefined} className="block text-xs text-muted-foreground">
        {t.workspaceLabel}
      </label>
      {me.memberships.length > 1 ? (
        <select
          id={id}
          value={me.activeWorkspaceId!}
          disabled={switching}
          title={name}
          onChange={(event) => void switchWorkspace(event.target.value)}
          className="h-9 w-full min-w-0 truncate rounded-md border border-input bg-background px-2 text-sm disabled:opacity-60"
        >
          {me.memberships.map((membership) => (
            <option key={membership.organizationId} value={membership.organizationId}>
              {membership.organizationName || membership.organizationId}
            </option>
          ))}
        </select>
      ) : <p className="truncate text-sm font-medium" title={name}>{name}</p>}
      {switching && <p role="status" className="text-xs text-muted-foreground">{t.workspaceSwitching}</p>}
      {switchError && <p role="alert" className="text-xs text-destructive">{switchError}</p>}
    </div>
  );
}
