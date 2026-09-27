package org.lilradish.lite.domain.codestep

import spock.lang.Specification

class CodeErrorReasonSpec extends Specification {

    static final Set<CodeErrorReason> FOUND_IN_WHAT_CAME_BACK = EnumSet.of(CodeErrorReason.NOT_DECLARED,
            CodeErrorReason.NOTHING_GIVEN, CodeErrorReason.NOT_ITS_KIND, CodeErrorReason.TOO_LONG, CodeErrorReason.TOO_MANY,
            CodeErrorReason.NOT_A_TERM, CodeErrorReason.UNKEEPABLE, CodeErrorReason.TOO_LONG_TO_KEEP)

    /** Only these are found in something the code gave back, so only for these is nothing kept something lost. */
    def "#reason is about what came back exactly where it is found reading something the code gave back"() {
        expect:
        reason.aboutWhatCameBack() == (reason in FOUND_IN_WHAT_CAME_BACK)

        where:
        reason << CodeErrorReason.values()
    }
}
