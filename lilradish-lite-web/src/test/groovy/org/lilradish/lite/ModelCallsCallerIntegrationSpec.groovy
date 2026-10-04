package org.lilradish.lite

import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.FieldRefEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.lang.classfile.constantpool.MethodRefEntry
import java.lang.classfile.constantpool.PoolEntry
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.Files
import java.nio.file.Path
import org.lilradish.lite.app.inference.development.DevelopmentModelCalls
import org.springframework.context.Lifecycle
import spock.lang.Specification

/**
 * A model must be called on the run engine's own threads and outside any transaction.
 * Constant pools retain calls, method references and types named only in descriptors.
 */
class ModelCallsCallerIntegrationSpec extends Specification {

    /** javac's own destination directory, for the reason {@link DomainPurityIntegrationSpec#COMPILED} gives. */
    static final Path COMPILED = DomainPurityIntegrationSpec.COMPILED

    static final Path DEVELOPMENT = Path.of(DevelopmentModelCalls.protectionDomain.codeSource.location.toURI())

    static final String MODEL_CALLS = "org/lilradish/lite/domain/inference/ModelCalls"

    static final String CALL_DESCRIPTOR = "(Lorg/lilradish/lite/domain/inference/CallRequest;" +
            "Lorg/lilradish/lite/domain/inference/CallProgress;)Lorg/lilradish/lite/domain/inference/CallOutcome;"

    static final String TOOL_MODEL_CALLS = "org/lilradish/lite/app/inference/ToolModelCalls"

    static final String TOOL_CALL_DESCRIPTOR = "(Lorg/lilradish/lite/app/inference/ToolCallRequest;" +
            "Lorg/lilradish/lite/domain/inference/CallProgress;)Lorg/lilradish/lite/app/inference/ToolCallOutcome;"

    static final String ENGINE_CALLS = "org.lilradish.lite.app.run.EngineCalls"

    static final String SHUTDOWN = "org/lilradish/lite/app/inference/ModelCallShutdown"

    static final String ENGINE_THREAD = "org/lilradish/lite/app/run/EngineThread"

    static final Map<String, List<PoolEntry>> POOLS = pools(COMPILED) + pools(DEVELOPMENT)

    def "the walk reaches the classes each rule turns on, in both trees, a wrong root leaving them empty"() {
        expect:
        POOLS.keySet().containsAll(["org.lilradish.lite.app.run.EngineCalls",
                                    "org.lilradish.lite.app.run.EngineExecutor",
                                    "org.lilradish.lite.app.run.EngineThread",
                                    "org.lilradish.lite.domain.inference.ModelCalls",
                                    "org.lilradish.lite.app.inference.ToolModelCalls",
                                    "org.lilradish.lite.app.inference.development.DevelopmentModelCalls",
                                    "org.lilradish.lite.app.inference.development.DevelopmentToolModelCalls"])

        and: "each carrying what it referenced"
        POOLS.every { !it.value.isEmpty() }
    }

    def "a model is called only by the engine's calls, on whatever type, a method reference to the call counting as one"() {
        expect:
        POOLS.findAll { it.value.any { calls(it, CALL_DESCRIPTOR) } }.keySet() == [ENGINE_CALLS] as Set
    }

    def "a model is called with tools by nothing but the engine's calls, on whatever type, a method reference to the call counting as one"() {
        expect:
        POOLS.findAll { it.value.any { calls(it, TOOL_CALL_DESCRIPTOR) } }.keySet() == [ENGINE_CALLS] as Set
    }

    def "only the engine's calls, the endpoint's wiring and the stand-in name where a model is called at all"() {
        expect:
        POOLS.findAll { it.value.any { namesModelCalls(it) } }.keySet() == [
                "org.lilradish.lite.domain.inference.ModelCalls",
                "org.lilradish.lite.app.run.EngineCalls",
                "org.lilradish.lite.app.inference.ModelCallsConfiguration",
                "org.lilradish.lite.app.inference.ModelCallsRequired",
                "org.lilradish.lite.app.inference.OpenAiCompatibleModelCalls",
                "org.lilradish.lite.app.inference.development.DevelopmentModelCalls"] as Set
    }

    def "only the engine's calls, the endpoint's wiring and the stand-in name where a model is called with tools at all"() {
        expect:
        POOLS.findAll { it.value.any { names(it, TOOL_MODEL_CALLS) } }.keySet() == [
                "org.lilradish.lite.app.inference.ToolModelCalls",
                ENGINE_CALLS,
                "org.lilradish.lite.app.inference.ConfiguredToolModelCalls",
                "org.lilradish.lite.app.inference.ModelCallsConfiguration",
                "org.lilradish.lite.app.inference.development.DevelopmentToolModelCalls"] as Set
    }

    def "only the engine's executor makes the thread a model may be called on"() {
        expect:
        POOLS.findAll { it.value.any { constructs(it, ENGINE_THREAD) } }.keySet() ==
                ["org.lilradish.lite.app.run.EngineExecutor"] as Set
    }

    /**
     * Executor shutdown must follow the signal that wakes the calls it drains.
     * A lifecycle dependency would reverse that order and wait on those calls.
     */
    def "the engine's executor takes the store and how long to drain, never the model calls or their shutdown signal, and is no lifecycle"() {
        given:
        def executor = Class.forName("org.lilradish.lite.app.run.EngineExecutor")
        def taken = executor.declaredConstructors.collectMany { it.parameterTypes.toList() }*.name

        expect:
        taken as Set == ["javax.sql.DataSource", "org.springframework.beans.factory.ObjectProvider",
                         "org.lilradish.lite.app.codestep.CodeStepDeployment"] as Set
        taken.size() == 3
        !Lifecycle.isAssignableFrom(executor)
        !POOLS["org.lilradish.lite.app.run.EngineExecutor"].any { names(it, SHUTDOWN) || names(it, MODEL_CALLS) }

        and: "over a check that still convicts the class the signal is made for"
        POOLS["org.lilradish.lite.app.inference.ModelCallsConfiguration"].any { names(it, SHUTDOWN) }
    }

    /* On any owner: a call made on an implementation's own type names no interface at all. */
    private static boolean calls(PoolEntry entry, String descriptor) {
        entry instanceof MemberRefEntry && !(entry instanceof FieldRefEntry) && entry.name().equalsString("call") &&
                entry.type().equalsString(descriptor)
    }

    private static boolean namesModelCalls(PoolEntry entry) {
        names(entry, MODEL_CALLS)
    }

    private static boolean names(PoolEntry entry, String type) {
        (entry instanceof ClassEntry && entry.asInternalName() == type) ||
                (entry instanceof Utf8Entry && entry.stringValue().contains("L${type};"))
    }

    private static boolean constructs(PoolEntry entry, String owner) {
        entry instanceof MethodRefEntry && entry.owner().asInternalName() == owner &&
                entry.name().equalsString("<init>")
    }

    private static Map<String, List<PoolEntry>> pools(Path root) {
        Files.walk(root.resolve("org/lilradish/lite")).withCloseable { paths ->
            paths.filter { it.toString().endsWith(".class") }
                    .toList()
                    .collectEntries { [binaryName(root, it), ClassFile.of().parse(it).constantPool().toList()] }
        }
    }

    private static String binaryName(Path root, Path file) {
        def relative = root.relativize(file).toString()
        relative.substring(0, relative.length() - ".class".length()).replace(File.separator, ".")
    }
}
