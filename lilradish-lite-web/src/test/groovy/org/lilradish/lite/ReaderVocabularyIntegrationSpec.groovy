package org.lilradish.lite

import java.nio.file.Files
import org.lilradish.lite.app.library.RunsKind
import org.lilradish.lite.app.library.SendingRole
import org.lilradish.lite.app.run.CameOutNow
import org.lilradish.lite.app.run.FailureReason
import org.lilradish.lite.app.run.StoppedWhat
import org.lilradish.lite.app.run.TryEnded
import org.lilradish.lite.app.run.WhereKind
import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.declaration.FieldKind
import org.lilradish.lite.domain.declaration.FieldStanding
import org.lilradish.lite.domain.filling.FillReason
import org.lilradish.lite.domain.groupregister.GroupSortColumn
import org.lilradish.lite.domain.identity.EstateAct
import org.lilradish.lite.domain.identity.EstateRole
import org.lilradish.lite.domain.identity.GroupPermission
import org.lilradish.lite.domain.identity.GroupRole
import org.lilradish.lite.domain.inference.DidNotFitReason
import org.lilradish.lite.domain.members.MemberSortColumn
import org.lilradish.lite.domain.pool.PoolSortColumn
import org.lilradish.lite.domain.registry.ContentPart
import org.lilradish.lite.domain.registry.ContentProblemCode
import org.lilradish.lite.domain.registry.EntryAct
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.LibrarySortColumn
import org.lilradish.lite.domain.registry.VersionAct
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.domain.run.ReviewSending
import org.lilradish.lite.domain.run.RunAct
import org.lilradish.lite.domain.run.RunSortColumn
import org.lilradish.lite.domain.run.RunState
import org.lilradish.lite.domain.run.RunStepHoldReason
import org.lilradish.lite.domain.run.RunningOn
import org.lilradish.lite.domain.run.StepAct
import org.lilradish.lite.domain.run.StepState
import org.lilradish.lite.domain.run.WaitsOn
import org.lilradish.lite.domain.workflow.StepProducer
import org.lilradish.lite.testutil.ReaderVocabulary
import spock.lang.Specification
import tools.jackson.databind.json.JsonMapper

/**
 * Vocabularies written down twice: once as the spellings this system publishes, once as the names the
 * reader's side declares against them. Nothing in either artefact can see the other — one is compiled
 * by javac and the other by tsc, and neither compiler is handed the other's tree — so a spelling
 * renamed on one side leaves both builds green, both suites green, and the reader meeting a word it
 * has never heard of where the one it knows used to be.
 *
 * <p>The reader's side is read as text, because text is what it is out here: a union type is erased
 * before anything runs, so there is no artefact of it to walk. The published side is discovered, not
 * restated — the constants answer for themselves — so no pairing can be brought into agreement by
 * editing this file.
 *
 * <p>Held total in both directions. A spelling published with nothing declared against it is one the
 * reader can only ever treat as unknown — an act that opens nothing, a role shown as foreign, a column
 * no heading can ask for — and a name declared against no published spelling is one the server will
 * never send, which reads exactly like one withheld.
 */
class ReaderVocabularyIntegrationSpec extends Specification {

    static final String MEMBERS = "api/groups/{groupId}/members.ts"

    static final String LIBRARY = "api/groups/{groupId}/{kind}.ts"

    static final String DECLARATION = "api/declaration.ts"

    static final String FILLING = "api/filling.ts"

    static final String VERSIONS = "api/groups/{groupId}/{kind}/{entryId}/versions.ts"

    static final String VERSION = "api/groups/{groupId}/{kind}/{entryId}/versions/{versionId}.ts"

    static final String RUN = "api/groups/{groupId}/runs/{runId}.ts"

    static final String RUNS = "api/groups/{groupId}/runs.ts"

    static final String STEPS = "api/groups/{groupId}/runs/{runId}/steps.ts"

    static final String STEP = "api/groups/{groupId}/runs/{runId}/steps/{stepId}.ts"

