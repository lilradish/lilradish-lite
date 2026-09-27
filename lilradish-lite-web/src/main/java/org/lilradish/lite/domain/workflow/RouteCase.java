package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * One way a route may go: the term it is chosen on, the workflow version it leads to, and what fills each input
 * of that workflow.
 *
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
