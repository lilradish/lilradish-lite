package org.lilradish.lite.app.group;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.identity.GroupId;

/**
 * What a list read within one group is read within, spelt once for every such list: its cursors are bound
 * to this spelling, and its statement narrows by it, so two spellings would be two scopes.
 */
public final class GroupLists {

    private GroupLists() {}

    /** The scope a list's query is read within, for a list of what one group holds. */
    public static String within(GroupId group) {
        requireNonNull(group, "GroupLists group must not be null");
        return group.value().toString();
    }
}
