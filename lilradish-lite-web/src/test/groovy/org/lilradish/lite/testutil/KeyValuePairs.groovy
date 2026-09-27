package org.lilradish.lite.testutil

import ch.qos.logback.classic.spi.ILoggingEvent

/** The key-value pairs a line was logged with, by key. */
final class KeyValuePairs {

    private KeyValuePairs() {}

    static Map<String, Object> of(ILoggingEvent line) {
        line.keyValuePairs.collectEntries { [(it.key): it.value] }
    }
}
