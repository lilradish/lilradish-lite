package com.lilradish.lite.testutil

import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.library.LibraryStore
import javax.sql.DataSource
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * Each application context owns a scope on its worker's database. Database contexts are closed after
 * their test class, before another scope restores the baseline and takes the process lock.
 */
@TestConfiguration(proxyBeanMethods = false)
class ApplicationStore {

    @Bean(destroyMethod = "close")
    TestPostgres applicationServer(Environment environment) {
        def scope = environment.getProperty("lite.test.database.schema-sensitive", Boolean, false) ?
                TestPostgres.isolated() : TestPostgres.worker()
        scope
    }

    @Bean
    DataSource dataSource(TestPostgres applicationServer) {
        LibraryStore.prepareBaseline(applicationServer)
        LibraryStore.forCase(applicationServer, "application")
        applicationServer.pooledDataSource("application", "?currentSchema=app")
    }
}
