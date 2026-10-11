package com.lilradish.lite.testutil.run

final class EngineCodeTasks {

    private EngineCodeTasks() {}

    static void run(Object executor, Runnable task) {
        executor.handOver(executor.@codePool, task, null)
    }
}
