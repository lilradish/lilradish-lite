package org.lilradish.lite.app.codestep;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.lilradish.lite.domain.codestep.CodeStep;
import org.lilradish.lite.domain.codestep.CodeStepDeclaration;
import org.springframework.stereotype.Component;

/**
 * Every code step this release holds, each under its own name, gathered once as it starts: two under one name
 * stop the start, since a step naming it could not say which runs. Each declaration is read from its code step
 * whenever it is asked for, and from nowhere else.
 */
@Component
public final class CodeSteps {

    /* By the name as spelled, which is how the store spells it: a label no name could be is simply not held. */
    private final Map<String, CodeStep> held;

    CodeSteps(List<CodeStep> held) {
        Map<String, CodeStep> byName = new TreeMap<>();
        for (CodeStep codeStep : held) {
            String name = codeStep.name().value();
            if (byName.putIfAbsent(name, codeStep) != null) {
                throw new IllegalArgumentException("CodeSteps holds " + name + " more than once");
            }
        }
        this.held = Collections.unmodifiableMap(byName);
    }

    /** None where this release holds no code step spelled so. */
    public Optional<CodeStepDeclaration> declaration(String name) {
        CodeStep codeStep = held.get(name);
        return codeStep == null ? Optional.empty() : Optional.of(codeStep.declaration());
    }

    /** None where this release holds no code step spelled so. */
    Optional<CodeStep> held(String name) {
        return Optional.ofNullable(held.get(name));
    }

    /** Every code step it holds, by name in name order, with what it declares now. */
    public Map<String, CodeStepDeclaration> declarations() {
        Map<String, CodeStepDeclaration> declared = new TreeMap<>();
        held.forEach((name, codeStep) -> declared.put(name, codeStep.declaration()));
        return Collections.unmodifiableMap(declared);
    }
}
