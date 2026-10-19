# Tover v2 client UI specification

- Status: Draft.
- Date: 2026-10-08.
- Scope: Intended client behavior.
- Implementation status: This document does not establish production capabilities.
- Source: User discussions and inventory workbench exploration.
- Current workflow: The client relies heavily on spreadsheets.

## Purpose and scope

- Answer what inventory exists.
- Show what happened to inventory.
- Explain the evidence supporting each answer.
- Let users describe familiar business actions.
- Handle identity, composition, history and evidence without requiring users to operate the data model.
- Preserve direct table work, quick entry and keyboard navigation.
- Explain changes before acceptance.
- Prevent incomplete marketplace Evidence statements from silently replacing accepted inventory.
- Support period-based finance work and investigation.
- Define features, user needs, interactions and observable business rules.
- Leave visual theme, frontend framework, component library, storage implementation and query language outside this document.

### Document responsibilities

| Document | Responsibility |
|---|---|
| [Product requirements](product-requirements.md) | Domain contract, compatibility requirements, shared terminology and unresolved product decisions. |
| [Architecture](architecture.md) | Data ownership, system boundaries, isolation, persistence and execution guarantees. |
| [RDF machinery](rdf-machinery.md) | Evidence, historical selection, validation, acceptance, projections, reports and synchronization. |
| This document | Client interactions and their visible business effects. |

- Use the [inventory workbench](/Users/usuario/.codex/visualizations/2026/10/06/01a111d4-155c-7cf1-9992-87065f203711/inventory-workbench.html) as an interaction exploration.
- Treat its goods, currency, accounts, dates, command grammar, shortcuts and calculations as examples.
- Do not treat those examples as product limits or accounting policies.
- Do not use that model as implementation authority.

## Users and needs

| User need | Required outcome |
|---|---|
| Record goods before marketplace setup | Receive goods without an article, SKU, barcode or Listing. Find the goods later. |
| Work at spreadsheet speed | Edit cells. Navigate by keyboard. Organize columns. Enter actions without a separate form for every action. |
| Manage changing sale options | Support packing, unpacking, repacking, modification and relabelling. Preserve production origins and lineage. Explain input consumption and new output identities. |
| Operate several Accounts and warehouses | Keep account-specific Listings distinct. Separate selling Account reassignment from physical movement. |
| Trust inventory answers | Distinguish accepted inventory, Evidence statements, proposed changes and unresolved discrepancies. |
| Investigate discrepancies | Follow quantities, movements, decisions and original Evidence. Explain indicators. |
| Prepare finance and tax work | Provide period totals, aggregations and exports. State calculation methods and missing inputs. |
| Coordinate with colleagues and agents | Attach discussion to the relevant row, change or report. Preserve its context. |
| Retain control during automation | Explain automation. Allow interpretation corrections. Resolve ambiguity within workspace permissions. |

- A person can have several needs.
- These needs do not define new roles.
- Workspace membership alone does not establish acceptance permission.
- [UNCLEAR] Q-07: Define action permissions and automatic acceptance.

## Client terminology

| Term | Meaning |
|---|---|
| Workspace | Scope for inventory, Evidence, Accounts, permissions and reports. |
| Resource specification | A kind of good with attributes, measurement unit and optional intended composition. Client label: Goods. |
| Physical object | An internally identified physical item with dated attributes, status and Location. The tracked unit can be a pair. |
| Account | A business or external-system context with selling or source roles. Provider and external references remain optional. |
| Listing | A marketplace offer or card linked to a selling Account. One Listing can represent many Physical objects. |
| Location | A recorded physical location, including a warehouse or evidenced transit state. |
| Evidence | Original source captures and immutable parsed-statement revisions, including attributed user declarations. Parsed statements reference their original capture. |
| Operation | A business action with stable lines, type and immutable revisions. Revisions record proposed, accepted or rejected status. |

### Client labels and calculated results

| Label | Meaning |
|---|---|
| Origin | Known Operation-line reference for an item's recorded arrival or production. It does not define a separate entity. |
| Store | Client label for an Account with a selling role. |
| Source | Client label for an Account with a source role. |
| Source record | Client label for an Evidence statement. |
| Proposal / Proposed change | Proposed Operation revision or unaccepted inline manual edit, with findings and supporting Evidence. |
| Accepted change | Accepted Operation or accepted manual edit used to calculate inventory and reports. |
| Projection | A completed calculated view from accepted history and declared inputs. |
| Discrepancy | A comparable difference or conflict with an incomplete explanation. It does not automatically establish loss or platform fault. |
| Saved report version | A read-only calculated result with values, parameters, calculation methods and Evidence horizon. |
| Saved report configuration | Parameters that rerun a report against its selected inputs. It differs from a Saved report version. |

