"""Offline, bounded RDF inventory proof. Never connects to a live service."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
from functools import lru_cache
from pathlib import Path
from urllib.parse import quote

from pyshacl import validate
from rdflib import Dataset, Graph, Literal, Namespace, RDF, URIRef, XSD

ROOT = Path(__file__).resolve().parent
EX = Namespace("urn:tover:inventory-proof:")
VF = Namespace("https://w3id.org/valueflows/ont/vf#")
PROV = Namespace("http://www.w3.org/ns/prov#")
TARGET = {"productId": "5792316122", "sku": "5326517520", "offerId": "L_Компрессор_4"}
SCOPE = {"projectId": "yjecngpktdufhmvxsqhh", "workspaceId": "581d2ff7-258a-4dfe-8714-992d44698c80", "connectionId": "a84ebd83-b93d-494f-b63e-513d958edd61"}
PREFIX = f"PREFIX ex: <{EX}> PREFIX vf: <{VF}> PREFIX prov: <{PROV}> "
STATE_PREDICATES = {EX.location, EX.contains, EX.status, EX.activeIdentifier}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def instant(value):
    return datetime.fromisoformat(str(value).replace("Z", "+00:00"))


def uri(kind, value):
    return URIRef(str(EX) + kind + ":" + quote(str(value), safe=""))


def bind(graph):
    for name, ns in [("ex", EX), ("vf", VF), ("prov", PROV)]:
        graph.bind(name, ns)
    return graph


def load_capture(path):
    # Use the producer's ECMAScript canonicalization, including numeric formatting.
    subprocess.run(["node", str(ROOT / "capture.mjs"), "--verify", str(path)], check=True, capture_output=True, text=True)
    data = json.loads(path.read_text())
    if data.get("version") != 1 or data["target"] != SCOPE | TARGET:
        raise ValueError("Unexpected capture target/version")
    count = sum(len(part["rows"]) for part in data["stored"])
    if not 0 < count <= 1000 or count != data["manifest"]["recordCount"]:
        raise ValueError("Invalid bounded snapshot count")
    for part in data["stored"]:
        for row in part["rows"]:
            if row["connection_id"] != data["target"]["connectionId"] or row["workspace_id"] != data["target"]["workspaceId"]:
                raise ValueError("Snapshot contains out-of-scope evidence")
    return data


def raw_ids(table, row):
    """Column names aren't identifier types: some mirrors put SKU in product_id."""
    raw = row.get("raw_payload") or {}
    ids = {"offerId": row.get("offer_id"), "sku": row.get("sku")}
    if table == "ozon_products":
        ids["productId"] = row.get("ozon_product_id")
    elif table == "ozon_stock_snapshots":
        ids["productId"] = row.get("ozon_product_id")
        ids["sku"] = (raw.get("stock") or {}).get("sku") or ids["sku"]
    else:
        ids["productId"] = raw.get("product_id") or (raw.get("product") or {}).get("product_id")
    barcode = raw.get("barcode")
    if barcode:
        ids["barcode"] = barcode
    return {kind: str(value) for kind, value in ids.items() if value is not None and str(value)}


def match(ids, candidates):
    hits = set()
    contradictory = False
    for kind, value in ids.items():
        matches = candidates.get((kind, value), set())
        hits.update(matches)
        # A conflicting known identifier must not be overruled by article equality.
        if kind in {"productId", "sku", "offerId"} and not matches:
            contradictory = True
    if len(hits) == 1 and not contradictory:
        return EX.Matched, hits
    return (EX.Ambiguous if hits else EX.Unresolved), hits


