package com.lilradish.lite.app.run

import static com.lilradish.lite.domain.run.fixture.Runs.json
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.valueId

import com.lilradish.lite.domain.model.SentText
import com.lilradish.lite.domain.run.InputRecord
import com.lilradish.lite.domain.run.StepInputs
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import spock.lang.Specification

class StepLengthDetailsIntegrationSpec extends Specification {

    def "one producer contributes every actual spelling at its different positions"() {
        given:
        def first = input(1, "first", 'A"\n😀', valueId(1))
        def second = input(2, "second", 0, valueId(1))
        def constant = input(3, "constant", null, null)
        def payload = json([takes: [first: 'A"\n😀', second: 0, constant: null, unbound: null], instruction: "Keep it brief."])

        when:
        def measured = StepLengthDetails.question("System words.", payload, [first, second, constant])

        then:
        measured.sent().user() == CanonicalJson.write(payload)
        measured.sent().characters() == SentText.measure("System words.", CanonicalJson.write(payload)).characters()
        measured.rows()*.characters() == [9L, 4L]
        measured.rows()[0].source() == valueId(1)
        measured.rows()[0].occurrences()*.characters() == [8L, 1L]
        measured.rows()[0].occurrences().collect { it.position()*.value() } == [["first"], ["second"]]
        measured.rows()[1].source() == null
        measured.fixedCharacters() == measured.sent().characters() - 13L
        !measured.rows().any { it.occurrences().any { occurrence -> occurrence.position()*.value() == ["unbound"] } }
    }

    def "composite parts count completed nested lists without counting their outer wrapper twice"() {
        given:
        def parts = [new InputRecord.Part(Pointer.parse("names").names(), valueId(1)),
                     new InputRecord.Part(Pointer.parse("summary").names(), valueId(2))]
        def kept = [names: [[name: "Ada", optional: null], [name: "Bo", optional: null]], summary: "Ready.", unbound: null]
        def bound = new StepInputs.BindingRecord(
                new Binding(key(1), Pointer.parse("package"), new BindingSource.WorkflowInput(Pointer.parse("package"))),
                json(kept), null, [valueId(1), valueId(2)], parts)
        def payload = json([takes: [package: kept]])
        def listSpelling = '[{"name":"Ada","optional":null},{"name":"Bo","optional":null}]'

        when:
        def measured = StepLengthDetails.question("", payload, [bound])

        then:
        measured.rows()*.source() == [valueId(1), valueId(2)]
        measured.rows()*.characters() == [listSpelling.length() as long, 8L]
        measured.rows()*.occurrences().flatten().collect { it.position()*.value() } == [["package", "names"], ["package", "summary"]]
        measured.fixedCharacters() + measured.rows()*.characters().sum() == measured.sent().characters()
        measured.rows().every { it.occurrences().size() == 1 }
        measured.fixedCharacters() > 4L
    }

    def "equal contributions preserve declaration order while different constants stay separate"() {
        given:
        def bound = [input(1, "first", "a", null), input(2, "second", "b", null)]

        when:
        def measured = StepLengthDetails.question("", json([takes: [first: "a", second: "b"]]), bound)

        then:
        measured.rows()*.characters() == [3L, 3L]
        measured.rows()*.occurrences().flatten()*.input()*.binding()*.id() == [key(1), key(2)]
        measured.rows()*.source() == [null, null]
        measured.rows().size() == 2
    }

    def "a missing or overlapping actual position fails without changing its input records"() {
        given:
        def bound = [input(1, first, [name: "Ada"], valueId(1)), input(2, second, "Ada", valueId(2))]
        def before = List.copyOf(bound)

        when:
        StepLengthDetails.question("", json([takes: [package: [name: "Ada"]]]), bound)

        then:
        def refused = thrown(IllegalStateException)
        refused.message == message
        bound == before

        where:
        first     | second         || message
        "package" | "package.name" || "Input contributions must not overlap"
        "missing" | "package.name" || "The actual sent payload has no field missing"
    }

    private static StepInputs.BindingRecord input(int id, String position, Object value, def source) {
        new StepInputs.BindingRecord(
                new Binding(key(id), Pointer.parse(position), source == null
                        ? new BindingSource.Written(json(value))
                        : new BindingSource.StepOutput(key(100 + id), Pointer.parse("answer"))),
                json(value), source, source == null ? [] : [source], [])
    }
}
