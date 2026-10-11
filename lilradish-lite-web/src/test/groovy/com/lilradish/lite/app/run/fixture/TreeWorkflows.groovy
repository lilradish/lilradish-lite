package com.lilradish.lite.app.run.fixture

import static com.lilradish.lite.testutil.library.LibraryStore.SEEDER

import com.lilradish.lite.app.run.Deployments
import com.lilradish.lite.app.run.EngineWiring
import com.lilradish.lite.domain.inference.ModelCalls
import com.lilradish.lite.testutil.codestep.CodeStepsHeld
import com.lilradish.lite.testutil.inference.ScriptedModelCalls
import com.lilradish.lite.testutil.library.DeployedModels
import com.lilradish.lite.testutil.library.LibraryStore

final class TreeWorkflows {

    static final String GROUP = "00000003-0000-4000-8000-000000003701"

    static final String CAT = "00000002-0000-4000-8000-000000003701"

    static final String NOTE_QUESTION = "00000006-0000-4000-8000-000000003701"
    static final String NOTE_QUESTION_VERSION = "00000007-0000-4000-8000-000000003701"
    static final String NOTE_TAKES = "0000000c-0000-4000-8000-000000003701"
    static final String NOTE_GIVES = "0000000c-0000-4000-8000-000000003702"

    static final String CHECK_QUESTION = "00000006-0000-4000-8000-000000003702"
    static final String CHECK_QUESTION_VERSION = "00000007-0000-4000-8000-000000003702"
    static final String CHECK_TAKES = "0000000c-0000-4000-8000-000000003703"
    static final String CHECK_GIVES = "0000000c-0000-4000-8000-000000003704"

    static final String ASK_QUESTION = "00000006-0000-4000-8000-000000003703"
    static final String ASK_QUESTION_VERSION = "00000007-0000-4000-8000-000000003703"
    static final String ASK_GIVES = "0000000c-0000-4000-8000-000000003705"

    static final String NOTE = "00000006-0000-4000-8000-000000003711"
    static final String NOTE_VERSION = "00000007-0000-4000-8000-000000003711"
    static final String WRITE_NOTE = "00000009-0000-4000-8000-000000003711"

    static final String ESCALATE = "00000006-0000-4000-8000-000000003712"
    static final String ESCALATE_VERSION = "00000007-0000-4000-8000-000000003712"
    static final String NOTED = "00000009-0000-4000-8000-000000003712"
    static final String CHECK = "00000009-0000-4000-8000-000000003713"

    static final String SILENT = "00000006-0000-4000-8000-000000003713"
    static final String SILENT_VERSION = "00000007-0000-4000-8000-000000003713"
    static final String SILENT_ASK = "00000009-0000-4000-8000-000000003714"

    static final String QUIET = "00000006-0000-4000-8000-000000003714"
    static final String QUIET_VERSION = "00000007-0000-4000-8000-000000003714"
    static final String QUIETED = "00000009-0000-4000-8000-000000003715"
    static final String AFTER_QUIET = "00000009-0000-4000-8000-000000003716"

    static final String LOOP = "00000006-0000-4000-8000-000000003715"
    static final String LOOP_VERSION = "00000007-0000-4000-8000-000000003715"
    static final String LOOP_STEP = "00000009-0000-4000-8000-000000003717"

    static final String BACK = "00000006-0000-4000-8000-000000003716"
    static final String BACK_VERSION = "00000007-0000-4000-8000-000000003716"
    static final String BACK_STEP = "00000009-0000-4000-8000-000000003718"

    static final String TERMS = "00000006-0000-4000-8000-000000003717"
    static final String TERMS_VERSION = "00000007-0000-4000-8000-000000003717"

    static final String GOING = "00000006-0000-4000-8000-000000003718"
    static final String GOING_VERSION = "00000007-0000-4000-8000-000000003718"
    static final String GOING_ASK = "00000009-0000-4000-8000-000000003719"

    static final String FALLING_BACK = "00000006-0000-4000-8000-000000003719"
    static final String FALLING_BACK_VERSION = "00000007-0000-4000-8000-000000003719"
    static final String FALLING_BACK_ASK = "00000009-0000-4000-8000-00000000371a"

    static final String ROUTED = "00000006-0000-4000-8000-00000000371a"
    static final String ROUTED_VERSION = "00000007-0000-4000-8000-00000000371a"
    static final String ROUTE = "00000009-0000-4000-8000-00000000371b"
    static final String GO_CASE = "0000000e-0000-4000-8000-000000003701"
    static final String FALLBACK_CASE = "0000000e-0000-4000-8000-000000003702"

