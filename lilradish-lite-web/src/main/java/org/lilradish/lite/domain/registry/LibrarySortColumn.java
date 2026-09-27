package org.lilradish.lite.domain.registry;

import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListValueKind;

/**
 * What a group's entries of one kind sort by. In service sorts by whether one is, two entries' version
 * numbers saying nothing of each other; published names are written out, as EstateAct's are.
 */
public enum LibrarySortColumn implements ListColumn {
    NAME("name", ListValueKind.TEXT),

    IN_SERVICE("inService", ListValueKind.BOOLEAN),

    SUBMITTED("submitted", ListValueKind.BOOLEAN),

    STOPPED("stopped", ListValueKind.BOOLEAN);

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
