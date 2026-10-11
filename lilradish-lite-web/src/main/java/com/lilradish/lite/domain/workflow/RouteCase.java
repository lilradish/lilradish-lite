package com.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.registry.EntryVersionId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * @param id its stored key, which changes whenever the steps are written again
 * @param term none for the fallback, which takes whatever no case claims
 * @param target none where no version is chosen yet
 */
public record RouteCase(
        UUID id, @Nullable String term, @Nullable EntryVersionId target, List<Binding> bindings) {

    public RouteCase {
        requireNonNull(id, "RouteCase id must not be null");
        bindings = List.copyOf(requireNonNull(bindings, "RouteCase bindings must not be null"));
    }
}