- Support multiple Accounts within one workspace.
- Permit one Account to have selling and source roles.
- Link Listings to their selling Account.
- Link captured Evidence to its source Account when applicable.
- Keep Account assignments independent from physical Location.
- Keep connector credentials separate from Account identity.
- Preserve distinct Account identities when their real contexts differ.
- Retain Ozon account support as a compatibility requirement.
- [UNCLEAR] Q-10: Define Account roles and legacy mappings.
- Treat intended recipes and Listing descriptions as different facts from actual production.
- Record actual production inputs and outputs through accepted Operations.
- Derive output composition and underlying quantities from recorded production.
- Preserve unknown composition as unknown.
- Keep unknown Origin unknown.
- Retain supplier lot identifiers as optional source attributes.
- Do not require a separate supplier-lot model.
- Preserve original Evidence through parsing corrections.
- Retain immutable Evidence statement revisions with source references.
- Capture alone does not change accepted inventory.
- Record physical and financial effects within Operations.
- Do not require separate inventory-event or financial-event entities.
- Preserve Operation identity and line references through revision and correction.
- Record acceptance and rejection within Operation revision history.
- Use business names, quantities, locations and external references during ordinary inventory work.
- Keep internal identifiers accessible in details.
- Use internal identifiers for continuity and investigation.
- Do not require manual entry of every Physical object identifier.
- Keep Origin details available without requiring a prominent default column.

### Operation types

- Use exactly these Operation types: `purchase`, `return`, `transfer`, `production`, `defect`, `sale`, `write-off`, `payment`.
- Keep action descriptions separate from Operation types.

### Purchase and arrival

- Purchase records commercial acquisition.
- Receipt means physical arrival within `purchase`, `return` or `transfer`.
- Do not add a `receipt` Operation type.
- Record acquisition and arrival together when Evidence supports both.
- Preserve physical arrival dates separately from commercial dates.
- Retain each arrival's quantity, time, Location and Evidence.
- Do not duplicate the full commercial amount across arrivals.
- Change inventory from accepted physical arrival effects.
- Do not infer arrival from an agreement, invoice or payment.
- Permit missing price when recording physical arrival.
- [UNCLEAR] Q-18: Define separate and partial arrival recording within the eight Operation types.

### Production actions

- Use `production` for packing, unpacking, repacking, repair, rework and other physical transformations.
- Treat assembly, disassembly and reassembly as action descriptions.
- Do not create Operation subtypes or formal variants for these actions.
- Consume available inputs for packing, unpacking and repacking.
- Create new output Physical objects for packing, unpacking and repacking.
- Assign new output identifiers for packing, unpacking and repacking.
- Preserve consumed input identities in production lineage.
- Do not reactivate consumed predecessors after unpacking.
- Retain identity during repair or rework only under the declared policy.
- Record attribute changes and any additionally consumed inputs for repair or rework.
- Retain familiar action labels in client workflows.
- Distinguish Production Operations from representation changes.
- Do not infer physical effects from a changed SKU.
- [UNCLEAR] Q-04: Define when repair or rework can preserve Physical object identity.

### Inline manual inventory edits

- Treat inventory adjustment as an inline manual edit.
- Do not add an inventory-adjustment Operation type.
- Retain count Evidence when a physical count supports the edit.
- Show exact previous and proposed values before acceptance.
- Retain exact previous and accepted values in edit history.
- Record actor, effective time, recording time and reason.
- Apply the same permissions, review and validation as Operations.
- Preserve accepted manual edits in immutable history.
- Recalculate derived values from accepted history.
- Do not silently overwrite a calculated balance.
- Preserve unknown origin and acquisition cost when Evidence cannot establish them.
- Do not fabricate a purchase or write-off to explain a manual edit.
- [UNCLEAR] Q-11: Define quantity edits as physical counts or corrections of earlier records.
- [UNCLEAR] Q-20: Define manual-edit history encoding without another Operation type.

## Working areas

| Area | Purpose |
|---|---|
| Stock | Show accepted inventory. Support table entry, edits, imports, attention filtering and inline review. |
| Movements | List recorded business actions across the workspace. Provide filters and contextual links. |
| Item history | Show actions and state durations for selected Goods or Physical objects. |
| Reports | Show period totals, balances, commercial results and supporting Evidence. |
| Comments and collapsible chat | Keep colleague and agent discussions attached to relevant work. |

- These areas do not require five separate tabs.
- Open Item history and comments in context.
- Keep review inline.
- Keep the main table compact.
- Reveal composition, Evidence, decisions and calculation details when requested.

## Feature catalog

### Stock and business actions

