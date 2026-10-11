package com.lilradish.lite.app.group;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.identity.GroupId;

/**
 * Group-scoped lists share one spelling: cursors bind to it and statements narrow by it, so two
 * spellings would be two scopes.
 */
public final class GroupLists {

    private GroupLists() {}

    public static String within(GroupId group) {
        requireNonNull(group, "GroupLists group must not be null");
        return group.value().toString();
    }
}