def observations(dataset, capture):
    graph = bind(dataset.graph(EX.observed))
    decisions = bind(dataset.graph(EX.identityDecisions))
    listing = EX["listing-ozon"]
    graph.add((listing, RDF.type, EX.Listing))
    graph.add((listing, EX.origin, EX.Observed))
    graph.add((EX.ozon, RDF.type, PROV.Organization))
    scope = URIRef("urn:tover:connection:" + capture["target"]["connectionId"])
    candidates = {(kind, value): {listing} for kind, value in TARGET.items()}
    quantities = {
        "ozon_stock_snapshots": ["present", "reserved"],
        "ozon_stock_analytics": ["available_stock_count", "valid_stock_count", "return_from_customer_stock_count", "transit_stock_count"],
        "ozon_posting_items": ["quantity"], "ozon_supply_order_items": ["quantity"], "ozon_returns": ["quantity"],
    }
    for part in capture["stored"]:
        table = part["table"]
        for row in part["rows"]:
            evidence = uri("record", table + ":" + row["id"])
            for triple in [(evidence, RDF.type, PROV.Entity), (evidence, EX.origin, EX.Observed),
                           (evidence, EX.sourceTable, Literal(table)), (evidence, EX.sourceId, Literal(row["id"])),
                           (evidence, EX.sha256, Literal(digest(row))), (evidence, PROV.wasAttributedTo, EX.ozon)]:
                graph.add(triple)
            captured = part["capturedAt"]
            graph.add((evidence, PROV.generatedAtTime, Literal(captured, datatype=XSD.dateTime)))
            ids = raw_ids(table, row)
            status, hits = match(ids, candidates)
            observation = uri("observation", table + ":" + row["id"])
            graph.add((observation, RDF.type, EX.Observation))
            graph.add((observation, EX.origin, EX.Observed))
            graph.add((observation, EX.evidence, evidence))
            decision = uri("decision", table + ":" + row["id"])
            for triple in [(decision, RDF.type, EX.IdentityDecision), (decision, EX.origin, EX.Derived),
                           (decision, EX.observation, observation), (decision, EX.matchStatus, status),
                           (decision, PROV.wasDerivedFrom, evidence)]:
                decisions.add(triple)
            for field in ["snapshot_at", "synced_at", "created_at", "in_process_at", "completed_at_ozon", "returned_at"]:
                if row.get(field):
                    graph.add((observation, EX[field], Literal(row[field], datatype=XSD.dateTime)))
            for field, parent in [("posting_id", "ozon_postings"), ("supply_order_id", "ozon_supply_orders")]:
                if row.get(field):
                    graph.add((observation, EX.partOf, uri("observation", parent + ":" + row[field])))
            if status == EX.Matched:
                decisions.add((decision, EX.about, listing))
            else:
                for hit in hits:
                    decisions.add((decision, EX.candidate, hit))
            for kind, value in ids.items():
                identifier = uri("identifier", str(scope) + ":" + kind + ":" + value)
                graph.add((identifier, RDF.type, EX.ExternalIdentifier))
                graph.add((identifier, EX.identifierType, EX[kind]))
                graph.add((identifier, EX.value, Literal(value)))
                graph.add((identifier, EX.scope, scope))
                graph.add((observation, EX.identifier, identifier))
                # These are observations of assignments, not invented validity periods.
                if status == EX.Matched:
                    assignment = uri("assignment", table + ":" + row["id"] + ":" + kind)
                    decisions.add((assignment, RDF.type, EX.IdentifierAssignment))
                    decisions.add((assignment, EX.listing, listing))
                    decisions.add((assignment, EX.identifier, identifier))
                    observed_at = row.get("snapshot_at") or row.get("synced_at") or captured
                    decisions.add((assignment, EX.observedAt, Literal(observed_at, datatype=XSD.dateTime)))
                    decisions.add((assignment, PROV.wasDerivedFrom, evidence))
                    decisions.add((assignment, EX.origin, EX.Derived))
            for field in quantities.get(table, []):
                if row.get(field) is not None:
                    quantity = Decimal(str(row[field]))
                    if not quantity.is_finite():
                        raise ValueError("Non-finite source quantity")
                    lexical = format(quantity, "f")
                    if "." not in lexical:
                        lexical += ".0"
                    graph.add((observation, EX[field], Literal(lexical, datatype=XSD.decimal)))
            for field in ["status", "supply_state", "fulfillment_schema", "snapshot_date"]:
                if row.get(field) is not None:
                    graph.add((observation, EX[field], Literal(row[field])))
            warehouse = row.get("ozon_warehouse_id") or row.get("ozon_storage_warehouse_id")
            if warehouse:
                graph.add((observation, EX.warehouse, uri("ozon-warehouse", warehouse)))
    for index, response in enumerate(capture["fresh"]):
        evidence = uri("response", index)
        graph.add((evidence, RDF.type, PROV.Entity))
        graph.add((evidence, EX.origin, EX.Observed))
        graph.add((evidence, EX.endpoint, Literal(response["endpoint"])))
        if response["status"] is not None:
            graph.add((evidence, EX.httpStatus, Literal(response["status"])))
        if response.get("error"):
            graph.add((evidence, EX.captureError, Literal(canonical(response["error"]))))
        graph.add((evidence, PROV.generatedAtTime, Literal(response["capturedAt"], datatype=XSD.dateTime)))
        graph.add((evidence, EX.request, Literal(canonical(response["request"]))))
        graph.add((evidence, PROV.wasAttributedTo, EX.ozon))
        if response.get("response") is not None:
            graph.add((evidence, EX.sha256, Literal(response["sha256"])))
            graph.add((evidence, EX.payload, Literal(canonical(response["response"]))))
    return graph


