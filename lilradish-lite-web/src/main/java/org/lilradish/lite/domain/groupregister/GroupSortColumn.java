package org.lilradish.lite.domain.groupregister;

import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListValueKind;

/**
 * The columns the register of groups may be sorted by, under the names a reader asks for them by. Every
 * column the register shows is among them.
 *
 * <p>The published name is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum GroupSortColumn implements ListColumn {
    KEY("key", ListValueKind.TEXT),

    NAME("name", ListValueKind.TEXT),

    CAN_BE_ADMINISTERED("canBeAdministered", ListValueKind.BOOLEAN),

    MEMBER_COUNT("memberCount", ListValueKind.LONG);

    private final String published;

    private final ListValueKind kind;

    GroupSortColumn(String published, ListValueKind kind) {
        this.published = published;
        this.kind = kind;
    }

    @Override
    public String published() {
        return published;
    }

    @Override
    public ListValueKind kind() {
        return kind;
    }
}
