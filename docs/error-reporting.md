# Error reporting

Tover uses `@sentry/nextjs` for browser exceptions, unhandled promise rejections,
React render failures, Next.js server/edge exceptions, and unexpected exceptions
handled by `toRouteErrorResponse`. Expected authentication/authorization failures
are not captured by that helper. Errors returned directly as JSON or handled
locally in a component are not automatically reported as exceptions.

Events include stack traces, browser details, release/environment, and recent
navigation/network breadcrumbs. Browser events also include the app language,
document/browser language, online status, and whether Chrome translation classes
are present. A translation marker is diagnostic evidence, not proof of a cause.

Replay, tracing, metrics, and Sentry Logs are disabled. Existing server console
logs remain unchanged. Automatic collection of user identity, cookies, headers,
request/response bodies, query parameters, and database values is disabled.
Console/DOM breadcrumbs and extra serialized error objects are removed; request
and breadcrumb URLs lose query strings and fragments. Exception messages still
carry diagnostics: do not put credentials or customer data into thrown messages.

## Configure a Sentry project

1. Create a Next.js project in Sentry and copy its DSN.
2. Set `NEXT_PUBLIC_SENTRY_DSN` and `NEXT_PUBLIC_SENTRY_ENVIRONMENT=production`
   as build variables in Coolify. The public DSN is intended to be visible in
   browser code. An empty DSN disables capture on that runtime.
3. Set runtime `SENTRY_DSN` to the same DSN and keep the environment consistent.
4. For readable production stacks, set build variables `SENTRY_ORG`,
   `SENTRY_PROJECT`, and `SENTRY_RELEASE` (the deployed commit SHA), and supply
   `SENTRY_AUTH_TOKEN` as a build secret. Use the same release at runtime.
5. Rebuild and redeploy. Changing a browser DSN only at runtime is insufficient.

The Dockerfile accepts the non-secret variables as build arguments. The upload
token uses a BuildKit secret mount, not an `ARG` or persisted `ENV`:

```sh
docker build \
  --secret id=SENTRY_AUTH_TOKEN,env=SENTRY_AUTH_TOKEN \
  --build-arg NEXT_PUBLIC_SENTRY_DSN \
  --build-arg NEXT_PUBLIC_SENTRY_ENVIRONMENT=production \
  --build-arg SENTRY_ORG \
  --build-arg SENTRY_PROJECT \
  --build-arg SENTRY_RELEASE \
  -t tover .
```

These variables must already be set in the build environment. If the deployment
UI does not support BuildKit secrets, run the image build in CI with secret
support. Do not expose the upload token through a public variable or build ARG.
Local/CI `npm run build` reads `SENTRY_AUTH_TOKEN` from its environment directly.
Source-map generation/upload is disabled unless token, organization, and project
are all supplied. Client source maps are deleted after upload. Check the build
upload result: receiving an event alone does not prove source maps were uploaded.

## Verify before calling monitoring live

Run `npm run test:unit` and `npm run build`. Unit tests capture a real SDK envelope
in memory, check request/URL filtering, and verify handled route error reporting.

On a staging build with the real DSN, trigger a deliberate browser error from a
temporary test component, confirm the issue and its readable source line in
Sentry, then remove the test component. Do not ship a public crash endpoint.
Verify a server error separately. A locally captured envelope proves SDK wiring;
it does not prove connectivity, quotas, project settings, or source-map upload.

Browser events are sent directly to Sentry. Network filters or extensions can
block delivery; this setup does not guarantee receipt from every browser.
