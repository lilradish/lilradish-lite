package org.lilradish.lite.domain.run

import static org.lilradish.lite.domain.failure.RefusalCode.ACT_NOT_PERMITTED
import static org.lilradish.lite.domain.failure.RefusalCode.ASK_AGAIN_NOT_OFFERED
import static org.lilradish.lite.domain.failure.RefusalCode.CODE_STEP_GIVES_OTHERWISE
import static org.lilradish.lite.domain.failure.RefusalCode.ENTRY_STOPPED
import static org.lilradish.lite.domain.failure.RefusalCode.REVIEW_NOT_A_PERSONS
import static org.lilradish.lite.domain.failure.RefusalCode.REVIEW_OWN_PRODUCTION
import static org.lilradish.lite.domain.failure.RefusalCode.RUN_STOPPED
import static org.lilradish.lite.domain.failure.RefusalCode.STEP_MOVED_ON
import static org.lilradish.lite.domain.failure.RefusalCode.TRY_SENDING_NOT_OFFERED
import static org.lilradish.lite.domain.identity.GroupPermission.ANSWER_STEP
import static org.lilradish.lite.domain.identity.GroupPermission.READ_ALL_RUNS
import static org.lilradish.lite.domain.identity.GroupPermission.REVIEW_AT_GATE
import static org.lilradish.lite.domain.identity.GroupPermission.START_RUN
import static org.lilradish.lite.domain.run.StepAct.ANSWER
import static org.lilradish.lite.domain.run.StepAct.ASK_AGAIN
import static org.lilradish.lite.domain.run.StepAct.REVIEW
import static org.lilradish.lite.domain.run.StepAct.TRY_SENDING
import static org.lilradish.lite.domain.run.fixture.Runs.STARTED
import static org.lilradish.lite.domain.run.fixture.Runs.position
import static org.lilradish.lite.domain.run.fixture.Runs.valueId
import static org.lilradish.lite.domain.workflow.StepProducer.CODE
import static org.lilradish.lite.domain.workflow.StepProducer.MODEL
import static org.lilradish.lite.domain.workflow.StepProducer.PERSON

import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.inference.ModelCallPurpose
import org.lilradish.lite.domain.workflow.StepProducer
import spock.lang.Specification

class StepActSpec extends Specification {

    static final Set<GroupPermission> EVERYTHING = EnumSet.allOf(GroupPermission)

    def "each act on a step is published under the spelling a reader draws its control by, in this order and no other"() {
        expect:
        StepAct.values().collect { [it, it.published()] } == [[ANSWER, "answer"], [ASK_AGAIN, "ask_again"],
                                                             [REVIEW, "review"], [TRY_SENDING, "try_sending"]]
    }

    /**
     * Asking again is making the next try by other hands, so it is the same authority as answering; sending again
     * may cost as a run does, so it is whoever may start one.
     */
    def "each act on a step takes the one permission the group's roles reach it by"() {
        expect:
        StepAct.values().collectEntries { [(it): it.permission()] } == [
                (ANSWER)     : ANSWER_STEP,
                (ASK_AGAIN)  : ANSWER_STEP,
                (REVIEW)     : REVIEW_AT_GATE,
                (TRY_SENDING): START_RUN,
        ]
    }

    /** Nothing waiting comes first, then the run stopped, then a value on the model, then the reader's own values. */
    def "reviewing is refused where nothing waits on a review, the run is stopped, the model reviews, or the reader produced it"() {
        expect:
        REVIEW.refusal(new StepGround(position(at), runStopped, entryStopped, producer, readerProduced, false, null,
                false)) == refusal

        where:
        at                                            | runStopped | entryStopped | producer | readerProduced || refusal
        "not started"                                 | false      | false        | PERSON   | false          || STEP_MOVED_ON
        "asked"                                       | false      | false        | PERSON   | false          || STEP_MOVED_ON
        "failed with its tries spent"                 | true       | false        | PERSON   | false          || STEP_MOVED_ON
        "done"                                        | false      | false        | PERSON   | true           || STEP_MOVED_ON
        "awaiting a person's review"                  | true       | false        | PERSON   | true           || RUN_STOPPED
        "awaiting the model's review"                 | true       | false        | MODEL    | false          || RUN_STOPPED
        "awaiting the model's review"                 | false      | false        | MODEL    | false          || REVIEW_NOT_A_PERSONS
        "awaiting a person's review and the model's"  | false      | false        | MODEL    | true           || REVIEW_NOT_A_PERSONS
        "awaiting a person's review"                  | false      | false        | PERSON   | true           || REVIEW_OWN_PRODUCTION
        "awaiting a person's review"                  | false      | true         | PERSON   | false          || null
        "awaiting a person's review"                  | false      | false        | MODEL    | false          || null
    }

