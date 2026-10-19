# Ozon source contracts for a later port

Extracted 2026-10-19 from local code/tests at the [baseline and source hashes](sources.json). Provider verification recorded by the legacy runbook: **2026-07-27**. Recheck the official [Seller API reference](https://docs.ozon.ru/api/seller/) before implementing; this extraction did not call Ozon or any remote database.

## Request boundary

The base URL was `https://api-seller.ozon.ru`; local tests used an explicit override. Send `Client-Id`, `Api-Key`, `Content-Type: application/json`. Credentials and full request/response bodies do not belong in application logs.

Ozon uses POST for reads. Enforce the explicit endpoint allowlist retained in [ozon-cases.json](fixtures/ozon-cases.json); do not infer safety from the HTTP method. Report-generation endpoints create remote report work even though they do not change marketplace inventory.

- Share request pacing by Client-Id: at least 25 ms between starts; turnover stock analytics permits one start per minute.
- Safe retrieval: 30-second attempt timeout, four total attempts for transport/timeouts or HTTP 408/425/429/500–599. Backoff is 500/1000/2000 ms plus up to 250 ms jitter. Respect longer `Retry-After` seconds/HTTP dates and `Item-Retry-After` minutes, capped at 30 seconds per HTTP wait.
- Do not automatically retry ambiguous report creation: mutual settlement, compensation, decompensation, discounted report creation. Persist the report phase and reconcile before creating another report.
- Share an absolute execution deadline across request, pacing, download and backoff; an unused canceled pacing reservation must not postpone the next resume.

Evidence: `baseline:src/lib/ozon/client.ts:3-57,122-175,178-331`; `baseline:tests/unit/ozon-client.test.ts:64-310`.

## Decoding and pagination

| Domain | Contract worth retaining |
| --- | --- |
| Identity and money | Preserve large int64 IDs and decimal lexemes before ordinary JSON number conversion. Normalize business decimals without binary rounding. Never identify a source record by a display name alone. |
| Warehouses | `/v2/warehouse/list`: seller FBS/rFBS, limit ≤200 and cursor. `/v1/warehouse/fbo/seller/list`: account-scoped FBO, empty request body. Do not fetch the global Ozon network as seller evidence. Prefer source warehouse ID; use names only when the source has no ID. |
| Product details | Send only one identifier family per `/v3/product/info/list` request; prefer product IDs, then separate offer/SKU fallback requests. Preserve offer, SKU, barcodes and source identity as separate values. |
| Stocks | `/v4/product/info/stocks` provides type/present/reserved, no warehouse. Empty `stocks: []` yields zero stock rows; missing `stocks` is incomplete. Do not invent a warehouse or an inventory movement. |
| Postings | Preserve all product lines. FBS/FBO wrapper and warehouse fields differ; Money is `{amount,currency}`. Delivered status and `in_process_at` are separate source facts; do not invent a delivered-at timestamp. Non-terminal postings need identifier refresh beyond the incremental window. |
| Returns | Start int64 cursor with numeric zero. FBO/FBS filter is `logistic_return_date`; next cursor is last row ID while `has_next`. rFBS filter is `created_at`; detail unwraps `returns`. Legacy price is `{price,currency_code}`. Missing receipt timestamp or quantity remains unknown. |
| Supplies | List returns `order_ids`, not full orders, at root or under `result`. Follow nonempty/nonrepeated cursors, stop on empty IDs, safety limit 100 pages. Fetch details in batches ≤50; fail unresolved/partial detail batches. Keep each supply/bundle/destination separately and paginate bundle items. |
| Analytics | Stocks identity includes SKU, cluster and warehouse ID. Required counts must be present; blank warehouse name is allowed. `requested_stock_count` is not reserved stock. Empty analytics is valid. |
| Reports | Cash flow uses calendar halves (1–15, 16–month end) and `page_count`; buyout windows do not overlap and are ≤31 days. Creation and polling are separate phases. Accept a missing monthly report only for the exact endpoint-matched not-found identity; other 404s are failures. |
| Discounted files | Use documented discounted SKUs and multi-row XLSX headers. Enforce HTTPS Ozon host/redirect checks, deadlines and compressed/expanded byte limits before parsing. |

Fixtures cover representative edge cases, not every endpoint. The full source catalog remains at `baseline:docs/ozon-integration.md:129-148,316-614`; decoder regressions at `baseline:tests/unit/ozon-sync.test.ts:110-1177` and `baseline:tests/unit/ozon-correctness-repair.test.ts:81-205,243-425`. Exact lossless-number and return fixtures are retained locally.

## Progress, retries and evidence

- Preserve per-connection source cursor, original requested date range, committed page/batch checkpoint and source contract version. Publish progress only after the corresponding data is durably committed. A failed child replacement must roll back its parent update too.
- The old ordered domains were warehouses, products, stocks, postings, returns, finance, legalEntities, reports, removals, supplies, analytics, discountedProducts. Their ordering is historical evidence, not a mandated new module structure.
- Claims use a unique lease token. Only the current token may checkpoint/yield/finish. Expired work may be reclaimed; stale workers must not overwrite it. API work runs outside the claim transaction.
- Count executions separately from actual failures. Budget yields and report-poll waits are not failures. Durable failure delays were 1 minute, 5 minutes, 15 minutes, 1 hour, 3 hours, 6 hours, 12 hours; failure eight terminates. Manual retry preserves checkpoint and prior failure events.
- Advance completed coverage only after every domain succeeds or validly skips. Partial/failed runs and explicit backfills do not advance coverage. Preserve account scoping and source IDs when deduplicating observations.
- Sanitize personal buyer/contact/address fields before persisting raw payloads. Preserve useful product/legal source identifiers. Typed errors may expose safe endpoint/status/code/reason and retry metadata; arbitrary exception messages, stack traces, credentials and payloads are excluded. Unknown errors use a fixed message.

Evidence: `baseline:src/lib/ozon/durable-runner.ts:198-315`; `baseline:docs/ozon-integration.md:205-314`; `baseline:supabase/migrations/021_ozon_sync_checkpoints_observability.sql`; `baseline:supabase/migrations/027_ozon_retry_accounting.sql`; `baseline:supabase/migrations/028_ozon_automatic_incremental_sync.sql`. Old lease durations, RPC names and scheduler wiring require a deliberate decision before reuse. Candidate generation and inventory commits are outside the fetcher port.

## Credential envelope

Each client ID and API key was encrypted separately with AES-256-GCM. Derive the 32-byte key as SHA-256 of UTF-8 `OZON_CREDENTIAL_ENCRYPTION_KEY`. Use a fresh random 12-byte IV. The envelope is `{v:1, alg:"aes-256-gcm", iv:<base64>, tag:<base64>, data:<base64>}`; the outer object has `clientId` and `apiKey`. Mask UI hints; do not log ciphertext. A later decision must either retain this key/envelope or require reconnection. No real or usable encrypted credentials were copied.

Evidence: `baseline:src/lib/ozon/credentials.ts:4-92`.

The [copied operations runbook](runbooks/ozon-operations-legacy.md) preserves old scheduler inspection/pause/recovery instructions. Source deletion does not disable an existing Supabase Cron job. This milestone does not change that job or fetch marketplace data.
