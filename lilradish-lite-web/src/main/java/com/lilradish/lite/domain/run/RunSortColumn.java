package com.lilradish.lite.domain.run;

import com.lilradish.lite.domain.listing.ListColumn;
import com.lilradish.lite.domain.listing.ListValueKind;

/**
 * The workflow column uses the workflow's name. A run's state has no stored sort key: it is computed
 * from the stop in force and step positions when read.
 */
public enum RunSortColumn implements ListColumn {
    NUMBER("run", ListValueKind.LONG),

    NAME("name", ListValueKind.TEXT),

    WORKFLOW("workflow", ListValueKind.TEXT),

    STARTED("started", ListValueKind.LONG),

    STARTED_BY("startedBy", ListValueKind.NULLABLE_TEXT);

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