| ID | Feature | User need | Required behavior |
|---|---|---|---|
| UI-01 | Current stock table | Explain availability and distinguish accepted inventory from Evidence statements. | Show the current Projection of accepted inventory. Group rows by Goods, saleable form, Location and selling context. Distinguish proposed changes and Evidence statements. |
| UI-02 | Goods without marketplace identifiers | Record purchases before marketplace setup. | Record receipt, attributes, quantity, tracked unit, Location and available cost or Evidence. Permit missing external identifiers. Generate identities through the tracking process. |
| UI-03 | Individual tracing with grouped work | Avoid one row per object without hiding origins. | Group rows during ordinary work. Expose current items, production inputs, Origin references and lineage. Label attribution as established, policy-allocated or unknown. |
| UI-04 | Arbitrary goods, variants and units | Support goods beyond the socks example. | Support arbitrary attributes and declared units. Permit several variant lines per receipt. Permit production inputs with several specifications or Origin references. |
| UI-05 | Packing and sale options | Separate produced items from their marketplace offers. | Select available input items. Record consumption and new output identities within `production`. Associate outputs with sale options or Listings. Separate output item counts from underlying quantities. Permit multiple compositions and options. |
| UI-06 | Unpacking and repacking | Change saleable forms without losing production origins. | Record unpacking or repacking within `production`. Consume current input items. Create new output Physical objects. Preview input/output composition changes. Preserve predecessor identities in lineage. Explain remaining loose inventory. Do not restore consumed input IDs. |
| UI-07 | Production, rework and relabelling | Distinguish physical effects from identifier changes. | Separate representation edits, identity-preserving rework and consumptive transformations. Explain lineage and availability effects. [UNCLEAR] Q-18: Define complete Production Operation entry. |
| UI-08 | Shipment and arrival | Show goods in transit. | Distinguish dispatch, transit and confirmed receipt when Evidence supports those stages. Show the current stage. Permit authorized arrival confirmation. Do not invent dispatch from completion-only Evidence. |
| UI-09 | Cross-Account operations | Separate selling responsibility from physical Location. | Support multiple Accounts, including Ozon accounts. Permit selling Account or Listing reassignment without movement. Permit independent or linked physical transfers. Show both effects. |
| UI-10 | Listing and identifier history | Preserve continuity across scoped identifier changes. | Link Listings and typed identifiers to Goods using account and time context. Retain useful barcode, article and SKU combinations. Preserve physical identity during representation changes. |
| UI-11 | Sales and returns | Preserve sold history and Operation-time composition. | Record or import sales and returns using applicable Listing and composition versions. Retain sold Physical objects and previous forms. Do not invent individual allocation. [UNCLEAR] Q-03: Define return identity. [UNCLEAR] Q-18: Define return entry. |
| UI-12 | Physical counts and unexplained differences | Prevent balance edits from hiding shortages. | Record a physical count as attributed Evidence with time and scope. Compare the count with expected inventory. Propose a supported inline manual edit or explanation. Distinguish source-reported balances. Label unexplained shortages Unaccounted. Preserve unknown origin and acquisition cost for unsupported surplus. [UNCLEAR] Q-11: Define manual edit meaning and Unaccounted availability. |

| Table information | Scope |
|---|---|
| Default direction | Goods, Quantity, Location, Store and useful identifiers. Include small change, comment and Evidence indicators. |
| Workbench example | Goods, Quantity, Location, Store, Evidence, Article and SKU. |
| Optional details | Composition, underlying quantity, cost, Origin, barcode and individual identifiers. |

- Keep optional details accessible without requiring default columns.
- [UNCLEAR] Q-02: Define tracking granularity and composition measurements for known and unknown production inputs.
- [UNCLEAR] Q-03: Define physical binding and allocation.

### Spreadsheet interaction and smart input

| ID | Feature | User need | Required behavior |
|---|---|---|---|
| UI-13 | Direct cell editing | Avoid separate forms for routine work. | Start editing with pointer or keyboard. Show quantity changes as inline manual edits. Retain supporting count Evidence. Interpret Location edits as transfers. Interpret physical composition changes as actions within `production`. Interpret identifier edits as assignment changes. Preserve history. |
| UI-14 | Editable and calculated values | Prevent edits from contradicting derived inventory. | Distinguish editable inputs from calculated results. Change derived quantity and Origin references through contributing Operations or accepted manual edits. Change Location through transfers. Do not silently overwrite calculated balances. |
| UI-15 | Column organization | Adapt the table to receiving, selling and investigation. | Support table sorting. Reorder columns. Resize columns. Show or hide columns. Keep units and meanings clear. Retain issues when their indicators are hidden. |
| UI-16 | Keyboard navigation and commands | Reduce repeated pointer use. | Support keyboard navigation, editing, confirmation, cancellation and contextual actions. Provide shortcut help. Provide a command palette. [UNCLEAR] Q-16: Define exact bindings. |
| UI-17 | Smart input with immediate interpretation | Make free-text actions understandable without lengthy forms. | Parse action or search intent while the user types. Extract parameters. Offer relevant options. Show interpretation immediately. Preview effects before submission. Clarify missing or ambiguous Goods, unit, Location or Account in context. |
| UI-18 | Search, filtering and selection | Find relevant goods through familiar references. | Search names, attributes, article, SKU, barcode, Location and Account using context. Use selection for actions and history. Do not silently change command meaning through selection. |
| UI-19 | Contextual item details | Inspect details without crowding the table. | Open production composition, Origin, identities, status, Listing assignments, Evidence and related Operations from a row or selection. Reveal details progressively. Keep the table usable. |

