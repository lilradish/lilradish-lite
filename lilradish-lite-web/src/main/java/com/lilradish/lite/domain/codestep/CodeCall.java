package com.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.filling.FillPath;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.wire.JsonValue;
import java.util.Map;
import org.libprunus.core.log.annotation.DoNotLog;

/** The declaration is captured as the try begins; inputs are filled from the step's bindings. */
public record CodeCall(
        CodeStepName name,
        @DoNotLog CodeStepDeclaration declared,
        @DoNotLog Map<EntryVersionId, OfferedTerms> lists,
        @DoNotLog JsonValue.JsonObject takes,
        @DoNotLog Map<FillPath, CodeStep.FileInput> files) {

    public CodeCall {
        requireNonNull(name, "CodeCall name must not be null");
        requireNonNull(declared, "CodeCall declared must not be null");
        lists = Map.copyOf(requireNonNull(lists, "CodeCall lists must not be null"));
        requireNonNull(takes, "CodeCall takes must not be null");
        files = Map.copyOf(requireNonNull(files, "CodeCall files must not be null"));
        for (EntryVersionId list : declared.lists()) {
            if (!lists.containsKey(list)) {
                throw new IllegalArgumentException(
                        "CodeCall to " + name.value() + " is handed no terms for list " + list.value());
            }
        }
    }
}
