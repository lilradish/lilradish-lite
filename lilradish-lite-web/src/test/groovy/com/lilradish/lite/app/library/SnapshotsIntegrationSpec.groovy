package com.lilradish.lite.app.library

import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** What a snapshot's transaction is on a real server, whose own default reads a new moment every statement. */
class SnapshotsIntegrationSpec extends Specification {

    @Shared
    @AutoCleanup
    TestPostgres server = TestPostgres.worker()

    @Shared
    LibraryStore store

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
        store = LibraryStore.forCase(server, "snapshots")
    }

    def "a snapshot reads one moment of the store, and writes nothing"() {
        // DB-SPECIFIC: PostgreSQL SHOW reads the transaction settings checked in this feature.
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