- Row selection does not establish bulk-edit or bulk-approval behavior.
- [UNCLEAR] Q-16: Define range paste, fill handles, formulas, mass edits, bulk approval and saved arrangements.

### Inline review, files and synchronization

| ID | Feature | User need | Required behavior |
|---|---|---|---|
| UI-20 | Inline indicator and diff | Review changes beside affected inventory. | Mark affected rows with a small indicator. Expand a familiar before-and-after diff inline. Do not add a separate Review tab. Do not require a separate review page. |
| UI-21 | Approve, discard and edit | Correct interpretation without restarting entry. | Permit contextual review, editing and discard of proposed changes. Create a new Operation revision for each Operation edit. Preserve manual edit history. Permit authorized approval after validation. Record the reviewed change on approval. Record discard as rejection. Do not require an additional routine Commit step. Retain Evidence after discard. |
| UI-22 | Impact before acceptance | Expose effects hidden by simple value differences. | Show action, affected values, quantity, unit, Location, account and composition effects. Show known financial impact. Show source and reason. Present linked row effects as one coherent change. |
| UI-23 | Concurrent and historical change protection | Protect reviewed changes from stale inputs. | Recheck the relevant state before acceptance. Explain changed baselines or dependent history. Show refreshed impact for review. Do not silently apply another change. |
| UI-24 | File upload and parsing | Reuse exports and documents without manual re-entry. | Choose or drop spreadsheet files. Parse records. Retain original values and file, sheet and row references. Support CSV, TSV and Excel workflows. Retain image and text operation imports. [UNCLEAR] Q-18: Define their entry details. |
| UI-25 | Import into the working table | Reuse inline review without a separate wizard. | Return to Stock after capture and parsing. Show proposed changes and attention markers. Reuse inline diffs. Show progress, failures and usable partial results. Do not require an intervening confirmation page. |
| UI-26 | Shared service-sync workflow | Reuse reconciliation across external services. | Present synchronization through the Stock, Operation and Evidence workflow. Reuse this workflow for marketplace, accounting and delivery feeds. Show source, account, capture time and coverage. [UNCLEAR] Q-21: Verify connectors, including reported МойСклад usage and the uncertain “Rocket…” service. |
| UI-27 | Needs-attention filter | Focus on unresolved work without losing the full table. | Filter pending changes, discrepancies, ambiguous references, unmatched references and parsing or coverage issues. Show their count. Provide a clear return action. Keep unmatched records reachable without a Stock row. |
| UI-28 | Context-aware matching | Prevent incorrect identifier-only matches. | Use typed identifiers, account, time, composition and other available context. Offer candidates and supporting Evidence when uncertain. Treat match acceptance as interpretation. Do not imply movement or receipt. |
| UI-29 | Interpretation and mapping correction | Correct imported meaning without losing the source. | Permit contextual corrections to columns, parsed values, units, warehouses, accounts and mappings. Preview effects. Retain original Evidence and preceding interpretations. Validate new interpretations. Require applicable acceptance. |
| UI-30 | Repeated imports and conflicting sources | Prevent duplicates without concealing revised statements. | Explain new Evidence, revised statements or unchanged effects. Preserve previous decisions and source revisions. Prevent duplicate accepted effects from repeated delivery. Show conflicting API, file and manual Evidence statements. Do not select the newest import automatically. |
| UI-31 | Evidence inspection and reconciliation | Explain comparability and unexplained differences. | Show exact source record, scope, units, dates and freshness. Compare with the applicable ledger answer. Explain accounted movements and unexplained remainder. Permit context, supported correction or a recorded decision retaining the current answer. |

- Distinguish capture, parsing and acceptance outcomes.
- Show unresolved records after successful upload.
- Show source disagreement after successful synchronization.
- Do not create a Physical object merely because a source row has no match.
- [UNCLEAR] Q-08: Define source authority, coverage and comparability.
- [UNCLEAR] Q-09: Define occurrence matching and cross-source duplicate detection.

### Movements and Item history

| ID | Feature | User need | Required behavior |
|---|---|---|---|
| UI-32 | Workspace Movements | Inspect actions across inventory. | List recorded Operations and accepted manual edits over time. Provide filters. Link Goods and Evidence. Name Operation types, production actions, dispatch, arrival and representation or cost corrections. Treat Movements as broader than warehouse transfers. |
| UI-33 | Unified Item history | Explain the complete lifecycle in one view. | Show selected actions and resulting states. Include origin, composition, Location, identifier assignments, sales, returns, physical changes and corrections. Keep purchases and sales together. |
| UI-34 | Gantt / network-waterfall history | Show time spent in places or stages. | Align state-duration bars with named actions. Show transit, warehouse residence, sale and retirement. Represent identifier edits as actions without fabricated movement. Show uncertain interval boundaries. |
| UI-35 | Bar and action inspection | Make history details useful. | Open entry and exit actions, previous and resulting state, dates, quantity, unit, composition, objects, Origin, actor and Evidence. Offer contextual discussion, production inputs, predecessors or evidenced arrival confirmation. |
| UI-36 | History scope and knowledge | Prevent current facts from misrepresenting earlier lifecycles. | Navigate from grouped Goods to Physical objects and their production history. Include sold, consumed or retired predecessors. Distinguish occurrence and recording time. Label current-knowledge and earlier-knowledge views. |

