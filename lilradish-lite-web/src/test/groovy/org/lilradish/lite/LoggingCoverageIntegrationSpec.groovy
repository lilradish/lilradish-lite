package org.lilradish.lite

import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.constant.ClassDesc
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import org.libprunus.core.log.annotation.DoNotLog
import org.libprunus.core.log.runtime.AbstractLogConfig
import org.libprunus.core.log.runtime.Loggable
import org.lilradish.lite.domain.identity.Delegation
import org.lilradish.lite.domain.identity.Principal
import org.lilradish.lite.domain.identity.Scope
import org.lilradish.lite.domain.identity.SubjectId
import org.lilradish.lite.domain.identity.SystemPrincipal
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.inference.Confidence
import org.lilradish.lite.domain.model.ModelPrice
import org.lilradish.lite.domain.run.DecisionRecord
import org.lilradish.lite.domain.run.ReviewRecord
import org.lilradish.lite.domain.run.RunId
import org.lilradish.lite.domain.run.RunSnapshot
import org.lilradish.lite.domain.run.StepInputs
import org.lilradish.lite.domain.run.StepSnapshot
import org.lilradish.lite.domain.run.TryRecord
import org.lilradish.lite.domain.run.ValueRecord
import org.lilradish.lite.domain.workflow.StepId
import spock.lang.Specification

/**
 * A value nested inside a rewritten class is readable only if it was rewritten too, or is one of the
 * runtime's builtins. Anything else renders as a class name and an identity hash, which names
 * nothing — and the suffix list in {@link AppLoggingConvention} that decides who is rewritten is
 * maintained by hand.
 *
 * <p>{@link LoggingRedactionIntegrationSpec} asserts carrier by carrier, so it fails only for the
 * carriers someone thought to name there. This asks the question that cannot be answered one carrier
 * at a time: of every value a rewritten class renders, is that value renderable in turn?
 *
 * <p>Keyed on the woven class files rather than on the annotation, so it cannot drift out of step
 * with what the build actually produced.
 */
class LoggingCoverageIntegrationSpec extends Specification {

    static final Path CLASSES = Path.of(AppLoggingConvention.protectionDomain.codeSource.location.toURI())

    /**
     * Descended into rather than judged: what these render is their elements. Optional is not one —
     * the runtime has no branch for it, so it renders as an identity hash and is judged as itself.
     */
    static final List<Class<?>> CONTAINERS = [Collection, Map]

    static final List<Class<?>> WOVEN = wovenClasses()

    def "the walk finds the classes the rewrite wove, and none the rewrite refuses to weave"() {
        expect:
        WOVEN.containsAll([RunSnapshot, StepSnapshot, TryRecord, ValueRecord, ReviewRecord, DecisionRecord,
                           StepInputs.BindingRecord, Confidence, SubjectId, UserId, SystemPrincipal, RunId,
                           StepId])

        and: "while an interface the suffix list does name is absent, the rewrite refusing to weave one"
        !WOVEN.contains(Principal)

        and: "and so is a value type no suffix names, which is what makes the list a filter at all"
        !WOVEN.contains(ModelPrice)
    }

    def "the components a class renders are its instance fields, less the ones the rewrite drops"() {
        expect:
        renderedComponents(owner).collect { "${it.declaringClass.simpleName}#${it.name}" }.toSorted() ==
                rendered.toSorted()

        where:
        owner                    || rendered
        StepInputs.BindingRecord || ["BindingRecord#source"]
        ValueRecord              || ["ValueRecord#id", "ValueRecord#field", "ValueRecord#confidence", "ValueRecord#needsReview"]
        DecisionRecord           || ["DecisionRecord#value", "DecisionRecord#outcome"]
        SystemPrincipal          || ["SystemPrincipal#subject"]
    }

