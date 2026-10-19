"""Temporal projection delegated to ordered SHACL-AF SPARQL rules.

Python transports RDF and extracts rule conclusions; it does not replay events.
The private rule vocabulary keeps source/history triples apart from projected
triples, including identical subject/predicate/object terms in both views.
"""
from pathlib import Path

from pyshacl import shacl_rules, validate
from rdflib import Graph, Literal, Namespace, RDF, XSD

EX = Namespace("urn:tover:inventory-proof:")
RULE = Namespace("urn:tover:inventory-proof:rules:")
RULES = Path(__file__).with_name("projection-rules.ttl")


def rules_project(scenario, valid_at, recorded_at):
    source = Graph()
    for triple in scenario:
        source.add(triple)
    source.add((EX.projection, RULE.businessTime, Literal(str(valid_at), datatype=XSD.dateTime)))
    source.add((EX.projection, RULE.knowledgeTime, Literal(str(recorded_at), datatype=XSD.dateTime)))
    inferred = shacl_rules(source, shacl_graph=str(RULES), inference="none",
                           inplace=False, iterate_rules=False)
    violations = list(inferred.subjects(RDF.type, RULE.ConsumptionViolation))
    if violations:
        raise ValueError("Transformation input is unavailable or already consumed")
    if list(inferred.subjects(RDF.type, RULE.IncompleteEffect)):
        raise ValueError("Incomplete event effect")
    result = Graph()
    for name, namespace in scenario.namespaces():
        result.bind(name, namespace)
    for fact in inferred.subjects(RDF.type, RULE.ProjectedFact):
        result.add(tuple(inferred.value(fact, predicate)
                         for predicate in (RDF.subject, RDF.predicate, RDF.object)))
    conforms, _, report = validate(result, shacl_graph=str(RULES.with_name("shapes.ttl")),
                                  inference="none")
    if not conforms:
        raise ValueError("Invalid rule projection: " + report)
    return result
