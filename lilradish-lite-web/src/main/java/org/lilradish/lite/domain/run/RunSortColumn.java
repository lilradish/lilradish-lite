package org.lilradish.lite.domain.run;

import org.lilradish.lite.domain.listing.ListColumn;
import org.lilradish.lite.domain.listing.ListValueKind;

/**
 * What a group's runs sort by. A workflow sorts by its name alone; where a run is sorts by nothing, being worked out
 * from its steps as it is read rather than held in the store.
 */
public enum RunSortColumn implements ListColumn {
    NUMBER("run", ListValueKind.LONG),

    NAME("name", ListValueKind.TEXT),

    WORKFLOW("workflow", ListValueKind.TEXT),

    STARTED("started", ListValueKind.LONG),

    STARTED_BY("startedBy", ListValueKind.NULLABLE_TEXT),

    LAST_HAPPENED("lastHappened", ListValueKind.LONG);

    private final String published;

    private final ListValueKind kind;

    RunSortColumn(String published, ListValueKind kind) {
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
