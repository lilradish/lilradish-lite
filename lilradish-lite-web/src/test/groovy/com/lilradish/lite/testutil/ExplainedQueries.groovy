package com.lilradish.lite.testutil

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DelegatingDataSource
import tools.jackson.databind.json.JsonMapper

/** Observes bound SELECTs before their connection or JDBC values can be released. */
class ExplainedQueries extends DelegatingDataSource {

    private static final JsonMapper JSON = JsonMapper.builder().build()

    private static final Set<String> VALUES = [
            'setBoolean', 'setByte', 'setShort', 'setInt', 'setLong', 'setFloat', 'setDouble',
            'setBigDecimal', 'setString', 'setNString', 'setBytes', 'setDate', 'setTime',
            'setTimestamp', 'setObject', 'setNull', 'setURL', 'setArray'
    ] as Set

    final List<Map<String, Object>> queries = []

    ExplainedQueries(DataSource database) {
        super(database)
    }

    /** Only invented test data is saved, outside the product tree, when an experiment asks for it. */
    void save(String name, Map population, List results) {
        String directory = System.getenv('SE2_QUERY_PLANS')
        if (directory == null) return
        File destination = new File(directory)
        destination.mkdirs()
        // DB-SPECIFIC: PostgreSQL catalogs, OID casts, pg_relation_size and current_setting expose relation sizes
        // and planner settings; version() reads the server version.
        def session = JdbcClient.create(targetDataSource)
        def relations = session.sql("""
                select relation.relname, relation.relkind::text as kind,
                       pg_catalog.pg_relation_size(relation.oid) as bytes
                  from pg_catalog.pg_class relation
                 where relation.relnamespace = 'app'::pg_catalog.regnamespace
                   and (relation.relname like '%!_trigrams' escape '!'
                        or relation.relname in ('people', 'subjects', 'pool_members', 'group_members', 'groups', 'entries', 'runs'))
                 order by relation.relname
                """).query().listOfRows()
        def configuration = session.sql("""
                select version() as server, current_setting('enable_seqscan') as enable_seqscan,
                       current_setting('random_page_cost') as random_page_cost,
                       current_setting('effective_cache_size') as effective_cache_size
                """).query().listOfRows().first()
        new File(destination, name + '.json').text = JSON.writeValueAsString([
                population: population, results: results, queries: queries, relations: relations, configuration: configuration
        ]) + '\n'
    }

    @Override
    Connection getConnection() {
        observed(super.getConnection())
    }

    @Override
    Connection getConnection(String user, String password) {
        observed(super.getConnection(user, password))
    }

    private Connection observed(Connection actual) {
        // JDBC interfaces require a type proxy to retain their overloaded entry points.
        Proxy.newProxyInstance(Connection.classLoader, [Connection] as Class[], { proxy, method, args ->
            if (method.name == 'prepareStatement') {
                String sql = args[0]
                String normalized = sql.stripLeading().toLowerCase(Locale.ROOT)
                if (!normalized.startsWith('select ') || sql.contains(';') ||
                        normalized =~ /\binto\b|\bfor\s+(?:update|share|no\s+key\s+update|key\s+share)\b/) {
                    throw new SQLException('Only one complete SELECT may be explained')
                }
                return observed(actual, ExplainedQueries.invokeTarget(actual, method, args) as PreparedStatement, sql)
            }
            if (method.name in ['createStatement', 'prepareCall']) {
                throw new SQLException('Only prepared SELECTs may be explained')
            }
            ExplainedQueries.invokeTarget(actual, method, args)
        } as java.lang.reflect.InvocationHandler) as Connection
    }

