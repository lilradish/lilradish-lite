package org.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * A code step as the running release declares it now, beside the terms of each list it pins that the run's group
 * holds. A list another group's, or not here at all, is absent from {@code lists}.
 */
public record ReleasedCodeStep(CodeStepDeclaration declared, Map<EntryVersionId, OfferedTerms> lists) {

    public ReleasedCodeStep {
        requireNonNull(declared, "ReleasedCodeStep declared must not be null");
        lists = Map.copyOf(requireNonNull(lists, "ReleasedCodeStep lists must not be null"));
    }

    public boolean mayRunAgain() {
        return declared.mayRunAgain();
    }

    /** Every list it pins that {@code lists} does not hold, in the order {@link CodeStepDeclaration#lists} gives. */
    public Set<EntryVersionId> listsMissing() {
        Set<EntryVersionId> missing = new LinkedHashSet<>(declared.lists());
        missing.removeAll(lists.keySet());
        return Collections.unmodifiableSet(missing);
    }
}
