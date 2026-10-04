package org.lilradish.lite.app.library

import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.TextLines
import org.lilradish.lite.domain.model.ModelToolName
import org.lilradish.lite.domain.search.SearchWay
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.DeployedModels
import spock.lang.Specification

/**
 * What a code step this release holds declares, and what a way to search gives back, keyed as nothing in the store
 * keys it: a key is drawn from the name, the half and the path to the field, so the same field is the same place at
 * every reading.
 */
class StoredDeclarationsSpec extends Specification {

    static final SpecCodeStep FILING = new SpecCodeStep("file_claim",
            [SpecCodeStep.given("claim", new FieldShape.Nested([
                    SpecCodeStep.given("amount", new FieldShape.Text(20, TextLines.ONE)),
                    SpecCodeStep.given("reason", new FieldShape.Text(500))]))],
            [SpecCodeStep.standing("claim", new FieldShape.Text(64))],
            false)

    def "holds each half of a code step's declaration as the release declares it"() {
        when:
        def halves = StoredDeclarations.ofCodeStep("file_claim", FILING.declaration())

        then:
        halves.takes().declaration().is(FILING.declaration().takes())
        halves.gives().declaration().is(FILING.declaration().gives())
        halves.takes().declaration().fields()[0].shape().fields()*.shape()*.lines() == [TextLines.ONE, TextLines.SEVERAL]
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

    /** The keys are published with what a way gives back and a problem is placed by them, so they never move. */
    def "holds what a way to search gives back as the release declares it, each field under the key it always had"() {
        when:
        def gives = StoredDeclarations.ofSearchWay(DeployedModels.WEB)

        then:
        gives.declaration().side() == DeclarationSide.GIVES
        gives.declaration().fields() == DeployedModels.WEB.outputs()
        gives.keys() == [
                "391c8551-d58a-3416-99ae-b7386d0d9065",
                "b28a871f-1fba-3756-b9a6-807f4b4df830",
                "f93bb658-be60-331d-8251-61180a1c2f95",
                "8a58c410-766e-35b6-a291-ecb53fe99577"].collect { new StoredDeclarations.Keyed(UUID.fromString(it), []) }
    }

    /** Keyed by the way's name and each field's alone: a release changing a limit or a tool moves no key. */
    def "keys a way's fields alike whatever it needs or holds them to, and apart from another way's and a code step's"() {
        given:
        def gives = StoredDeclarations.ofSearchWay(DeployedModels.WEB)

        expect:
        StoredDeclarations.ofSearchWay(new SearchWay("web", new ModelToolName("other_tool"), 1, 1, 1, 1)).keys() == gives.keys()
        StoredDeclarations.ofSearchWay(new SearchWay("news", DeployedModels.WEB.requiredTool(), 200, 4000, 2000, 1000)).keys()
                .disjoint(gives.keys())
        StoredDeclarations.ofCodeStep("web", new SpecCodeStep("web", [], [
                SpecCodeStep.standing("title", new FieldShape.Text(200))], false).declaration()).gives().keys()
                .disjoint(gives.keys())
    }
}