    /**
     * A stop is judged as it stands now, never from a hold whose stop may since have been let go; code's own try is
     * answered as the release declares it, unless a later step reads what it gives otherwise.
     */
    def "answering is refused where no try is owed, the run or what the step runs is stopped, or code gives otherwise"() {
        expect:
        ANSWER.refusal(new StepGround(position(at), runStopped, entryStopped, producer, false, false,
                givingOtherwise(otherwise), false)) ==
                refusal

        where:
        at                                        | runStopped | entryStopped | producer | otherwise || refusal
        "not started"                             | false      | false        | PERSON   | false     || STEP_MOVED_ON
        "running a call"                          | false      | false        | MODEL    | false     || STEP_MOVED_ON
        "running code"                            | false      | false        | CODE     | true      || STEP_MOVED_ON
        "held back too long"                      | false      | false        | PERSON   | false     || STEP_MOVED_ON
        "held back, code step not held"           | false      | false        | CODE     | false     || STEP_MOVED_ON
        "held back on a stop before it was asked" | false      | true         | PERSON   | false     || STEP_MOVED_ON
        "awaiting a person's review"              | false      | false        | PERSON   | false     || STEP_MOVED_ON
        "failed as written down"                  | false      | false        | MODEL    | false     || STEP_MOVED_ON
        "done"                                    | true       | true         | PERSON   | false     || STEP_MOVED_ON
        "owed"                                    | true       | true         | CODE     | true      || RUN_STOPPED
        "held back on a stop"                     | true       | true         | PERSON   | false     || RUN_STOPPED
        "held back on a stop"                     | false      | true         | PERSON   | false     || ENTRY_STOPPED
        "asked"                                   | true       | true         | PERSON   | false     || RUN_STOPPED
        "owed"                                    | false      | true         | MODEL    | false     || ENTRY_STOPPED
        "owed"                                    | false      | true         | CODE     | true      || ENTRY_STOPPED
        "owed"                                    | false      | false        | CODE     | true      || CODE_STEP_GIVES_OTHERWISE
        "failed with its tries spent"             | false      | false        | CODE     | true      || CODE_STEP_GIVES_OTHERWISE
        "asked"                                   | false      | false        | PERSON   | false     || null
        "owed"                                    | false      | false        | MODEL    | false     || null
        "failed with its tries spent"             | false      | false        | MODEL    | false     || null
        "owed"                                    | false      | false        | CODE     | false     || null
        "failed with its tries spent"             | false      | false        | CODE     | false     || null
    }

