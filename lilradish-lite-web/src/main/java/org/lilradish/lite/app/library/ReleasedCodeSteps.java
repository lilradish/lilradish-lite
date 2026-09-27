package org.lilradish.lite.app.library;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.codestep.CodeSteps;
import org.springframework.stereotype.Component;

/**
 * What this release declares of each code step it holds, keyed as {@link StoredDeclarations#ofCodeStep} keys it.
 * Worked out once: what a release holds changes only by deploying another.
 */
@Component
final class ReleasedCodeSteps {

    private final Map<String, StoredDeclarations.Halves> declared;

    ReleasedCodeSteps(CodeSteps codeSteps) {
        Map<String, StoredDeclarations.Halves> keyed = new HashMap<>();
        codeSteps
                .declarations()
                .forEach((name, declaration) -> keyed.put(name, StoredDeclarations.ofCodeStep(name, declaration)));
        this.declared = Map.copyOf(keyed);
    }

    /** None where this release holds no code step spelled so. */
    StoredDeclarations.@Nullable Halves of(String name) {
        return declared.get(name);
    }
}
