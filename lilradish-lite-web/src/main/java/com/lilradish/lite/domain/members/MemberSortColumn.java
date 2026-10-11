package com.lilradish.lite.domain.members;

import com.lilradish.lite.domain.listing.ListColumn;
import com.lilradish.lite.domain.listing.ListValueKind;

/**
 * Roles are shown but cannot be sorted by: they are a set, and a set has no order to offer.
 *
 * <p>The published name is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum MemberSortColumn implements ListColumn {
    USER_ID("userId", ListValueKind.TEXT),

    DISPLAY_NAME("displayName", ListValueKind.NULLABLE_TEXT);

    private final String published;

    private final ListValueKind kind;

    MemberSortColumn(String published, ListValueKind kind) {
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
