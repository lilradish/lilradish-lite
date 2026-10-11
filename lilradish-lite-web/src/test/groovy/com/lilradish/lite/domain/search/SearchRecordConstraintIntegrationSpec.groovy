package com.lilradish.lite.domain.search

import com.lilradish.lite.domain.inference.KeptAnswer
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.Baseline
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class SearchRecordConstraintIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    JdbcClient session

    def setupSpec() {
        session = JdbcClient.create(Baseline.appliedTo(server, 'postgres'))
    }

    def "the record position kept by the application matches the actual database bound in both directions"() {
        given:
        def condition = check('model_call_searches_position_range')

        expect:
        session.sql("select ${condition} from (select cast(? as integer) as position) candidate".toString())
                .param(position).query(Boolean).single() == kept

        where:
        position                     || kept
        0                            || false
        1                            || true
        SearchRecord.MOST_SEARCHES   || true
        SearchRecord.MOST_SEARCHES+1 || false
    }

    def "each kept record text fits its actual store bound while one more character does not"() {
        given:
        def condition = check(constraint)
        def text = 'a' * length
        def fits = session.sql("select ${condition} from (select cast(? as text) as searched_for, cast(? as text) as found) candidate".toString())
                .params(text, text).query(Boolean).single()

        expect:
        fits == kept

        where:
        constraint                                | length                 || kept
        'model_call_searches_searched_for_bounded' | 0                      || true
        'model_call_searches_searched_for_bounded' | KeptAnswer.MOST_KEPT    || true
        'model_call_searches_searched_for_bounded' | KeptAnswer.MOST_KEPT+1  || false
        'model_call_searches_found_bounded'        | 0                      || true
        'model_call_searches_found_bounded'        | KeptAnswer.MOST_KEPT    || true
        'model_call_searches_found_bounded'        | KeptAnswer.MOST_KEPT+1  || false
    }

    private String check(String name) {
        // DB-SPECIFIC: PostgreSQL :: casts to regnamespace; pg_constraint; pg_get_constraintdef().
        session.sql("""
                select pg_get_constraintdef(oid) from pg_constraint
                 where conname = ? and connamespace = 'app'::regnamespace and contype = 'c'
                """).param(name).query(String).single().replaceFirst(/^CHECK /, '')
    }
}
