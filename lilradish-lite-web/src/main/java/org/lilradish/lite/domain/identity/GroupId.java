package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * Which group a piece of work belongs to, asked in the one way correcting the group's name cannot
 * change. The name sits beside this and is not carried here, because nothing reads a group by it.
 */
public record GroupId(UUID value) {

    public GroupId {
        requireNonNull(value, "GroupId must not be null");
    }
}