    def "a value reached only through a collection is judged, which its erasure alone would hide"() {
        expect:
        renderedTypes(TryRecord.getDeclaredField("values").genericType) == [ValueRecord]

        and: "which is what the judgement rests on, the collection itself naming nothing"
        !renderable(List)
    }

    def "a sealed interface component is judged as the subtypes it permits, an interface never being woven"() {
        expect:
        renderedTypes(Delegation.getDeclaredField("scopes").genericType) == [Scope.Group, Scope.Estate]

        and: "which is the only way it is judged at all"
        !renderable(Scope)
    }

    def "every value a woven class renders is itself renderable rather than an identity hash"() {
        given:
        def components = WOVEN.collectMany { renderedComponents(it) }

        when:
        def unrenderable = components.collectMany { component ->
            renderedTypes(component.genericType)
                    .findAll { !renderable(it) }
                    .collect { "${component.declaringClass.name}#${component.name} holds ${it.name}" as String }
        }

        then:
        unrenderable == []

        and: "over components that were walked at all, which a broken enumeration would leave empty"
        !components.isEmpty()
    }

    private static List<Class<?>> wovenClasses() {
        Files.walk(CLASSES.resolve("org/lilradish/lite")).withCloseable { paths ->
            paths.filter { it.toString().endsWith(".class") }
                    .map { CLASSES.relativize(it) }
                    .map { load(it) }
                    .filter { Loggable.isAssignableFrom(it) }
                    .toList()
                    .toSorted { it.name }
        }
    }

    private static Class<?> load(Path relative) {
        def file = relative.toString()
        def binaryName = file.substring(0, file.length() - ".class".length()).replace(File.separator, ".")
        Class.forName(binaryName, false, LoggingCoverageIntegrationSpec.classLoader)
    }

    /** Layer by layer down the chain, because the rewrite renders inherited fields too. */
    private static List<Field> renderedComponents(Class<?> owner) {
        def rendered = []
        for (Class<?> layer = owner; layer != null && layer != Object; layer = layer.superclass) {
            def dropped = droppedComponents(layer)
            def root = layer == owner
            rendered += layer.declaredFields.findAll {
                !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.synthetic &&
                        !dropped.contains(it.name) && (root || !Modifier.isPrivate(it.modifiers))
            }
        }
        rendered
    }

    /*
     * @DoNotLog is CLASS-retained, so reflection cannot see it: the class file is the only place a
     * dropped slot still shows.
     */
    private static Set<String> droppedComponents(Class<?> owner) {
        // java.lang.Record stands in the chain of every record, and has no class file to read here.
        def file = CLASSES.resolve(owner.name.replace(".", File.separator) + ".class")
        if (!Files.exists(file)) {
            return [] as Set
        }
        def annotation = ClassDesc.of(DoNotLog.name)
        ClassFile.of().parse(file).fields()
                .findAll {
                    it.findAttribute(Attributes.runtimeInvisibleAnnotations())
                            .orElse(null)?.annotations()?.any { it.classSymbol() == annotation }
                }
                .collect { it.fieldName().stringValue() } as Set
    }

    private static List<Class<?>> renderedTypes(Type type) {
        if (type instanceof ParameterizedType) {
            Class<?> raw = type.rawType as Class
            return CONTAINERS.any { it.isAssignableFrom(raw) }
                    ? type.actualTypeArguments.collectMany { renderedTypes(it) }
                    : [raw]
        }
        if (type instanceof Class) {
            if (type.isArray()) {
                return renderedTypes(type.componentType)
            }
            return type.isInterface() && type.isSealed()
                    ? type.permittedSubclasses.collectMany { renderedTypes(it) }
                    : [type]
        }
        throw new IllegalStateException("No rule says what a component typed ${type.typeName} renders as")
    }

    private static boolean renderable(Class<?> type) {
        type.isPrimitive() || type.isEnum() || Loggable.isAssignableFrom(type) ||
                AbstractLogConfig.DEFAULT.isWhitelisted(type)
    }
}
