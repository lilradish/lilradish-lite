package org.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.wire.JsonValue;

/**
 * One run of a code step, as a try that fits hands it over: which code step, what the release declared of it as
 * the try began, the terms of every list that declares, and what it takes, filled in from what the step binds.
 */
public record CodeCall(
        CodeStepName name,
        @DoNotLog CodeStepDeclaration declared,
        @DoNotLog Map<EntryVersionId, OfferedTerms> lists,
        @DoNotLog JsonValue.JsonObject takes) {

    public CodeCall {
        requireNonNull(name, "CodeCall name must not be null");
        requireNonNull(declared, "CodeCall declared must not be null");
        lists = Map.copyOf(requireNonNull(lists, "CodeCall lists must not be null"));
        requireNonNull(takes, "CodeCall takes must not be null");
        for (EntryVersionId list : declared.lists()) {
            if (!lists.containsKey(list)) {
                throw new IllegalArgumentException(
                        "CodeCall to " + name.value() + " is handed no terms for list " + list.value());
            }
        }
    }
}
