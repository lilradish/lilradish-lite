package com.lilradish.lite.app.library

import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import spock.lang.Specification

class SnapshotsSpec extends Specification {

    def "only an active repeatable read or serializable transaction reads one moment"() {
        given:
        TransactionSynchronizationManager.setActualTransactionActive(active)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation)

        expect:
        Snapshots.inForceReadsOneMoment() == oneMoment

        cleanup:
        TransactionSynchronizationManager.setActualTransactionActive(false)
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null)

        where:
        active | isolation                                          || oneMoment
        false  | null                                               || false
        false  | TransactionDefinition.ISOLATION_REPEATABLE_READ      || false
        true   | null                                               || false
        true   | TransactionDefinition.ISOLATION_READ_COMMITTED       || false
        true   | TransactionDefinition.ISOLATION_REPEATABLE_READ      || true
        true   | TransactionDefinition.ISOLATION_SERIALIZABLE         || true
    }
}
