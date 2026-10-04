package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.app.filling.FillFieldAnswer;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.domain.codestep.CodeError;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.run.RunStepHoldReason;
import org.lilradish.lite.domain.run.StepAct;
import org.lilradish.lite.domain.workflow.Pointer;
import tools.jackson.databind.JsonNode;

/**
 * What a run's steps are read as, and what every act on a step answers with, each from one moment of the store;
 * every list is in the order it means, and spend goes out as digits in a string.
 */
final class StepAnswers {

    private StepAnswers() {}

    /**
     * Both ways of drawing a run: its header, what it gave back, and every step in order.
     *
     * @param declarations what each version named here declares, by its identifier: the run's own, and each
     *     one a step pins; and what each code step a step runs declares, by its name, where the release holds it
     * @param rereadAfterSeconds how long after this read to read it again, only while it is running
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunStepsAnswer(
            HeaderAnswer run,
            Map<String, DeclaredAnswer> declarations,
            GaveBackAnswer gaveBack,
            List<StepRowAnswer> steps,
            @Nullable Integer rereadAfterSeconds) {}

    /**
     * One step's page, which every act on a step answers with.
     *
     * @param declarations as {@link RunStepsAnswer#declarations} has them
     * @param wentIn absent where nothing has gone in yet, nor could
     * @param wentInFrom as {@link WentInFrom} spells it
     * @param cameOut absent where it has produced nothing
     * @param answering only where the reader may answer it
     * @param drawing how the pinned question version lays out its inputs, only where it says
     * @param rereadAfterSeconds as {@link RunStepsAnswer#rereadAfterSeconds} has it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StepAnswer(
            HeaderAnswer run,
            Map<String, DeclaredAnswer> declarations,
            StepRowAnswer step,
            @Nullable List<WentInAnswer> wentIn,
            @Nullable String wentInFrom,
            @Nullable List<CameOutAnswer> cameOut,
            List<TryAnswer> triesMade,
            @Nullable AnsweringAnswer answering,
            @Nullable String drawing,
            @Nullable Integer rereadAfterSeconds) {

        StepAnswer withDrawing(String drawing) {
            return new StepAnswer(
                    run,
                    declarations,
                    step,
                    wentIn,
                    wentInFrom,
                    cameOut,
                    triesMade,
                    answering,
                    drawing,
                    rereadAfterSeconds);
        }
    }

    /**
     * The run as the same read of the store has it.
     *
     * @param at the step it is on, only while it is running
     * @param acts what the reader may do to the run itself now
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record HeaderAnswer(
            UUID runId,
            int number,
            UUID versionId,
            String state,
            RunsController.@Nullable AtAnswer at,
            List<String> acts,
            ProgressAnswer progress) {}

    record ProgressAnswer(int done, int of) {}

    /** What a version takes and gives back, each field as whoever fills it reads it. */
    record DeclaredAnswer(List<FillFieldAnswer> takes, List<FillFieldAnswer> gives) {}

