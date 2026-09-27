package org.lilradish.lite.domain.inference

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What cleaning leaves, judged by the schema's own checks on the columns it is stored in, read back
 * off a real server rather than copied here, so a check that changes is judged as it now stands.
 */
class ForeignProseConstraintIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, "postgres"))
    }

    def "every code point having been through cleaning, what is left passes #constraint"() {
        given:
        def condition = session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                """).param(constraint).query(String).single().replaceFirst(/^CHECK /, "")
        def inputs = everyCodePointInChunks() + ["a" * 2049, Character.toString(0x1F600) * 2049]

        when:
        def refused = inputs.collect { ForeignProse."$factory"(it)?.text() }
                .findAll { it != null && !passes(condition, column, it) }

        then:
        refused.isEmpty()

        and: "the check refusing what cleaning takes out, so its passing says something"
        !passes(condition, column, uncleaned)

        where:
        constraint                             | column         | factory       || uncleaned
        "model_call_turnaways_said_visible"    | "said"         | "said"        || "a\rb"
        "model_call_turnaways_said_bounded"    | "said"         | "said"        || "a" * 2049
        "model_calls_error_detail_visible"     | "error_detail" | "errorDetail" || "a\rb"
        "model_calls_error_detail_bounded"     | "error_detail" | "errorDetail" || "a" * 2049
    }

    private boolean passes(String condition, String column, String text) {
        session.sql("select ${condition} from (select cast(? as text) as ${column}) as candidate".toString())
                .param(text).query(Boolean).single()
    }

    /** Every code point, in pieces short enough to be kept whole, NUL included, lone surrogates among them. */
    private static List<String> everyCodePointInChunks() {
        (0..Character.MAX_CODE_POINT).collate(1000).collect { chunk ->
            def builder = new StringBuilder()
            chunk.each { builder.appendCodePoint(it as int) }
            builder.toString()
        }
    }
}