    static final String COSTLY = "00000006-0000-4000-8000-000000003721"
    static final String COSTLY_VERSION = "00000007-0000-4000-8000-000000003721"
    static final String COSTLY_FIRST = "00000009-0000-4000-8000-000000003721"
    static final String COSTLY_SECOND = "00000009-0000-4000-8000-000000003722"

    static final String SPENDING = "00000006-0000-4000-8000-000000003722"
    static final String SPENDING_VERSION = "00000007-0000-4000-8000-000000003722"
    static final String SPENT = "00000009-0000-4000-8000-000000003723"

    static final String ROOT = "00000008-0000-4000-8000-000000003701"

    static final String NOTED_TICKET = '{"ticket":"The printer is on fire."}'

    private TreeWorkflows() {}

    static void seed(LibraryStore store, boolean withFallback = true) {
        store.person(CAT, "003701", "Cat")
        store.group(GROUP, "TREES", "Trees")
        store.member(GROUP, CAT, "operator")
        questions(store)
        note(store)
        escalate(store)
        quiet(store)
        loop(store)
        routed(store, withFallback)
    }

    static void spending(LibraryStore store, long top, long beneath) {
        StepRows.workflow(store, COSTLY, COSTLY_VERSION, GROUP, "Ask a model twice")
        StepRows.step(store, COSTLY_FIRST, COSTLY_VERSION, 1, "first", ASK_QUESTION_VERSION, "model", 1)
        StepRows.step(store, COSTLY_SECOND, COSTLY_VERSION, 2, "second", ASK_QUESTION_VERSION, "model", 1)
        StepRows.workflow(store, SPENDING, SPENDING_VERSION, GROUP, "Spend beneath")
        store.step(SPENT, SPENDING_VERSION, 1, COSTLY_VERSION, "workflow")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_versions set ceiling = ?, keeps_own_ceiling = true where entry_version_id = ?::uuid")
                .params(beneath, COSTLY_VERSION).update()
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("update workflow_versions set ceiling = ? where entry_version_id = ?::uuid")
                .params(top, SPENDING_VERSION).update()
    }

    static void run(LibraryStore store, String entry, String version, String startedWith) {
        StepRows.run(store, ROOT, GROUP, 1, entry, version, CAT, startedWith)
    }

    static EngineWiring wired(LibraryStore store, int maximumDepth, ModelCalls modelCalls = new ScriptedModelCalls()) {
        new EngineWiring(store, modelCalls, CodeStepsHeld.NONE, DeployedModels.HELD, CodeStepsHeld.NONE,
                Deployments.deep(maximumDepth))
    }

    private static void questions(LibraryStore store) {
        StepRows.question(store, NOTE_QUESTION, NOTE_QUESTION_VERSION, GROUP, "Write a note")
        StepRows.field(store, [id: NOTE_TAKES, owner: NOTE_QUESTION_VERSION, ownerKind: "question", side: "takes",
                               position: 1, name: "text", limit: 4000, mustBe: true])
        StepRows.field(store, [id: NOTE_GIVES, owner: NOTE_QUESTION_VERSION, ownerKind: "question", side: "gives",
                               position: 1, name: "note", limit: 1000, mustBe: true, standing: "always"])
        StepRows.question(store, CHECK_QUESTION, CHECK_QUESTION_VERSION, GROUP, "Check a note")
        StepRows.field(store, [id: CHECK_TAKES, owner: CHECK_QUESTION_VERSION, ownerKind: "question", side: "takes",
                               position: 1, name: "text", limit: 1000, mustBe: true])
        StepRows.field(store, [id: CHECK_GIVES, owner: CHECK_QUESTION_VERSION, ownerKind: "question", side: "gives",
                               position: 1, name: "fine", kind: "yes_no", mustBe: true, standing: "always"])
        StepRows.question(store, ASK_QUESTION, ASK_QUESTION_VERSION, GROUP, "Ask somebody")
        StepRows.field(store, [id: ASK_GIVES, owner: ASK_QUESTION_VERSION, ownerKind: "question", side: "gives",
                               position: 1, name: "answer", limit: 1000, mustBe: true, standing: "always"])
    }