    /**
     * @param declares {@code nothing} for a workflow that acts rather than answers, {@code values} otherwise
     * @param standing each value it gives back that stands, empty where none stands yet
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GaveBackAnswer(String declares, @Nullable List<GivenValueAnswer> standing) {}

    /**
     * @param field what the run's own version calls the field it gives back, its names joined by dots
     * @param value as {@link ShownValueAnswer#value} has it, as is {@code withheld}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GivenValueAnswer(
            String field,
            @Nullable JsonNode value,
            @Nullable Boolean withheld) {}

    /**
     * One step as both ways of drawing a run list it.
     *
     * @param order where it runs, counted from one
     * @param producer absent where what it runs produces nothing of its own
     * @param reviewer a model where one reviews in a person's stead, otherwise a person where any field it gives
     *     back may wait on a review; absent where every one stands as given, or none is declared here
     * @param where why it is where it is; absent where it is not started or done
     * @param tries absent where it makes none
     * @param gaveBack what its newest try gave back; absent until one did
     * @param wentIn what went into it, only where a value of it waits on this reader's review
     * @param next the try an answer or a next asking makes, only where the step owes one
     * @param acts what the reader may do to it now
     * @param withheld what it would offer somebody and does not offer this reader, and why
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StepRowAnswer(
            UUID stepId,
            int order,
            String name,
            RunsAnswer runs,
            @Nullable BeneathAnswer beneath,
            @Nullable WhoAnswer producer,
            @Nullable WhoAnswer reviewer,
            String state,
            @Nullable WhereAnswer where,
            List<TakesFromAnswer> takesFrom,
            @Nullable TriesAnswer tries,
            CostAnswer cost,
            @Nullable List<ShownValueAnswer> gaveBack,
            @Nullable List<WentInAnswer> wentIn,
            @Nullable String wentInFrom,
            @Nullable NextAnswer next,
            List<String> acts,
            List<WithheldAnswer> withheld) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BeneathAnswer(
            UUID runId,
            int number,
            RunsAnswer runs,
            @Nullable BeneathAtAnswer at) {}

    record BeneathAtAnswer(UUID runId, int number, UUID stepId, String name) {}

    /**
     * What a step runs: a question or a workflow at the version pinned, a code step by its name, or a route.
     *
     * @param kind {@code question}, {@code workflow}, {@code code_step} or {@code route}
     * @param version the number of the version pinned
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RunsAnswer(
            String kind,
            @Nullable UUID entryId,
            @Nullable String name,
            @Nullable Integer version,
            @Nullable UUID versionId,
            @Nullable String codeStep,
            @Nullable String searchWay,
            @Nullable String tool) {}

    /**
     * Who or what produced or reviewed: a person, code, or a model in a mode.
     *
     * @param kind {@code person}, {@code code} or {@code model}
     * @param person which person, where one is named
     * @param mode absent where the model runs as it is
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WhoAnswer(
            String kind,
            @Nullable PersonAnswer person,
            @Nullable String model,
            @Nullable String mode) {}

    /**
     * Why a step is where it is, by {@link WhereKind}: held back with its reason and the stop holding it, waiting
     * on a review with the try and the values waiting, owed a try with whether it is asked already, failed with
     * its reason, or running with what is out.
     *
     * @param reason why it is held back, as {@code RunStepHoldReason} publishes it, or why it failed, as
     *     {@link FailureReason} does
     * @param spentUp present where the model turned it away, or turned away the review of values waiting on it, as
     *     what may be spent with it is used up
     * @param model the model a step failed for not being held, only where it did
     * @param mode the mode that model was named in, absent where it runs as it is
     * @param reviewing present where that model was the one to review, and absent where it was the one to produce
     * @param stopped the stop in force on what the step runs where it keeps a production from being sent again;
     *     absent otherwise, and on a review's rows
     * @param turnedAway each time a call to produce the try a step is held back on was turned away, oldest first
     * @param waitsOn whom it waits on, absent where it waits on nobody, which is everywhere on a stopped run
     * @param review how values waiting on a review stand with the model the step names to review them, as
     *     {@code ReviewSending} publishes it; absent where no model is named
     * @param failedUnclaimed the value no case claimed, or that it is withheld, exactly where that is why it failed
     * @param failedTooDeep how deep runs may go, exactly where a run that deep is why it failed
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WhereAnswer(
            String kind,
            @Nullable String reason,
            @Nullable Boolean spentUp,
            @Nullable String model,
            @Nullable String mode,
            @Nullable Boolean reviewing,
            @Nullable Integer number,
            @Nullable List<WaitingValueAnswer> values,
            @Nullable Boolean open,
            @Nullable Boolean beyond,
            @Nullable String on,
            @Nullable String since,
            @Nullable StoppedAnswer stopped,
            @Nullable List<TurnawayAnswer> turnedAway,
            @Nullable String waitsOn,
            @Nullable String review,
            @Nullable UnclaimedAnswer failedUnclaimed,
            @Nullable TooDeepAnswer failedTooDeep,
            @Nullable String tool,
            @Nullable String searchQuestion,
            @Nullable Boolean searchQuestionWithheld,
            @Nullable HeldSourceAnswer source,
            @Nullable SourceNoLongerMatchesAnswer failedSourceNoLongerMatches,
            @Nullable SourceNoLongerMatchesAnswer failedSourceListNotHere) {

        // A reader refuses a row whose reason and these disagree, so a mismatch fails here rather than there.
        WhereAnswer {
            if ((failedSourceNoLongerMatches != null)
                            != FailureReason.SOURCE_NO_LONGER_MATCHES
                                    .published()
                                    .equals(reason)
                    || (failedSourceNoLongerMatches != null
                            && !WhereKind.FAILED.published().equals(kind))) {
                throw new IllegalArgumentException("A source no longer matching is named exactly where it failed");
            }
            if ((failedSourceListNotHere != null)
                            != FailureReason.SOURCE_LIST_NOT_HERE.published().equals(reason)
                    || (failedSourceListNotHere != null
                            && !WhereKind.FAILED.published().equals(kind))) {
                throw new IllegalArgumentException("An unavailable source list is named exactly where it failed");
            }
            if (source != null
                    && (!WhereKind.HELD_BACK.published().equals(kind)
                            || !RunStepHoldReason.CODE_STEP_NOT_HELD.published().equals(reason))) {
                throw new IllegalArgumentException("Only a missing source code step names a held source");
            }
            if ((tool != null) != FailureReason.TOOL_NOT_OFFERED.published().equals(reason)
                    || (tool != null
                            && (!WhereKind.FAILED.published().equals(kind) || model == null || reviewing != null))) {
                throw new IllegalArgumentException("A tool failure names its tool and producing model");
            }
            boolean heldQuestion = searchQuestion != null || searchQuestionWithheld != null;
            if (heldQuestion
                    && (!WhereKind.HELD_BACK.published().equals(kind)
                            || !(RunStepHoldReason.TOO_LONG.published().equals(reason)
                                    || RunStepHoldReason.TURNED_AWAY.published().equals(reason))
                            || (searchQuestion != null) == (searchQuestionWithheld != null)
                            || Boolean.FALSE.equals(searchQuestionWithheld))) {
                throw new IllegalArgumentException(
                        "A held search question is named or withheld only where its attempt is held");
            }
            if ((failedUnclaimed != null)
                            != FailureReason.UNCLAIMED_VALUE.published().equals(reason)
                    || (failedTooDeep != null)
                            != FailureReason.TOO_DEEP.published().equals(reason)) {
                throw new IllegalArgumentException(
                        "A step's whereabouts name the value no case claimed, or how deep runs may go, exactly where"
                                + " that is why it failed");
            }
        }
    }

    record HeldSourceAnswer(UUID runId, int runNumber, UUID stepId, String name, String codeStep) {

        HeldSourceAnswer {
            requireNonNull(runId);
            requireNonNull(stepId);
            requireNonNull(name);
            requireNonNull(codeStep);
            if (runNumber < 1) {
                throw new IllegalArgumentException("A held source belongs to a numbered run");
            }
        }
    }

    record SourceNoLongerMatchesAnswer(HeldSourceAnswer source, String field) {

        SourceNoLongerMatchesAnswer {
            requireNonNull(source);
            requireNonNull(field);
        }
    }

    /** The value no case claimed, as the reader may read it: named, or withheld where a model said it. */
    sealed interface UnclaimedAnswer permits UnclaimedAnswer.Named, UnclaimedAnswer.Withheld {

