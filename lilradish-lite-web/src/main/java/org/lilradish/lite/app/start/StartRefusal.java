package org.lilradish.lite.app.start;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.run.RunName;

/** Every refusal starting a run raises, each under the one sentence written for it. */
enum StartRefusal {
    BODY_UNUSABLE(
            RefusalCode.BODY_UNUSABLE,
            "This takes a JSON object holding a run's name, the version it runs and a value for each field that"
                    + " version takes, and nothing else."),
    RUN_NAME_UNUSABLE(RefusalCode.RUN_NAME_UNUSABLE, RunName.UNUSABLE),
    WORKFLOW_NOT_OFFERED(
            RefusalCode.WORKFLOW_NOT_OFFERED,
            "Only a version in service of a workflow of this group's, not stopped, may be started.");

    private final RefusalCode code;

    private final String sentence;

    StartRefusal(RefusalCode code, String sentence) {
        this.code = code;
        this.sentence = sentence;
    }

    String sentence() {
        return sentence;
    }

    ApiErrorException raised() {
        return raised(null);
    }

    ApiErrorException raised(@Nullable Throwable cause) {
        return new ApiErrorException(code, sentence, cause);
    }
}
