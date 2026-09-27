package org.lilradish.lite.domain.codestep

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What cleaning a member's name leaves, judged by the schema's own checks on the column it is stored in, read back
 * off a real server rather than copied here, so a check that changes is judged as it now stands.
 */
class CodeErrorConstraintIntegrationSpec extends Specification {

    static final int LONGEST_MEMBER = 64

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, "postgres"))
    }

    def "every code point having been through cleaning, the member named passes #constraint"() {
        given:
        def condition = session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                """).param(constraint).query(String).single().replaceFirst(/^CHECK /, "")
        def inputs = everyCodePointInChunks() + ["a" * 66, Character.toString(0x1F600) * 66]
        def members = inputs.collect { new CodeError.Fault(CodeErrorReason.NOT_DECLARED, [], it, null).member() }

        expect:
        refusedAmong(condition, members) == 0

        and: "the check refusing what cleaning takes out, so its passing says something"
        refusedAmong(condition, [uncleaned]) == 1

        where:
        constraint                               || uncleaned
        "productions_code_error_member_one_line" || "a" + Character.toString(10) + "b"
        "productions_code_error_member_bounded"  || "a" * 66
    }

    /** One statement for all of them, each tested as the check tests a row's own. */
    private int refusedAmong(String condition, List<String> members) {
        session.sql("select count(*) from unnest(cast(:members as text[])) as candidate(code_error_member)" +
                " where not (${condition})".toString())
                .param("members", members as String[]).query(Integer).single()
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
