package org.lilradish.lite.testutil

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

/**
 * Keeps each line as it was when it was logged. An event reads the logging context only when first
 * asked, which after the request has let go of it is too late.
 */
class SnapshottingAppender extends ListAppender<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        event.prepareForDeferredProcessing()
        super.append(event)
    }
}
