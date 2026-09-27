package org.lilradish.lite.testutil.codestep

import org.lilradish.lite.app.codestep.CodeSteps
import org.lilradish.lite.domain.codestep.CodeStep

/** What a release holds of code steps, as a spec deploys it: none, or exactly those it names. */
final class CodeStepsHeld {

    /** A release holding no code step, as one deployed with none. */
    static final CodeSteps NONE = new CodeSteps([])

    private CodeStepsHeld() {}

    static CodeSteps of(CodeStep... held) {
        new CodeSteps(held.toList())
    }
}
