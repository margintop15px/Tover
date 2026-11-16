# Tover specifications

The Clojure application lives at the repository root. The agreed first tester version uses a durable inventory ledger, shared server drafts and real identity. The [application README](../README.md) describes running and checking it; passing release checks establish readiness.

- [Board process](board-process.md): how the prooph board drives work: where rules live, slice lifecycle, change impact and agent read rules. Start here.
- [Product requirements](product-requirements.md): item identity, eight operation types, corrections and shared acceptance.
- [Client UI](client-ui-spec.md): Stock, Operations, server editing and lazy item history.
- [Architecture](architecture.md): ownership, authorization, transport and runtime boundaries.
- [Bel architecture and DSL](bel-dsl-proposal.md): core declarations, scopes, adapter boundaries and proposed continuations. Goods rename and Create Workspace stay isolated from live routes.
- [Bel milestones](bel-next-milestone.md): shared PostgreSQL journal, Goods delivery, Create Workspace and cutover boundaries.
- [Onboarding DSL mapping](bel-onboarding-mapping.md): chapters 01–07, agreed business rules and the next adoption steps.
- [Experiment findings](bel-experiment-findings.md): short historical conclusions from the RDF4J, Jena, QLever and earlier inventory evaluations.
- [RDF storage](rdf-machinery.md): records, replay, transactions and quantity lineage.
- [Identity deployments](identity-deployment.md): local and hosted identity compatibility.
- [Production deployment](production-deployment.md): HTTPS, SMTP, installation, upgrades and backup/restore.
- [Marketplace integration execution plan](marketplace-integration-plan.md): approved goods identity/Ozon roadmap, decisions, source research and session checkpoints. M0/M1 are complete; M2 is next.
- [Ozon source contracts](ozon-source-contracts.md): M1 endpoint/evidence matrix, synthetic cases and explicit gaps before live conversion.

The 2026-10-21 contracts supersede individual physical-unit identity and browser-simulated submissions. [Legacy contracts](legacy-contracts/README.md) retain selected historical integration behavior. Experiment code has been retired; its findings do not replace the current ledger contract.
