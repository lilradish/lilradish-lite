package com.lilradish.lite.testutil

import com.lilradish.lite.testsupport.TestPostgres
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class ExplainedQueriesSpec extends Specification {

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    def "a SELECT retains actual typed bindings and is explained on its live connection"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        // DB-SPECIFIC: PostgreSQL ::uuid/::timestamptz/::text/::text[] casts retain typed parameters.
        def statement = connection.prepareStatement('select ?::uuid as identity, ?::timestamptz as moment, ?::text as absent, ?::text[] as items',
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
        def identity = UUID.fromString('00000008-0000-4000-8000-000000000001')
        def moment = Timestamp.valueOf('2026-01-02 03:04:05.123456')
        def items = connection.createArrayOf('text', ['first', 'second'] as Object[])
        statement.setObject(1, identity, Types.OTHER)
        statement.setTimestamp(2, moment)
        statement.setNull(3, Types.VARCHAR)
        statement.setArray(4, items)

        when:
        def result = statement.executeQuery()

        then:
        result.next()
        result.getObject('identity', UUID) == identity
        result.getTimestamp('moment') == moment
        result.getString('absent') == null
        result.getArray('items').array.toList() == ['first', 'second']
        !result.next()
        observed.queries.size() == 1
        observed.queries.first().sql == 'select ?::uuid as identity, ?::timestamptz as moment, ?::text as absent, ?::text[] as items'
        observed.queries.first().bindings*.setter == ['setObject', 'setTimestamp', 'setNull', 'setArray']
        observed.queries.first().bindings*.index == [1, 2, 3, 4]
        observed.queries.first().bindings.first().values == [identity, Types.OTHER]
        observed.queries.first().plan.size() == 1
        observed.queries.first().plan.first().Plan['Node Type'] == 'Result'
        !connection.closed

        cleanup:
        result?.close()
        statement.close()
        items.free()
        connection.close()
    }

    def "clearParameters refuses a missing binding before any query is explained, and rebinding keeps only its last value"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        // DB-SPECIFIC: PostgreSQL ::integer casts retain the integer parameter type.
        def statement = connection.prepareStatement('select ?::integer as value')
        statement.setInt(1, 3)
        statement.clearParameters()

        when:
        statement.executeQuery()

        then:
        def refused = thrown(SQLException)
        refused.message == 'Every JDBC parameter must have a recorded setter'
        observed.queries.empty

        when:
        statement.setInt(1, 5)
        statement.setInt(1, 7)
        def result = statement.executeQuery()

        then:
        result.next()
        result.getInt(1) == 7
        !result.next()
        observed.queries.size() == 1
        observed.queries.first().bindings*.values == [[7]]

        cleanup:
        result?.close()
        statement.close()
        connection.close()
    }

    def "streams and non-query execution are refused without a plan or a consumed resource"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        // DB-SPECIFIC: PostgreSQL ::text casts retain the text parameter type.
        def statement = connection.prepareStatement('select ?::text')
        def stream = new ByteArrayInputStream([65, 66] as byte[])

        when:
        if (operation == 'setBinaryStream') statement.setBinaryStream(1, stream)
        else if (operation == 'setObject') statement.setObject(1, stream)
        else if (operation == 'executeUpdate') statement.executeUpdate()
        else if (operation == 'addBatch') statement.addBatch()
        else statement.executeBatch()

        then:
        thrown(SQLException)
        observed.queries.empty
        stream.available() == 2

        cleanup:
        stream.close()
        statement.close()
        connection.close()

        where:
        operation << ['setBinaryStream', 'setObject', 'executeUpdate', 'addBatch', 'executeBatch']
    }

    def "closed statements and connections cannot produce a plan"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        def statement = connection.prepareStatement('select 1')
        if (closed == 'statement') statement.close()
        else connection.close()

        when:
        statement.executeQuery()

        then:
        def refused = thrown(SQLException)
        refused.message == 'A closed JDBC resource cannot be explained'
        observed.queries.empty

        cleanup:
        statement.close()
        connection.close()

        where:
        closed << ['statement', 'connection']
    }

    def "a released JDBC array is refused before a plan is saved"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        // DB-SPECIFIC: PostgreSQL ::text[] casts retain the array parameter type.
        def statement = connection.prepareStatement('select ?::text[]')
        def items = connection.createArrayOf('text', ['first'] as Object[])
        statement.setArray(1, items)
        items.free()

        when:
        statement.executeQuery()

        then:
        def refused = thrown(SQLException)
        refused.message == 'A released JDBC array cannot be replayed'
        observed.queries.empty
        !connection.closed

        cleanup:
        statement.close()
        connection.close()
    }

    def "a reused prepared statement explains each current binding on its live connection"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        // DB-SPECIFIC: PostgreSQL ::integer casts retain the integer parameter type.
        def statement = connection.prepareStatement('select ?::integer')

        when:
        statement.setInt(1, 3)
        def first = statement.executeQuery()
        assert first.next()
        def firstValue = first.getInt(1)
        first.close()
        statement.setInt(1, 8)
        def second = statement.executeQuery()
        assert second.next()
        def secondValue = second.getInt(1)
        second.close()

        then:
        firstValue == 3
        secondValue == 8
        observed.queries*.bindings*.values == [[[3]], [[8]]]
        observed.queries.size() == 2
        !connection.closed
        !statement.closed

        cleanup:
        statement.close()
        connection.close()
    }

    def "the driver's SQL exception is retained and a failed EXPLAIN does not close its connection"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection
        // DB-SPECIFIC: PostgreSQL ::integer casts retain the divisor parameter type.
        def statement = connection.prepareStatement('select 1 / ?::integer')
        statement.setInt(1, 0)

        when:
        statement.executeQuery()

        then:
        def failure = thrown(SQLException)
        failure.SQLState == '22012'
        observed.queries.empty
        !connection.closed

        when:
        statement.setInt(1, 1)
        def result = statement.executeQuery()

        then:
        result.next()
        result.getInt(1) == 1
        !result.next()
        observed.queries.size() == 1

        cleanup:
        result?.close()
        statement.close()
        connection.close()
    }

    def "non-SELECT and multi-statement preparation never executes a write"() {
        given:
        def observed = new ExplainedQueries(server.postgresDatabase)
        def connection = observed.connection

        when:
        connection.prepareStatement(sql)

        then:
        def refused = thrown(SQLException)
        refused.message == 'Only one complete SELECT may be explained'
        observed.queries.empty

        cleanup:
        connection.close()

        where:
        // DB-SPECIFIC: the rejected PostgreSQL fixtures include SELECT INTO table creation
        // and FOR UPDATE/FOR SHARE row-locking forms; these statements are not executed.
        sql << ['create table unexpectedly_written (value integer)', 'delete from unexpectedly_written',
                'select 1; select 2', 'select 1 into unexpectedly_written',
                'select * from unexpectedly_written for update', 'select * from unexpectedly_written for share']
    }
}
