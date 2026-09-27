package org.lilradish.lite.app.run.fixture

import org.lilradish.lite.domain.identity.UserId
import org.lilradish.lite.testutil.library.LibraryStore

/**
 * One group, its people, and a workflow of two steps a person answers: {@code summarise} takes the ticket the
 * run was started with and gives back a summary that stands only once reviewed; {@code confirm} takes that
 * summary and gives back whether it is right, and a note, both standing as given. The workflow gives the
 * summary back.
 */
final class TicketWorkflow {

    static final String GROUP = "00000003-0000-4000-8000-000000000e01"

    /** An overseer: reads every run, answers a step, reviews at a gate. */
    static final String ANN = "00000002-0000-4000-8000-000000000e01"

    static final UserId ANN_USER = new UserId("000e01")

    /** An overseer as well, so there is a second reviewer. */
    static final String BEN = "00000002-0000-4000-8000-000000000e02"

    static final UserId BEN_USER = new UserId("000e02")

    /** An operator: starts runs, reads their own, answers a step, and reviews nothing. */
    static final String CAT = "00000002-0000-4000-8000-000000000e03"

    static final UserId CAT_USER = new UserId("000e03")

    /** In no role in the group. */
    static final String DAN = "00000002-0000-4000-8000-000000000e04"

    static final UserId DAN_USER = new UserId("000e04")

    static final String SUMMARISE_QUESTION = "00000006-0000-4000-8000-000000000e01"

    static final String SUMMARISE_VERSION = "00000007-0000-4000-8000-000000000e01"

    static final String SUMMARISE_TAKES = "0000000c-0000-4000-8000-000000000e01"

    static final String SUMMARY = "0000000c-0000-4000-8000-000000000e02"

    static final String CONFIRM_QUESTION = "00000006-0000-4000-8000-000000000e02"

    static final String CONFIRM_VERSION = "00000007-0000-4000-8000-000000000e02"

    static final String CONFIRM_TAKES = "0000000c-0000-4000-8000-000000000e03"

    static final String APPROVED = "0000000c-0000-4000-8000-000000000e04"

    static final String NOTE = "0000000c-0000-4000-8000-000000000e05"

    static final String WORKFLOW = "00000006-0000-4000-8000-000000000e03"

    static final String VERSION = "00000007-0000-4000-8000-000000000e03"

    static final String SUMMARISE = "00000009-0000-4000-8000-000000000e01"

    static final String CONFIRM = "00000009-0000-4000-8000-000000000e02"

    static final String TICKET_IN = "0000000d-0000-4000-8000-000000000e01"

    static final String SUMMARY_IN = "0000000d-0000-4000-8000-000000000e02"

    static final String RESULT_OUT = "0000000d-0000-4000-8000-000000000e03"

    static final String RUN = "00000008-0000-4000-8000-000000000e01"

    static final String STARTED_WITH = '{"ticket": "The printer is on fire."}'

    private TicketWorkflow() {}

    /** The group, its people, both questions and the workflow, with {@code summariseTries} tries on summarise. */
    static void seed(LibraryStore store, int summariseTries = 2) {
        store.person(ANN, "000e01", "Ann")
        store.person(BEN, "000e02", "Ben")
        store.person(CAT, "000e03", "Cat")
        store.person(DAN, "000e04", "Dan")
        store.group(GROUP, "TICKETS", "Tickets")
        store.member(GROUP, ANN, "overseer")
        store.member(GROUP, BEN, "overseer")
        store.member(GROUP, CAT, "operator")
        StepRows.question(store, SUMMARISE_QUESTION, SUMMARISE_VERSION, GROUP, "Summarise")
        StepRows.field(store, [id: SUMMARISE_TAKES, owner: SUMMARISE_VERSION, ownerKind: "question", side: "takes",
                               position: 1, name: "text", limit: 4000, mustBe: true])
        StepRows.field(store, [id: SUMMARY, owner: SUMMARISE_VERSION, ownerKind: "question", side: "gives",
                               position: 1, name: "summary", limit: 1000, mustBe: true, standing: "never"])
        StepRows.question(store, CONFIRM_QUESTION, CONFIRM_VERSION, GROUP, "Confirm", "Say whether it is right.")
        StepRows.field(store, [id: CONFIRM_TAKES, owner: CONFIRM_VERSION, ownerKind: "question", side: "takes",
                               position: 1, name: "summary", limit: 1000, mustBe: true])
        StepRows.field(store, [id: APPROVED, owner: CONFIRM_VERSION, ownerKind: "question", side: "gives",
                               position: 1, name: "approved", kind: "yes_no", mustBe: true, standing: "always"])
        StepRows.field(store, [id: NOTE, owner: CONFIRM_VERSION, ownerKind: "question", side: "gives",
                               position: 2, name: "note", limit: 200, mustBe: false, standing: "always"])
        StepRows.workflow(store, WORKFLOW, VERSION, GROUP, "Handle a ticket")
        StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000e06", owner: VERSION, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket", limit: 4000, mustBe: true])
        StepRows.field(store, [id: "0000000c-0000-4000-8000-000000000e07", owner: VERSION, ownerKind: "workflow",
                               side: "gives", position: 1, name: "result", limit: 1000, mustBe: true])
        StepRows.step(store, SUMMARISE, VERSION, 1, "summarise", SUMMARISE_VERSION, "person", summariseTries)
        StepRows.step(store, CONFIRM, VERSION, 2, "confirm", CONFIRM_VERSION, "person", 1)
        StepRows.binding(store, TICKET_IN, VERSION, SUMMARISE, "text", null, "ticket")
        StepRows.binding(store, SUMMARY_IN, VERSION, CONFIRM, "summary", SUMMARISE, "summary")
        StepRows.binding(store, RESULT_OUT, VERSION, null, "result", SUMMARISE, "summary")
    }

    /** Cat's run of the workflow, started with a ticket and nothing written about any step. */
    static void run(LibraryStore store, String run = RUN, int number = 1) {
        StepRows.run(store, run, GROUP, number, WORKFLOW, VERSION, CAT, STARTED_WITH)
    }
}