- Stock shows the current accepted inventory.
- Movements lists recorded workspace actions.
- Item history explains how Goods or Physical objects reached their states.
- Distinguish proposed changes from accepted history.
- Link proposed changes to inline review.
- [UNCLEAR] Q-05: Define uncertain dates and conflicting action order.
- [UNCLEAR] Q-06: Define correction conflicts and dependent historical effects.

### Collaboration and agents

| ID | Feature | User need | Required behavior |
|---|---|---|---|
| UI-37 | Contextual comment threads | Retain discussion beside inventory questions. | Attach threads to rows, proposed changes, source records, accepted changes or report contexts. Preserve context after identifier or grouping changes. Use small indicators. Reveal threads on demand. |
| UI-38 | Resolve and reopen discussion | Separate active questions from completed discussion. | Permit thread resolution and reopening. Retain messages and attribution. Do not equate resolution with acceptance, factual confirmation or discrepancy resolution. |
| UI-39 | Collapsible human and agent chat | Keep collaboration available without permanent table displacement. | Provide collapsible workspace chat. Identify human and agent authors. Link relevant inventory context. Keep the table primary. [UNCLEAR] Q-17: Define mentions, notifications and agent request permissions. |
| UI-40 | Explainable automation and human control | Automate safe steps while preserving review authority. | Explain automated outcomes with reasons and Evidence. Route uncertain agent-proposed changes through ordinary review. Permit authorized rejection, correction and interpretation override. Retain decision history. Permit automatic acceptance only under declared policy. |

- Change accepted inventory through authorized visible business actions.
- Do not use chat or agent replies as hidden inventory edits.
- Retain user-customizable rules as a v2 goal.
- [UNCLEAR] Q-15: Define rule authoring and governance.
- [UNCLEAR] Q-17: Define collaboration data ownership and durable context anchors.

### Reports and finance work

- Select the report and period.
- Read totals and completeness.
- Open named details when needed.
- Save or export an explainable calculated version.
- Retain current-balance and operational reports alongside period reports.

| ID | Feature | User need | Required behavior |
|---|---|---|---|
| UI-41 | Period and scope selection | Prepare finance work across Accounts and periods. | Offer month, quarter, year and appropriate period selection. Expose workspace, Account, Goods and relevant dimensions. State currency and timing basis. Do not assume Account equals legal entity. |
| UI-42 | Profit and loss | Explain profitability and prepare finance calculations. | Show gross sales, returns, refunds, net revenue, cost of goods sold and gross profit. Show fees, expense subtotals and supported operating result before tax. Include relevant delivery, storage and advertising expenses. Label missing inputs and provisional results. |
| UI-43 | Cash and marketplace settlements | Separate earnings, marketplace balances and received money. | Separate cash receipts and payments from earned income and charges. Show supported opening balances, closing balances, payouts, supplier payments, other payments and outstanding settlements. Show net cash movement. State the date basis. Do not treat marketplace payout as proof of bank receipt. |
| UI-44 | Sales and profitability aggregation | Avoid fragmentation from identifier and composition changes. | Aggregate by Goods, sale option, composition, Listing, Account and period as appropriate. Show sold forms, underlying quantities, revenue, cost and profit. Label known and unknown inputs. Preserve historical Listing and composition. Prevent duplicate sales through identity joins. |
| UI-45 | Inventory quantity and valuation | Show period-end inventory and supported value. | Report quantities and supported valuation by Goods, Location and saleable form at the selected cutoff. Distinguish available, in-transit and unresolved status. Separate known valuation from missing cost basis. |
| UI-46 | Totals and subtotals | Provide meaningful aggregates without mixing incompatible units. | Provide defined totals, groups and subtotals. Separate output item counts from underlying quantities. Exclude consumed predecessors from current stock totals. Aggregate quantities only across compatible units. Label allocation and conversion assumptions. |
| UI-47 | Named detail actions | Make drill-down discoverable. | Offer named actions such as Show transactions, Show sales and Cost sources. Link summaries to contributing records and objects. Link contributions to original Evidence. Do not rely on unexplained clickable numbers. |
| UI-48 | Report methods and completeness | Prevent incomplete inputs from producing falsely complete totals. | Expose period, scope, timing, valuation, allocation, inputs and exclusions. Show incomplete or provisional status. Identify missing inputs. Label known subtotals. |
| UI-49 | Saved report versions | Preserve previously shared figures after correction. | Save calculated values, parameters, calculation methods and Evidence horizon as a read-only version. Preserve that version after corrections. Distinguish Saved report configurations from Saved report versions. |
| UI-50 | Finance exports | Support bookkeeping and external review. | Export CSV or Excel summaries and detailed records. Include period, scope, currency, calculation methods, Evidence references and unresolved inputs. Export the inspected report version. Do not claim statutory readiness without defined accounting and jurisdiction policies. |
| UI-51 | Report discussion | Preserve meaning when live reports change. | Attach comments to the period, scope and calculated version or record. Keep discussed values and supporting explanations accessible. |

