import type { Breadcrumb, ErrorEvent, init } from "@sentry/nextjs";

function withoutUrlParameters(value: string): string {
  return value.split(/[?#]/, 1)[0];
}

export function sanitizeBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb | null {
  // Console arguments and DOM labels can contain account or business data.
  if (breadcrumb.category === "console" || breadcrumb.category?.startsWith("ui.")) {
    return null;
  }
  if (breadcrumb.data) {
    for (const key of ["url", "from", "to"]) {
      if (typeof breadcrumb.data[key] === "string") {
        breadcrumb.data[key] = withoutUrlParameters(breadcrumb.data[key]);
      }
    }
  }
  return breadcrumb;
}

export function sanitizeErrorEvent(event: ErrorEvent): ErrorEvent {
  // Auth callbacks carry one-time codes in URLs. Keep route and method only.
  if (event.request) {
    event.request = {
      url: event.request.url && withoutUrlParameters(event.request.url),
      method: event.request.method,
    };
  }
  delete event.user;
  delete event.extra;
  event.breadcrumbs = event.breadcrumbs
    ?.map(sanitizeBreadcrumb)
    .filter((breadcrumb): breadcrumb is Breadcrumb => breadcrumb !== null);
  return event;
}

export const sentryOptions = {
  environment: process.env.NEXT_PUBLIC_SENTRY_ENVIRONMENT || process.env.NODE_ENV,
  sendDefaultPii: false,
  dataCollection: {
    userInfo: false,
    cookies: false,
    httpHeaders: false,
    httpBodies: [],
    urlQueryParams: false,
    databaseQueryData: false,
    stackFrameVariables: false,
    genAI: { inputs: false, outputs: false },
    graphQL: { document: false, variables: false },
  },
  tracesSampleRate: 0,
  enableLogs: false,
  enableMetrics: false,
  maxBreadcrumbs: 30,
  beforeSend: sanitizeErrorEvent,
  beforeBreadcrumb: sanitizeBreadcrumb,
} satisfies Parameters<typeof init>[0];
