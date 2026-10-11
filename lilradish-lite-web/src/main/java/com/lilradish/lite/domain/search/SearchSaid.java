package com.lilradish.lite.domain.search;

import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;

/**
 * What an answer said one search sought and found, as it came: not cleaned, and either may be unsaid, which an
 * empty text is not.
 */
public record SearchSaid(
        @DoNotLog @Nullable String searchedFor,
        @DoNotLog @Nullable String found) {}