    def "every spelling this system publishes is one the reader declares, and the reader declares no other"() {
        given:
        def declared = ReaderVocabulary.union(declaredIn, typeName)

        expect:
        declared as Set == published as Set

        and: "and neither side is empty, which is how two nothings would satisfy that"
        !declared.isEmpty()
        !published.isEmpty()

        and: "nor does either spell one of them twice, which comparing two sets would have hidden"
        declared.size() == declared.toSet().size()
        declared.size() == published.size()

        where:
        declaredIn           | typeName         || published
        "api/standing.ts"    | "SurfaceAct"     || EstateAct.values()*.published()
        "api/standing.ts"    | "GroupPermission" || GroupPermission.values()*.published()
        "api/pool/people.ts" | "EstateRole"     || EstateRole.values()*.published()
        "api/pool/people.ts" | "PoolSortColumn" || PoolSortColumn.values()*.published()
        "api/groups.ts"      | "GroupSortColumn" || GroupSortColumn.values()*.published()
        MEMBERS              | "GroupRole"       || GroupRole.values()*.published()
        MEMBERS              | "MemberSortColumn" || MemberSortColumn.values()*.published()
        LIBRARY              | "EntryKind"       || EntryKind.values()*.published()
        LIBRARY              | "EntrySegment"    || EntryKind.values()*.segment()
        LIBRARY              | "VersionStanding" || VersionStanding.values()*.published()
        LIBRARY              | "EntryAct"        || EntryAct.values()*.published()
        LIBRARY              | "VersionAct"      || VersionAct.values()*.published()
        LIBRARY              | "LibrarySortColumn" || LibrarySortColumn.values()*.published()
        DECLARATION         | "FieldKind"         || FieldKind.values()*.published()
        DECLARATION          | "FieldStanding"     || FieldStanding.values()*.published()
        DECLARATION          | "DeclarationSide"   || DeclarationSide.values()*.published()
        FILLING              | "FillReason"        || FillReason.values()*.published()
        VERSIONS             | "ContentProblemCode" || ContentProblemCode.values()*.published()
        VERSIONS             | "ContentPart"       || ContentPart.values()*.published()
        VERSION              | "RunsKind"          || RunsKind.values()*.published()
        VERSION              | "ProducerKind"      || StepProducer.values()*.published()
        VERSION              | "SendingRole"       || SendingRole.values()*.published()
        RUN                  | "RunAct"            || RunAct.values()*.published()
        RUN                  | "RunState"          || RunState.values()*.published()
        RUNS                 | "RunSortColumn"     || RunSortColumn.values()*.published()
        STEPS                | "StepState"         || StepState.values()*.published()
        STEPS                | "WaitsOn"           || WaitsOn.values()*.published()
        STEPS                | "StepAct"           || StepAct.values()*.published()
        STEPS                | "WhereKind"         || WhereKind.values()*.published()
        STEPS                | "RunningOn"         || RunningOn.values()*.published()
        STEPS                | "HoldReason"        || RunStepHoldReason.values()*.published()
        STEPS                | "FailureReason"     || FailureReason.values()*.published()
        STEPS                | "StoppedWhat"       || StoppedWhat.values()*.published()
        STEPS                | "ReviewSending"     || ReviewSending.values()*.published()
        STEP                 | "Standing"          || CameOutNow.values()*.published()
        STEP                 | "Ended"             || TryEnded.values()*.published()
        STEP                 | "DidNotFitReason"   || DidNotFitReason.values()*.published()
    }

    /**
     * What the reader was read out of, asserted rather than assumed: a tree that moved leaves the
     * pattern matching nothing, and an emptiness on one side is the one thing a pairing cannot tell
     * from a vocabulary deliberately emptied.
     */
    def "the reader's side was read out of the declaration itself, rather than out of nowhere"() {
        expect:
        Files.readString(ReaderVocabulary.FRONTEND.resolve(declaredIn)).count("export type ${typeName} ") == 1

        where:
        declaredIn           | typeName
        "api/standing.ts"    | "SurfaceAct"
        "api/standing.ts"    | "GroupPermission"
        "api/pool/people.ts" | "EstateRole"
        "api/pool/people.ts" | "PoolSortColumn"
        "api/groups.ts"      | "GroupSortColumn"
        MEMBERS              | "GroupRole"
        MEMBERS              | "MemberSortColumn"
        LIBRARY              | "EntryKind"
        LIBRARY              | "EntrySegment"
        LIBRARY              | "VersionStanding"
        LIBRARY              | "EntryAct"
        LIBRARY              | "VersionAct"
        LIBRARY              | "LibrarySortColumn"
        DECLARATION          | "FieldKind"
        DECLARATION          | "FieldStanding"
        DECLARATION          | "DeclarationSide"
        FILLING              | "FillReason"
        VERSIONS             | "ContentProblemCode"
        VERSIONS             | "ContentPart"
        VERSION              | "RunsKind"
        VERSION              | "ProducerKind"
        VERSION              | "SendingRole"
        RUN                  | "RunAct"
        RUN                  | "RunState"
        RUNS                 | "RunSortColumn"
        STEPS                | "StepState"
        STEPS                | "WaitsOn"
        STEPS                | "StepAct"
        STEPS                | "WhereKind"
        STEPS                | "RunningOn"
        STEPS                | "HoldReason"
        STEPS                | "FailureReason"
        STEPS                | "StoppedWhat"
        STEPS                | "ReviewSending"
        STEP                 | "Standing"
        STEP                 | "Ended"
        STEP                 | "DidNotFitReason"
    }

