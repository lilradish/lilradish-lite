package org.lilradish.lite.app.library

import org.lilradish.lite.domain.declaration.Declaration
import org.lilradish.lite.domain.declaration.DeclarationSide
import org.lilradish.lite.domain.declaration.Demands
import org.lilradish.lite.domain.registry.EntryKind
import org.lilradish.lite.domain.registry.EntryName
import org.lilradish.lite.domain.registry.EntryVersionId
import org.lilradish.lite.domain.registry.VersionStanding
import org.lilradish.lite.domain.workflow.Binding
import org.lilradish.lite.domain.workflow.BindingSource
import org.lilradish.lite.domain.workflow.Pointer
import org.lilradish.lite.domain.workflow.Producer
import org.lilradish.lite.domain.workflow.StepId
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import spock.lang.Specification

/** What the answer to a read is built from, where what the read gathered does not hold what the version names. */
class WorkflowControllerSpec extends Specification {

    static final EntryVersionId ASKED = new EntryVersionId(UUID.fromString("00000007-0000-4000-8000-000000000f51"))

    static final UUID STEP = UUID.fromString("0000000c-0000-4000-8000-000000000f51")

    static final StoredDeclarations.Half NOTHING_TAKEN = new StoredDeclarations.Half(
            new Declaration(DeclarationSide.TAKES, Demands.ofWorkflow(DeclarationSide.TAKES), []), [])

    static final StoredDeclarations.Half NOTHING_GIVEN = new StoredDeclarations.Half(
            new Declaration(DeclarationSide.GIVES, Demands.ofWorkflow(DeclarationSide.GIVES), []), [])

    static final Workflows.Offers NOTHING_OFFERED = new Workflows.Offers([], [], [], [], [])

    def "a step pinning a version whose declaration the read did not gather is a reader gone wrong"() {
        when:
        WorkflowController.answer(view(workflow([pinnedStep()], []), [:], [(ASKED): pinned()]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A step pins version ${ASKED.value()}, which no reader declared" as String
    }

    def "a step pinning a version the read did not name is a reader gone wrong"() {
        when:
        WorkflowController.answer(view(workflow([pinnedStep()], []),
                [(ASKED): new StoredDeclarations.Halves(NOTHING_TAKEN, NOTHING_GIVEN)], [:]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A step pins version ${ASKED.value()}, which no reader named" as String
    }

    def "a binding reading a step its version does not hold is a store gone wrong"() {
        given:
        def stray = new Binding(UUID.fromString("0000000e-0000-4000-8000-000000000f51"), Pointer.parse("summary"),
                new BindingSource.StepOutput(UUID.fromString("0000000c-0000-4000-8000-0000000000ff"),
                        Pointer.parse("summary")))

        when:
        WorkflowController.answer(view(workflow([], [stray]), [:], [:]))

        then:
        def failed = thrown(IllegalStateException)
        failed.message == "A binding reads a step its version does not hold"
    }

    /** Another group's list is never told, so what the read did not gather of a code step is answered as nothing. */
    def "a code step's step is answered with what the release declares of it only where the read gathered it"() {
        given:
        def sending = StoredDeclarations.ofCodeStep("send_reply", SpecCodeStep.SEND_REPLY.declaration())
        def step = new StoredWorkflow.Step(STEP, new StepId("send"), new StoredWorkflow.Runs.Code("send_reply"),
                new Producer.Code(), 1, null, [])

        when:
        def runs = WorkflowController.answer(new Workflows.WorkflowView(workflow([step], []), [:],
                gathered ? [send_reply: sending] : [:], [:], [:], [], [], NOTHING_OFFERED)).steps()[0].runs()

        then:
        runs.codeStep() == "send_reply"
        runs.takes() == (gathered ? DeclarationAnswers.of(sending.takes(), [:]) : null)
        runs.gives() == (gathered ? DeclarationAnswers.of(sending.gives(), [:]) : null)
        runs.version() == null

        where:
        gathered << [true, false]
    }

    private static StoredWorkflow.Step pinnedStep() {
        new StoredWorkflow.Step(STEP, new StepId("classify"), new StoredWorkflow.Runs.Pinned(EntryKind.QUESTION, ASKED),
                null, null, null, [])
    }

    private static StoredWorkflow workflow(List<StoredWorkflow.Step> steps, List<Binding> outputs) {
        new StoredWorkflow(1, NOTHING_TAKEN, NOTHING_GIVEN, steps, outputs, null, false, false, false, null)
    }

    private static PinnedVersions.PinnedVersion pinned() {
        new PinnedVersions.PinnedVersion(new EntryName("Classify"), ASKED, 1, VersionStanding.IN_SERVICE, null)
    }

    private static Workflows.WorkflowView view(
            StoredWorkflow stored,
            Map<EntryVersionId, StoredDeclarations.Halves> declared,
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins) {
        new Workflows.WorkflowView(stored, declared, [:], pins, [:], [], [], NOTHING_OFFERED)
    }
}
