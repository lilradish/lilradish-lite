package com.lilradish.lite.domain.inference;

import static java.util.Objects.requireNonNull;

import org.libprunus.core.log.annotation.DoNotLog;

/** @param stopped whether downloading it was stopped here */
public record UnreadableResponseRecord(
        @DoNotLog KeptAnswer kept, @DoNotLog boolean stopped) {

    public UnreadableResponseRecord {
        requireNonNull(kept, "Unreadable response evidence must not be null");
    }
}
