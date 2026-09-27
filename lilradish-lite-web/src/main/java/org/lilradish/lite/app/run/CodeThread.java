package org.lilradish.lite.app.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread of the run engine's code pool, which only that pool's factory makes: a code step is run on one of these
 * and on nothing else, and never a model called, so code that never returns holds up no call.
 */
final class CodeThread extends Thread {

    private static final Logger logger = LoggerFactory.getLogger(CodeThread.class);

    /* Made by a factory, the pool names none of its threads, so each names itself as the pool would. */
    CodeThread(Runnable task, int number) {
        super(task, "run-code-" + number);
        setPriority(NORM_PRIORITY);
        setDaemon(false);
        // Whatever ends one is logged by its kind alone: printed whole, as by default, it could say what code said.
        setUncaughtExceptionHandler((ended, fatal) -> logger.error(
                "Run code thread {} ended with {}",
                ended.getName(),
                fatal.getClass().getName()));
    }
}
