import type { TranslationKeys } from "@/i18n/en";

export function teamErrorMessage(code: string | undefined, t: TranslationKeys): string {
  switch (code) {
    case "MEMBER_EXISTS": return t.teamMemberExists;
    case "INVITE_EXISTS": return t.teamInviteExists;
    case "MEMBER_INACTIVE": return t.teamMemberInactive;
    case "NOT_FOUND":
    case "INVITE_CHANGED": return t.teamRecordChanged;
    case "RATE_LIMITED": return t.emailRateLimited;
    case "INVALID_INPUT": return t.teamInvalidInput;
    case "DELIVERY_UNCONFIRMED": return t.emailDeliveryUnconfirmed;
    default: return t.teamActionFailed;
  }
}
