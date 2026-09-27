package org.lilradish.lite.app.run.fixture

import java.util.concurrent.CountDownLatch
import org.lilradish.lite.domain.declaration.Field
import org.lilradish.lite.domain.declaration.FieldShape
import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.domain.wire.JsonValue
import org.lilradish.lite.testutil.codestep.ScriptedCodeStep
import org.lilradish.lite.testutil.codestep.SpecCodeStep
import org.lilradish.lite.testutil.library.LibraryStore

/**
 * One group, its people, and a workflow whose first step, {@code send}, runs the code step {@code send_reply} on
 * the ticket the run was started with, giving back a receipt; its second, {@code check}, asks a person whether that
 * receipt looks right, or, where the workflow sends twice, runs the same code again on the receipt. The workflow
 * gives the receipt back.
 */
final class CodeWorkflow {

    static final String CODE_STEP = "send_reply"

    static final String GROUP = "00000003-0000-4000-8000-000000000c01"

    /** An overseer: reads every run, answers a step, reviews at a gate. */
    static final String ANN = "00000002-0000-4000-8000-000000000c01"

    static final UserId ANN_USER = new UserId("000c01")

    /** An operator, who starts the run. */
    static final String CAT = "00000002-0000-4000-8000-000000000c03"

    static final UserId CAT_USER = new UserId("000c03")

    static final String CHECK_QUESTION = "00000006-0000-4000-8000-000000000c01"

    static final String CHECK_VERSION = "00000007-0000-4000-8000-000000000c01"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000c02"

    static final String VERSION = "00000007-0000-4000-8000-000000000c02"

    static final String SEND = "00000009-0000-4000-8000-000000000c01"

    static final String CHECK = "00000009-0000-4000-8000-000000000c02"

    static final String TICKET_IN = "0000000d-0000-4000-8000-000000000c01"

    static final String RECEIPT_IN = "0000000d-0000-4000-8000-000000000c02"

    static final String RUN = "00000008-0000-4000-8000-000000000c01"

    static final String STARTED_WITH = '{"ticket": "The printer is on fire."}'

    static final Field REPLY = SpecCodeStep.given("reply", new FieldShape.Text(2000))

    static final Field RECEIPT = SpecCodeStep.standing("receipt", new FieldShape.Text(64))

    private CodeWorkflow() {}

    /**
     * The code step as a release holding it declares it, taking the reply and giving back the receipt, doing
     * {@code behaviour} each time it runs, after {@code gate} opens where there is one.
     */
    static ScriptedCodeStep scripted(boolean mayRunAgain, Closure<JsonValue.JsonObject> behaviour,
                                     CountDownLatch gate = null) {
        new ScriptedCodeStep(CODE_STEP, [REPLY], [RECEIPT], mayRunAgain, behaviour, gate)
    }

    /** What code gives back that fits: a receipt naming what it sent. */
    static JsonValue.JsonObject receipt(String said = "R-1") {
        new JsonValue.JsonObject([new JsonValue.JsonMember("receipt", new JsonValue.JsonString(said))])
    }

    /**
     * The group, its people and the workflow, {@code tries} declared on send, produced by {@code producer}. The code
     * step's label is added to the store here, as a migration publishing it would.
     */
    static void seed(LibraryStore store, int tries = 1, boolean twice = false, String producer = "code") {
        store.session.sql("alter type code_step add value if not exists '${CODE_STEP}'").update()
        store.person(ANN, "000c01", "Ann")
        store.person(CAT, "000c03", "Cat")
        store.group(GROUP, "SENDING", "Sending")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, CAT, "operator")
        StepRows.workflow(store, WORKFLOW, VERSION, GROUP, "Answer a ticket")
        StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000c01", owner: VERSION, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket", limit: 2000, mustBe: true])
        StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000c02", owner: VERSION, ownerKind: "workflow",
                               side: "gives", position: 1, name: "result", limit: 64, mustBe: true])
        StepRows.codeStep(store, SEND, VERSION, 1, "send", CODE_STEP, producer, tries)
        StepRows.binding(store, TICKET_IN, VERSION, SEND, "reply", null, "ticket")
        if (twice) {
            StepRows.codeStep(store, CHECK, VERSION, 2, "send_again", CODE_STEP, "code", 1)
            StepRows.binding(store, RECEIPT_IN, VERSION, CHECK, "reply", SEND, "receipt")
        } else {
            StepRows.question(store, CHECK_QUESTION, CHECK_VERSION, GROUP, "Check",
                    "Say whether the receipt looks right.")
            StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000c03", owner: CHECK_VERSION,
                                   ownerKind: "question", side: "takes", position: 1, name: "receipt", limit: 64,
                                   mustBe: true])
            StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000c04", owner: CHECK_VERSION,
                                   ownerKind: "question", side: "gives", position: 1, name: "ok", kind: "yes_no",
                                   mustBe: true, standing: "always"])
            StepRows.step(store, CHECK, VERSION, 2, "check", CHECK_VERSION, "person", 1)
            StepRows.binding(store, RECEIPT_IN, VERSION, CHECK, "receipt", SEND, "receipt")
        }
        StepRows.binding(store, "0000000d-0000-4000-8000-000000000c03", VERSION, null, "result", SEND, "receipt")
    }

    /** Cat's run of the workflow, started with a ticket and nothing written about any step. */
    static void run(LibraryStore store) {
        StepRows.run(store, RUN, GROUP, 1, WORKFLOW, VERSION, CAT, STARTED_WITH)
    }
}
