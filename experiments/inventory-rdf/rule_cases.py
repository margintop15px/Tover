"""Bounded differential checks and independent known-answer temporal cases."""
from datetime import timedelta

from pyshacl import validate
from rdflib import Graph, Literal, Namespace, RDF, XSD


def compare_projections(scenario, shapes, reference_project, rules_project):
    """Require exact projection parity; never compare just aggregate counts.

    The ex:inheritedLocation materialization is checked against an independent
    containment-path query, then excluded from equality because replay leaves
    inherited locations queryable rather than materializing them.

    Invalid consumption may raise or produce SHACL-invalid diagnostic facts.
    Invalid state/correction cases must be rejected, not silently repaired.
    This function operates only on copies and returns human-readable check names.
    """
    from experiment import EX, VF, PROV, instant

    passed = []

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        passed.append(name)

    def equivalent(graph, business, knowledge, name):
        expected = reference_project(graph, business, knowledge)
        actual = rules_project(graph, business, knowledge)
        inherited = set(actual.triples((None, EX.inheritedLocation, None)))
        expected_inherited = {(row.object, EX.inheritedLocation, row.location)
                              for row in expected.query(f"""
            PREFIX ex: <{EX}>
            SELECT DISTINCT ?object ?location WHERE {{
                ?ancestor ex:contains+ ?object ; ex:location ?location .
            }}""")}
        if inherited != expected_inherited:
            raise AssertionError(name + ": incorrect recursive inherited locations")
        actual_core = set(actual) - inherited
        missing, extra = set(expected) - actual_core, actual_core - set(expected)
        if missing or extra:
            detail = lambda triples: sorted(" ".join(term.n3() for term in triple) for triple in triples)[:6]
            raise AssertionError(f"{name}: missing={detail(missing)}, extra={detail(extra)}")
        passed.append(name)
        return actual

    def conforms(graph):
        return validate(graph, shacl_graph=shapes, inference="none")[0]

    def fixture():
        graph = Graph()
        graph.add((EX["case-evidence"], RDF.type, PROV.Entity))
        graph.add((EX["case-evidence"], EX.origin, EX.Constructed))
        return graph

    def resource(graph, name, kind=EX.Object):
        node = EX[name]
        graph.add((node, RDF.type, VF.EconomicResource))
        graph.add((node, EX.kind, kind))
        graph.add((node, EX.origin, EX.Constructed))
        return node

    def event(graph, name, day, kind, assertions, retractions=(), *, recorded=None,
              inputs=(), outputs=(), corrects=None):
        node = EX[name]
        occurred = day if isinstance(day, str) else f"2026-01-{day:02}T12:00:00Z"
        recorded = recorded or occurred
        for triple in [(node, RDF.type, EX.InventoryEvent), (node, RDF.type, VF.EconomicEvent),
                       (node, EX.origin, EX.Constructed), (node, EX.kind, kind),
                       (node, EX.occurredAt, Literal(occurred, datatype=XSD.dateTime)),
                       (node, EX.recordedAt, Literal(recorded, datatype=XSD.dateTime)),
                       (node, EX.evidence, EX["case-evidence"])]:
            graph.add(triple)
        if corrects is not None:
            graph.add((node, EX.corrects, corrects))
        for predicate, items in [(EX.input, inputs), (EX.output, outputs)]:
            for item in items:
                graph.add((node, predicate, item))
        affected = {triple[0] for triple in list(assertions) + list(retractions)} | set(inputs) | set(outputs)
        for item in affected:
            graph.add((node, EX.resource, item))
        for predicate, triples in [(EX.retracts, retractions), (EX.asserts, assertions)]:
            for index, triple in enumerate(triples):
                statement = EX[f"{name}-{str(predicate).split(':')[-1]}-{index}"]
                graph.add((node, predicate, statement))
                graph.add((statement, RDF.type, RDF.Statement))
                for field, value in zip([RDF.subject, RDF.predicate, RDF.object], triple):
                    graph.add((statement, field, value))
        return node

    def reject(graph, name, *, reference_rejects=False):
        business = knowledge = "2026-01-16T23:59:59Z"
        if reference_rejects:
            try:
                reference_project(graph, business, knowledge)
            except ValueError as error:
                if str(error) != "Transformation input is unavailable or already consumed":
                    raise
            else:
                raise AssertionError(name + ": reference accepted invalid consumption")
        try:
            actual = rules_project(graph, business, knowledge)
        except ValueError as error:
            if reference_rejects:
                if str(error) != "Transformation input is unavailable or already consumed":
                    raise
            elif not str(error).startswith("Invalid rule projection:"):
                raise
            passed.append(name)
        else:
            if reference_rejects:
                raise AssertionError(name + ": rules did not reject invalid consumption")
            check(name, not conforms(actual))

    # The fixed grid detects future-knowledge leakage and historical count drift.
    for day in range(1, 15):
        for knowledge in (14, 16):
            equivalent(scenario, f"2026-01-{day:02}T23:59:59Z",
                       f"2026-01-{knowledge:02}T23:59:59Z",
                       f"rules parity: business day {day}, knowledge day {knowledge}")

    # Cutoffs are inclusive. Inspect each unique event-time boundary independently
    # of the end-of-day grid, and the late correction's recording-time boundary.
    event_times = sorted({instant(value) for value in scenario.objects(None, EX.occurredAt)})
    for moment in event_times:
        for delta, label in [(-1, "before"), (0, "at"), (1, "after")]:
            business = (moment + timedelta(seconds=delta)).isoformat()
            equivalent(scenario, business, "2026-01-16T23:59:59Z",
                       f"rules parity: {label} event boundary {moment.isoformat()}")
    correction_times = sorted({instant(scenario.value(node, EX.recordedAt))
                               for node in scenario.subjects(EX.corrects, None)})
    for moment in correction_times:
        for delta, label in [(-1, "before"), (0, "at"), (1, "after")]:
            knowledge = (moment + timedelta(seconds=delta)).isoformat()
            equivalent(scenario, "2026-01-14T23:59:59Z", knowledge,
                       f"rules parity: {label} correction knowledge boundary {moment.isoformat()}")

    old = rules_project(scenario, "2026-01-02T23:59:59Z", "2026-01-16T23:59:59Z")
    check("rules: no future containment or transformation lineage",
          not list(old.triples((None, EX.everContained, None)))
          and (EX["object-a1-modified"], PROV.wasDerivedFrom, EX["object-a1"]) not in old)

    graph = fixture()
    obj = resource(graph, "tie-object")
    a, b, c = EX["case-a"], EX["case-b"], EX["case-c"]
    event(graph, "tie-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a)])
    # Insert in reverse URI order: graph traversal/insertion order must not decide.
    event(graph, "tie-z-move", 2, EX.Move, [(obj, EX.location, c)], [(obj, EX.location, b)])
    event(graph, "tie-a-move", 2, EX.Move, [(obj, EX.location, b)], [(obj, EX.location, a)])
    actual = equivalent(graph, "2026-01-03T00:00:00Z", "2026-01-03T00:00:00Z", "rules parity: timestamp ties")
    check("rules: timestamp ties use deterministic URI order", set(actual.objects(obj, EX.location)) == {c})

    graph = fixture()
    obj = resource(graph, "same-event-object")
    event(graph, "same-event-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a)])
    event(graph, "same-event-move", 2, EX.Move, [(obj, EX.location, a)], [(obj, EX.location, a)])
    actual = equivalent(graph, "2026-01-03T00:00:00Z", "2026-01-03T00:00:00Z", "rules parity: retract/assert within one event")
    check("rules: same-event assertion wins over exact-triple retraction", set(actual.objects(obj, EX.location)) == {a})

    graph = fixture()
    obj = resource(graph, "reassert-object")
    event(graph, "reassert-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a)])
    event(graph, "reassert-move-away", 2, EX.Move, [(obj, EX.location, b)], [(obj, EX.location, a)])
    event(graph, "reassert-move-back", 3, EX.Move, [(obj, EX.location, a)], [(obj, EX.location, b)])
    actual = equivalent(graph, "2026-01-04T00:00:00Z", "2026-01-04T00:00:00Z", "rules parity: later reassertion")
    check("rules: later reassertion survives earlier retraction", set(actual.objects(obj, EX.location)) == {a})

    # Each assertion retains an evidence-bearing assignment. Retracting an exact
    # listing/value closes every open record for it, including repeated claims.
    graph = fixture()
    obj = resource(graph, "repeat-id-object")
    listing = EX["repeat-id-listing"]
    graph.add((listing, RDF.type, EX.Listing))
    graph.add((listing, EX.origin, EX.Constructed))
    graph.add((obj, EX.listing, listing))
    old_id, new_id = Literal("ARTICLE-A", datatype=XSD.string), Literal("ARTICLE-B", datatype=XSD.string)
    receive = event(graph, "repeat-id-receive", 1, EX.Receive,
                    [(obj, EX.status, EX.OnHand), (listing, EX.activeIdentifier, old_id)])
    confirm = event(graph, "repeat-id-confirm", 2, EX.Relabel, [(listing, EX.activeIdentifier, old_id)])
    switch = event(graph, "repeat-id-switch", 3, EX.Relabel,
                   [(listing, EX.activeIdentifier, new_id)], [(listing, EX.activeIdentifier, old_id)])
    reassert = event(graph, "repeat-id-return", 4, EX.Relabel,
                     [(listing, EX.activeIdentifier, old_id)], [(listing, EX.activeIdentifier, new_id)])
    same_event = event(graph, "repeat-id-same-event", 5, EX.Relabel,
                      [(listing, EX.activeIdentifier, old_id)], [(listing, EX.activeIdentifier, old_id)])
    interval_query = f"""
        PREFIX ex: <{EX}> PREFIX prov: <{PROV}>
        SELECT ?value ?from ?to ?event WHERE {{
            ?assignment a ex:IdentifierAssignment ; ex:listing ex:repeat-id-listing ;
                ex:identifier / ex:value ?value ; ex:validFrom ?from ; prov:wasDerivedFrom ?event .
            OPTIONAL {{ ?assignment ex:validTo ?to }}
        }}
    """
    expected_intervals = {
        2: [("ARTICLE-A", 1, None, receive), ("ARTICLE-A", 2, None, confirm)],
        3: [("ARTICLE-A", 1, 3, receive), ("ARTICLE-A", 2, 3, confirm), ("ARTICLE-B", 3, None, switch)],
        4: [("ARTICLE-A", 1, 3, receive), ("ARTICLE-A", 2, 3, confirm), ("ARTICLE-B", 3, 4, switch), ("ARTICLE-A", 4, None, reassert)],
        5: [("ARTICLE-A", 1, 3, receive), ("ARTICLE-A", 2, 3, confirm), ("ARTICLE-B", 3, 4, switch), ("ARTICLE-A", 4, 5, reassert), ("ARTICLE-A", 5, None, same_event)],
    }
    def time(day):
        return None if day is None else f"2026-01-{day:02}T12:00:00+00:00"
    for day, intervals in expected_intervals.items():
        actual = equivalent(graph, f"2026-01-{day:02}T23:59:59Z", "2026-01-16T23:59:59Z",
                            f"rules parity: repeated identifier assignment day {day}")
        rows = [(str(row.value), instant(row['from']).isoformat(),
                 instant(row.to).isoformat() if row.to is not None else None, str(row.event))
                for row in actual.query(interval_query)]
        expected = {(value, time(start), time(end), str(source)) for value, start, end, source in intervals}
        check(f"rules: exact identifier validity intervals and assertion evidence day {day}",
              len(rows) == len(intervals) and set(rows) == expected)
        check(f"rules: current identifier agrees with closed/open history day {day}",
              set(actual.objects(listing, EX.activeIdentifier)) == {new_id if day == 3 else old_id})

    graph = fixture()
    obj = resource(graph, "chain-object")
    event(graph, "chain-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a)])
    original = event(graph, "chain-original", 2, EX.Move, [(obj, EX.location, b)], [(obj, EX.location, a)])
    first = event(graph, "chain-correction-1", 2, EX.Move, [(obj, EX.location, c)], [(obj, EX.location, a)],
                  recorded="2026-01-03T12:00:00Z", corrects=original)
    d = EX["case-d"]
    event(graph, "chain-correction-2", 2, EX.Move, [(obj, EX.location, d)], [(obj, EX.location, a)],
          recorded="2026-01-04T12:00:00Z", corrects=first)
    for knowledge, location in [(2, b), (3, c), (4, d)]:
        actual = equivalent(graph, "2026-01-05T00:00:00Z", f"2026-01-{knowledge:02}T23:59:59Z",
                            f"rules parity: correction chain knowledge day {knowledge}")
        check(f"rules: correction chain selects expected location day {knowledge}",
              set(actual.objects(obj, EX.location)) == {location})
    check("rules: correction of correction never resurrects original event",
          (EX.projection, PROV.wasDerivedFrom, original) not in actual)

    def consumption_case(receipt_day=1, repeated=False, restore=False):
        graph = fixture()
        obj = resource(graph, "consume-input")
        out = resource(graph, "consume-output")
        event(graph, "consume-receive", receipt_day, EX.Receive, [(obj, EX.status, EX.OnHand)])
        event(graph, "consume-first", 2, EX.Transform,
              [(obj, EX.status, EX.Consumed), (out, EX.status, EX.OnHand)],
              [(obj, EX.status, EX.OnHand)], inputs=[obj], outputs=[out])
        if repeated:
            second = resource(graph, "consume-second-output")
            event(graph, "consume-second", 3, EX.Transform,
                  [(obj, EX.status, EX.Consumed), (second, EX.status, EX.OnHand)],
                  [(obj, EX.status, EX.OnHand)], inputs=[obj], outputs=[second])
        if restore:
            event(graph, "consume-late-restoration", 4, EX.Return,
                  [(obj, EX.status, EX.OnHand)], [(obj, EX.status, EX.Consumed)])
        return graph

    reject(consumption_case(receipt_day=3), "rules reject input unavailable before its later receipt", reference_rejects=True)
    reject(consumption_case(repeated=True), "rules reject repeated transformation consumption", reference_rejects=True)
    reject(consumption_case(repeated=True, restore=True), "rules reject invalid consumption despite later OnHand restoration", reference_rejects=True)

    graph = fixture()
    obj = resource(graph, "conflict-object")
    event(graph, "conflict-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a), (obj, EX.location, b)])
    reject(graph, "rules reject conflicting active locations")

    graph = fixture()
    obj = resource(graph, "contained-object")
    one, two = resource(graph, "container-one", EX.Pack), resource(graph, "container-two", EX.Pack)
    event(graph, "containment-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand)])
    event(graph, "containment-pack", 2, EX.Pack, [(one, EX.status, EX.OnHand), (two, EX.status, EX.OnHand),
                                               (one, EX.contains, obj), (two, EX.contains, obj)])
    reject(graph, "rules reject duplicate active containment")

    # Two eligible direct replacements are a source conflict. Validation of the
    # event graph must reject this, even if their final facts happen to agree.
    graph = fixture()
    obj = resource(graph, "competing-object")
    event(graph, "competing-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a)])
    original = event(graph, "competing-original", 2, EX.Move, [(obj, EX.location, b)], [(obj, EX.location, a)])
    for index, day in [(1, 3), (2, 4)]:
        event(graph, f"competing-correction-{index}", 2, EX.Move, [(obj, EX.location, c)], [(obj, EX.location, a)],
              recorded=f"2026-01-{day:02}T12:00:00Z", corrects=original)
    projection = reference_project(graph, "2026-01-16T23:59:59Z", "2026-01-16T23:59:59Z")
    valid, report, _ = validate(projection, shacl_graph=shapes, inference="none")
    messages = " ".join(str(message).lower() for message in report.objects(None, Namespace("http://www.w3.org/ns/shacl#").resultMessage))
    check("shapes explicitly reject competing corrections despite identical replacement facts", not valid and "correction" in messages)
    reject(graph, "rules reject competing corrections rather than choose a URI winner")

    # Replacing one branch does not resolve its competition with the other branch.
    # Both surviving replacements still reach the same corrected ancestor.
    event(graph, "competing-correction-3", 2, EX.Move, [(obj, EX.location, c)], [(obj, EX.location, a)],
          recorded="2026-01-05T12:00:00Z", corrects=EX["competing-correction-1"])
    projection = reference_project(graph, "2026-01-16T23:59:59Z", "2026-01-16T23:59:59Z")
    valid, report, _ = validate(projection, shacl_graph=shapes, inference="none")
    messages = " ".join(str(message).lower() for message in report.objects(None, Namespace("http://www.w3.org/ns/shacl#").resultMessage))
    check("shapes explicitly reject indirect competing correction branches", not valid and "correction" in messages)
    reject(graph, "rules reject indirect correction competition despite identical replacement facts")

    graph = fixture()
    obj = resource(graph, "cycle-object")
    event(graph, "cycle-receive", 1, EX.Receive, [(obj, EX.status, EX.OnHand), (obj, EX.location, a)])
    first = event(graph, "cycle-correction-1", 2, EX.Move, [(obj, EX.location, b)], [(obj, EX.location, a)],
                  corrects=EX["cycle-correction-2"])
    event(graph, "cycle-correction-2", 2, EX.Move, [(obj, EX.location, c)], [(obj, EX.location, a)], corrects=first)
    reject(graph, "rules reject correction cycles")
    return passed
