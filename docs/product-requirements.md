# Tover v2 product requirements

- Status: specification draft.
- Review date: 2026-10-08.
- Agreed foundation: RDF for inventory domain data.
- This document defines the domain contract.
- [Client UI specification](client-ui-spec.md) defines interactions.
- [Architecture](architecture.md) defines system boundaries.
- [RDF machinery](rdf-machinery.md) defines storage, reasoning, validation and synchronization contracts.
- Draft requirements describe intended behavior.
- Proposed defaults do not describe implemented behavior.
- `[UNCLEAR] Q-xx` identifies an unresolved decision.
- [Clarifying questions](#clarifying-questions) collect unresolved decisions at the end.
- Writing reference: [ASD-STE100, Issue 9](https://www.asd-ste100.org/assets/files/ASD-STE100_ISSUE9.pdf).

## Purpose

- Maintain a durable inventory ledger.
- Record receipts, changes, availability and supporting Evidence.
- Explain accepted facts and visible uncertainty.
- Do not claim physical facts without recorded support.

## Recorded user challenges

- Goods can arrive without an article, SKU, barcode or Listing.
- One receipt document can include several variants as separate line items.
- Example: one line records 50 black pairs. Another line records 50 white pairs.
- Goods can ship with or without prior assembly.
- Four-pair, five-pair and six-pair sale options can coexist.
- Sale options can contain mixed compositions and discounts.
- Unsold goods can undergo disassembly, reassembly, rework or consumptive transformation.
- Listing changes can replace articles or SKUs while physical identities remain unchanged.
- Goods move between local and marketplace warehouses.
- Users need movement history and each item's recorded Location.
- Supplier barcodes, Ozon SKUs and articles can jointly support matching.
- No external identifier provides universal physical identity.
- Marketplace data can contain delays, stale values, errors or omissions.
- The client uses Ozon XLSX exports and API data.
- `[UNCLEAR] Q-08`: Verify API/export scope, stock measures and source reliability before treating values as equivalent.
- Missing records and hidden context limit safe automation.
- Users need less manual work and authority over ambiguous decisions.
- Aggregate balances hide Discrepancy origins.
- Users need receipt-to-sale tracing through returns and production.
- Users need underlying quantities and separate sale-option measures.

## Agreed direction

1. Give each individually tracked Physical object a stable internal identifier.
2. Keep physical identity independent of external systems.
3. Track origins through Operation lines that record physical arrival or production.
4. Preserve individual lineage where Evidence supports it.
5. Separate Physical objects, Resource specifications, production lineage and Listings.
6. Preserve movements and identifier assignments over time.
7. Preserve original Evidence.
8. Explain each accepted or derived conclusion.
9. Keep conflicting Evidence statements visible.
10. Automate acceptance only under a declared policy.
11. Permit authorized review, correction and overrides with an audit trail.
12. Use RDF for interoperable domain data.
13. Preserve future marketplace, accounting and delivery integration paths.
14. Separate ontology, validation and explicit rule responsibilities.
15. Retain Supabase identity and organization capabilities.
16. Use private S3-compatible storage for uploaded files.
17. Keep inventory domain authority in RDF.

## Domain vocabulary

| Term | Meaning |
|---|---|
| Workspace | Organization boundary for inventory, Evidence, connections, permissions and reports. |
| Resource specification | Goods definition with attributes, measurement unit and optional intended composition. Client label: Goods. |
| Physical object | Tracked inventory item with a permanent internal IRI, Resource specification, status and Location. |
| Location | Recorded physical place or transit state. It remains independent of selling and source Accounts. |
| Account | Named business or integration context. Optional fields identify provider, external references and selling/source roles. |
| Listing | Marketplace offer or card linked to an Account. One Listing can represent many Physical objects. |
| Operation | Recorded business action with stable lines, type, dated effects, Evidence and immutable review/acceptance history. |
| Evidence | Original capture or declaration with versioned parsed statements, source references, scope and timing. |

### Fields, relationships and client labels

| Field or relationship | Meaning |
|---|---|
| Tracked unit | Declared measurement and tracking unit. One pair can represent one Physical object. |
| Production lineage | Dated input/output relationships between Operations and Physical objects. Consumed inputs remain historical records. |
| Output composition | Recorded material quantities attributable to each produced item. It does not represent active predecessor objects. |
| Origin | Known Operation-line reference for physical arrival or production. Keep unknown origins unknown. Retain supplied lot IDs as line attributes. |
| External identifier | Typed value with issuer, Account scope, target, validity and Evidence. Preserve assignment history. |
| Account roles | Selling and source roles can coexist. Store and Source are context labels. Preserve distinct real Account identities. |
| Operation revision | Proposed, accepted or rejected version. Retain alternatives, findings, actor, reason and acceptance history. Client label: Proposed change. |
| Operation effects | Physical, monetary or interpretation changes. Derive accepted facts from accepted effects. Use one Operation class for all effect categories. |
| Evidence statement | Parsed statement within Evidence. Retain capture, source-position, parser/mapping references and previous revisions. Capture does not accept inventory changes. |

- Record actual production inputs and outputs separately from intended composition.
- Record quantities attributable to each output.
- Disclose unknown per-output material allocation.
- Keep intended composition on the Resource specification.
- Preserve item attributes, status, production lineage and Location over time.
- Remove consumed inputs from available inventory.
- Create new item IDs for assembly, disassembly and reassembly outputs.
- Preserve earlier item IDs in production history.
- Consumption does not require physical destruction.
- Record each current item's Location directly.
- Use origin references instead of a separate Batch entity.
- Permit local Accounts without external identifiers.
- Keep credentials in server-side connection configuration.
- Purchase records commercial acquisition.
- Receipt records physical arrival.
- Receipt is not a separate operation type.
- Record arrivals within purchase, return or transfer according to their business context.
- Record acquisition and arrival together when they occur together.
- Preserve separate dates and quantities when acquisition and arrivals differ.
- Preserve each arrival's quantity, time, Location and Evidence.
- Do not repeat the full purchase amount for each partial arrival.
- An invoice, purchase agreement or payment does not establish physical arrival.
- `[UNCLEAR] Q-18`: recording separate and partial arrivals within the retained operation types.
- Preserve original Evidence and immutable parsed-statement revisions.
- `[UNCLEAR] Q-02`: bulk material, weighed quantities and unidentified legacy balances.
- `[UNCLEAR] Q-04`: identity-preserving repair and rework.
- `[UNCLEAR] Q-10`: Account roles and legacy-account mappings.

### Derived results

- Calculate Projections from pinned accepted history, Evidence and semantic versions.
- Include accepted Operation effects and manual-edit effects.
- Treat Projections as rebuildable results.
- Preserve Discrepancy scope, supporting Evidence and resolution history.
- Save report values with parameters, calculation basis and Evidence references.
- Keep Saved report versions immutable.
- Treat Saved report configurations as parameters for a later calculation.
- Keep semantic-package details in the [RDF machinery](rdf-machinery.md).

## Operation types

- Keep these operation types: purchase, return, transfer, production, defect, sale, write-off and payment.
- Do not add operation types for assembly, disassembly, reassembly, packing, unpacking, repacking, repair or rework.
- Do not add an inventory adjustment operation type.

### Production effects

- Use production for assembly, disassembly, reassembly, packing, unpacking, repacking, repair and rework.
- Record actual inputs, outputs, quantities and composition.
- Preserve input/output lineage and mixed origins.
- Consume available inputs for assembly, disassembly and reassembly.
- Create new item IDs for their outputs.
- Create new recovered item IDs after disassembly.
- Record consumptive transformations through production inputs and new outputs.
- Record identity-preserving repair or rework through production under the declared identity policy.
- Record changed attributes, components and additional consumed inputs.
- Treat identity-preserving repair or rework as updates to an existing item.
- Do not model that item as its own consumed predecessor.
- Keep familiar action labels in the client.
- Apply production to arbitrary goods.
- Keep descriptive edits, relabelling and Listing reassignment separate from physical production effects.
- `[UNCLEAR] Q-04`: identity policy for repair and rework.

### Inline manual edits

- Handle inventory adjustment through inline manual edits.
- Preview the exact previous and proposed values.
- Record actor, reason, Evidence, occurrence time and recording time.
- Preserve physical counts as attributed Evidence.
- Apply the same validation and acceptance permissions as Operations.
- Record accepted manual edits in immutable history.
- Preserve earlier values and explanations.
- Do not overwrite a calculated balance directly.
- Do not invent purchase or write-off Operations to explain a manual edit.
- Preserve unknown origins, costs and individual allocation.
- `[UNCLEAR] Q-11`: physical-count intent, record-correction intent and unresolved-difference availability.
- `[UNCLEAR] Q-20`: encoding accepted manual edits within domain history.

## Requirements

### Identity and physical lifecycle

| ID | Requirement |
|---|---|
| PR-01 | Receive goods without external identifiers or a Listing. Record receipt quantity and origin-line references. Generate internal IDs without separate per-object operations. |
| PR-02 | Keep Physical object, Operation, Operation-line, Listing and Evidence IDs stable. Never recycle IDs. Preserve history through external identifier changes. |
| PR-03 | Support arbitrary goods and attributes. Support receipts with one variant or several variant-specific line items. Support compositions across Resource specifications and origins. Treat socks as an example. |
| PR-04 | Record assembly, disassembly and reassembly through production. Consume their inputs. Create new output item IDs. Preserve input/output lineage. Separate Listing changes from production. A sale option does not establish a produced item. |
| PR-05 | Record explicit production effects and lineage. Support identity-preserving rework under a declared policy. Support consumed inputs and new outputs. Separate relabelling, descriptive edits and Listing reassignment. Do not infer identity effects from SKU changes. |
| PR-06 | Preserve consumed, sold, retired and superseded identities in history. Exclude currently unavailable objects from availability. Reject unavailable inputs at each transition. Later restoration cannot justify invalid earlier consumption. |
| PR-07 | Retain purchase, return, transfer, production, defect, sale, write-off and payment. Record inventory adjustments through inline manual edits. Keep balance Evidence statements separate from movements. Do not invent movements from snapshots. |
| PR-08 | Record each current item's Location directly. Transfer produced items without moving consumed predecessors again. Support local and external warehouses. |
| PR-09 | Reject cycles in consumed-input/new-output lineage. Reject contradictory locations or statuses. Reject unavailable consumption and unresolved ordering/correction conflicts. Preserve rejected Operation revisions and explanations. |
| PR-39 | Bind internal IDs through labels, scans, operator selection or supporting Evidence. Explain the physical-binding basis in individual tracing. ID generation alone cannot identify marketplace sales. |

### Marketplace representations and matching

| ID | Requirement |
|---|---|
| PR-10 | Keep Listings, Physical objects and Resource specifications separate. Preserve their relationships, validity and Evidence. Support simultaneous sale options and Listings. |
| PR-11 | Record identifier type, value and scope. Keep barcode, product ID, SKU and article namespaces distinct. Include supplier, platform, account and relevant time. |
| PR-12 | Preserve assignment, replacement and retirement history. Keep unknown historical validity unknown. Do not replace unknown validity with import time. |
| PR-13 | Match through compatible identifiers and contextual Evidence. Keep ambiguous, conflicting and unknown references unresolved. Require Evidence or a recorded resolution decision. Do not equate physical identities from identifier matches alone. |
| PR-14 | Separate price, discount and composition facts. Preserve occurrence-time Listing and composition versions. Discounts do not change underlying quantities or identities. |
| PR-15 | Map future marketplace, accounting and delivery sources into the common model. Preserve source-specific details and semantics. Retain adapter and reconciliation responsibilities. |

### Evidence, provenance and trust

| ID | Requirement |
|---|---|
| PR-16 | Preserve captured source revisions and uploaded originals. Link parsed records to exact captures and import context. Separate Evidence statements, accepted Operation effects and deductions. `[UNCLEAR] Q-14`: permitted content and retention limits. |
| PR-17 | Record source, actor, account, endpoint/report type, filters, paging, coverage and source identifiers. Record occurrence, capture and recording times. Record file/request/row references and applicable parser, mapping, rule and decision versions. |
| PR-18 | Link report values and alerts through derived facts and accepted Operations to original Evidence. Record correction and automation reasons. Identify affected claims. |
| PR-19 | Retain errors, incomplete pages, excluded scopes and stale-data coverage. Missing rows and failed requests do not establish zero stock. Use absence only when capture scope and completeness support it. |
| PR-20 | Compare API data, XLSX exports, mirrors and user declarations. Establish comparable dates, units, warehouses, stock categories and report scopes. Do not select authority by capture recency alone. |
| PR-21 | Preserve conflicts and resolution history. Show unresolved alternatives and decisions. Retain original assertions and Evidence after corrections. |
| PR-22 | Protect Evidence bytes with checksums and controlled access. Isolate data and explanations by Workspace. Checksums establish byte integrity, not physical truth. |
| PR-23 | Support attributed user declarations without integration data. Record author, source, precision and uncertainty. User authority cannot manufacture documentary Evidence. |

### History, reconciliation and reports

| ID | Requirement |
|---|---|
| PR-24 | Query business cutoff B using current knowledge. Query B using only knowledge recorded by cutoff K. Apply K to Evidence statements, mappings, decisions and accepted history. Preserve the applicable policy version. Label newer-rule reinterpretations separately from actual earlier belief. |
| PR-25 | Preserve time-selected production lineage, actual output composition, origins, movements and identifier assignments. Do not interpret historical sales through current composition alone. |
| PR-26 | Separate current item quantities and supported underlying quantities. Do not count consumed predecessors as current stock. Prevent duplicate lineage-counting paths. State tracked unit, filters, timing, coverage and rule version. |
| PR-27 | Provide current availability, historical balances, movements, sales, returns, composition and lineage reports. Trace individual objects where supported. Aggregate by specification, origin line, sale option, Listing, warehouse, source or period. |
| PR-28 | Reconcile accepted movements against comparable Evidence statements. Explain accounted changes and the remainder. Transfers conserve current-item quantities. Account explicitly for production inputs, outputs, underlying quantities and unit conversions. |
| PR-29 | Separate gross sales, returns and net sales. Interpret Listing quantities through applicable composition. Disclose unknown composition or physical allocation. Do not assign arbitrary objects to aggregate sales. |
| PR-30 | Produce evidence-linked Discrepancies and alerts. Include unmatched identifiers, inconsistent counts, unexplained losses, stale Evidence statements and missing coverage. Attribute causes only with supporting Evidence. |
| PR-31 | Retain valuation, supplier purchases/payments/debt, sales metrics and critical-stock alerts. Define quantity and financial measures explicitly. Declare production-cost and discount allocation policies. |
| PR-32 | Preserve report parameters and ledger/Evidence revision references. Preserve ontology, mapping, rule and query versions. Keep earlier reports explainable after knowledge changes. |
| PR-40 | Separate transaction prices, invoice amounts and inventory valuation. Keep missing valuation unknown. Do not substitute sale price for inventory cost. Propagate unknown costs through movements and valuation-dependent reports. Do not present incomplete valuation or turnover as complete. |

### Automation, control and Workspace capabilities

| ID | Requirement |
|---|---|
| PR-33 | Automate ingestion, normalization, supported matching, validation and reconciliation under declared policies. Preserve incomplete or conflicting Evidence. Do not promote incomplete or conflicting Evidence merely to increase automation. Explain automation outcomes in business terms. |
| PR-34 | Permit authorized review, contextual input, rejection, correction and Discrepancy resolution. Record actor, reason, previous decision and scope. Keep corrections visible. |
| PR-35 | Support versioned business rules and eventual controlled customization. Preview historical and report effects. Prevent cross-Workspace access and weakened core constraints. |
| PR-36 | Retain organizations, memberships, roles, authentication, invitations and Workspace settings. Enforce current permissions for originals, explanations, reports and exports. |
| PR-37 | Retain categories, Accounts/channels, warehouses, suppliers and Resource specifications. Retain English/Russian presentation and Workspace currency. Retain existing CSV imports and marketplace synchronization. Retain S3-backed uploaded Evidence. |
| PR-38 | Export authorized RDF domain data with schema/rule versions and Evidence references. Enforce Workspace access for referenced files. Distinguish RDF export from a complete Evidence archive. |

## Required user flows

### Receive unlisted goods

1. Record purchase and its supported arrival effects.
2. Record origin-line references, tracked unit, attributes, quantity, Location and available Evidence.
3. Generate internal IDs under the declared tracking policy.
4. Keep missing external identifiers and cost optional for physical receipt.

### Produce and ship saleable forms

1. Select available components.
2. Enter the production Operation.
3. Preview its input, output, identity and quantity effects.
4. Record actual inputs, output composition and mixed origins.
5. Associate the result with its Resource specification and applicable Listing.
6. Validate input availability, output quantities, lineage and Location before acceptance.
7. Record shipment or movement separately.

### Sell or return

1. Capture Evidence of the business occurrence.
2. Resolve occurrence-time Listing and composition.
3. Establish physical allocation only where Evidence or disclosed policy supports it.
4. Propose the supported Operation.
5. Retain aggregate Evidence statements without invented individual allocation.
6. Accept the Operation under the applicable policy.
7. Preserve return identity uncertainty.

### Change physical goods or representations

1. Use production for disassembly, reassembly, rework or consumptive transformation.
2. Select a representation action for Listing or identifier changes.
3. Show surviving, consumed and newly created identities.
4. Record the supporting identity decision and Evidence.
5. Preserve previous composition and assignment history.

### Investigate a difference

1. Open the report or Evidence statement.
2. Inspect scope and freshness.
3. Inspect the ledger equation.
4. Follow contributing Operations to Evidence.
5. Record missing context or a conflict-resolution decision.
6. Recalculate the answer.
7. Preserve the previous explanation and resolution history.

### Correct history

1. Target the affected Operation.
2. Record effective time and reason.
3. Validate affected history and reports.
4. Accept the correction under the applicable policy.
5. Preserve the original assertion.
6. Show results under different knowledge cutoffs.

## Reporting and acceptance examples

| Example | Required answer |
|---|---|
| Receive 100 pairs, including 50 black pairs and 50 white pairs, without external identifiers. | Record one purchase with physical arrival and 100 internal pair identities. State attributes and tracked unit. Do not require a Listing. |
| Produce 20 four-pair items from those pairs. | Consume 80 input pairs. Create 20 new four-pair items. Leave 20 loose pairs available. Underlying quantity remains 100 pairs. Support mixed variants and origins. |
| Sell ten four-pair items with known composition and allocation. | Show ten sold items and 40 underlying pairs. Remaining quantity equals 60 pairs. Link sales to Evidence. |
| Disassemble five unsold four-pair items. Produce four five-pair items from the recovered 20 pairs. | Create new output IDs for both production stages. Preserve predecessor IDs in lineage. Remaining underlying quantity stays 60 pairs. Update current-item counts and sale-option totals. |
| Replace an article, SKU or Listing without physical change. | Preserve physical IDs. Preserve assignment Evidence and known validity. Interpret past sales through occurrence-time assignments and composition. |
| Consume one object to produce a new output. | Preserve input/output identities and lineage. Exclude the consumed input from availability. Explain the identity-policy decision. |
| Repair an object under an identity-preserving policy. | Retain its IRI. Record attribute/component revisions, Evidence and additional consumed inputs. |
| Transfer a produced four-pair item between warehouses. | Update that item's Location. Do not transfer consumed predecessors again. Preserve production lineage. |
| Correct a January 13 movement on January 15. | January 14 knowledge shows the original interpretation. Later knowledge shows the correction. Preserve original Evidence in both views. |
| Correct an occurrence time across business cutoff B. | `[UNCLEAR] Q-06`: correction visibility, replacement eligibility and original effects across B. |
| Receive 100 pairs. A comparable report shows 90 sold and zero remaining. | Show a ten-pair unexplained difference only with complete opening/closing balances, movements, periods and units. Otherwise show coverage limitations. Do not infer an Ozon bug. |
| Two sources disagree about an identifier or warehouse. | Preserve both Evidence statements. Show source/time context and unresolved alternatives. Do not choose by import recency. |
| A request fails or an export covers one warehouse. | Show failed or incomplete coverage. Do not conclude that all stock equals zero. |
| An object undergoes duplicate consumption before later restoration. | Reject the invalid proposed history. A valid final state cannot erase invalid intermediate consumption. |
| A physical count finds surplus with unknown origin. | Preserve the count as Evidence. Keep origin and acquisition cost unknown. Record any accepted inline manual edit in history. |
| API and XLSX captures describe the same sale. | Preserve both captures. Do not duplicate the sale. `[UNCLEAR] Q-09`: occurrence-equivalence and Operation-line keys. |

- Numerical examples assume complete, explicitly identified lifecycles.
- These examples do not restrict goods, units, compositions or marketplaces.
- Individual IDs cannot supply missing physical-allocation Evidence.

## Compatibility with the existing product

### Access and settings

- Retain organization isolation and invitations.
- Retain owner, admin and member roles.
- Retain manager-only settings changes.
- Retain Workspace currency.
- Retain category requirements and defaults.
- Map legacy Store/channel records, requirements, defaults and report dimensions into Accounts.

### Reference data and operations

- Retain categories, Accounts, warehouses, suppliers and Resource specifications.
- Retain ordinary/defect quality and known/unknown cost status.
- Retain purchase, return, transfer, production, defect, sale, write-off and payment.
- Express assembly, disassembly, reassembly, repair and rework through production.
- Record inventory adjustments through inline manual edits.
- Keep purchase, physical receipt, invoice, payment and valuation meanings separate.

### Reports

- Retain inventory balances, product movement, sales volume, turnover, defects and supplier settlements.
- Retain product, category, warehouse, Account, quality and supplier dimensions where relevant.
- Retain quantity, cost, invoice, payment and debt measures.
- Retain historical and period views.
- Preserve P&L, cash/settlement, sales/profitability and inventory report directions from the client specification.
- `[UNCLEAR] Q-12`: final formulas and financial parity.
- `[UNCLEAR] Q-13`: tax and legal scope.

### Imports and Ozon safeguards

- Retain orders, order-lines, inventory and payments CSV outcomes.
- Retain reviewable operation imports from CSV, XLSX, images and text.
- Preserve multiple items, source references and errors.
- Preserve sheet, row, page and region references.
- Begin marketplace synchronization with inbound reads.
- Retain final-delivered FBO/FBS posting support for sales.
- Require seller-receipt Evidence for returns.
- Require explicit dated disposal Evidence for write-offs.
- Permit completed supplies to support transfers after mapping.
- Do not infer purchases, payments, production, defects or adjustments from unrelated marketplace Evidence statements.

### Migration

- Preserve legacy data at its actual certainty and precision.
- Do not convert balances or snapshots into invented physical histories.
- Make opening balances and historical limits visible in tracing and reconciliation.
- Retain transaction-price and inventory-cost distinctions.
- Propagate unknown costs.
- Map weighted-average valuation, turnover formulas, cost/debt settings and reports deliberately.
- Do not adopt every v1 calculation as final v2 policy.

## Bounded proof

- Reference: [inventory RDF experiment](../experiments/inventory-rdf/README.md).
- Retained real aggregate source records: 743.
- Constructed lifecycle: 12 initial Physical objects.
- Passed checks: 196.
- Complete-graph comparisons against the Python reference: 92.
- Tested declarative SHACL-AF SPARQL rules.
- Demonstrated temporal Projections, legacy containment, lineage, scoped identifiers, corrections and selected invalid-history rejection.
- The experiment modeled packing through containment.
- The current specification models packing through consumed inputs and new output items.
- Verify new assembly, disassembly and reassembly cases before claiming proof coverage for this contract.
- The proof did not reconstruct individual physical truth from aggregate records.
- The proof did not verify all Ozon API/export semantics.
- The proof did not test OWL reasoning.
- The proof did not establish production transactions, concurrency, durability or performance.
- The proof did not establish incremental maintenance or validation of every intermediate state.
- Treat RDF as the agreed direction.
- Prove production guarantees before relying on them.

## Review findings

| Finding | Resolution or remaining decision |
|---|---|
| Packing examples imply separate operation families. | Use production with consumed inputs, new output items and preserved lineage. |
| Inventory adjustment implies a ninth operation type. | Use inline manual edits with validation, acceptance, Evidence and immutable history. |
| Packing retained a separate containment abstraction. | Remove that abstraction. Treat packing, unpacking and repacking as item-changing production. |
| Required origin conflicts with found surplus. | Record origin when known. Preserve explicit unknown origin and opening-balance Evidence. |
| Architecture fixes a first path while release scope remains open. | Label the path Proposed in all documents. `[UNCLEAR] Q-01`. |
| Correction eligibility can depend on changed occurrence time. | Preserve the proof policy as a reference. `[UNCLEAR] Q-06`: cutoff-crossing correction semantics. |
| Capture digests cannot identify repeated business occurrences. | Separate capture revision and occurrence identity. `[UNCLEAR] Q-09`: cross-source equivalence. |
| Selling and source contexts use overlapping entity names. | Use Accounts with provider and roles. Preserve distinct real account identities. `[UNCLEAR] Q-10`: mappings. |
| Required Evidence retention conflicts with conditional API-file retention. | Preserve exact permitted capture references. `[UNCLEAR] Q-14`: restricted content and retention behavior. |
| Collaboration lacks an architecture owner. | Preserve the feature commitment. `[UNCLEAR] Q-17`: ownership, durable anchors and permissions. |
| Leaf-object counting implies support for bulk quantities. | Scope counting to the declared tracking mode. `[UNCLEAR] Q-02`: bulk and weighed quantities. |
| Concurrent retry can change a reviewed operation. | Retain the reviewed Operation revision. Require renewed review when its business effects change. |
| Financial preservation can imply adoption of existing formulas. | Preserve user outcomes. `[UNCLEAR] Q-12`: final formulas, turnover measures and allocation policies. |

## Proposed first slice and decision gates

- Proposed path: purchase arrival, physical identity, production, transfer, evidence-linked report and late correction.
- Proposed validation method: compare inbound source data alongside the current system.
- `[UNCLEAR] Q-01`: first-release lifecycle, imports, reports and connectors.
- Apply each gate only to workflows within the selected slice.

| Gate | Required decisions or proof |
|---|---|
| First inventory slice | Tracking mode, physical binding, identity effects, quantity precision, occurrence precision/order, correction profile and acceptance permissions. |
| First durable domain writes | Schema/Operation encoding and selected-store atomic acceptance, idempotency, stable reads and completed Projection publication. |
| Connector acceptance | Source scope, authority, coverage, cross-source occurrence keys and Operation-line keys. |
| Physical counts | Manual-edit intent, individual allocation and Unaccounted availability. |
| Financial reports | Valuation, costs, discounts, fees, returns, debt, currencies, timing, rounding and explicit turnover measures. |
| Collaboration | Storage ownership, durable context anchors, notifications and human/agent permissions. |
| Custom rules | Authoring, approval, activation, effect preview, rollback and mandatory constraints. |
| Domain cutover | Real lifecycle Evidence, migration reconciliation, isolation, concurrency, recovery, retention and report reproducibility. |

- Defer unselected capabilities without deleting their requirements.
- Do not require every future policy before a bounded first slice.

## Clarifying questions

| ID | Topic | Direct questions |
|---|---|---|
| Q-01 | Release scope | Which lifecycle, imports, reports and connectors must the first release support? |
| Q-02 | Tracking | Which tracking modes cover individual goods, bulk material, weighed quantities and legacy balances? What quantity precision, rounding and conversions apply? |
| Q-03 | Physical binding | Which labels, scans or operator selections establish physical identity? When may policy allocate aggregate sales, returns or adjustments to individuals? How must Tover label those allocations? |
| Q-04 | Identity-preserving rework | Which repair or rework actions preserve an item's identity? Which supporting Evidence must those actions require? |
| Q-05 | Business time | Which occurrence-time precision and uncertainty must the first slice support? Which Evidence establishes order for conflicting simultaneous actions? |
| Q-06 | Corrections | Who resolves correction branches and dependent-Operation conflicts? How must corrections behave when occurrence times cross business cutoff B? Which correction and reversal profiles must the first slice support? |
| Q-07 | Permissions and automation | Who may propose, accept, reject, correct or override each operation? Which operations permit automatic acceptance? Which thresholds and review conditions govern automatic acceptance? Which actions require reasons or separate external-write authorization? |
| Q-08 | Source authority | Which source has authority for each field and process? Which account, warehouse, stock-category and completeness rules establish comparable API/export scopes? |
| Q-09 | Occurrence identity | Which fields identify one occurrence and its Operation lines across API, XLSX and CSV captures? How must users resolve uncertain cross-source equivalence? |
| Q-10 | Account roles and mapping | Which Account roles and provider fields must Tover support? Which legacy Stores map to the same Account, and which remain distinct? |
| Q-11 | Counts and manual edits | Does a quantity edit declare a physical count or correct an earlier record? How must unresolved differences affect availability? How must manual edits represent shortages, surplus and unknown individual allocation? |
| Q-12 | Financial policies | Which valuation, production-cost, discount, fee, expense, partial-return and debt policies apply? Which currency, exchange-time, accrual, cash-date and period-closing policies apply? What monetary precision and rounding apply? Which formulas define turnover and financial parity? |
| Q-13 | Tax and legal scope | Which jurisdictions, tax regimes, VAT rules and legal entities apply? What must the accountant handoff contain? |
| Q-14 | Evidence administration | Which original content may Tover retain? What retention, sensitive-access, redaction, deletion, export and unavailable-Evidence policies apply? |
| Q-15 | Rule governance | Who may author, approve and activate rules? Which rules vary by Workspace? Which preview, effective-version and rollback policies apply? |
| Q-16 | Spreadsheet scope | Which range-selection, range-paste, fill-handle, formula, mass-edit, bulk-approval and saved-layout features belong in the first release? Which keyboard bindings should Tover use? |
| Q-17 | Collaboration | Where must Tover store comments and chat? Which stable records anchor discussions after grouping changes? Which notification, mention and agent-execution permissions apply? |
| Q-18 | Additional interactions | How must purchase, return and transfer record separate or partial arrivals? What entry and approval flows cover production, returns, refunds, defects, write-offs, payments and manual edits? Which management interactions must reference data and settings provide? How must users export authorized RDF domain data? |
| Q-19 | RDF deployment | Which RDF store and deployment topology pass the bounded acceptance test? Which tenancy layout, retention limits and service targets fit the operating envelope? |
| Q-20 | Semantic package | Which namespace, property definitions, Operation encoding, manual-edit encoding and quantity datatypes define the first schema? Which ontology subset, reasoner and execution order define the first semantic package? |
| Q-21 | Connectors | Which external services and accounts does the client use? Which representative exports confirm МойСклад and the reported “Rocket…” service? |
