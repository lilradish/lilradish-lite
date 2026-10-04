package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.run.RunStepFailureReason.MODEL_NOT_DEPLOYED
import static org.lilradish.lite.domain.run.RunStepFailureReason.SOURCE_LIST_NOT_HERE
import static org.lilradish.lite.domain.run.RunStepFailureReason.SOURCE_NO_LONGER_MATCHES
import static org.lilradish.lite.domain.run.RunStepFailureReason.TOOL_NOT_OFFERED
import static org.lilradish.lite.domain.run.RunStepFailureReason.TOO_DEEP
import static org.lilradish.lite.domain.run.RunStepFailureReason.UNCLAIMED_VALUE
import static org.lilradish.lite.domain.run.RunStepFailureReason.UNCUTTABLE_LENGTH

import spock.lang.Specification

class RunStepFailureReasonSpec extends Specification {

    def "each reason a failure is written down for is published under the spelling a reader words it by, in this order and no other"() {
        expect:
        RunStepFailureReason.values().collect { [it, it.published()] } == [
                [UNCUTTABLE_LENGTH, "uncuttable_length"],
                [UNCLAIMED_VALUE, "unclaimed_value"],
                [MODEL_NOT_DEPLOYED, "model_not_deployed"],
                [TOO_DEEP, "too_deep"],
                [TOOL_NOT_OFFERED, "tool_not_offered"],
                [SOURCE_NO_LONGER_MATCHES, "source_no_longer_matches"],
                [SOURCE_LIST_NOT_HERE, "source_list_not_here"],
        ]
    }
}