    /**
     * Pressing it again on a try it already made finds the step moved on, whatever else holds; code is asked again
     * only where the release lets it run again, and giving otherwise does not stop it being asked.
     */
    def "asking again is refused as answering is, besides where the try is asked already or nobody may be asked again"() {
        expect:
        ASK_AGAIN.refusal(new StepGround(position(at), runStopped, entryStopped, producer, false, again,
                givingOtherwise(otherwise), false)) ==
                refusal

        where:
        at                                        | runStopped | entryStopped | producer | again | otherwise || refusal
        "not started"                             | false      | false        | MODEL    | false | false     || STEP_MOVED_ON
        "awaiting a person's review"              | false      | false        | PERSON   | false | false     || STEP_MOVED_ON
        "held back on a stop before it was asked" | false      | true         | PERSON   | false | false     || STEP_MOVED_ON
        "held back, code step not held"           | false      | false        | CODE     | false | false     || STEP_MOVED_ON
        "running its next try"                    | false      | false        | CODE     | true  | false     || STEP_MOVED_ON
        "asked"                                   | true       | true         | PERSON   | false | false     || STEP_MOVED_ON
        "asked"                                   | false      | false        | PERSON   | false | false     || STEP_MOVED_ON
        "held back on a stop"                     | true       | true         | PERSON   | false | false     || RUN_STOPPED
        "held back on a stop"                     | false      | true         | CODE     | true  | false     || ENTRY_STOPPED
        "owed"                                    | false      | false        | CODE     | false | false     || ASK_AGAIN_NOT_OFFERED
        "failed with its tries spent"             | false      | false        | CODE     | false | false     || ASK_AGAIN_NOT_OFFERED
        "owed"                                    | false      | false        | null     | false | false     || ASK_AGAIN_NOT_OFFERED
        "owed"                                    | false      | false        | MODEL    | false | false     || null
        "failed with its tries spent"             | false      | false        | MODEL    | false | false     || null
        "owed"                                    | false      | false        | PERSON   | false | false     || null
        "failed with its tries spent"             | false      | false        | PERSON   | false | false     || null
        "owed"                                    | false      | false        | CODE     | true  | false     || null
        "failed with its tries spent"             | false      | false        | CODE     | true  | true      || null
    }

    /**
     * Only a step held back or failed, or values waiting on the model to review them, has anything of this kind to
     * do; of those, a model's try held back on length or a turnaway, failed on length, any try failed on its model
     * not held, whatever was to be sent, and values the reviewer turned away are sent again, save of a code step the
     * release does not hold, before any stop is judged. A stop on what the step runs holds no review.
     */
    def "sending again is refused where nothing went unsent to a model, where sending settles nothing, or while what it would send is stopped"() {
        expect:
        TRY_SENDING.refusal(new StepGround(named(at), runStopped, entryStopped, producer, false, false, null,
                unreleased)) == refusal

        where:
        at                                                 | runStopped | entryStopped | producer | unreleased || refusal
        "not started"                                      | false      | false        | MODEL    | false      || STEP_MOVED_ON
        "running a call"                                   | false      | false        | MODEL    | false      || STEP_MOVED_ON
        "running its next try"                             | true       | true         | MODEL    | false      || STEP_MOVED_ON
        "owed"                                             | false      | false        | MODEL    | false      || STEP_MOVED_ON
        "awaiting the model's review"                      | false      | false        | MODEL    | false      || STEP_MOVED_ON
        "awaiting the model's review, out"                 | true       | true         | CODE     | true       || STEP_MOVED_ON
        "awaiting a person's review"                       | false      | false        | PERSON   | false      || STEP_MOVED_ON
        "done"                                             | false      | false        | MODEL    | false      || STEP_MOVED_ON
        "held back on a stop"                              | false      | true         | MODEL    | false      || TRY_SENDING_NOT_OFFERED
        "held back on a stop before it was asked"          | true       | true         | MODEL    | false      || TRY_SENDING_NOT_OFFERED
        "held back, code step not held"                    | false      | false        | CODE     | true       || TRY_SENDING_NOT_OFFERED
        "failed with its tries spent"                      | true       | false        | MODEL    | false      || TRY_SENDING_NOT_OFFERED
        "failed, no route case claiming it"                | false      | false        | null     | false      || TRY_SENDING_NOT_OFFERED
        "held back too long"                               | false      | false        | PERSON   | false      || TRY_SENDING_NOT_OFFERED
        "failed as written down"                           | false      | false        | CODE     | false      || TRY_SENDING_NOT_OFFERED
        "awaiting a person's review in the model's place"  | true       | true         | MODEL    | false      || TRY_SENDING_NOT_OFFERED
        "awaiting a person's review, a list not here"      | false      | false        | CODE     | false      || TRY_SENDING_NOT_OFFERED
        "awaiting a person's review, takes no longer declared" | false  | false        | CODE     | false      || TRY_SENDING_NOT_OFFERED
        "awaiting a person's review, no longer declared"   | false      | false        | CODE     | false      || TRY_SENDING_NOT_OFFERED
        "awaiting the model's review, turned away"         | true       | false        | CODE     | true       || TRY_SENDING_NOT_OFFERED
        "failed, its reviewer not held"                    | false      | true         | CODE     | true       || TRY_SENDING_NOT_OFFERED
        "held back, turned away"                           | true       | true         | MODEL    | false      || RUN_STOPPED
        "failed as written down"                           | true       | false        | MODEL    | false      || RUN_STOPPED
        "awaiting the model's review, turned away"         | true       | true         | PERSON   | false      || RUN_STOPPED
        "failed, its reviewer not held"                    | true       | true         | CODE     | false      || RUN_STOPPED
        "held back too long"                               | false      | true         | MODEL    | false      || ENTRY_STOPPED
        "held back, turned away"                           | false      | true         | MODEL    | false      || ENTRY_STOPPED
        "failed as written down"                           | false      | true         | MODEL    | false      || ENTRY_STOPPED
        "failed, its model not held"                       | false      | true         | MODEL    | false      || ENTRY_STOPPED
        "held back too long"                               | false      | false        | MODEL    | false      || null
        "held back, turned away"                           | false      | false        | MODEL    | false      || null
        "failed as written down"                           | false      | false        | MODEL    | false      || null
        "failed, its model not held"                       | false      | false        | MODEL    | false      || null
        "failed, its reviewer not held"                    | false      | false        | MODEL    | false      || null
        "failed, its reviewer not held"                    | false      | false        | PERSON   | false      || null
        "failed, its reviewer not held"                    | false      | false        | CODE     | false      || null
        "failed, its reviewer not held"                    | false      | true         | PERSON   | false      || null
        "awaiting the model's review, turned away"         | false      | false        | PERSON   | false      || null
        "awaiting the model's review, turned away"         | false      | false        | MODEL    | false      || null
        "awaiting the model's review, turned away"         | false      | false        | CODE     | false      || null
        "awaiting the model's review, turned away"         | false      | true         | CODE     | false      || null
    }

