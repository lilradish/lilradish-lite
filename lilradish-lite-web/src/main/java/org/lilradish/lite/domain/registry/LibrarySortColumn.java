package org.lilradish.lite.domain.registry;

import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListValueKind;

/**
 * What a group's entries of one kind sort by. In service sorts by whether one is; status sorts
 * waiting, stopped-only, then neither. Published names are written out, as EstateAct's are.
 */
public enum LibrarySortColumn implements ListColumn {
    NAME("name", ListValueKind.TEXT),

    IN_SERVICE("inService", ListValueKind.BOOLEAN),

    STATUS("status", ListValueKind.LONG);

    private final String published;

    private final ListValueKind kind;

    LibrarySortColumn(String published, ListValueKind kind) {
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
