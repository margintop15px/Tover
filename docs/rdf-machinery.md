# RDF storage and replay contract

Status: agreed first tester contract, 2026-10-21. RDF4J NativeStore is the selected store for one application instance. [Product requirements](product-requirements.md) define operation semantics. Earlier individual physical-unit and containment models are superseded.

## Records and isolation

| Record | Required meaning |
|---|---|
| Item | Stable workspace-scoped internal ID, goods/variant attributes, whole-unit counting basis and optional article/barcode. |
| Operation revision | Stable operation/line IDs, one of eight types, complete type-specific facts, actor, reason, occurrence/recording times and acceptance revision. |
| Manual correction | Stock-row correction or preexisting-stock declaration with the same audit fields; no invented business-operation type. |
| Supersession | Explicit revised/cancelled target preserving every earlier accepted record. |
| Draft entry | Workspace, stable entry ID, revision, incomplete/complete content, last actor and saved time. |
| Projection revision | Accepted-ledger revision and its reproducible stock/history/lineage results. |
| Submission receipt | Workspace, request identity, reviewed revisions and accepted result retained across retries and restarts. |

Partition all inventory reads and writes by the trusted workspace. Never accept a browser-supplied workspace as authorization. Exclude anonymous prototype graphs. Preserve previous stores and migration history; do not reinterpret legacy containment data as accepted tester inventory.

Aggregate quantities by item and location. Supplier remains a purchase fact. Identifier edits preserve item identity. Do not generate per-unit entities or automatic allocation edges. Different items can share a descriptive external identifier without merging their internal identities.

Use ordinary RDF nodes and a versioned declarative semantic package for durable records. The package contains the RDF/OWL vocabulary, SHACL constraints and SPARQL queries/rules for domain relationships, effective history and projections. Put business semantics in that package wherever practical. SHACL validates the transaction's candidate graph before commit. Clojure owns authorization, transaction boundaries, concurrency, idempotency and orchestration of the semantic checks. Record the semantic version used by accepted results and rebuilds. Accepted revisions are immutable. Current stock derives from effective accepted records; it is not a second inventory authority. External connector mappings and generalized report manifests are deferred.

Install the semantic package on a fresh store. Retain its version/checksum with the store and refuse an incompatible package on reopen. Changes to ontology, shapes or rule/query resources require an explicit reviewed migration and a restored-backup rehearsal; do not silently replace stored semantics.

## Operations and replay

Use exactly `purchase`, `return`, `transfer`, `production`, `defect`, `sale`, `write-off` and `payment`. Manual corrections remain separate accepted history records. Packing/unpacking/repacking are Production.

Accepting a Purchase increases stock by its item quantities at the operation date and locations. It has one commercial amount and no separate order, arrival or delivery status. Customer returns increase stock; supplier returns decrease it. Transfer conserves item quantity across locations. Production consumes all input quantities and creates all outputs atomically. Defect, Sale and Write-off remove quantities. Payment records exact decimal monetary facts and currency without stock effects.

Historical compatibility: previously accepted purchases with explicit receipt lines retain their recorded stock quantities and dates during replay. Read these quantities as ordinary items in the UI. New purchases and replacement drafts use direct stock lines; do not rewrite accepted history.

Replay effective revisions in deterministic business order with stable tie-breaking. Preserve occurrence time independently of recording time. A correction or cancellation changes effective history while preserving the original record and review trail. Rebuilds from the same accepted records must produce the same balances and histories.

Accepted facts cannot occur after their server recording time. Keep future facts in the shared draft. Explicit stock corrections control current item descriptions in accepted revision order; later operation snapshots cannot undo them.

Validate the entire affected chronology before acceptance. Reject negative balances, unavailable production inputs and invalid dependent outputs. Return affected record references. Permit a selected group of related revisions/corrections to resolve a conflict atomically. Never accept a valid prefix of an invalid selection.

Seed validation with selected new/replacement item IDs and the latest selected targets. Query effective operations touching those items, include complete multi-line operations, and expand item and original-operation reference links to a fixed point. Load every immutable revision of included entities. Superseded lines do not expand membership. Use the workspace-wide sequence maximum for new records, retaining original business order for revisions.

Validate this complete component in a temporary RDF graph using the installed shapes and projections, then compare independent replay. Replay full item timelines so later stock resets cannot conceal earlier deficits. Preview rolls back the candidate graph. Submit writes immutable records, affected projection records, draft removals and the receipt together. Unaffected components remain valid and their cached records remain intact.

