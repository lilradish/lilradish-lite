package org.lilradish.lite.testutil

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import javax.sql.DataSource
import org.lilradish.lite.testutil.library.LibraryStore
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * The store a spec starting the whole application starts it on: a server of its context's own, running a database
 * copied from one the real baseline was applied to, since the lock one process takes is the database's own and a
 * context stays cached beside others. The server stops with its context, after all that writes through it.
 */
@TestConfiguration(proxyBeanMethods = false)
class ApplicationStore {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres applicationServer() {
        EmbeddedPostgres.builder().start()
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres applicationServer) {
        LibraryStore.template(applicationServer)
        LibraryStore.copied(applicationServer, "application").database
    }
}