        /** @param term the value, written out as null where nothing was given */
        @JsonInclude(JsonInclude.Include.ALWAYS)
        record Named(@Nullable String term) implements UnclaimedAnswer {}

        /** @param withheld always true: a reader takes a false as a value it may read, which this is not */
        record Withheld(boolean withheld) implements UnclaimedAnswer {

            public Withheld {
                if (!withheld) {
                    throw new IllegalArgumentException("A value no case claimed is withheld only as withheld");
                }
            }
        }
    }

    /**
     * @param most how many runs deep a tree may go as the deployment says now, the run at the top counted as one,
     *     in decimal
     */
    record TooDeepAnswer(String most) {}

    /**
     * One time a model turned a call away, which cost nothing and spent no try.
     *
     * @param at when, ISO 8601
     * @param said what the model said in turning it away; absent where it said nothing, or where the reader may
     *     not read what a model said
     * @param cut whether {@code said} was cut to its bound, present with it
     * @param withheld present where what it said is withheld
     * @param sentAgain whether the call was sent again after it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TurnawayAnswer(
            String at,
            @Nullable String said,
            @Nullable Boolean cut,
            @Nullable Boolean withheld,
            boolean sentAgain) {}

    /**
     * @param what as {@link StoppedWhat} spells it
     * @param by who stopped it, absent where they are no longer to be found
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoppedAnswer(String what, @Nullable PersonAnswer by, String at) {}

    /** @param on {@code review_at_gate} or {@code model} */
    record WaitingValueAnswer(String field, String on) {}