    private static void note(LibraryStore store) {
        StepRows.workflow(store, NOTE, NOTE_VERSION, GROUP, "Note a ticket")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: NOTE_VERSION, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket", limit: 4000, mustBe: true])
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: NOTE_VERSION, ownerKind: "workflow",
                               side: "gives", position: 1, name: "note", limit: 1000, mustBe: true])
        StepRows.step(store, WRITE_NOTE, NOTE_VERSION, 1, "write_note", NOTE_QUESTION_VERSION, "person", 2)
        StepRows.binding(store, UUID.randomUUID().toString(), NOTE_VERSION, WRITE_NOTE, "text", null, "ticket")
        StepRows.binding(store, UUID.randomUUID().toString(), NOTE_VERSION, null, "note", WRITE_NOTE, "note")
    }

    private static void escalate(LibraryStore store) {
        StepRows.workflow(store, ESCALATE, ESCALATE_VERSION, GROUP, "Escalate a ticket")
        StepRows.field(store, [id: UUID.randomUUID().toString(), owner: ESCALATE_VERSION, ownerKind: "workflow",
                               side: "takes", position: 1, name: "ticket", limit: 4000, mustBe: true])
        store.step(NOTED, ESCALATE_VERSION, 1, NOTE_VERSION, "workflow")
        StepRows.step(store, CHECK, ESCALATE_VERSION, 2, "check", CHECK_QUESTION_VERSION, "person", 1)
        StepRows.binding(store, UUID.randomUUID().toString(), ESCALATE_VERSION, NOTED, "ticket", null, "ticket")
        StepRows.binding(store, UUID.randomUUID().toString(), ESCALATE_VERSION, CHECK, "text", NOTED, "note")
    }

    private static void quiet(LibraryStore store) {
        StepRows.workflow(store, SILENT, SILENT_VERSION, GROUP, "Ask and give nothing")
        StepRows.step(store, SILENT_ASK, SILENT_VERSION, 1, "ask", ASK_QUESTION_VERSION, "person", 1)
        StepRows.workflow(store, QUIET, QUIET_VERSION, GROUP, "Run something quiet")
        store.step(QUIETED, QUIET_VERSION, 1, SILENT_VERSION, "workflow")
        StepRows.step(store, AFTER_QUIET, QUIET_VERSION, 2, "after", ASK_QUESTION_VERSION, "person", 1)
    }

    private static void loop(LibraryStore store) {
        StepRows.workflow(store, LOOP, LOOP_VERSION, GROUP, "Go down")
        StepRows.workflow(store, BACK, BACK_VERSION, GROUP, "Go down again")
        store.step(LOOP_STEP, LOOP_VERSION, 1, BACK_VERSION, "workflow")
        store.step(BACK_STEP, BACK_VERSION, 1, LOOP_VERSION, "workflow")
    }

    private static void routed(LibraryStore store, boolean withFallback) {
        store.entry(TERMS, GROUP, "reference_list", "Demo Sample terms")
        store.seeded(TERMS_VERSION, TERMS, 1)
        store.content(TERMS_VERSION, "reference_list")
        ["go", "wait"].eachWithIndex { term, index ->
            // DB-SPECIFIC: PostgreSQL ::uuid casts.
            store.session.sql("""
                    insert into reference_list_terms (entry_version_id, position, term, meaning, created_by)
                    values (?::uuid, ?, ?, 'Demo Sample.', ?::uuid)
                    """).params(TERMS_VERSION, index + 1, term, SEEDER).update()
        }
        StepRows.workflow(store, GOING, GOING_VERSION, GROUP, "Go on")
        StepRows.step(store, GOING_ASK, GOING_VERSION, 1, "ask", ASK_QUESTION_VERSION, "person", 1)
        StepRows.workflow(store, FALLING_BACK, FALLING_BACK_VERSION, GROUP, "Fall back")
        StepRows.step(store, FALLING_BACK_ASK, FALLING_BACK_VERSION, 1, "ask", ASK_QUESTION_VERSION, "person", 1)
        StepRows.workflow(store, ROUTED, ROUTED_VERSION, GROUP, "Route a category")
        store.termField(UUID.randomUUID().toString(), ROUTED_VERSION, "workflow", TERMS_VERSION, "takes")
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, created_by)
                values (?::uuid, ?::uuid, 1, 'route', 'route', ?::uuid)
                """).params(ROUTE, ROUTED_VERSION, SEEDER).update()
        routeCase(store, GO_CASE, "go", GOING_VERSION)
        if (withFallback) {
            routeCase(store, FALLBACK_CASE, null, FALLING_BACK_VERSION)
        }
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into bindings (binding_id, entry_version_id, workflow_step_id, step_kind, source_path, created_by)
                values (?::uuid, ?::uuid, ?::uuid, 'route', 'category', ?::uuid)
                """).params(UUID.randomUUID().toString(), ROUTED_VERSION, ROUTE, SEEDER).update()
    }

    private static void routeCase(LibraryStore store, String routeCase, String term, String target) {
        // DB-SPECIFIC: PostgreSQL ::uuid casts.
        store.session.sql("""
                insert into route_cases (route_case_id, entry_version_id, workflow_step_id, term, target_version_id,
                                         created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?::uuid)
                """).params(routeCase, ROUTED_VERSION, ROUTE, term, target, SEEDER).update()
    }
}
