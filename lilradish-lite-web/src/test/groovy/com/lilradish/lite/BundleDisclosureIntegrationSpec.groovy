package com.lilradish.lite

import com.lilradish.lite.testutil.EmittedNames
import com.lilradish.lite.testutil.ReaderVocabulary
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/** Read the emitted classpath bundle; source checks cover build-time inlining. Secret-word and entropy scans match dependencies. */
class BundleDisclosureIntegrationSpec extends Specification {

    /**
     * Where {@code processResources} puts what {@code frontendBundle} built. Resolved through the
     * classpath and not through a build path, so what is read is what the archive would carry.
     */
    static final Path BUNDLE = Path.of(Objects.requireNonNull(
            BundleDisclosureIntegrationSpec.getResource("/static"),
            "nothing is on the classpath at /static; the bundle reaches it through processResources").toURI())

    static final String HASHED_FILES = "assets/"

    /** The configuration the build reads, beside the tree it builds, whose hash alphabet the names are held to. */
    static final Path BUILD_CONFIGURATION = ReaderVocabulary.FRONTEND.resolve("../../../vite.config.ts").normalize()

    static final Map<String, String> EMITTED = emitted()

    def "the bundle was found on the classpath, a tree that was not satisfying every check below"() {
        expect:
        EMITTED.containsKey("index.html")

        and: "together with the script it loads, an entry document alone being an emptier output than this"
        EMITTED.keySet().any { it.startsWith("assets/") && it.endsWith(".js") }

        and: "each read as bytes that are there, an empty file carrying no needle either"
        EMITTED.every { name, content -> !content.isEmpty() }
    }

    def "no emitted file carries a PEM block"() {
        expect:
        carrying("-----BEGIN").isEmpty()

        and: "over a bundle holding files, an empty one carrying no key either"
        !EMITTED.isEmpty()
    }

    def "no emitted javascript still names import.meta.env or process.env"() {
        given:
        def javascript = EMITTED.findAll { name, content -> name.endsWith(".js") }

        expect:
        javascript.every { name, content -> !content.contains("import.meta.env") && !content.contains("process.env") }

        and: "over javascript that reached the output, emitting none being the other way to hold that"
        !javascript.isEmpty()
    }

    def "the bundle emits the entry document and content-hashed assets and nothing besides"() {
        expect:
        EMITTED.keySet().findAll { it != "index.html" && !hashedAsset(it) }.isEmpty()

        and: "hashed names having been matched rather than absent, the entry document alone passing that too"
        EMITTED.keySet().any { hashedAsset(it) }
    }

    /**
     * A hash of letters alone is as much a hash as any, and a file the bundle does not emit is not
     * one whatever it is called — a key parked under the assets directory among them.
     */
    def "an asset is known by the shape the build names it with, and by nothing else"() {
        expect:
        hashedAsset(name) == admitted

        where:
        name                         || admitted
        "assets/index-DFfciWWi.js"   || true
        "assets/index-BjQ1blGe.js"   || true
        "assets/chunk-a_b-C9d0.js"   || true
        "assets/styles-DFfciWWi.css" || true
        "assets/styles-BjQ1blGe.css" || true
        "assets/styles-DFfciWW.css"  || false
        "assets/styles-DFfciWWiX.css" || false
        "assets/styles.css"          || false
        "assets/styles-DFfciWWi.map" || false
        "assets/evil-AAAAAAAA.pem"   || false
        "assets/index.js"            || false
        "assets/index-DFfciWW.js"    || false
        "index-DFfciWWi.js"          || false
        "assets/x/index-DFfciWWi.js" || false
    }

    /** The alphabet asked of every name is the one the build is told to hash in, not one assumed of it. */
    def "the build is configured to hash in the alphabet the names are held to"() {
        expect:
        Files.readString(BUILD_CONFIGURATION).count('hashCharacters: "base64"') == 2
    }

    def "the prose worker is emitted, referenced by a client chunk, and carries no document dependency"() {
        given:
        def workers = EMITTED.findAll { name, content -> name.startsWith("assets/proseWorker-") && name.endsWith(".js") }

        expect:
        workers.size() == 1
        workers.every { name, content -> !content.isEmpty() && !content.contains("document.") && !content.contains("document[") }

        and:
        workers.every { name, content ->
            EMITTED.any { otherName, client -> otherName != name && otherName.endsWith(".js") && client.contains("/" + name) }
        }

        and:
        Files.readString(BUILD_CONFIGURATION).count('comments: { legal: true, annotation: false, jsdoc: false }') == 2
    }

    private static boolean hashedAsset(String emitted) {
        emitted.startsWith(HASHED_FILES) && EmittedNames.HASHED.matcher(emitted.substring(HASHED_FILES.length())).matches()
    }

    private static List<String> carrying(String needle) {
        EMITTED.findAll { name, content -> content.contains(needle) }.keySet().toList()
    }

    /**
     * Latin-1, not UTF-8: every byte decodes, so a font or an image arriving in the output is judged
     * rather than thrown over, and every needle above is ASCII in either encoding.
     */
    private static Map<String, String> emitted() {
        def files = Files.walk(BUNDLE).withCloseable { tree ->
            tree.filter { path -> Files.isRegularFile(path) }.toList()
        }
        assert !files.isEmpty(): "the bundle at ${BUNDLE} holds no file"
        files.collectEntries { path ->
            [BUNDLE.relativize(path).toString(), new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1)]
        }
    }
}