def project(scenario, valid_at, recorded_at):
    valid_at, recorded_at = instant(valid_at), instant(recorded_at)
    events = {event for event in scenario.subjects(RDF.type, EX.InventoryEvent)
              if instant(scenario.value(event, EX.occurredAt)) <= valid_at
              and instant(scenario.value(event, EX.recordedAt)) <= recorded_at}
    superseded = {old for event in events for old in scenario.objects(event, EX.corrects)}
    graph = bind(Graph())
    ordered = sorted(events - superseded, key=lambda e: (instant(scenario.value(e, EX.occurredAt)), str(e)))
    for event in ordered:
        if scenario.value(event, EX.kind) == EX.Transform:
            for source in scenario.objects(event, EX.input):
                if (source, EX.status, EX.OnHand) not in graph:
                    raise ValueError("Transformation input is unavailable or already consumed")
        for effect, adding in [(EX.retracts, False), (EX.asserts, True)]:
            for statement in scenario.objects(event, effect):
                triple = tuple(scenario.value(statement, predicate) for predicate in [RDF.subject, RDF.predicate, RDF.object])
                if any(term is None for term in triple):
                    raise ValueError("Incomplete event effect")
                if adding:
                    graph.add(triple)
                else:
                    graph.remove(triple)
                if adding and triple[1] == EX.contains:
                    graph.add((triple[0], EX.everContained, triple[2]))
        if scenario.value(event, EX.kind) == EX.Transform:
            for output in scenario.objects(event, EX.output):
                for source in scenario.objects(event, EX.input):
                    graph.add((output, PROV.wasDerivedFrom, source))
    # Catalog definitions are timeless here. Resource existence, lineage and
    # event/effect evidence are restricted to the selected temporal horizon.
    resource_catalog = set(scenario.subjects(RDF.type, VF.EconomicResource))
    known_resources = set(graph.subjects(EX.status, None))
    all_events = set(scenario.subjects(RDF.type, EX.InventoryEvent))
    all_statements = set(scenario.subjects(RDF.type, RDF.Statement))
    visible_statements = {statement for event in events for predicate in [EX.asserts, EX.retracts]
                          for statement in scenario.objects(event, predicate)}
    known_batches = {batch for resource in known_resources for batch in scenario.objects(resource, EX.batch)}
    all_batches = set(scenario.subjects(RDF.type, EX.Batch))
    known_listings = {listing for resource in known_resources for listing in scenario.objects(resource, EX.listing)}
    all_listings = set(scenario.subjects(RDF.type, EX.Listing))
    for subject, predicate, obj in scenario:
        if predicate in STATE_PREDICATES or predicate == EX.everContained:
            continue
        if subject in resource_catalog and (subject not in known_resources or predicate == PROV.wasDerivedFrom):
            continue
        if subject in all_events and subject not in events:
            continue
        if subject in all_statements and subject not in visible_statements:
            continue
        if subject in all_batches and subject not in known_batches:
            continue
        if subject in all_listings and subject not in known_listings:
            continue
        graph.add((subject, predicate, obj))
    graph.add((EX.projection, RDF.type, PROV.Entity))
    graph.add((EX.projection, EX.origin, EX.Derived))
    for event in ordered:
        graph.add((EX.projection, PROV.wasDerivedFrom, event))
    # Known validity intervals for constructed listing identifiers only. Keep
    # evidence for every assertion; a retraction closes all open same-value records.
    # For real mirror rows, never guess an observation's start/end times.
    active_assignments = {}
    for event in ordered:
        for predicate in [EX.retracts, EX.asserts]:
            for statement in scenario.objects(event, predicate):
                if scenario.value(statement, RDF.predicate) != EX.activeIdentifier:
                    continue
                listing = scenario.value(statement, RDF.subject)
                value = scenario.value(statement, RDF.object)
                if predicate == EX.retracts:
                    for assignment in active_assignments.pop((listing, value), []):
                        graph.add((assignment, EX.validTo, scenario.value(event, EX.occurredAt)))
                    continue
                assignment = uri("constructed-assignment", str(event) + ":" + str(statement))
                identifier = uri("constructed-identifier", value)
                for triple in [(assignment, RDF.type, EX.IdentifierAssignment), (assignment, EX.origin, EX.Derived),
                               (assignment, EX.listing, listing), (assignment, EX.identifier, identifier),
                               (assignment, EX.validFrom, scenario.value(event, EX.occurredAt)),
                               (assignment, EX.recordedAt, scenario.value(event, EX.recordedAt)),
                               (assignment, PROV.wasDerivedFrom, event), (identifier, RDF.type, EX.ExternalIdentifier),
                               (identifier, EX.identifierType, EX.offerId), (identifier, EX.value, value),
                               (identifier, EX.scope, EX.constructedAccount)]:
                    graph.add(triple)
                active_assignments.setdefault((listing, value), []).append(assignment)
    return graph


