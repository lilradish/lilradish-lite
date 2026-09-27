package org.lilradish.lite.domain.identity

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification

/**
 * The one pairing in this system that spans two build artefacts: a constant compiled into the
 * application has to name a row a migration inserts, and neither file can see the other. Each side
 * carried a comment admitting nothing checked it, and a comment is not a check: editing either side
 * alone would pass every gate on both, and the first thing to notice it would be a foreign key
 * violation at the first act a system actor recorded — against a build that had gone green.
 *
 * <p>Both sides are derived rather than restated — the migrations are parsed, the constants are
 * discovered — so neither can be brought into agreement by editing this file.
 *
 * <p>Every migration, rather than the one that happens to seed these rows today. Named singly, a
 * system actor seeded by some later migration and matched by no constant would be missing from both
 * sides at once and so agree: an unclaimed subject that every act of its recorded against would
 * resolve to nothing. The glob is what makes the population the schema's rather than this file's.
 *
 * <p>The migrations arrive as a directory the build points at, declared as an input of this task so
 * a changed migration runs this again, and deliberately not on the classpath — Spring Boot's Flyway
 * auto-configuration finds {@code classpath:db/migration} without being asked and would run a
 * PostgreSQL baseline into whatever datasource a context test had. A published artefact would not do
 * either: it would carry the migrations of some earlier release, while the divergence this exists to
 * catch is one made within a single commit.
 */
class SeededSubjectIntegrationSpec extends Specification {

    /**
     * No default, because there is no default that fails. An empty string is the empty path, which
     * the runtime resolves to the process working directory for every syscall — so the check below
     * finds a directory, reads whatever {@code V*.sql} happens to be beside the runner and passes,
     * having verified the migrations it was never pointed at. A default that makes an absent input
     * look like a present one is worse than none.
     */
    static final Path MIGRATIONS = Path.of(Objects.requireNonNull(System.getProperty("baseline.location"),
            "baseline.location was not set; the build names it to test and to pitest"))

    static final String SQL = readMigrations()

    /** Discovered, not listed: a constant added without a seeded row must fail here rather than be skipped. */
    static final Set<SubjectId> DECLARED = SystemPrincipal.class.declaredFields
            .findAll { it.type == SystemPrincipal }
            .collect { SystemPrincipal."$it.name".subject() }
            .toSet()

    def "every system actor in code names a subject row the baseline seeds, and the baseline seeds no others"() {
        when:
        def seeded = subjectsSeededAs("system")

        then:
        seeded == DECLARED.collect { it.value().toString() }.toSet()

        and: "neither side is empty, so agreement cannot be two nothings matching"
        !seeded.isEmpty()
        seeded.size() == DECLARED.size()
    }

    /**
     * The other half of what the pairing is for. A system actor charged with the seeder's row would
     * read as seeded, and the seeder given a system actor's row would read as something that acted.
     */
    def "the seeder's row is its own and is named by no actor in code"() {
        given:
        def seeder = subjectsSeededAs("seeder")

        expect:
        seeder.size() == 1

        and:
        !DECLARED.collect { it.value().toString() }.toSet().contains(seeder.first())

        and: "and it is the row every seeded subject is charged to, which is what makes it the seeder"
        subjectsAuthoredBy(seeder.first()).containsAll(subjectsSeededAs("system"))
    }

    private static Set<String> subjectsSeededAs(String kind) {
        (SQL =~ /\(\s*'([0-9a-fA-F-]{36})'\s*,\s*'${kind}'\s*,/).collect { it[1] }.toSet()
    }

    private static Set<String> subjectsAuthoredBy(String author) {
        (SQL =~ /\(\s*'([0-9a-fA-F-]{36})'\s*,\s*'\w+'\s*,\s*'${author}'\s*\)/).collect { it[1] }.toSet()
    }

    private static String readMigrations() {
        assert Files.isDirectory(MIGRATIONS):
                "the build did not point baseline.location at a directory, and got '${MIGRATIONS}'"
        def found = Files.list(MIGRATIONS).withCloseable { paths ->
            paths.filter { it.fileName.toString() ==~ /V.*\.sql/ }.toList().toSorted { it.fileName.toString() }
        }
        assert !found.isEmpty(): "no migration was found under ${MIGRATIONS}"
        found.collect { Files.readString(it) }.join("\n")
    }
}
