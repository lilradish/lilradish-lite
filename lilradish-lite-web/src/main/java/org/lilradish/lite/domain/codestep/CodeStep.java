package org.lilradish.lite.domain.codestep;

import org.lilradish.lite.domain.wire.JsonValue;

/**
 * One code step this release holds: the name steps name it by, what it declares, and the code. The declaration
 * is written in the same type as the code, so a change to either is seen by whoever reads the one file.
 */
public interface CodeStep {

    CodeStepName name();

    CodeStepDeclaration declaration();

    /** What it gives back for what it takes, each shaped as its declaration says. */
    JsonValue.JsonObject run(JsonValue.JsonObject takes);
}
