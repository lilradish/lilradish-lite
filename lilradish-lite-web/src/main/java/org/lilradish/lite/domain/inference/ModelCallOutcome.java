package org.lilradish.lite.domain.inference;

/** How a call to a model ended, as the store keeps it; a call not yet ended has none. */
public enum ModelCallOutcome {
    CAME_BACK,
    NOTHING_CAME_BACK,
    ERRORED,
    TURNED_AWAY
}
