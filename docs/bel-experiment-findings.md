# Bel experiment findings

Historical findings, 2026-11-10. Experiment implementations, runners, fixtures and prototype Bel APIs were removed. These results are recorded evidence, not a currently runnable suite or a production migration decision. Private captures and database files were preserved.

| Evaluation | Finding |
| --- | --- |
| Original RDF4J catalogue slice | Commands, schemas, generic RDF event encoding, receipts, scoped read models and Bel rendering worked together. Transaction, retry and isolation checks established the basic path. |
| Jena 6.2.0 / TDB2 | Explicit event-to-domain updates passed 20 tests / 150 assertions in the REPL and a fresh instrumented run. Separate writer/reader JVMs preserved events, current data and receipts; retry skipped business work. |
| QLever 0.6.0, build `6c5861a` | A failed multi-operation HTTP update could become visible after a later successful write and survive restart. Three assertions failed across initial, restart and SIGKILL checks. This tested version did not satisfy the required general rollback contract. |
| Earlier inventory RDF proof | Explored provenance, corrections and temporal queries. Its individual-object containment model was superseded by the current inventory contract. |

Decisions retained:

- Keep immutable events, current domain data and receipts separate, with one atomic commit.
- Use explicit application-owned event mappings. Read models query persisted domain state; normal reads/restarts do not replay history.
- Apply query bindings natively and enforce workspace scope. Ignored bindings returned unfiltered results.
- Structural SHACL validation misses absent entities and zero-match updates. Check affected entities against their final accepted snapshots before commit.
- Retry receipts must bypass decisions, mappings and candidate validation after authorization.
- Keep domain decisions and validation in the application; keep database mechanics in adapters.
- Grain's event reducers and cached read state informed the separation of concerns. Bel's chosen read model is a database query function.

The experiments did not prove production throughput, power-loss recovery, live migration, optional delivery or a simpler authoring experience. The [Goods rename milestone](bel-next-milestone.md) applies these findings to an isolated application feature using the existing database.
