package org.lilradish.lite.domain.run;

/** Stored labels derive from enum names through StoreLabels, so renaming one requires a storage change. */
public enum ReviewUnbuiltReason {
    LIST_NOT_HERE,
    TAKES_NO_LONGER_DECLARED,
    NO_LONGER_DECLARED,
    HOLDS_A_FILE
}
