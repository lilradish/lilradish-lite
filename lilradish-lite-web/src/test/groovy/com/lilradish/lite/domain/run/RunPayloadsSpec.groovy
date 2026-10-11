package com.lilradish.lite.domain.run

import static com.lilradish.lite.domain.run.fixture.Runs.MODEL
import static com.lilradish.lite.domain.run.fixture.Runs.PERSON
import static com.lilradish.lite.domain.run.fixture.Runs.asking
import static com.lilradish.lite.domain.run.fixture.Runs.assured
import static com.lilradish.lite.domain.run.fixture.Runs.codeStep
import static com.lilradish.lite.domain.run.fixture.Runs.gives
import static com.lilradish.lite.domain.run.fixture.Runs.json
import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.lengthReview
import static com.lilradish.lite.domain.run.fixture.Runs.lost
import static com.lilradish.lite.domain.run.fixture.Runs.lostReview
import static com.lilradish.lite.domain.run.fixture.Runs.minutes
import static com.lilradish.lite.domain.run.fixture.Runs.modelReview
import static com.lilradish.lite.domain.run.fixture.Runs.nested
import static com.lilradish.lite.domain.run.fixture.Runs.nestedMany
import static com.lilradish.lite.domain.run.fixture.Runs.open
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.personReview
import static com.lilradish.lite.domain.run.fixture.Runs.refused
import static com.lilradish.lite.domain.run.fixture.Runs.routeStep
import static com.lilradish.lite.domain.run.fixture.Runs.standing
import static com.lilradish.lite.domain.run.fixture.Runs.started
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.takes
import static com.lilradish.lite.domain.run.fixture.Runs.text
import static com.lilradish.lite.domain.run.fixture.Runs.value
import static com.lilradish.lite.domain.run.fixture.Runs.valueId
import static com.lilradish.lite.domain.run.fixture.Runs.workflowStep
import static com.lilradish.lite.domain.run.fixture.Runs.yielded

