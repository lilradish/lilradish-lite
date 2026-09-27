package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.fixture.Runs.MODEL
import static org.lilradish.lite.domain.run.fixture.Runs.model
import static org.lilradish.lite.domain.run.fixture.Runs.person
import static org.lilradish.lite.domain.run.fixture.Runs.question

import spock.lang.Specification

class EngineActSpec extends Specification {

    def "a try asked for is refused numbered below one"() {
        when:
        new EngineAct.StartTry(question(1, person(), 1), number)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineAct.StartTry number must be positive: " + number

        where:
        number << [0, -1]
    }

    def "a try sent is refused numbered below one"() {
        when:
        new EngineAct.Send(question(1, model(), 1), number)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineAct.Send number must be positive: " + number

        where:
        number << [0, -1]
    }

    def "a try sent to be reviewed is refused numbered below one"() {
        when:
        new EngineAct.Review(question(1, model(), 1, MODEL), number)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "EngineAct.Review number must be positive: " + number

        where:
        number << [0, -1]
    }
}
