package org.lilradish.lite.domain.workflow

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.app.library.FlowBody
import org.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SearchQuestionConstraintIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    JdbcClient session

    @Shared
    int most

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, 'postgres'))
        def field = FlowBody.declaredFields.find { it.name == 'MOST_QUESTION_PARTS' }
        assert field.type == Integer.TYPE && java.lang.reflect.Modifier.isStatic(field.modifiers)
        most = FlowBody.@MOST_QUESTION_PARTS
    }

    def "the actual search part position bounds match the application maximum with nonempty positive and negative cases"() {
        given:
        def checks = ['search_question_parts_position_not_negative', 'search_question_parts_position_bounded']
                .collect { name ->
                    // DB-SPECIFIC: pg_constraint, pg_get_constraintdef() and ::regnamespace are PostgreSQL's.
                    session.sql("""
                            select pg_get_constraintdef(oid) from pg_constraint
                             where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                            """).param(name).query(String).single().replaceFirst(/^CHECK /, '')
                }
        def positions = [-1, 0, most-1, most]
        def accepts = positions.collect { position ->
            session.sql("select (${checks.join(' and ')}) from (select cast(? as integer) as position) candidate".toString())
                    .param(position).query(Boolean).single()
        }

        expect:
        most > 1
        checks.size() == 2
        accepts == [false, true, true, false]
    }
}