def select(graph, query):
    return [{str(key): str(value) for key, value in row.asdict().items()}
            for row in graph.query(PREFIX + query)]


QUERIES = {
    "onhand_objects": "SELECT ?object WHERE { ?object ex:kind ex:Object; ex:status ex:OnHand } ORDER BY ?object",
    "saleable_forms": "SELECT ?resource ?kind WHERE { ?resource ex:kind ?kind; ex:status ex:OnHand. FILTER(?kind IN (ex:Object, ex:Pack)) FILTER NOT EXISTS { ?parent ex:contains ?resource } } ORDER BY ?resource",
    "active_packs": "SELECT ?pack (COUNT(?object) AS ?objects) WHERE { ?pack ex:kind ex:Pack; ex:status ex:OnHand; ex:contains+ ?object. ?object ex:kind ex:Object } GROUP BY ?pack ORDER BY ?pack",
    "pack_origin": "SELECT ?object ?batch WHERE { ex:pack-mixed ex:everContained ?object. ?object ex:batch ?batch } ORDER BY ?object",
    "transformation_origin": "SELECT ?input ?batch WHERE { ex:object-a1-modified prov:wasDerivedFrom+ ?input. ?input ex:batch ?batch }",
    "inherited_location": "SELECT ?location WHERE { ex:object-a5 (^ex:contains)* / ex:location ?location }",
    "identifier": "SELECT ?identifier WHERE { ex:listing-pair-1 ex:activeIdentifier ?identifier }",
    "identifier_history": "SELECT ?value ?from ?to WHERE { ?assignment a ex:IdentifierAssignment; ex:listing ex:listing-pair-1; ex:identifier / ex:value ?value; ex:validFrom ?from. OPTIONAL { ?assignment ex:validTo ?to } } ORDER BY ?from",
    "evidence": "SELECT ?event ?kind ?evidence ?origin WHERE { ?event a ex:InventoryEvent; ex:kind ?kind; ex:evidence ?evidence. ?evidence ex:origin ?origin } ORDER BY ?event",
}


