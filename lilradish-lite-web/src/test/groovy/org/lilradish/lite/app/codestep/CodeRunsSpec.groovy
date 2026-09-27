package org.lilradish.lite.app.codestep

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.lilradish.lite.domain.codestep.CodeCall
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.codestep.CodeOutcome
import org.lilradish.lite.domain.codestep.CodeStepName
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.inference.ForeignProse
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.SnapshottingAppender
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

class CodeRunsSpec extends Specification {

    static final Logger RUNS_LOGGER = LoggerFactory.getLogger(CodeRuns) as Logger

    static final JsonValue.JsonObject TAKES = new JsonValue.JsonObject(
            [new JsonValue.JsonMember("ticket", new JsonValue.JsonString("T-1"))])

    static final JsonValue.JsonObject RECEIPT = new JsonValue.JsonObject(
            [new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-1"))])

    static final JsonValue.JsonObject TOO_LONG_RECEIPT = new JsonValue.JsonObject(
            [new JsonValue.JsonMember("receipt", new JsonValue.JsonString("R-123456789"))])

    static final CodeError.Fault SAID_NOTHING = new CodeError.Fault(CodeErrorReason.SAID_NOTHING, [], null, null)

    private final SnapshottingAppender logged = new SnapshottingAppender()

    def setup() {
        logged.start()
        RUNS_LOGGER.addAppender(logged)
    }

    def cleanup() {
        RUNS_LOGGER.detachAppender(logged)
        TransactionSynchronizationManager.setActualTransactionActive(false)
        Thread.interrupted()
    }

    def "runs the code step a call names, on the caller's thread and outside any transaction, and says what it gave"() {
        given:
        JsonValue.JsonObject taken = null
        def filing = scripted("file_claim") { takes -> taken = takes; RECEIPT }
        def other = scripted("archive") { RECEIPT }
        def runs = new CodeRuns(new CodeSteps([filing, other]))

        when:
        def outcome = runs.run(callTo(filing))

        then:
        outcome == new CodeOutcome.Gave([
            new CodeOutcome.Given(new FieldName("receipt"), new JsonValue.JsonString("R-1"), FieldStanding.ALWAYS, null)])
        taken == TAKES
        filing.runs.get() == 1
        filing.ran == [new ScriptedCodeStep.Ran(Thread.currentThread().name, false)]
        other.runs.get() == 0
        logged.list.isEmpty()
    }

    /** What it gave back is read as the call's release declared it, so a misfit is given as that reading finds it. */
    def "code that gave back nothing, or what does not fit, went wrong for the reason what it gave back is read to"() {
        given:
        def filing = scripted("file_claim") { gave }
        def runs = new CodeRuns(new CodeSteps([filing]))

        expect:
        erred(runs.run(callTo(filing))) ==
                [new CodeError.Fault(reason, path.collect { new FieldName(it) }, null, null), kept]

        where:
        gave             || reason                       | path        | kept
        null             || CodeErrorReason.GAVE_NOTHING | []          | null
        TOO_LONG_RECEIPT || CodeErrorReason.TOO_LONG     | ["receipt"] | '{"receipt":"R-123456789"}'
    }

    /** A transaction held open while the code runs would hold its locks and a connection for as long as it takes. */
    def "refuses to run a code step inside a transaction, and runs nothing"() {
        given:
        def filing = scripted("file_claim") { RECEIPT }
        def runs = new CodeRuns(new CodeSteps([filing]))
        TransactionSynchronizationManager.setActualTransactionActive(true)

        when:
        runs.run(callTo(filing))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Code step file_claim is run outside any transaction, not inside one"
        filing.runs.get() == 0
        logged.list.isEmpty()
    }

    def "refuses a call to a code step this release does not hold, and runs none it holds"() {
        given:
        def filing = scripted("file_claim") { RECEIPT }
        def runs = new CodeRuns(new CodeSteps([filing]))

        when:
        runs.run(new CodeCall(new CodeStepName("close_ticket"), filing.declaration(), [:], TAKES))

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "This release holds no code step close_ticket"
        filing.runs.get() == 0
    }

    /** The message is the code's own and may repeat what it took: it goes on the try, and never into a log. */
    def "code that threw went wrong, saying what its message said, and the log names the code step and the error alone"() {
        given:
        def filing = scripted("file_claim") { throw failure }
        def runs = new CodeRuns(new CodeSteps([filing]))

        when:
        def outcome = runs.run(callTo(filing))

        then:
        erred(outcome) == [said("ticket T-1 for account 4471 was refused"), null]
        filing.runs.get() == 1
        !Thread.currentThread().isInterrupted()

        and:
        logged.list*.formattedMessage == ["Code step file_claim threw " + failure.class.name]
        logged.list*.level == [Level.WARN]
        logged.list*.throwableProxy == [null]
        logged.list.every { !it.formattedMessage.contains("4471") }

        where:
        failure << [
            new IllegalStateException("ticket T-1 for account 4471 was refused"),
            new IOException("ticket T-1 for account 4471 was refused"),
            new NoClassDefFoundError("ticket T-1 for account 4471 was refused"),
            new StackOverflowError("ticket T-1 for account 4471 was refused"),
            new AssertionError("ticket T-1 for account 4471 was refused")]
    }

    /** Whoever runs the code on this thread is asked to stop as the code was; swallowing that would lose it. */
    def "code that was interrupted went wrong, and leaves the thread interrupted for whatever runs it next"() {
        given:
        def filing = scripted("file_claim") { throw new InterruptedException("ticket T-1 was waiting") }
        def runs = new CodeRuns(new CodeSteps([filing]))

        when:
        def outcome = runs.run(callTo(filing))

        then:
        erred(outcome) == [said("ticket T-1 was waiting"), null]
        Thread.currentThread().isInterrupted()
        logged.list*.formattedMessage == ["Code step file_claim threw java.lang.InterruptedException"]
    }

    def "code that threw what cannot say its message went wrong as having said nothing, and the log names it"() {
        given:
        def failure = new UnreadableFailure()
        def filing = scripted("file_claim") { throw failure }
        def runs = new CodeRuns(new CodeSteps([filing]))

        when:
        def outcome = runs.run(callTo(filing))

        then:
        erred(outcome) == [SAID_NOTHING, null]
        filing.runs.get() == 1
        logged.list*.formattedMessage == ["Code step file_claim threw " + UnreadableFailure.name]
    }

    /** Past saying anything of the code step, it is left to reach whatever handed the call over. */
    def "an error the machine is failing with is not caught, and nothing is said of the code step"() {
        given:
        def failure = new OutOfMemoryError("ticket T-1 for account 4471 was refused")
        def filing = scripted("file_claim") { throw failure }
        def runs = new CodeRuns(new CodeSteps([filing]))

        when:
        runs.run(callTo(filing))

        then:
        def escaped = thrown(OutOfMemoryError)
        escaped.is(failure)
        filing.runs.get() == 1
        logged.list.isEmpty()
    }

    /** A spec whose gate never opens must fail as that, not pass as code gone wrong. */
    def "a scripted code step whose gate never opens fails the run outright rather than going wrong"() {
        given:
        def neverOpens = new CountDownLatch(1) {
            @Override
            boolean await(long timeout, TimeUnit unit) {
                false
            }
        }
        def filing = new ScriptedCodeStep("file_claim", [SpecCodeStep.given("ticket", new FieldShape.Text(64))],
                [SpecCodeStep.standing("receipt", new FieldShape.Text(8))], false, { RECEIPT }, neverOpens)
        def runs = new CodeRuns(new CodeSteps([filing]))

        when:
        runs.run(callTo(filing))

        then:
        def escaped = thrown(ScriptedCodeStep.GateNeverOpened)
        escaped.message == "ScriptedCodeStep file_claim was never let through its gate"
        filing.runs.get() == 1
        logged.list.isEmpty()
    }

    private static ScriptedCodeStep scripted(String name, Closure<JsonValue.JsonObject> behaviour) {
        new ScriptedCodeStep(name, [SpecCodeStep.given("ticket", new FieldShape.Text(64))],
                [SpecCodeStep.standing("receipt", new FieldShape.Text(8))], false, behaviour)
    }

    private static CodeCall callTo(ScriptedCodeStep codeStep) {
        new CodeCall(codeStep.name(), codeStep.declaration(), [:], TAKES)
    }

    /** Why an outcome went wrong and what it kept, or none where it did not go wrong. */
    private static List<Object> erred(CodeOutcome outcome) {
        outcome instanceof CodeOutcome.Errored ? [outcome.wentWrong(), outcome.returned()] : null
    }

    /** What code said went wrong, cleaned and cut as it is kept. */
    private static CodeError.Said said(String message) {
        new CodeError.Said(ForeignProse.errorDetail(message))
    }

    /** Code whose own message cannot be read, which says nothing of what went wrong. */
    static final class UnreadableFailure extends RuntimeException {

        @Override
        String getMessage() {
            throw new IllegalStateException("ticket T-1 for account 4471 was refused")
        }
    }
}