    private PreparedStatement observed(Connection connection, PreparedStatement actual, String sql) {
        Map<Integer, Map<String, Object>> bindings = new TreeMap<>()
        Proxy.newProxyInstance(PreparedStatement.classLoader, [PreparedStatement] as Class[], { proxy, method, args ->
            if (method.name == 'clearParameters') {
                ExplainedQueries.invokeTarget(actual, method, args)
                bindings.clear()
                return null
            }
            if (method.name in VALUES) {
                if (method.name == 'setObject' && args[1] instanceof java.io.InputStream ||
                        method.name == 'setObject' && args[1] instanceof java.io.Reader ||
                        method.name == 'setObject' && args[1] instanceof java.sql.Blob ||
                        method.name == 'setObject' && args[1] instanceof java.sql.Clob ||
                        method.name == 'setObject' && args[1] instanceof java.sql.SQLXML) {
                    throw new SQLException('This JDBC value cannot be replayed without consuming a resource')
                }
                Object[] retained = args.clone()
                for (int index = 1; index < retained.length; index++) {
                    if (retained[index] instanceof byte[]) retained[index] = retained[index].clone()
                    if (retained[index] instanceof Object[]) retained[index] = retained[index].clone()
                    if (retained[index] instanceof Date) retained[index] = retained[index].clone()
                    if (retained[index] instanceof Calendar) retained[index] = retained[index].clone()
                }
                ExplainedQueries.invokeTarget(actual, method, retained)
                bindings[args[0] as Integer] = [method: method, arguments: retained]
                return null
            }
            if (method.name.startsWith('set') && args && args[0] instanceof Integer &&
                    method.name in ['setAsciiStream', 'setBinaryStream', 'setCharacterStream',
                                    'setNCharacterStream', 'setBlob', 'setClob', 'setNClob', 'setSQLXML', 'setRef', 'setUnicodeStream']) {
                throw new SQLException('This JDBC setter cannot be replayed without consuming a resource')
            }
            if (method.name in ['addBatch', 'executeBatch', 'executeLargeBatch', 'executeUpdate', 'executeLargeUpdate', 'execute']) {
                throw new SQLException('Only executeQuery may be explained')
            }
            if (method.name == 'executeQuery') {
                if (args) throw new SQLException('Only the original prepared SELECT may be explained')
                if (actual.closed || connection.closed) throw new SQLException('A closed JDBC resource cannot be explained')
                int count = actual.parameterMetaData.parameterCount
                if ((count == 0 ? [] : (1..count).toList()) != bindings.keySet().toList()) {
                    throw new SQLException('Every JDBC parameter must have a recorded setter')
                }
                // DB-SPECIFIC: PostgreSQL EXPLAIN ANALYZE with BUFFERS and FORMAT JSON records the actual bound SELECT plan.
                def explained = connection.prepareStatement('EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) ' + sql)
                try {
                    bindings.values().each { binding ->
                        binding.arguments.each { value ->
                            if (value instanceof java.sql.Array) {
                                try {
                                    value.baseTypeName
                                } catch (Exception | AssertionError unavailable) {
                                    throw new SQLException('A released JDBC array cannot be replayed', unavailable)
                                }
                            }
                        }
                        ExplainedQueries.invokeTarget(explained, binding.method as Method, binding.arguments as Object[])
                    }
                    def result = explained.executeQuery()
                    try {
                        if (!result.next()) throw new SQLException('EXPLAIN returned no plan')
                        queries.add([sql: sql, bindings: bindings.collect { index, binding ->
                            [index: index, setter: binding.method.name,
                             parameterTypes: binding.method.parameterTypes*.name,
                             values: binding.arguments.drop(1).collect { value ->
                                 value instanceof java.sql.Array ? [type: value.baseTypeName, values: value.array] : value
                             }]
                        }, plan: JSON.readValue(result.getString(1), List)])
                        if (result.next()) throw new SQLException('EXPLAIN returned more than one plan')
                    } finally {
                        result.close()
                    }
                } finally {
                    explained.close()
                }
            }
            ExplainedQueries.invokeTarget(actual, method, args)
        } as java.lang.reflect.InvocationHandler) as PreparedStatement
    }

    private static Object invokeTarget(Object target, Method method, Object[] arguments) {
        try {
            method.invoke(target, arguments)
        } catch (InvocationTargetException failed) {
            throw failed.cause
        }
    }
}
