package org.lilradish.lite

import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * The domain holds value types, vocabularies and pure rules. Delivery and storage depend on it; it
 * depends on neither. That is true today and nothing in the build keeps it true, so it is asserted
 * here rather than remembered.
 *
 * <p>Judged on the constant pool rather than on import lines: a fully qualified reference, a type
 * reached only through a method descriptor, and an annotation all leave a mark there, and none of
 * them need an import to exist.
 */
class DomainPurityIntegrationSpec extends Specification {

    static final Path REWRITTEN = Path.of(AppLoggingConvention.protectionDomain.codeSource.location.toURI())

    /**
     * javac's own destination directory. The build rewrites what it finds there into {@link #REWRITTEN},
     * weaving in a logging runtime the source never named, so only this stage answers what the code
     * itself depends on.
     */
    static final Path COMPILED = REWRITTEN.resolveSibling("javaByteBuddyRaw")

    static final String DOMAIN = "org/lilradish/lite/domain"

    static final String WOVEN_MARKER = "org.libprunus.core.log.runtime.Loggable"

    static final List<String> FORBIDDEN_ROOTS = ["org.springframework", "jakarta", "javax.servlet",
                                                 "com.fasterxml.jackson", "tools.jackson", "java.sql", "javax.sql",
                                                 "org.libprunus.core.error", "dev.langchain4j"]

    /*
     * RefusalCode alone carries the platform contract deciding which HTTP category a refusal answers
     * with. Pinned to that class by name rather than folded into the roots above, so the next class
     * reaching for the same contract has to argue for itself instead of inheriting this.
     */
    static final Map<String, List<String>> PERMITTED_PLATFORM_CONTRACT = [
            "org.lilradish.lite.domain.failure.RefusalCode":
                    ["org.libprunus.core.error.ErrorCode", "org.libprunus.core.error.ErrorCategory"]]

    static final Pattern QUALIFIED_NAME = ~/[a-zA-Z_]\w*(?:\/\w+)+/

    static final Map<String, Set<String>> DOMAIN_REFERENCES = domainReferences()

    def "the walk reaches the domain, a wrong root or a swallowed filter leaving it empty"() {
        expect:
        DOMAIN_REFERENCES.keySet().containsAll(["org.lilradish.lite.domain.failure.RefusalCode",
                                                "org.lilradish.lite.domain.workflow.Pointer",
                                                "org.lilradish.lite.domain.run.RunSnapshot",
                                                "org.lilradish.lite.domain.people.PersonName",
                                                "org.lilradish.lite.domain.identity.SystemPrincipal"])

        and: "each carrying what it referenced, an empty pool satisfying any rule asked of it"
        DOMAIN_REFERENCES.every { !it.value.isEmpty() }
    }

    def "the tree read is javac's own, not the rewritten one whose references no source declares"() {
        given:
        def relative = "org/lilradish/lite/domain/run/TryRecord.class"

        expect:
        !references(COMPILED.resolve(relative)).contains(WOVEN_MARKER)

        and: "the shipped copy holding exactly that, which is what makes the earlier stage load-bearing"
        references(REWRITTEN.resolve(relative)).contains(WOVEN_MARKER)
    }

    def "no domain type reaches a framework, a transport or a persistence API"() {
        when:
        def violations = DOMAIN_REFERENCES.collectMany { owner, referenced ->
            def permitted = PERMITTED_PLATFORM_CONTRACT.getOrDefault(owner, [])
            referenced.findAll { forbidden(it) && !permitted.contains(it) }
                    .collect { "${owner} reaches for ${it}" as String }
        }

        then:
        violations.toSorted() == []

        and: "over a predicate that still convicts, one permitting everything being green and worthless"
        forbidden("org.springframework.stereotype.Component")
        forbidden("dev.langchain4j.model.chat.ChatModel")
        !forbidden("org.lilradish.lite.domain.run.RunId")
    }

    def "the platform error contract stays pinned to the single class the exception names"() {
        expect:
        DOMAIN_REFERENCES.findAll { it.value.any { name -> name.startsWith("org.libprunus.core.error.") } }
                .keySet() == PERMITTED_PLATFORM_CONTRACT.keySet()
    }

    private static Map<String, Set<String>> domainReferences() {
        Files.walk(COMPILED.resolve(DOMAIN)).withCloseable { paths ->
            paths.filter { it.toString().endsWith(".class") }
                    .toList()
                    .collectEntries { [binaryName(it), references(it)] }
        }
    }

    private static String binaryName(Path file) {
        def relative = COMPILED.relativize(file).toString()
        relative.substring(0, relative.length() - ".class".length()).replace(File.separator, ".")
    }

    private static Set<String> references(Path file) {
        ClassFile.of().parse(file).constantPool()
                .findResults { it instanceof Utf8Entry ? it.stringValue() : null }
                .collectMany { it.findAll(QUALIFIED_NAME) }
                .collect { it.replace("/", ".") } as Set
    }

    private static boolean forbidden(String name) {
        FORBIDDEN_ROOTS.any { name == it || name.startsWith(it + ".") }
    }
}
