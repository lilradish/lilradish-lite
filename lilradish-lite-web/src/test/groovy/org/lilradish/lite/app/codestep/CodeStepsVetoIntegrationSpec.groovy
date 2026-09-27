package org.lilradish.lite.app.codestep

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static org.lilradish.lite.testutil.library.LibraryStore.versionId

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.lilradish.lite.domain.declaration.Demand
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldName
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.declaration.HowMany
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.LibraryStore
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * What a release is refused the start over, read off a real server running the real baseline: a code step
 * published that it does not hold, and one it holds declaring a field no version could be submitted with.
 */
class CodeStepsVetoIntegrationSpec extends Specification {

    static final String GROUP = "00000003-0000-4000-8000-000000000e01"

    static final String LIST = "00000006-0000-4000-8000-000000000e01"

    static final String LISTED = "00000007-0000-4000-8000-000000000e01"

    static final int MOST_TERMS = 70_000

    static final Field UNLIMITED = SpecCodeStep.given("note", new FieldShape.Text(null))

    /** One past the longest a value may be written out in, its quotes counted. */
    static final Field PAST_LONGEST = SpecCodeStep.given("note", new FieldShape.Text(8_388_607))

    static final Field UNLIMITED_WITHIN = SpecCodeStep.standing("details",
            new FieldShape.Nested([SpecCodeStep.given("summary", new FieldShape.Text(null))]))

    static final Field STANDING_UNSAID = new Field(new FieldName("receipt"), null, null, new FieldShape.Text(64),
            new HowMany.One(), new Demand.Stands(true, null, null))

    @Shared
    @AutoCleanup
    EmbeddedPostgres server = EmbeddedPostgres.builder().start()

    @Shared
    int databasesMade = 0

    LibraryStore store

    def setupSpec() {
        LibraryStore.template(server)
    }

    def setup() {
        store = LibraryStore.copied(server, "code_step_veto_" + (++databasesMade))
        store.group(GROUP, "SUPPORT", "Customer support")
        ["send_reply", "archive", "close_ticket"].each {
            store.session.sql("alter type code_step add value '${it}'" as String).update()
        }
    }

    def "starts where every code step published, to a group's key or to every group, is one this release holds"() {
        given:
        published.each { name, key -> publish(name, key) }

        when:
        veto([SpecCodeStep.SEND_REPLY]).afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        published << [[:], [send_reply: "SUPPORT"], [send_reply: null], [send_reply: "NOBODY"]]
    }

    /** Every workflow naming it would fail on real work, so the start stops, naming the first such by name. */
    def "refuses to start naming a code step published that this release does not hold, whoever it is published to"() {
        given:
        publish("send_reply", "SUPPORT")
        unheld.each { publish(it, key) }

        when:
        veto([SpecCodeStep.SEND_REPLY]).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Code step ${unheld.first()} is published, and this release does not hold it" as String

        where:
        unheld                      | key
        ["archive"]                 | "SUPPORT"
        ["archive"]                 | null
        ["archive"]                 | "NOBODY"
        ["archive", "close_ticket"] | "SUPPORT"
    }

    /** Not a value past the longest alone: a limit left unsaid bounds nothing, so it is refused as well. */
    def "refuses to start naming the code step, the half and the field no version could be submitted with"() {
        given:
        def codeStep = new SpecCodeStep("archive", takes, gives, false)

        when:
        veto([SpecCodeStep.SEND_REPLY, codeStep]).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Code step archive declares ${expected} as no version could be submitted with: ${code}" as String

        where:
        takes                    | gives                   || expected                      | code
        [UNLIMITED]              | []                      || "takes field note"            | "longest_missing"
        [PAST_LONGEST]           | []                      || "takes field note"            | "limit_past_largest"
        [SpecCodeStep.given("tags", new FieldShape.Term(null))] | [] || "takes field tags"   | "list_missing"
        []                       | [UNLIMITED_WITHIN]      || "gives field details.summary" | "longest_missing"
        []                       | [STANDING_UNSAID]       || "gives field receipt"         | "standing_missing"
    }

    /** A code step is judged before any group asks for it, so its list is read whichever group owns it. */
    def "a term a code step declares is measured at the longest term its list holds in the store"() {
        given:
        listHolding(terms)

        when:
        veto([tagging()]).afterSingletonsInstantiated()

        then:
        def refused = thrown(IllegalStateException)
        refused.message == "Code step archive declares takes field tags as no version could be submitted with: limit_past_largest"

        where:
        terms << [["A", "B" * 120], ["B" * 120]]
    }

    def "a term a code step declares is kept where the longest term its list holds, or none held at all, lets it be"() {
        given:
        listHolding(terms)

        when:
        veto([tagging()]).afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        terms << [["A", "B"], []]
    }

    private CodeStepsVeto veto(List<SpecCodeStep> held) {
        new CodeStepsVeto(new CodeSteps(held), store.session)
    }

    /** A list of the group's in service, holding {@code terms} in order. */
    private void listHolding(List<String> terms) {
        store.entry(LIST, GROUP, "reference_list", "Priorities")
        store.seeded(LISTED, LIST, 1)
        store.content(LISTED, "reference_list")
        terms.eachWithIndex { term, index ->
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, 'When it is.', ?::uuid)
                    """).params(LISTED, index + 1, term, SEEDER).update()
        }
    }

    /** Takes as many terms of that list as fit when each is one letter, and not when each is 120. */
    private static SpecCodeStep tagging() {
        new SpecCodeStep("archive", [new Field(new FieldName("tags"), null, null,
                new FieldShape.Term(versionId(LISTED)), new HowMany.Many(MOST_TERMS), new Demand.Given(true))], [], false)
    }

    /** To a group's key, or to every group where none is named. */
    private void publish(String name, String key) {
        store.session.sql("""
                insert into code_step_publications (code_step, group_key, every_group, created_by)
                values (?::code_step, ?, ?, ?::uuid)
                """).params(name, key, key == null, SEEDER).update()
    }
}
