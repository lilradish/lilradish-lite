package org.lilradish.lite.testutil.run

import org.lilradish.lite.domain.inference.CallOutcome
import org.lilradish.lite.domain.inference.CallProgress
import org.lilradish.lite.domain.inference.CallRequest
import org.lilradish.lite.domain.inference.ModelCalls
import org.lilradish.lite.testutil.inference.ScriptedModelCalls
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A model scripted as {@link ScriptedModelCalls} is, noting of each call made whether it was made on one of the
 * engine's threads and whether inside a transaction, before the script answers it or finds nothing to answer.
 */
final class RecordedModelCalls implements ModelCalls {

    final ScriptedModelCalls scripted = new ScriptedModelCalls()

    /** For each call made, whether on an engine thread, and whether inside a transaction. */
    final List<List<Boolean>> madeOn = Collections.synchronizedList([])

    /* By its class's name: the engine's thread type is its package's own, which no class here may reach. */
    static final String ENGINE_THREAD = "org.lilradish.lite.app.run.EngineThread"

    @Override
    CallOutcome call(CallRequest request, CallProgress progress) {
        madeOn << [Thread.currentThread().getClass().name == ENGINE_THREAD,
                   TransactionSynchronizationManager.isActualTransactionActive()]
        scripted.call(request, progress)
    }
}