- Retain profit and loss, cash and settlements, sales and profitability, and inventory reporting directions.
- Do not adopt illustrative workbench formulas as accounting policy.
- [UNCLEAR] Q-12: Define valuation, costs, fees, returns, allocation, supplier debt, currency, timing, period closing and turnover measures.
- [UNCLEAR] Q-13: Define jurisdiction, tax regime, VAT, legal entities and accountant handoff.
- Support finance preparation before claiming statutory filing compliance.

### Retained capabilities

- Preserve user outcomes from the v2 compatibility contract.
- Do not require previous forms or navigation.

| ID | Capability | User need | Required behavior |
|---|---|---|---|
| UI-52 | Workspace access and permissions | Share work safely. | Retain authentication, organizations, invitations and roles. Enforce permissions for actions, originals, comments, reports and exports. [UNCLEAR] Q-07: Define edit, propose, approve and override permissions. |
| UI-53 | Reference data and settings | Reuse familiar business information and conventions. | Retain categories, Accounts, channels, warehouses, suppliers and Resource specifications. Retain workspace currency, category and selling Account requirements or defaults. Retain English and Russian presentation. [UNCLEAR] Q-18: Define management interactions. |
| UI-54 | Quality, Operations and manual edits | Record the complete business workflow. | Retain the eight Operation types. Retain quality tracking and inline manual inventory edits. [UNCLEAR] Q-18: Define detailed entry and approval interactions. |
| UI-55 | Existing reports and alerts | Preserve useful operational and financial answers. | Preserve supplier purchases, payments, debt, movement, sales volume, turnover, defects and critical-stock reports or alerts. Provide meaningful filters and Evidence. [UNCLEAR] Q-12: Define formulas. [UNCLEAR] Q-01: Define release placement and parity. |
| UI-56 | Existing imports and portable data | Retain working imports and prevent data lock-in. | Retain orders, order-lines, inventory and payments CSV outcomes. Retain reviewable spreadsheet, image and text operation imports. Preserve multiple items and source references. Support authorized domain export and report export. [UNCLEAR] Q-18: Define the RDF export interface. |
| UI-57 | Honest migration history | Prevent invented individual history from legacy balances. | Identify migrated opening balances and historical limitations. Retain useful legacy records. Do not present inferred Physical object lifecycles as established facts. |

## Client business rules

### Accepted state

- Distinguish Evidence statements and proposed changes from accepted inventory.
- Show the last completed Projection while calculation catches up.
- Label pending work.
- Do not present partial calculations as current state.

### Action meaning

- Show business intent and effects before review.
- Separate selling Account and Listing association from physical Location.
- Distinguish snapshots from receipts.
- Distinguish identifier edits from Production Operations.
- Treat imported text as source material.
- Do not treat imported text as instructions to the application or agents.

### Coherent acceptance

- Check permissions against the reviewed baseline.
- Check Evidence against the reviewed baseline.
- Check inventory validity against the reviewed baseline.
- Accept all effects of a linked action together.
- Explain failure or stale previews.
- Retain failed proposed changes for correction.

### Historical correction

- Identify the affected action, effective time, actor and reason.
- Preserve preceding assertions, decisions and Evidence.
- Separate occurrence time from capture and recording time.
- Preserve unknown time precision.

### Actual traceability

- Show physical binding and allocation methods.
- Do not treat internal identifiers as proof of actual individual allocation.
- Do not treat external references as proof of actual individual allocation.
- Exclude currently sold, consumed or retired Physical objects from availability.
- Preserve those Physical objects in history.
- Permit supported returns to change the appropriate current status.

### Quantity and Location

- Consume available inputs during packing, unpacking and repacking within `production`.
- Create new output Physical objects during packing, unpacking and repacking within `production`.
- Preserve previous identities only in input/output lineage.
- Derive underlying quantities from recorded production inputs and output composition.
- Separate output item quantities from underlying quantities.
- Do not add consumed predecessors to current stock.
- Record explicit yield, waste or conversion effects when underlying quantities change.
- Move current output Physical objects directly.
- Sell current output Physical objects directly.
- Exclude in-transit goods from destination availability until accepted receipt.
- Explain unavailable inputs and invalid production quantities.
- Reject invalid intermediate states even when final totals appear correct.

### Comparable reconciliation

- Check Goods, composition, unit, account, warehouse, stock category, period and coverage before comparison.
- Do not interpret failed requests, missing rows or partial coverage as zero.
- Preserve conflicting alternatives.
- Do not automatically prefer the latest capture.

