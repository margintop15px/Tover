"use client";

import { useEffect, useRef, useState } from "react";
import { useWorkspace } from "@/contexts/WorkspaceContext";
import { useI18n } from "@/i18n/context";
import { useEmailCooldown } from "@/hooks/use-email-cooldown";
import { workspaceFetch } from "@/lib/workspace-fetch";
import { teamErrorMessage } from "@/lib/team-error-message";
import type { TeamResponse } from "@/types/team";
import { Button } from "@/components/ui/button";
import InviteForm from "@/components/InviteForm";

export default function TeamPanel() {
  const { me } = useWorkspace();
  const { t, locale } = useI18n();
  const role = me.memberships.find((m) => m.organizationId === me.activeWorkspaceId)?.role;
  const canManage = role === "owner" || role === "admin";
  const [team, setTeam] = useState<TeamResponse | null>(null);
  const [revision, setRevision] = useState(0);
  const [loadError, setLoadError] = useState(false);
  const [busy, setBusy] = useState<Record<string, string>>({});
  const inFlight = useRef(new Set<string>());
  const [messages, setMessages] = useState<Record<string, { error: boolean; text: string }>>({});
  const cooldown = useEmailCooldown();
  const refresh = () => setRevision((value) => value + 1);

  useEffect(() => {
    if (!canManage) return;
    const controller = new AbortController();
    workspaceFetch("/api/auth/team", { signal: controller.signal }).then(async (response) => {
      if (!response.ok) throw new Error("Could not load team");
      const data: TeamResponse = await response.json();
      if (!controller.signal.aborted) { setTeam(data); setLoadError(false); }
    }).catch(() => { if (!controller.signal.aborted) setLoadError(true); });
    return () => controller.abort();
  }, [canManage, me.activeWorkspaceId, revision]);

  async function act(key: string, path: string, action: "recovery" | "resend" | "cancel") {
    if (inFlight.current.has(key)) return;
    inFlight.current.add(key);
    setBusy((previous) => ({ ...previous, [key]: action }));
    setMessages((previous) => ({ ...previous, [key]: { error: false, text: "" } }));
    if (action !== "cancel") cooldown.start(key);
    try {
      const response = await workspaceFetch(path, { method: "POST" });
      const data = await response.json();
      if (data.retryAfter) cooldown.start(key, Math.max(60, Number(data.retryAfter) || 60));
      setMessages((previous) => ({ ...previous, [key]: {
        error: !response.ok,
        text: response.ok
          ? action === "cancel" ? t.teamInvitationCanceled : action === "recovery" ? t.recoveryEmailSent
            : data.delivery === "magiclink" ? t.existingUserInvitationSent : t.invitationSent
          : teamErrorMessage(data.code, t),
      } }));
    } catch {
      setMessages((previous) => ({ ...previous, [key]: {
        error: true, text: action === "cancel" ? t.teamCancelUnconfirmed : t.emailDeliveryUnconfirmed,
      } }));
    } finally {
      inFlight.current.delete(key);
      setBusy((previous) => ({ ...previous, [key]: "" }));
      refresh();
    }
  }

  const date = (value: string | null) => value ? new Date(value).toLocaleString(locale) : t.teamNever;
  const roleName = (value: string) => value === "owner" ? t.teamOwner : value === "admin" ? t.adminRole : t.memberRole;
  const statuses = { active: t.teamActive, invited: t.teamInvited, suspended: t.teamSuspended, pending: t.teamPending, expired: t.teamExpired };

  return (
    <div className="min-w-0 space-y-6">
      <div className="max-w-lg"><InviteForm onChange={refresh} /></div>
      {canManage && <>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">{t.teamTitle}</h2>
          <Button variant="outline" onClick={refresh}>{t.teamRefresh}</Button>
        </div>
        {loadError && <p role="alert" className="text-sm text-destructive">{t.teamLoadFailed}</p>}
        {!team && !loadError && <p role="status">{t.loading}</p>}
        {Object.entries(messages).filter(([, message]) => message.text).map(([key, message]) => (
          <p key={key} role={message.error ? "alert" : "status"} className={`text-sm [overflow-wrap:anywhere] ${message.error ? "text-destructive" : "text-emerald-700"}`}>
            {key}: {message.text}
          </p>
        ))}
        {team && <>
          <section id="team-members" className="space-y-3" aria-labelledby="team-members-title">
            <h3 id="team-members-title" className="font-medium">{t.teamMembers} ({team.members.length})</h3>
            {team.members.length === 0 && <p className="text-sm text-muted-foreground">{t.teamNoMembers}</p>}
            {team.members.map((member) => {
              const key = member.email || member.userId;
              const remaining = cooldown.remaining(key, member.recoveryRequestedAt);
              return <article key={member.userId} className="min-w-0 rounded-lg border p-4 [overflow-wrap:anywhere]">
                <p className="font-medium">{member.name || member.email || member.userId}</p>
                {member.name && <p className="text-sm text-muted-foreground">{member.email}</p>}
                <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                  <div><dt className="text-muted-foreground">{t.roleLabel}</dt><dd>{roleName(member.role)}</dd></div>
                  <div><dt className="text-muted-foreground">{t.status}</dt><dd>{statuses[member.status]}</dd></div>
                  <div><dt className="text-muted-foreground">{t.teamEmailStatus}</dt><dd>{member.emailConfirmedAt ? t.teamVerified : t.teamUnverified}</dd></div>
                  <div><dt className="text-muted-foreground">{t.teamLastSignIn}</dt><dd>{date(member.lastSignInAt)}</dd></div>
                </dl>
                <div className="mt-4 flex flex-wrap items-center gap-3">
                  <Button variant="outline" disabled={!!busy[key] || remaining > 0 || member.status !== "active" || !member.email}
                    onClick={() => act(key, `/api/auth/members/${member.userId}/recovery`, "recovery")}>
                    {busy[key] === "recovery" ? t.sending : t.teamSendRecovery}
                  </Button>
                  {remaining > 0 && <span className="text-sm text-muted-foreground">{t.emailCooldown(remaining)}</span>}
                </div>
              </article>;
            })}
          </section>
          <section id="team-invitations" className="space-y-3" aria-labelledby="team-invitations-title">
            <h3 id="team-invitations-title" className="font-medium">{t.teamInvitations} ({team.invitations.length})</h3>
            {team.invitations.length === 0 && <p className="text-sm text-muted-foreground">{t.teamNoInvitations}</p>}
            {team.invitations.map((invite) => {
              const remaining = cooldown.remaining(invite.email, invite.lastRequestedAt);
              return <article key={invite.email} className="min-w-0 rounded-lg border p-4 [overflow-wrap:anywhere]">
                <p className="font-medium">{invite.email}</p>
                <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
                  <div><dt className="text-muted-foreground">{t.roleLabel}</dt><dd>{roleName(invite.role)}</dd></div>
                  <div><dt className="text-muted-foreground">{t.status}</dt><dd>{statuses[invite.status]}</dd></div>
                  <div><dt className="text-muted-foreground">{t.teamCreated}</dt><dd>{date(invite.createdAt)}</dd></div>
                  <div><dt className="text-muted-foreground">{t.teamExpires}</dt><dd>{date(invite.expiresAt)}</dd></div>
                </dl>
                <div className="mt-4 flex flex-wrap items-center gap-3">
                  <Button variant="outline" disabled={!!busy[invite.email] || remaining > 0}
                    onClick={() => act(invite.email, `/api/auth/invites/${invite.id}/resend`, "resend")}>
                    {busy[invite.email] === "resend" ? t.sending : t.teamResend}
                  </Button>
                  <Button variant="ghost" disabled={!!busy[invite.email]}
                    onClick={() => act(invite.email, `/api/auth/invites/${invite.id}/cancel`, "cancel")}>
                    {busy[invite.email] === "cancel" ? t.updating : t.teamCancelInvitation}
                  </Button>
                  {remaining > 0 && <span className="text-sm text-muted-foreground">{t.emailCooldown(remaining)}</span>}
                </div>
              </article>;
            })}
          </section>
        </>}
      </>}
    </div>
  );
}
