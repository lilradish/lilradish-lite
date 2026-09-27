package org.lilradish.lite.domain.members;

/**
 * How somebody stopped holding a role in a group. Two acts rather than one, though both leave the row
 * closed: a role taken away and a person taken out of the group are different decisions, and a record
 * naming both alike has lost which of them anybody made.
 */
public enum GroupMemberRemoval {
    ROLE_TAKEN,

    REMOVED_FROM_GROUP
}
