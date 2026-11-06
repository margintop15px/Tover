# Tover product requirements

Status: agreed first tester contract, 2026-10-21. This document replaces the prototype's physical-unit model. [Client UI](client-ui-spec.md), [architecture](architecture.md) and [RDF storage](rdf-machinery.md) use the same terms. Specifications state required behavior; the [README](../README.md) and release checks describe implementation evidence.

## Purpose and terms

Maintain a durable inventory ledger. Show current stock and explain every accepted change. Let workspace members enter business operations and review their effects before acceptance.

| Term | Meaning |
|---|---|
| Workspace | Isolated inventory, membership and shared-draft boundary. |
| Item | Distinct goods/variant with a stable internal ID, name, whole-unit counting basis and optional article/barcode. |
| Location | Named place where an item's quantity is held. |
| Operation | One of eight business action types with stable lines and immutable accepted revisions. |
| Manual correction | An accepted stock-row correction or declaration of preexisting stock. It is not a business-operation type. |
| Workspace draft | Durable incomplete or complete proposed operations, corrections, revisions and cancellations shared by all members. |
| Stock | Available quantities from effective accepted history, grouped by item and location. Show outstanding purchase quantities separately as Awaiting arrival, by purchase and item. |
| Lineage | Dated production input/output lines and quantities. It does not assign individual physical units or particular receipts. |

Article and barcode changes retain the item ID and its history. Different variants use different IDs. Receipts from different suppliers can increase the same item balance; suppliers remain receipt facts. Names and external identifiers do not establish physical-unit identity. Do not generate individual unit IDs, allocate outgoing quantities automatically to receipts, or infer unsupported origin claims.

## Business operations

Use exactly these eight types. Packing, unpacking, repacking, assembly and rework use Production.

| Type | Required behavior |
|---|---|
| Purchase | Record party, item lines, quantities, locations and optional monetary facts. Accepting a purchase increases stock by its item quantities on the operation date. Keep the commercial amount once. No separate arrival step or delivery status. |
| Return | Record incoming customer or outgoing supplier direction, items, quantities and locations. Optionally reference the original operation. Increase or decrease stock according to direction. |
| Transfer | Move item quantities from a source location to a destination. Preserve the total quantity atomically. |
| Production | Consume input quantities and create output quantities atomically. Record all items and locations. Support multiple inputs and outputs. |
| Defect | Remove item quantities at a location and record a reason. |
| Sale | Remove item quantities from source locations. Record party and optional monetary facts. |
| Write-off | Remove item quantities at a location and record a reason. |
| Payment | Record incoming/outgoing direction, party, exact monetary amount and currency. Optionally reference a purchase or sale. Do not change stock. |

Inventory quantities are whole units. Monetary amounts retain exact decimal values and currency. Financial facts do not establish valuation, debt allocation or accounting reports.

A Return linked to a Purchase must be outgoing; one linked to a Sale must be incoming. Preview and Submit enforce this against the latest effective original operation, including corrections in the same selection. Returns without a reference support either direction. Payment directions remain independent of the referenced operation type.

Production can transform A into B and later B into A. These are separate dated operation lines, not a cyclic identity graph. Item history includes that item's own records. The lineage Gantt may include production parents and their dated quantities. Shared operations must not be counted twice. Pooled inventory must disclose any unsupported per-output attribution.

## Accepted history and corrections

Record the actor, reason, occurrence time and recording time. Keep accepted revisions immutable. A correction or cancellation supersedes the prior effect and preserves all prior records.

Inline Stock edits create manual corrections. Stock Add row declares preexisting inventory with an explicit reason; it does not invent a purchase or unknown production history. Existing item metadata can change without replacing its ID. An opening declaration and a later correction remain distinguishable in history.

Replay effective records in deterministic business order. Rebuilds must reproduce stock, item history and lineage. A backdated operation, correction or cancellation must not create a negative balance, consume unavailable input or leave a dependent production invalid. Return affected record references and actionable validation findings. Permit related corrections to be reviewed and submitted together.

## Shared review and acceptance

Save completed field/cell edits on Enter, Tab or leaving the field. Incomplete entries remain valid draft content and cannot be accepted until complete. Show Saving, Saved or Failed. Every acknowledged save survives refresh, tab closure and application restart.

Smart-input compositions persist privately for their author until Done. Done validates the complete entry and shares it as pending; final Submit accepts history. Stock Add row and edits to existing pending entries stay shared.

All active workspace members can edit, review and submit the shared draft. Reviewer selections are private to that review. Select complete operations or individual stock-row corrections. A transfer or production operation and all its lines form one indivisible entry.

