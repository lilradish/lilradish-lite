package com.lilradish.lite.domain.inference;

/** Uses the store's vocabulary; a call not yet ended has no outcome. */
public enum ModelCallOutcome {
    CAME_BACK,
    NOTHING_CAME_BACK,
    ERRORED,
    TURNED_AWAY
}
