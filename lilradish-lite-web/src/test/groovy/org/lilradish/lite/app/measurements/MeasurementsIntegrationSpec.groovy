package org.lilradish.lite.app.measurements

import static org.lilradish.lite.testutil.library.LibraryStore.FIRST_STEWARD
import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.Connection
import java.sql.PreparedStatement
import javax.sql.DataSource
import org.lilradish.lite.domain.model.ModelMode
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.support.JdbcTransactionManager
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The measurements as the store counts them, asked of a real server running the real baseline. Every
 * row a feature arranges is one the schema admits, so a case the schema cannot hold is never one this
 * counts; the one row written round the schema is written so on purpose, and says so.
 *
 * <p>Each feature reads a database of its own holding a run of a workflow whose steps the feature adds
 * as it needs them. A step names the model and mode producing on it and the one reviewing it, or none,
 * and the schema holds every call made for the step to those.
 *
 * <p>Every read is compared whole, so a count put on the wrong row, or a row nobody arranged, fails as
 * surely as a count that is wrong.
 */
class MeasurementsIntegrationSpec extends Specification {

    static final String WORKFLOW_RUNNER = "00000000-0000-4000-8000-000000000001"

    static final String GROUP = "00000003-0000-4000-8000-000000000b01"

    static final String QUESTION = "00000006-0000-4000-8000-000000000b01"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000b02"

    static final String QUESTION_VERSION = "00000007-0000-4000-8000-000000000b01"

    /** Helped by the helper model in research mode, which is the only model a call to help reaches. */
    static final String WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000b02"

    static final String RUN = "00000011-0000-4000-8000-000000000b01"

    /** What the question gives back: two fields always needing a review, and one standing as given. */
    static final String DETAILS = "0000000b-0000-4000-8000-000000000b01"

    static final String NOTE = "0000000b-0000-4000-8000-000000000b02"

    static final String SUMMARY = "0000000b-0000-4000-8000-000000000b03"

    /** What a code step gives back, named rather than declared, and always needing a review. */
    static final String RECEIPT = "receipt"

    static final String CODE_STEP = "send_reply"

    static final String NOT_A_NAME = "Not A Name"

    static final int TRIES = 20

    /** No mode: the model run as it is. */
    static final ModelMode AS_IT_IS = null

    static final ModelMode RESEARCH = new ModelMode("research")

    static final List<String> MODEL_COUNTS = ["productions", "refusedOnReview", "reviews", "refusing", "didNotFit"]

    static final List<String> SYSTEM_COUNTS = ["wentWrong", "neverCameBack", "turnedAway"]

    /** Named so that code point and the Unicode collation algorithm order them apart. */
    static final List<String> APART = ["ab", "a_b", "a1"]

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    @Shared
    Map<String, LibraryStore> underLocales = [:]

    LibraryStore store

    JdbcClient session

    Measurements measurements

    int positions = 0

    String hold

    def setupSpec() {
        LibraryStore.template(server)
        underLocales.c = LibraryStore.madeUnder(server, "measured_c", "locale_provider libc locale 'C'")
        underLocales.icu = LibraryStore.madeUnder(server, "measured_icu",
                "locale_provider icu icu_locale 'und' locale 'C'")
        underLocales.values().each { populate(it) }
    }

    def "counts a production the model made and was taken, and none lost, nor any a person made"() {
        given:
        measuring()
        "$arranged"()

        expect:
        measuredWhole() == [models, system]

        where:
        arranged                               || models                                      | system
        "aProductionTaken"                     || [modelRow("alpha", AS_IT_IS, productions: 1)] | [systemRow("alpha")]
        "aProductionWhoseCallWentWrong"        || [modelRow("alpha", AS_IT_IS)]               | [systemRow("alpha", wentWrong: 1)]
        "aProductionNothingCameBackFor"        || [modelRow("alpha", AS_IT_IS)]               | [systemRow("alpha", neverCameBack: 1)]
        "aPersonsProductionTheModelRefused"    || [modelRow("beta", RESEARCH, reviews: 1, refusing: 1)] | [systemRow("beta")]
        "manyTriesOfOneModel"                  || [modelRow("alpha", AS_IT_IS, productions: 3, refusedOnReview: 1,
                                                           didNotFit: 1)]           | [systemRow("alpha", wentWrong: 1, neverCameBack: 1)]
    }

