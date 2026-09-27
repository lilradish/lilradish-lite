package org.lilradish.lite.app.run.fixture

import static org.lilradish.lite.testutil.library.LibraryStore.SEEDER

import org.lilradish.lite.testutil.library.LibraryStore
import org.lilradish.lite.testutil.run.RunRows

/**
 * A run's version written straight into a store as a migration would have left it in service: the questions its
 * steps pin with what each takes and gives back, its steps in order with what fills each input, what it gives
 * back, and runs of it started with what was filled.
 */
final class StepRows {

    private StepRows() {}

    /** A question in service, telling whoever answers it {@code instruction}. */
    static void question(LibraryStore store, String entry, String version, String group, String name,
                         String instruction = "Say what the ticket is about.") {
        store.entry(entry, group, "question", name)
        store.seeded(version, entry, 1)
        store.content(version, "question", instruction)
    }

    /** A workflow in service, with no ceiling. */
    static void workflow(LibraryStore store, String entry, String version, String group, String name) {
        RunRows.workflow(store, entry, version, group, name, null, false)
    }

    /**
     * A field of a version's declaration, named as {@code declared} names it: {@code id}, {@code owner} and
     * {@code ownerKind}, {@code side}, {@code position} and {@code name}, and optionally {@code kind} (text by
     * default), {@code limit}, {@code mustBe}, {@code standing}, {@code floor}, {@code parent} and {@code most}.
     */
    static void field(LibraryStore store, Map declared) {
        def kind = declared.kind ?: "text"
        store.session.sql("""
                insert into declaration_fields (declaration_field_id, entry_version_id, entry_kind, side,
                                                parent_field_id, position, name, kind, holds_many, text_limit,
                                                many_limit, must_be_given, standing, standing_threshold, created_by)
                values (?::uuid, ?::uuid, ?::entry_kind, ?::declaration_side, ?::uuid, ?, ?, ?::field_kind, ?,
                        ?, ?, ?, ?::field_standing, ?, ?::uuid)
                """).params(declared.id, declared.owner, declared.ownerKind, declared.side, declared.parent,
                declared.position, declared.name, kind, declared.most != null,
                kind == "text" ? (declared.limit ?: 1000) : null, declared.most, declared.mustBe ?: false,
                declared.standing, declared.floor, SEEDER).update()
    }

    /**
     * A step placed before any other of the version, asking somebody a question of its own that takes nothing
     * and gives back one text standing as given, so that a run of it has a step not yet done; the key of that
     * text's field is answered.
     */
    static String askingSomebody(LibraryStore store, String version, String group,
                                 String asking = UUID.randomUUID().toString()) {
        def entry = UUID.randomUUID().toString()
        def asked = UUID.randomUUID().toString()
        def answer = UUID.randomUUID().toString()
        question(store, entry, asked, group, "Question " + entry.substring(0, 8))
        field(store, [id: answer, owner: asked, ownerKind: "question", side: "gives", position: 1, name: "answer",
                      mustBe: true, standing: "always"])
        step(store, asking, version, 0, "ask", asked, "person", 1)
        answer
    }

    /** A step of a workflow version asking the question version {@code pinned} of {@code producer}. */
    static void step(LibraryStore store, String step, String version, int position, String name, String pinned,
                     String producer, int tries, String reviewerModel = null) {
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind,
                                            pinned_version_id, pinned_kind, producer, producer_model, producer_mode,
                                            tries, reviewer_model, reviewer_mode, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'entry', ?::uuid, 'question', ?::step_producer, ?, ?, ?, ?, ?,
                        ?::uuid)
                """).params(step, version, position, name, pinned, producer,
                producer == "model" ? "general" : null, producer == "model" ? "ordinary" : null, tries,
                reviewerModel, reviewerModel == null ? null : "ordinary", SEEDER).update()
    }

    /** A step of a workflow version running the code step {@code codeStep}, produced by {@code producer}. */
    static void codeStep(LibraryStore store, String step, String version, int position, String name, String codeStep,
                         String producer, int tries) {
        store.session.sql("""
                insert into workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step,
                                            producer, tries, created_by)
                values (?::uuid, ?::uuid, ?, ?, 'code_step', cast(? as code_step), ?::step_producer, ?, ?::uuid)
                """).params(step, version, position, name, codeStep, producer, tries, SEEDER).update()
    }

    /**
     * What fills {@code target} of {@code step}, or what the version gives back where {@code step} is none: a
     * path into an earlier step where {@code sourceStep} is one, into what the run takes where it is none, or a
     * constant written as JSON where {@code constant} is one.
     */
    static void binding(LibraryStore store, String binding, String version, String step, String target,
                        String sourceStep, String sourcePath, String constant = null) {
        store.session.sql("""
                insert into bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_step_id,
                                      source_path, constant, created_by)
                values (?::uuid, ?::uuid, ?::uuid, ?, ?::uuid, ?, cast(? as jsonb), ?::uuid)
                """).params(binding, version, step, target, sourceStep, constant == null ? sourcePath : null,
                constant, SEEDER).update()
    }

    /**
     * The open try of {@code step} answered by the person {@code by}, saying {@code why}, each value given as
     * JSON by its field's key, none as null. Written in one transaction under the tree's lock, so a run going on
     * by itself meanwhile reads it before or after, never half written.
     */
    static void answered(LibraryStore store, String step, String by, String why, Map<String, String> values) {
        store.transactions().executeWithoutResult {
            def open = store.texts("""
                    select try.production_id::text from productions try
                      join run_steps held on held.run_step_id = try.run_step_id
                     where held.workflow_step_id = ?::uuid and try.ended_at is null
                    """, step)
            assert open.size() == 1: "step ${step} has no open try to answer"
            store.session.sql("""
                    select 1 from runs run
                     where run.run_id = (select try.root_run_id from productions try where try.production_id = ?::uuid)
                       for no key update
                    """).params(open[0]).query(Integer).single()
            store.session.sql("""
                    update productions set ended_at = now(), ended_by = ?::uuid, ended_by_kind = 'person',
                                           explanation = ?
                     where production_id = ?::uuid
                    """).params(by, why, open[0]).update()
            values.each { field, value ->
                store.session.sql("""
                        insert into production_values (production_id, run_step_kind, producer, pinned_version_id,
                                                       declaration_field_id, field_standing, standing_threshold,
                                                       value)
                        select try.production_id, try.run_step_kind, try.producer, try.pinned_version_id,
                               field.declaration_field_id, field.standing, field.standing_threshold, cast(? as jsonb)
                          from productions try
                          join declaration_fields field on field.declaration_field_id = ?::uuid
                         where try.production_id = ?::uuid
                        """).params(value, field, open[0]).update()
            }
        }
    }

    /** A run at the top, started by {@code starter} with {@code startedWith}, as JSON. */
    static void run(LibraryStore store, String run, String group, int number, String entry, String version,
                    String starter, String startedWith) {
        store.session.sql("""
                insert into runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth,
                                  started_with, created_by)
                values (?::uuid, ?::uuid, ?, 'A run', ?::uuid, ?::uuid, ?::uuid, 0, cast(? as jsonb), ?::uuid)
                """).params(run, group, number, entry, version, run, startedWith, starter).update()
    }
}
