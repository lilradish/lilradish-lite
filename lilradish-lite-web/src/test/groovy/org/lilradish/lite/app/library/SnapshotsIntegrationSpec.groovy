package org.lilradish.lite.app.library

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** What a snapshot's transaction is on a real server, whose own default reads a new moment every statement. */
class SnapshotsIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    LibraryStore store

    def setupSpec() {
        LibraryStore.template(server)
        store = LibraryStore.copied(server, "snapshots")
    }

    def "a snapshot reads one moment of the store, and writes nothing"() {
        when:
        def seen = Snapshots.readOnly(store.transactionManager()).execute {
            [store.session.sql("show transaction_isolation").query(String).single(),
             store.session.sql("show transaction_read_only").query(String).single()]
        }

        then:
        seen == ["repeatable read", "on"]

        and: "unlike a transaction the server begins at its own default"
        store.session.sql("show transaction_isolation").query(String).single() == "read committed"
    }
}
