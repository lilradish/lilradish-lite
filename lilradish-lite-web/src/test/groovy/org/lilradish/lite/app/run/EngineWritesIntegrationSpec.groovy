package org.lilradish.lite.app.run

import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.CONFIRM_VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.GROUP
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.RUN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARISE_VERSION
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.SUMMARY_IN
import static org.lilradish.lite.app.run.fixture.TicketWorkflow.TICKET_IN
import static org.lilradish.lite.testutil.library.LibraryStore.groupId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.run.fixture.CodeWorkflow
import org.lilradish.lite.app.run.fixture.TicketWorkflow
import org.lilradish.lite.app.search.SearchWays
import org.lilradish.lite.domain.codestep.CodeError
import org.lilradish.lite.domain.codestep.CodeErrorReason
import org.lilradish.lite.domain.codestep.CodeOutcome
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.run.InputRecord
import org.lilradish.lite.domain.run.ModelCallId
import org.lilradish.lite.domain.run.ProductionId
import org.lilradish.lite.domain.run.ProductionValueId
import org.lilradish.lite.domain.run.ReviewPayload
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunInTree
import org.lilradish.lite.domain.run.RunPayloads
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.RunStepId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.domain.workflow.StepProducer
import org.lilradish.lite.testutil.codestep.CodeStepsHeld
import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class EngineWritesIntegrationSpec extends Specification {

    static final String SYSTEM = RunRows.WORKFLOW_RUNNER

    static final String OTHER_RUN = "00000008-0000-4000-8000-000000000e41"

    static final CodeError.ReadBy READ_BY_CHECK = new CodeError.StepReads(UUID.fromString(CodeWorkflow.CHECK))

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
        snapshots = new RunSnapshots(store.session, CodeStepsHeld.NONE, new SearchWays([]))
        writes = new EngineWrites(store.session)
        TicketWorkflow.seed(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
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

    private String runStep(String run) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
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

    private void heldFor(String run, String runStep, String reason) {
        String attempt = null
        if (reason == "turned_away") {
            // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
            def production = store.session.sql("""
                    insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                             reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                             created_by)
                    values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, null, 'model', ?::uuid)
                    returning cast(production_id as text)
                    """).params(runStep, run, run, SUMMARISE_VERSION, SYSTEM).query(String).single()
            // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
            attempt = store.session.sql("""
                    insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose,
                                                        model, mode, production_id, payload, created_by,
                                                        created_by_kind)
                    values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid, '{}',
                            ?::uuid, 'system')
                    returning cast(run_step_send_attempt_id as text)
                    """).params(runStep, run, SUMMARISE, production, SYSTEM).query(String).single()
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts, enum type run_step_hold_reason.
        store.session.sql("""
                insert into run_step_holds (run_step_id, run_id, run_step_kind, calls_a_model, reason,
                                            run_step_send_attempt_id, created_by, created_by_kind)
                values (?::uuid, ?::uuid, 'question', true, cast(? as run_step_hold_reason), ?::uuid, ?::uuid, 'system')
                """).params(runStep, run, reason, attempt, SYSTEM).update()
    }

    private List<String> sourceAndValue(boolean reviewed = false) {
        if (reviewed) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    update workflow_steps set reviewer_model = 'general', reviewer_mode = 'ordinary'
                     where workflow_step_id = ?::uuid
                    """).params(SUMMARISE).update()
        }
        def sourceStep = runStep(RUN)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
        def sourceTry = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', ?, 2, ?::uuid, 1, 'person',
                        ?::uuid, now(), ?::uuid, 'person', 'Answered.')
                returning cast(production_id as text)
                """).params(sourceStep, RUN, RUN, reviewed, SUMMARISE_VERSION, SYSTEM, TicketWorkflow.CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'question', ?::uuid, ?::uuid, false)
                """).params(sourceTry, RUN, RUN, sourceStep, SUMMARISE, TICKET_IN).update()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts, RETURNING.
        def sourceValue = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'never', '"Earlier"'::jsonb)
                returning cast(production_value_id as text)
                """).params(sourceTry, SUMMARISE_VERSION, SUMMARY).query(String).single()
        [sourceStep, sourceTry, sourceValue]
    }

    private List<String> consumingTry(String sourceStep, String sourceTry, String sourceValue, boolean model = true) {
        if (model) {
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    update workflow_steps set producer = 'model', producer_model = 'general', producer_mode = 'ordinary'
                     where workflow_step_id = ?::uuid
                    """).params(CONFIRM).update()
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def step = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id,
                                       pinned_kind, producer, reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, held.entry_version_id, held.workflow_step_id, held.kind, held.pinned_version_id,
                       held.pinned_kind, held.producer, held.reviewed_by_model, held.tries, ?::uuid, 'system'
                  from workflow_steps held where held.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(RUN, SYSTEM, CONFIRM).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING, enum type step_producer.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', cast(? as step_producer), false, 1, ?::uuid, 1,
                        cast(? as step_producer), ?::uuid)
                returning cast(production_id as text)
                """).params(step, RUN, RUN, model ? 'model' : 'person', CONFIRM_VERSION,
                model ? 'model' : 'person', SYSTEM).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant,
                                               source_workflow_step_id, source_run_step_id, source_run_step_kind,
                                               source_production_id, source_production_value_id)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'question', ?::uuid, ?::uuid, false,
                        ?::uuid, ?::uuid, 'question', ?::uuid, ?::uuid)
                """).params(production, RUN, RUN, step, CONFIRM, SUMMARY_IN, SUMMARISE, sourceStep, sourceTry,
                sourceValue).update()
        [step, production]
    }

    def "a later try of the same source step cannot replace a direct input's recorded maker"() {
        given:
        def source = sourceAndValue()
        def consumer = consumingTry(source[0], source[1], source[2], false)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person',
                                       explanation = 'Answered.'
                 where production_id = ?::uuid
                """).params(TicketWorkflow.CAT, consumer[1]).update()
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        assert store.texts("select source_production_value_id::text from production_inputs where production_id = ?::uuid",
                consumer[1]) == [source[2]]

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now(), RETURNING.
        def laterTry = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by, ended_at, ended_by, ended_by_kind, explanation)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, 2,
                        'person', ?::uuid, now(), ?::uuid, 'person', 'Later.')
                returning cast(production_id as text)
                """).params(source[0], RUN, RUN, SUMMARISE_VERSION, SYSTEM, TicketWorkflow.CAT)
                .query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into production_inputs (production_id, run_id, root_run_id, run_step_id, run_step_kind,
                                               workflow_step_id, binding_id, binding_reads_a_constant)
                values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'question', ?::uuid, ?::uuid, false)
                """).params(laterTry, RUN, RUN, source[0], SUMMARISE, TICKET_IN).update()
        // DB-SPECIFIC: PostgreSQL ::jsonb/::uuid casts, RETURNING.
        def laterValue = store.session.sql("""
                insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                               declaration_field_id, field_standing, value)
                values (?::uuid, 'question', 'person', ?::uuid, ?::uuid, 'never', '"Later"'::jsonb)
                returning cast(production_value_id as text)
                """).params(laterTry, SUMMARISE_VERSION, SUMMARY).query(String).single()
        then:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        store.texts("select source_production_value_id::text from production_inputs where production_id = ?::uuid",
                consumer[1]) == [source[2]]

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts, UPDATE FROM.
        store.session.sql("""
                update production_inputs input
                   set source_production_id = value.production_id,
                       source_production_value_id = value.production_value_id
                  from production_values value
                 where input.production_id = ?::uuid and value.production_value_id = ?::uuid
                """).params(consumer[1], laterValue).update()

        then:
        thrown(org.springframework.dao.DataIntegrityViolationException)

        when:
        // DB-SPECIFIC: PostgreSQL ::text/::uuid casts.
        def savedSource = store.texts("""
                select source_production_value_id::text from production_inputs where production_id = ?::uuid
                """, consumer[1])

        then:
        savedSource == [source[2]]
    }

    def "a yielded consumer retains its input when deletion is rejected"() {
        given:
        def source = sourceAndValue()
        def consumer = consumingTry(source[0], source[1], source[2], false)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
        store.session.sql("""
                update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person',
                                       explanation = 'Answered.'
                 where production_id = ?::uuid
                """).params(TicketWorkflow.CAT, consumer[1]).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        assert store.count("select count(*) from production_inputs where production_id = ?::uuid", consumer[1]) == 1

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from production_inputs where production_id = ?::uuid")
                .params(consumer[1]).update()

        then:
        thrown(org.springframework.dao.DataIntegrityViolationException)

        and:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from production_inputs where production_id = ?::uuid", consumer[1]) == 1
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.count("select count(*) from productions where production_id = ?::uuid and yielded", consumer[1]) == 1
    }

    def "a review payload keeps its recorded input when deletion is rejected"() {
        given:
        def source = sourceAndValue(true)
        def original = snapshots.asRead(groupId(GROUP), runId(RUN))
        assert RunPayloads.toReview(RunInTree.root(original), original.steps()[0], original.steps()[0].tries()[0])
                instanceof ReviewPayload.Built

        when:
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("delete from production_inputs where production_id = ?::uuid")
                .params(source[1]).update()

        then:
        thrown(org.springframework.dao.DataIntegrityViolationException)

        when:
        def unchanged = snapshots.asRead(groupId(GROUP), runId(RUN))
        def built = RunPayloads.toReview(RunInTree.root(unchanged), unchanged.steps()[0],
                unchanged.steps()[0].tries()[0])

        then:
        built instanceof ReviewPayload.Built
    }

    private void writeInput(InputRecord input) {
        store.transactions().executeWithoutResult {
            def locked = tree.lock(groupId(GROUP), runId(RUN)).orElseThrow()
            def root = snapshots.locked(locked, runId(RUN))
            def step = root.steps()[0]
            def held = writes.runStep(locked, root, step)
            writes.tried(locked, root, held, 1, StepProducer.PERSON, null, null, [input])
        }
    }

    def "a missing non-null direct or component maker is refused without leaving a try"() {
        given:
        def missing = new ProductionValueId(UUID.randomUUID())
        def input = part
                ? new InputRecord(UUID.fromString(TICKET_IN), null,
                        [new InputRecord.Part([new FieldName("piece")], missing)])
                : new InputRecord(UUID.fromString(TICKET_IN), missing)

        when:
        writeInput(input)

        then:
        thrown(RuntimeException)
        store.count("select count(*) from productions") == 0
        store.count("select count(*) from production_inputs") == 0
        store.count("select count(*) from production_input_parts") == 0

        where:
        part << [false, true]
    }

    def "a root input with no maker remains a valid recorded input"() {
        when:
        writeInput(new InputRecord(UUID.fromString(TICKET_IN), null))

        then:
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select binding_id::text || ' ' || coalesce(source_production_value_id::text, '-')
                  from production_inputs
                """) == [TICKET_IN + " -"]
        store.count("select count(*) from productions") == 1
        store.count("select count(*) from production_input_parts") == 0
    }

    private List<String> holds() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select reason || ' ' || coalesce(released_by::text, '-') from run_step_holds order by reason")
    }

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

    def "a code try ended as it gave back is written with each value standing as the release declares, and no confidence"() {
        given:
        def aTry = codeTry()

        when:
        def ended = codeEnded(aTry, new CodeOutcome.Gave([new CodeOutcome.Given(new FieldName("receipt"),
                new JsonValue.JsonString("R-1"), FieldStanding.ABOVE_CONFIDENCE, 80)]))

        then:
        ended
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select producer || ' ' || field_name || ' ' || field_standing || ' ' || standing_threshold || ' '
                       || value::text || ' ' || coalesce(confidence::text, '-') || ' ' || needs_review
                  from production_values
                """) == ['code receipt above_confidence 80 "R-1" - true']
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select ended_by_kind || ' ' || coalesce(lost_reason::text, '-') from productions") == ["system -"]
    }

    def "a code try ended already is not ended again, and nothing it gave back is written"() {
        given:
        def aTry = codeTry()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select coalesce(lost_reason::text, '-') from productions") == ["nothing_came_back"]
    }

    def "a code try gone wrong for this system's reason is written with that reason, the field and member it names and whether that was cut, and no words"() {
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
        reason                              | path             | member                                | readBy           | returned            || row
        CodeErrorReason.TOO_LONG            | ["receipt"]      | null                                  | null             | '{"receipt":"R-9"}' || 'errored too_long receipt - false - - - false {"receipt":"R-9"}'
        CodeErrorReason.NOT_DECLARED        | ["lines"]        | new CodeError.UndeclaredMember("extra", false)  | null             | '{"lines":[{}]}'    || 'errored not_declared lines extra false - - - false {"lines":[{}]}'
        CodeErrorReason.NOT_DECLARED        | []               | new CodeError.UndeclaredMember("", false)       | null             | '{"":1}'            || 'errored not_declared -  false - - - false {"":1}'
        CodeErrorReason.NOT_DECLARED        | ["lines"]        | new CodeError.UndeclaredMember("n" * 65, false) | null             | null                || "errored not_declared lines ${"n" * 64} true - - - false -"
        CodeErrorReason.TAKES_OTHERWISE     | ["parts", "sku"] | null                                  | null             | null                || 'errored takes_otherwise parts.sku - false - - - false -'
        CodeErrorReason.GIVES_OTHERWISE     | ["receipt"]      | null                                  | READ_BY_CHECK    | null                || "errored gives_otherwise receipt - false ${CodeWorkflow.CHECK} - - false -"
        CodeErrorReason.GIVES_OTHERWISE     | ["receipt"]      | null                                  | READ_INTO_RESULT | null                || 'errored gives_otherwise receipt - false - result.receipt - false -'
        CodeErrorReason.GAVE_NOTHING        | []               | null                                  | null             | null                || 'errored gave_nothing - - false - - - false -'
        CodeErrorReason.FAILED_ON_THIS_SIDE | []               | null                                  | null             | null                || 'errored failed_on_this_side - - false - - - false -'
    }

    def "a fault written through the engine is read back with its path, reader and member cut intact"() {
        given:
        def aTry = codeTry()
        def fault = new CodeError.Fault(reason, path, member, readBy)

        when:
        def ended = codeEnded(aTry, new CodeOutcome.Errored(fault, null))
        def read = snapshots.asRead(groupId(CodeWorkflow.GROUP), runId(CodeWorkflow.RUN))
                .steps().find { it.planned().id().value().toString() == CodeWorkflow.SEND }.tries()

        then:
        ended
        read.size() == 1
        read[0].fault() == fault
        read[0].returned() == null
        read[0].lostDetail() == null
        store.count("select count(*) from production_values") == 0

        where:
        reason                        | path                                                       | member                                      | readBy
        CodeErrorReason.GIVES_OTHERWISE | [new FieldName("lines"), new FieldName("sku")]          | null                                        | READ_BY_CHECK
        CodeErrorReason.GIVES_OTHERWISE | [new FieldName("lines"), new FieldName("sku")]          | null                                        | READ_INTO_RESULT
        CodeErrorReason.NOT_DECLARED    | [new FieldName("lines"), new FieldName("sku")]          | new CodeError.UndeclaredMember("n" * 65, false) | null
        CodeErrorReason.NOT_DECLARED    | [new FieldName("lines"), new FieldName("sku")]          | new CodeError.UndeclaredMember("n" * 64, false) | null
    }

    def "a code try whose code threw is written with what it said and whether that was cut, and no reason of this system's"() {
        given:
        def aTry = codeTry()

        when:
        def ended = codeEnded(aTry, CodeOutcome.thrown("x" * saidLength))

        then:
        ended
        codeEnd() == ["errored - - - false - - ${"x" * keptLength} ${cut} -" as String]

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
        codeEnd() == ["errored said_nothing - - false - - - false -"]

        where:
        said << [null, " \t\n "]
    }

    private List<String> codeEnd() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select lost_reason || ' ' || coalesce(code_error_reason::text, '-') || ' ' || coalesce(code_error_path, '-')
                       || ' ' || coalesce(code_error_member, '-') || ' ' || code_error_member_truncated
                       || ' ' || coalesce(code_error_read_by_step::text, '-')
                       || ' ' || coalesce(code_error_read_by_output, '-') || ' ' || coalesce(lost_detail, '-') || ' '
                       || lost_detail_truncated || ' ' || coalesce(returned_by_code, '-')
                  from productions where producer = 'code'
                """)
    }

    private String callOut() {
        def held = runStep(RUN)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def production = store.session.sql("""
                insert into productions (run_step_id, run_id, root_run_id, run_step_kind, step_producer,
                                         reviewed_by_model, tries, pinned_version_id, try_number, producer,
                                         created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'question', 'model', false, 2, ?::uuid, 1, 'model', ?::uuid)
                returning cast(production_id as text)
                """).params(held, RUN, RUN, SUMMARISE_VERSION, SYSTEM).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def attempt = store.session.sql("""
                insert into run_step_send_attempts (run_step_id, run_id, run_step_kind, workflow_step_id, purpose,
                                                    model, mode, production_id, payload, created_by,
                                                    created_by_kind)
                values (?::uuid, ?::uuid, 'question', ?::uuid, 'produce', 'general', 'ordinary', ?::uuid, '{}',
                        ?::uuid, 'system')
                returning cast(run_step_send_attempt_id as text)
                """).params(held, RUN, SUMMARISE, production, SYSTEM).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
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
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select outcome || ' ' || sent_count || ' ' || coalesce(came_back_count::text, '-') || ' '
                       || counted_by_model || ' ' || coalesce(answer, '-') || ' ' || coalesce(error_detail, '-')
                       || ' ' || (ended_at >= created_at)
                  from model_calls
                """)
    }

    private List<String> modelTries() {
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("""
                select production_id || ' ' || coalesce(ended_at::text, 'open')
                  from productions where producer = 'model'
                """)
    }

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
        // DB-SPECIFIC: PostgreSQL now().
        store.session.sql("update model_calls set outcome = 'errored', error_detail = 'Gone.', ended_at = now()")
                .update()

        when:
        def ended = nothingCameBack(call)

        then:
        !ended
        calls() == ["errored 40 - false - Gone. true"]
    }

    private String beneathRun() {
        def beneath = "00000008-0000-4000-8000-000000000e42"
        RunRows.workflow(store, "00000006-0000-4000-8000-000000000e42", "00000007-0000-4000-8000-000000000e42", GROUP,
                "Demo Sample", 100L, false, true)
        RunRows.beneath(store, beneath, RUN, GROUP, 3, "00000006-0000-4000-8000-000000000e42",
                "00000007-0000-4000-8000-000000000e42", "00000009-0000-4000-8000-000000000e42",
                "0000000a-0000-4000-8000-000000000e42", 3)
        beneath
    }

    def "a stop for ceilings is written once, naming the first and keeping each of the rest: #further.size() besides"() {
        given:
        def reached = [RUN] + further.collect { it == "beneath" ? beneathRun() : it }

        when:
        store.transactions().executeWithoutResult {
            writes.stoppedAtCeilings(tree.lock(groupId(GROUP), runId(RUN)).orElseThrow(), reached.collect { runId(it) })
        }

        then:
        RunRows.stops(store) == ["system ${RUN} ${further.size()}" as String]
        // DB-SPECIFIC: PostgreSQL ::text casts.
        store.texts("select ceiling_run_id::text from run_stop_additional_ceilings") == reached.drop(1)

        where:
        further << [[], ["beneath"]]
    }

    def "a stop for no ceiling, or for one twice, is refused and nothing is written: #named"() {
        given:
        def before = ["run_stops", "run_stop_additional_ceilings"].collect { store.digestOf(it) }

        when:
        store.transactions().executeWithoutResult {
            writes.stoppedAtCeilings(tree.lock(groupId(GROUP), runId(RUN)).orElseThrow(), named.collect { runId(it) })
        }

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith("A stop is made for one ceiling at least, each once: ")
        ["run_stops", "run_stop_additional_ceilings"].collect { store.digestOf(it) } == before

        where:
        named << [[], [RUN, RUN]]
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
        // DB-SPECIFIC: PostgreSQL ::uuid casts, now().
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

    private String codeTry() {
        CodeWorkflow.seed(store)
        CodeWorkflow.run(store)
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
        def runStep = store.session.sql("""
                insert into run_steps (run_id, entry_version_id, workflow_step_id, step_kind, producer,
                                       reviewed_by_model, tries, created_by, created_by_kind)
                select ?::uuid, step.entry_version_id, step.workflow_step_id, step.kind, step.producer,
                       step.reviewed_by_model, step.tries, ?::uuid, 'system'
                  from workflow_steps step
                 where step.workflow_step_id = ?::uuid
                returning cast(run_step_id as text)
                """).params(CodeWorkflow.RUN, SYSTEM, CodeWorkflow.SEND).query(String).single()
        // DB-SPECIFIC: PostgreSQL ::uuid casts, RETURNING.
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
