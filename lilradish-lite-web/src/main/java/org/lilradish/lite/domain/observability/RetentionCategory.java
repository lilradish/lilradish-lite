package org.lilradish.lite.domain.observability;

import static java.util.Objects.requireNonNull;

/** One named class of data and how long it is kept. */
public record RetentionCategory(String name, String description, Retention retention) {

    public RetentionCategory {
        requireNonNull(name, "RetentionCategory name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("RetentionCategory name must not be blank");
        }
        requireNonNull(description, "RetentionCategory description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("RetentionCategory " + name + " states no description");
        }
        requireNonNull(retention, "RetentionCategory retention must not be null");
    }
}
