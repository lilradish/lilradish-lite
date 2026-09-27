package org.lilradish.lite.app.codestep

import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

class CodeStepsSpec extends Specification {

    static final SpecCodeStep ARCHIVE = new SpecCodeStep("archive",
            [SpecCodeStep.given("ticket", new FieldShape.Text(64))], [], true)

    /** A step naming it could not say which of two runs, so the start stops rather than choosing one. */
    def "refuses two code steps under one name"() {
        when:
        new CodeSteps(held)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == expectedMessage

        where:
        held                                                        || expectedMessage
        [SpecCodeStep.SEND_REPLY, ARCHIVE, SpecCodeStep.SEND_REPLY] || "CodeSteps holds send_reply more than once"
        [ARCHIVE, new SpecCodeStep("archive", [], [], false)]       || "CodeSteps holds archive more than once"
    }

    /** The store spells a code step as text, so a spelling no name could be is held by nothing rather than refused. */
    def "answers what a code step it holds declares, and nothing for a name it does not hold"() {
        given:
        def codeSteps = new CodeSteps([SpecCodeStep.SEND_REPLY, ARCHIVE])

        expect:
        codeSteps.declaration(name) == Optional.ofNullable(expected)

        where:
        name         || expected
        "send_reply" || SpecCodeStep.SEND_REPLY.declaration()
        "archive"    || ARCHIVE.declaration()
        "close"      || null
        "Send_Reply" || null
        "send-reply" || null
    }

    def "answers the code step it holds under a name, spelt as held, and none under a name it does not hold"() {
        given:
        def codeSteps = new CodeSteps([SpecCodeStep.SEND_REPLY, ARCHIVE])

        expect:
        codeSteps.held(name) == Optional.ofNullable(expected)

        where:
        name         || expected
        "send_reply" || SpecCodeStep.SEND_REPLY
        "archive"    || ARCHIVE
        "close"      || null
        "Send_Reply" || null
    }

    def "answers every code step it holds by name in name order, whatever order they were gathered in, and none where it holds none"() {
        when:
        def declared = new CodeSteps(held).declarations()

        then:
        declared.keySet() as List == names
        declared.values() as List == names.collect { it == "archive" ? ARCHIVE.declaration() : SpecCodeStep.SEND_REPLY.declaration() }

        where:
        held                               || names
        [SpecCodeStep.SEND_REPLY, ARCHIVE] || ["archive", "send_reply"]
        [ARCHIVE, SpecCodeStep.SEND_REPLY] || ["archive", "send_reply"]
        []                                 || []
    }
}
