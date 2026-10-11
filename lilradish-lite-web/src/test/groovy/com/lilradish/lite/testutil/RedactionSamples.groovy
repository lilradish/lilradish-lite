package com.lilradish.lite.testutil

import com.lilradish.lite.AppLoggingConvention
import com.lilradish.lite.domain.identity.GroupId
import com.lilradish.lite.domain.identity.Scope
import com.lilradish.lite.domain.identity.SubjectId
import com.lilradish.lite.domain.run.ProductionValueId
import com.lilradish.lite.domain.run.RunId
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.nio.file.Path
import java.time.Instant
import org.libprunus.core.log.annotation.MaxMessageLength

/** Values the LoggingRedaction specs render, held once so the identifiers their assertions spell stay one set. */
final class RedactionSamples {

    static final RunId RUN = new RunId(UUID.fromString("44440000-0000-4000-8000-000000000001"))

    static final SubjectId OVERSEER = new SubjectId(UUID.fromString("44440000-0000-4000-8000-000000000003"))

    static final SubjectId NIGHTLY_DIGEST = new SubjectId(UUID.fromString("44440000-0000-4000-8000-000000000006"))

    static final Instant DECIDED_AT = Instant.parse("2026-09-20T09:00:00Z")

    static final Scope.Group FINANCE =
            new Scope.Group(new GroupId(UUID.fromString("44440000-0000-4000-8000-000000000005")))

    static final ProductionValueId A_VALUE =
            new ProductionValueId(UUID.fromString("44440000-0000-4000-8000-000000000008"))

    /** Read off the declaration rather than restated, so the budget and the cut cannot drift apart. */
    static final int BUDGET = declaredBudget()

    private RedactionSamples() {}

    /** CLASS-retained annotations require reading class files rather than runtime metadata. */
    private static int declaredBudget() {
        def classes = Path.of(AppLoggingConvention.protectionDomain.codeSource.location.toURI())
        def declaring = classes.resolve(AppLoggingConvention.name.replace(".", File.separator) + ".class")
        def annotation = ClassDesc.of(MaxMessageLength.name)
        ClassFile.of().parse(declaring)
                .findAttribute(Attributes.runtimeInvisibleAnnotations())
                .orElseThrow()
                .annotations()
                .find { it.classSymbol() == annotation }
                .elements()
                .find { it.name().stringValue() == "value" }
                .value()
                .resolvedValue() as int
    }
}
