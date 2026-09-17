import * as Sentry from "@sentry/nextjs";
import { sanitizeErrorEvent, sentryOptions } from "@/lib/sentry-options";

if (process.env.NEXT_PUBLIC_SENTRY_DSN) {
  Sentry.init({
    ...sentryOptions,
    dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
    integrations: [Sentry.breadcrumbsIntegration({ console: false, dom: false })],
    beforeSend(event) {
      let locale: string | null = null;
      try {
        locale = localStorage.getItem("tover-locale");
      } catch {
        // Storage can be disabled by browser policy.
      }
      event.contexts = {
        ...event.contexts,
        tover: {
          locale: locale === "ru" ? "ru" : "en",
          document_language: document.documentElement.lang,
          browser_language: navigator.language,
          user_agent: navigator.userAgent,
          path: window.location.pathname,
          online: navigator.onLine,
          translation_marker: document.documentElement.matches(
            ".translated-ltr, .translated-rtl"
          ),
        },
      };
      return sanitizeErrorEvent(event);
    },
  });
}

export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;
