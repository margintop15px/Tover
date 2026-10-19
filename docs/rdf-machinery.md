# Tover v2 RDF machinery

- Status: proposed execution contract.
- RDF supplies the chosen domain foundation.
- This contract defines required machinery.
- This contract does not describe an implemented production service.
- [Product requirements](product-requirements.md) define the domain contract.
- [Architecture](architecture.md) defines storage, authorization and transaction boundaries.

## 1. Schema and semantic package

### Package contents

| Component | Contents |
|---|---|
| Application vocabulary | Classes, properties, units, identifier scopes, Operation payloads and relation meanings. |
| Semantic package | Application vocabulary, RDFS/OWL axioms, SHACL shapes, derivation rules, connector mappings and report queries. |
| Released version | Immutable ID and content checksums. |
| Projection manifest | Versions that the Projection actually used. |

- Version the application vocabulary.
- Release the package components together.
- Record the versions that each Projection used.
- Reuse a small [Valueflows resource/event profile](https://www.valueflo.ws/concepts/resources/).
- Reuse [PROV-O](https://www.w3.org/TR/prov-o/) provenance.
- Maintain explicit mappings to [EPCIS aggregation, transformation and movement events](https://ref.gs1.org/standards/epcis/2.0.1/).
- These vocabularies support integration.
- These vocabularies do not determine Tover's acceptance policy.
- These vocabularies do not determine Tover's accounting policy.
- [UNCLEAR] Q-20: Namespace and complete property definitions.

### Domain entities

| Entity | Minimum meaning and links |
|---|---|
| Resource specification | Goods characteristics, unit/counting basis, optional expected composition and dated revisions. Client label: Goods. |
| Physical object | Stable internal IRI, Workspace, Resource specification, Evidence and origin when known. External identifiers remain optional. |
| Account | Business context with optional provider/external references and nonexclusive selling/source roles. |
| Evidence | Original capture, scope and immutable parsed-statement revisions. Statements can contain incomplete, conflicting or aggregate information. |
| Operation | Type, stable lines, immutable revisions, acceptance status, actor, Evidence, business/recording times and explicit effects. |
| Listing | Account representation, commercial description, saleable-form offer, price/discount Evidence and dated revisions. |
| Workspace | Authorization and domain isolation boundary. |
| Location | Physical location, including warehouse references and evidenced transit states. |

### Operation types

- Use exactly eight Operation types.
- `purchase`, `return`, `transfer`, `production`, `defect`, `sale`, `write-off`, `payment`.
- Use `production` for assembly, disassembly, reassembly, packing, unpacking, repacking, repair and rework.
- Receipt names an arrival effect within `purchase`, `return` or `transfer`.
- Manual inventory adjustment uses an inline edit.
- Manual inventory adjustment does not add an Operation type.

### Retained record contracts

| Record or relation | Required data |
|---|---|
| Production composition | Actual input/output quantities, units and attribution recorded by accepted Production Operations. |
| Physical object origin | Arrival or production Operation-line reference when known. Supplier, source documents and dated quantities when known. |
| Optional lot reference | Supplier/manufacturing lot ID in Evidence or Operation-line attributes. |
| External identifier assignment | Type, value, issuing system, supplier/Account scope, target, known validity/recording times and Evidence. |
| Operation line | Stable ID, type-specific targets and effects. Optional object quantities, locations, inputs/outputs or monetary amount/currency. |
| Proposed Operation revision | Proposed effects, alternatives, validation findings and acceptance/rejection/override actor and reason. |
| Projection | Reproducible derived result, inputs, policy versions, certainty and unresolved differences. |
| Saved report version | Reproducible calculated result and its retained basis. |
| Discrepancy | Derived conflict or unexplained difference, linked inputs, certainty and resolution history. |

- Resource specification changes do not necessarily change physical identity.
- Assembly, disassembly and reassembly consume input Physical objects through Production Operations.
- Create new identified output Physical objects for packing, unpacking and repacking.
- Preserve consumed input identities for historical tracing.
- Unpacking does not reactivate earlier input identities.
- Record actual input/output composition and quantity attribution.
- Recipes and Listing descriptions do not establish actual production composition.
- Listings remain separate from Physical objects.
- Trace mixed origins through Production Operation input/output lineage.
- Preserve optional supplier/manufacturing lot IDs without creating lot entities.
- Keep distinct real Accounts distinct.
- Permit one Account to serve selling and source roles.
- Store optional provider/external references on Accounts.
- Exclude credentials from Accounts and Evidence.
- [UNCLEAR] Q-10: Account roles and external-system mapping.
- Evidence includes original captures and immutable parsed-statement revisions.
- Parsing Evidence does not accept inventory changes.
- Propose Operation changes through proposed Operation revisions.
- Operations carry inventory and financial effects.
- Do not require physical fields for financial or interpretation-only Operation lines.
- Accepted inventory state derives from accepted Operation effects and accepted inline manual edits.
- Preserve explicit unknown origin status.
- Do not invent receipt or production history for unknown origins.
- Use ordinary RDF nodes for these records.
- Represent assertions/retractions through reified subject/predicate/object statements or equivalent explicit Operation-line records.
- The proof uses reified event statements.
- External standards retain their event terminology.
- Map their events to Operations without adding separate domain event classes.
- RDF reification describes a statement.
- RDF reification does not assert that statement in the active inventory graph.
- Preserve this separation in the final Operation encoding.
- Verify the applicable temporal contract before replacing the proof encoding.
- Revalidate encoding against the current production identity model.
- The first version does not require RDF-star.
- [UNCLEAR] Q-20: Final Operation encoding.
- Record dated revisions for historically relevant Physical object attributes, recipes and Listing definitions.
- The proof treats some catalogs as timeless.
- Exclude today's composition and product classification from older views unless their historical revisions support those values.

## 2. Business rule responsibilities

- Assign each requirement to its responsible mechanism.
- OWL does not automatically implement every business rule.

| Business rule | OWL/RDFS | SHACL | Derivation or command policy |
|---|---|---|---|
| Assembly, disassembly and reassembly create new output Physical objects. | Physical object and Operation input/output vocabulary. | Validate available inputs and identified outputs. | Consume inputs once. Derive output lineage. |
| A Listing differs from physical stock. | Separate classes. Safe disjointness if the profile permits it. | Reject incompatible record roles. | Keep mapping assignments explicit. |
| An accepted Operation has Evidence and both times. | Property definitions. | Required fields, types and Workspace references. | Assign durable recording time server-side. Verify authorization. |
| A Physical object has one supported effective Location. | Location vocabulary. | Reject conflicting direct Locations. | Select direct Location from effective Operations. |
| A Production input must be available. | Input/output vocabulary. | Validate the immediate preceding state and transition findings. | Evaluate availability before the Operation. |
| A correction replaces earlier effects. | Correction relation. | Reject cycles and unresolved competing branches. | Select effective Operations through eligibility and supersession rules. |
| An identifier can change or undergo reuse. | Scoped assignment vocabulary. | Validate scope, target and overlapping conflicts. | Compute intervals and evidence-based matches. Preserve distinct physical IDs despite matching values. |
| Inventory reconciles in compatible units. | Unit and quantity vocabulary. | Reject invalid decimals, incompatible units and unsupported conversions. | Apply explicit conservation/conversion policy, including waste or yield. |
| A marketplace snapshot differs from Tover. | Evidence statement vocabulary. | Validate the supplied source statement. | Compare compatible measures. Create an evidence-linked Discrepancy. |

### Bounded inference

- [OWL uses open-world semantics](https://www.w3.org/TR/owl2-primer/).
- Missing information does not automatically mean false information.
- Different names do not automatically identify different individuals.
- Domain/range and cardinality axioms produce semantic entailments.
- These axioms do not perform required-field or operational uniqueness checks.
- Do not silently merge inventory records through OWL keys, inverse-functional identifiers or `owl:sameAs`.
- Application IDs and commit constraints enforce operational uniqueness.
- Keep identity-resolution decisions explicit and auditable.
- Start with named classes, subclass/subproperty relationships and a small approved OWL axiom subset.
- Validate asserted records before inference can conceal missing required fields.
- Run bounded entailment on the selected accepted view.
- Run shapes that require that entailment afterward.
- Include the subset, reasoner and execution order in the semantic package.
- Do not depend on global store inference defaults.
- Exclude other Workspaces and unselected source claims from reasoning.
- [UNCLEAR] Q-20: Approved OWL subset, reasoner and execution order.

### Rule execution

- The proof uses ordered [SHACL-AF SPARQL rules](https://www.w3.org/TR/shacl-af/#rules) for its nonmonotonic temporal policy.
- SHACL-AF is a Working Group Note.
- SHACL-AF requires separate engine support.
- Prove equivalent comparisons, ordering, stratified negation and aggregation before adopting a Datalog engine.
- The proof does not test native Datalog.

## 3. Evidence and provenance

- Link every accepted Operation to Evidence and a decision.
- Accept attributed user declarations as Evidence without requiring attachments.
- Record each declaration's author, statement, time and certainty.
- Do not describe a user declaration as a supplier document.
- Identify AI extractions and rule conclusions as derived Evidence.
- Preserve their attribution to their generating activity.

### Required provenance fields

| Record | Required fields |
|---|---|
| API capture | Workspace, connector, Account, endpoint and contract version. |
| API capture: request | Filters, window and pagination. |
| API capture: response | Capture time, source time when supplied, response status, coverage/completeness, retained response reference and digest. |
| File capture | Workspace, private object key/version, media type, byte length, digest algorithm/value, uploader and upload time. |
| File capture: source period | Source date/period when known. |
| Extracted statement | Capture reference, sheet/row/columns or page/region/text span. |
| Extracted statement: interpretation | Original value/unit, parser/model/mapping version, errors and unknown fields. |
| Identity decision | All typed identifier claims, candidate targets, considered time/scope, supporting Evidence and conflicting Evidence. |
| Identity decision: authority | Rule or human actor, decision time and reason. |
| Operation revision: accepted | Proposed revision, Evidence statements, actor/decision, semantic package, business time and server recording time. |
| Operation revision: commit | Commit revision and idempotency receipt. |
| Inline manual edit | Exact before/after effects, actor, business/recording times, reason, Evidence, permissions and acceptance history. |
| Derived result | Accepted inputs, Projection manifest, rule/query version, generation activity, cutoffs, warnings and coverage. |

### Quantities and financial effects

- Record parties, amounts, currency and Evidence for financial Operation effects, including supplier payments.
- Financial effects do not establish physical effects.
- Require type-specific payloads through Operation shapes.
- Use exact decimals and explicit units for quantities.
- Use integral counts for discrete Physical objects.
- Retain legitimate zero-stock Evidence statements as Evidence.
- Declare precision before rules use quantities.
- Declare rounding before rules use quantities.
- Declare supported conversions before rules use quantities.
- [UNCLEAR] Q-02: Quantity-based tracking and measurement policy.
- [UNCLEAR] Q-12: Financial precision, rounding and conversion policy.
- [UNCLEAR] Q-20: Quantity datatype encoding.

### Purchase, arrival and payment

- Buying does not establish physical arrival.
- Buying does not establish payment.
- Record purchase and arrival together when one Operation describes both supported occurrences.
- Receipt remains an arrival meaning or effect within `purchase`, `return` or `transfer`.
- Record each separate arrival's time, quantity and Evidence.
- Do not duplicate commercial amounts when recording separate or partial arrivals.
- [UNCLEAR] Q-18: Encoding separate or partial arrivals within the eight Operation types.
- Record payment through its own supported Operation effects.

### PROV links

| PROV concept | Tover role |
|---|---|
| Entity | Evidence and results. |
| Activity | Capture, extraction, decision and Projection generation. |
| Agent | User, connector or worker actor. |
| Links | `prov:wasDerivedFrom`, `prov:used`, `prov:wasGeneratedBy`, `prov:wasAssociatedWith`. |
| Application properties | Source scope, trust and temporal policy. |

- A checksum verifies retained-byte consistency.
- A checksum does not prove the physical truth of source content.

### Capture integrity

- Store transport credentials separately.
- Exclude transport credentials from retained Evidence.
- Preserve original permitted content in private storage.
- Identify each redacted or normalized copy as a separate derivative.
- Record each derivative's checksum.
- Record each derivative's transformation.
- Name the canonicalization format for normalized JSON hashes.
- Do not describe normalized JSON hashes as original-response byte hashes.
- S3 ETags do not provide a universal content digest.
- Verify the provider's [object integrity semantics](https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity-upload.html).
- [UNCLEAR] Q-14: Permitted original content, retention, sensitive fields and redaction.
- Raw Evidence can survive parsing or validation failures.
- Keep failures and partial captures visible.
- Preserve unresolved file lines.
- Do not interpret API errors, incomplete page sets or missing fields as zero inventory.
- A current retrieval cannot recover an earlier source revision that the source overwrote.

## 4. Historical records and Projection

### Append-only history

- Append accepted Operation revisions and decisions to history.
- Retain accepted manual-edit effects and acceptance decisions in history.
- Preserve rejected Operation revisions and their explanations.
- Record corrections, reversals, reclassifications and identifier changes through immutable Operation revisions.
- Preserve old Evidence.
- A reversal records a later business action.
- A correction changes the interpretation of an earlier business occurrence.
- Use separate semantics for reversals and corrections.

### Historical selection

| Parameter | Meaning |
|---|---|
| Business cutoff B | State through the selected occurrence time. |
| Knowledge cutoff K | Information durably recorded by this time, according to each layer's recording semantics. |
| History revision R | Immutable commit boundary for accepted Operation revisions and accepted inline manual edits. |
| Evidence inputs E | Pinned Evidence statement, mapping and decision versions through an Evidence revision or enumerated immutable IDs. |
| Semantic version S | Ontology, shapes, temporal/cost rules and query versions. |

- Knowledge availability does not imply acceptance of source Evidence statements.
- `recordedAt` identifies the server's durable acceptance time.
- Do not substitute a supplier date for `recordedAt`.
- Do not substitute an unapproved proposed Operation revision upload time for `recordedAt`.
- Retain capture and Evidence statement times separately.
- Preserve unknown or imprecise source dates.
- Do not fabricate precise occurrence times to make proposed Operation revisions executable.
- Record durable server recording/availability times for captures, normalized Evidence statements, identity mappings and review decisions.
- Filter E using each input's knowledge time `<= K`.
- Filter E using source scope and validity relevant to B.
- Exclude later captures and mappings from earlier reconciliation views.
- Use the policy and decisions active at K to reproduce actual earlier belief.
- Label newer-rule interpretations of older Evidence as retrospective interpretations.

### Current production reconstruction requirements

- Build from fixed R/E/B/K/S inputs.
- Apply agreed correction eligibility before deriving effective Operation and accepted manual-edit effects.
- [UNCLEAR] Q-06: Select full replacement or another correction profile before implementing corrective Operation effects.
- Preserve original revisions and their Evidence.
- Reject correction cycles and unresolved competing branches.
- Do not resurrect an original revision through a correction chain.
- Process effective Operation revisions and accepted manual edits together in explicit business order.
- Apply accepted manual-edit effects within the same business and knowledge cutoffs.
- Apply retractions before assertions within each Operation revision.
- An effective assertion remains active without a subsequent effective retraction of its exact subject/predicate/object triple.
- Validate each transition against its immediately preceding state.
- Derive Physical object existence and status within the same horizon.
- Consume available inputs through accepted Production Operations.
- Create new identified outputs for assembly, disassembly and reassembly.
- Derive consumed-input/new-output lineage from production Operation references.
- Derive each output's direct Location from effective Operations.
- Derive underlying quantity from actual output composition and supported input/output accounting.
- Exclude consumed ancestral identities from current inventory.
- Apply metadata revisions valid for the selected horizon.
- Derive identifier assignment intervals within the same horizon.
- Preserve repeated identifier assertions' separate Evidence records.
- Close open assignment records through retractions of their exact target/scope/type/value.
- Publish only complete validated Projections.
- [UNCLEAR] Q-06: Correction eligibility when replacement occurrence time crosses B.
- [UNCLEAR] Q-06: Correction-conflict resolution and dependent-Operation handling.

### Legacy proof reconstruction reference

- The proof established the following algorithm for its earlier event profile.
- This reference preserves the old proof's containment and inherited-location behavior.
- It does not establish the current packing-as-production model.
- Revalidate applicable temporal semantics against the current Operation model.

1. Select accepted proof events with `occurredAt <= B` and `recordedAt <= K`.
2. Supersede referenced proof events through eligible corrections.
   - A later correction can supersede an eligible correction without restoring its original event.
   - A correction chain does not resurrect its original event.
   - The proof's event profile requires full replacement effects.
3. Reject correction cycles.
   - Reject effective correction branches with a shared ancestor.
   - Arrival order does not resolve competing corrections.
   - The correction-resolution command and model require design.
4. Process effective proof events in explicit business order.
   - Apply retractions before assertions within each proof event.
   - An effective assertion remains active without a subsequent effective retraction of its exact subject/predicate/object triple.
   - The proof does not hide conflicting locations or parents by selecting one value.
5. Validate transition preconditions against the immediately preceding state.
   - The proof derives resource existence within the same horizon.
   - The proof derives containment history within the same horizon.
   - The proof derives transformation lineage within the same horizon.
   - Copy only metadata revisions valid for that view.
6. Derive identifier assignment intervals.
   - The proof derives inherited locations.
   - Preserve separate Evidence records for repeated same-value assertions.
   - The next retraction closes every open record for the exact target/scope/type/value.
   - A later assertion opens a new interval.
   - Overlapping Evidence records do not represent additional goods.
7. Validate the complete proof Projection.
   - Publish the proof Projection with its manifest.

### Correction cutoff acceptance case

| Input | Required policy decision |
|---|---|
| Original occurrence: January 13. Business cutoff B: January 14. | Original occurrence precedes B. |
| Correction recorded: January 16. Replacement occurrence: January 15. Knowledge cutoff K: January 16. | Correction knowledge falls within K. Replacement occurrence follows B. |
| Current-knowledge view at B. | [UNCLEAR] Q-06: Specify original-effect supersession and replacement-effect visibility before accepting this correction profile. |

### Ordering and rebuilds

- The proof orders timestamp ties by occurrence time and URI.
- Record source sequence or explicit causal order when production results depend on ordering.
- Commit revision orders recorded knowledge.
- Commit revision does not necessarily order physical actions.
- Retain chronology conflicts for review when Evidence cannot establish a consequential order.
- [UNCLEAR] Q-05: Ordering fields and date-only or uncertain-time handling.
- Build each Projection from a fresh working graph.
- Rules add conclusions.
- Remove stale conclusions when cutoffs, corrections or semantic releases change.
- Use rebuilding until a separate proof establishes equivalent incremental maintenance.
- Preserve the proof's ordered rule semantics.
- Do not replace it with an unbounded fixpoint over fresh blank nodes.

## 5. Validation and acceptance

### Validation targets

- [SHACL validates a specified data graph](https://www.w3.org/TR/shacl/).
- Validate imperfect Evidence statements separately from complete proposed Operation revisions.
- Validate accepted Operation structure separately from Projection state.
- A snapshot without individual IDs can constitute valid Evidence.
- That snapshot can remain insufficient for a proposed movement Operation revision.

### Proposed write pipeline

1. Authenticate the request.
   - Authorize the command.
   - Bind Workspace, actor and request key server-side.
2. Retain Evidence.
   - Verify referenced storage objects' durability and accessibility.
3. Normalize every line into typed claims with source references.
   - Check structure, Workspace boundaries, numeric precision, units and duplicate/source versions.
4. Resolve identity and chronology where Evidence supports resolution.
   - Retain alternatives and missing facts for review otherwise.
5. Compile the exact typed Operation revision and effects under a pinned semantic package.
6. Validate against a stable history revision.
   - Validate Operation shapes.
   - Validate the immediately preceding state.
   - Validate preconditions.
   - Validate the resulting state.
   - Validate every affected later transition for backdated Operations and corrections.
   - Final-state-only validation does not satisfy this contract.
7. Record human or policy acceptance.
   - Atomically commit accepted Operation revision, decision, receipt and history revision against the validated baseline.
   - Follow the [architecture acceptance contract](architecture.md#commit-and-projection-consistency).
   - Revalidate after a baseline change.
   - Preserve the reviewed Operation revision throughout retries.
   - Require renewed review when refreshed effects or selections change.
   - Resolve unconfirmed writes through their receipts.
   - Do not submit blind replacement writes.
8. Build the complete Projection.
   - Publish the complete Projection.
   - Link the result to its acceptance.

- [UNCLEAR] Q-07: Acceptance permissions and automatic acceptance policies.

### Hard invariants

- Reject cycles in consumed-input/new-output lineage.
- Require identified new outputs for assembly, disassembly and reassembly.
- Reject conflicting direct Locations.
- Require supported Physical object status.
- Require available Production inputs.
- Reject double consumption.
- Require valid quantities and units.
- Reject cross-Workspace references.

### Production effects

- Record assembly, disassembly and reassembly through `production` Operations.
- Record packing, unpacking and repacking through `production` Operations.
- Consume available input Physical objects for these actions.
- Create new identified output Physical objects.
- Record actual composition and all production stages' lineage.
- Do not reactivate historical input identities through unpacking or repacking.
- Transfers conserve units between Locations.
- Move or sell output Physical objects using their direct Locations.
- Record output quantity, unit, yield and waste effects explicitly.
- Keep consumed inputs unavailable as current stock.
- Record explicit production/yield/waste effects for quantity changes.
- Apply declared quantity-change policy.
- A discount does not prove a defect.
- Record repair and rework through `production` Operations.
- Retain the Physical object IRI when identity-preserving repair/rework policy permits it.
- Record dated specification, attribute and component effects for retained Physical objects.
- Do not model a retained Physical object as its own consumed predecessor.
- Link newly consumed repair/rework inputs to the Operation.
- SKU changes do not determine physical identity outcomes.
- The proof tests consumptive physical modification.
- The proof does not establish identity-preserving repair/rework semantics.
- [UNCLEAR] Q-04: Identity-preserving repair/rework policy.

### Inline manual inventory edits

- Apply the same validation, permission, review and acceptance safeguards to inline manual inventory edits.
- Record exact before/after effects.
- Record actor, business time, recording time, reason and Evidence.
- Preserve acceptance history and rejected edit explanations.
- Validate immediate preceding state and resulting state.
- Validate affected later transitions for backdated edits.
- Require the expected history revision.
- Require a command idempotency key bound to the exact edit.
- Bind edit keys to Workspace, command and canonical edit digest.
- Commit accepted edit effects and acceptance history atomically against the validated baseline.
- Advance the accepted-history revision with that commit.
- Return the recorded receipt for identical repeated edit commands.
- Reject idempotency-key reuse with different edit effects.
- Preserve the reviewed edit during retries.
- Require renewed review when refreshed effects change.
- Include accepted edits in reconstruction and report explanations.
- Do not classify manual edits as `purchase` or `write-off`.
- [UNCLEAR] Q-11: Physical-count versus record-correction intent and unresolved quantity handling.
- [UNCLEAR] Q-20: Durable edit encoding without adding a domain entity or Operation type.

### Historical transition validation

- Historical validation of every affected state remains a production requirement beyond the small proof.
- Do not let later restoration conceal earlier impossible transitions.
- Start with reconstruction and validation of the affected suffix.
- Prove parity before optimizing affected-history validation.

### Findings and review

| Finding fields | Required values |
|---|---|
| Identification | Stable code, severity, focus entity and Operation/line. |
| Contract | Violated shape or rule/version. |
| Evidence | Source references and expected/observed values. |
| Explanation | Useful explanation of the failure or uncertainty. |

- Distinguish incomplete data, identity ambiguity, chronology conflict and hard invariant failure.
- User review can supply Evidence.
- User review can select a mapping.
- User review can reject a proposed Operation revision.
- User review can approve an inline manual edit or a corrective revision.
- Do not bypass Workspace isolation through user review.
- Do not conceal invariant violations through user review.
- Require reasons for human overrides of source priority or inference.
- Preserve those override reasons in provenance.

## 6. Reports and reconciliation

### Report execution

- Run SPARQL report templates on one authorized, complete Projection.
- Pin Workspace, R/E/B/K/S and generation time in every response and export.
- Pin filters, dimensions and units in every response and export.
- Pin currency/valuation policy, coverage, pending work and unresolved Discrepancies in every response and export.
- Include stable domain and Evidence references in report rows.
- Explain contributing Operations in report rows.
- Explain contributing accepted manual-edit effects in report rows.

| Report | Calculation contract |
|---|---|
| Inventory at a date | Current quantities by Resource specification, variant, origin, direct Location, status and quality. Apply selected inventory scope. Separate saleable forms from underlying quantities. |
| Movement and sales | Accepted Operation lines within the period and actual composition at occurrence time. Separate gross sales, returns and net sales. |
| Production/origin lineage | Arrival/production Operation-line origins, consumed inputs, new outputs and actual composition. Preserve unknown origin status. |
| Identifier/Listing history | Assignment Evidence statements, known intervals, Listing revisions, matching decisions and conflicting claims. |
| Reconciliation | Accepted balance against each comparable external Evidence statement. Difference, source windows/coverage, supporting Evidence and conflicting Evidence. |
| Cost, turnover, defects and supplier settlements | Existing outcomes, explicit formulas, cost-basis certainty and independent invoice/payment Evidence. |

### Counting and composition

- Count current inventory Physical objects within the selected status and availability scope.
- Use the declared discrete tracking basis for individual-object counts.
- Report saleable output forms as a separate measure.
- Derive underlying quantities from supported input/output accounting and actual output composition.
- [UNCLEAR] Q-02: Bulk and weighed quantity counting.
- Exclude consumed ancestral Physical objects from current inventory counts.
- Prevent repeated lineage paths from duplicating quantities.
- “100 pairs” and “100 socks” describe different units.
- Require explicit conversion when changing units.
- Recipes do not prove actual production composition.
- Use actual output composition at sale time for a four-unit packaged sale.
- Later Listing edits do not change that historical composition.
- Later reassembly of returned goods does not change that historical composition.
- Prevent quantity multiplication through identifier, origin or Evidence joins.
- Aggregate movements by stable Operation-line ID before adding descriptive joins.
- A returned and resold unit can contribute twice to gross sales.
- Distinguish flow quantities from distinct Physical objects ever sold.

### Financial meaning

- Keep supplier invoice price, customer sale price, marketplace settlement and inventory cost separate.
- Preserve missing cost as unknown.
- Propagate unknown cost into valuation-dependent metrics.
- Preserve v1 financial outcomes.
- Label v1's current turnover basis if reused.
- [UNCLEAR] Q-12: Transformed-output allocation, rounding, currency conversion and v2 valuation formulas.

### Discrepancy example

| Evidence statement | Value |
|---|---|
| Received quantity | 100 pairs. |
| Sold quantity | 90 pairs. |
| External remaining quantity | Zero pairs. |
| Unexplained difference | Ten pairs, only within a compatible closed scope. |

- Do not invent a write-off from this difference.
- Do not conclude that Ozon is faulty from this difference alone.
- Show the reconciliation equation.
- Show known transfers, returns/losses, disputed Evidence statements and missing coverage.
- Attribute a cause only when additional Evidence supports it.
- Allow a user to add a documented correction.

## 7. Marketplace and file synchronization

- Use one capture-to-Operation contract for API responses, XLSX/CSV exports, images/text documents and future connectors.
- Connector mappings translate source meaning into the schema.
- RDF interoperability does not remove source-specific semantics, units, scope or version checks.

### Inbound pipeline

1. Create a sync run with Account, requested window, endpoint/report versions, page limits and checkpoints.
   - Capture each response or file as independent Evidence.
2. Retain permitted raw data, request context, status and completeness.
   - Record successful pages and failures separately.
   - An API read can use HTTP POST.
   - Classify safety through the operation contract rather than the HTTP verb.
3. Append normalized Evidence statement versions.
   - A source entity ID identifies the source entity.
   - A payload/version digest identifies the Evidence statement.
   - Preserve changed source rows as new versions.
   - Do not discard changed source rows as duplicates.
   - Do not overwrite previous source meanings.
   - Track the stable business-occurrence key and its Operation lines separately.
   - Prevent repeated captures from creating repeated movements.
   - Propose a correction or revised claim for changed Evidence statements of already accepted occurrences.
   - A changed payload digest does not establish another sale or transfer.
4. Resolve identifiers using typed issuing-system and supplier/Account scope at the relevant time.
   - Combine supplier barcode, SKU, article and other claims only when compatible.
   - Preserve unknown IDs and conflicting mappings explicitly.
   - Do not conceal contradictory SKUs through matching articles.
   - Several barcodes can legitimately refer to one Resource specification.
5. Compare equivalent quantities.
   - Propose only Evidence-supported Operation revisions.
   - A snapshot supplies reconciliation Evidence.
   - A snapshot does not establish a delta or sale.
   - Aggregate sold quantities do not identify particular Physical objects without binding or a disclosed allocation decision.
6. Validate the proposed Operation revision.
   - Apply authorized review or automatic acceptance policy.
   - Commit once.
   - Publish the complete Projection.
   - Update progress.
   - Reconcile interrupted jobs through domain receipts.
   - Advance coverage checkpoints only for verified complete scopes.

- [UNCLEAR] Q-03: Physical binding, allocation and return identity.
- [UNCLEAR] Q-08: Source authority and comparable coverage.
- [UNCLEAR] Q-09: Business-occurrence keys and cross-source equivalence.
- [UNCLEAR] Q-21: First supported connectors and verified endpoint contracts.

### Initial Ozon safeguards

- Preserve the [current evidence policy](legacy-contracts/runbooks/ozon-operations-legacy.md#operation-support-matrix).

| Source Evidence | Permitted support |
|---|---|
| Final delivered postings | Sales. |
| Seller-receipt Evidence | Returns. |
| Explicit disposal Evidence | Write-offs. |
| Completed supplies | Transfers after source mapping. |

- Marketplace finance does not establish supplier payment.
- Stock or discount Evidence statements do not establish purchase, production or defect Operations.
- Do not apply source balance Evidence as automatic manual inventory edits.
- Revalidate endpoint contracts before implementation.
- V1 policy references do not prove that future API versions retain the same fields.

### API/export comparison

- Treat API responses and UI exports as separate sources.
- Record each report's requested period, export time, Account, warehouse, row references, measures and known coverage.
- Compare only aligned scopes.
- Freeze API and export captures for controlled comparison tests.
- Retain the user-confirmed physical case for those tests.
- Identify reproducible differences from those fixed inputs.
- A newer capture does not establish source correctness.
- A larger dataset does not establish source correctness.

### Outbound boundary

- Start with read-only inbound synchronization.
- Require separate authorized outbound intents for marketplace cards, labels and shipments.
- Retain outbound payload/mapping versions, requests, responses and resulting external IDs as Evidence.
- A timeout can follow a successful external write.
- Verify external status before retrying uncertain writes without proven external idempotency.
- Use bounded retries for safe reads and resumable jobs.
- Keep retry exhaustion visible for human action.

## 8. Rule customization and intelligence

### Controlled customization

- Preserve mandatory core identity, provenance and physical invariants.
- Support eventual Workspace customization within a controlled profile.

| Customizable area | Scope |
|---|---|
| Matching | Preferences and tolerances. |
| Decisions | Review and automation policies. |
| Interpretation | Classifications and reporting derivations. |
| Released rules | Owner/permission, examples, validation, impact preview and explicit activation. |

- Publish immutable versioned rules.
- [UNCLEAR] Q-07: Numeric thresholds and permitted automatic acceptance operations.
- [UNCLEAR] Q-15: Rule ownership, permissions, activation and customization governance.
- Do not embed arbitrary product defaults in ontology axioms.
- Test rule changes against representative history.
- Compare resulting matches, proposed Operation revisions, balances and reports.
- Activation does not rewrite accepted Operations retroactively.
- Create a new versioned Projection for a new interpretation.
- Require a recorded decision for a changed domain claim.
- Select a previous release for rollback.
- Rebuild its Projection.

### Rule and AI limits

- Start with controlled templates and parameters.
- Do not accept arbitrary executable SPARQL from Workspaces.
- Do not permit rules to write accepted history.
- Do not permit rules to cross Workspace boundaries.
- Do not permit unrestricted rule network calls.
- Do not permit rules to disable core validation.
- Apply the same Evidence and acceptance pipeline to AI-generated Operation revisions and explanations.
- Use the intelligence layer to discover gaps.
- Use the intelligence layer to suggest mappings.
- Use the intelligence layer to explain Discrepancies.
- AI confidence does not prove physical identity.

## 9. Proof and delivery gates

### Established proof

- The [offline experiment](../experiments/inventory-rdf/README.md) passed 196 checks.
- These checks include 92 complete-graph comparisons between RDF rules and a Python reference.

| Legacy constructed lifecycle coverage | Tested behavior |
|---|---|
| Composition | Packing, unpacking and repacking preserved component identities through containment. |
| Locations | Movement and inherited locations through containment. |
| Commercial movements | Sale and return. |
| Lineage | Consumptive transformation lineage. |
| History | Historical cutoffs and late corrections. |
| Identifiers | Assignment intervals. |
| Rejection | Invalid consumption and correction conflicts. |

- The 743 real records supply aggregate Evidence.
- They do not supply complete individual-object histories.
- The legacy proof does not validate packing, unpacking or repacking as consumed-input/new-output Production Operations.
- The legacy proof does not establish direct-Location or quantity reporting for that revised model.
- Preserve experiment code and its historical results.

### First implementation gates

- [UNCLEAR] Q-01: First supported receipt-to-report lifecycle and acceptance cases.
- [UNCLEAR] Q-02: First supported tracking basis.
- [UNCLEAR] Q-03: Operational physical binding and disclosed allocation.
- [UNCLEAR] Q-04: Identity-preserving repair/rework policy.
- [UNCLEAR] Q-05: Time precision and consequential Operation ordering.
- [UNCLEAR] Q-06: Selected correction profile, cutoff-crossing behavior and dependent-Operation handling.
- [UNCLEAR] Q-07: First acceptance permissions and review policy.
- [UNCLEAR] Q-20: Vocabulary, Operation encoding and bounded semantic subset.
- Demonstrate atomic acceptance against the chosen RDF store before enabling accepted Operation writes.
- Validate packing, unpacking and repacking against new-output identity acceptance cases.
- Validate direct Locations and underlying quantities without ancestral stock duplication.
- Extend or replace the legacy proof before claiming parity with the current production model.
- Use the smallest receipt-to-report path that proves the selected contract.

### Workflow-specific gates

| Workflow | Required decision or proof |
|---|---|
| Connector acceptance | Source authority, coverage, occurrence identity and verified endpoint semantics. [UNCLEAR] Q-08, Q-09, Q-21. |
| Valuation and finance reports | Defined formulas, allocation, rounding, currency and unknown-cost behavior. [UNCLEAR] Q-12. |
| Evidence administration | Integrity, retention, permitted-content and redaction policy. [UNCLEAR] Q-14. |
| Rule customization | Controlled authorship, activation and immutable integrity boundaries. [UNCLEAR] Q-15. |

### Domain cutover gates

- Demonstrate a user-observed lifecycle with traceable Physical object binding for the supported tracking mode.
- Retain unresolved gaps in that lifecycle.
- Align API/XLSX captures for supported connector workflows.
- Demonstrate concurrent acceptance against the selected RDF store.
- Demonstrate durable receipts.
- Demonstrate recovery from interrupted jobs.
- Prevent stale builders from replacing newer Projections.
- Verify historical metadata revisions.
- Verify explicit business ordering.
- Verify affected-history validation.
- Demonstrate an intentional correction-conflict resolution workflow.
- Verify Workspace-scoped inference, queries and exports.
- Verify authorized Evidence retrieval.
- Demonstrate joint history/file recovery.
- Verify source integrity and retention policy.
- Verify reproducible report definitions.
- Verify unknown-cost behavior.
- Agree valuation rules for supported financial reports.
- Agree units, precision and rounding for supported workflows.
- [UNCLEAR] Q-19: RDF store vendor, topology and operating envelope.
- These gates refine the first implementation's contract.
- Do not build a second platform before the first receipt-to-report path works.

## Clarifying questions

- Resolve referenced questions in [Product requirements: clarifying questions](product-requirements.md#clarifying-questions).
