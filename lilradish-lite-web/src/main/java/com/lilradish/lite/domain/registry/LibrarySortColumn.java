package com.lilradish.lite.domain.registry;

import com.lilradish.lite.domain.listing.ListColumn;
import com.lilradish.lite.domain.listing.ListValueKind;

/**
 * In service sorts by whether one is; status sorts waiting, stopped-only, then neither. Published names are written out, as EstateAct's are.
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