### Decision limits

- Do not infer loss, write-off or platform fault from unexplained quantity.
- Distinguish discussion resolution from discrepancy resolution.
- Distinguish matching from physical action.
- Distinguish import success from physical action.
- Do not treat a decision as proof of unavailable source Evidence.
- Explain what each decision settles.
- Show remaining uncertainty.

### Historical report methods

- Use Listing and composition versions applicable at sale time.
- Label current-knowledge views.
- Label earlier-knowledge views.
- Label reinterpretations under newer policies.
- Separate gross sales, returns and net sales.
- Separate sales flows from counts of distinct Physical objects ever sold.

### Financial meaning

- Distinguish transaction price, invoice amount, discount, payment and valuation.
- Preserve missing financial inputs as unknown.
- Do not present incomplete totals as complete.
- Do not create sales revenue from production, relabelling or transfers.
- Apply declared cost and charge allocation policies.
- Separate earned income from actual cash receipts and payments.

### Consistent report outputs

- Use the same calculated version and scope for totals, details, Evidence and export.
- Preserve explanations for saved values after correction.
- Distinguish Saved report configurations from Saved report versions.

### Bounded authority

- Enforce workspace permissions for viewing, discussion, acceptance and export.
- Start marketplace synchronization with inbound read-only operations.
- Do not publish remote cards or prices through local Listing edits.
- Require an authorized workflow for external mutations.

## Proposed first path

- Status: Proposed.
- [UNCLEAR] Q-01: Confirm the first lifecycle, release scope and compatibility gates.
- The journeys below describe intended workflows.
- Their inclusion does not assign first-release priority.

## User journeys

### Receive, produce, ship and sell

1. Enter purchase arrival details through smart input, direct table work or import.
2. Review Goods, quantity, unit, Origin and Location.
3. Permit missing external identifiers.
4. Record packing through `production` using available input items.
5. Inspect composition and underlying quantity.
6. Associate the saleable form with a Listing or Account where appropriate.
7. Record dispatch when Evidence supports it.
8. Record arrival within the transfer when Evidence supports it.
9. Follow transit in the same inventory context.
10. Capture sales using applicable Listing and composition versions.
11. Show individual-allocation uncertainty.
12. Select unsold goods for unpacking, repacking or Listing reassignment.
13. Review inline effects.
14. Retain consumed input history and new output identities.

### Import or synchronize

1. Upload a file or start service synchronization.
2. Show progress, source, account, coverage and parsing outcomes.
3. Continue in Stock.
4. Use Needs attention to focus on unresolved records.
5. Expand a proposed Operation diff or source discrepancy.
6. Correct interpretation or matching when required.
7. Inspect Evidence when required.
8. Discuss missing context when required.
9. Approve a valid proposed Operation within permissions.
10. Discard unwanted proposed changes.
11. Retain unresolved questions.
12. Preserve captured Evidence for every outcome.
13. Return to the full table.
14. Distinguish accepted changes from unresolved items.

### Investigate history

1. Open Movements for a workspace question.
2. Open Item history for selected Goods or Physical objects.
3. Read named actions with duration bars.
4. Follow production inputs or predecessors when needed.
5. Open an interval or Operation.
6. Inspect effects, dates, actor and Evidence.
7. Use a relevant contextual action.
8. Propose historical corrections with effective time and reason.
9. Inspect affected results.
10. Review the correction inline.

### Prepare a period report

1. Choose a report and period.
2. Set Account and other relevant scope.
3. Read totals, subtotals and completeness.
4. Resolve missing inputs where practical.
5. Label remaining incompleteness.
6. Open named transaction, cost and Evidence details when needed.
7. Discuss questions in report context.
8. Save or export a Saved report version.
9. Preserve earlier versions when a rerun produces new results.

## Client acceptance examples

