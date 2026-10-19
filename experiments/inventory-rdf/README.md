# Inventory RDF proof

An isolated, offline domain experiment using one Lobanova product and a separate
known-answer physical lifecycle. It changes no application, database or marketplace
records. Client evidence and generated results stay in ignored `private/` files.

This is historical evidence. Its packing/containment model is superseded by the
[current specifications](../../docs/README.md), which consume inputs and create
new output identities during production. It is not the application inventory model.

## Run

From the repository root (no application Node dependencies are needed):

```sh
python3.12 -m venv experiments/inventory-rdf/.venv
experiments/inventory-rdf/.venv/bin/python -m pip install -r experiments/inventory-rdf/requirements.txt
```

The frozen capture is already present locally. Run the rules projection and checks:

```sh
experiments/inventory-rdf/.venv/bin/python experiments/inventory-rdf/experiment.py
```

Outputs: `private/dataset.trig`, `private/results.json` (queries and evidence-linked
rows), and `private/report.md` (assessment). The runner makes no network requests.
It requires Node 24.3+ for offline verification using the capture producer's exact
ECMAScript canonical JSON format; this avoids cross-language float checksum drift.

Live capture was retired with the legacy application. The original capture script
is preserved in the local milestone-1 source archive; the [Ozon contract](../../docs/legacy-contracts/ozon-fetching.md)
records the useful fetching behavior for a future Clojure port. The remaining
`capture.mjs` performs offline verification/self-checks using only Node built-ins.

The frozen capture covers one bounded account/product scope. It has separately
timed table reads and one-page endpoint samples, not an atomic database snapshot
or exhaustive API history. Errors are evidence rather than zero-stock observations.

```sh
node experiments/inventory-rdf/capture.mjs --self-check
node experiments/inventory-rdf/capture.mjs --verify experiments/inventory-rdf/private/capture.json
```

## Approximate domain model

| Concept | Meaning |
|---|---|
| `vf:ResourceSpecification` | Definition of goods or pack composition |
| `vf:EconomicResource` | Individually identified physical object or pack |
| `ex:Batch` | Specific receipt or production origin |
| `ex:Listing` | Marketplace representation, separate from physical identity |
| `ex:ExternalIdentifier` | Product ID, SKU, article or barcode, scoped to an account |
| `ex:IdentifierAssignment` | Evidence of assignment; known validity intervals only in the constructed lifecycle |
| `ex:InventoryEvent` | Occurrence/recording times, evidence, explicit assertions/retractions |
| `prov:Entity` | Source record, API response or constructed evidence |

`model.ttl` is a small application profile. It reuses [Valueflows](https://www.valueflo.ws/specification/spec-overview/)
resource/event terms and [PROV-O](https://www.w3.org/TR/prov-o/) provenance. It does
not import entire ontologies or assume `owl:sameAs` for external identifiers.

Approximate [EPCIS 2.0.1](https://ref.gs1.org/standards/epcis/2.0.1/) mappings:

| Behaviour | Mapping direction |
|---|---|
| Pack / unpack | AggregationEvent ADD / DELETE; component identities survive |
| Move / observe stock | ObjectEvent with location and business context |
| Consume inputs / create outputs | TransformationEvent input/output lineage |
| Correct an event | Error declaration referencing corrective events |

These are mapping notes, not a compliant EPCIS export or capture interface.

## Temporal and trust policy

Named graphs keep observed evidence, constructed events and derived projections
separate. Real mirror identifiers are interpreted by endpoint/table semantics,
not by misleading column names. A conflicting or unknown typed reference is not
automatically promoted to a match. Derived assignment links point to source evidence.
Real stock counts remain aggregate; no physical objects are invented for them.

For a state view, choose both a business-time cutoff and a recording-time cutoff.
Eligible corrections replace original effects; original evidence remains visible.
Effects apply retractions before assertions in event-time order. Resource existence,
containment history and transformation lineage are gated to the same horizon.
Only top-level resources have direct locations; contained objects inherit location.
Specifications and location catalogs are treated as timeless definitions here.

`projection-rules.ttl` expresses this policy as ordered [SHACL-AF SPARQL rules](https://www.w3.org/TR/shacl-af/#rules),
executed by the pinned pySHACL engine. Its strata select eligible/effective events,
derive surviving exact triples, gate metadata and derive lineage, identifier
intervals and inherited locations. `rules_projection.py` supplies the two cutoffs,
extracts conclusions and invokes validation; it contains no event replay.
`experiment.py` retains the Python replay as a reference oracle. `rule_cases.py`
compares entire projected graphs, plus independent boundary and invalid-history cases.
The published derived graph comes from the rules. This is a declarative RDF rules
proof; no native Datalog runtime is used.

Every projection starts with a fresh working graph because rules add conclusions.
Changing a cutoff or adding a correction must not preserve stale deductions.
Each ordered rule runs once; iterative execution would keep creating internal
blank-node wrappers and is deliberately disabled.
Corrections are full replacements; correcting a correction does not resurrect
the original. Competing effective correction branches and cycles fail validation;
a workflow for resolving a branch conflict needs separate design. Equal
occurrence times use URI order as a proof convention; actual capture needs explicit
ordering evidence when the order matters.

The scenario receives two batches of six objects, builds homogeneous and mixed
four-object packs, ships, sells/returns the mixed pack, unpacks/reassembles mixed
pairs, moves them and changes a listing identifier. One separate object is consumed
to create a modified output. A late correction revises a warehouse movement.
Reporting counts either top-level saleable forms or underlying objects; it never
adds containers to their components. SHACL validates projected states, while replay
and rules independently reject inputs unavailable immediately before a transformation.
A later restoration cannot hide repeated consumption.

## Decision and limits

The proof is promising if lineage, reporting and temporal queries pass with source
references and invalid states fail. It is not evidence of physical truth or full
Ozon completeness. Existing mirror overwrites, merged source responses and broad
historical redaction remain gaps that new requests cannot repair retrospectively.
Fresh evidence is sanitized contextually to keep operational counts and dates.

Production storage durability, transactions, concurrency, incremental maintenance,
performance, validation of every intermediate state, accounting, automatic identity
resolution and user-authored rules need separate experiments. Rules do not repair
missing evidence. Repeated identifier assertions retain separate evidence records;
a retraction closes all open records for that listing and value. Overlapping
assignment records describe one identifier episode and must not be counted as goods.
