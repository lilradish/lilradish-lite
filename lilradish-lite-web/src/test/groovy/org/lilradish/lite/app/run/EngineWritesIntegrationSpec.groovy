package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.codestep.CodeOutcome
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.RunStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * Every row a run's steps are written with, on a real server running the real baseline: each guarded write
 * changes exactly the one row it names or fails, and none is made for a run of another tree than the one held.
 */
class EngineWritesIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String OTHER_RUN = "00000008-0000-4000-8000-000000000e41"

    /** The code workflow's check, reading what send gives back. */
    static final CodeError.ReadBy READ_BY_CHECK = new CodeError.StepReads(UUID.fromString(CodeWorkflow.CHECK))

    /** A field of what a workflow gives back, a level down, that what send gives back is read into. */
    static final CodeError.ReadBy READ_INTO_RESULT =
            new CodeError.OutputReads([new FieldName("result"), new FieldName("receipt")])

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    RunTree tree

    RunSnapshots snapshots

    EngineWrites writes

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "engine_writes_" + (++databasesMade))
        tree = new RunTree(store.session)
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE)
        writes = new EngineWrites(store.session)
        TicketWorkflow.seed(store)
        store.session.sql("""
                update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                 where workflow_step_id = ?::uuid
                """).params(SUMMARISE).update()
        TicketWorkflow.run(store)
        TicketWorkflow.run(store, OTHER_RUN, 2)
    }

    private static RunId runId(String spelled) {
        new RunId(UUID.fromString(spelled))
    }

    /** Summarise's row in {@code run}, as the engine writes it with the first thing written of it. */
    private String runStep(String run) {
        store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.pinned_version_id,
                       step.pinned_kind, step.producer, step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step
                 where step.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(run, SYSTEM, SUMMARISE).query(String).single()
    }

    /** Summarise held back in {@code run} for {@code reason}, a turning away holding the model's asked try. */
    private void heldFor(String run, String runStep, String reason) {
        String attempt = null
        if (reason == "turned_away") {
            def production = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                             created_by)
                    values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, 1, 'model', ?::uuid)
                    returning cast(production_id as text)
                    """).params(runStep, run, run, SUMMARISE_VERSION, SYSTEM).query(String).single()
            attempt = store.session.sql("""
                    insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose,
                                                        model, mode, production_id, payload, created_by,
                                                        created_by_kind)
                    values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid, '{}',
                            ?::uuid, 'system')
                    returning cast(run_step_send_attempt_id as text)
                    """).params(runStep, run, SUMMARISE, production, SYSTEM).query(String).single()
        }
        store.session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                            run_step_send_attempt_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', true, cast(? as run_step_hold_reason), ?::uuid, ?::uuid, 'system')
                """).params(runStep, run, reason, attempt, SYSTEM).update()
    }

    private List<String> holds() {
        store.texts("select reason || ' ' || coalesce(released_by::text, '-') from run_step_holds order by reason")
    }

    /** Only a stop's hold goes when its stop is let go; one of any other kind waits on what answers it. */
    def "releasing the hold a stop made releases it, and a hold of any other reason is left and the release fails"() {
        given:
        def held = runStep(RUN)
        heldFor(RUN, held, reason)

        when:
        def failed = null
        try {
            store.transactions().executeWithoutResult {
                def locked = tree.lock(groupId(GROUP), runId(RUN)).orElseThrow()
                writes.released(locked, snapshots.locked(locked, runId(RUN)), new RunStepId(UUID.fromString(held)),
                        RunStepHoldReason.ENTRY_STOPPED)
            }
        } catch (IllegalStateException refused) {
            failed = refused.message
        }

        then:
        holds() == [reason + " " + releasedBy]
        failed == (releasedBy == "-"
                ? "Wrote the hold on step ${held} as 0 rows, not one: it was changed outside its tree's lock" as String
                : null)

        where:
        reason          || releasedBy
        "entry_stopped" || SYSTEM
        "turned_away"   || "-"
    }

    def "a write naming a run of another tree than the one held is refused before anything is written"() {
        given:
        def elsewhere = runStep(OTHER_RUN)
        def other = snapshots.asRead(groupId(GROUP), runId(OTHER_RUN))

        when:
        store.transactions().executeWithoutResult {
            writes.held(tree.lock(groupId(GROUP), runId(RUN)).orElseThrow(), other,
                    new RunStepId(UUID.fromString(elsewhere)), RunStepHoldReason.ENTRY_STOPPED)
        }

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Run ${OTHER_RUN} is not of the tree held" as String

        and:
        holds() == []
    }

    /** Code has no confidence to offer, so a value standing above one waits on a review, as the store works it out. */
    def "a code try ended as it gave back is written with each value standing as the release declares, and no confidence"() {
        given:
        def aTry = codeTry()

        when:
        def ended = codeEnded(aTry, new CodeOutcome.Gave([new CodeOutcome.Given(new FieldName("receipt"),
                new JsonValue.JsonString("R-1"), FieldStanding.ABOVE_CONFIDENCE, 80)]))

        then:
        ended
        store.texts("""
                select producer || ' ' || field_name || ' ' || field_standing || ' ' || standing_threshold || ' '
                       || value::text || ' ' || coalesce(confidence::text, '-') || ' ' || needs_review
                  from production_values
                """) == ['code receipt above_confidence 80 "R-1" - true']
        store.texts("select ended_by_kind || ' ' || coalesce(lost_reason::text, '-') from productions") == ["system -"]
    }

    def "a code try ended already is not ended again, and nothing it gave back is written"() {
        given:
        def aTry = codeTry()
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'system',
                                       lost_reason = 'nothing_came_back'
                """).params(SYSTEM).update()

        when:
        def ended = codeEnded(aTry, new CodeOutcome.Gave([new CodeOutcome.Given(new FieldName("receipt"),
                new JsonValue.JsonString("R-1"), FieldStanding.ALWAYS, null)]))

        then:
        !ended
        store.count("select count(*) from production_values") == 0
        store.texts("select coalesce(lost_reason::text, '-') from productions") == ["nothing_came_back"]
    }

    /** Each part of why it went wrong is its own column, so nothing needs splitting from words to be read again. */
    def "a code try gone wrong for this system's reason is written with that reason, the field and member it names, and no words"() {
        given:
        def aTry = codeTry()

        when:
        def ended = codeEnded(aTry, new CodeOutcome.Errored(
                new CodeError.Fault(reason, path.collect { new FieldName(it) }, member, readBy), returned))

        then:
        ended
        codeEnd() == [row as String]

        and: "nothing given back is written"
        store.count("select count(*) from production_values") == 0

        where:
        reason                              | path             | member  | readBy                  | returned            || row
        CodeErrorReason.TOO_LONG            | ["receipt"]      | null    | null                    | '{"receipt":"R-9"}' || 'errored too_long receipt - - - - false {"receipt":"R-9"}'
        CodeErrorReason.NOT_DECLARED        | ["lines"]        | "extra" | null                    | '{"lines":[{}]}'    || 'errored not_declared lines extra - - - false {"lines":[{}]}'
        CodeErrorReason.NOT_DECLARED        | []               | ""      | null                    | '{"":1}'            || 'errored not_declared -  - - - false {"":1}'
        CodeErrorReason.TAKES_OTHERWISE     | ["parts", "sku"] | null    | null                    | null                || 'errored takes_otherwise parts.sku - - - - false -'
        CodeErrorReason.GIVES_OTHERWISE     | ["receipt"]      | null    | READ_BY_CHECK           | null                || "errored gives_otherwise receipt - ${CodeWorkflow.CHECK} - - false -"
        CodeErrorReason.GIVES_OTHERWISE     | ["receipt"]      | null    | READ_INTO_RESULT        | null                || 'errored gives_otherwise receipt - - result.receipt - false -'
        CodeErrorReason.GAVE_NOTHING        | []               | null    | null                    | null                || 'errored gave_nothing - - - - - false -'
        CodeErrorReason.FAILED_ON_THIS_SIDE | []               | null    | null                    | null                || 'errored failed_on_this_side - - - - - false -'
    }

    def "a code try whose code threw is written with what it said and whether that was cut, and no reason of this system's"() {
        given:
        def aTry = codeTry()

        when:
        def ended = codeEnded(aTry, CodeOutcome.thrown("x" * saidLength))

        then:
        ended
        codeEnd() == ["errored - - - - - ${"x" * keptLength} ${cut} -" as String]

        where:
        saidLength || keptLength | cut
        27         || 27         | false
        2049       || 2048       | true
    }

    def "a code try whose code threw saying nothing readable is written as that, with no words standing in for it"() {
        given:
        def aTry = codeTry()

        when:
        def ended = codeEnded(aTry, CodeOutcome.thrown(said))

        then:
        ended
        codeEnd() == ["errored said_nothing - - - - - false -"]

        where:
        said << [null, " \t\n "]
    }

    /** How the one code try ended: why, what that is about and reads it, what the code said and whether cut, what came back. */
    private List<String> codeEnd() {
        store.texts("""
                select lost_reason || ' ' || coalesce(code_error_reason::text, '-') || ' ' || coalesce(code_error_path, '-')
                       || ' ' || coalesce(code_error_member, '-') || ' ' || coalesce(code_error_read_by_step::text, '-')
                       || ' ' || coalesce(code_error_read_by_output, '-') || ' ' || coalesce(lost_detail, '-') || ' '
                       || lost_detail_truncated || ' ' || coalesce(returned_by_code, '-')
                  from productions where producer = 'code'
                """)
    }

    /** Summarise's model try in the ticket run, sent: its attempt and its call, out, measured at 40 units. */
    private String callOut() {
        def held = runStep(RUN)
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, 1, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(held, RUN, RUN, SUMMARISE_VERSION, SYSTEM).query(String).single()
        def attempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose,
                                                    model, mode, production_id, payload, created_by,
                                                    created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid, '{}',
                        ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(held, RUN, SUMMARISE, production, SYSTEM).query(String).single()
        store.session.sql("""
                insert into model_calls (run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id,
                                         production_id, model, mode, envelope_version, sent_count, created_by)
                values (?::uuid, ?::uuid, 'produce', ?::uuid, ?::uuid, ?::uuid, 'general', 'ordinary', 2, 40,
                        ?::uuid)
                returning cast(model_call_id as text)
                """).params(RUN, RUN, attempt, held, production, SYSTEM).query(String).single()
    }

    private boolean nothingCameBack(String call) {
        store.transactions().execute {
            writes.nothingCameBack(tree.lock(groupId(GROUP), runId(RUN)).orElseThrow(),
                    new ModelCallId(UUID.fromString(call)))
        }
    }

    private List<String> calls() {
        store.texts("""
                select outcome || ' ' || sent_count || ' ' || coalesce(came_back_count::text, '-') || ' '
                       || counted_by_model || ' ' || coalesce(answer, '-') || ' ' || coalesce(error_detail, '-')
                       || ' ' || (ended_at >= created_at)
                  from model_calls
                """)
    }

    private List<String> modelTries() {
        store.texts("""
                select production_id || ' ' || coalesce(ended_at::text, 'open')
                  from productions where producer = 'model'
                """)
    }

    /** What was sent stays as measured here, and what came back is not known, which no count stands in for. */
    def "a call nothing came back for is ended so, what was sent kept as measured and nothing counted as come back"() {
        given:
        def call = callOut()

        when:
        def ended = nothingCameBack(call)

        then:
        ended
        calls() == ["nothing_came_back 40 - false - - true"]
    }

    def "a call ended already is not ended again as nothing came back, and is left as it ended"() {
        given:
        def call = callOut()
        store.session.sql("update model_calls set outcome = 'errored', error_detail = 'Gone.', ended_at = now()")
                .update()

        when:
        def ended = nothingCameBack(call)

        then:
        !ended
        calls() == ["errored 40 - false - Gone. true"]
    }

    def "every open try of code under the tree held is ended by the system as nothing came back, and nothing else is"() {
        given:
        def aTry = codeTry()
        callOut()
        def modelTriesBefore = modelTries()

        when:
        def lost = store.transactions().execute {
            writes.codeLost(tree.lock(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN)).orElseThrow())
        }

        then:
        lost == [new ProductionId(UUID.fromString(aTry))]
        store.texts("""
                select ended_by || ' ' || ended_by_kind || ' ' || lost_reason || ' ' || coalesce(lost_detail, '-')
                       || ' ' || coalesce(returned_by_code, '-') || ' ' || may_run_again || ' '
                       || (ended_at >= created_at)
                  from productions where producer = 'code'
                """) == ["${SYSTEM} system nothing_came_back - - false true" as String]

        and: "another tree's open try, of a model, left as it was, and nothing given back"
        modelTries() == modelTriesBefore
        modelTries()[0].endsWith(" open")
        store.count("select count(*) from production_values") == 0
    }

    def "a tree with no try of code open has none ended"() {
        given:
        codeTry()
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'system',
                                       lost_reason = 'errored', lost_detail = 'Refused.'
                """).params(SYSTEM).update()
        def before = store.digestOf("productions")

        when:
        def lost = store.transactions().execute {
            writes.codeLost(tree.lock(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN)).orElseThrow())
        }

        then:
        lost == []
        store.digestOf("productions") == before
    }

    /** The code workflow's send, written in a run of its own with an open try of code, which may not run again. */
    private String codeTry() {
        CodeWorkflow.seed(store)
        CodeWorkflow.run(store)
        def runStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, producer,
                                       reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.producer,
                       step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step
                 where step.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(CodeWorkflow.RUN, SYSTEM, CodeWorkflow.SEND).query(String).single()
        store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, try_number, producer, may_run_again, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'code_step', 'code', false, 1, 1, 'code', false, ?::uuid)
                returning cast(production_id as text)
                """).params(runStep, CodeWorkflow.RUN, CodeWorkflow.RUN, SYSTEM).query(String).single()
    }

    private boolean codeEnded(String aTry, CodeOutcome outcome) {
        store.transactions().execute {
            writes.codeEnded(tree.lock(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN)).orElseThrow(),
                    new ProductionId(UUID.fromString(aTry)), outcome)
        }
    }

    /** A hold on length or on a turnaway is written beside the attempt it is for, and by nothing here. */
    def "a hold naming no attempt is written or released only on a stop or on a code step the release does not hold"() {
        given:
        def held = runStep(RUN)

        when:
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(RUN)).orElseThrow()
            writes."$act"(locked, snapshots.locked(locked, runId(RUN)), new RunStepId(UUID.fromString(held)), reason)
        }

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "A hold on length or on a turnaway names the attempt it is for"

        and:
        holds() == []

        where:
        [act, reason] << [["held", "released"], [RunStepHoldReason.TOO_LONG, RunStepHoldReason.TURNED_AWAY]].combinations()
    }
}