- Retain the [domain acceptance examples](product-requirements.md#reporting-and-acceptance-examples).
- Treat counts, goods and Accounts below as examples.

| Situation | Expected behavior |
|---|---|
| Edit quantity or Location. | Show an inline manual quantity edit or a transfer intent. Show exact previous and proposed values. Preserve accepted inventory until acceptance. Retain actor, times, reason and immutable history. |
| Assign a new SKU in another Ozon Account without movement. | Show representation and account changes. Retain Physical object identities and Location. Preserve historical identifier interpretation. |
| Receive 100 pairs. Import 90 sold and zero remaining. | Show ten unexplained pairs when scope and movements are complete and comparable. Otherwise show comparability or coverage limits. Do not infer write-off or platform fault. |
| An identifier matches several accounts or Listings. | Preserve the source row. Show contextual candidates. Keep interpretation unresolved until Evidence or a recorded decision supports it. |
| Open a warehouse-duration bar. | Show entry and exit actions, dates, quantity, production composition, Origin and Evidence. Offer contextual actions. Show uncertain boundaries. |
| Import valid rows, an unmatched reference and a parsing error. | Continue in Stock. Expose unresolved records through Needs attention. Show inline proposed changes. Do not require another confirmation page. |
| Another user changes available inputs before repacking approval. | Explain the stale preview. Show refreshed valid options. Do not silently apply different production inputs or outputs. |
| A monthly report lacks some sales costs. | Show known revenue and cost subtotals. Show missing cost scope. Mark overall profit incomplete. |
| Correct a January movement in February. | Permit current-knowledge January results to change. Preserve earlier Saved report versions and earlier-knowledge explanations. |
| Resolve a shortage comment. | Resolve the thread. Keep the shortage unresolved until a supported decision or action addresses it. |

## Domain alignment

| Client area | Domain requirements | Client commitment |
|---|---|---|
| Goods and physical lifecycle | PR-01–09, PR-39 | Grouped table work, business intents, object details and dispatch, transit and arrival. |
| Accounts and identifiers | PR-10–15 | Multiple Accounts. Separate selling Account reassignment from physical movement. |
| Import, matching and trust | PR-16–23, PR-30, PR-33–34 | Inline diffs, Needs attention and interpretation correction. No mandatory separate import-review page. |
| Movements and Item history | PR-24–29 | Unified action and state history, duration bars and useful inspection. |
| Financial reporting | PR-26–32, PR-40, compatibility requirements | Period reports, named details, Saved report versions and finance exports. Apply declared financial policies. |
| Collaboration and control | PR-33–36 | Contextual threads, discussion resolution, collapsible chat and visible agent-proposed changes. |
| Existing workflows | PR-31, PR-36–38, compatibility requirements | Preserve useful outcomes without requiring previous navigation or dialogs. |

- Present architecture guarantees in business language.
- Preserve Evidence retention, historical selection, validation, concurrency, calculation consistency and reproducibility.
- Do not weaken these guarantees for UI convenience.
- Leave storage vendor selection to architecture.

## Unresolved policies and interactions

| Topic | Unresolved scope |
|---|---|
| Release scope | [UNCLEAR] Q-01: First lifecycle, report and import parity, migration limits and operational-authority gates. |
| Tracking granularity | [UNCLEAR] Q-02: Bulk goods, weighed goods, migrated aggregates and known or unknown production composition measurements. |
| Physical identity | [UNCLEAR] Q-03: Labels, scans, operator selection, allocations, individual movements and returned-object identity. |
| Identity-preserving rework | [UNCLEAR] Q-04: Conditions for repair or rework to preserve Physical object identity. |
| Dates | [UNCLEAR] Q-05: Date-only records, uncertain times and conflicting simultaneous actions. |
| Corrections | [UNCLEAR] Q-06: Dependent corrections, conflict resolution and corrections crossing query cutoffs. |
| Source comparison | [UNCLEAR] Q-08: Authority by field or process, API/export equivalence, stock categories, coverage and matching explanations. |
| Duplicate detection | [UNCLEAR] Q-09: Repeated captures, revised occurrences and duplicate Operations across sources. |
| Account roles | [UNCLEAR] Q-10: Selling and source roles, external references and legacy Account mappings. |
| Physical counts | [UNCLEAR] Q-11: Quantity edits as counts or record corrections, Unaccounted availability, acceptance and individual allocation. |
| Permissions and automation | [UNCLEAR] Q-07: Propose, approve, override, reasons, automatic acceptance and policy inspection or changes. |
| Financial methods | [UNCLEAR] Q-12: Valuation, Production costs, discounts, fees, shared expenses, partial returns, supplier debt and currencies. Accrual timing, payment timing, period closing and turnover definitions also need decisions. |
| Tax and legal scope | [UNCLEAR] Q-13: Jurisdiction, tax regime, VAT, legal entities and accountant handoff. |
| Evidence administration | [UNCLEAR] Q-14: Retention, access, redaction and export administration. |
| Rule administration | [UNCLEAR] Q-15: Authoring, activation and historical-effect previews. No dedicated interface exists in this specification. |
| Spreadsheet breadth | [UNCLEAR] Q-16: Range selection, paste, bulk edits, bulk approval, formulas, fill handles, saved arrangements and exact shortcuts. |
| Collaboration | [UNCLEAR] Q-17: Ownership, grouped-row anchors, notifications, mentions and agent request or execution permissions. |
| Additional entry | [UNCLEAR] Q-18: Separate arrivals, partial arrivals, production actions, returns, refunds, defects, write-offs, payments and inline manual edits. Reference data and export interactions also need decisions. |
| Manual-edit encoding | [UNCLEAR] Q-20: Immutable manual-edit history encoding without another Operation type. |
| Connectors | [UNCLEAR] Q-21: Verified services and accounts. The workbench does not prove integration or collaborative persistence. |

- These policies do not block agreement on working areas and feature inventory.
- Resolve each policy before implementing its affected workflow.
- Read the [central clarifying questions](product-requirements.md#clarifying-questions).
