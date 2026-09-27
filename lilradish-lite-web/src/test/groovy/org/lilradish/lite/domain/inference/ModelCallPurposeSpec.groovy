package org.lilradish.lite.domain.inference

import spock.lang.Specification

class ModelCallPurposeSpec extends Specification {

    /**
     * Pinned whole and in order: the store's vocabulary is held to this declaration label by label,
     * so a purpose added, dropped or moved here is a change to what the store accepts as well.
     */
    def "a model is called to produce, to review or to help, in that declared order"() {
        expect:
        ModelCallPurpose.values().toList() == [ModelCallPurpose.PRODUCE, ModelCallPurpose.REVIEW, ModelCallPurpose.HELP]
    }
}