    /** @param input the field it fills, its names from the first level joined by dots */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TakesFromAnswer(
            String input, FromAnswer from, @Nullable UUID sourcePartId) {}

    /**
     * Where an input comes from, by {@link FromKind}: a path into what the run was started with, a path into what
     * an earlier step gave back, or a value written into the workflow.
     *
     * @param versionId the version the earlier step pins, which {@code declarations} holds where it is a question's
     * @param codeStep the code step the earlier step runs, by which {@code declarations} holds what it declares
     *     where the release holds it
     * @param runId the actual run holding this source, absent for a constant or a declared binding
     * @param runNumber present exactly with {@code runId}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record FromAnswer(
            String kind,
            @Nullable String path,
            @Nullable UUID stepId,
            @Nullable String name,
            @Nullable UUID versionId,
            @Nullable String codeStep,
            @Nullable UUID runId,
            @Nullable Integer runNumber,
            @Nullable String searchWay) {

        FromAnswer {
            if ((runId == null) != (runNumber == null)) {
                throw new IllegalArgumentException("A source run has both an identifier and a number");
            }
        }
    }

    /** @param current which try it is on, of the {@code declared} it declares */
    record TriesAnswer(int current, int declared, boolean beyond) {}

    /**
     * What a step or a try cost: nothing where no model is called, and otherwise its calls between them.
     *
     * @param measuredHere wholly or partly measured here; absent where no call was measured here
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CostAnswer(
            boolean callsAModel,
            @Nullable String sent,
            @Nullable String cameBack,
            @Nullable String spent,
            @Nullable Boolean cameBackUnknown,
            @Nullable String measuredHere) {}

    /**
     * One value a try gave back.
     *
     * @param value none as null; absent where it is withheld from this reader
     * @param withheld present where a model produced it and the reader may not read what a model said
     * @param now where it stands within the try it is of
     * @param earlierShape present where the value is of a shape its field no longer has, or of a field no longer
     *     declared, which only a code step's release can change: then it is the value as kept, as JSON text, and
     *     {@code field} the name it was kept under
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ShownValueAnswer(
            String field,
            @Nullable JsonNode value,
            @Nullable Boolean withheld,
            String now,
            @Nullable Boolean earlierShape) {}

    /** @param value as {@link ShownValueAnswer#value} has it, as are {@code withheld} and {@code earlierShape} */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WentInAnswer(
            String input,
            @Nullable FromAnswer from,
            @Nullable JsonNode value,
            @Nullable Boolean withheld,
            @Nullable Boolean earlierShape,
            @Nullable List<SourceAnswer> sources,
            @Nullable UUID sourcePartId) {

        WentInAnswer {
            if ((from == null) == (sources == null) || (sources != null && sources.isEmpty())) {
                throw new IllegalArgumentException("An input has one origin or individual part origins");
            }
        }
    }

    record SourceAnswer(String targetPath, FromAnswer from) {}

    record NextAnswer(int number, boolean beyond) {}

    /**
     * @param refusal the code pressing it would be refused with
     * @param readBy as {@link GivesOtherwiseNames} has it, only where its code step giving otherwise refuses it
     * @param field as {@link GivesOtherwiseNames} has it, only where its code step giving otherwise refuses it;
     *     the code step's own dotted path may not be found in {@code declarations}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WithheldAnswer(
            String act,
            String refusal,
            @Nullable ReadByAnswer readBy,
            @Nullable String field) {

        static WithheldAnswer of(StepAct act, RefusalCode refusal, CodeError.@Nullable Fault givesOtherwise) {
            if (refusal != RefusalCode.CODE_STEP_GIVES_OTHERWISE) {
                return new WithheldAnswer(act.published(), refusal.code(), null, null);
            }
            GivesOtherwiseNames named = GivesOtherwiseNames.of(
                    requireNonNull(givesOtherwise, "a code step refused for giving otherwise says why"));
            return new WithheldAnswer(act.published(), refusal.code(), named.readBy(), named.field());
        }
    }

    /**
     * What a code step refused for giving otherwise is named with, on the refusal and on its step's row alike, as a
     * try of it gone wrong for the same reason names it.
     *
     * @param readBy what reads the field that no longer matches, only where that is why
     * @param field the field of what the code step gives back that no longer matches, or that pins a list this group
     *     does not hold, its own names joined by dots, which may not be found in {@code declarations}
     */
    record GivesOtherwiseNames(@Nullable ReadByAnswer readBy, String field) {

