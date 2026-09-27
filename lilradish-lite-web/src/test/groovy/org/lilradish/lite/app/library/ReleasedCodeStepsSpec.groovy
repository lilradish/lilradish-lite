package org.lilradish.lite.app.library

import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

class ReleasedCodeStepsSpec extends Specification {

    static final SpecCodeStep ARCHIVE = new SpecCodeStep("archive",
            [SpecCodeStep.given("ticket", new FieldShape.Text(64))], [], true)

    def "answers what the release declares of each code step it holds, keyed as a stored declaration of it is, and none of another"() {
        given:
        def released = new ReleasedCodeSteps(new CodeSteps([SpecCodeStep.SEND_REPLY, ARCHIVE]))

        expect:
        released.of("send_reply") == StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())
        released.of("archive") == StoredDeclarations.ofCodeStep("archive", ARCHIVE.declaration())
        released.of("close_ticket") == null
        released.of("Archive") == null
    }

    def "answers none of any name where the release holds no code step"() {
        expect:
        new ReleasedCodeSteps(new CodeSteps([])).of("send_reply") == null
    }
}
