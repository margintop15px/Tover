# Import integrity and provenance

This captures regression contracts for later work. Old operation names, master-data defaults and SQL commit implementation are not the new domain specification. Source references use the [manifest](sources.json).

## Complete source items

- Preserve every source line, including unresolved/partly illegible lines. Missing fields remain null or explicit questions; do not discard the line or guess identifiers.
- Keep all invoice lines together. Headings, subtotals, taxes and totals are not product items.
- `sourceItemCount` means an explicitly printed count, not the extracted array length. If present, it must equal the total extracted line count across operations. Also compare visible quantity × price totals and record discrepancies.
- A provider HTTP 200 is insufficient. Reject incomplete provider status, missing/refused/unparseable structured output, no operations, and explicit count mismatch.
- Review, field editing, entity mapping and commit must retain every line. A first-item preview must not become the whole saved operation. Filling blank warehouse cells must preserve existing per-line choices.

The [six-line synthetic fixture](fixtures/import-integrity.json) retains the known regression: six lines, amount 344500; first-line-only output is rejected. The fixture contains no image bytes or real document.

Evidence: `baseline:src/lib/operation-imports/openai.ts:39-56,481-534,595`; `baseline:tests/unit/operation-import-extraction.test.ts:5-49`; `baseline:tests/e2e/operation-import-items.authenticated.spec.ts:94-167`.

## Evidence and review

- Record file name/type/size and SHA-256 of original bytes. Keep extraction/provider ID, parser/model version, findings, source text and generated-code audit separately from normalized proposals.
- Retain source references where available: kind, sheet name, row/page number, region, columns and evidence text. Store raw candidate input alongside normalized data and validation errors.
- Document contents are data, never instructions. Generated transformation text was saved for audit and never executed by the application server. Old image extraction used provider-hosted code interpretation; that does not authorize or require a new implementation to do so.
- Keep explicit approval identity/time and links among import, candidate and accepted result. Do not infer approval from extraction confidence.
- Preserve duplicate decisions: file hash for repeated upload, workspace-scoped semantic fingerprint for already-accepted operations, and explicit duplicate/accepted-result links. The legacy fingerprint sorted line keys before SHA-256; its specific old operation fields are historical, not a new identity scheme.
- A reprocess must preserve committed rows and existing audit links. Partial commit accepts only approved valid candidates; remaining rows stay reviewable. Duplicate acceptance must not produce a second accepted operation. Port the invariant, not the old SQL business types.

Evidence: `baseline:supabase/migrations/008_operation_imports.sql:3-68`; `baseline:src/lib/operation-imports/types.ts:48-74`; `baseline:src/lib/operation-imports/pipeline.ts:207-208,466-491,900-912`; `baseline:supabase/migrations/018_operation_import_targeted_reprocess.sql`; `baseline:supabase/migrations/019_operation_import_partial_commit.sql:41-96,322-389`.

## Retention gap

The inspected old upload path reads bytes into memory and saves metadata/hash plus extracted data; it does not persist an original file/object key. Do not promise that original documents can be reconstructed from Postgres. New original-file retention requires its own storage implementation and explicit policy.

Evidence: `baseline:src/app/api/operation-imports/route.ts:48-50,95-108,248-258`; `baseline:supabase/migrations/008_operation_imports.sql:3-24`.
