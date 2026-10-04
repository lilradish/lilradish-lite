package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.MODEL
import static org.lilradish.lite.domain.run.fixture.Runs.RUN
import static org.lilradish.lite.domain.run.fixture.Runs.model
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.question
import static org.lilradish.lite.domain.run.fixture.Runs.routeStep
import static org.lilradish.lite.domain.run.fixture.Runs.workflowStep

import spock.lang.Specification

class EngineActSpec extends Specification {

    def "a try asked for is refused numbered below one"() {
        when:
        new EngineAct.StartTry(RUN, question(1, person(), 1), number)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineAct.StartTry number must be positive: " + number

        where:
        number << [0, -1]
    }

    def "a production sent or reviewed names its identity"() {
        given:
        def identity = new ProductionId(UUID.randomUUID())
        def planned = question(1, model(), 1, MODEL)

        expect:
        new EngineAct.Send(RUN, planned, identity).aTry() == identity
        new EngineAct.Review(RUN, planned, identity).aTry() == identity
    }

    def "acts on the same planned step keep their runs distinct"() {
        given:
        def planned = question(1, person(), 1)
        def child = new RunId(UUID.randomUUID())

        expect:
        new EngineAct.StartTry(RUN, planned, 1) != new EngineAct.StartTry(child, planned, 1)
        new EngineAct.Nothing(RUN) != new EngineAct.Nothing(child)
    }

    def "an unclaimed discriminator stays out of the act's diagnostic text"() {
        given:
        def planned = routeStep(1)
        def act = new EngineAct.FailUnclaimed(RUN, planned, "private value")

        expect:
        act.said() == "private value"
        !act.toString().contains("private value")
    }

    def "a production sent or reviewed without its identity is refused"() {
        when:
        if (reviewing) {
            new EngineAct.Review(RUN, question(1, model(), 1, MODEL), null)
        } else {
            new EngineAct.Send(RUN, question(1, model(), 1), null)
        }

        then:
        def refused = thrown(NullPointerException)
        refused.message == "EngineAct.${reviewing ? 'Review' : 'Send'} aTry must not be null"

        where:
        reviewing << [false, true]
    }

    def "a step fails for going too deep only where it would start a run beneath"() {
        when:
        def act = new EngineAct.FailTooDeep(RUN, planned)

        then:
        act.step() == planned
        act.run() == RUN

        where:
        planned << [workflowStep(1), routeStep(1)]
    }

    def "a step that would start no run beneath is refused as going too deep"() {
        when:
        new EngineAct.FailTooDeep(RUN, planned)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Only a workflow or route starts a run beneath to go too deep"

        where:
        planned << [question(1, person(), 1), question(1, model(), 1)]
    }
}
