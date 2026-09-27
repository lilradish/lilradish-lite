package org.lilradish.lite.app.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread of the run engine's own pool, which only that pool's factory makes: a model is called on one of these
 * and on nothing else, so no request thread can ever wait on a model.
 */
final class EngineThread extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(EngineThread.class);

    /* Made by a factory, the pool names none of its threads, so each names itself as the pool would. */
    EngineThread(Runnable task, int number) {
        super(task, "run-engine-" + number);
        setPriority(NORM_PRIORITY);
        setDaemon(false);
        // Whatever ends one is logged by its kind alone: printed whole, as by default, it could say what code said.
        setUncaughtExceptionHandler((ended, fatal) -> logger.error(
                "Run engine thread {} ended with {}",
                ended.getName(),
                fatal.getClass().getName()));
    }
}