    /**
     * What each depth of a version's halves asks, which the page builds its fields to as the server reads
     * them: one table both sides read, so a demand moved on either side fails here.
     */
    def "what each depth of a #kind's halves asks is the one table the reader builds its fields to"() {
        given:
        def table = JsonMapper.builder().build().readValue(
                Files.readString(ReaderVocabulary.FRONTEND.resolve("features/library/${file}")), Map)

        expect:
        table == DeclarationSide.values().collectEntries {
            def demands = demandsOf(kind, it)
            [(it.published()): [first: demands.first().name().toLowerCase(Locale.ROOT),
                                below: demands.below().name().toLowerCase(Locale.ROOT)]]
        }

        and: "over a table holding both halves, two nothings agreeing proving nothing"
        table.keySet() == ["takes", "gives"] as Set

        where:
        kind               | file
        EntryKind.QUESTION | "questionDemands.json"
        EntryKind.WORKFLOW | "workflowDemands.json"
    }

    /**
     * The reader says a refused act as the rule of the permission it takes, which it reads off a table of
     * its own. Held whole against each act's own permission, so a permission moved on either side, or an
     * act added to one of them, fails here.
     */
    def "the permission the reader says each act on an entry or a version takes is the one it takes"() {
        given:
        def table = JsonMapper.builder().build().readValue(
                Files.readString(ReaderVocabulary.FRONTEND.resolve("features/library/actPermissions.json")), Map)

        expect:
        table[side] == acts.collectEntries { [(it.published()): it.permission().published()] }

        and: "over tables that hold something, two nothings agreeing proving nothing"
        !acts.isEmpty()

        where:
        side      | acts
        "entry"   | EntryAct.values() as List
        "version" | VersionAct.values() as List
    }

    /** The same for a run, whose one table is held whole against each act's own permission. */
    def "the permission the reader says each act on a run takes is the one it takes"() {
        given:
        def table = JsonMapper.builder().build().readValue(
                Files.readString(ReaderVocabulary.FRONTEND.resolve("features/runs/actPermissions.json")), Map)

        expect:
        table == RunAct.values().collectEntries { [(it.published()): it.permission().published()] }

        and: "over a table that holds something, two nothings agreeing proving nothing"
        !table.isEmpty()
    }

    /** The same for a step, whose one table is held whole against each act's own permission. */
    def "the permission the reader says each act on a step takes is the one it takes"() {
        given:
        def table = JsonMapper.builder().build().readValue(
                Files.readString(ReaderVocabulary.FRONTEND.resolve("features/runs/steps/stepActPermissions.json")), Map)

        expect:
        table == StepAct.values().collectEntries { [(it.published()): it.permission().published()] }

        and: "over a table that holds something, two nothings agreeing proving nothing"
        !table.isEmpty()
    }

    /** The reader names the most one asking may send beside how far past it one runs: one figure both sides read. */
    def "the most one asking may send is the figure the reader says it is"() {
        given:
        def table = JsonMapper.builder().build().readValue(
                Files.readString(ReaderVocabulary.FRONTEND.resolve("features/library/askingLimit.json")), Map)

        expect:
        table.keySet() == ["mostSent"] as Set
        (table.mostSent as long) == Declaration.MOST_SENT
    }

    private static Demands demandsOf(EntryKind kind, DeclarationSide side) {
        kind == EntryKind.QUESTION ? Demands.ofQuestion(side) : Demands.ofWorkflow(side)
    }
}
