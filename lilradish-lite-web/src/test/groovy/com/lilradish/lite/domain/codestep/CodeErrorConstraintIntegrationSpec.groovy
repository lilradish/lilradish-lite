package com.lilradish.lite.domain.codestep

import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.wire.StoreLabels
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What cleaning a member's name leaves, judged by the schema's own checks on the columns it is stored in, read back
 * off a real server rather than copied here, so a check that changes is judged as it now stands.
 */
class CodeErrorConstraintIntegrationSpec extends Specification {

    static final int LONGEST_MEMBER = 64

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    JdbcClient session

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, "postgres"))
    }

    def "every code point having been through cleaning, the member named passes #constraint"() {
        given:
        // DB-SPECIFIC: PostgreSQL :: casts to regnamespace; pg_constraint; pg_get_constraintdef().
        def condition = session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                """).param(constraint).query(String).single().replaceFirst(/^CHECK /, "")
        def inputs = everyCodePointInChunks() + ["a" * 66, Character.toString(0x1F600) * 66]
        def members = inputs.collect { new CodeError.UndeclaredMember(it, false) }

        expect:
        refusedAmong(condition, members*.name(), members*.cut()) == 0

        and: "among them names cut, which alone a check on the cut judges"
        members.count { it.cut() } == 2

        and: "the check refusing what cleaning takes out, so its passing says something"
        refusedAmong(condition, [uncleaned], [uncut]) == 1

        where:
        constraint                                         || uncleaned                          | uncut
        "productions_code_error_member_one_line"           || "a" + Character.toString(10) + "b" | false
        "productions_code_error_member_bounded"            || "a" * 65                           | false
        "productions_code_error_member_truncated_at_bound" || "a" * 63                           | true
    }

    def "each reason's field, member and reader classification matches the stored checks in both directions"() {
        given:
        def checks = ["productions_code_error_path_as_its_reason_names",
                      "productions_code_error_member_exactly_for_not_declared",
                      "productions_code_error_read_by_exactly_for_gives_otherwise"].collect { constraint ->
            // DB-SPECIFIC: PostgreSQL :: casts to regnamespace; pg_constraint; pg_get_constraintdef().
            session.sql("""
                    select pg_get_constraintdef(oid) from pg_constraint
                    where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                    """).param(constraint).query(String).single().replaceFirst(/^CHECK /, "")
        }
        def candidates = [[[], [new FieldName("lines"), new FieldName("sku")]],
                          [null, new CodeError.UndeclaredMember("extra", false)],
                          [null, new CodeError.StepReads(UUID.fromString("00000009-0000-4000-8000-000000000c02")),
                           new CodeError.OutputReads([new FieldName("result"), new FieldName("receipt")])]]
                .combinations()
        int accepted = 0
        int refused = 0
        def acceptedFaults = []
        // DB-SPECIFIC: PostgreSQL :: casts to regnamespace; pg_constraint; pg_get_constraintdef().
        def returnedCheck = session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = 'productions_returned_by_code_only_for_code_errored'
                  and connamespace = 'app'::regnamespace and contype = 'c'
                """).query(String).single().replaceFirst(/^CHECK /, "")

        expect:
        candidates.every { candidate ->
            def (path, member, readBy) = candidate
            boolean javaAccepts
            try {
                acceptedFaults << new CodeError.Fault(reason, path, member, readBy)
                javaAccepts = true
                accepted++
            } catch (IllegalArgumentException ignored) {
                javaAccepts = false
                refused++
            }
            // DB-SPECIFIC: PostgreSQL enum type app.code_error_reason.
            def storedAccepts = session.sql(("select (" + checks.join(" and ") + """
                    ) is not false from (
                        select cast(:reason as app.code_error_reason) as code_error_reason,
                               cast(:path as text) as code_error_path, cast(:member as text) as code_error_member,
                               cast(:step as uuid) as code_error_read_by_step,
                               cast(:output as text) as code_error_read_by_output
                    ) candidate
                    """).toString()).param("reason", StoreLabels.label(reason))
                    .param("path", path.isEmpty() ? null : path*.value().join("."))
                    .param("member", member?.name())
                    .param("step", readBy instanceof CodeError.StepReads ? readBy.step().toString() : null)
                    .param("output", readBy instanceof CodeError.OutputReads ? readBy.field()*.value().join(".") : null)
                    .query(Boolean).single()
            assert storedAccepts == javaAccepts
            true
        }
        accepted > 0
        refused > 0
        acceptedFaults.every { fault ->
            [null, "{}"].every { returned ->
                boolean javaAccepts
                try {
                    new CodeOutcome.Errored(fault, returned)
                    javaAccepts = true
                } catch (IllegalArgumentException ignored) {
                    javaAccepts = false
                }
                // DB-SPECIFIC: PostgreSQL enum types app.code_error_reason, app.try_lost_reason and app.step_producer; :: casts.
                def storedAccepts = session.sql(("select (" + returnedCheck + """
                        ) is not false from (
                            select cast(:reason as app.code_error_reason) as code_error_reason,
                                   cast(:returned as text) as returned_by_code,
                                   'errored'::app.try_lost_reason as lost_reason, 'code'::app.step_producer as producer
                        ) candidate
                        """).toString()).param("reason", StoreLabels.label(reason)).param("returned", returned)
                        .query(Boolean).single()
                assert storedAccepts == javaAccepts
                true
            }
        }

        where:
        reason << CodeErrorReason.values()
    }

    /** One statement for all of them, each tested as the check tests a row's own. */
    private int refusedAmong(String condition, List<String> names, List<Boolean> cuts) {
        // DB-SPECIFIC: PostgreSQL array type [] syntax.
        session.sql("select count(*) from unnest(cast(:names as text[]), cast(:cuts as boolean[]))" +
                " as candidate(code_error_member, code_error_member_truncated) where not (${condition})".toString())
                .param("names", names as String[]).param("cuts", cuts as Boolean[]).query(Integer).single()
    }

    /** Every code point, in pieces no longer than a member's name is kept, NUL included, lone surrogates among them. */
    private static List<String> everyCodePointInChunks() {
        (0..Character.MAX_CODE_POINT).collate(LONGEST_MEMBER).collect { chunk ->
            def builder = new StringBuilder()
            chunk.each { builder.appendCodePoint(it as int) }
            builder.toString()
        }
    }
}
