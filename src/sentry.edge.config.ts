import * as Sentry from "@sentry/nextjs";
import { sentryOptions } from "@/lib/sentry-options";

const dsn = process.env.SENTRY_DSN || process.env.NEXT_PUBLIC_SENTRY_DSN;
if (dsn) Sentry.init({ ...sentryOptions, dsn });
