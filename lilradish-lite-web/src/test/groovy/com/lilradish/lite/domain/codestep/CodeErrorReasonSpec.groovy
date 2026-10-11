package com.lilradish.lite.domain.codestep

import spock.lang.Specification

class CodeErrorReasonSpec extends Specification {

    static final Set<CodeErrorReason> FOUND_IN_WHAT_CAME_BACK = EnumSet.of(CodeErrorReason.NOT_DECLARED,
            CodeErrorReason.NOTHING_GIVEN, CodeErrorReason.NOT_ITS_KIND, CodeErrorReason.LINE_BREAK, CodeErrorReason.TOO_LONG, CodeErrorReason.TOO_MANY,
            CodeErrorReason.NOT_A_TERM, CodeErrorReason.UNKEEPABLE, CodeErrorReason.TOO_LONG_TO_KEEP,
            CodeErrorReason.FILE_TYPE_UNUSABLE, CodeErrorReason.FILE_NAME_UNUSABLE,
            CodeErrorReason.FILE_TOO_LARGE, CodeErrorReason.FILE_TOTAL_TOO_LARGE)

    def "each code error reason publishes its declared spelling"() {
        given:
        def expected = [
                (CodeErrorReason.GAVE_NOTHING): "gave_nothing",
                (CodeErrorReason.NOT_DECLARED): "not_declared",
                (CodeErrorReason.NOTHING_GIVEN): "nothing_given",
                (CodeErrorReason.NOT_ITS_KIND): "not_its_kind",
                (CodeErrorReason.LINE_BREAK): "line_break",
                (CodeErrorReason.TOO_LONG): "too_long",
                (CodeErrorReason.TOO_MANY): "too_many",
                (CodeErrorReason.NOT_A_TERM): "not_a_term",
                (CodeErrorReason.UNKEEPABLE): "unkeepable",
                (CodeErrorReason.TOO_LONG_TO_KEEP): "too_long_to_keep",
                (CodeErrorReason.FILE_TYPE_UNUSABLE): "file_type_unusable",
                (CodeErrorReason.FILE_NAME_UNUSABLE): "file_name_unusable",
                (CodeErrorReason.FILE_TOO_LARGE): "file_too_large",
                (CodeErrorReason.FILE_TOTAL_TOO_LARGE): "file_total_too_large",
                (CodeErrorReason.TAKES_A_LIST_NOT_HERE): "takes_a_list_not_here",
                (CodeErrorReason.GIVES_A_LIST_NOT_HERE): "gives_a_list_not_here",
                (CodeErrorReason.TAKES_OTHERWISE): "takes_otherwise",
                (CodeErrorReason.GIVES_OTHERWISE): "gives_otherwise",
                (CodeErrorReason.FAILED_ON_THIS_SIDE): "failed_on_this_side",
                (CodeErrorReason.SAID_NOTHING): "said_nothing"
        ]

        expect:
        CodeErrorReason.values().collectEntries { [(it): it.published()] } == expected
    }

    /** Only these are found in something the code gave back, so only for these is nothing kept something lost. */
    def "#reason is about what came back exactly where it is found reading something the code gave back"() {
        expect:
        reason.aboutWhatCameBack() == (reason in FOUND_IN_WHAT_CAME_BACK)

        where:
        reason << CodeErrorReason.values()
    }
}
