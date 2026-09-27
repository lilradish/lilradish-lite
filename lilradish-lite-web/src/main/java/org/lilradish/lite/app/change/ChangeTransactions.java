package org.lilradish.lite.app.change;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one transaction every change is made in, whole or not at all.
 *
 * <p>Read Committed is named rather than left to the server. Only there does a statement after a lock
 * read what the transaction it waited on committed; under a snapshot taken at the transaction's first
 * statement, a check made after the wait would answer from before it.
 */
@Configuration(proxyBeanMethods = false)
public final class ChangeTransactions {

    @Bean
    public TransactionOperations readCommitted(PlatformTransactionManager transactionManager) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }
}
