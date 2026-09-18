export interface TeamMember {
  userId: string;
  name: string | null;
  email: string | null;
  role: string;
  status: "active" | "invited" | "suspended";
  emailConfirmedAt: string | null;
  lastSignInAt: string | null;
  recoveryRequestedAt: string | null;
}

export interface TeamInvitation {
  id: string;
  email: string;
  role: string;
  status: "pending" | "expired";
  createdAt: string;
  expiresAt: string;
  lastRequestedAt: string;
}

export interface TeamResponse {
  members: TeamMember[];
  invitations: TeamInvitation[];
}
