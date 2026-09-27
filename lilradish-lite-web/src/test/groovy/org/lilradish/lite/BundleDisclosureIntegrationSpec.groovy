package org.lilradish.lite

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.lilradish.lite.testutil.EmittedNames
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification

/**
 * One archive ships, and the frontend bundle inside it is served to anyone who reaches the ungated
 * static paths. Only {@code /api} is gated, so whatever the bundle carries is published — and these
 * are the things it must never carry. Read off the classpath rather than out of the source tree,
 * because what a browser is handed is what the bundler emitted, and a value inlined at build time
 * exists in no source file.
 *
 * <p>An inlined build-time value is not caught here: inlining replaces the very text searched for.
 * The build refuses to hold one ({@code inliningNothing} in vite.config.ts), and the lint says so at
 * the source.
 *
 * <p>Deliberately no denylist of secret-sounding words and deliberately no entropy scan. Measured
 * against this bundle, "password", "credential", "BEGIN" and "token" each already match minified
 * dependency code — a MUI input type, React's {@code use-credentials}, an SVG {@code begin}
 * attribute, react-intl's parser — and entropy cannot tell minified JavaScript from a key. A check
 * that cries wolf is a check somebody switches off, after which nothing guards this at all. Every
 * assertion below was measured at zero against the bundle as it stands.
 */
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
        "assets/evil-AAAAAAAA.pem"   || false
        "assets/index.js"            || false
        "assets/index-DFfciWW.js"    || false
        "index-DFfciWWi.js"          || false
        "assets/x/index-DFfciWWi.js" || false
    }

    /** The alphabet asked of every name is the one the build is told to hash in, not one assumed of it. */
    def "the build is configured to hash in the alphabet the names are held to"() {
        expect:
        Files.readString(BUILD_CONFIGURATION).count('hashCharacters: "base64"') == 1
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
