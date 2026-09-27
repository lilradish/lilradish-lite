package org.lilradish.lite.testutil

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

/**
 * A vocabulary as the reader's side declares it: the string members of one exported union type,
 * read as text, because a union is erased before anything runs and so leaves no artefact to walk.
 */
final class ReaderVocabulary {

    /**
     * No default, because an empty string is the empty path, which resolves to the process working
     * directory: a default would have a spec read whatever is beside the runner and pass over a tree
     * it was never pointed at.
     */
    static final Path FRONTEND = Path.of(Objects.requireNonNull(System.getProperty("frontend.location"),
            "frontend.location was not set; the build names it to test and to pitest"))

    private ReaderVocabulary() {}

    /**
     * Read up to the union's semicolon, whatever it is wrapped over: a formatter decides where the
     * lines break, and a pattern anchored to one line reads the whole union one day and part of it
     * the next.
     */
    static List<String> union(String declaredIn, String typeName) {
        def declaration = FRONTEND.resolve(declaredIn)
        assert Files.isRegularFile(declaration): "the build did not point frontend.location at the reader's tree"
        def union = Pattern.compile("export type ${Pattern.quote(typeName)}\\s*=([^;]*);")
                .matcher(Files.readString(declaration))
        assert union.find(): "no ${typeName} union was found in ${declaration}"
        (union.group(1) =~ /"([^"]*)"/).collect { it[1] }
    }
}