    def "what a step offers is every act whose permission is held and which nothing refuses there"() {
        expect:
        StepAct.admitted(permitted as Set<GroupPermission>, ground(at, producer, runStopped)) == admitted as Set

        where:
        permitted                     | at                           | producer | runStopped || admitted
        EVERYTHING                    | "owed"                       | PERSON   | false      || [ANSWER, ASK_AGAIN]
        EVERYTHING                    | "owed"                       | MODEL    | false      || [ANSWER, ASK_AGAIN]
        EVERYTHING                    | "asked"                      | PERSON   | false      || [ANSWER]
        EVERYTHING                    | "owed"                       | CODE     | false      || [ANSWER]
        EVERYTHING                    | "awaiting a person's review" | PERSON   | false      || [REVIEW]
        EVERYTHING                    | "owed"                       | PERSON   | true       || []
        EVERYTHING                    | "done"                       | PERSON   | false      || []
        EVERYTHING                    | "held back, turned away"     | MODEL    | false      || [TRY_SENDING]
        EVERYTHING                    | "failed, its model not held" | MODEL    | false      || [TRY_SENDING]
        EVERYTHING                    | "failed, its reviewer not held" | PERSON | false     || [TRY_SENDING]
        EVERYTHING                    | "awaiting the model's review, turned away" | PERSON | false || [TRY_SENDING]
        EVERYTHING                    | "awaiting a person's review, takes no longer declared" | CODE | false || [REVIEW]
        EVERYTHING                    | "awaiting a person's review, no longer declared" | CODE | false || [REVIEW]
        EVERYTHING                    | "awaiting a person's review, a list not here" | CODE | false || [REVIEW]
        EVERYTHING                    | "held back too long"         | MODEL    | true       || []
        EVERYTHING                    | "failed with its tries spent" | MODEL   | false      || [ANSWER, ASK_AGAIN]
        [ANSWER_STEP]                 | "owed"                       | PERSON   | false      || [ANSWER, ASK_AGAIN]
        [ANSWER_STEP]                 | "awaiting a person's review" | PERSON   | false      || []
        [ANSWER_STEP, REVIEW_AT_GATE] | "held back too long"         | MODEL    | false      || []
        [START_RUN]                   | "failed as written down"     | MODEL    | false      || [TRY_SENDING]
        [START_RUN]                   | "owed"                       | MODEL    | false      || []
        [REVIEW_AT_GATE]              | "owed"                       | PERSON   | false      || []
        [REVIEW_AT_GATE]              | "awaiting a person's review" | PERSON   | false      || [REVIEW]
        [READ_ALL_RUNS]               | "owed"                       | PERSON   | false      || []
        []                            | "awaiting a person's review" | PERSON   | false      || []
    }