def checks(scenario, shapes, observed, projector=project):
    passed = []
    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        passed.append(name)
    @lru_cache(maxsize=None)
    def state(day, knowledge=16):
        return projector(scenario, f"2026-01-{day:02}T23:59:59Z", f"2026-01-{knowledge:02}T23:59:59Z")
    def conforms(graph):
        return validate(graph, shacl_graph=shapes, inference="none")[0]
    final = state(14)
    check("scenario SHACL", conforms(scenario))
    for day, leaves, forms, packs in [(2, 12, 12, 0), (3, 12, 3, 3), (5, 8, 2, 2), (6, 12, 3, 3), (7, 12, 6, 2), (8, 12, 4, 4), (14, 12, 7, 3)]:
        graph = state(day)
        check(f"day {day}: historical state SHACL", conforms(graph))
        check(f"day {day}: objects/forms/packs", (len(select(graph, QUERIES["onhand_objects"])), len(select(graph, QUERIES["saleable_forms"])), len(select(graph, QUERIES["active_packs"]))) == (leaves, forms, packs))
    origins = select(final, QUERIES["pack_origin"])
    check("mixed sold pack traces to four original objects and two batches", len(origins) == 4 and {row["batch"] for row in origins} == {str(EX["batch-a"]), str(EX["batch-b"])})
    check("transformation lineage to consumed input", select(final, QUERIES["transformation_origin"]) == [{"input": str(EX["object-a1"]), "batch": str(EX["batch-a"])}])
    check("consumed identity retained, excluded from onhand", (EX["object-a1"], EX.status, EX.Consumed) in final and len(set(final.subjects(EX.kind, EX.Object))) == 13)
    check("before correction knowledge", select(state(14, 14), QUERIES["inherited_location"]) == [{"location": str(EX["warehouse-b"])}])
    check("after correction knowledge", select(final, QUERIES["inherited_location"]) == [{"location": str(EX["warehouse-c"])}])
    check("pre-movement valid time unaffected by correction", select(state(12), QUERIES["inherited_location"]) == [{"location": str(EX["warehouse-a"])}])
    check("future lineage does not leak into historical views", not select(state(2), QUERIES["pack_origin"]) and not select(state(2), QUERIES["transformation_origin"]))
    check("future recording does not leak into historical evidence", all(instant(state(14, 14).value(URIRef(row["event"]), EX.recordedAt)) <= instant("2026-01-14T23:59:59Z") for row in select(state(14, 14), QUERIES["evidence"])))
    check("listing identity changed, physical identity retained", select(state(9), QUERIES["identifier"]) != select(state(10), QUERIES["identifier"]) and (EX["pair-1"], EX.contains, EX["object-a5"]) in final)
    check("every scenario event has constructed evidence", len(select(final, QUERIES["evidence"])) == len(set(scenario.subjects(RDF.type, EX.InventoryEvent))) and all(row["origin"] == str(EX.Constructed) for row in select(final, QUERIES["evidence"])))
    bad_facts = {
        "conflicting location": [(EX["pair-1"], EX.location, EX["warehouse-wrong"])],
        "direct and inherited location conflict": [(EX["object-a5"], EX.location, EX["warehouse-wrong"])],
        "duplicate containment": [(EX["pair-2"], EX.contains, EX["object-a5"])],
        "containment cycle": [(EX["object-a5"], EX.contains, EX["pair-1"])],
        "consumed input reuse": [(EX["pair-1"], EX.contains, EX["object-a1"])],
    }
    for name, triples in bad_facts.items():
        invalid = Graph()
        for triple in final:
            invalid.add(triple)
        for triple in triples:
            invalid.add(triple)
        check("reject " + name, not conforms(invalid))
    repeated = Graph()
    for triple in scenario:
        repeated.add(triple)
    for predicate, obj in scenario.predicate_objects(EX["modify-a1"]):
        repeated.add((EX["second-modification"], predicate, obj))
    repeated.set((EX["second-modification"], EX.occurredAt, Literal("2026-01-13T12:01:00Z", datatype=XSD.dateTime)))
    repeated.set((EX["second-modification"], EX.recordedAt, Literal("2026-01-13T12:01:00Z", datatype=XSD.dateTime)))
    try:
        projector(repeated, "2026-01-16T00:00:00Z", "2026-01-16T00:00:00Z")
    except ValueError as error:
        check("reject consuming an input a second time", "unavailable or already consumed" in str(error))
    else:
        check("reject consuming an input a second time", False)
    intervals = select(final, QUERIES["identifier_history"])
    check("time-scoped listing assignment intervals", len(intervals) == 2 and "to" in intervals[0] and "to" not in intervals[1])
    one, two = EX["listing-one"], EX["listing-two"]
    check("ambiguous identifiers never automatically match", match({"sku": "1", "offerId": "2"}, {("sku", "1"): {one}, ("offerId", "2"): {two}})[0] == EX.Ambiguous)
    check("conflicting identifier does not inherit an article match", match({"sku": "unknown", "offerId": "2"}, {("offerId", "2"): {one}})[0] == EX.Ambiguous)
    check("unknown identifier remains unresolved", match({"sku": "unknown"}, {})[0] == EX.Unresolved)
    check("real observations create no physical individuals", not list(observed.subjects(RDF.type, VF.EconomicResource)))
    check("product and SKU identifier namespaces remain distinct", raw_ids("ozon_stock_snapshots", {"ozon_product_id": TARGET["productId"], "raw_payload": {"stock": {"sku": TARGET["sku"]}}}) == {"productId": TARGET["productId"], "sku": TARGET["sku"]})
    return passed, final


