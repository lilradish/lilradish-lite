package org.lilradish.lite.app.library

import org.libprunus.core.error.ApiErrorException
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.workflow.ModelChoice
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import spock.lang.Specification
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class FlowBodySpec extends Specification {

    static final JsonMapper JSON = JsonMapper.builder().build()

    static final String QUESTION = "00000007-0000-4000-8000-000000000101"

    static final String WORKFLOW = "00000007-0000-4000-8000-000000000102"

    static final String STEP_KEY = "0000000c-0000-4000-8000-000000000101"

    static final String CASE_KEY = "0000000d-0000-4000-8000-000000000101"

    static final Map COMPLAINT = [target: "complaint", source: [input: "complaint"]]

    static final Map ASKING = [name: "classify", runs: [kind: "question", version: QUESTION],
                               producer: [kind: "model", model: "general", mode: "research", toldWhatHappened: true],
                               tries: 3, reviewer: [model: "small", mode: null], bindings: [COMPLAINT]]

    static final Map ROUTING = [stepId: STEP_KEY, name: "route",
                                runs: [kind: "route", discriminator: [step: 0, path: "category"],
                                       gives: [[name: "note", label: null, help: null, kind: "text", many: false,
                                                most: null, longest: 100, mustBeGiven: true]],
                                       cases: [[caseId: CASE_KEY, term: "Billing", workflow: WORKFLOW,
                                                bindings: [[target: "complaint", source: [step: 0, path: "summary"]]]],
                                               [term: null, workflow: null, bindings: []]]],
                                bindings: []]

    static final Map CODED = [name: "send", runs: [kind: "code_step", codeStep: "send_reply"],
                              producer: [kind: "code"], tries: 1, reviewer: null,
                              bindings: [[target: "text", source: [constant: '{ "a": [1.25, null, true] }']]]]

    static final Map BENEATH = [name: "escalate", runs: [kind: "workflow", version: WORKFLOW],
                                bindings: [[target: "complaint", source: [constant: '"Kind regards"']]]]

    static final Map UNCHOSEN = [name: "later", runs: null, bindings: []]

    def "every step is read in the order sent, each by the key it was read under, and what fills what it gives back"() {
        when:
        def sent = FlowBody.read(body([ASKING, ROUTING, CODED, BENEATH, UNCHOSEN],
                [[target: "summary", source: [step: 0, path: "summary"]]]))

        then:
        sent.revision() == 7
        sent.steps()*.name() == ["classify", "route", "send", "escalate", "later"].collect { new StepId(it) }
        sent.steps()*.readAs() == [null, UUID.fromString(STEP_KEY), null, null, null]
        sent.outputs() == [new FlowBody.SentBinding(
                Pointer.parse("summary"), new FlowBody.SentSource.Step(0, Pointer.parse("summary")))]
    }

    def "a workflow version of as many steps as one holds is read"() {
        expect:
        FlowBody.read(body(unchosen(FlowBody.MOST_STEPS), [])).steps().size() == FlowBody.MOST_STEPS
    }

    def "a workflow version of one step more than one holds is refused under its own code"() {
        when:
        FlowBody.read(body(unchosen(FlowBody.MOST_STEPS + 1), []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.STEPS_TOO_MANY
    }

    def "a body that is no object of steps and outputs is refused whole"() {
        when:
        FlowBody.read(JSON.valueToTree(sent))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        sent << [[], [revision: 1, steps: []], [revision: 1, steps: [], outputs: [], more: 1],
                 [revision: 1, steps: [:], outputs: []], [revision: 1, steps: [], outputs: [:]],
                 [revision: 1, steps: ["classify"], outputs: []], [revision: 1, steps: [], outputs: [[target: "a"]]]]
    }

    def "a revision is a whole number from one"() {
        when:
        FlowBody.read(JSON.valueToTree([revision: revision, steps: [], outputs: []]))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE
        refused.message == FlowBody.REFUSED

        where:
        revision << [0, -1, 1.5, "1", null, true]
    }

    def "a question's step says who produces, how many tries and who reviews, and what fills it"() {
        when:
        def asking = FlowBody.read(body([ASKING], [])).steps()[0]

        then:
        asking.runs() == new FlowBody.SentRuns.Pinned(EntryKind.QUESTION, new EntryVersionId(UUID.fromString(QUESTION)))
        asking.producer() == new Producer.Model(new ModelChoice(new ModelName("general"), new ModelMode("research")), true)
        asking.tries() == 3
        asking.reviewer() == new ModelChoice(new ModelName("small"), null)
        asking.bindings() == [new FlowBody.SentBinding(
                Pointer.parse("complaint"), new FlowBody.SentSource.Input(Pointer.parse("complaint")))]
    }

    def "a code step's step says who produces, how many tries and who reviews, a constant written as the store keeps it"() {
        when:
        def coded = FlowBody.read(body([CODED], [])).steps()[0]

        then:
        coded.runs() == new FlowBody.SentRuns.Code("send_reply")
        coded.producer() == new Producer.Code()
        coded.tries() == 1
        coded.reviewer() == null
        coded.bindings() == [new FlowBody.SentBinding(
                Pointer.parse("text"), new FlowBody.SentSource.Written('{"a":[1.25,null,true]}'))]
    }

    def "a step running a workflow, or nothing yet, says nothing of producing"() {
        when:
        def steps = FlowBody.read(body([BENEATH, UNCHOSEN], [])).steps()

        then:
        steps[0].runs() == new FlowBody.SentRuns.Pinned(EntryKind.WORKFLOW, new EntryVersionId(UUID.fromString(WORKFLOW)))
        steps[0].bindings()*.source() == [new FlowBody.SentSource.Written('"Kind regards"')]
        steps[1].runs() == new FlowBody.SentRuns.Unchosen()
        steps*.producer() == [null, null]
        steps*.tries() == [null, null]
        steps*.reviewer() == [null, null]
    }

    /** Each object holds exactly the members its kind takes: a member of another kind's is refused, not ignored. */
    def "a step holding a member its kind does not take, or lacking one it does, is refused whole"() {
        when:
        FlowBody.read(body([step], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        step << [
                ASKING.findAll { it.key != "tries" },
                ASKING + [extra: 1],
                CODED.findAll { it.key != "producer" },
                BENEATH + [tries: 1],
                BENEATH + [producer: null],
                BENEATH.findAll { it.key != "bindings" },
                UNCHOSEN + [reviewer: null],
                ROUTING + [producer: null],
                [name: "x", runs: [kind: "question"], producer: null, tries: null, reviewer: null, bindings: []],
                [name: "x", runs: [kind: "entry", version: QUESTION], bindings: []],
                [name: "x", runs: [version: QUESTION], bindings: []],
                [name: "x", runs: "question", bindings: []],
                [name: "x", runs: [kind: "code_step"], producer: null, tries: null, reviewer: null, bindings: []],
                CODED + [runs: [kind: "code_step", codeStep: "Send"]],
        ]
    }

    /** A route's cases are each read with their own bindings, the fallback the one case on no term. */
    def "a route is read with what it chooses by, what it gives back and its cases, each case by the key it was read under"() {
        when:
        def route = FlowBody.read(body([ASKING, ROUTING], [])).steps()[1].runs() as FlowBody.SentRuns.Route

        then:
        route.discriminator() == new FlowBody.SentSource.Step(0, Pointer.parse("category"))
        route.gives().half().side() == DeclarationSide.GIVES
        route.gives().half().fields() == [new Field(new FieldName("note"), null, null, new FieldShape.Text(100),
                new HowMany.One(), new Demand.Given(true))]
        route.cases()*.readAs() == [UUID.fromString(CASE_KEY), null]
        route.cases()*.term() == ["Billing", null]
        route.cases()*.workflow() == [new EntryVersionId(UUID.fromString(WORKFLOW)), null]
        route.cases()*.bindings() == [[new FlowBody.SentBinding(Pointer.parse("complaint"),
                new FlowBody.SentSource.Step(0, Pointer.parse("summary")))], []]
    }

    def "a route chooses by a step or what the workflow takes, never a constant nor itself"() {
        when:
        FlowBody.read(body([ASKING, ROUTING + [runs: ROUTING.runs + [discriminator: chosenBy]]], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        chosenBy << [[constant: "Billing"], [step: 1, path: "category"]]
    }

    def "a route holds at most one fallback, and each case's term is one a list could hold"() {
        when:
        FlowBody.read(body([ASKING, ROUTING + [runs: ROUTING.runs + [cases: cases]]], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        cases << [
                [[term: null, workflow: null, bindings: []], [term: null, workflow: null, bindings: []]],
                [[term: "", workflow: null, bindings: []]],
                [[term: "x" * 129, workflow: null, bindings: []]],
                [[term: "Bill" + Character.toString(0x0A) + "ing", workflow: null, bindings: []]],
                [[term: "Bill" + Character.toString(0x85) + "ing", workflow: null, bindings: []]],
                [[term: "Bill" + Character.toString(0x1F) + "ing", workflow: null, bindings: []]],
                [[term: "Bill" + Character.toString(0x7F) + "ing", workflow: null, bindings: []]],
                [[term: "Bill" + Character.toString(0x9F) + "ing", workflow: null, bindings: []]],
                [[term: 3, workflow: null, bindings: []]],
                [[term: "Billing", bindings: []]],
                [[term: "Billing", workflow: null, bindings: [], extra: 1]],
        ]
    }

    def "two cases of one route on the same term are refused under their own code"() {
        when:
        FlowBody.read(body([ASKING, ROUTING + [runs: ROUTING.runs + [cases: [
                [term: "Billing", workflow: null, bindings: []],
                [term: null, workflow: null, bindings: []],
                [term: "Billing", workflow: WORKFLOW, bindings: []]]]]], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CASE_TERM_REPEATED
    }

    /** A step is bound from by its place among those sent; none is its own source, and none is past the end. */
    def "a source naming no step sent, or the step it fills, is refused"() {
        when:
        FlowBody.read(body([ASKING + [bindings: [[target: "complaint", source: source]]], UNCHOSEN], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        source << [[step: 0, path: "summary"], [step: 2, path: "summary"], [step: -1, path: "summary"],
                   [step: "1", path: "summary"], [step: 1], [input: "complaint", path: "x"], [input: "Complaint"],
                   [input: "a..b"], [constant: 1, input: "a"], [:], "complaint"]
    }

    def "a step bound from a step sent later is read as it is, to be named when submitted"() {
        expect:
        FlowBody.read(body([ASKING + [bindings: [[target: "complaint", source: [step: 1, path: "summary"]]]], UNCHOSEN], []))
                .steps()[0].bindings()*.source() == [new FlowBody.SentSource.Step(1, Pointer.parse("summary"))]
    }

    /** What a model is sent holds no character that shows nothing yet changes how it reads, wherever it is written. */
    def "a constant holding a character a model may not be sent is refused as prose holding it is"() {
        when:
        FlowBody.read(body([BENEATH + [bindings: [[target: "complaint", source: [constant: constant]]]]], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == expected

        where:
        constant                                                          || expected
        '"a' + Character.toString(0x202E) + 'b"'                          || RefusalCode.PROSE_DIRECTION_CONTROL
        '{"note":["ok","x' + Character.toString(0xE0041) + '"]}'          || RefusalCode.PROSE_TAG_CHARACTER
        '"half ' + Character.toString(0xD800) + ' a pair"'                || RefusalCode.BODY_UNUSABLE
        '"half ' + "\\" + 'ud800 a pair"'                                 || RefusalCode.BODY_UNUSABLE
    }

    /** A constant crosses as its JSON text, so no number in it is ever read through a double on the way. */
    def "a constant that is not the text of one JSON value is refused as a body this does not take"() {
        when:
        FlowBody.read(body([], [[target: "amount", source: [constant: constant]]]))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        constant << [12, null, true, [a: 1], "", "  ", "1 2", "{} x", "{", "'a'", '{"a":1,"a":2}', "1" * 1001]
    }

    /** Refused before its plain form is ever written out, which for a number this long would never end. */
    def "a constant no store keeps as written is refused as one that does not fit"() {
        when:
        FlowBody.read(body([], [[target: "amount", source: [constant: written]]]))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.CONSTANT_DOES_NOT_FIT

        where:
        written << ["1e999999999", "1e-999999999", "1e200000", "1e-20000", "1" * 39, "0." + "1" * 39, "1." + "0" * 39,
                    "1e2147483647", "-1e2147483647", "12e2147483647", "1e-2147483647", "1e9999999999", "1e3", "1.0e1",
                    "[1, 1e-5]",
                    '"a' + "\\" + 'u0000b"', '{"a' + "\\" + 'u0000":1}', '"' + "x" * 8193 + '"',
                    "[" + (["0"] * 350000).join(",") + "]"]
    }

    def "a constant at every bound a store keeps is read"() {
        expect:
        FlowBody.read(body([], [[target: "amount", source: [constant: written]]])).outputs()*.source() ==
                [new FlowBody.SentSource.Written(stored)]

        where:
        written                 || stored
        "1" * 38                || "1" * 38
        "0." + "1" * 38         || "0." + "1" * 38
        '"' + "x" * 8192 + '"'  || '"' + "x" * 8192 + '"'
    }

    /** Read through a double, any of these would be rounded or written with an exponent. */
    def "a constant's number is kept exactly as written, its digits and its zeros"() {
        expect:
        FlowBody.read(body([], [[target: "amount", source: [constant: written]]])).outputs()*.source() ==
                [new FlowBody.SentSource.Written(stored)]

        where:
        written                                   || stored
        "0.1000000000000000055511151231257827"    || "0.1000000000000000055511151231257827"
        "12345678901234567890123456789012345678"  || "12345678901234567890123456789012345678"
        "-1.50"                                   || "-1.50"
        "1000"                                    || "1000"
    }

    /** A question is produced by a model or a person, a code step by code or a person saying it was done. */
    def "a producer that is not one what the step runs may have, or not whole, is refused whole"() {
        when:
        FlowBody.read(body([step], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        step << [
                ASKING + [producer: [kind: "model", model: "general", mode: "ordinary", toldWhatHappened: false]],
                ASKING + [producer: [kind: "model", model: "General", mode: null, toldWhatHappened: false]],
                ASKING + [producer: [kind: "model", model: "general", mode: null]],
                ASKING + [producer: [kind: "model", model: "general", mode: null, toldWhatHappened: "yes"]],
                ASKING + [producer: [kind: "person", toldWhatHappened: false]],
                ASKING + [producer: [kind: "code"]],
                ASKING + [producer: [kind: "robot"]],
                ASKING + [producer: "person"],
                CODED + [producer: [kind: "model", model: "general", mode: null, toldWhatHappened: false]],
                CODED + [producer: [kind: "code", toldWhatHappened: false]],
        ]
    }

    def "a person, or nothing chosen, produces a question's values as sent"() {
        expect:
        FlowBody.read(body([ASKING + [producer: producer, tries: null]], [])).steps()[0].producer() == read

        where:
        producer         || read
        null             || null
        [kind: "person"] || new Producer.Person()
    }

    def "code, a person saying it was done, or nothing chosen, produces a code step's values as sent"() {
        expect:
        FlowBody.read(body([CODED + [producer: producer]], [])).steps()[0].producer() == read

        where:
        producer         || read
        [kind: "code"]   || new Producer.Code()
        [kind: "person"] || new Producer.Person()
        null             || null
    }

    def "tries of none and a reviewer not named with its mode are refused whole"() {
        when:
        FlowBody.read(body([step], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.BODY_UNUSABLE

        where:
        step << [
                ASKING + [tries: 0],
                ASKING + [tries: 1.5],
                ASKING + [reviewer: [model: "general"]],
                ASKING + [reviewer: [model: "general", mode: "research", extra: 1]],
                CODED + [reviewer: "small"],
        ]
    }

    def "a step producing values that says nothing yet of its tries is read as saying nothing of them"() {
        when:
        def asking = FlowBody.read(body([ASKING + [tries: null]], [])).steps()[0]

        then:
        asking.tries() == null
        asking.reviewer() == new ModelChoice(new ModelName("small"), null)
    }

    def "a step's name is one a step may be called, or refused as such"() {
        when:
        FlowBody.read(body([ASKING + [name: name]], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == expected

        where:
        name       || expected
        "Classify" || RefusalCode.STEP_NAME_UNUSABLE
        ""         || RefusalCode.STEP_NAME_UNUSABLE
        "a" * 64   || RefusalCode.STEP_NAME_UNUSABLE
        7          || RefusalCode.BODY_UNUSABLE
    }

    /** Counted in characters as the store counts a term, so one written in letters beyond ASCII is not cut short. */
    def "a term of as many characters as a list's term may hold is read"() {
        expect:
        (FlowBody.read(body([ASKING, ROUTING + [runs: ROUTING.runs + [cases: [[term: term, workflow: null, bindings: []]]]]], []))
                .steps()[1].runs() as FlowBody.SentRuns.Route).cases()*.term() == [term]

        where:
        term << ["x" * 128, Character.toString(0xE9) * 128, Character.toString(0x1F600) * 128]
    }

    /** Only the controls are refused, so each character just past either range of them is read. */
    def "a term of one character, or holding a character just past the controls, is read"() {
        expect:
        (FlowBody.read(body([ASKING, ROUTING + [runs: ROUTING.runs + [cases: [[term: term, workflow: null, bindings: []]]]]], []))
                .steps()[1].runs() as FlowBody.SentRuns.Route).cases()*.term() == [term]

        where:
        term << ["x", "Bill ing", "Bill~ing", "Bill" + Character.toString(0xA0) + "ing"]
    }

    /** What is pinned is named by the identifier this system mints it under, and nothing else is one. */
    def "a version that is no identifier this system mints is refused as none that may be pinned"() {
        when:
        FlowBody.read(body([ASKING + [runs: [kind: "question", version: "not-a-version"]]], []))

        then:
        def refused = thrown(ApiErrorException)
        refused.errorCode() == RefusalCode.VERSION_NOT_PINNABLE
    }

    def "a key that is no identifier this system mints keeps nothing, and is read as none"() {
        expect:
        FlowBody.read(body([ASKING + [stepId: key]], [])).steps()[0].readAs() == null

        where:
        key << [null, "not-a-key"]
    }

    private static JsonNode body(List<Map> steps, List<Map> outputs) {
        JSON.valueToTree([revision: 7, steps: steps, outputs: outputs])
    }

    private static List<Map> unchosen(int count) {
        (1..count).collect { UNCHOSEN + [name: "step_" + it] }
    }
}