    def "counts a production refused on review once, whoever refused it, and never for a refusal for length"() {
        given:
        measuring()
        "$arranged"()

        expect:
        measuredWhole() == [models, system]

        where:
        arranged                                  || models                                                          | system
        "aProductionRefusedByAPerson"             || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1)] | [systemRow("alpha")]
        "aProductionAssuredByAPerson"             || [modelRow("alpha", AS_IT_IS, productions: 1)]                   | [systemRow("alpha")]
        "aProductionRefusedForLengthAlone"        || [modelRow("alpha", AS_IT_IS, productions: 1)]                   | [systemRow("alpha")]
        "aValueAssuredThenRefusedForLength"       || [modelRow("alpha", AS_IT_IS, productions: 1)]                   | [systemRow("alpha")]
        "aProductionWithTwoValuesRefused"         || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1)] | [systemRow("alpha")]
        "aProductionRefusedOnReviewAndForLength"  || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1)] | [systemRow("alpha")]
        "aReviewNeverSentAndMadeByAPerson"        || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1)] | [systemRow("alpha")]
    }

    def "counts a review the model made that decided, and those refusing, under the mode it reviewed in"() {
        given:
        measuring()
        "$arranged"()

        expect:
        measuredWhole() == [models, system]

        where:
        arranged                                     || models | system
        "aReviewRefusingOneValueOfTwo"               || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1),
                                                         modelRow("beta", RESEARCH, reviews: 1, refusing: 1)] | [systemRow("alpha"), systemRow("beta")]
        "aReviewAssuringEveryValue"                  || [modelRow("alpha", AS_IT_IS, productions: 1),
                                                         modelRow("beta", RESEARCH, reviews: 1)]              | [systemRow("alpha"), systemRow("beta")]
        "aReviewAssuringEveryValueThenOneForLength"  || [modelRow("alpha", AS_IT_IS, productions: 1),
                                                         modelRow("beta", RESEARCH, reviews: 1)]              | [systemRow("alpha"), systemRow("beta")]
        "aReviewWhoseCallWentWrong"                  || [modelRow("alpha", AS_IT_IS, productions: 1),
                                                         modelRow("beta", RESEARCH)]                          | [systemRow("alpha"), systemRow("beta", wentWrong: 1)]
        "aReviewNothingCameBackFor"                  || [modelRow("alpha", AS_IT_IS, productions: 1),
                                                         modelRow("beta", RESEARCH)]                          | [systemRow("alpha"), systemRow("beta", neverCameBack: 1)]
        "aReviewStillOut"                            || [modelRow("alpha", AS_IT_IS, productions: 1),
                                                         modelRow("beta", RESEARCH)]                          | [systemRow("alpha"), systemRow("beta")]
        "oneModelReviewingInTheOtherMode"            || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1),
                                                         modelRow("alpha", RESEARCH, reviews: 1, refusing: 1)] | [systemRow("alpha")]
        "oneModelProducingAndReviewingInOneMode"     || [modelRow("alpha", AS_IT_IS, productions: 1, refusedOnReview: 1,
                                                                  reviews: 1, refusing: 1)]                   | [systemRow("alpha")]
    }

    def "counts every answer that did not fit, producing or reviewing, and nothing the helper gave nor code gone wrong"() {
        given:
        measuring()
        "$arranged"()

        expect:
        measuredWhole() == [models, system]

        where:
        arranged                                   || models | system
        "aProductionThatDidNotFit"                 || [modelRow("alpha", AS_IT_IS, didNotFit: 1)]           | [systemRow("alpha")]
        "aReviewThatDidNotFit"                     || [modelRow("alpha", AS_IT_IS, productions: 1),
                                                       modelRow("beta", RESEARCH, didNotFit: 1)]            | [systemRow("alpha"), systemRow("beta")]
        "oneModelsProductionAndReviewDidNotFit"    || [modelRow("alpha", AS_IT_IS, productions: 1, didNotFit: 2)] | [systemRow("alpha")]
        "aCodeTryErroredBesideOneReviewed"         || [modelRow("beta", RESEARCH, reviews: 1)]              | [systemRow("beta")]
        "aHelpCallAnswered"                        || [modelRow("helper", RESEARCH)]                        | [systemRow("helper")]
    }

    def "counts for each model, whatever its mode, the calls that went wrong, never came back or were turned away"() {
        given:
        measuring()
        "$arranged"()

        expect:
        measuredWhole() == [models, system]

        where:
        arranged                        || models                                          | system
        "aHelpCallThatWentWrong"        || [modelRow("helper", RESEARCH)]                  | [systemRow("helper", wentWrong: 1)]
        "aCallTurnedAwayOnceThenTaken"  || [modelRow("alpha", AS_IT_IS, productions: 1)]   | [systemRow("alpha", turnedAway: 1)]
        "aCallOnlyEverTurnedAway"       || [modelRow("delta", AS_IT_IS)]                   | [systemRow("delta", turnedAway: 2)]
        "oneModelAskedInBothModes"      || [modelRow("alpha", AS_IT_IS, productions: 1),
                                            modelRow("alpha", RESEARCH)]                   | [systemRow("alpha", wentWrong: 2, turnedAway: 2)]
    }

    def "lists every model and mode a call was sent to, however it ended, and nothing no call was sent to"() {
        given:
        measuring()
        "$arranged"()

        expect:
        measuredWhole() == [models, system]

        where:
        arranged               || models                            | system
        "nothingEverCalled"    || []                                | []
        "aProductionStillOut"  || [modelRow("alpha", AS_IT_IS)]     | [systemRow("alpha")]
        "aHelpCallStillOut"    || [modelRow("helper", RESEARCH)]    | [systemRow("helper")]
        "aStepNeverSent"       || []                                | []
    }

    /**
     * Written round the schema, which holds every name a call can be made under, as only a restore could. A
     * check outlasts switching the keys off, so the one on a call's mode is dropped from this database first.
     */
    def "fails the whole read where a call names a model or a mode this system will not show"() {
        given:
        measuring()
        aProductionTaken()
        writtenRoundTheSchema("""
                alter table model_calls drop constraint model_calls_mode_shape;
                insert into model_calls (run_id, root_run_id, purpose, root_version_id, request, model, mode,
                                         envelope_version, sent_count, created_by)
                values ('${RUN}', '${RUN}', 'help', '${WORKFLOW_VERSION}', '{}', '${model}', '${mode}', 1, 100,
                        '${WORKFLOW_RUNNER}')
                """)

        when:
        measurements.read()

        then:
        def failed = thrown(IllegalStateException)

        and: "naming no part of what was stored"
        causesOf(failed).every { !(it.message ?: "").contains(model) && !(it.message ?: "").contains(mode) }

        where:
        model      | mode
        NOT_A_NAME | RESEARCH.value()
        "helper"   | "Not A Mode"
    }

    /** A call committed between the two reads would otherwise stand in one table and not in the other. */
    def "reads both tables as one moment of the store, whatever is committed between the two reads"() {
        given:
        measuring()
        aProductionTaken()
        def late = step("gamma", AS_IT_IS)
        def production = tryOf(late)
        def attempt = attempted(late, "produce", production)
        def between = new CommittedBetweenReads(store.database, """
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, outcome, error_detail,
                                         ended_at, created_by)
                values ('${RUN}', '${RUN}', 'produce', '${attempt}', '${late.runStep}', '${production}', 'gamma',
                        '${stored(AS_IT_IS)}', 1, 100, 'errored', 'Timed out.', now(), '${WORKFLOW_RUNNER}')
                """)
        def reading = new Measurements(JdbcClient.create(between), new JdbcTransactionManager(between))

        when:
        def measured = reading.read()
        def afterwards = reading.read()

        then:
        measured.models()*.model()*.value() == ["alpha"]
        measured.system()*.model()*.value() == ["alpha"]

        and: "while the call was committed, as the next read shows in both"
        between.committed
        afterwards.models()*.model()*.value() == ["alpha", "gamma"]
        afterwards.system()*.model()*.value() == ["alpha", "gamma"]
    }

    /** Every mode here orders before the word the store keeps for no mode, which still comes first. */
    def "orders models by code point and under each the model run as it is, then its modes by code point, whatever the database defaults to"() {
        given:
        using(underLocales[locale])
        assert defaultProvider() == provider
        assert session.sql("select datcollate from pg_database where datname = current_database()")
                .query(String).single() == "C"

        and: "a default ordering the names otherwise than by code point, where it is ICU's"
        assert defaultOrderOf(APART) == byDefault
        assert APART.every { it < ModelMode.RESERVED_FOR_AS_IT_IS }

        and:
        APART.each { produced(step("ab", new ModelMode(it)), "came_back") }
        produced(step("ab", AS_IT_IS), "came_back")
        produced(step("a_b", AS_IT_IS), "came_back")
        produced(step("a1", AS_IT_IS), "came_back")

        when:
        def measured = measurements.read()

        then:
        measured.models().collect { [it.model().value(), it.mode()?.value()] } ==
                [["a1", null], ["a_b", null], ["ab", null], ["ab", "a1"], ["ab", "a_b"], ["ab", "ab"]]
        measured.system()*.model()*.value() == ["a1", "a_b", "ab"]

        where:
        locale | provider | byDefault
        "c"    | "c"      | ["a1", "a_b", "ab"]
        "icu"  | "i"      | ["a_b", "a1", "ab"]
    }

    // ---------------------------------------------------------------- what each case arranges

    private void nothingEverCalled() {}

    private void aProductionTaken() {
        produced(step("alpha", AS_IT_IS), "came_back")
    }

    private void aProductionThatDidNotFit() {
        produced(step("alpha", AS_IT_IS), "came_back", "did_not_fit")
    }

    private void aProductionWhoseCallWentWrong() {
        produced(step("alpha", AS_IT_IS), "errored", "errored")
    }

    private void aProductionNothingCameBackFor() {
        produced(step("alpha", AS_IT_IS), "nothing_came_back", "nothing_came_back")
    }

    private void aProductionStillOut() {
        def step = step("alpha", AS_IT_IS)
        def production = tryOf(step)
        called(step, "produce", attempted(step, "produce", production), production, null)
    }

    /** Measured too long to send, so nothing was, and the step waits. */
    private void aStepNeverSent() {
        heldOnLength()
    }

    /** Answered by a person on a step a model produces for, which the model reviewing it then refuses. */
    private void aPersonsProductionTheModelRefused() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        def production = session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, explanation,
                                         created_by, created_by_kind, ended_at, ended_by, ended_by_kind)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', true, ?, ?::uuid, ?, 'person', 'Read by hand.',
                        ?::uuid, 'person', now(), ?::uuid, 'person')
                returning cast(production_id as text)
                """).params(step.runStep, RUN, RUN, TRIES, QUESTION_VERSION, ++step.tries, FIRST_STEWARD, FIRST_STEWARD)
                .query(String).single()
        valued(production, "person")
        reviewedByTheModel(step, production, "came_back", null, [(DETAILS): "refused", (NOTE): "assured"])
    }

    private void aProductionRefusedByAPerson() {
        def step = step("alpha", AS_IT_IS)
        reviewedByAPerson(produced(step, "came_back"), [(DETAILS): "refused", (NOTE): "assured"])
    }

    private void aProductionAssuredByAPerson() {
        def step = step("alpha", AS_IT_IS)
        reviewedByAPerson(produced(step, "came_back"), [(DETAILS): "assured", (NOTE): "assured"])
    }

    private void aProductionRefusedForLengthAlone() {
        def production = produced(step("alpha", AS_IT_IS), "came_back")
        reviewedByAPerson(production, [(DETAILS): "assured", (NOTE): "assured"])
        refusedForLength(production, SUMMARY)
    }

    /** A value that needed a review, assured on it, then refused for length naming that assurance. */
    private void aValueAssuredThenRefusedForLength() {
        def production = produced(step("alpha", AS_IT_IS), "came_back")
        def assurance = reviewedByAPerson(production, [(DETAILS): "assured", (NOTE): "assured"])
        refusedForLength(production, DETAILS, assurance)
    }

    private void aProductionWithTwoValuesRefused() {
        reviewedByAPerson(produced(step("alpha", AS_IT_IS), "came_back"), [(DETAILS): "refused", (NOTE): "refused"])
    }

    private void aProductionRefusedOnReviewAndForLength() {
        def production = produced(step("alpha", AS_IT_IS), "came_back")
        reviewedByAPerson(production, [(DETAILS): "refused", (NOTE): "assured"])
        refusedForLength(production, SUMMARY)
    }

    /** Too long for the model reviewing the step, so a person reviews in its place, and refuses. */
    private void aReviewNeverSentAndMadeByAPerson() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        def production = produced(step, "came_back")
        def tooLong = attempted(step, "review", production, true)
        def review = session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                     too_long_attempt_id, created_by)
                select production.production_id, production.run_step_id, production.root_run_id, production.ended_by,
                       production.reviewed_by_model, ?::uuid, ?::uuid
                  from productions production
                 where production.production_id = ?::uuid
                returning cast(review_id as text)
                """).params(tooLong, FIRST_STEWARD, production).query(String).single()
        decided(review, false, [(DETAILS): "refused", (NOTE): "assured"])
    }

    private void aReviewRefusingOneValueOfTwo() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        reviewedByTheModel(step, produced(step, "came_back"), "came_back", null,
                [(DETAILS): "refused", (NOTE): "assured"])
    }

    private void aReviewAssuringEveryValue() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        reviewedByTheModel(step, produced(step, "came_back"), "came_back", null,
                [(DETAILS): "assured", (NOTE): "assured"])
    }

    private void aReviewAssuringEveryValueThenOneForLength() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        def production = produced(step, "came_back")
        reviewedByTheModel(step, production, "came_back", null, [(DETAILS): "assured", (NOTE): "assured"])
        refusedForLength(production, SUMMARY)
    }

    /** A refusal the model gave no reason for is held so too. */
    private void aReviewThatDidNotFit() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        reviewedByTheModel(step, produced(step, "came_back"), "came_back", "did_not_fit", [:])
    }

    private void aReviewWhoseCallWentWrong() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        reviewedByTheModel(step, produced(step, "came_back"), "errored", "errored", [:])
    }

    private void aReviewNothingCameBackFor() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        reviewedByTheModel(step, produced(step, "came_back"), "nothing_came_back", "nothing_came_back", [:])
    }

    /** Sent to review and not yet ended, so there is no review for it yet. */
    private void aReviewStillOut() {
        def step = step("alpha", AS_IT_IS, "beta", RESEARCH)
        def production = produced(step, "came_back")
        called(step, "review", attempted(step, "review", production), production, null)
    }

    private void oneModelReviewingInTheOtherMode() {
        def step = step("alpha", AS_IT_IS, "alpha", RESEARCH)
        reviewedByTheModel(step, produced(step, "came_back"), "came_back", null,
                [(DETAILS): "refused", (NOTE): "assured"])
    }

    private void oneModelProducingAndReviewingInOneMode() {
        def step = step("alpha", AS_IT_IS, "alpha", AS_IT_IS)
        reviewedByTheModel(step, produced(step, "came_back"), "came_back", null,
                [(DETAILS): "refused", (NOTE): "assured"])
    }

    /** Only a production taken is reviewed, so the one that did not fit is a try before it. */
    private void oneModelsProductionAndReviewDidNotFit() {
        def step = step("alpha", AS_IT_IS, "alpha", AS_IT_IS)
        produced(step, "came_back", "did_not_fit")
        reviewedByTheModel(step, produced(step, "came_back"), "came_back", "did_not_fit", [:])
    }

    /** Code gone wrong, what it gave back kept, is neither an answer nor a call; only the try beside it is reviewed. */
    private void aCodeTryErroredBesideOneReviewed() {
        def step = codeStep("beta", RESEARCH)
        codeTry(step, true)
        def reviewed = codeTry(step, false)
        reviewedByTheModel(step, reviewed, "came_back", null, [(RECEIPT): "assured"])
    }

    private void aHelpCallAnswered() {
        helped("came_back")
    }

    private void aHelpCallThatWentWrong() {
        helped("errored")
    }

    private void aHelpCallStillOut() {
        helped(null)
    }

    /**
     * Turned away once, then again as what may be spent with it was used up, and never sent again: the
     * step is held back on it, as a call turned away every time holds its step.
     */
    private void aCallOnlyEverTurnedAway() {
        def step = step("delta", AS_IT_IS)
        def production = tryOf(step)
        def attempt = attempted(step, "produce", production)
        def call = called(step, "produce", attempt, production, "turned_away")
        turnedAway(call, false)
        turnedAway(call, true)
        session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                            run_step_send_attempt_id, created_by)
                values (?::uuid, ?::uuid, 'question', true, 'turned_away', ?::uuid, ?::uuid)
                """).params(step.runStep, RUN, attempt, WORKFLOW_RUNNER).update()
    }

    private void aCallTurnedAwayOnceThenTaken() {
        turnedAway(callOf(produced(step("alpha", AS_IT_IS), "came_back")), false)
    }

    private void oneModelAskedInBothModes() {
        turnedAway(callOf(produced(step("alpha", AS_IT_IS), "came_back")), false)
        produced(step("alpha", AS_IT_IS), "errored", "errored")
        turnedAway(callOf(produced(step("alpha", RESEARCH), "errored", "errored")), false)
    }

    /** Taken three times, one of them refused on two values; beside them one that did not fit, and two lost. */
    private void manyTriesOfOneModel() {
        def step = step("alpha", AS_IT_IS)
        reviewedByAPerson(produced(step, "came_back"), [(DETAILS): "refused", (NOTE): "refused"])
        produced(step, "came_back")
        produced(step, "came_back")
        produced(step, "came_back", "did_not_fit")
        produced(step, "errored", "errored")
        produced(step, "nothing_came_back", "nothing_came_back")
    }

    // ---------------------------------------------------------------- how a row is written

    /** A step of the workflow answering the question that no model reviews, reached in the run. */
    private Map step(String model, ModelMode mode) {
        step(model, mode, null, null)
    }

    /** A step of the workflow answering the question, reached in the run. */
    private Map step(String model, ModelMode mode, String reviewer, ModelMode reviewerMode) {
        def position = ++positions
        def workflowStep = session.sql("""
                insert into workflow_steps (entry_version_id, position, name, kind, pinned_version_id, pinned_kind,
                                            producer, producer_model, producer_mode, tries, reviewer_model,
                                            reviewer_mode, created_by)
                values (?::uuid, ?, ?, 'entry', ?::uuid, 'question', 'model', ?, ?, ?, ?, ?, ?::uuid)
                returning cast(workflow_step_id as text)
                """).params(WORKFLOW_VERSION, position, "step_" + position, QUESTION_VERSION, model, stored(mode), TRIES,
                reviewer, reviewer == null ? null : stored(reviewerMode), SEEDER).query(String).single()
        def runStep = session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'entry', ?::uuid, 'question', 'model', ?, ?, ?::uuid)
                returning cast(run_step_id as text)
                """).params(RUN, WORKFLOW_VERSION, workflowStep, QUESTION_VERSION, reviewer != null, TRIES, WORKFLOW_RUNNER)
                .query(String).single()
        [workflowStep: workflowStep, runStep: runStep, kind: "question", model: model, mode: mode, reviewer: reviewer,
         reviewerMode: reviewerMode, reviewed: reviewer != null, tries: 0]
    }

    /** A step of the workflow running code, reviewed by the model named, reached in the run. */
    private Map codeStep(String reviewer, ModelMode reviewerMode) {
        def position = ++positions
        def workflowStep = session.sql("""
                insert into workflow_steps (entry_version_id, position, name, kind, code_step, producer, tries,
                                            reviewer_model, reviewer_mode, created_by)
                values (?::uuid, ?, ?, 'code_step', cast(? as code_step), 'code', ?, ?, ?, ?::uuid)
                returning cast(workflow_step_id as text)
                """).params(WORKFLOW_VERSION, position, "step_" + position, CODE_STEP, TRIES, reviewer,
                stored(reviewerMode), SEEDER).query(String).single()
        def runStep = session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, producer,
                                       reviewed_by_model, tries, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', true, ?, ?::uuid)
                returning cast(run_step_id as text)
                """).params(RUN, WORKFLOW_VERSION, workflowStep, TRIES, WORKFLOW_RUNNER).query(String).single()
        [workflowStep: workflowStep, runStep: runStep, kind: "code_step", reviewer: reviewer,
         reviewerMode: reviewerMode, reviewed: true, tries: 0]
    }

    /** The code's next try of the step, ended, giving back a receipt, or errored with what it gave back kept. */
    private String codeTry(Map step, boolean errored) {
        def production = session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, may_run_again, try_number, producer, lost_reason,
                                         lost_detail, returned_by_code, created_by, ended_at, ended_by)
                values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', true, ?, true, ?, 'code',
                        cast(? as try_lost_reason), ?, ?, ?::uuid, now(), ?::uuid)
                returning cast(production_id as text)
                """).params(step.runStep, RUN, RUN, TRIES, ++step.tries, errored ? "errored" : null,
                errored ? "Code step gave back a field it does not declare." : null, errored ? "{}" : null,
                WORKFLOW_RUNNER, WORKFLOW_RUNNER).query(String).single()
        if (!errored) {
            session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, field_name, field_standing,
                                                   value)
                    values (?::uuid, 'code_step', 'code', ?, 'never', '"sent"')
                    """).params(production, RECEIPT).update()
        }
        production
    }

    /** The model's next try of the step, ended as its call did, with what it gave back where it was taken. */
    private String produced(Map step, String outcome, String lostReason = null) {
        def production = tryOf(step)
        called(step, "produce", attempted(step, "produce", production), production, outcome)
        session.sql("""
                update productions
                   set model_call_id = (select call.model_call_id from model_calls call
                                         where call.production_id = productions.production_id
                                           and call.purpose = 'produce'),
                       model_call_outcome = cast(? as model_call_outcome),
                       ended_at = now(),
                       ended_by = ?::uuid,
                       lost_reason = cast(? as try_lost_reason),
                       did_not_fit_reason = case when cast(? as text) = 'did_not_fit'
                                                 then 'not_the_shape'::did_not_fit_reason end
                 where production_id = ?::uuid
                """).params(outcome, WORKFLOW_RUNNER, lostReason, lostReason, production).update()
        if (outcome == "came_back" && lostReason == null) {
            valued(production, "model")
        }
        production
    }

    private void valued(String production, String producer) {
        [(DETAILS): "never", (NOTE): "never", (SUMMARY): "always"].each { field, standing ->
            session.sql("""
                    insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                                   declaration_field_id, field_standing, value)
                    values (?::uuid, 'question', cast(? as step_producer), ?::uuid, ?::uuid, cast(? as field_standing),
                            '"said"')
                    """).params(production, producer, QUESTION_VERSION, field, standing).update()
        }
    }

    private String tryOf(Map step) {
        session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', ?, ?, ?::uuid, ?, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(step.runStep, RUN, RUN, step.reviewed, TRIES, QUESTION_VERSION, ++step.tries, WORKFLOW_RUNNER)
                .query(String).single()
    }

    /** What would be sent, to the model the step names for the purpose. */
    private String attempted(Map step, String purpose, String production, boolean tooLong = false) {
        def producing = purpose == "produce"
        session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose, model,
                                                    mode, production_id, too_long, payload, created_by, created_by_kind)
                values (?::uuid, ?::uuid, cast(? as run_step_kind), ?::uuid, cast(? as model_call_purpose), ?, ?,
                        ?::uuid, ?, '{}', ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(step.runStep, RUN, step.kind, step.workflowStep, purpose,
                producing ? step.model : step.reviewer, stored(producing ? step.mode : step.reviewerMode), production,
                tooLong, WORKFLOW_RUNNER).query(String).single()
    }

    /** The call sending an attempt, ended as it came to, or still out where it has not. */
    private String called(Map step, String purpose, String attempt, String production, String outcome) {
        def producing = purpose == "produce"
        session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, came_back_count,
                                         outcome, answer, error_detail, ended_at, created_by)
                values (?::uuid, ?::uuid, cast(? as model_call_purpose), ?::uuid, ?::uuid, ?::uuid, ?, ?, 1, 100,
                        ${ended(outcome)}, ?::uuid)
                returning cast(model_call_id as text)
                """).params(RUN, RUN, purpose, attempt, step.runStep, production, producing ? step.model : step.reviewer,
                stored(producing ? step.mode : step.reviewerMode), WORKFLOW_RUNNER).query(String).single()
    }

    private void helped(String outcome) {
        session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, root_version_id, request, model, mode,
                                         envelope_version, sent_count, came_back_count, outcome, answer, error_detail,
                                         ended_at, created_by)
                values (?::uuid, ?::uuid, 'help', ?::uuid, '{"question": "Why?"}', 'helper', '${RESEARCH.value()}', 1, 100,
                        ${ended(outcome)}, ?::uuid)
                """).params(RUN, RUN, WORKFLOW_VERSION, WORKFLOW_RUNNER).update()
    }

    /** What a call carries once it ended as {@code outcome}: counted back, answered, gone wrong and when. */
    private static String ended(String outcome) {
        switch (outcome) {
            case "came_back": return "40, 'came_back', '{}', null, now()"
            case "errored": return "null, 'errored', null, 'Timed out.', now()"
            case null: return "null, null, null, null, null"
            default: return "null, '${outcome}', null, null, now()"
        }
    }

    private String callOf(String production) {
        session.sql("select cast(model_call_id as text) from productions where production_id = ?::uuid")
                .param(production).query(String).single()
    }

    private void turnedAway(String call, boolean spentUp) {
        session.sql("""
                insert into model_call_turnaways (model_call_id, said, spent_up, model_call_outcome, created_by)
                values (?::uuid, 'Busy.', ?, case when ? then 'turned_away'::model_call_outcome end, ?::uuid)
                """).params(call, spentUp, spentUp, WORKFLOW_RUNNER).update()
    }

    /** A person reviewing a production of a step no model reviews, and deciding each value as named. */
    private String reviewedByAPerson(String production, Map<String, String> decisions) {
        def review = session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                     created_by)
                select production.production_id, production.run_step_id, production.root_run_id, production.ended_by,
                       production.reviewed_by_model, ?::uuid
                  from productions production
                 where production.production_id = ?::uuid
                returning cast(review_id as text)
                """).params(FIRST_STEWARD, production).query(String).single()
        decided(review, false, decisions)
        review
    }

    /** A review that did not fit says why, as the store requires; which way is no concern of what is measured. */
    private void reviewedByTheModel(
            Map step, String production, String outcome, String lostReason, Map<String, String> decisions) {
        def call = called(step, "review", attempted(step, "review", production), production, outcome)
        def review = session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                     model_call_id, model_call_outcome, lost_reason, did_not_fit_reason, created_by,
                                     created_by_kind)
                select production.production_id, production.run_step_id, production.root_run_id, production.ended_by,
                       production.reviewed_by_model, ?::uuid, cast(? as model_call_outcome), cast(? as try_lost_reason),
                       case when cast(? as text) = 'did_not_fit' then 'undecided'::did_not_fit_reason end, ?::uuid,
                       'system'
                  from productions production
                 where production.production_id = ?::uuid
                returning cast(review_id as text)
                """).params(call, outcome, lostReason, lostReason, WORKFLOW_RUNNER, production).query(String).single()
        decided(review, false, decisions)
    }

    /** A person refusing one value for the hold on length in the run, naming where it was assured if it was. */
    private void refusedForLength(String production, String field, String assuredIn = null) {
        def review = session.sql("""
                insert into reviews (production_id, run_step_id, root_run_id, production_ended_by, reviewed_by_model,
                                     run_step_hold_id, hold_run_id, created_by)
                select production.production_id, production.run_step_id, production.root_run_id, production.ended_by,
                       production.reviewed_by_model, ?::uuid, ?::uuid, ?::uuid
                  from productions production
                 where production.production_id = ?::uuid
                returning cast(review_id as text)
                """).params(heldOnLength(), RUN, FIRST_STEWARD, production).query(String).single()
        decided(review, true, [(field): "refused"], assuredIn)
    }

    /** A step of its own held back, what it would send being too long, which calls nobody. */
    private String heldOnLength() {
        if (hold == null) {
            def held = step("alpha", AS_IT_IS)
            def tooLong = attempted(held, "produce", tryOf(held), true)
            hold = session.sql("""
                    insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                                run_step_send_attempt_id, created_by)
                    values (?::uuid, ?::uuid, 'question', true, 'too_long', ?::uuid, ?::uuid)
                    returning cast(run_step_hold_id as text)
                    """).params(held.runStep, RUN, tooLong, WORKFLOW_RUNNER).query(String).single()
        }
        hold
    }

    /** Each value of the production reviewed, named by its field, decided as named. */
    private void decided(String review, boolean forLength, Map<String, String> decisions, String assuredIn = null) {
        decisions.each { field, outcome ->
            def written = session.sql("""
                    insert into review_decisions (review_id, production_value_id, production_id, for_length,
                                                  value_needs_review, outcome, explanation, assured_in_review_id)
                    select review.review_id, value.production_value_id, value.production_id, ?, value.needs_review,
                           cast(? as review_outcome), case when ? = 'refused' then 'Not so.' end,
                           case when ? and value.needs_review then cast(? as uuid) end
                      from production_values value
                      join reviews review on review.production_id = value.production_id
                     where review.review_id = ?::uuid
                       and coalesce(cast(value.declaration_field_id as text), value.field_name) = ?
                    """).params(forLength, outcome, outcome, forLength, assuredIn, review, field).update()
            assert written == 1: "no value of ${field} was there to decide"
        }
    }

    // ---------------------------------------------------------------- the store

    /** The group, the question and the workflow in service, what the question gives back, and one run. */
    private static void populate(LibraryStore store) {
        store.group(GROUP, "MEASURED", "Measured")
        store.entry(QUESTION, GROUP, "question", "Summarise a complaint")
        store.entry(WORKFLOW, GROUP, "workflow", "Handle a complaint")
        store.seeded(QUESTION_VERSION, QUESTION, 1)
        store.seeded(WORKFLOW_VERSION, WORKFLOW, 1)
        store.content(QUESTION_VERSION, "question")
        store.content(WORKFLOW_VERSION, "workflow")
        [
            "update workflow_versions set may_be_helped = true, helper_model = 'helper', helper_mode = '${RESEARCH.value()}'" +
                    " where entry_version_id = '${WORKFLOW_VERSION}'",
            "alter type code_step add value '${CODE_STEP}'",
            "insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name," +
                    " kind, must_be_given, standing, created_by) values" +
                    " ('${DETAILS}', '${QUESTION_VERSION}', 'question', 'gives', 1, 'details', 'text', true, 'never', '${SEEDER}')," +
                    " ('${NOTE}', '${QUESTION_VERSION}', 'question', 'gives', 2, 'note', 'text', true, 'never', '${SEEDER}')," +
                    " ('${SUMMARY}', '${QUESTION_VERSION}', 'question', 'gives', 3, 'summary', 'text', true, 'always', '${SEEDER}')",
            "insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth," +
                    " started_with, created_by) values ('${RUN}', '${GROUP}', 1, 'Complaint from Ada', '${WORKFLOW}'," +
                    " '${WORKFLOW_VERSION}', '${RUN}', 0, '{}', '${FIRST_STEWARD}')",
        ].each { store.session.sql(it as String).update() }
    }

    private void measuring() {
        def copied = LibraryStore.copied(server, "measured_" + (++databasesMade))
        populate(copied)
        using(copied)
    }

    private void using(LibraryStore chosen) {
        store = chosen
        session = chosen.session
        measurements = new Measurements(session, chosen.transactionManager())
        positions = 0
        hold = null
    }

    /** One statement on a connection of its own, with every foreign key and trigger switched off. */
    private void writtenRoundTheSchema(String statement) {
        store.database.connection.withCloseable { Connection connection ->
            connection.createStatement().withCloseable {
                it.execute("set session_replication_role = replica")
                it.execute(statement)
            }
        }
    }

    private String defaultProvider() {
        session.sql("select datlocprovider::text from pg_database where datname = current_database()")
                .query(String).single()
    }

    private List<String> defaultOrderOf(List<String> names) {
        session.sql("select name from unnest(cast(? as text[])) name order by name")
                .param(names as String[]).query(String).list()
    }

    private List measuredWhole() {
        def measured = measurements.read()
        [measured.models().collect {
            [it.model().value(), it.mode(), it.productions(), it.refusedOnReview(), it.reviews(), it.refusing(),
             it.didNotFit()]
        }, measured.system().collect { [it.model().value(), it.wentWrong(), it.neverCameBack(), it.turnedAway()] }]
    }

    /** A row of the models' table, every count not named being none. */
    static List modelRow(Map counts = [:], String name, ModelMode mode) {
        assert MODEL_COUNTS.containsAll(counts.keySet())
        [name, mode] + MODEL_COUNTS.collect { counts[it] ?: 0 }
    }

    /** A row of this system's table, every count not named being none. */
    static List systemRow(Map counts = [:], String name) {
        assert SYSTEM_COUNTS.containsAll(counts.keySet())
        [name] + SYSTEM_COUNTS.collect { counts[it] ?: 0 }
    }

    private static List<Throwable> causesOf(Throwable failed) {
        failed == null ? [] : [failed] + causesOf(failed.cause)
    }

    private static String stored(ModelMode mode) {
        mode == null ? ModelMode.RESERVED_FOR_AS_IT_IS : mode.value()
    }

    /**
     * Commits one statement from a connection of its own the moment the second statement of a read is
     * prepared, which is between the two reads a measurement makes.
     */
    private static final class CommittedBetweenReads extends DelegatingDataSource {

        private final String statement

        private int prepared = 0

        boolean committed = false

        CommittedBetweenReads(DataSource target, String statement) {
            super(target)
            this.statement = statement
        }

        @Override
        Connection getConnection() {
            new CommittingConnection(super.getConnection(), this)
        }

        void preparing() {
            if (++prepared == 2) {
                targetDataSource.connection.withCloseable { Connection separate ->
                    separate.createStatement().withCloseable { it.execute(statement) }
                }
                committed = true
            }
        }

        private static final class CommittingConnection implements Connection {

            @Delegate
            private final Connection connection

            private final CommittedBetweenReads between

            CommittingConnection(Connection connection, CommittedBetweenReads between) {
                this.connection = connection
                this.between = between
            }

            PreparedStatement prepareStatement(String sql) {
                between.preparing()
                connection.prepareStatement(sql)
            }
        }
    }
}
