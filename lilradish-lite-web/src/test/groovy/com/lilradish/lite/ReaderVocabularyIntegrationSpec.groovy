package com.lilradish.lite

import static com.lilradish.lite.domain.run.fixture.Runs.key
import static com.lilradish.lite.domain.run.fixture.Runs.person
import static com.lilradish.lite.domain.run.fixture.Runs.question
import static com.lilradish.lite.domain.run.fixture.Runs.run
import static com.lilradish.lite.domain.run.fixture.Runs.started

import com.lilradish.lite.app.library.RunsKind
import com.lilradish.lite.app.library.SendingRole
import com.lilradish.lite.app.run.CameOutNow
import com.lilradish.lite.app.run.FailureReason
import com.lilradish.lite.app.run.StoppedWhat
import com.lilradish.lite.app.run.TryEnded
import com.lilradish.lite.app.run.WhereKind
import com.lilradish.lite.domain.codestep.CodeError
import com.lilradish.lite.domain.codestep.CodeErrorReason
import com.lilradish.lite.domain.declaration.Declaration
import com.lilradish.lite.domain.declaration.DeclarationSide
import com.lilradish.lite.domain.declaration.Demands
import com.lilradish.lite.domain.declaration.FieldKind
import com.lilradish.lite.domain.declaration.FieldName
import com.lilradish.lite.domain.declaration.FieldStanding
import com.lilradish.lite.domain.filling.FillReason
import com.lilradish.lite.domain.groupregister.GroupSortColumn
import com.lilradish.lite.domain.identity.EstateAct
import com.lilradish.lite.domain.identity.EstateRole
import com.lilradish.lite.domain.identity.GroupPermission
import com.lilradish.lite.domain.identity.GroupRole
import com.lilradish.lite.domain.inference.DidNotFitReason
import com.lilradish.lite.domain.inference.ModelCallPurpose
import com.lilradish.lite.domain.members.MemberSortColumn
import com.lilradish.lite.domain.pool.PoolSortColumn
import com.lilradish.lite.domain.registry.ContentPart
import com.lilradish.lite.domain.registry.ContentProblemCode
import com.lilradish.lite.domain.registry.EntryAct
import com.lilradish.lite.domain.registry.EntryKind
import com.lilradish.lite.domain.registry.LibrarySortColumn
import com.lilradish.lite.domain.registry.VersionAct
import com.lilradish.lite.domain.registry.VersionStanding
import com.lilradish.lite.domain.run.ProductionValueId
import com.lilradish.lite.domain.run.ReviewSending
import com.lilradish.lite.domain.run.RunAct
import com.lilradish.lite.domain.run.RunSortColumn
import com.lilradish.lite.domain.run.RunState
import com.lilradish.lite.domain.run.RunStepFailureReason
import com.lilradish.lite.domain.run.RunStepHoldReason
import com.lilradish.lite.domain.run.RunningOn
import com.lilradish.lite.domain.run.StepAct
import com.lilradish.lite.domain.run.StepFailure
import com.lilradish.lite.domain.run.StepGround
import com.lilradish.lite.domain.run.StepPosition
import com.lilradish.lite.domain.run.StepState
import com.lilradish.lite.domain.run.WaitsOn
import com.lilradish.lite.domain.workflow.StepProducer
import com.lilradish.lite.testutil.ReaderVocabulary
import java.nio.file.Files
import java.time.Instant
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

    static final String STEP_STATES = "api/stepStates.ts"

    static final String STEP_ACTS = "api/stepActs.ts"

    static final String STEP_WHEREABOUTS = "api/stepWhereabouts.ts"

    static final String STEP_TRIES = "api/stepTries.ts"

    def "every spelling this system publishes is one the reader declares, and the reader declares no other"() {
        given:
        def declared = ReaderVocabulary.union(declaredIn, typeName)

        expect:
        !declared.isEmpty()
        !published.isEmpty()
        declared.size() == published.size()
        declared as Set == published as Set

        and: "nor does either spell one of them twice, which comparing two sets would have hidden"
        declared.size() == declared.toSet().size()

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
        STEP_STATES                | "StepState"         || StepState.values()*.published()
        STEP_WHEREABOUTS                | "WaitsOn"           || WaitsOn.values()*.published()
        STEP_ACTS                | "StepAct"           || StepAct.values()*.published()
        STEP_ACTS                | "StateRefusalCode"  || stateRefusals()
        STEP_WHEREABOUTS                | "WhereKind"         || WhereKind.values()*.published()
        STEP_WHEREABOUTS                | "RunningOn"         || RunningOn.values()*.published()
        STEP_WHEREABOUTS                | "HoldReason"        || RunStepHoldReason.values()*.published()
        STEP_WHEREABOUTS                | "FailureReason"     || FailureReason.values()*.published()
        STEP_WHEREABOUTS                | "StoppedWhat"       || StoppedWhat.values()*.published()
        STEP_WHEREABOUTS                | "ReviewSending"     || ReviewSending.values()*.published()
        STEP_TRIES                 | "Standing"          || CameOutNow.values()*.published()
        STEP_TRIES                 | "Ended"             || TryEnded.values()*.published()
        STEP_TRIES                 | "DidNotFitReason"   || DidNotFitReason.values()*.published()
        STEP_TRIES                 | "ErroredForReason"  || CodeErrorReason.values()*.published()
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
        STEP_STATES                | "StepState"
        STEP_WHEREABOUTS                | "WaitsOn"
        STEP_ACTS                | "StepAct"
        STEP_ACTS                | "StateRefusalCode"
        STEP_WHEREABOUTS                | "WhereKind"
        STEP_WHEREABOUTS                | "RunningOn"
        STEP_WHEREABOUTS                | "HoldReason"
        STEP_WHEREABOUTS                | "FailureReason"
        STEP_WHEREABOUTS                | "StoppedWhat"
        STEP_WHEREABOUTS                | "ReviewSending"
        STEP_TRIES                 | "Standing"
        STEP_TRIES                 | "Ended"
        STEP_TRIES                 | "DidNotFitReason"
        STEP_TRIES                 | "ErroredForReason"
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

    /**
     * The finite choices an act inspects, across every sealed position and each enum spelling. Counts and
     * identities use legal representatives; those not inspected by StepAct do not create another choice.
     * Neither the expected published reasons nor a second refusal algorithm is written down here.
     */
    private static List<String> stateRefusals() {
        def since = Instant.parse("2026-10-07T08:00:00Z")
        def owed = [false, true].collectMany { open ->
            [false, true].collect { beyond -> new StepPosition.Owed(1, open, beyond, since) }
        }
        def step = started(question(1, person(), 1), [])
        def positions = [
            NotStarted: [new StepPosition.NotStarted()],
            Running: RunningOn.values().collect { new StepPosition.Running(it) },
            HeldBack: RunStepHoldReason.values().collectMany { reason ->
                [new StepPosition.HeldBack(reason, since, null)] +
                        (reason == RunStepHoldReason.ENTRY_STOPPED
                        ? owed.collect { new StepPosition.HeldBack(reason, since, it) } : [])
            },
            AwaitingReview: ([null] + ReviewSending.values().toList()).collectMany { sending ->
                WaitsOn.values().collect { on ->
                    new StepPosition.AwaitingReview(1,
                            [new StepPosition.WaitingValue(new ProductionValueId(key(9901)), "receipt", on)],
                            null, since, sending)
                }
            },
            Owed: owed,
            Failed: [new StepPosition.Failed(new StepFailure.TriesSpent(1, 1), since)] +
                    RunStepFailureReason.values().collectMany { reason ->
                        ([null] + ModelCallPurpose.values().toList()).collect { purpose ->
                            new StepPosition.Failed(new StepFailure.Recorded(reason, purpose), since)
                        }
                    },
            Beneath: [new StepPosition.Beneath(run([step]), step, new StepPosition.NotStarted())],
            Done: [new StepPosition.Done()]
        ]
        def source = Files.readString(ReaderVocabulary.FRONTEND.resolveSibling("java")
                .resolve("com/lilradish/lite/domain/run/StepPosition.java"))
        def permitted = source.split("permits", 2)[1].split(/\{/, 2)[0]
        def kinds = (permitted =~ /StepPosition\.(\w+)/).collect { it[1] }.toSet()
        assert !kinds.isEmpty()
        assert positions.values().every { !it.isEmpty() }
        assert kinds.size() == positions.size()
        assert kinds == positions.keySet(): "a new sealed position needs legal act inputs"

        def mismatch = new CodeError.Fault(CodeErrorReason.GIVES_OTHERWISE,
                [new FieldName("receipt")], null, new CodeError.OutputReads([new FieldName("total")]))
        def reasons = new LinkedHashSet<String>()
        positions.values().flatten().each { position ->
            [false, true].each { runStopped ->
                [false, true].each { entryStopped ->
                    ([null] + StepProducer.values().toList()).each { producer ->
                            [false, true].each { codeMayRunAgain ->
                                [null, mismatch].each { codeGivesOtherwise ->
                                    [false, true].each { unreleased ->
                                        def state = new StepGround.State(position, runStopped, entryStopped,
                                                producer, codeMayRunAgain, codeGivesOtherwise,
                                                unreleased)
                                        reasons.addAll(StepAct.stateRefusals(state).values()*.code())
                                    }
                                }
                            }
                    }
                }
            }
        }
        reasons.toList()
    }

    private static Demands demandsOf(EntryKind kind, DeclarationSide side) {
        kind == EntryKind.QUESTION ? Demands.ofQuestion(side) : Demands.ofWorkflow(side)
    }
}
