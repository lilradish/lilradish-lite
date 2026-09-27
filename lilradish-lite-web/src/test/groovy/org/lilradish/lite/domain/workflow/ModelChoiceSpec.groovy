package org.lilradish.lite.domain.workflow

import org.lilradish.lite.domain.model.DeployedModel
import org.lilradish.lite.domain.model.ModelCatalog
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.domain.model.ModelName
import spock.lang.Specification

class ModelChoiceSpec extends Specification {

    static final ModelCatalog HELD = new ModelCatalog([
            new DeployedModel(new ModelName("general"), [new ModelMode("research")], 1_000, 4.0G, 100, []),
            new DeployedModel(new ModelName("small"), [], 1_000, 4.0G, 100, [])])

    def "a choice may name no mode, the model running as it is"() {
        when:
        new ModelChoice(new ModelName("general"), null)

        then:
        noExceptionThrown()
    }

    def "a choice names its model"() {
        when:
        new ModelChoice(null, null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ModelChoice model must not be null"
    }

    /** What the deployment holds now decides it, so a choice once held can fall out of it by deploying. */
    def "a choice is held where the deployment holds its model and offers its mode, running as it is always offered"() {
        expect:
        new ModelChoice(new ModelName(model), mode == null ? null : new ModelMode(mode)).unheldBy(HELD) == unheld

        where:
        model     | mode       || unheld
        "general" | null       || null
        "general" | "research" || null
        "small"   | null       || null
        "general" | "fast"     || ModelChoice.Unheld.MODE
        "small"   | "research" || ModelChoice.Unheld.MODE
        "large"   | null       || ModelChoice.Unheld.MODEL
        "large"   | "research" || ModelChoice.Unheld.MODEL
    }

    def "whether a choice is held is asked of a catalogue"() {
        when:
        new ModelChoice(new ModelName("general"), null).unheldBy(null)

        then:
        def refused = thrown(NullPointerException)
        refused.message == "ModelChoice catalog must not be null"
    }
}
