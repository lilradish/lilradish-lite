package com.lilradish.lite.app.codestep

import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER
import static com.lilradish.lite.testutil.library.LibraryStore.versionId

import com.lilradish.lite.app.files.FileDeployment
import com.lilradish.lite.domain.declaration.Demand
import com.lilradish.lite.domain.declaration.Field
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldShape
import com.lilradish.lite.domain.declaration.HowMany
import com.lilradish.lite.domain.file.FileExtension
import com.lilradish.lite.testsupport.TestPostgres
import com.lilradish.lite.testutil.codestep.SpecCodeStep
import com.lilradish.lite.testutil.library.LibraryStore
import org.springframework.util.unit.DataSize
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
    TestPostgres server = TestPostgres.worker()

    @Shared
    int databasesMade = 0

    LibraryStore store

    def setupSpec() {
        LibraryStore.prepareBaseline(server)
    }

    def setup() {
        store = LibraryStore.forCase(server, "code_step_veto_" + (++databasesMade), true)
        store.group(GROUP, "SUPPORT", "Customer support")
        ["send_reply", "archive", "close_ticket"].each {
            // DB-SPECIFIC: PostgreSQL enum ADD VALUE.
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
        [fileTaken(null, 100)]   | []                      || "takes field details.attachment" | "file_size_missing"
        [fileTaken(104_857_601L, 100)] | []              || "takes field details.attachment" | "file_limit_past_deployment"
        [fileTaken(104_857_600L, 101)] | []              || "takes field details.attachment" | "file_limit_past_deployment"
    }

    def "refuses a nested file taken beyond the handover total"() {
        given:
        def codeStep = new SpecCodeStep("archive", [fileTaken(104_857_600L, 100)], [], false)
        def before = store.contents()

        when:
        veto([codeStep]).afterSingletonsInstantiated()

        then:
        def refusal = thrown(IllegalStateException)
        refusal.message == "Code step archive declares takes as no version could be submitted with: file_total_past_deployment"
        store.contents() == before
    }

    def "starts when code could take exactly one handover total in nested files"() {
        given:
        def codeStep = new SpecCodeStep("archive", [fileTaken(67_108_864L, 16)], [], false)
        def before = store.contents()

        when:
        veto([codeStep]).afterSingletonsInstantiated()

        then:
        noExceptionThrown()
        store.contents() == before
    }

    def "refuses code whose nested inputs together allow one byte past the handover total"() {
        given:
        def extra = SpecCodeStep.given("extra", new FieldShape.File([new FileExtension(".pdf")], 1L))
        def codeStep = new SpecCodeStep("archive", [fileTaken(67_108_864L, 16), extra], [], false)
        def before = store.contents()

        when:
        veto([codeStep]).afterSingletonsInstantiated()

        then:
        def refusal = thrown(IllegalStateException)
        refusal.message == "Code step archive declares takes as no version could be submitted with: file_total_past_deployment"
        store.contents() == before
    }

    def "starts when code could give exactly one handover total in files"() {
        given:
        def codeStep = new SpecCodeStep("archive", [], [fileGiven("documents", 67_108_864L, 16)], false)

        when:
        veto([codeStep]).afterSingletonsInstantiated()

        then:
        noExceptionThrown()
    }

    def "refuses code whose two output fields together allow one byte past the handover total"() {
        given:
        def codeStep = new SpecCodeStep("archive", [], [fileGiven("documents", 67_108_864L, 16),
                fileGiven("extra", 1L, 1)], false)

        when:
        veto([codeStep]).afterSingletonsInstantiated()

        then:
        def refusal = thrown(IllegalStateException)
        refusal.message == "Code step archive declares gives as no version could be submitted with: file_total_past_deployment"
    }

    def "starts when a file field has sixty-four types and its byte bound fits the configured deployment"() {
        given:
        def types = (0..<64).collect { new FileExtension(".x${it}" as String) }
        def field = side == "takes" ? fileTaken(9_007_199_254_740_991L, 1, types)
                : fileGiven("documents", 9_007_199_254_740_991L, 1, types)
        def codeStep = new SpecCodeStep("archive", side == "takes" ? [field] : [], side == "gives" ? [field] : [], false)
        def deployment = new FileDeployment(DataSize.ofBytes(9_007_199_254_740_991L), 1,
                DataSize.ofBytes(9_007_199_254_740_991L))
        store.session.sql("delete from code_step_publications where code_step = 'read_text_file'").update()

        when:
        new CodeStepsVeto(new CodeSteps([codeStep]), store.session, deployment).afterSingletonsInstantiated()

        then:
        noExceptionThrown()

        where:
        side << ["takes", "gives"]
    }

    def "refuses a code step with a sixty-fifth type or an overbound byte count at its nested field"() {
        given:
        def types = (0..<count).collect { new FileExtension(".x${it}" as String) }
        def field = side == "takes" ? fileTaken(bytes, 1, types) : fileGiven("documents", bytes, 1, types)
        def codeStep = new SpecCodeStep("archive", side == "takes" ? [field] : [], side == "gives" ? [field] : [], false)
        def path = side == "takes" ? "details.attachment" : "documents.attachment"

        when:
        veto([codeStep]).afterSingletonsInstantiated()

        then:
        def refusal = thrown(IllegalStateException)
        refusal.message == "Code step archive declares ${side} field ${path} as no version could be submitted with: ${code}" as String

        where:
        side     | count | bytes                 || code
        "takes"  | 65    | 1L                    || "FILE_TYPES_TOO_MANY"
        "gives"  | 65    | 1L                    || "FILE_TYPES_TOO_MANY"
        "takes"  | 1     | 9_007_199_254_740_992L || "FILE_SIZE_LIMIT_UNUSABLE"
        "gives"  | 1     | 9_007_199_254_740_992L || "FILE_SIZE_LIMIT_UNUSABLE"
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

    private static Field fileTaken(Long maximumBytes, int most,
                                   List<FileExtension> types = [new FileExtension(".pdf")]) {
        def file = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File(types, maximumBytes),
                new HowMany.Many(most), new Demand.Given(true))
        SpecCodeStep.given("details", new FieldShape.Nested([file]))
    }

    private static Field fileGiven(String name, long maximumBytes, int most,
                                   List<FileExtension> types = [new FileExtension(".pdf")]) {
        def file = new Field(new FieldName("attachment"), null, null,
                new FieldShape.File(types, maximumBytes),
                new HowMany.Many(most), new Demand.Given(false))
        SpecCodeStep.standing(name, new FieldShape.Nested([file]))
    }

    private CodeStepsVeto veto(List<SpecCodeStep> held, FileDeployment deployment = LibraryStore.FILES) {
        new CodeStepsVeto(new CodeSteps(held + [new ReadTextFile()]), store.session, deployment)
    }

    /** A list of the group's in service, holding {@code terms} in order. */
    private void listHolding(List<String> terms) {
        store.entry(LIST, GROUP, "reference_list", "Priorities")
        store.seeded(LISTED, LIST, 1)
        store.content(LISTED, "reference_list")
        terms.eachWithIndex { term, index ->
            // DB-SPECIFIC: PostgreSQL :: casts to uuid.
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
        // DB-SPECIFIC: PostgreSQL :: casts to code_step, uuid.
        store.session.sql("""
                insert into code_step_publications (code_step, group_key, every_group, created_by)
                values (?::code_step, ?, ?, ?::uuid)
                """).params(name, key, key == null, SEEDER).update()
    }
}
