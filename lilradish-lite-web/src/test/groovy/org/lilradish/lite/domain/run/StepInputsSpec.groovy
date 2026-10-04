package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.RUN
import static org.lilradish.lite.domain.run.fixture.Runs.askedAt
import static org.lilradish.lite.domain.run.fixture.Runs.asking
import static org.lilradish.lite.domain.run.fixture.Runs.assured
import static org.lilradish.lite.domain.run.fixture.Runs.codeStep
import static org.lilradish.lite.domain.run.fixture.Runs.gives
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
import static org.lilradish.lite.domain.run.fixture.Runs.personReview
import static org.lilradish.lite.domain.run.fixture.Runs.pinned
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.questionTaking
import static org.lilradish.lite.domain.run.fixture.Runs.refused
import static org.lilradish.lite.domain.run.fixture.Runs.run
import static org.lilradish.lite.domain.run.fixture.Runs.standing
import static org.lilradish.lite.domain.run.fixture.Runs.started
import static org.lilradish.lite.domain.run.fixture.Runs.stepId
import static org.lilradish.lite.domain.run.fixture.Runs.takes
import static org.lilradish.lite.domain.run.fixture.Runs.text
import static org.lilradish.lite.domain.run.fixture.Runs.tidy
import static org.lilradish.lite.domain.run.fixture.Runs.tryId
import static org.lilradish.lite.domain.run.fixture.Runs.unstarted
import static org.lilradish.lite.domain.run.fixture.Runs.value
import static org.lilradish.lite.domain.run.fixture.Runs.valueId
import static org.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static org.lilradish.lite.domain.run.fixture.Runs.yielded
import static org.lilradish.lite.domain.workflow.StepProducer.MODEL

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.file.FileName
import org.lilradish.lite.domain.file.MediaTypeName
import org.lilradish.lite.domain.file.StoredFile
import org.lilradish.lite.domain.filling.FillPath
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.SearchQuestionPart
import org.lilradish.lite.domain.workflow.StepId
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

    def "a historical model input names its actual missing code maker regardless of who produced that code value"() {
        given:
        def planned = codeStep(1, 2, held ? tidy(false) : null)
        def source = started(planned, [yielded(1, producedBy, [value(1, 'receipt', false, 'Demo Sample old')]),
                                       yielded(2, producedBy, [value(2, 'receipt', false, 'Demo Sample live')])])
        def binding = binding(1, 'note', fromStep('receipt'))
        def asked = questionTaking(2, takes([text('note')]), [binding])
        def historical = new TryRecord(tryId(21), null, MODEL, null, askedAt(1), null, null, null, null, null,
                null, false, null, [], [], [new InputRecord(binding.id(), valueId(1))], [], [])
        def destination = started(asked, [historical])
        def at = RunInTree.root(run([source, destination]))
        def expected = new HoldRecord.SourceRecord(at.run().run(), source.runStep(), planned.id().value())

        expect:
        StepInputs.took(at, destination, historical).first().value() == json('Demo Sample old')
        StepInputs.bound(at, destination).orElseThrow().first().value() == json('Demo Sample live')
        StepInputs.missingCodeSource(at, destination) == (held ? Optional.empty() : Optional.of(expected))
        StepInputs.codeSource(at, expected) == source
        historical.inputs().first().source() == valueId(1)
        historical.attempts().empty
        historical.calls().empty

        where:
        producedBy        | held
        StepProducer.CODE | false
        StepProducer.PERSON | false
        StepProducer.CODE | true
        StepProducer.PERSON | true
    }

    def "only the actual selected code field is fitted while a changed unread file sibling stays outside it"() {
        given:
        def selected = text('note')
        def attachment = new Field(new FieldName('attachment'), null, null,
                new FieldShape.File([new MediaTypeName('text/plain')], 100L), new HowMany.One(),
                new Demand.Given(true))
        def current = new Field(new FieldName('receipt'), null, null,
                new FieldShape.Nested([selected, attachment]), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def source = started(codeStep(1, 1, tidy(false, [], [current])),
                [yielded(1, StepProducer.CODE, [value(1, 'receipt', false,
                        [note: 'Demo Sample input', attachment: 'Demo Sample old ordinary value'])])])
        def binding = binding(1, 'note', fromStep('receipt.note'))
        def asked = questionTaking(2, takes([text('note')]), [binding])
        def historical = new TryRecord(tryId(21), null, MODEL, null, askedAt(1), null, null, null, null, null,
                null, false, null, [], [], [new InputRecord(binding.id(), valueId(1))], [], [])
        def destination = started(asked, [historical])
        def at = RunInTree.root(run([source, destination]))

        expect:
        StepInputs.sourceFailure(at, destination).empty
        StepInputs.took(at, destination, historical).first().value() == json('Demo Sample input')
        historical.inputs().first().source() == valueId(1)
        historical.number() == null
        historical.attempts().empty
        historical.calls().empty
    }

    def "a missing term list is classified only for the selected code field and keeps its full source pointer"() {
        given:
        def note = text('note')
        def category = new Field(new FieldName('category'), null, null,
                new FieldShape.Term(new EntryVersionId(key(9801))), new HowMany.One(),
                new Demand.Given(required))
        def receipt = new Field(new FieldName('receipt'), null, null,
                new FieldShape.Nested([note, category]), new HowMany.One(),
                new Demand.Stands(true, FieldStanding.ALWAYS, null))
        def source = started(codeStep(1, 1, tidy(false, [], [receipt])),
                [yielded(1, StepProducer.CODE, [value(1, 'receipt', false,
                        [note: 'Demo Sample note', category: withValue ? 'Demo Sample category' : null])])])
        def path = selected ? 'receipt.category' : 'receipt.note'
        def binding = binding(1, 'note', fromStep(path))
        def taken = selected ? new Field(new FieldName('note'), null, null, category.shape(), category.howMany(),
                new Demand.Given(false)) : text('note')
        def asked = questionTaking(2, takes([taken]), [binding])
        def historical = new TryRecord(tryId(21), null, MODEL, null, askedAt(1), null, null, null, null, null,
                null, false, null, [], [], [new InputRecord(binding.id(), valueId(1))], [], [])
        def destination = started(asked, [historical])
        def at = RunInTree.root(run([source, destination]))

        when:
        def failure = StepInputs.sourceFailure(at, destination)

        then:
        failure.present == (reason != null)
        if (reason != null) {
            assert failure.orElseThrow().reason() == reason
            assert failure.orElseThrow().source().field() == path
            assert failure.orElseThrow().source().maker() ==
                    new HoldRecord.SourceRecord(at.run().run(), source.runStep(), source.planned().id().value())
        }
        historical.inputs().first().source() == valueId(1)
        historical.number() == null
        historical.attempts().empty
        historical.calls().empty

        where:
        selected | withValue | required || reason
        true     | true      | false    || RunStepFailureReason.SOURCE_LIST_NOT_HERE
        true     | false     | false    || null
        false    | true      | false    || null
        false    | false     | false    || null
        true     | false     | true     || RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
        true     | true      | true     || RunStepFailureReason.SOURCE_LIST_NOT_HERE
    }

    def "a stored source tuple cannot name another root or another written step or a question as code"() {
        given:
        def source = started(code ? codeStep(1, 1, null) : question(1, model(), 1),
                [yielded(1, code ? StepProducer.CODE : MODEL, [value(1, 'answer', false)])])
        def at = RunInTree.root(run([source]))
        def named = new HoldRecord.SourceRecord(foreignRun ? new RunId(key(9901)) : at.run().run(),
                wrongStep ? new RunStepId(key(9902)) : source.runStep(),
                wrongWorkflowStep ? key(9903) : source.planned().id().value())

        when:
        StepInputs.codeSource(at, named)

        then:
        thrown(IllegalStateException)
        source.tries().size() == 1
        source.tries().first().attempts().empty
        source.tries().first().calls().empty

        where:
        code  | foreignRun | wrongStep | wrongWorkflowStep
        true  | true       | false     | false
        true  | false      | true      | false
        true  | false      | false     | true
        false | false      | false     | false
    }

    def "a search try reads historical makers in question order and gives literals no saved inputs"() {
        given:
        def name = new SearchQuestionPart.Read(key(1901), fromStep("profile.name"))
        def missing = new SearchQuestionPart.Read(key(1902), fromRun("missing"))
        def inputs = [new InputRecord(null, missing.id(), null, []),
                      new InputRecord(null, name.id(), valueId(1), [])]
        def at = RunInTree.root(ranWith([], inputs))
        def aTry = at.run().steps().last().newest().orElseThrow()

        expect:
        StepInputs.tookSearch(at, [new SearchQuestionPart.LiteralPart("Demo Sample "), name,
                                  new SearchQuestionPart.LiteralPart(" "), missing], aTry) == [
                new StepInputs.ReadRecord(name, json("Ari"), valueId(1), [valueId(1)], []),
                new StepInputs.ReadRecord(missing, json(null), null, [], [])]
        StepInputs.bound(at, name).orElseThrow().value() == json("Bea")
        StepInputs.tookSearch(at, [new SearchQuestionPart.LiteralPart("Demo Sample")],
                ranWith([], []).steps().last().newest().orElseThrow()).empty
    }

    def "a search try rejects missing duplicate binding and unplanned saved inputs"() {
        given:
        def part = new SearchQuestionPart.Read(key(1901), fromStep("profile.name"))
        def saved = keys.collect { identity ->
            new InputRecord(identity == "binding" ? key(1901) : null,
                    identity == "binding" ? null : key(identity), valueId(1), [])
        }
        def at = RunInTree.root(ranWith([], saved))
        def aTry = at.run().steps().last().newest().orElseThrow()

        when:
        StepInputs.tookSearch(at, [new SearchQuestionPart.LiteralPart("Demo Sample "), part], aTry)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == message
        aTry.values().empty
        aTry.calls().empty

        where:
        keys         || message
        []           || "A search try has no input for its read part"
        [1901, 1901] || "A search try holds distinct search question part inputs"
        ["binding"]  || "A search try holds distinct search question part inputs"
        [1902]       || "A search try has no input for its read part"
        [1901, 1902] || "A search try holds an unplanned input"
    }

    def "a search read keeps its saved maker separate from the value standing now"() {
        given:
        def part = new SearchQuestionPart.Read(key(1901), fromStep("profile.name"))
        def at = RunInTree.root(ranWith([], []))
        def input = new InputRecord(null, part.id(), valueId(1), [])

        expect:
        StepInputs.bound(at, part) == Optional.of(new StepInputs.ReadRecord(part, json("Bea"),
                valueId(4), [valueId(4)], []))
        StepInputs.took(at, part, input) == new StepInputs.ReadRecord(part, json("Ari"),
                valueId(1), [valueId(1)], [])
        input.binding() == null
    }

    def "a search read rejects a saved binding identity or another question part"() {
        given:
        def part = new SearchQuestionPart.Read(key(1901), fromStep("profile.name"))
        def input = new InputRecord(binding, questionPart, valueId(1), [])

        when:
        StepInputs.took(RunInTree.root(ranWith([], [])), part, input)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Search question part " + part.id() + " does not own its saved input"

        where:
        binding   | questionPart
        key(1901) | null
        null      | key(1902)
    }

    def "a saved search read cannot claim a missing maker or a different source field"() {
        given:
        def part = new SearchQuestionPart.Read(key(1901), fromStep(pointer))

        when:
        StepInputs.took(RunInTree.root(ranWith([], [])), part,
                new InputRecord(null, part.id(), maker, []))

        then:
        thrown(IllegalStateException)

        where:
        pointer        | maker
        "profile.name" | valueId(99)
        "other"        | valueId(1)
        "profile.name" | null
    }

    def "a live search read waits for its actual source to stand"() {
        expect:
        StepInputs.bound(RunInTree.root(runWith(sourceWith(source), [])),
                new SearchQuestionPart.Read(key(1901), fromStep("summary"))) == Optional.empty()

        where:
        source << SOURCES_NOT_STANDING
    }

    def "a search read of an absent optional workflow input keeps no invented maker"() {
        given:
        def part = new SearchQuestionPart.Read(key(1901), fromRun("missing"))
        def at = RunInTree.root(runWith(standingSource(), []))
        def expected = new StepInputs.ReadRecord(part, json(null), null, [], [])

        expect:
        StepInputs.bound(at, part) == Optional.of(expected)
        StepInputs.took(at, part, new InputRecord(null, part.id(), null, [])) == expected

        when:
        StepInputs.took(at, part, new InputRecord(null, part.id(), valueId(1), []))

        then:
        thrown(IllegalStateException)
    }

    def "a taken input identifies its binding or question part without losing component makers"() {
        given:
        def makers = [new InputRecord.Part([new FieldName("street")], valueId(3))]

        when:
        def input = new InputRecord(binding, questionPart, null, makers)

        then:
        input.binding() == binding
        input.searchQuestionPart() == questionPart
        input.source() == null
        input.parts() == makers

        where:
        binding  | questionPart
        key(801) | null
        null     | key(901)
    }

    def "a taken input with neither or both origin keys cannot be recorded"() {
        given:
        InputRecord recorded = null

        when:
        recorded = new InputRecord(binding, questionPart, valueId(3), [])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A taken input names exactly one binding or search question part"
        recorded == null

        where:
        binding  | questionPart
        null     | null
        key(801) | key(901)
    }

    def "a direct maker cannot coexist with component makers for either origin"() {
        given:
        InputRecord recorded = null
        def makers = [new InputRecord.Part([new FieldName("street")], valueId(3))]

        when:
        recorded = new InputRecord(binding, questionPart, valueId(2), makers)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "One direct value has no component sources"
        recorded == null

        where:
        binding  | questionPart
        key(801) | null
        null     | key(901)
    }

    def "what a step takes traces each binding in the order written, to the value a step made only where one did"() {
        given:
        def bindings = [
                binding(1, "contacts", fromStep("people")),
                binding(2, "title", fromRun("subject")),
                binding(3, "address.street", written("Kept as written")),
        ]
        def running = runWith(standingSource(), bindings)

        expect:
        StepInputs.traced(RunInTree.root(running), running.steps()[1], TAKES) == Optional.of([
                new InputRecord(key(801), valueId(3)),
                new InputRecord(key(802), null),
                new InputRecord(key(803), null),
        ])
    }

    def "what a step takes is not there yet while a step it reads from has no value standing"() {
        given:
        def running = runWith(sourceWith(source), [binding(1, "title", fromStep("summary"))])

        expect:
        StepInputs.traced(RunInTree.root(running), running.steps()[1], TAKES) == Optional.empty()

        where:
        source << SOURCES_NOT_STANDING
    }

    def "a binding filling nothing the step takes is refused as what no approved version could hold"() {
        given:
        def running = runWith(standingSource(), [binding(1, "note", written("Fine")), binding(2, target, written("Stray"))])

        when:
        StepInputs.traced(RunInTree.root(running), running.steps()[1], TAKES)

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
        StepInputs.traced(RunInTree.root(running), running.steps()[1], TAKES)

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
        StepInputs.traced(RunInTree.root(running), running.steps()[1], TAKES)

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
        def took = StepInputs.took(RunInTree.root(running), running.steps()[1], running.steps()[1].tries()[0])

        then:
        took == [
                new StepInputs.BindingRecord(bindings[0], json("Ari"), valueId(1), [valueId(1)]),
                new StepInputs.BindingRecord(bindings[1], json("Kept as written"), null, []),
                new StepInputs.BindingRecord(bindings[2], json("Ledger"), null, []),
        ]

        and: "never the value standing now"
        !took*.value().contains(json("Bea"))
    }

    def "a saved try missing a planned input is refused"() {
        given:
        def bindings = [binding(1, "note", written("Unread")), binding(2, "title", written("Kept as written"))]
        def running = ranWith(bindings, [new InputRecord(key(802), null)])

        when:
        StepInputs.took(RunInTree.root(running), running.steps()[1], running.steps()[1].tries()[0])

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Try " + running.steps()[1].tries()[0].id().value() + " has no input for binding " + key(801)
    }

    def "a try that took a value its step does not hold is refused, naming the binding"() {
        given:
        def running = ranWith([binding(1, "note", fromStep("profile.name"))], [new InputRecord(key(801), valueId(99))])

        when:
        StepInputs.took(RunInTree.root(running), running.steps()[1], running.steps()[1].tries()[0])

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(801) + " took a value its step does not hold"
    }

    def "each binding of a step is read in the order written"() {
        given:
        def bindings = [binding(1, "note", fromStep("summary")), binding(2, "title", written("Kept as written"))]
        def running = runWith(standingSource(), bindings)

        expect:
        StepInputs.bound(RunInTree.root(running), running.steps()[1]) == Optional.of([
                new StepInputs.BindingRecord(bindings[0], json("Short"), valueId(2), [valueId(2)]),
                new StepInputs.BindingRecord(bindings[1], json("Kept as written"), null, []),
        ])
    }

    def "no binding of a step is read while one of them reads a step with no value standing, whatever the others read"() {
        given:
        def bindings = [binding(1, "title", written("Kept as written")), binding(2, "note", fromStep("summary"))]
        def running = runWith(sourceWith("an open try"), bindings)

        expect:
        StepInputs.bound(RunInTree.root(running), running.steps()[1]) == Optional.empty()
    }

    def "a constant is read as written"() {
        given:
        def constant = binding(1, "note", written([kept: "as written"]))

        expect:
        StepInputs.bound(RunInTree.root(runWith(standingSource(), [])), constant) ==
                Optional.of(new StepInputs.BindingRecord(constant, json([kept: "as written"]), null, []))
    }

    def "what a run was started with is read at the pointer, none where nothing is there"() {
        given:
        def input = binding(1, "note", fromRun(pointer))

        expect:
        StepInputs.bound(RunInTree.root(runWith(standingSource(), [])), input) ==
                Optional.of(new StepInputs.BindingRecord(input, json(read), null, []))

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
        StepInputs.bound(RunInTree.root(runWith(standingSource(), [], null)), input)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run " + RUN.value() + " beneath another reads what no run above bound into it"
    }

    def "a step's value is read from its newest try at the rest of the pointer, beside the value it came from"() {
        given:
        def output = binding(1, "note", fromStep(pointer))

        expect:
        StepInputs.bound(RunInTree.root(runWith(standingSource(), [])), output) ==
                Optional.of(new StepInputs.BindingRecord(output, json(read), valueId(from), [valueId(from)]))

        where:
        pointer           || read                        | from
        "summary"         || "Short"                     | 2
        "profile"         || [name: "Ari", tags: ["a"]]  | 1
        "profile.name"    || "Ari"                       | 1
        "profile.missing" || null                        | 1
        "summary.inner"   || null                        | 2
    }

    def "a step's value is not read while its newest try has no value standing"() {
        expect:
        StepInputs.bound(RunInTree.root(runWith(sourceWith(source), [])), binding(1, "note", fromStep("summary"))) == Optional.empty()

        where:
        source << SOURCES_NOT_STANDING
    }

    def "a step's value is refused where the step is not the version's or gave nothing back for the field"() {
        given:
        def output = binding(1, "note", new BindingSource.StepOutput(key(step), Pointer.parse(field)))

        when:
        StepInputs.bound(RunInTree.root(runWith(standingSource(), [])), output)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + key(801) + message

        where:
        step || field     | message
        999  || "summary" | " reads a step its version does not hold"
        401  || "unknown" | " reads a field its step gave nothing back for"
    }

    def "a child reads its workflow input through the parent step while keeping the producing value identity"() {
        given:
        def childBinding = binding(31, "value", fromRun("subject"))
        def childStep = unstarted(questionTaking(1, takes([text("value")]), [childBinding]))
        def child = beneathRun(1101, [childStep], takes([text("subject")]), gives([]), [])
        def parentStep = beneath(workflowWith(2, [binding(32, "subject", fromStep("summary"))]), child)
        def parent = run([standingSource(), parentStep])
        def at = RunInTree.root(parent).beneath(parentStep)

        when:
        def read = StepInputs.bound(at, childStep)

        then:
        read == Optional.of([new StepInputs.BindingRecord(childBinding, json("Short"), valueId(2), [valueId(2)])])
        StepInputs.traced(at, childStep, takes([text("value")])) ==
                Optional.of([new InputRecord(childBinding.id(), valueId(2))])
        child.startedWith() == null
    }

    def "a route child reads the chosen case's mapping rather than the route step's ordinary bindings"() {
        given:
        def childBinding = binding(41, "value", fromRun("subject"))
        def childStep = unstarted(questionTaking(1, takes([text("value")]), [childBinding]))
        def child = beneathRun(1102, [childStep], takes([text("subject")]), gives([]), [])
        def chosen = new StepRuns.Route.Case(key(9041), "case", pinned(2),
                [binding(42, "subject", fromStep("summary"))])
        def route = new StepRuns.Route(binding(43, null, fromStep("summary")), gives([]), [chosen])
        def planned = new PlannedStep(stepId(2), 2, new StepId("step_2"), route, null, null, null, [])
        def parentStep = beneath(planned, child, chosen.id())
        def parent = run([standingSource(), parentStep])

        expect:
        StepInputs.bound(RunInTree.root(parent).beneath(parentStep), childStep) ==
                Optional.of([new StepInputs.BindingRecord(childBinding, json("Short"), valueId(2), [valueId(2)])])
        parentStep.planned().bindings().isEmpty()
        child.startedWith() == null
    }

    def "a parent follows two workflow outputs to a grandchild and a historical try keeps its earlier value"() {
        given:
        def grandchildPlan = question(1, model(), 2)
        def grandchildSource = started(grandchildPlan, [
                yielded(1, MODEL, [value(11, "answer", false, "Earlier")]),
                yielded(2, MODEL, [value(12, "answer", false, "Current")])])
        def grandchildOutput = binding(51, "answer", fromStep("answer"))
        def grandchild = beneathRun(1111, [grandchildSource], takes([]), gives([standing("answer")]), [grandchildOutput])
        def childStep = beneath(workflowWith(1, []), grandchild)
        def childOutput = binding(52, "answer", fromStep("answer"))
        def child = beneathRun(1112, [childStep], takes([]), gives([standing("answer")]), [childOutput])
        def parentStep = beneath(workflowWith(1, []), child)
        def consumerBinding = new Binding(key(853), Pointer.parse("value"),
                new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer")))
        def consumerPlan = questionTaking(2, takes([text("value")]), [consumerBinding])
        def historical = new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(consumerBinding.id(), valueId(11))], [], [])
        def consumer = started(consumerPlan, [historical])
        def parent = run([parentStep, consumer])
        def at = RunInTree.root(parent)

        when:
        def live = StepInputs.bound(at, consumer)
        def taken = StepInputs.took(at, consumer, historical)

        then:
        live == Optional.of([new StepInputs.BindingRecord(consumerBinding, json("Current"), valueId(12), [valueId(12)])])
        taken == [new StepInputs.BindingRecord(consumerBinding, json("Earlier"), valueId(11), [valueId(11)])]
        !taken*.value().contains(json("Current"))
        parentStep.tries().isEmpty()
        childStep.tries().isEmpty()
        def readPart = new SearchQuestionPart.Read(key(1903), consumerBinding.source())
        StepInputs.bound(at, readPart).get().value() == json("Current")
        StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), valueId(11), [])).value() == json("Earlier")
    }

    def "a grandchild file bound through an ancestor keeps the root run as its trusted source"() {
        given:
        def stored = new StoredFile(key(990), new FileName("Demo Sample.txt"), new MediaTypeName("text/plain"), 8)
        def fileTakes = takes([fileField("attachment")])
        def grandchild = beneathRun(1131, [], fileTakes, gives([]), [])
        def middleStep = beneath(workflowWith(1, [binding(71, "attachment", fromRun("attachment"))]), grandchild)
        def middle = beneathRun(1132, [middleStep], fileTakes, gives([]), [])
        def rootStep = beneath(workflowWith(1, [binding(72, "attachment", fromRun("attachment"))]), middle)
        def root = run([rootStep], false, null, [attachment: stored.kept()])
        def at = RunInTree.root(root).beneath(rootStep).beneath(middleStep)
        def path = new FillPath([new FillPath.Named(new FieldName("attachment"))])

        expect:
        StepInputs.workflowInput(at).values() == json([attachment: stored.kept()])
        StepInputs.workflowInput(at).files() == [(path): new StepInputs.FileSource(RUN, stored.id())]
    }

    def "a file made by an earlier child names that child as the next child's trusted source"() {
        given:
        def stored = new StoredFile(key(991), new FileName("Demo Sample.txt"), new MediaTypeName("text/plain"), 8)
        def source = started(codeStep(1, 1, tidy(false, [], [standingFileField("attachment")])),
                [yielded(1, StepProducer.CODE, [value(71, "attachment", false, stored.kept())])])
        def firstChild = beneathRun(1133, [source], takes([]), gives([standingFileField("attachment")]),
                [binding(73, "attachment", fromStep("attachment"))])
        def firstStep = beneath(workflowWith(1, []), firstChild)
        def fileTakes = takes([fileField("attachment")])
        def secondChild = beneathRun(1134, [], fileTakes, gives([]), [])
        def secondStep = beneath(workflowWith(2, [new Binding(key(874), Pointer.parse("attachment"),
                new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("attachment")))]), secondChild)
        def at = RunInTree.root(run([firstStep, secondStep])).beneath(secondStep)
        def path = new FillPath([new FillPath.Named(new FieldName("attachment"))])

        expect:
        StepInputs.workflowInput(at).values() == json([attachment: stored.kept()])
        StepInputs.workflowInput(at).files() == [(path): new StepInputs.FileSource(firstChild.run(), stored.id())]
    }

    def "a saved direct input without its maker cannot drift to a replacement value"() {
        given:
        def source = started(question(1, model(), 2), [
                yielded(1, MODEL, [value(11, "answer", false, "Earlier")]),
                yielded(2, MODEL, [value(12, "answer", false, "Current")])])
        def output = binding(51, "answer", fromStep("answer"))
        def child = beneathRun(1111, [source], takes([]), gives([standing("answer")]), [output])
        def parentStep = beneath(workflowWith(1, []), child)
        def binding = new Binding(key(853), Pointer.parse("value"),
                new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer")))
        def consumer = started(questionTaking(2, takes([text("value")]), [binding]), [
                new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                        null, null, false, null, [], [], [new InputRecord(binding.id(), null)], [], [])])
        def parent = run([parentStep, consumer])

        when:
        StepInputs.took(RunInTree.root(parent), consumer, consumer.tries()[0])

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Binding " + binding.id() + " took a value its source does not hold"
        StepInputs.bound(RunInTree.root(parent), consumer).get()[0].value() == json("Current")
    }

    def "a parent reads a child output as soon as its ultimate value stands, and waits when it does not"() {
        given:
        def childStep = unstarted(question(1, model(), 1))
        def childOutput = new Binding(key(870), Pointer.parse("answer"), outputSource)
        def child = beneathRun(1113, [childStep], takes([]), gives([standing("answer")]), [childOutput])
        def parentStep = beneath(workflowWith(1, []), child)
        def consumerBinding = new Binding(key(871), Pointer.parse("value"),
                new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer")))
        def consumer = unstarted(questionTaking(2, takes([text("value")]), [consumerBinding]))
        def parent = run([parentStep, consumer])

        when:
        def read = StepInputs.bound(RunInTree.root(parent), consumer)

        then:
        read == expected.map { value ->
            [new StepInputs.BindingRecord(consumerBinding, json(value), null, [])]
        }
        parentStep.tries().isEmpty()
        child.startedWith() == null

        where:
        outputSource                                    || expected
        new BindingSource.Written(json("Already"))      || Optional.of("Already")
        new BindingSource.StepOutput(stepId(1).value(),
                Pointer.parse("answer"))                || Optional.empty()
    }

    def "a composite child input keeps each historical source after both are replaced"() {
        given:
        def first = started(question(1, model(), 2), [
                yielded(1, MODEL, [value(31, "answer", false, "Ari")]),
                yielded(2, MODEL, [value(33, "answer", false, "Bea")])])
        def second = started(question(2, model(), 2), [
                yielded(1, MODEL, [value(32, "answer", false, "123")]),
                yielded(2, MODEL, [value(34, "answer", false, "456")])])
        def childBinding = binding(61, "customer", fromRun("customer"))
        def childTakes = takes([nested("customer", [text("name"), text("id"), text("region")])])
        def sourceParts = [
                new InputRecord.Part(Pointer.parse("name").names(), valueId(31)),
                new InputRecord.Part(Pointer.parse("id").names(), valueId(32)),
                new InputRecord.Part(Pointer.parse("region").names(), null)]
        def historical = new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(childBinding.id(), null, sourceParts)], [], [])
        def childStep = started(questionTaking(1, childTakes, [childBinding]), [historical])
        def child = beneathRun(1121, [childStep], childTakes, gives([]), [])
        def parentStep = beneath(workflowWith(3, [
                new Binding(key(862), Pointer.parse("customer.name"),
                        new BindingSource.StepOutput(stepId(1).value(), Pointer.parse("answer"))),
                new Binding(key(863), Pointer.parse("customer.id"),
                        new BindingSource.StepOutput(stepId(2).value(), Pointer.parse("answer"))),
                new Binding(key(864), Pointer.parse("customer.region"), written("Demo Sample")),
        ]), child)
        def parent = run([first, second, parentStep])
        def at = RunInTree.root(parent).beneath(parentStep)

        when:
        def live = StepInputs.bound(at, childStep)
        def taken = StepInputs.took(at, childStep, historical)
        def traced = StepInputs.traced(at, childStep, childTakes)

        then:
        live == Optional.of([new StepInputs.BindingRecord(childBinding, json([name: "Bea", id: "456", region: "Demo Sample"]), null,
                [valueId(33), valueId(34)], [
                        new InputRecord.Part(Pointer.parse("name").names(), valueId(33)),
                        new InputRecord.Part(Pointer.parse("id").names(), valueId(34)),
                        new InputRecord.Part(Pointer.parse("region").names(), null)])])
        taken == [new StepInputs.BindingRecord(childBinding, json([name: "Ari", id: "123", region: "Demo Sample"]), null,
                [valueId(31), valueId(32)], sourceParts)]
        traced.get()[0].parts()*.source() == [valueId(33), valueId(34), null]
        !taken*.value().contains(json([name: "Bea", id: "456", region: "Demo Sample"]))
        child.startedWith() == null

        and: "a search read follows the same composite sources without a binding identity"
        def readPart = new SearchQuestionPart.Read(key(1904), childBinding.source())
        def readInput = new InputRecord(null, readPart.id(), null, sourceParts)
        StepInputs.bound(at, readPart).get().value() == json([name: "Bea", id: "456", region: "Demo Sample"])
        StepInputs.took(at, readPart, readInput) == new StepInputs.ReadRecord(readPart,
                json([name: "Ari", id: "123", region: "Demo Sample"]), null, [valueId(31), valueId(32)], sourceParts)

        when: "an old saved component omits its producing source"
        def missingSource = [new InputRecord.Part(Pointer.parse("name").names(), null),
                             sourceParts[1], sourceParts[2]]
        def uncertain = new TryRecord(tryId(25), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [],
                [new InputRecord(childBinding.id(), null, missingSource)], [], [])
        StepInputs.took(at, childStep, uncertain)

        then:
        thrown(StepInputs.HistoricalSourceUnclear)
        StepInputs.tookForReading(at, childStep, uncertain) == [Optional.empty()]
        StepInputs.tookSearchForReading(at, readPart,
                new InputRecord(null, readPart.id(), null, missingSource)) == Optional.empty()

        when: "a component names a value made by the other source step"
        def wrongMaker = new TryRecord(tryId(23), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(childBinding.id(), null, [
                        new InputRecord.Part(Pointer.parse("name").names(), valueId(32)),
                        new InputRecord.Part(Pointer.parse("id").names(), valueId(31)),
                        new InputRecord.Part(Pointer.parse("region").names(), null)])], [], [])
        StepInputs.took(at, childStep, wrongMaker)

        then:
        thrown(IllegalStateException)

        when: "a search read claims an unowned composite path"
        StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), null, sourceParts + [
                new InputRecord.Part(Pointer.parse("other").names(), null)]))

        then:
        thrown(IllegalStateException)

        when: "a search read names the other component's maker"
        StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), null, wrongMaker.inputs()[0].parts()))

        then:
        thrown(IllegalStateException)

        when: "a search read omits a component the actual chain supplies"
        StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), null, sourceParts.subList(0, 2)))

        then:
        thrown(IllegalStateException)

        when: "a stored path claims a component that the binding chain never filled"
        def extraPath = new TryRecord(tryId(24), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(childBinding.id(), null, sourceParts + [
                        new InputRecord.Part(Pointer.parse("other").names(), null)])], [], [])
        StepInputs.took(at, childStep, extraPath)

        then:
        thrown(IllegalStateException)
    }

    def "a saved composite with an unknown producing source stays hidden when that source no longer stands"() {
        given:
        def producer = model()
        def madeBy = codeSource ? StepProducer.CODE : MODEL
        def answer = new Field(new FieldName("answer"), null, null, new FieldShape.Text(200),
                new HowMany.One(), new Demand.Stands(true, FieldStanding.NEVER, null))
        def planned = codeSource ? codeStep(1, 2, tidy(true, [], [answer])) :
                new PlannedStep(stepId(1), 1, new StepId("step_1"),
                        asking(1, takes([]), gives([answer])), producer, 2, null, [])
        def latest = waitingForReview
                ? yielded(2, madeBy, [value(33, "answer", true, "Demo Sample private")])
                : open(2, madeBy)
        def oldValue = value(31, "answer", true, "Demo Sample old")
        def source = started(planned, [yielded(1, madeBy, [oldValue],
                [personReview(minutes(16), [assured(oldValue)])]), latest])
        def childBinding = binding(61, "customer", fromRun("customer"))
        def childTakes = takes([nested("customer", [text("name"), text("region")])])
        def saved = [new InputRecord.Part(Pointer.parse("name").names(), null),
                     new InputRecord.Part(Pointer.parse("region").names(), null)]
        def historical = new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(childBinding.id(), null, saved)], [], [])
        def childStep = started(questionTaking(1, childTakes, [childBinding]), [historical])
        def child = beneathRun(1125, [childStep], childTakes, gives([]), [])
        def parentBindings = [
                new Binding(key(870), Pointer.parse("customer.name"), fromStep("answer")),
                new Binding(key(871), Pointer.parse("customer.region"),
                        rootInput ? fromRun("region") : written("Demo Sample"))]
        def parentStep = beneath(workflowWith(2, reverseComponents ? parentBindings.reverse() : parentBindings), child)
        def at = RunInTree.root(run([source, parentStep], false, null, [region: null])).beneath(parentStep)
        def read = new SearchQuestionPart.Read(key(1907), childBinding.source())

        expect:
        def known = [new InputRecord.Part(Pointer.parse("name").names(), valueId(31)), saved[1]]
        StepInputs.took(at, childBinding, new InputRecord(childBinding.id(), null, known)).value() ==
                json([name: "Demo Sample old", region: rootInput ? null : "Demo Sample"])
        StepInputs.took(at, read, new InputRecord(null, read.id(), null, known)).value() ==
                json([name: "Demo Sample old", region: rootInput ? null : "Demo Sample"])
        StepInputs.tookForReading(at, childStep, historical) == [Optional.empty()]
        StepInputs.tookSearchForReading(at, read, new InputRecord(null, read.id(), null, saved)) == Optional.empty()

        when:
        StepInputs.took(at, childStep, historical)

        then:
        thrown(StepInputs.HistoricalSourceUnclear)

        when: "a missing producer does not conceal a known value falsely assigned to a constant"
        def wrong = [saved[0], new InputRecord.Part(Pointer.parse("region").names(), valueId(31))]
        def wrongTry = new TryRecord(tryId(22), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(childBinding.id(), null, wrong)], [], [])
        StepInputs.tookForReading(at, childStep, wrongTry)

        then:
        def wrongError = thrown(IllegalStateException)
        !(wrongError instanceof StepInputs.HistoricalSourceUnclear)

        when:
        StepInputs.tookSearchForReading(at, read, new InputRecord(null, read.id(), null, wrong))

        then:
        def wrongReadError = thrown(IllegalStateException)
        !(wrongReadError instanceof StepInputs.HistoricalSourceUnclear)

        when: "an unknown source does not conceal an extra path"
        StepInputs.tookSearchForReading(at, read, new InputRecord(null, read.id(), null,
                saved + [new InputRecord.Part(Pointer.parse("other").names(), null)]))

        then:
        def extraError = thrown(IllegalStateException)
        !(extraError instanceof StepInputs.HistoricalSourceUnclear)

        when: "an unknown source does not conceal a duplicate path"
        StepInputs.tookSearchForReading(at, read, new InputRecord(null, read.id(), null, saved + [saved[0]]))

        then:
        def repeatedError = thrown(IllegalStateException)
        !(repeatedError instanceof StepInputs.HistoricalSourceUnclear)

        when: "an unknown source does not conceal a missing component"
        StepInputs.tookSearchForReading(at, read, new InputRecord(null, read.id(), null, [saved[0]]))

        then:
        def missingError = thrown(IllegalStateException)
        !(missingError instanceof StepInputs.HistoricalSourceUnclear)

        where:
        codeSource | waitingForReview | reverseComponents | rootInput
        false      | false            | false             | false
        false      | true             | false             | false
        true       | false            | false             | false
        true       | true             | false             | false
        false      | false            | true              | false
        false      | true             | true              | false
        true       | false            | true              | false
        true       | true             | true              | false
        false      | false            | false             | true
        false      | true             | true              | true
        true       | false            | true              | true
        true       | true             | false             | true
    }

    def "two fields from one maker keep both paths and the earlier value when the maker is replaced"() {
        given:
        def source = started(question(1, model(), 2), [
                yielded(1, MODEL, [value(41, "profile", false, [name: "Ari", id: "123"])]),
                yielded(2, MODEL, [value(42, "profile", false, [name: "Bea", id: "456"])])])
        def childBinding = binding(65, "customer", fromRun("customer"))
        def childTakes = takes([nested("customer", [text("name"), text("id")])])
        def oldParts = [new InputRecord.Part(Pointer.parse("name").names(), valueId(41)),
                        new InputRecord.Part(Pointer.parse("id").names(), valueId(41))]
        def historical = new TryRecord(tryId(22), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(childBinding.id(), null, oldParts)], [], [])
        def childStep = started(questionTaking(1, childTakes, [childBinding]), [historical])
        def child = beneathRun(1122, [childStep], childTakes, gives([]), [])
        def parentStep = beneath(workflowWith(2, [
                new Binding(key(865), Pointer.parse("customer.name"), fromStep("profile.name")),
                new Binding(key(866), Pointer.parse("customer.id"), fromStep("profile.id"))]), child)
        def at = RunInTree.root(run([source, parentStep])).beneath(parentStep)

        expect:
        StepInputs.bound(at, childStep).get()[0].parts()*.source() == [valueId(42), valueId(42)]
        StepInputs.took(at, childStep, historical)[0].value() == json([name: "Ari", id: "123"])
        StepInputs.took(at, childStep, historical)[0].parts() == oldParts
        def readPart = new SearchQuestionPart.Read(key(1906), childBinding.source())
        def read = StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), null, oldParts))
        read.value() == json([name: "Ari", id: "123"])
        read.parts() == oldParts
        read.sources() == [valueId(41)]
    }

    def "a repeated nested field name stays a component, not a direct historical source"() {
        given:
        def source = started(question(1, model(), 2), [
                yielded(1, MODEL, [value(51, "answer", false, "Ari")]),
                yielded(2, MODEL, [value(52, "answer", false, "Bea")])])
        def shape = takes([nested("root", [nested("customer", [text("customer")])])])
        def leafBinding = binding(67, "root", fromRun("root"))
        def saved = [new InputRecord.Part(Pointer.parse("customer.customer").names(), valueId(51))]
        def historical = new TryRecord(tryId(25), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null,
                null, null, false, null, [], [], [new InputRecord(leafBinding.id(), null, saved)], [], [])
        def leafStep = started(questionTaking(1, shape, [leafBinding]), [historical])
        def leaf = beneathRun(1123, [leafStep], shape, gives([]), [])
        def middleStep = beneath(workflowWith(1, [new Binding(key(867), Pointer.parse("root.customer"),
                fromRun("root.customer"))]), leaf)
        def middle = beneathRun(1124, [middleStep], shape, gives([]), [])
        def rootStep = beneath(workflowWith(2, [new Binding(key(868), Pointer.parse("root.customer.customer"),
                fromStep("answer"))]), middle)
        def at = RunInTree.root(run([source, rootStep])).beneath(rootStep).beneath(middleStep)

        expect:
        StepInputs.bound(at, leafStep).get()[0].value() == json([customer: [customer: "Bea"]])
        StepInputs.took(at, leafStep, historical)[0].value() == json([customer: [customer: "Ari"]])
        StepInputs.took(at, leafStep, historical)[0].parts() == saved
        def readPart = new SearchQuestionPart.Read(key(1905), leafBinding.source())
        StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), null, saved)).value() ==
                json([customer: [customer: "Ari"]])
        StepInputs.took(at, readPart, new InputRecord(null, readPart.id(), null, saved)).parts() == saved
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
                value(1, "profile", false, [name: "Ari", tags: ["a"]]),
                value(2, "summary", false, "Short"),
                value(3, "people", false, [[name: "Ari"], "loose"]),
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


    private static RunSnapshot ranWith(List<Binding> bindings, List<InputRecord> taken) {
        def source = started(SOURCE, [
                yielded(1, MODEL, [value(1, "profile", false, [name: "Ari"])]),
                yielded(2, MODEL, [value(4, "profile", false, [name: "Bea"])])])
        def aTry = new TryRecord(tryId(21), 1, StepProducer.PERSON, null, askedAt(1), null, null, null, null, null,
                null, false, null, [], [], taken, [], [])
        run([source, started(questionTaking(2, TAKES, bindings), [aTry])], false, null, STARTED_WITH)
    }


    private static List<StepInputs.BindingRecord> bindings(Map bound) {
        (bound.keySet() as List<String>).withIndex().collect { target, index ->
            new StepInputs.BindingRecord(binding(index + 1, target, written(bound[target])), json(bound[target]), null, [])
        }
    }


    private static List<StepInputs.BindingRecord> aimed(List<String> targets) {
        targets.withIndex().collect { target, index ->
            new StepInputs.BindingRecord(binding(index + 1, target, written("x")), json("x"), null, [])
        }
    }

    private static RunSnapshot beneathRun(
            int number, List<StepSnapshot> steps, Declaration takes, Declaration gives, List<Binding> outputs) {
        def template = run(steps)
        def workflow = new RunnableWorkflow(takes, gives, [:], outputs, steps*.planned())
        new RunSnapshot(new RunId(key(number)), template.root(), template.group(), template.version(), false, null,
                null, workflow, steps)
    }

    private static PlannedStep workflowWith(int order, List<Binding> bindings) {
        def original = workflowStep(order)
        new PlannedStep(original.id(), order, original.name(), original.runs(), null, null, null, bindings)
    }

    private static StepSnapshot beneath(PlannedStep planned, RunSnapshot child, UUID routeCase = null) {
        new StepSnapshot(planned, null, new RunStepId(key(1200 + planned.order())), null, [], [],
                new StepSnapshot.Beneath(child, routeCase), [:])
    }

    private static Binding binding(int number, String target, BindingSource source) {
        new Binding(key(800 + number), target == null ? null : Pointer.parse(target), source)
    }

    private static Field fileField(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.File([new MediaTypeName("text/plain")], 100L),
                new HowMany.One(), new Demand.Given(false))
    }

    private static Field standingFileField(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.File([new MediaTypeName("text/plain")], 100L),
                new HowMany.One(), new Demand.Stands(true, FieldStanding.ALWAYS, null))
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
