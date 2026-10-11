package com.lilradish.lite.domain.groupregister;

import com.lilradish.lite.domain.listing.ListColumn;
import com.lilradish.lite.domain.listing.ListValueKind;

/**
 * Every column the register shows can be sorted by.
 *
 * <p>The published name is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
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