Ordered timeline validation computes balance prefixes over prior effects and has quadratic worst-case growth within a connected component. Bound each SPARQL query to 15 seconds. A timeout aborts the transaction and retains pending drafts. Query-only measurements were 58 ms/101 effects, 506 ms/501 and 1.82 s/1,001; these do not establish a supported operation count. Large connected components still need this operating limit.

## Draft and transaction boundary

Persist one shared draft per workspace beside the accepted ledger. Accept incomplete draft content during editing. Acknowledge a save only after its transaction commits. Completed edits carry an expected entry revision; stale edits return a conflict with the current saved value and preserve the attempted input.

The reviewer selects complete operations or individual stock-row corrections. Transfer/Production and all their lines are one entry. Preview validates the selected entries against their revisions and the current accepted-ledger revision.

Submit executes in one serialized workspace acceptance transaction:

1. Return the durable receipt if this request already committed.
2. Check the reviewed ledger and draft-entry revisions and current workspace membership.
3. Revalidate the selected entries and replay their combined effects.
4. Append immutable accepted revisions, replace only affected projection records, advance the projection revision, persist the receipt and remove only selected draft entries.
5. Commit before publishing draft/stock/operations invalidations.

A failed check writes none of these effects. Concurrent submissions cannot accept the same draft twice. Lost responses are recoverable through the same request identity and receipt. Unselected entries survive unchanged. Restore and reopen must preserve acknowledged drafts and receipts as well as accepted inventory.

## History and lineage

Item history returns records whose lines affect that item. Stock inline history loads when expanded. The history table contains the item's own records. The Gantt can include production parent items and operation lines, quantities and locations.

Retain a separate immutable item audit of revisions and cancellations, including revisions that move lines to another item. Superseded records remain visible in that audit without contributing stock or Gantt effects.

Lineage edges join dated production input/output lines. Multiple inputs/outputs, partial consumption, unpacking/repacking and successive A → B → A transformations are valid. Traverse operation-line identities and time; do not infer an impossible cyclic object lifecycle from reuse of an item ID. Shared operations must not be double counted.

Pooled stock does not identify the particular incoming line or unit used by an outgoing line. Present recorded operation-level relationships and disclose missing allocation. Do not invent per-output material attribution, origin certainty or purchase-to-sale allocation.

## Required evidence

Test each operation type, both return directions, direct Purchase stock changes and exact monetary facts. Preserve replay compatibility for historical receipt-based purchases. Test revision/cancellation replay, stable order, projection rebuilds and conflicts caused by backdating. Heavily test multi-input/output lineage, partial consumption, repeated transformations, insufficient stock and shared-history counting.

Exercise draft save/reopen, concurrent edits, stale previews, simultaneous Submit, duplicate requests, response loss and selective acceptance. Verify rollback leaves both the ledger and unselected draft content unchanged. Test cross-workspace reads/writes, history and realtime authorization. A stopped-store backup/restore rehearsal must reproduce accepted stock, drafts, histories and submission receipts.


## Bounded table reads

The disposable `inventory-read-v1` workspace graph stores Stock rows, latest Operations groups, typed sort/filter values, physical row counts, accepted item effects, dated production links and item lookup metadata. SPARQL selects complete groups around a requested physical window and returns query-wide counts and columns. It reads only selected group payloads. Query tokens hold account/workspace-scoped descriptors, not a full history or row index.

Draft state has a separate lightweight payload. Its generation and the read projection update in the same transaction as canonical state and accepted history. A generation mismatch expires the query. On first access to an older store, backfill reads saved stock and streams latest structured records individually; it does not replay all history. The semantic package and immutable records remain unchanged. Rebuild recreates the disposable graph from canonical state.

Item history queries the selected item's effects and all revisions of operations that ever mentioned it, including replacements and cancellations. Lineage traverses only relevant earlier producers and limits parent effects to consumption cutoffs. History mode skips parent effects and metadata. Clicked parent details fetch one authorized immutable revision. No on-demand history or lineage request reconstructs the workspace ledger. Read-cache version upgrades backfill from the saved accepted projection and retain canonical records.

Accepted projection records are stored individually by item and operation. State retains drafts, revision markers and the projection-storage format, rather than a complete aggregate payload. Older aggregate stores bootstrap the new cache; if that cache is lost, canonical history rebuilds it once. Keep full rebuilds as an explicit verification and recovery path. Normal Preview and Submit load only affected canonical records and projection payloads. The semantic package and ledger rules remain unchanged.
