package org.lilradish.lite.domain.listing;

/**
 * A column a list may be sorted by: the name a reader asks for it by, and the kind of value a row holds
 * in it. Implemented by an enum per list, whose constants are the whole of what that list sorts by.
 */
public interface ListColumn {

    String published();

    ListValueKind kind();
}