import com.lilradish.lite.domain.codestep.CodeStepDeclaration
import com.lilradish.lite.domain.codestep.ReleasedCodeStep
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.declaration.OfferedTerms
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.domain.inference.Envelope
import com.lilradish.lite.domain.referencelist.Term
import com.lilradish.lite.domain.referencelist.TermMeaning
import com.lilradish.lite.domain.registry.EntryVersionId
import com.lilradish.lite.domain.run.StepInputs.BindingRecord
import com.lilradish.lite.domain.run.fixture.Runs
import com.lilradish.lite.domain.wire.CanonicalJson
import com.lilradish.lite.domain.workflow.Binding
import com.lilradish.lite.domain.workflow.BindingSource
import com.lilradish.lite.domain.workflow.Pointer
import com.lilradish.lite.domain.workflow.Producer
import com.lilradish.lite.domain.workflow.StepId
import com.lilradish.lite.domain.workflow.StepLabel
import com.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class RunPayloadsSpec extends Specification {

    static final StepRuns.Question QUESTION = asking(1,
            takes([
                    text("complaint"),
                    text("order"),
                    nested("address", [text("street"), nested("geo", [text("lat"), text("lng")])]),
                    nestedMany("contacts", [text("name"), text("phone")])]),
            gives([standing("summary"), standing("reason")]))

    static final List<BindingRecord> BOUND = [bind("complaint", "It broke.")]

    static final String OPENING = '{"envelope_version":' + Envelope.VERSION + ',"instruction":"Answer what is asked.",' +
            '"takes":{"complaint":"It broke.","order":null,"address":null,"contacts":null}'

    static final String TOLD = ',"refused":{"values":{"summary":"late","reason":null},' +
            '"words":{"summary":"Not what was asked."}}'

    /** What a review of a production giving back {@link #LATE} and {@link #NO_REASON} is sent of it. */
    static final String ANSWERED = ',"answer":{"summary":"late","reason":null},"deciding":["summary"]'

    static final ValueRecord LATE = value(1, "summary", true, "late")

    static final ValueRecord NO_REASON = value(2, "reason", false, null)

    /** {@link #LATE} as though it stood without a review. */
    static final ValueRecord STOOD = value(1, "summary", false, "late")

    static final EntryVersionId GRADES = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000109"))

    static final Demand REVIEWED = new Demand.Stands(true, FieldStanding.ALWAYS, null)

    /** The group holding {@link #GRADES}, as a release's lists are read where it is. */
    static final Map<EntryVersionId, OfferedTerms> HERE = [(GRADES): new OfferedTerms(
            [new OfferedTerms.Offered(new Term("gold"), new TermMeaning("Graded gold.")),
             new OfferedTerms.Offered(new Term("silver"), new TermMeaning("Graded silver."))], null)]

    /** What every step here binds, as {@link #BOUND} holds it: the complaint, written into the version. */
    static final Binding BINDING = new Binding(key(900), Pointer.parse("complaint"),
            new BindingSource.Written(json("It broke.")))

    static final String CODE_OPENING = '{"envelope_version":' + Envelope.VERSION + ',"takes":{"complaint":"It broke."}'

    static final ValueRecord EXTRA = value(3, "extra", false, "more")

    /** What a review of a production giving back {@link #LATE}, {@link #NO_REASON} and {@link #EXTRA} is sent of it. */
    static final String ANSWERED_THREE =
            ',"answer":{"summary":"late","reason":null,"extra":"more"},"deciding":["summary"]'

    static final List<Field> REVIEWED_THREE = [standing("summary"), standing("reason"), standing("extra")]

    def "what producing is sent is what the step takes there, where nothing was refused before"() {
        expect:
        CanonicalJson.write(RunPayloads.toProduce(step(told()), BOUND, null)) == OPENING + '}'
    }

    def "a human display name reaches neither production overload nor ordinary or retained question review"() {
        given:
        def ordinary = step(told())
        def labelled = new PlannedStep(ordinary.id(), ordinary.order(), ordinary.name(),
                new StepLabel("人向け\u202e MetadataMarker"), ordinary.runs(), ordinary.producer(), ordinary.tries(),
                ordinary.reviewer(), ordinary.bindings())
        def tried = tookBound(byModel(1))
        def run = Runs.run([started(labelled, [tried])])
        def context = RunInTree.root(run)

        when:
        def produced = CanonicalJson.write(RunPayloads.toProduce(labelled, BOUND, null))
        def producedInRun = CanonicalJson.write(RunPayloads.toProduce(context, labelled, BOUND, null))
        def review = RunPayloads.toReview(context, run.steps()[0], tried, null, null)
        def kept = RunPayloads.toReview(context, run.steps()[0], tried, null,
                json([envelope_version: Envelope.VERSION, instruction: "Answer what is asked.",
                      takes: [complaint: "It broke.", order: null, address: null, contacts: null]]))

        then:
        labelled.name() == new StepId("step_1")
        labelled.label().value() == "人向け\u202e MetadataMarker"
        produced == OPENING + '}'
        producedInRun == OPENING + '}'
        review instanceof ReviewPayload.Built
        CanonicalJson.write(review.payload()) == OPENING + ANSWERED + '}'
        kept instanceof ReviewPayload.Built
        CanonicalJson.write(kept.payload()) == OPENING + ANSWERED + '}'
        !produced.contains("MetadataMarker")
        !producedInRun.contains("MetadataMarker")
        !CanonicalJson.write(review.payload()).contains("MetadataMarker")
        !CanonicalJson.write(kept.payload()).contains("MetadataMarker")
    }

    def "a code review retains its uninstructed payload and never sends a human display name"() {
        given:
        def ordinary = code([text("complaint")], [standing("summary"), standing("reason")], [:])
        def labelled = new PlannedStep(ordinary.id(), ordinary.order(), ordinary.name(),
                new StepLabel("人向け\u202e MetadataMarker"), ordinary.runs(), ordinary.producer(), ordinary.tries(),
                ordinary.reviewer(), ordinary.bindings())

        when:
        def review = reviewing(labelled, [byPerson(1)])

        then:
        review instanceof ReviewPayload.Built
        CanonicalJson.write(review.payload()) == CODE_OPENING + ANSWERED + '}'
        !CanonicalJson.write(review.payload()).contains("MetadataMarker")
        labelled.name() == ordinary.name()
        labelled.bindings() == [BINDING]
    }

    def "a search review keeps its historical question and records without sending step display metadata"() {
        given:
        def ordinary = Runs.searchStep(1, Runs.webSearch(), Runs.searchQuestion(1))
        def labelled = new PlannedStep(ordinary.id(), ordinary.order(), ordinary.name(),
                new StepLabel("人向け\u202e MetadataMarker"), ordinary.runs(), ordinary.producer(), ordinary.tries(),
                ordinary.reviewer(), ordinary.bindings())
        def tried = yielded(1, StepProducer.PERSON, [value(1, "title", true, "Kettle repairs"),
                value(2, "body", false, "A maker repairs kettles."), value(3, "references", false, "https://example.test/repairs"),
                value(4, "other", false, "Check stock.")])
        def run = Runs.run([started(labelled, [tried])])

        when:
        def review = RunPayloads.toReview(RunInTree.root(run), run.steps()[0], tried, json(record),
                json([envelope_version: Envelope.VERSION, instruction: "Answer what is asked.", takes: [:]]))

        then:
        review instanceof ReviewPayload.Built
        CanonicalJson.write(review.payload()) == '{"envelope_version":' + Envelope.VERSION +
                ',"instruction":"Answer what is asked.","takes":{},' + kept +
                '"answer":{"title":"Kettle repairs","body":"A maker repairs kettles.",' +
                '"references":"https://example.test/repairs","other":"Check stock."},"deciding":["title"]}'
        !CanonicalJson.write(review.payload()).contains("MetadataMarker")
        labelled.name() == new StepId("step_1")
        labelled.bindings() == []

        where:
        record                                                                                                            || kept
        [:]                                                                                                               || ''
        [searches: [[searched_for: "kettle parts", found: "A maker repairs kettles."], [searched_for: "kettle dispatch", found: "The imaginary depot records a packing note."]], cut: false, not_as_it_came: false, left_out: 0] || '"search_record":{"searches":[{"searched_for":"kettle parts","found":"A maker repairs kettles."},{"searched_for":"kettle dispatch","found":"The imaginary depot records a packing note."}],"cut":false,"not_as_it_came":false,"left_out":0},'
    }

    def "what producing is sent is worked out only from a step, named where it is missing"() {
        when:
        RunPayloads.toProduce(null, BOUND, null)

        then:
        def refusal = thrown(NullPointerException)
        refusal.message == "RunPayloads step must not be null"
    }

    def "what producing is sent is worked out only for a step running a question or search"() {
        when:
        RunPayloads.toProduce(codeStep(1, 3), BOUND, null)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "RunPayloads asks a step running a question or search"
    }

    /** What filling refuses, and every depth of it, is StepInputsSpec's: this is only that the envelope carries it. */
    def "what the step takes is sent as filling fills it, at every level in declared order"() {
        when:
        def sent = RunPayloads.toProduce(step(told()), bound.collect { target, held -> bind(target, held) }, null)

        then:
        CanonicalJson.write(sent) == '{"envelope_version":' + Envelope.VERSION + ',"instruction":"Answer what is asked.","takes":' +
                CanonicalJson.write(json([complaint: null, order: null, address: address, contacts: contacts])) + '}'

        where:
        bound                                                  || address                                          | contacts
        ["address.geo.lat": "51.5", "address.street": "Main"]  || [street: "Main", geo: [lat: "51.5", lng: null]]  | null
        [contacts: []]                                         || null                                             | []
    }

    def "a step telling the next asking sends the refused production whole, and the words each refused value got"() {
        given:
        def refusedTry = yielded(1, StepProducer.MODEL, [LATE, NO_REASON], reviews)

        expect:
        CanonicalJson.write(RunPayloads.toProduce(step(told()), BOUND, refusedTry)) == OPENING + TOLD + '}'

        where:
        reviews << [
                [personReview(minutes(20), [refused(LATE)])],
                [modelReview(minutes(20), [refused(LATE)])],
                [personReview(minutes(20), [assured(LATE)]),
                 new ReviewRecord(PERSON, minutes(40), true, null, null, [refused(LATE)])]]
    }

    def "a refused production is told without how sure its model was of any value"() {
        given:
        def sure = new ValueRecord(valueId(1), "summary", json("late"), 87, true)
        def refusedTry = yielded(1, StepProducer.MODEL, [sure, NO_REASON], [modelReview(minutes(20), [refused(sure)])])

        when:
        def sent = CanonicalJson.write(RunPayloads.toProduce(step(told()), BOUND, refusedTry))

        then:
        sent == OPENING + TOLD + '}'
        !sent.contains("87")
        !sent.contains("confidence")
    }

    def "a step that tells nothing sends nothing of a refused production"() {
        given:
        def refusedTry = yielded(1, StepProducer.MODEL, [LATE, NO_REASON], [personReview(minutes(20), [refused(LATE)])])

        expect:
        CanonicalJson.write(RunPayloads.toProduce(step(producer), BOUND, refusedTry)) == OPENING + '}'

        where:
        producer << [new Producer.Model(MODEL, false), person()]
    }

    def "a production refused only by a review that went wrong is told nothing, no words having refused it"() {
        given:
        def refusedTry = yielded(1, StepProducer.MODEL, [LATE, NO_REASON], [lostReview(minutes(20))])

        expect:
        CanonicalJson.write(RunPayloads.toProduce(step(told()), BOUND, refusedTry)) == OPENING + '}'
    }

    def "a production named as refused that gave nothing back is refused, whether or not the step tells"() {
        when:
        RunPayloads.toProduce(step(producer), BOUND, named)

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "RunPayloads tells only a production that gave something back"

        where:
        named                       | producer
        lost(1, StepProducer.MODEL) | told()
        open(1, StepProducer.MODEL) | told()
        lost(1, StepProducer.MODEL) | new Producer.Model(MODEL, false)
    }

    def "a production named as refused with no value refused is refused, whether or not the step tells"() {
        when:
        RunPayloads.toProduce(step(producer), BOUND, yielded(1, StepProducer.MODEL, values, reviews))

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "RunPayloads tells only a production with a value refused"

        where:
        values                                          | reviews                                      | producer
        [value(1, "summary", false, "late"), NO_REASON] | []                                           | told()
        [LATE, NO_REASON]                               | []                                           | told()
        [LATE, NO_REASON]                               | [personReview(minutes(20), [assured(LATE)])] | told()
        [LATE, NO_REASON]                               | []                                           | new Producer.Model(MODEL, false)
    }

    /**
     * What went in is read back from the run, the refused production that went in is found in it, and nothing says
     * who or what produced it. A code step is reviewed as the running release declares it now, with no instruction;
     * where that release pins a list not here, no longer takes what the version binds into it, or no longer declares
     * what came back of the try or of the production told, nothing is built, and the first of those is said.
     */
    def "what reviewing is sent is built from what went in and came out, or said to be unbuildable, in one judgement"() {
        when:
        def built = reviewing(planned, tries)

        then:
        (built instanceof ReviewPayload.Built ? CanonicalJson.write(built.payload()) : null) == sent
        (built instanceof ReviewPayload.Unbuilt ? built.reason() : null) == unbuilt

        where:
        planned                                                                         | tries                                     || sent                                  | unbuilt
        step(told())                                                                    | [byModel(1)]                              || OPENING + ANSWERED + '}'              | null
        step(told())                                                                    | [inWords(1), byPerson(2)]                 || OPENING + TOLD + ANSWERED + '}'       | null
        step(new Producer.Model(MODEL, false))                                          | [inWords(1), byPerson(2)]                 || OPENING + TOLD + ANSWERED + '}'       | null
        step(person())                                                                  | [inWords(1), byPerson(2)]                 || OPENING + TOLD + ANSWERED + '}'       | null
        step(person())                                                                  | [inNoWords(1), byPerson(2)]               || OPENING + ANSWERED + '}'              | null
        code([text("complaint")], [standing("summary"), standing("reason")], [:])       | [byPerson(1)]                             || CODE_OPENING + ANSWERED + '}'         | null
        code([text("complaint")], [standing("summary"), standing("reason")], [:])       | [codeInWords(1), byPerson(2)]             || CODE_OPENING + TOLD + ANSWERED + '}'  | null
        code([text("complaint")], [standing("summary"), graded("reason")], HERE)        | [byPerson(1)]                             || CODE_OPENING + ANSWERED + '}'         | null
        code([text("complaint")], REVIEWED_THREE, [:])                                  | [inNoWords(1), byPerson(2, [EXTRA])]      || CODE_OPENING + ANSWERED_THREE + '}'   | null
        code([graded("complaint", true)], [standing("summary"), standing("reason")], [:]) | [byPerson(1)]                           || null                                  | ReviewUnbuiltReason.LIST_NOT_HERE
        code([text("complaint")], [standing("summary"), graded("reason")], [:])         | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.LIST_NOT_HERE
        code([text("message")], [graded("summary")], [:])                               | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.LIST_NOT_HERE
        code([text("message")], [standing("summary"), standing("reason")], [:])         | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED
        code([text("complaint"), required("case")], [standing("summary"), standing("reason")], [:]) | [byPerson(1)]                 || null                                  | ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED
        code([text("message")], [standing("summary")], [:])                             | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.TAKES_NO_LONGER_DECLARED
        code([text("complaint")], [standing("summary")], [:])                           | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.NO_LONGER_DECLARED
        code([text("complaint")], REVIEWED_THREE, [:])                                  | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.NO_LONGER_DECLARED
        code([text("complaint")], [standing("summary"), standing("cause")], [:])        | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.NO_LONGER_DECLARED
        code([text("complaint")], [standingFields("summary"), standing("reason")], [:]) | [byPerson(1)]                             || null                                  | ReviewUnbuiltReason.NO_LONGER_DECLARED
        code([text("complaint")], REVIEWED_THREE, [:])                                  | [codeInWords(1), byPerson(2, [EXTRA])]    || null                                  | ReviewUnbuiltReason.NO_LONGER_DECLARED
        code([text("complaint"), nestedFile("attachment", true)], [standing("summary"), standing("reason")], [:]) | [byPerson(1)] || null | ReviewUnbuiltReason.HOLDS_A_FILE
        code([text("complaint")], [standing("summary"), standing("reason"), nestedFile("attachment", false)], [:]) | [byPerson(1)] || null | ReviewUnbuiltReason.HOLDS_A_FILE
    }

    /** Who or what produced it is never sent, so the same production reads the same whoever made it. */
    def "a review is sent no confidence and nothing of who produced what it reviews"() {
        given:
        def sure = new ValueRecord(valueId(1), "summary", json("late"), 87, true)

        when:
        def modelSent = CanonicalJson.write((reviewing(step(told()), [yielded(1, StepProducer.MODEL, [sure, NO_REASON])])
                as ReviewPayload.Built).payload())
        def personSent = CanonicalJson.write((reviewing(step(told()), [byPerson(1)]) as ReviewPayload.Built).payload())

        then:
        modelSent == personSent
        modelSent == OPENING + ANSWERED + '}'
        !modelSent.contains("87")
        !modelSent.contains("confidence")
        !personSent.contains(PERSON.value().toString())
    }

    def "a review is worked out only of a production that gave something back, of a question or a code step, with a value to decide"() {
        when:
        reviewing(planned, [reviewed])

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == message

        where:
        planned         | reviewed                                               || message
        workflowStep(1) | yielded(1, StepProducer.CODE, [LATE, NO_REASON])       || "RunPayloads reviews only what a question or a code step produced"
        routeStep(1)    | yielded(1, StepProducer.CODE, [LATE, NO_REASON])       || "RunPayloads reviews only what a question or a code step produced"
        step(told())    | open(1, StepProducer.MODEL)                            || "RunPayloads reviews only a production that gave something back"
        step(told())    | lost(1, StepProducer.MODEL)                            || "RunPayloads reviews only a production that gave something back"
        step(told())    | yielded(1, StepProducer.MODEL, [STOOD, NO_REASON])     || "Payload decides values given back, each once, and one at least"
    }

    def "a code step gone from the release leaves its produced value for a person to review"() {
        when:
        def payload = reviewing(codeStep(1, 3, null, Runs.code(), [BINDING]), [byPerson(1)])

        then:
        payload instanceof ReviewPayload.Unbuilt
        payload.reason() == ReviewUnbuiltReason.NO_LONGER_DECLARED
        !(payload instanceof ReviewPayload.Built)
    }

    def "the values a review decides are those of the production waiting on one, in declared order"() {
        given:
        def first = value(1, "summary", true, "late")
        def second = value(2, "reason", true, "slow")
        def reviewed = yielded(1, StepProducer.MODEL, [first, value(3, "extra", false), second], reviews)

        expect:
        RunPayloads.deciding(reviewed)*.field() == fields

        where:
        reviews                                                                    || fields
        []                                                                         || ["summary", "reason"]
        [lengthReview(minutes(20), [refused(LATE)])]                               || ["reason"]
        [personReview(minutes(20), [assured(LATE), assured(value(2, "reason", true))])] || []
    }

    /**
     * What went into a model's production is what the step told it; into a person's, the one answering it here
     * showed, which is the same production whatever the step tells. A refusal in no words is none. Code is told
     * nothing.
     */
    def "the refused production that went into what is reviewed is the newest refused in words, where its producer was shown one"() {
        given:
        def reviewed = reviewedAfter(held, producer)
        def planned = step(tells ? told() : new Producer.Model(MODEL, false))

        expect:
        RunPayloads.wentInto(started(planned, tried(held) + [reviewed]), reviewed).map { it.number() } ==
                Optional.ofNullable(expected)

        where:
        held                                            | producer            | tells || expected
        "refused in words, then lost"                   | StepProducer.MODEL  | true  || 1
        "refused in words, then lost"                   | StepProducer.MODEL  | false || null
        "refused in words, then in no words"            | StepProducer.MODEL  | true  || 1
        "refused in words, then lost"                   | StepProducer.PERSON | false || 1
        "refused in words, then in no words"            | StepProducer.PERSON | false || 1
        "refused in words, then in no words"            | StepProducer.PERSON | true  || 1
        "refused in words, in no words, then in words"  | StepProducer.PERSON | false || 3
        "refused in no words, then lost"                | StepProducer.PERSON | false || null
        "refused in words, then assured"                | StepProducer.PERSON | true  || 1
        "refused in words, then assured"                | StepProducer.MODEL  | true  || 1
        "none"                                          | StepProducer.PERSON | true  || null
    }

    def "a code step's production is told nothing refused before it"() {
        given:
        def refusedTry = yielded(1, StepProducer.CODE, [LATE, NO_REASON], [personReview(minutes(20), [refused(LATE)])])
        def reviewed = yielded(2, StepProducer.CODE, [LATE, NO_REASON])

        expect:
        RunPayloads.wentInto(started(codeStep(1, 3), [refusedTry, reviewed]), reviewed) == Optional.empty()
    }

    def "the production told is the newest before the asking refused in words, tries lost, open or refused in no words passed over"() {
        expect:
        refusedForAsking(held, asking).map { it.number() } ==
                Optional.ofNullable(newest)

        where:
        held                                   | asking || newest
        "refused in words, then lost"          | 3      || 1
        "refused in words, then in no words"   | 3      || 1
        "refused in words, in no words, then in words" | 4 || 3
        "refused in words, in no words, then in words" | 3 || 1
        "refused in words, then assured"       | 3      || 1
        "refused in words twice"               | 3      || 2
        "refused in words twice"               | 2      || 1
        "refused in words, then open"          | 2      || 1
        "refused in words, then open"          | 3      || 1
        "refused for length"                   | 2      || 1
        "refused in no words, then lost"       | 3      || null
        "assured"                              | 2      || null
        "none"                                 | 1      || null
    }

    def "every production found refused in words is one the next asking is told, in those words"() {
        given:
        def found = refusedForAsking(held, asking).orElseThrow()

        when:
        def sent = CanonicalJson.write(RunPayloads.toProduce(step(told()), BOUND, found))

        then:
        noExceptionThrown()
        sent.contains('"words":{"' + field + '":"Not what was asked."}')

        where:
        held                                 | asking || field
        "refused in words, then lost"        | 3      || "summary"
        "refused in words, then in no words" | 3      || "summary"
        "refused in words, then assured"     | 3      || "summary"
        "refused in words twice"             | 3      || "summary"
        "refused in words, then open"        | 3      || "summary"
        "refused for length"                 | 2      || "reason"
    }

    def "an existing model production finds the same refusal before its number is assigned or after it is cleared"() {
        given:
        def history = tried("refused in words twice")
        def identity = new ProductionId(UUID.randomUUID())
        def pending = new TryRecord(identity, number, StepProducer.MODEL, null, minutes(30), null, null, null, null,
                null, false, null, false, false, null, [], [], [], [], [])
        def snapshot = started(step(told()), history + [pending])

        when:
        def found = RunPayloads.refusedInWords(snapshot, identity)

        then:
        found.orElseThrow().id() == history.last().id()
        found.orElseThrow().number() == 2
        RunPayloads.wentInto(snapshot, pending) == found
        CanonicalJson.write(RunPayloads.toProduce(step(told()), BOUND, found.orElseThrow()))
                .contains('"words":{"summary":"Not what was asked."}')

        where:
        number << [null, 3]
    }

    def "the production told is looked for only before a production the step holds"() {
        when:
        RunPayloads.refusedInWords(started(step(told()), tried("refused in words twice")),
                new ProductionId(UUID.randomUUID()))

        then:
        def refusal = thrown(IllegalArgumentException)
        refusal.message == "RunPayloads names a production its step does not hold"
    }

    def "the production told is looked for only in a step"() {
        when:
        RunPayloads.refusedInWords(null, new ProductionId(UUID.randomUUID()))

        then:
        def refusal = thrown(NullPointerException)
        refusal.message == "RunPayloads step must not be null"
    }

    private static Producer told() {
        new Producer.Model(MODEL, true)
    }

    /** The try after those {@code held} names, giving back what they gave, produced by {@code producer}. */
    private static TryRecord reviewedAfter(String held, StepProducer producer) {
        int number = tried(held).size() + 1
        yielded(number, producer, [LATE, NO_REASON], [], producer == StepProducer.PERSON ? PERSON : null)
    }

    def "production and review mark a search source once at its top level for either producer"() {
        given:
        def sourceValue = value(910, 'body', false, 'Demo Sample web text')
        def searched = started(Runs.searchStep(1), [yielded(1, sourceProducer, [sourceValue])])
        def source = new BindingSource.StepOutput(stepId(1).value(), Pointer.parse('body'))
        def bindings = [new Binding(key(911), Pointer.parse('address.geo.lat'), source),
                        new Binding(key(912), Pointer.parse('address.street'), source),
                        new Binding(key(913), Pointer.parse('complaint'), new BindingSource.Written(json('Demo Sample complaint')))]
        def planned = new PlannedStep(stepId(2), 2, new StepId('step_2'), null, QUESTION, told(), 3, null, bindings)
        def current = Runs.run([searched, Runs.unstarted(planned)])
        def at = RunInTree.root(current)
        def bound = StepInputs.bound(at, current.steps()[1]).orElseThrow()
        def produced = RunPayloads.toProduce(at, planned, bound, null)
        def original = byModel(1)
        def reviewed = new TryRecord(new ProductionId(key(914)), original.number(), original.producer(), original.askedBy(),
                original.askedAt(), original.endedAt(), original.endedBy(), original.explanation(), original.lost(),
                original.fault(), original.lostDetailPresent(), original.lostDetail(), original.lostDetailCut(), original.returnedPresent(), original.returned(), original.values(),
                original.reviews(), bound.collect { new InputRecord(it.binding().id(), it.source(), it.parts()) },
                original.attempts(), original.calls())
        def held = Runs.run([searched, started(planned, [reviewed])])

        when:
        def review = RunPayloads.toReview(RunInTree.root(held), held.steps()[1], reviewed, null, null)

        then:
        CanonicalJson.write(produced).contains('"found_on_the_web":["address"]')
        review instanceof ReviewPayload.Built
        CanonicalJson.write(review.payload()).contains('"found_on_the_web":["address"]')

        where:
        sourceProducer << [StepProducer.MODEL, StepProducer.PERSON]
    }

    private static PlannedStep step(Producer producer) {
        new PlannedStep(stepId(1), 1, new StepId("step_1"), null, QUESTION, producer, 3, null, [BINDING])
    }

    /** A code step code produces, bound as {@link #step} is, as the running release declares it now. */
    private static PlannedStep code(List<Field> taken, List<Field> given, Map<EntryVersionId, OfferedTerms> lists) {
        codeStep(1, 3, released(taken, given, lists), Runs.code(), [BINDING])
    }

    /**
     * How reviewing the last of {@code tries} of {@code planned} comes out, each try having taken what
     * {@link #BINDING} binds, as the run holding only that step reads it.
     */
    private static ReviewPayload reviewing(PlannedStep planned, List<TryRecord> tries) {
        def took = tries.collect { tookBound(it) }
        def run = Runs.run([started(planned, took)])
        RunPayloads.toReview(RunInTree.root(run), run.steps()[0], took.last(), null, null)
    }

    private static TryRecord tookBound(TryRecord aTry) {
        new TryRecord(aTry.id(), aTry.number(), aTry.producer(), aTry.askedBy(), aTry.askedAt(), aTry.endedAt(),
                aTry.endedBy(), aTry.explanation(), aTry.lost(), aTry.fault(), aTry.lostDetailPresent(), aTry.lostDetail(), aTry.lostDetailCut(),
                aTry.returnedPresent(), aTry.returned(), aTry.values(), aTry.reviews(), [new InputRecord(BINDING.id(), null, [])], aTry.attempts(),
                aTry.calls())
    }

    private static TryRecord byModel(int number) {
        yielded(number, StepProducer.MODEL, [LATE, NO_REASON])
    }

    private static TryRecord byPerson(int number, List<ValueRecord> besides = []) {
        yielded(number, StepProducer.PERSON, [LATE, NO_REASON] + besides, [], PERSON)
    }

    /** A model's production whose summary a person refused in words. */
    private static TryRecord inWords(int number) {
        yielded(number, StepProducer.MODEL, [LATE, NO_REASON], [personReview(minutes(20), [refused(LATE)])])
    }

    /** Code's production whose summary a person refused in words. */
    private static TryRecord codeInWords(int number) {
        yielded(number, StepProducer.CODE, [LATE, NO_REASON], [personReview(minutes(20), [refused(LATE)])])
    }

    /** A model's production refused only by a review that went wrong, in no words. */
    private static TryRecord inNoWords(int number) {
        yielded(number, StepProducer.MODEL, [LATE, NO_REASON], [lostReview(minutes(20))])
    }

    /** A field taken that must be given. */
    private static Field required(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Text(200), new HowMany.One(), new Demand.Given(true))
    }

    /** The code step as a release declares it now, beside the terms of those lists it pins that are here. */
    private static ReleasedCodeStep released(List<Field> taken, List<Field> given,
                                             Map<EntryVersionId, OfferedTerms> lists) {
        new ReleasedCodeStep(new CodeStepDeclaration(takes(taken), gives(given), true), lists)
    }

    /** A term of {@link #GRADES}: one taken where it is taken, and otherwise given back to stand once reviewed. */
    private static Field graded(String name, boolean taken = false) {
        new Field(new FieldName(name), null, null, new FieldShape.Term(GRADES), new HowMany.One(),
                taken ? new Demand.Given(false) : REVIEWED)
    }

    /** Fields given back to stand once reviewed, which no text is written as. */
    private static Field standingFields(String name) {
        new Field(new FieldName(name), null, null, new FieldShape.Nested([text("why")]), new HowMany.One(), REVIEWED)
    }

    private static Field nestedFile(String name, boolean taken) {
        Demand demand = taken ? new Demand.Given(false) : REVIEWED
        new Field(new FieldName(name), null, null,
                new FieldShape.Nested([new Field(new FieldName("document"), null, null,
                        new FieldShape.File([new FileExtension(".pdf")], 1024L),
                        new HowMany.One(), new Demand.Given(false))]), new HowMany.One(), demand)
    }

    /** A constant, which is as good a source as any: what went in is the value, whatever it was read from. */
    private static BindingRecord bind(String target, Object held, int number = 900) {
        new BindingRecord(new Binding(key(number), Pointer.parse(target), new BindingSource.Written(json(held))),
                json(held), null, [], [])
    }

    /** A model's tries, oldest first, each giving back both fields and each refused in words having its summary refused. */
    private static List<TryRecord> tried(String held) {
        def gave = [LATE, NO_REASON]
        def inWords = { int number -> yielded(number, StepProducer.MODEL, gave, [modelReview(minutes(20), [refused(LATE)])]) }
        switch (held) {
            case "refused in words, then lost": return [inWords(1), lost(2, StepProducer.MODEL)]
            case "refused in words, then in no words": return [inWords(1), yielded(2, StepProducer.MODEL, gave, [lostReview(minutes(30))])]
            case "refused in words, in no words, then in words": return [inWords(1), yielded(2, StepProducer.MODEL, gave, [lostReview(minutes(30))]), inWords(3)]
            case "refused in words, then assured": return [inWords(1), yielded(2, StepProducer.MODEL, gave, [personReview(minutes(30), [assured(LATE)])])]
            case "refused in words twice": return [inWords(1), inWords(2)]
            case "refused in words, then open": return [inWords(1), open(2, StepProducer.MODEL)]
            case "refused for length": return [yielded(1, StepProducer.MODEL, gave, [lengthReview(minutes(20), [refused(NO_REASON)])])]
            case "refused in no words, then lost": return [yielded(1, StepProducer.MODEL, gave, [lostReview(minutes(20))]), lost(2, StepProducer.MODEL)]
            case "assured": return [yielded(1, StepProducer.MODEL, gave, [personReview(minutes(20), [assured(LATE)])])]
            case "none": return []
            default: throw new IllegalArgumentException(held)
        }
    }
    private static Optional<TryRecord> refusedForAsking(String held, int asking) {
        def tries = tried(held)
        def askingTry = tries.find { it.number() == asking }
        def snapshot = started(step(told()), tries)
        askingTry == null ? RunPayloads.refusedBeforeAnswer(snapshot) : RunPayloads.refusedInWords(snapshot, askingTry.id())
    }


}
