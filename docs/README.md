# Tover specifications

The Clojure application lives at the repository root. The agreed first tester version uses a durable inventory ledger, shared server drafts and real identity. The [application README](../README.md) describes running and checking it; passing release checks establish readiness.

- [Product requirements](product-requirements.md): item identity, eight operation types, corrections and shared acceptance.
- [Client UI](client-ui-spec.md): Stock, Operations, server editing and lazy item history.
- [Architecture](architecture.md): ownership, authorization, transport and runtime boundaries.
- [RDF storage](rdf-machinery.md): records, replay, transactions and quantity lineage.
- [Identity deployments](identity-deployment.md): local and hosted identity compatibility.
- [Production deployment](production-deployment.md): HTTPS, SMTP, installation, upgrades and backup/restore.

The 2026-10-21 contracts supersede individual physical-unit identity and browser-simulated submissions. [Legacy contracts](legacy-contracts/README.md) retain selected historical integration behavior. The [offline RDF experiment](../experiments/inventory-rdf/README.md) uses a superseded containment model and is not the current ledger contract.