Preview the selected entries on the server. Accept the exact reviewed selection atomically, using both the accepted-ledger revision and reviewed draft-entry revisions. Persist accepted history, the new projection revision, an idempotency receipt and removal of submitted entries in one transaction. Leave unselected entries pending. Retrying a completed request returns its existing receipt.

Stale draft saves must not overwrite another member's edits. Preserve the attempted value and show the current saved value for resolution. Changed previews require a new review. Concurrent submissions must not apply any entry twice.

## Identity and release boundary

Use verified email accounts with signup, confirmation, login/logout and recovery. Signup creates the user's first workspace. Managers invite users; users can join invited workspaces and switch among memberships. Check current membership on every protected request and realtime delivery. Team administration remains manager-only; inventory draft work is available to members.

The first release is one Clojure application instance with RDF4J NativeStore and self-hosted Supabase identity. Express domain relationships and business constraints through a versioned RDF/OWL/SHACL/SPARQL package wherever practical; retain the semantic version used for accepted results and rebuilds. Clojure owns authorization and transactional/concurrency orchestration. Broadcast committed changes to connected members and refresh authoritative snapshots on initial realtime connection and reconnect. Keep workspaces isolated in storage and transport.

Release only after operation/replay/lineage correctness checks, two-member browser flows, real confirmation/invitation/recovery email checks and a consistent backup/restore rehearsal pass. Deployment details are in [production deployment](production-deployment.md).

Imports, marketplace integrations, file/evidence uploads, automatic matching, valuation, debt reports, generalized report snapshots and multi-instance operation are deferred. Do not simulate them in the tester interface.

## External identity contract (M1)

M1 defines offline schemas, semantic validation and synthetic source contracts. Live fetching, persistence, Review integration and synchronization are later milestones in the [execution plan](marketplace-integration-plan.md).

The workspace-local goods ID is the only inventory identity. External products are scoped to a connection/account and may have changing names, articles, multiple barcodes and primary/source/historical SKU aliases. Warehouses and suppliers do not create additional goods identities. Several external listings may refer to one goods ID after explicit confirmation. Names or identifier similarity never confirm this relationship automatically.

Keep imported facts and revisions separate from user-controlled catalogue metadata. Preserve incomplete/conflicting source evidence. Validate mapping and catalogue decisions against the connection's versioned policy and all competing active bindings. Default policy permits a barcode to resolve to one internal goods ID within a connection; the strict preset permits one listing. Optional name/article/barcode/unit requirements apply at acceptance. Historical bindings remain evidence; policies do not erase them or disable workspace isolation.

Article/name changes retain confirmed identity. Changes to the confirmed variant or whole-unit counting basis require review before further automatic acceptance. Mapping corrections affect future proposals; accepted operations require reviewed revisions. Never infer unit conversion or merge same-name variants.

Goods will support multiple Store memberships in M2. External field definitions preserve provider category/type/attribute identities, value types, multiplicity, dictionary IDs and complex groups. Later view preferences select optional imported columns; required TOVER columns remain visible. M1 does not change the current single-Store catalogue UI.

The [Ozon source contracts](ozon-source-contracts.md) distinguish observations from physical events. New events need confirmed goods/location mappings and complete evidence before automatic acceptance. Corrections require Review. Stock snapshots support cutover/reconciliation; actual dispatch creates Sale, actual return receipt creates Return, and transportation updates have no second stock effect. Missing actual quantities, time or location remain incomplete.


## Workspace catalogues

Maintain separate Goods, Warehouses, Suppliers, Categories and Stores catalogues. All active members can add and edit records directly. Require names; retain stable identities and workspace-local relationships. Goods use existing item IDs, one optional Category and one optional Store. Stores represent sales channels with an optional default warehouse.

Catalogue metadata never owns quantities or rewrites accepted history. Warehouse labels may change while their immutable inventory location keys remain fixed. Reserve previous warehouse and supplier names as aliases to avoid ambiguous reuse. Current goods metadata changes only through direct catalogue edits or explicitly edited fields in accepted Stock corrections; ordinary operation snapshots cannot replace newer metadata. Catalogue-created goods need no opening stock declaration.

Bootstrap current accepted items, locations and purchase suppliers additively. Do not infer suppliers from sale customers, expose private compositions, or import the old v1 database. New accepted references join the appropriate catalogues. Preserve quantities, ledger records and stock identities across bootstrap, catalogue edits, restart and projection rebuild.

Suggest the appropriate catalogue records in table cells. Resolve a manually typed goods name to a unique exact match; require selection for ambiguous names. Unmatched inventory values join catalogues only when Submit succeeds. Existing Stock name edits retain the original goods identity. Category, Store and default-warehouse names typed into catalogue relationships create and link records atomically on cell save, reusing an existing exact match or warehouse alias.
