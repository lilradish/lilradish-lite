package org.lilradish.lite.app.library

import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

/**
 * What a code step this release holds declares, keyed as nothing in the store keys it: a key is drawn from the code
 * step's name, the half and the path to the field, so the same field is the same place at every reading.
 */
class StoredDeclarationsSpec extends Specification {

    static final SpecCodeStep FILING = new SpecCodeStep("file_claim",
            [SpecCodeStep.given("claim", new FieldShape.Nested([
                    SpecCodeStep.given("amount", new FieldShape.Text(20)),
                    SpecCodeStep.given("reason", new FieldShape.Text(500))]))],
            [SpecCodeStep.standing("claim", new FieldShape.Text(64))],
            false)

    def "holds each half of a code step's declaration as the release declares it"() {
        when:
        def halves = StoredDeclarations.ofCodeStep("file_claim", FILING.declaration())

        then:
        halves.takes().declaration().is(FILING.declaration().takes())
        halves.gives().declaration().is(FILING.declaration().gives())
    }

    def "keys every field at every depth, each by the key the same field is keyed by at every reading, and no two alike"() {
        when:
        def halves = StoredDeclarations.ofCodeStep("file_claim", FILING.declaration())
        def again = StoredDeclarations.ofCodeStep("file_claim", FILING.declaration())
        def keys = [halves.takes().keyAt([0]), halves.takes().keyAt([0, 0]), halves.takes().keyAt([0, 1]),
                    halves.gives().keyAt([0])]

        then:
        halves.takes().keys()*.fields()*.size() == [2]
        halves.gives().keys()*.fields()*.size() == [0]
        again == halves
        keys.toSet().size() == keys.size()
        keys.every { it.version() == 3 }
    }

    /** A field of one name in both halves, or in two code steps, is two places, and is keyed as two. */
    def "keys a field of one name apart in another half and in another code step"() {
        when:
        def filing = StoredDeclarations.ofCodeStep("file_claim", FILING.declaration())
        def other = StoredDeclarations.ofCodeStep("refile_claim", FILING.declaration())

        then:
        filing.takes().keyAt([0]) != filing.gives().keyAt([0])
        filing.takes().keyAt([0]) != other.takes().keyAt([0])
        filing.gives().keyAt([0]) != other.gives().keyAt([0])
    }

    def "refuses to key a declaration it is not given"() {
        when:
        StoredDeclarations.ofCodeStep("file_claim", null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "StoredDeclarations declared must not be null"
    }
}