        static GivesOtherwiseNames of(CodeError.Fault fault) {
            return switch (fault.reason()) {
                case GIVES_OTHERWISE ->
                    new GivesOtherwiseNames(
                            ReadByAnswer.of(requireNonNull(fault.readBy(), "giving otherwise names its reader")),
                            new Pointer(fault.path()).published());
                case GIVES_A_LIST_NOT_HERE -> new GivesOtherwiseNames(null, new Pointer(fault.path()).published());
                case GAVE_NOTHING,
                        NOT_DECLARED,
                        NOTHING_GIVEN,
                        NOT_ITS_KIND,
                        LINE_BREAK,
                        TOO_LONG,
                        TOO_MANY,
                        NOT_A_TERM,
                        UNKEEPABLE,
                        TOO_LONG_TO_KEEP,
                        FILE_TYPE_UNUSABLE,
                        FILE_NAME_UNUSABLE,
                        FILE_TOO_LARGE,
                        FILE_TOTAL_TOO_LARGE,
                        TAKES_A_LIST_NOT_HERE,
                        TAKES_OTHERWISE,
                        FAILED_ON_THIS_SIDE,
                        SAID_NOTHING ->
                    throw new IllegalStateException("No code step is refused for giving otherwise over "
                            + fault.reason().published());
            };
        }
    }

    /**
     * @param standing its standing production, only where it stands
     * @param now as {@link CameOutNow} spells it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CameOutAnswer(
            String field,
            @Nullable StandingAnswer standing,
            String now,
            RunsController.@Nullable NumberedRunAnswer fromRun,
            @Nullable FromAnswer from,
            @Nullable List<SourceAnswer> sources) {}

    /**
     * @param number the try it stands in, absent where no try made it
     * @param value as {@link ShownValueAnswer#value} has it, as are {@code withheld} and {@code earlierShape}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StandingAnswer(
            @Nullable Integer number,
            @Nullable JsonNode value,
            @Nullable Boolean withheld,
            @Nullable Boolean earlierShape) {}

    /**
     * One try, as it was asked, produced, reviewed and ended.
     *
     * @param askedBy absent where the system asked
     * @param why why the person producing it said it, where one did
     * @param ended as {@link TryEnded} spells it; of a try that gave values back, one refused on review outranks
     *     one refused for length, which outranks one waiting
     * @param didNotFit why a model's answer did not fit, as {@code DidNotFitReason} publishes it, where it did not
     * @param wentWrong what code said went wrong where it threw, or what a model's call said went wrong, where either
     *     did
     * @param erroredFor why code went wrong, where this system found it so rather than the code saying
     * @param returned what code gave back that did not fit, as it came back
     * @param turnedAway each time a call to produce it was turned away, oldest first; absent where none was
     * @param declined what the model gave back when it declined, or a withheld marker where its words may not be read
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TryAnswer(
            int number,
            boolean beyond,
            @Nullable PersonAnswer askedBy,
            WhoAnswer producedBy,
            @Nullable String why,
            List<TriedValueAnswer> values,
            ReviewAnswer review,
            String ended,
            @Nullable String didNotFit,
            @Nullable WentWrongAnswer wentWrong,
            @Nullable ErroredForAnswer erroredFor,
            @Nullable String returned,
            @Nullable List<KeptCodeFileAnswer> keptFiles,
            @Nullable List<TurnawayAnswer> turnedAway,
            CostAnswer cost,
            @Nullable SearchAnswer search,
            @Nullable DeclinedAnswer declined,
            @Nullable UnreadableResponseRecord unreadableResponse) {}

    /** The bounded original service response for a call whose envelope could not be read. */
    record UnreadableResponseRecord(
            @DoNotLog String text,
            @DoNotLog boolean altered,
            @DoNotLog boolean truncated) {}

