"use client";

import { workspaceFetch } from "@/lib/workspace-fetch";

import { FormEvent, useState } from "react";
import { useWorkspace } from "@/contexts/WorkspaceContext";
import { useI18n } from "@/i18n/context";

const MANAGER_ROLES = new Set(["owner", "admin"]);

export default function InviteForm() {
  const { t } = useI18n();

  const { me } = useWorkspace();
  const [inviteEmail, setInviteEmail] = useState("");
  const [role, setRole] = useState("member");
  const [inviting, setInviting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const activeMembership = me.memberships.find(
    (item) => item.organizationId === me.activeWorkspaceId
  );

  const canInvite = Boolean(
    activeMembership && MANAGER_ROLES.has(activeMembership.role)
  );

  async function handleInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canInvite) return;

    setInviting(true);
    setError(null);
    setSuccess(null);

    try {
      const response = await workspaceFetch("/api/auth/invite", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email: inviteEmail, role }),
      });

      const data = (await response.json()) as { error?: string };

      if (!response.ok) {
        setError(data.error || t.failedToSendInvite);
        return;
      }

      setSuccess(t.invitationSent);
      setInviteEmail("");
      setRole("member");
    } finally {
      setInviting(false);
    }
  }

  return (
    <section className="rounded-lg border border-border bg-card p-6 shadow-sm">
      <h2 className="text-lg font-medium">{t.organizationAccess}</h2>
      <p className="mt-1 text-sm text-muted-foreground">
        {t.organizationAccessSubtitle}
      </p>

      <p className="mt-2 text-sm font-medium">
        {activeMembership?.organizationName || me.activeWorkspaceId}
      </p>

      <form className="mt-6 space-y-4" onSubmit={handleInvite}>
        <div>
          <label
            className="mb-1 block text-sm font-medium"
            htmlFor="invite-email"
          >
            {t.userEmail}
          </label>
          <input
            id="invite-email"
            type="email"
            value={inviteEmail}
            onChange={(event) => setInviteEmail(event.target.value)}
            required
            className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
          />
        </div>

        <div>
          <label
            className="mb-1 block text-sm font-medium"
            htmlFor="invite-role"
          >
            {t.roleLabel}
          </label>
          <select
            id="invite-role"
            value={role}
            onChange={(event) => setRole(event.target.value)}
            className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
          >
            <option value="member">{t.memberRole}</option>
            <option value="admin">{t.adminRole}</option>
          </select>
        </div>

        {activeMembership && !canInvite ? (
          <p className="text-sm text-amber-700">
            {t.roleInsufficientWarning(activeMembership.role)}
          </p>
        ) : null}

        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        {success ? (
          <p className="text-sm text-emerald-700">{success}</p>
        ) : null}

        <button
          type="submit"
          disabled={!canInvite || inviting}
          className="h-10 rounded-md bg-foreground px-4 text-sm font-medium text-background disabled:opacity-60"
        >
          {inviting ? t.sendingInvite : t.sendInvite}
        </button>
      </form>
    </section>
  );
}
