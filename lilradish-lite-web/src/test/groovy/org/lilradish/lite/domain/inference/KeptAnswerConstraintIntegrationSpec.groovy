package org.lilradish.lite.domain.inference

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.testutil.Baseline
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What is kept of an answer, sent to a real server and read back, and judged by the schema's own check on the
 * column it is kept in, read off that server rather than copied here.
 */
class KeptAnswerConstraintIntegrationSpec extends Specification {

    static final int MOST = KeptAnswer.MOST_KEPT

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    @Shared
    String bounded

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, "postgres"))
        bounded = session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conname = 'model_calls_answer_bounded' and connamespace = 'app'::regnamespace and contype = 'c'
                """).query(String).single().replaceFirst(/^CHECK /, "")
    }

    def "every code point having been kept, the store holds what is kept exactly as it was kept"() {
        when:
        def changed = everyCodePointInChunks().collect { KeptAnswer.of(it).text() }
                .findAll { readBack(it) != it }

        then:
        changed.isEmpty()
    }

    /** Millions of characters are compared inside the feature, never rendered by a condition that fails. */
    def "what is kept of an answer past the most is within the answer's bound, and what came back was not"() {
        given:
        def cameBack = piece * (MOST + 1)

        when:
        boolean keptPasses = passes(KeptAnswer.of(cameBack).text())
        boolean cameBackPasses = passes(cameBack)

        then:
        keptPasses
        !cameBackPasses

        where:
        piece << ["a", Character.toString(0x1F600)]
    }

    /** Refused on the way in, or rewritten without a word: either way, not what came back. */
    def "what came back unkept is not held by the store as it came"() {
        expect:
        readBack(cameBack) != cameBack
        readBack(KeptAnswer.of(cameBack).text()) == KeptAnswer.of(cameBack).text()

        where:
        cameBack << ["a" + Character.toString(0x0) + "b", "a" + Character.toString(0xD83D) + "b",
                     "a" + Character.toString(0xDE00) + "b"]
    }

    private String readBack(String text) {
        try {
            session.sql("select cast(? as text)").param(text).query(String).single()
        } catch (DataAccessException ignored) {
            null
        }
    }

    private boolean passes(String answer) {
        session.sql("select ${bounded} from (select cast(? as text) as answer) as candidate".toString())
                .param(answer).query(Boolean).single()
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