    /** Each nonempty part of a model's declined answer, in its original order: words, then content. */
    record DeclinedTextAnswer(String text, boolean altered, boolean truncated) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DeclinedAnswer(
            @Nullable DeclinedTextAnswer words,
            @Nullable DeclinedTextAnswer content,
            @Nullable Boolean withheld) {}

    /** A file verified and held by this run even though its code try ended errored. */
    record KeptCodeFileAnswer(String path, UUID id, String name, String type, long size) {}

    /**
     * @param value as {@link ShownValueAnswer#value} has it, as are {@code withheld} and {@code earlierShape}
     * @param confidence how sure a model was, where it was asked; absent where withheld
     * @param decision what its review decided, where one did
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record TriedValueAnswer(
            String field,
            @Nullable JsonNode value,
            @Nullable Boolean withheld,
            String now,
            @Nullable Integer confidence,
            @Nullable DecisionAnswer decision,
            @Nullable Boolean earlierShape) {}

    /** @param why the words it was refused with; absent where a model said them and the reader may not read it */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DecisionAnswer(
            String outcome, @Nullable String why, @Nullable Boolean withheld) {}

    /**
     * @param asked whether any value of the try needed a review
     * @param by who reviewed it, where anybody has
     * @param wentWrong present where a model's review of it went wrong
     * @param lost how a model's review of it went wrong, as {@code TryLostReason} publishes it; absent where it did not
     * @param didNotFit which way what the model gave back did not fit, as {@code DidNotFitReason} publishes it, only
     *     where {@code lost} is {@code did_not_fit}
     * @param declined what the model gave back when it declined, or a withheld marker where its words may not be read
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReviewAnswer(
            boolean asked,
            @Nullable WhoAnswer by,
            @Nullable Boolean wentWrong,
            @Nullable String lost,
            @Nullable String didNotFit,
            @Nullable DeclinedAnswer declined,
            @Nullable UnreadableResponseRecord unreadableResponse) {}

    /**
     * @param detail absent where a model said it and the reader may not read what a model said
     * @param cut whether {@code detail} was cut to its bound, present with it
     * @param withheld present where {@code detail} is withheld
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WentWrongAnswer(
            @Nullable String detail,
            @Nullable Boolean cut,
            @Nullable Boolean withheld) {}

    /**
     * @param reason the reason from {@link org.lilradish.lite.domain.codestep.CodeErrorReason#published()}
     * @param field the field of the code step it is about, its names from the first level joined by dots; absent
     *     where it is about none, or about a member nothing declares at the first level; the code step's own path
     *     may not be found in {@code declarations}
     * @param member the name of a member nothing declares, as the code wrote it, cleaned for one line and past 64
     *     characters cut to its first 64; only where that is the reason, and kept though it is empty
     * @param memberCut present, and true, exactly where {@code member} was cut
     * @param readBy what reads the field that no longer matches, only where that is the reason
     * @param returnedNotKept present, and true, exactly where the code gave something back and it could not be kept
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErroredForAnswer(
            String reason,
            @Nullable String field,
            @Nullable String member,
            @Nullable Boolean memberCut,
            @Nullable ReadByAnswer readBy,
            @Nullable Boolean returnedNotKept) {}

    /**
     * What reads a field of a code step's, by exactly one of its members.
     *
     * @param step the later step reading it, by the identifier its version gives it
     * @param output the field of what the workflow gives back it is read into, its names joined by dots
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReadByAnswer(@Nullable UUID step, @Nullable String output) {

        static ReadByAnswer of(CodeError.ReadBy readBy) {
            return switch (readBy) {
                case CodeError.StepReads reads -> new ReadByAnswer(reads.step(), null);
                case CodeError.OutputReads reads -> new ReadByAnswer(null, new Pointer(reads.field()).published());
            };
        }
    }

    /**
     * What answering a step here puts to the reader.
     *
     * @param number the try an answer fills
     * @param instruction what the question tells whoever answers it; none for a code step, which tells nothing
     * @param gives one per field the answer gives, as whoever fills it reads it
     * @param lastRefused the newest try before it refused in words, with the words each value was refused with
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnsweringAnswer(
            int number,
            boolean beyond,
            @Nullable String instruction,
            List<FillFieldAnswer> gives,
            @Nullable LastRefusedAnswer lastRefused,
            @Nullable String searchQuestion,
            @Nullable Boolean searchQuestionSent,
            @Nullable Boolean searchQuestionWithheld) {
        AnsweringAnswer {
            if ((searchQuestion != null) != (searchQuestionSent != null)
                    || (searchQuestionWithheld != null && (searchQuestion != null || !searchQuestionWithheld))) {
                throw new IllegalArgumentException(
                        "An answering search question is named with whether it was sent, or withheld");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SearchedAnswer(
            @Nullable String searchedFor, @Nullable String found) {
        SearchedAnswer {
            if (searchedFor == null && found == null) {
                throw new IllegalArgumentException("A search record says what was searched for or what was found");
            }
        }
    }

    enum SearchUsesCounted {
        SAID("said"),
        AT_MOST("at_most");

        private final String published;

        SearchUsesCounted(String published) {
            this.published = published;
        }

        String published() {
            return published;
        }
    }

    enum SearchNoFeeReason {
        NOT_IN_CURRENCY("not_in_currency"),
        MANY_CURRENCIES("many_currencies"),
        NO_PRICE("no_price");

        private final String published;

        SearchNoFeeReason(String published) {
            this.published = published;
        }

        String published() {
            return published;
        }
    }

    record SearchFeeAnswer(String currency, String perUse, String amount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NoSearchFeeAnswer(String reason, @Nullable String currency) {
        NoSearchFeeAnswer {
            if (!(SearchNoFeeReason.NOT_IN_CURRENCY.published().equals(reason)
                            || SearchNoFeeReason.MANY_CURRENCIES.published().equals(reason)
                            || SearchNoFeeReason.NO_PRICE.published().equals(reason))
                    || (currency != null)
                            != SearchNoFeeReason.NOT_IN_CURRENCY.published().equals(reason)) {
                throw new IllegalArgumentException(
                        "A search fee is absent for one reason, with a currency only where it lacks that currency");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SearchAnswer(
            @Nullable Boolean withheld,
            @Nullable String question,
            @Nullable List<SearchedAnswer> searches,
            @Nullable Boolean searchesUnsaid,
            @Nullable Boolean searchesCut,
            @Nullable Boolean searchesNotAsItCame,
            @Nullable String searchesLeftOut,
            @Nullable String uses,
            @Nullable String usesCounted,
            @Nullable SearchFeeAnswer fee,
            @Nullable NoSearchFeeAnswer noFee) {
        SearchAnswer {
            if (Boolean.FALSE.equals(withheld)
                    || Boolean.FALSE.equals(searchesUnsaid)
                    || Boolean.FALSE.equals(searchesCut)
                    || Boolean.FALSE.equals(searchesNotAsItCame)
                    || (searches != null && searches.isEmpty())
                    || (searchesUnsaid != null && (searches != null || searchesCut != null || searchesLeftOut != null))
                    || (withheld != null
                            && (question != null
                                    || searches != null
                                    || searchesUnsaid != null
                                    || searchesCut != null
                                    || searchesNotAsItCame != null
                                    || searchesLeftOut != null))
                    || (withheld == null && question == null)) {
                throw new IllegalArgumentException(
                        "A search's words are named or withheld together, with a meaningful record");
            }
            if ((uses == null) != (usesCounted == null)
                    || (uses != null) != ((fee != null) ^ (noFee != null))
                    || (fee != null && noFee != null)
                    || (uses != null
                            && (!uses.matches("[0-9]+")
                                    || !(SearchUsesCounted.SAID.published().equals(usesCounted)
                                            || SearchUsesCounted.AT_MOST
                                                    .published()
                                                    .equals(usesCounted))))
                    || (searchesLeftOut != null && !searchesLeftOut.matches("[1-9][0-9]*"))) {
                throw new IllegalArgumentException(
                        "A search count and its fee are one complete group of decimal values");
            }
        }
    }

    record LastRefusedAnswer(int number, List<TriedValueAnswer> values) {}
}
