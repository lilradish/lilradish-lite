package org.lilradish.lite.app.run;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.run.RunName;

/** Every refusal an act on a run raises, each under the one sentence written for it. */
enum RunRefusal {
    ASK_AGAIN_NOT_OFFERED(
            RefusalCode.ASK_AGAIN_NOT_OFFERED, "This step's next try is only ever somebody's answer to it."),
    CEILING_RAISE_ASKED_BY_ANOTHER(
            RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER, "A raise is withdrawn only by whoever asked for it."),
    CEILING_RAISE_ASKED_BY_CALLER(
            RefusalCode.CEILING_RAISE_ASKED_BY_CALLER, "Nobody approves or refuses a raise they asked for."),
    CEILING_RAISE_NOT_WAITING(RefusalCode.CEILING_RAISE_NOT_WAITING, "That raise is not waiting on anybody."),
    CEILING_UNUSABLE(
            RefusalCode.CEILING_UNUSABLE,
            "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all."),
    CODE_STEP_GIVES_OTHERWISE(
            RefusalCode.CODE_STEP_GIVES_OTHERWISE,
            "What this code step gives back, as this release declares it, no longer matches what the workflow reads"
                    + " from it or the lists this group holds, so it cannot be answered here."),
    ENTRY_STOPPED(
            RefusalCode.ENTRY_STOPPED,
            "What this step runs, or the workflow itself, is stopped, so no try is made on it until that is let go."),
    PROSE_DIRECTION_CONTROL(
            RefusalCode.PROSE_DIRECTION_CONTROL,
            "A reason holds a character that turns the direction text is shown in."),
    PROSE_LINE_BREAK_CRLF(RefusalCode.PROSE_LINE_BREAK_CRLF, "A reason breaks its lines with a carriage return."),
    PROSE_TAG_CHARACTER(RefusalCode.PROSE_TAG_CHARACTER, "A reason holds a tag character."),
    REASON_MISSING(RefusalCode.REASON_MISSING, "A refusal, and an answer, say why."),
    REASON_UNUSABLE(
            RefusalCode.REASON_UNUSABLE,
            "A reason is prose of one to 2048 characters, with something in it that shows."),
    REVIEW_INCOMPLETE(
            RefusalCode.REVIEW_INCOMPLETE, "A review decides every value waiting on it, and names nothing else."),
    REVIEW_NOT_A_PERSONS(RefusalCode.REVIEW_NOT_A_PERSONS, "Those values wait on the model the step names to review."),
    REVIEW_OWN_PRODUCTION(RefusalCode.REVIEW_OWN_PRODUCTION, "Nobody reviews what they produced."),
    RUN_BENEATH_ANOTHER(
            RefusalCode.RUN_BENEATH_ANOTHER, "A run beneath another is changed only with the run at the top."),
    RUN_NAME_UNUSABLE(RefusalCode.RUN_NAME_UNUSABLE, RunName.UNUSABLE),
    RUN_NOT_IN_VIEW(RefusalCode.RUN_NOT_IN_VIEW, "That run is not in view."),
    RUN_STOPPED(RefusalCode.RUN_STOPPED, "A stopped run takes no answer, review or try until it is opened again."),
    STEP_MOVED_ON(RefusalCode.STEP_MOVED_ON, "That step has moved on since it was read."),
    STEP_NOT_IN_VIEW(RefusalCode.STEP_NOT_IN_VIEW, "That step is not in view."),
    TRY_SENDING_NOT_OFFERED(
            RefusalCode.TRY_SENDING_NOT_OFFERED,
            "Try sending is only for a call to a model that could not be sent: the model turned it away, it was"
                    + " too long for the model, or it is to a model, or a mode of one, that this deployment does not"
                    + " offer."),
    VALUES_UNSHAPED(
            RefusalCode.BODY_UNUSABLE,
            "This takes a JSON object holding what is given for every field the answer gives back, and why.");

    private final RefusalCode code;

    private final String sentence;

    RunRefusal(RefusalCode code, String sentence) {
        this.code = code;
        this.sentence = sentence;
    }

    ApiErrorException raised() {
        return raised(null);
    }

    ApiErrorException raised(@Nullable Throwable cause) {
        if (this == CODE_STEP_GIVES_OTHERWISE) {
            throw new IllegalStateException(
                    "A code step giving otherwise is refused only through givesOtherwise, naming what found it so");
        }
        return new ApiErrorException(code, sentence, cause);
    }

    /** Answering here refused for {@code fault}, which the refusal carries to be named beside it. */
    static ApiErrorException givesOtherwise(CodeError.Fault fault) {
        return new GivesOtherwiseRefusal(CODE_STEP_GIVES_OTHERWISE.code, CODE_STEP_GIVES_OTHERWISE.sentence, fault);
    }

    /** The refusal a domain rule answered with, which has to be one an act on a run raises. */
    static RunRefusal answering(RefusalCode code) {
        for (RunRefusal refusal : values()) {
            if (refusal.code == code) {
                return refusal;
            }
        }
        throw new IllegalArgumentException("No act on a run raises " + code);
    }
}
