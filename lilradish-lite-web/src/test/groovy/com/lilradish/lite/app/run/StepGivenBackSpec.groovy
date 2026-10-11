package com.lilradish.lite.app.run

import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.run
import static com.lilradish.lite.domain.run.fixture.Runs.searchStep
import static com.lilradish.lite.domain.run.fixture.Runs.stepId
import static com.lilradish.lite.domain.run.fixture.Runs.unstarted

import com.lilradish.lite.app.run.fixture.ChildComposite
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.run.RunInTree
import com.lilradish.lite.domain.run.StepInputs
import org.mockito.Mockito
import spock.lang.Specification
import tools.jackson.databind.node.JsonNodeFactory

/** What a step gives back, from its real reading, with each person's parts beside withheld model content. */
class StepGivenBackSpec extends Specification {

    static final JsonNodeFactory NODES = JsonNodeFactory.instance

    static final Set<GroupPermission> READS_MODELS = EnumSet.of(GroupPermission.READ_ALL_RUNS,
            GroupPermission.ANSWER_STEP, GroupPermission.REVIEW_AT_GATE, GroupPermission.READ_INFERENCE_CONTENT)

    static final Set<GroupPermission> READS_NO_MODEL = READS_MODELS - GroupPermission.READ_INFERENCE_CONTENT

    def "a child's composite outputs keep person parts beside withheld model text with or without a code declaration"() {
        given:
        def scenario = ChildComposite.of(codeNow)
        def context = scenario.context()
        def parentStep = scenario.parentStep()
        def nameSource = scenario.nameSource()
        def idSource = scenario.idSource()
        def parentRun = new RunReadDetails.NumberedRunRecord(context.above().run().run(), 7)
        def modelOrigin = new RunReadDetails.OriginRecord("step", null, parentRun,
                new RunReadDetails.MakerRecord(parentRun, stepId(1).value(), "step_1", "answer", 1))
        def personOrigin = new RunReadDetails.OriginRecord("step", null, parentRun,
                new RunReadDetails.MakerRecord(parentRun, stepId(2).value(), "step_2", "answer", 1))
        def parts = ["id", "name", "type", "size", "secret"].collect { name ->
            new RunReadDetails.PartSourceRecord(name, name == "secret" ? modelOrigin : personOrigin)
        }
        def details = Mockito.mock(RunReadDetails)
        Mockito.when(details.costBeneath(Mockito.anyList())).thenReturn([:])
        Mockito.when(details.parts(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyList())).thenReturn(parts)
        Mockito.when(details.planned(modelOrigin.maker())).thenReturn(nameSource.planned())
        Mockito.when(details.planned(personOrigin.maker())).thenReturn(idSource.planned())
        Mockito.when(details.origin(Mockito.any(), Mockito.any(StepInputs.BindingRecord) as StepInputs.BindingRecord))
                .thenAnswer { call ->
                    call.getArgument(1).binding().target().published().endsWith("secret") ? modelOrigin : personOrigin
                }

        when:
        def hiddenCame = new StepReading(context.above(), 7, true, READS_NO_MODEL, Optional.empty(),
                [:], [:], [:], [:], Deployments.DEEP_ENOUGH).withDetails(details).stepGivenBack.cameOutBeneath(parentStep)[0]
        def shownCame = new StepReading(context.above(), 7, true, READS_MODELS, Optional.empty(),
                [:], [:], [:], [:], Deployments.DEEP_ENOUGH).withDetails(details).stepGivenBack.cameOutBeneath(parentStep)[0]

        then:
        hiddenCame.field() == "customer"
        hiddenCame.standing().value() == NODES.objectNode()
                .put("id", "123").put("name", "123").put("type", "123")
                .put("size", "123").set("secret", NODES.objectNode().put("withheld", true))
        hiddenCame.standing().withheld() == null
        hiddenCame.standing().number() == null
        hiddenCame.fromRun() == null
        hiddenCame.from() == null
        hiddenCame.sources()*.targetPath() == ["id", "name", "type", "size", "secret"]
        hiddenCame.sources()*.from()*.stepId() == [stepId(2).value()] * 4 + [stepId(1).value()]
        shownCame.standing().value() == NODES.objectNode().put("id", "123").put("name", "123")
                .put("type", "123").put("size", "123").put("secret", "Ari")
        shownCame.standing().withheld() == null
        shownCame.sources() == hiddenCame.sources()
        !hiddenCame.toString().contains("Ari")
        hiddenCame.toString().contains('"id":"123"')

        where:
        codeNow << [false, true]
    }

    def "released search and question outputs can be read before a step is reached"() {
        given:
        def searching = unstarted(searchStep(1))
        def stepReading = new StepReading(RunInTree.root(run([searching, unstarted(question(2, person(), 1))])),
                7, true, READS_MODELS, Optional.empty(), [:], [:], [:], [:], Deployments.DEEP_ENOUGH)

        expect:
        stepReading.stepGivenBack.reading(searching.planned()) != null
        stepReading.stepGivenBack.reading(question(2, person(), 1)) != null
    }
}
