package org.lilradish.lite.app.library;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * What a step runs, as a page names it when it reads a workflow version and when it writes one. The published
 * spelling is written out rather than folded from the constant name, for the reason
 * {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
enum RunsKind {
    QUESTION("question"),
    WORKFLOW("workflow"),
    CODE_STEP("code_step"),
    ROUTE("route");

    private static final Map<String, RunsKind> BY_SPELLING =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(RunsKind::published, Function.identity()));

    private final String published;

    RunsKind(String published) {
        this.published = published;
    }

    String published() {
        return published;
    }

    /** None where nothing is spelt so. */
    static @Nullable RunsKind spelt(String spelling) {
        return BY_SPELLING.get(spelling);
    }
}
