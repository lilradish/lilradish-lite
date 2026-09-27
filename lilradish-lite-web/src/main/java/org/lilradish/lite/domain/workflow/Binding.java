package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One input filled, or what a route chooses its case by, from one source.
 *
 * @param id its stored key, which changes whenever the part holding it is written again
 * @param target none only where it is what a route chooses by, which fills no input
 */
public record Binding(UUID id, @Nullable Pointer target, BindingSource source) {

    public Binding {
        requireNonNull(id, "Binding id must not be null");
        requireNonNull(source, "Binding source must not be null");
    }
}
