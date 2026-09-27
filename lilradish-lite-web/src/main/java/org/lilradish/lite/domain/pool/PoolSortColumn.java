package org.lilradish.lite.domain.pool;

import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListValueKind;

/**
 * The columns the pool may be sorted by, under the names a reader asks for them by. The roles
 * somebody holds are shown and are not among them: they are a set, and a set has no order to offer.
 *
 * <p>The published name is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum PoolSortColumn implements ListColumn {
    USER_ID("userId", ListValueKind.TEXT),

    DISPLAY_NAME("displayName", ListValueKind.NULLABLE_TEXT),

    GROUP_COUNT("groupCount", ListValueKind.LONG);

    private final String published;

    private final ListValueKind kind;

    PoolSortColumn(String published, ListValueKind kind) {
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