def run(path):
    from rules_projection import rules_project
    from rule_cases import compare_projections

    capture = load_capture(path)
    dataset = Dataset()
    bind(dataset)
    dataset.graph(EX.model).parse(ROOT / "model.ttl")
    scenario = bind(dataset.graph(EX.constructed))
    scenario.parse(ROOT / "scenario.ttl")
    shapes = Graph().parse(ROOT / "shapes.ttl")
    observed = observations(dataset, capture)
    reference_checks, reference_final = checks(scenario, shapes, observed)
    # Cache only this frozen base fixture. Mutated independent cases aren't cached.
    @lru_cache(maxsize=None)
    def cached_rules(valid, known):
        return rules_project(scenario, valid, known)
    def rule_backend(graph, valid, known):
        return cached_rules(valid, known) if graph is scenario else rules_project(graph, valid, known)
    rule_checks, final = checks(scenario, shapes, observed, rule_backend)
    differential_checks = compare_projections(scenario, shapes, project, rule_backend)
    passed = ["Python reference: " + name for name in reference_checks]
    passed += ["RDF rules: " + name for name in rule_checks] + differential_checks
    if set(reference_final) != {triple for triple in final if triple[1] != EX.inheritedLocation}:
        raise AssertionError("Final RDF-rule projection differs from Python reference")
    # Trust-boundary checks alter copies only; the frozen client evidence is intact.
    with tempfile.TemporaryDirectory(prefix="inventory-rdf-check-") as directory:
        for name, change in [
            ("payload tampering", lambda data: data["stored"][0]["rows"][0].update(name="tampered")),
            ("endpoint/filter metadata tampering", lambda data: data["fresh"][0]["request"].update(unexpected=True)),
            ("record bound tampering", lambda data: data["manifest"].update(recordCount=1001)),
        ]:
            altered = deepcopy(capture)
            change(altered)
            altered_path = Path(directory) / "altered.json"
            altered_path.write_text(json.dumps(altered, ensure_ascii=False))
            os.chmod(altered_path, 0o600)
            try:
                load_capture(altered_path)
            except (subprocess.CalledProcessError, ValueError):
                passed.append("reject " + name)
            else:
                raise AssertionError("Accepted " + name)
    for triple in final:
        dataset.graph(EX.derived).add(triple)
    roundtrip = Dataset()
    serialized = dataset.serialize(format="trig")
    roundtrip.parse(data=serialized, format="trig")
    if set(roundtrip.quads()) != set(dataset.quads()):
        raise AssertionError("TriG roundtrip changed triples or graph boundaries")
    passed.append("TriG roundtrip preserves graph dataset")
    results = {name: {"sparql": PREFIX + query, "rows": select(final, query)} for name, query in QUERIES.items()}
    query = "SELECT ?status (COUNT(?decision) AS ?count) WHERE { ?decision a ex:IdentityDecision; ex:matchStatus ?status } GROUP BY ?status"
    results["observed_matches"] = {"sparql": PREFIX + query, "rows": select(dataset.graph(EX.identityDecisions), query)}
    query = "SELECT ?decision ?evidence ?table ?id WHERE { GRAPH ex:identityDecisions { ?decision a ex:IdentityDecision; ex:matchStatus ex:Matched; prov:wasDerivedFrom ?evidence } GRAPH ex:observed { ?evidence ex:sourceTable ?table; ex:sourceId ?id } } ORDER BY ?decision LIMIT 5"
    results["matching_evidence"] = {"sparql": PREFIX + query, "rows": select(dataset, query)}
    report = {"projectionEngine": "pySHACL SHACL-AF SPARQL rules", "ruleComparisons": len(differential_checks), "capturedAt": capture["capturedAt"], "storedRecords": capture["manifest"]["recordCount"], "tableCounts": {part["table"]: len(part["rows"]) for part in capture["stored"]}, "freshStatuses": [{"endpoint": response["endpoint"], "status": response["status"]} for response in capture["fresh"]], "checks": passed, "queries": results}
    output = ROOT / "private"
    output.mkdir(mode=0o700, exist_ok=True)
    os.chmod(output, 0o700)
    for name, text in [("dataset.trig", serialized), ("results.json", json.dumps(report, ensure_ascii=False, indent=2)), ("report.md", render_report(report))]:
        target = output / name
        target.write_text(text)
        os.chmod(target, 0o600)
    print(f"PASS: {len(passed)} checks; {report['storedRecords']} source records; {len(dataset)} RDF triples")
    print(f"Report: {output / 'report.md'}")


