package org.lilradish.lite

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.sql.DriverManager
import java.sql.SQLException
import javax.sql.DataSource
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * A row the store refuses is described by the server in full, a piece of every column it holds, and that
 * description would reach the logs with any failure logged whole. Through the application's own datasource
 * configuration the driver keeps it out of the message, while the constraint refusing the row is still named.
 */
class StoreErrorDetailIntegrationSpec extends Specification {

    static final String REFUSED = "insert into kept (said) values ('the invoice total is 4471')"

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    def setupSpec() {
        server.postgresDatabase.connection.withCloseable {
            it.createStatement().execute("create table kept (said text constraint kept_said_short check (length(said) < 5))")
        }
    }

    private String refusedThrough(DataSource store) {
        store.connection.withCloseable { connection ->
            try {
                connection.createStatement().execute(REFUSED)
            } catch (SQLException refused) {
                return refused.message
            }
            throw new AssertionError("the row was not refused")
        }
    }

    def "through the application's datasource configuration a refused row is named by its constraint, and none of it is told"() {
        given:
        def runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration))
                .withPropertyValues("spring.datasource.url=" + server.getJdbcUrl("postgres", "postgres"),
                        "spring.datasource.username=postgres")
        String message = null

        when:
        runner.run { context ->
            assert context.startupFailure == null
            message = refusedThrough(context.getBean(DataSource))
        }

        then:
        message.contains("kept_said_short")
        !message.contains("Failing row")
        !message.contains("4471")
    }

    /** What the configuration switches off: without it the same refusal carries the row. */
    def "a connection made without it tells the refused row, which is what the configuration keeps out"() {
        when:
        def message = DriverManager.getConnection(server.getJdbcUrl("postgres", "postgres")).withCloseable { connection ->
            try {
                connection.createStatement().execute(REFUSED)
                null
            } catch (SQLException refused) {
                refused.message
            }
        }

        then:
        message.contains("kept_said_short")
        message.contains("Failing row")
        message.contains("4471")
    }
}