    def "a code step offers asking again only where the release lets it run again, and answering where nothing reads it otherwise"() {
        expect:
        StepAct.admitted(EVERYTHING, codeGround(at, again, otherwise)) == admitted as Set

        where:
        at                            | again | otherwise || admitted
        "owed"                        | true  | false     || [ANSWER, ASK_AGAIN]
        "owed"                        | false | false     || [ANSWER]
        "owed"                        | true  | true      || [ASK_AGAIN]
        "owed"                        | false | true      || []
        "failed with its tries spent" | true  | false     || [ANSWER, ASK_AGAIN]
        "running code"                | true  | false     || []
    }

    def "what a step offers is nobody's to widen"() {
        given:
        def offered = StepAct.admitted(EVERYTHING, ground("asked", PERSON, false))

        when:
        offered.add(ASK_AGAIN)

        then:
        thrown(UnsupportedOperationException)
        !offered.contains(ASK_AGAIN)
    }

    /** The gate refuses a missing permission before anything else, so that is what the reader is told first. */
    def "what a step withholds is each act with something of its kind to do there that the reader may not do, beside why"() {
        expect:
        StepAct.withheld(permitted as Set<GroupPermission>, ground(at, producer, runStopped)) == withheld

        where:
        permitted        | at                                        | producer | runStopped || withheld
        EVERYTHING       | "asked"                                   | PERSON   | false      || [:]
        EVERYTHING       | "owed"                                    | PERSON   | false      || [:]
        EVERYTHING       | "owed"                                    | MODEL    | false      || [:]
        EVERYTHING       | "owed"                                    | CODE     | false      || [(ASK_AGAIN): ASK_AGAIN_NOT_OFFERED]
        EVERYTHING       | "owed"                                    | PERSON   | true       || [(ANSWER): RUN_STOPPED, (ASK_AGAIN): RUN_STOPPED]
        EVERYTHING       | "held back on a stop"                     | PERSON   | false      || [(ANSWER): ENTRY_STOPPED, (ASK_AGAIN): ENTRY_STOPPED]
        EVERYTHING       | "held back on a stop before it was asked" | PERSON   | false      || [:]
        EVERYTHING       | "done"                                    | PERSON   | true       || [:]
        []               | "owed"                                    | PERSON   | false      || [(ANSWER): ACT_NOT_PERMITTED, (ASK_AGAIN): ACT_NOT_PERMITTED]
        []               | "owed"                                    | PERSON   | true       || [(ANSWER): ACT_NOT_PERMITTED, (ASK_AGAIN): ACT_NOT_PERMITTED]
        []               | "held back on a stop before it was asked" | PERSON   | false      || [:]
        []               | "awaiting a person's review"              | PERSON   | false      || [(REVIEW): ACT_NOT_PERMITTED]
        [REVIEW_AT_GATE] | "held back on a stop"                     | PERSON   | false      || [(ANSWER): ACT_NOT_PERMITTED, (ASK_AGAIN): ACT_NOT_PERMITTED]
        [ANSWER_STEP]    | "awaiting a person's review"              | PERSON   | true       || [(REVIEW): ACT_NOT_PERMITTED]
    }

