package org.lilradish.lite.app.library;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads of several statements that must see one moment. A template started inside another transaction joins
 * it at that one's isolation, so joining a weaker one is refused rather than reading several moments quietly.
 */
final class Snapshots {

    private Snapshots() {}

    static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate snapshot = new TransactionTemplate(transactionManager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        return snapshot;
    }

    /* Spring records no isolation for a transaction begun at the default, which is read committed here. */
    static boolean inForceReadsOneMoment() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        return TransactionSynchronizationManager.isActualTransactionActive()
                && isolation != null
                && (isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                        || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /** Refused inside a transaction reading more than one moment; outside any, the caller starts its own. */
    static void requireNoWeakerOneInForce(String asking) {
        if (TransactionSynchronizationManager.isActualTransactionActive() && !inForceReadsOneMoment()) {
            throw new IllegalStateException(asking + " was asked inside a transaction reading more than one moment");
        }
    }
}