def render_report(report):
    lines = ["# Inventory RDF proof results", "", f"Capture: {report['capturedAt']}. {report['storedRecords']} frozen source records. {len(report['checks'])} checks passed.", "", "## Evidence coverage", "", "| Source | Records |", "|---|---:|"]
    lines += [f"| {table} | {count} |" for table, count in report["tableCounts"].items()]
    lines += ["", f"Projection engine: {report['projectionEngine']}. {report['ruleComparisons']} differential checks compare the declarative projection with the Python reference, including time boundaries and invalid history. The published derived graph comes from the RDF rules."]
    lines += ["", "Fresh capture outcomes:"]
    lines += [f"- `{entry['endpoint']}`: HTTP {entry['status'] if entry['status'] is not None else 'transport failure'}." for entry in report["freshStatuses"]]
    lines += ["", "Fresh responses and their filters/times/checksums are in capture.json. Stored record evidence URIs use `urn:tover:inventory-proof:record:<table>:<id>`. Full SPARQL and evidence-linked rows are in results.json; dataset.trig separates observed evidence, derived identity decisions, constructed events and temporal projections. The initial analytics server error remains alongside a later successful capture; failed responses are not stock observations.", "", "## Known-answer results", "", "12 constructed objects remain on hand after repacking and one modification; 13 object identities exist because the consumed input is retained. The final saleable view has 3 packs and 4 loose objects (7 forms), covering 12 underlying objects. These two totals are different reporting measures and must not be added.", "", "The sold mixed pack traces to four original objects from two receipt batches. Relabelling changes a listing identifier while the pair and its objects keep their URIs. A late correction changes the inherited location from warehouse-b to warehouse-c when recording knowledge advances, without rewriting the original event.", "", "## Assessment", "", "RDF expresses identity, provenance, containment and transformation links. Ordered SHACL-AF SPARQL rules reconstruct temporal state, identifier intervals and lineage; SHACL validates the resulting graph. Rules also detect unavailable transformation inputs at the time of consumption, even when later events restore their final status. Python is now the reference oracle and runner, not the published projection policy. This proves a declarative RDF rules implementation, not a native Datalog implementation.", "", "Each projection uses a fresh working graph: rule conclusions are additive, and a new cutoff or correction must not retain stale deductions. Correction chains replace earlier effects without resurrecting originals; competing effective corrections and correction cycles are rejected. Timestamp ties use URI order as a deterministic proof convention, not evidence of real-world order.", "", "Real Ozon evidence is aggregate and incomplete. Matching uses typed, account-scoped identifier claims and leaves unknown or conflicting references unresolved. A match is a derived claim from evidence, not proof of physical identity. No individual sales or supplier origins were invented for real records. Snapshots and different quantity measures cannot be summed across dates or assumed comparable.", "", "The capture inherits historical gaps and redaction/merging losses already present in stored mirrors. Fresh responses improve present evidence but cannot reconstruct missing earlier revisions. This proof does not evaluate production storage guarantees, incremental maintenance, performance, validation of every intermediate state, OWL reasoning, configurable automation or completeness of all Ozon endpoints.", "", "## Checks", ""]
    lines += [f"- PASS: {name}" for name in report["checks"]]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture", type=Path, default=ROOT / "private/capture.json")
    args = parser.parse_args()
    run(args.capture)
