package com.lilradish.lite

import com.lilradish.lite.domain.codestep.CodeCall
import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.codestep.CodeOutcome
import com.lilradish.lite.domain.codestep.CodeStepName
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ForeignProse
import com.lilradish.lite.domain.inference.GivenBack
import com.lilradish.lite.domain.wire.JsonValue
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

/** What a code step is called with and gives back renders without the values it carries. */
class LoggingRedactionCodeStepIntegrationSpec extends Specification {

    def "code gone wrong as giving otherwise renders what reads the field, a step by its key and an output by its field"() {
        when:
        def rendered = new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("receipt")], null, readBy)
                .toString()

        then:
        rendered.startsWith('CodeError$Fault(')
        rendered.contains(head)
        rendered.contains(named)
        !rendered.contains("Reads@")

        where:
        readBy                                                                           || head                             | named
        new CodeError.StepReads(UUID.fromString("44440000-0000-4000-8000-00000000000c")) || 'readBy=CodeError$StepReads('   | 'step=44440000-0000-4000-8000-00000000000c'
        new CodeError.OutputReads([new FieldName("result")])                             || 'readBy=CodeError$OutputReads(' | 'FieldName(value=result)'
    }

    def "values a code step gave back that fit render as fitting and drop the values"() {
        when:
        def rendered = new GivenBack.Fits(
                [(new FieldName("summary")): new JsonValue.JsonString("invoice 4471 was refused")]).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("values=")

        and: "under a head only the rewrite emits"
        rendered == 'GivenBack$Fits()'
    }

    def "a code step's misfit renders why and where, and drops the member nothing declares"() {
        when:
        def rendered = new GivenBack.Misfit(DidNotFitReason.FIELD_UNKNOWN, [new FieldName("details")],
                "invoice 4471 was refused").toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("undeclared=")

        and: "under a head only the rewrite emits, why and where still said"
        rendered.startsWith('GivenBack$Misfit(')
        rendered.contains("FIELD_UNKNOWN")
        rendered.contains("details")
    }

    def "a call to a code step renders which code step it runs and drops what it takes, declares and is offered"() {
        when:
        def rendered = new CodeCall(new CodeStepName("send_reply"), SpecCodeStep.SEND_REPLY.declaration(), [:],
                new JsonValue.JsonObject([new JsonValue.JsonMember("reply", new JsonValue.JsonString("invoice 4471 was refused"))]), [:])
                .toString()

        then:
        !rendered.contains("invoice 4471")
        ["takes=", "declared=", "lists="].every { !rendered.contains(it) }

        and: "under a head only the rewrite emits, naming the code step"
        rendered.startsWith('CodeCall(')
        rendered.contains('name=CodeStepName(value=send_reply)')
        rendered == 'CodeCall(name=CodeStepName(value=send_reply))'
    }

    def "values code gave back render as given and drop them"() {
        when:
        def rendered = new CodeOutcome.Gave([new CodeOutcome.Given(new FieldName("receipt"),
                new JsonValue.JsonString("invoice 4471 was sent"), FieldStanding.ALWAYS, null)]).toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("values=")

        and: "under a head only the rewrite emits"
        rendered == 'CodeOutcome$Gave()'
    }

    def "a value code gave back renders which field it is of and how it stands, and drops the value"() {
        when:
        def rendered = new CodeOutcome.Given(new FieldName("tier"), new JsonValue.JsonString("invoice 4471 is gold"),
                FieldStanding.ABOVE_CONFIDENCE, 80).toString()

        then:
        !rendered.contains("invoice 4471")

        and: "the value's own slot gone, the only value= left being the field name's"
        !rendered.replace("FieldName(value=tier)", "").contains("value=")

        and: "under a head only the rewrite emits, the field's name rendered in turn"
        rendered.startsWith('CodeOutcome$Given(')
        rendered.contains('name=FieldName(value=tier)')
        rendered.contains('standing=ABOVE_CONFIDENCE')
        rendered.contains('floor=80')
        rendered == 'CodeOutcome$Given(name=FieldName(value=tier), standing=ABOVE_CONFIDENCE, floor=80)'
    }

    def "code gone wrong renders whether what it said was cut and drops what it said and what it gave back"() {
        when:
        def rendered = new CodeOutcome.Errored(
                new CodeError.Said(ForeignProse.errorDetail("invoice 4471 was refused upstream " + "a" * 2048)),
                null).toString()
        def returned = new CodeOutcome.Errored(
                new CodeError.Fault(CodeErrorReason.TOO_LONG, [new FieldName("total")], null, null),
                '{"total":"1234.50"}').toString()

        then:
        !rendered.contains("invoice 4471")
        !rendered.contains("1234.50")
        !returned.contains("1234.50")
        ["text=", "returned="].every { !rendered.contains(it) && !returned.contains(it) }

        and: "under a head only the rewrite emits, what it said rendered a level down as prose renders"
        rendered.startsWith('CodeOutcome$Errored(')
        rendered.contains('wentWrong=CodeError$Said(detail=ForeignProse(truncated=true))')
        rendered == 'CodeOutcome$Errored(wentWrong=CodeError$Said(detail=ForeignProse(truncated=true)))'
        returned == 'CodeOutcome$Errored(wentWrong=CodeError$Fault(reason=TOO_LONG, path=[FieldName(value=total)], readBy=null))'
    }

    def "code gone wrong for this system's reason renders the reason and where, and drops the member and what it gave back"() {
        when:
        def rendered = new CodeOutcome.Errored(
                new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [new FieldName("lines")],
                        new CodeError.UndeclaredMember("invoice_4471", false), null),
                '{"lines":[{"invoice_4471":"1234.50"}]}').toString()

        then:
        !rendered.contains("invoice_4471")
        !rendered.contains("1234.50")
        ["member=", "returned="].every { !rendered.contains(it) }

        and: "under a head only the rewrite emits, the reason and the field rendered a level down"
        rendered.startsWith('CodeOutcome$Errored(')
        rendered.contains('wentWrong=CodeError$Fault(')
        rendered.contains('reason=NOT_DECLARED')
        rendered.contains('FieldName(value=lines)')
    }
}