    /** Only a model reviews what waits on one, so no permission a reader lacks is why they may not. */
    def "a review waiting on the model is withheld from a reviewer for why it is, and from nobody else for a permission"() {
        given:
        def standing = ground(at, MODEL, runStopped)

        when:
        def withheld = StepAct.withheld(permitted as Set<GroupPermission>, standing)

        then:
        withheld == expected

        and: "offered to nobody, whatever they hold"
        StepAct.admitted(permitted as Set<GroupPermission>, standing).isEmpty()

        where:
        permitted        | at                                            | runStopped || expected
        []               | "awaiting the model's review"                 | false      || [:]
        []               | "awaiting the model's review"                 | true       || [:]
        [ANSWER_STEP]    | "awaiting a person's review and the model's"  | false      || [:]
        [REVIEW_AT_GATE] | "awaiting the model's review"                 | false      || [(REVIEW): REVIEW_NOT_A_PERSONS]
        [REVIEW_AT_GATE] | "awaiting a person's review and the model's"  | true       || [(REVIEW): RUN_STOPPED]
        []               | "awaiting a person's review"                  | false      || [(REVIEW): ACT_NOT_PERMITTED]
        [ANSWER_STEP]    | "awaiting a person's review"                  | true       || [(REVIEW): ACT_NOT_PERMITTED]
    }

    /**
     * Where sending again settles nothing there is nothing of its kind to do, so nobody is told why they may not;
     * where it would, a reader who may not start a run is told that before any stop, and a stop on what the step
     * runs is told only where what would be sent runs it.
     */
    def "sending again is withheld where it is offered from whoever may not start a run, and while stopped from whoever may"() {
        given:
        def standing = new StepGround(named(at), runStopped, entryStopped, MODEL, false, false, null, false)

        when:
        def withheld = StepAct.withheld(permitted as Set<GroupPermission>, standing)

        then:
        withheld == expected

        and: "offered exactly where nothing is withheld of it and the reader may start a run"
        StepAct.admitted(permitted as Set<GroupPermission>, standing).contains(TRY_SENDING) == offered

        where:
        permitted                     | at                            | runStopped | entryStopped || expected                                  | offered
        [ANSWER_STEP, REVIEW_AT_GATE] | "held back, turned away"      | false      | false        || [(TRY_SENDING): ACT_NOT_PERMITTED]        | false
        []                            | "failed, its model not held"  | false      | false        || [(TRY_SENDING): ACT_NOT_PERMITTED]        | false
        []                            | "held back too long"          | true       | false        || [(TRY_SENDING): ACT_NOT_PERMITTED]        | false
        [START_RUN]                   | "held back too long"          | true       | false        || [(TRY_SENDING): RUN_STOPPED]              | false
        [START_RUN]                   | "failed as written down"      | false      | true         || [(TRY_SENDING): ENTRY_STOPPED]            | false
        [START_RUN]                   | "held back too long"          | false      | false        || [:]                                       | true
        EVERYTHING                    | "held back, turned away"      | false      | false        || [:]                                       | true
        EVERYTHING                    | "failed with its tries spent" | false      | false        || [:]                                       | false
        EVERYTHING                    | "running a call"              | true       | false        || [:]                                       | false
        []                            | "held back on a stop"         | false      | true         || [(ANSWER): ACT_NOT_PERMITTED, (ASK_AGAIN): ACT_NOT_PERMITTED] | false
        [ANSWER_STEP]                 | "awaiting the model's review, turned away" | false | false || [(TRY_SENDING): ACT_NOT_PERMITTED]  | false
        [START_RUN]                   | "awaiting the model's review, turned away" | true | false  || [(TRY_SENDING): RUN_STOPPED]        | false
        [START_RUN]                   | "awaiting the model's review, turned away" | false | true  || [:]                                 | true
        [START_RUN]                   | "failed, its reviewer not held" | false    | true         || [:]                                       | true
        EVERYTHING                    | "awaiting the model's review, turned away" | false | false || [(REVIEW): REVIEW_NOT_A_PERSONS]    | true
        [START_RUN]                   | "awaiting the model's review, out" | false | false        || [:]                                       | false
        [START_RUN]                   | "awaiting a person's review, no longer declared" | false | false || [(REVIEW): ACT_NOT_PERMITTED] | false
    }

