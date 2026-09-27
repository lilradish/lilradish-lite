package org.lilradish.lite.testutil.listing

import org.lilradish.lite.domain.listing.ListColumn
import org.lilradish.lite.domain.listing.ListValueKind

/** The columns of a list of no feature in particular: text, text a row may leave empty, and a count. */
enum SampleColumn implements ListColumn {
    KEY("key", ListValueKind.TEXT),
    NAME("name", ListValueKind.NULLABLE_TEXT),
    SIZE("size", ListValueKind.LONG)

    final String spelt

    final ListValueKind valueKind

    SampleColumn(String spelt, ListValueKind valueKind) {
        this.spelt = spelt
        this.valueKind = valueKind
    }

    @Override
    String published() {
        spelt
    }

    @Override
    ListValueKind kind() {
        valueKind
    }
}
