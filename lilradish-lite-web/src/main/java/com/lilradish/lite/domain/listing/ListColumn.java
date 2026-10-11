package com.lilradish.lite.domain.listing;

/** Implemented by an enum per list, whose constants are its complete set of sortable columns. */
public interface ListColumn {

    String published();

    ListValueKind kind();
}