    def "a code step withholds asking again where the release does not let it run again, and answering where it gives otherwise"() {
        expect:
        StepAct.withheld(EVERYTHING, codeGround("owed", again, otherwise)) == withheld

        where:
        again | otherwise || withheld
        false | false     || [(ASK_AGAIN): ASK_AGAIN_NOT_OFFERED]
        true  | false     || [:]
        false | true      || [(ANSWER): CODE_STEP_GIVES_OTHERWISE, (ASK_AGAIN): ASK_AGAIN_NOT_OFFERED]
        true  | true      || [(ANSWER): CODE_STEP_GIVES_OTHERWISE]
    }

    def "the reviewer's own values are withheld from them for being theirs"() {
        given:
        def standing = new StepGround(position("awaiting a person's review"), false, false, PERSON, true, false, null,
                false)

        expect:
        StepAct.withheld(EVERYTHING, standing) == [(REVIEW): REVIEW_OWN_PRODUCTION]
        StepAct.admitted(EVERYTHING, standing).isEmpty()
    }

    /** A step held back on a stop is one whose entry is stopped, as it is wherever such a position is worked out. */
    private static StepGround ground(String at, StepProducer producer, boolean runStopped) {
        new StepGround(named(at), runStopped, at.startsWith("held back on a stop"), producer, false, false, null, false)
    }

    /** A step code produces, of a run not stopped, running what is not stopped. */
    private static StepGround codeGround(String at, boolean mayRunAgain, boolean givesOtherwise) {
        new StepGround(position(at), false, false, CODE, false, mayRunAgain, givingOtherwise(givesOtherwise), false)
    }

    /** What a later step reading the receipt found, where it gives otherwise. */
    private static CodeError.Fault givingOtherwise(boolean otherwise) {
        otherwise
                ? new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE, [new FieldName("receipt")], null,
                        new CodeError.StepReads(UUID.fromString("00000009-0000-4000-8000-000000000c02")))
                : null
    }

    /**
     * A position as the run fixture names it, beside three failures written down and three ways values are handed
     * to a person as what the model would be sent could not be built, that it names none of.
     */
    private static StepPosition named(String at) {
        switch (at) {
            case "failed, its model not held":
                return failedOn(RunStepFailureReason.MODEL_NOT_DEPLOYED, ModelCallPurpose.PRODUCE)
            case "failed, its reviewer not held":
                return failedOn(RunStepFailureReason.MODEL_NOT_DEPLOYED, ModelCallPurpose.REVIEW)
            case "failed, no route case claiming it":
                return failedOn(RunStepFailureReason.UNCLAIMED_VALUE, null)
            case "awaiting a person's review, a list not here":
                return handedToPerson(ReviewSending.LIST_NOT_HERE)
            case "awaiting a person's review, takes no longer declared":
                return handedToPerson(ReviewSending.TAKES_NO_LONGER_DECLARED)
            case "awaiting a person's review, no longer declared":
                return handedToPerson(ReviewSending.NO_LONGER_DECLARED)
            default:
                return position(at)
        }
    }

    private static StepPosition failedOn(RunStepFailureReason reason, ModelCallPurpose purpose) {
        new StepPosition.Failed(new StepFailure.Recorded(reason, purpose), STARTED)
    }

    private static StepPosition handedToPerson(ReviewSending sending) {
        new StepPosition.AwaitingReview(1, [new StepPosition.WaitingValue(valueId(1), "answer_1", WaitsOn.REVIEW_AT_GATE)],
                null, STARTED, sending)
    }
}
