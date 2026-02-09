"use client";

import Link from "next/link";
import { FormEvent, useEffect, useMemo, useState } from "react";

interface Membership {
  organizationId: string;
  organizationName: string;
  role: string;
}

interface MeResponse {
  user: { id: string; email: string | null };
  profile: { displayName: string | null };
  memberships: Membership[];
}

const MANAGER_ROLES = new Set(["owner", "admin"]);

export default function TeamPage() {
  const [me, setMe] = useState<MeResponse | null>(null);
  const [workspaceId, setWorkspaceId] = useState("");
  const [inviteEmail, setInviteEmail] = useState("");
  const [role, setRole] = useState("member");
  const [loading, setLoading] = useState(true);
  const [inviting, setInviting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    async function loadMe() {
      setLoading(true);
      setError(null);

      try {
        const response = await fetch("/api/auth/me", { cache: "no-store" });
        const data = (await response.json()) as MeResponse | { error?: string };

        if (!active) {
          return;
        }

        if (!response.ok) {
          setError("error" in data && data.error ? data.error : "Failed to load account");
          return;
        }

        const payload = data as MeResponse;
        setMe(payload);

        if (payload.memberships.length > 0) {
          setWorkspaceId(payload.memberships[0].organizationId);
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    }

    void loadMe();

    return () => {
      active = false;
    };
  }, []);

  const activeMembership = useMemo(() => {
    if (!me) {
      return null;
    }

    return me.memberships.find((item) => item.organizationId === workspaceId) || null;
  }, [me, workspaceId]);

  const canInvite = Boolean(activeMembership && MANAGER_ROLES.has(activeMembership.role));

  async function handleInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!canInvite) {
      return;
    }

    setInviting(true);
    setError(null);
    setSuccess(null);

    try {
      const response = await fetch("/api/auth/invite", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          email: inviteEmail,
          role,
          workspaceId,
        }),
      });

      const data = (await response.json()) as { error?: string };

      if (!response.ok) {
        setError(data.error || "Failed to send invite");
        return;
      }

      setSuccess("Invitation sent.");
      setInviteEmail("");
      setRole("member");
    } finally {
      setInviting(false);
    }
  }

  return (
    <main className="mx-auto min-h-screen w-full max-w-3xl px-6 py-10">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Team</h1>
        <Link className="text-sm underline" href="/">
          Back to dashboard
        </Link>
      </div>

      {loading ? (
        <p className="text-sm text-muted-foreground">Loading...</p>
      ) : null}

      {error ? <p className="mb-4 text-sm text-red-600">{error}</p> : null}

      {me ? (
        <section className="rounded-lg border border-border bg-card p-6 shadow-sm">
          <h2 className="text-lg font-medium">Organization access</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Select an organization and invite users.
          </p>

          <div className="mt-4">
            <label className="mb-1 block text-sm font-medium" htmlFor="workspace-id">
              Organization
            </label>
            <select
              id="workspace-id"
              value={workspaceId}
              onChange={(event) => setWorkspaceId(event.target.value)}
              className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
            >
              {me.memberships.map((item) => (
                <option key={item.organizationId} value={item.organizationId}>
                  {item.organizationName || item.organizationId} ({item.role})
                </option>
              ))}
            </select>
          </div>

          <form className="mt-6 space-y-4" onSubmit={handleInvite}>
            <div>
              <label className="mb-1 block text-sm font-medium" htmlFor="invite-email">
                User email
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
              <label className="mb-1 block text-sm font-medium" htmlFor="invite-role">
                Role
              </label>
              <select
                id="invite-role"
                value={role}
                onChange={(event) => setRole(event.target.value)}
                className="h-10 w-full rounded-md border border-input bg-background px-3 text-sm"
              >
                <option value="member">member</option>
                <option value="admin">admin</option>
              </select>
            </div>

            {activeMembership && !canInvite ? (
              <p className="text-sm text-amber-700">
                Your role in this organization is {activeMembership.role}. Invites require owner or admin.
              </p>
            ) : null}

            {success ? <p className="text-sm text-emerald-700">{success}</p> : null}

            <button
              type="submit"
              disabled={!canInvite || inviting}
              className="h-10 rounded-md bg-foreground px-4 text-sm font-medium text-background disabled:opacity-60"
            >
              {inviting ? "Sending..." : "Send invite"}
            </button>
          </form>
        </section>
      ) : null}
    </main>
  );
}
