export interface WorkspaceMembership {
  organizationId: string;
  organizationName: string;
  role: string;
}

export interface AuthMeResponse {
  user: { id: string; email: string | null };
  profile: { displayName: string | null };
  memberships: WorkspaceMembership[];
  activeWorkspaceId: string | null;
}
