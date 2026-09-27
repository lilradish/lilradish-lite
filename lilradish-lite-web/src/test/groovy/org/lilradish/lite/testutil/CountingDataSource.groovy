package org.lilradish.lite.testutil

import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import javax.sql.DataSource
import org.springframework.jdbc.datasource.DelegatingDataSource

/**
 * Counts every statement a connection it hands out is asked to prepare or create, whichever of
 * the driver's overloads is used, so a second statement per row is counted however it is issued.
 */
final class CountingDataSource extends DelegatingDataSource {

    private final List<String> statements

    CountingDataSource(DataSource target, List<String> statements) {
        super(target)
        this.statements = statements
    }

    @Override
    Connection getConnection() {
        new CountingConnection(super.getConnection(), statements)
    }

    private static final class CountingConnection implements Connection {

        @Delegate
        private final Connection connection

        private final List<String> statements

        CountingConnection(Connection connection, List<String> statements) {
            this.connection = connection
            this.statements = statements
        }

        PreparedStatement prepareStatement(String sql) {
            statements << sql
            connection.prepareStatement(sql)
        }

        PreparedStatement prepareStatement(String sql, int keys) {
            statements << sql
            connection.prepareStatement(sql, keys)
        }

        PreparedStatement prepareStatement(String sql, int[] columns) {
            statements << sql
            connection.prepareStatement(sql, columns)
        }

        PreparedStatement prepareStatement(String sql, String[] columns) {
            statements << sql
            connection.prepareStatement(sql, columns)
        }

        PreparedStatement prepareStatement(String sql, int type, int concurrency) {
            statements << sql
            connection.prepareStatement(sql, type, concurrency)
        }

        PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability) {
            statements << sql
            connection.prepareStatement(sql, type, concurrency, holdability)
        }

        Statement createStatement() {
            statements << "a statement created without its text"
            connection.createStatement()
        }

        Statement createStatement(int type, int concurrency) {
            statements << "a statement created without its text"
            connection.createStatement(type, concurrency)
        }

        Statement createStatement(int type, int concurrency, int holdability) {
            statements << "a statement created without its text"
            connection.createStatement(type, concurrency, holdability)
        }

        CallableStatement prepareCall(String sql) {
            statements << sql
            connection.prepareCall(sql)
        }

        CallableStatement prepareCall(String sql, int type, int concurrency) {
            statements << sql
            connection.prepareCall(sql, type, concurrency)
        }

        CallableStatement prepareCall(String sql, int type, int concurrency, int holdability) {
            statements << sql
            connection.prepareCall(sql, type, concurrency, holdability)
        }
    }
}
