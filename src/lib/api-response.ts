import * as Sentry from "@sentry/nextjs";

// Read endpoints must not turn an error response into an empty successful result.
export async function readJsonResponse<T>(response: Response): Promise<T> {
  if (!response.ok) throw new Error(`Request failed (HTTP ${response.status})`);
  const data = await response.json();
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new Error("Invalid API response");
  }
  return data as T;
}

export function reportRequestFailure(error: unknown, action: string) {
  // Use fixed action names only: never include credentials, drafts or bodies.
  Sentry.captureException(error, { tags: { handled_by: "request_ui", action } });
}
