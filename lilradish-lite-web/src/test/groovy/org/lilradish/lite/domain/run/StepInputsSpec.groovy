package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.RUN
import static org.lilradish.lite.domain.run.fixture.Runs.askedAt
import static org.lilradish.lite.domain.run.fixture.Runs.json
import static org.lilradish.lite.domain.run.fixture.Runs.key
import static org.lilradish.lite.domain.run.fixture.Runs.lengthReview
import static org.lilradish.lite.domain.run.fixture.Runs.lost
import static org.lilradish.lite.domain.run.fixture.Runs.minutes
import static org.lilradish.lite.domain.run.fixture.Runs.model
import static org.lilradish.lite.domain.run.fixture.Runs.modelReview
import static org.lilradish.lite.domain.run.fixture.Runs.nested
import static org.lilradish.lite.domain.run.fixture.Runs.nestedMany
import static org.lilradish.lite.domain.run.fixture.Runs.open
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.takes
import static org.lilradish.lite.domain.run.fixture.Runs.text
import static org.lilradish.lite.domain.run.fixture.Runs.tryId
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.valueId
import static org.lilradish.lite.domain.run.fixture.Runs.yielded
import static org.lilradish.lite.domain.workflow.StepProducer.MODEL

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class StepInputsSpec extends Specification {

    static final Declaration TAKES = takes([
            text("title"),
            nested("address", [text("street"), nested("geo", [text("lat"), text("lng")])]),
            nestedMany("contacts", [text("name"), text("phone")]),
            text("note"),
    ])

    static final PlannedStep SOURCE = question(1, model(), 2)

    static final Map STARTED_WITH = [subject: "Ledger", deep: [inner: "Kept"]]

    def "what a step takes traces each binding in the order written, to the value a step made only where one did"() {
        given:
        def bindings = [
                binding(1, "contacts", fromStep("people")),
                binding(2, "title", fromRun("subject")),
                binding(3, "address.street", written("Kept as written")),
        ]
        def running = runWith(standingSource(), bindings)

        expect:
        StepInputs.traced(running, running.steps()[1], TAKES) == Optional.of([
                new InputRecord(key(801), valueId(3)),
                new InputRecord(key(802), null),
                new InputRecord(key(803), null),
        ])
    }

    def "what a step takes is not there yet while a step it reads from has no value standing"() {
        given:
        def running = runWith(sourceWith(source), [binding(1, "title", fromStep("summary"))])

        expect:
        StepInputs.traced(running, running.steps()[1], TAKES) == Optional.empty()

        where:
        source << SOURCES_NOT_STANDING
    }

    def "a binding filling nothing the step takes is refused as what no approved version could hold"() {
        given:
        def running = runWith(standingSource(), [binding(1, "note", written("Fine")), binding(2, target, written("Stray"))])

        when:
        StepInputs.traced(running, running.steps()[1], TAKES)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(802) + " fills nothing its step takes"

        where:
        target << ["nowhere", "title.inner", "contacts.name"]
    }

    def "a binding filling what another fills, within it or around it, is refused where what a step takes is traced"() {
        given:
        def bindings = targets.withIndex().collect { target, index -> binding(index + 1, target, written("x")) }
        def running = runWith(standingSource(), bindings)

        when:
        StepInputs.traced(running, running.steps()[1], TAKES)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(later) + " fills what binding " + key(earlier) + " fills"

        where:
        targets                                               || earlier | later
        ["title", "title"]                                    || 801     | 802
        ["address", "address.street"]                         || 801     | 802
        ["note", "address.geo.lat", "address.street", "address.geo"] || 802 | 804
    }

    def "a binding filling no input at all is refused where what a step takes is read"() {
        given:
        def running = runWith(standingSource(), [binding(1, null, written("Stray"))])

        when:
        StepInputs.traced(running, running.steps()[1], TAKES)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(801) + " fills no input"
    }

    def "what is filled holds each field taken at the first level in declared order, none where nothing was bound"() {
        when:
        def filled = StepInputs.filled(TAKES, bindings(bound))

        then:
        filled == json([title: title, address: null, contacts: null, note: note])

        where:
        bound                           || title | note
        [:]                             || null  | null
        [note: "Later", title: "First"] || "First" | "Later"
        [title: null]                   || null  | null
    }

    def "every object filled holds each field it declares, in declared order and none where unfilled, at every depth"() {
        when:
        def filled = StepInputs.filled(TAKES, bindings(bound))

        then:
        filled == json([title: null, address: address, contacts: contacts, note: null])

        where:
        bound                                                  || address                                          | contacts
        ["address.street": "Main"]                             || [street: "Main", geo: null]                      | null
        ["address.geo.lat": "51.5", "address.street": "Main"]  || [street: "Main", geo: [lat: "51.5", lng: null]]  | null
        [address: [geo: [lng: "0.1"], street: "Main"]]         || [street: "Main", geo: [lat: null, lng: "0.1"]]   | null
        [address: [geo: null]]                                 || [street: null, geo: null]                        | null
        [contacts: [[phone: "555"], [phone: "1", name: "Ann"]]] || null                                            | [[name: null, phone: "555"], [name: "Ann", phone: "1"]]
        [contacts: []]                                         || null                                             | []
    }

    def "a value bound holding a field its field does not declare is refused, naming the binding"() {
        when:
        StepInputs.filled(TAKES, bindings(bound))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(801) + " fills a value holding a field not declared"

        where:
        bound << [
                [address: [street: "Main", zip: "1"]],
                [address: [geo: [lat: "1", alt: "2"]]],
                [contacts: [[name: "Ann"], [name: "Bo", age: "9"]]],
        ]
    }

    def "a binding filling what another fills, within it or around it, is refused, naming the later and the earlier"() {
        when:
        StepInputs.filled(TAKES, aimed(targets))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(later) + " fills what binding " + key(earlier) + " fills"

        where:
        targets                                             || earlier | later
        ["title", "title"]                                  || 801     | 802
        ["address", "address.street"]                       || 801     | 802
        ["address.geo.lat", "address"]                      || 801     | 802
        ["note", "address.street", "address.geo", "address"] || 802    | 804
    }

    def "a binding filling nothing its step takes, or no input at all, is refused where what is filled is read"() {
        when:
        StepInputs.filled(TAKES, aimed(["note", target]))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(802) + message

        where:
        target          || message
        "nowhere"       || " fills nothing its step takes"
        "title.inner"   || " fills nothing its step takes"
        "contacts.name" || " fills nothing its step takes"
        null            || " fills no input"
    }

    def "what a try took is read back per binding in the order written, a step's value from the one it was traced to"() {
        given:
        def bindings = [binding(1, "note", fromStep("profile.name")), binding(2, "title", written("Kept as written")),
                        binding(3, "address.street", fromRun("subject"))]
        def taken = [new InputRecord(key(803), null), new InputRecord(key(802), null),
                     new InputRecord(key(801), valueId(1))]
        def running = ranWith(bindings, taken)

        when:
        def took = StepInputs.took(running, running.steps()[1], running.steps()[1].tries()[0])

        then:
        took == [
                new StepInputs.BindingRecord(bindings[0], json("Ada"), valueId(1)),
                new StepInputs.BindingRecord(bindings[1], json("Kept as written"), null),
                new StepInputs.BindingRecord(bindings[2], json("Ledger"), null),
        ]

        and: "never the value standing now"
        !took*.value().contains(json("Bea"))
    }

    def "a binding a try took nothing through is passed over in what it took"() {
        given:
        def bindings = [binding(1, "note", written("Unread")), binding(2, "title", written("Kept as written"))]
        def running = ranWith(bindings, [new InputRecord(key(802), null)])

        expect:
        StepInputs.took(running, running.steps()[1], running.steps()[1].tries()[0]) ==
                [new StepInputs.BindingRecord(bindings[1], json("Kept as written"), null)]
    }

    def "a try that took a value its step does not hold is refused, naming the binding"() {
        given:
        def running = ranWith([binding(1, "note", fromStep("profile.name"))], [new InputRecord(key(801), valueId(99))])

        when:
        StepInputs.took(running, running.steps()[1], running.steps()[1].tries()[0])

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(801) + " took a value its step does not hold"
    }

    def "each binding of a step is read in the order written"() {
        given:
        def bindings = [binding(1, "note", fromStep("summary")), binding(2, "title", written("Kept as written"))]
        def running = runWith(standingSource(), bindings)

        expect:
        StepInputs.bound(running, running.steps()[1]) == Optional.of([
                new StepInputs.BindingRecord(bindings[0], json("Short"), valueId(2)),
                new StepInputs.BindingRecord(bindings[1], json("Kept as written"), null),
        ])
    }

    def "no binding of a step is read while one of them reads a step with no value standing, whatever the others read"() {
        given:
        def bindings = [binding(1, "title", written("Kept as written")), binding(2, "note", fromStep("summary"))]
        def running = runWith(sourceWith("an open try"), bindings)

        expect:
        StepInputs.bound(running, running.steps()[1]) == Optional.empty()
    }

    def "a constant is read as written"() {
        given:
        def constant = binding(1, "note", written([kept: "as written"]))

        expect:
        StepInputs.bound(runWith(standingSource(), []), constant) ==
                Optional.of(new StepInputs.BindingRecord(constant, json([kept: "as written"]), null))
    }

    def "what a run was started with is read at the pointer, none where nothing is there"() {
        given:
        def input = binding(1, "note", fromRun(pointer))

        expect:
        StepInputs.bound(runWith(standingSource(), []), input) ==
                Optional.of(new StepInputs.BindingRecord(input, json(read), null))

        where:
        pointer         || read
        "subject"       || "Ledger"
        "deep.inner"    || "Kept"
        "deep"          || [inner: "Kept"]
        "missing"       || null
        "subject.inner" || null
    }

    def "what a run beneath another was started with is never read, having been bound by nothing above it"() {
        given:
        def input = binding(1, "note", fromRun("subject"))

        when:
        StepInputs.bound(runWith(standingSource(), [], null), input)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run " + RUN.value() + " beneath another reads what no run above bound into it"
    }

    def "a step's value is read from its newest try at the rest of the pointer, beside the value it came from"() {
        given:
        def output = binding(1, "note", fromStep(pointer))

        expect:
        StepInputs.bound(runWith(standingSource(), []), output) ==
                Optional.of(new StepInputs.BindingRecord(output, json(read), valueId(from)))

        where:
        pointer           || read                        | from
        "summary"         || "Short"                     | 2
        "profile"         || [name: "Ada", tags: ["a"]]  | 1
        "profile.name"    || "Ada"                       | 1
        "profile.missing" || null                        | 1
        "summary.inner"   || null                        | 2
    }

    def "a step's value is not read while its newest try has no value standing"() {
        expect:
        StepInputs.bound(runWith(sourceWith(source), []), binding(1, "note", fromStep("summary"))) == Optional.empty()

        where:
        source << SOURCES_NOT_STANDING
    }

    def "a step's value is refused where the step is not the version's or gave nothing back for the field"() {
        given:
        def output = binding(1, "note", new BindingSource.StepOutput(key(step), Pointer.parse(field)))

        when:
        StepInputs.bound(runWith(standingSource(), []), output)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(801) + message

        where:
        step || field     | message
        999  || "summary" | " reads a step its version does not hold"
        401  || "unknown" | " reads a field its step gave nothing back for"
    }

    def "what stands within a value at some names is what each object holds there, and none past anything else"() {
        expect:
        StepInputs.within(json(held), names == "" ? [] : Pointer.parse(names).names()) == json(found)

        where:
        held              | names || found
        [a: [b: "x"]]     | "a.b" || "x"
        [a: [b: "x"]]     | "a"   || [b: "x"]
        [a: [b: "x"]]     | ""    || [a: [b: "x"]]
        [a: [b: "x"]]     | "c"   || null
        [a: "x"]          | "a.b" || null
        [a: [b: null]]    | "a.b" || null
        [["a"]]           | "a"   || null
        "x"               | "a"   || null
    }

    static final List<String> SOURCES_NOT_STANDING = [
            "not started", "an open try", "a lost try", "a newer try open after one that stood",
            "a value waiting on review", "a value refused on review", "a value refused for its length",
    ]

    private static StepSnapshot standingSource() {
        started(SOURCE, [yielded(1, MODEL, [
                value(1, "profile", false, [name: "Ada", tags: ["a"]]),
                value(2, "summary", false, "Short"),
                value(3, "people", false, [[name: "Ada"], "loose"]),
        ])])
    }

    private static StepSnapshot sourceWith(String source) {
        def waiting = value(2, "summary", true, "Short")
        def standing = value(2, "summary", false, "Short")
        switch (source) {
            case "not started": return unstarted(SOURCE)
            case "an open try": return started(SOURCE, [open(1, MODEL)])
            case "a lost try": return started(SOURCE, [lost(1, MODEL)])
            case "a newer try open after one that stood": return started(SOURCE, [yielded(1, MODEL, [standing]), open(2, MODEL)])
            case "a value waiting on review": return started(SOURCE, [yielded(1, MODEL, [waiting])])
            case "a value refused on review": return started(SOURCE,
                    [yielded(1, MODEL, [waiting], [modelReview(minutes(30), [refused(waiting)])])])
            case "a value refused for its length": return started(SOURCE,
                    [yielded(1, MODEL, [standing], [lengthReview(minutes(30), [refused(standing)])])])
            default: throw new IllegalArgumentException(source)
        }
    }

    private static RunSnapshot runWith(StepSnapshot source, List<Binding> bindings, Object startedWith = STARTED_WITH) {
        run([source, unstarted(questionTaking(2, TAKES, bindings))], false, null, startedWith)
    }

    /**
     * The source step having made a profile of Ada, and since then one of Bea; the step taking it tried once by a
     * person, through what {@code taken} names.
     */
    private static RunSnapshot ranWith(List<Binding> bindings, List<InputRecord> taken) {
        def source = started(SOURCE, [
                yielded(1, MODEL, [value(1, "profile", false, [name: "Ada"])]),
                yielded(2, MODEL, [value(4, "profile", false, [name: "Bea"])])])
        def aTry = new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null, null,
                null, false, null, [], [], taken, [], [])
        run([source, started(questionTaking(2, TAKES, bindings), [aTry])], false, null, STARTED_WITH)
    }

    /** Each constant bound where its target says, keyed in the order given. */
    private static List<StepInputs.BindingRecord> bindings(Map bound) {
        (bound.keySet() as List<String>).withIndex().collect { target, index ->
            new StepInputs.BindingRecord(binding(index + 1, target, written(bound[target])), json(bound[target]), null)
        }
    }

    /** The same constant bound at each target, keyed in the order given. */
    private static List<StepInputs.BindingRecord> aimed(List<String> targets) {
        targets.withIndex().collect { target, index ->
            new StepInputs.BindingRecord(binding(index + 1, target, written("x")), json("x"), null)
        }
    }

    private static Binding binding(int number, String target, BindingSource source) {
        new Binding(key(800 + number), target == null ? null : Pointer.parse(target), source)
    }

    private static BindingSource fromRun(String pointer) {
        new BindingSource.WorkflowInput(Pointer.parse(pointer))
    }

    private static BindingSource fromStep(String pointer) {
        new BindingSource.StepOutput(stepId(1).value(), Pointer.parse(pointer))
    }

    private static BindingSource written(Object constant) {
        new BindingSource.Written(json(constant))
    }
}
