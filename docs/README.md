# Tover specifications

The Clojure application lives at the repository root. It is still a local UI prototype; see the [application README](../README.md) for implemented behavior and limitations.

These draft documents define the intended product:

- [Product requirements](product-requirements.md): vocabulary, business rules and unresolved policies.
- [Client UI specification](client-ui-spec.md): user workflows and observable outcomes.
- [Architecture](architecture.md): ownership and system boundaries.
- [RDF machinery](rdf-machinery.md): accepted history, semantics and projections.

[Legacy contracts](legacy-contracts/README.md) preserve selected identity, Ozon and import behavior for later ports. They describe the removed application. The [offline RDF experiment](../experiments/inventory-rdf/README.md) uses the superseded containment model and does not validate the new production lifecycle.
