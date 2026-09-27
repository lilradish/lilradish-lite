package org.lilradish.lite.app.run;

/** When something last happened to the steps of a tree of runs, worked out as it is read and stored nowhere. */
final class StepHappenings {

    private StepHappenings() {}

    /**
     * The latest moment a step of the tree whose root {@code root}, a column or a parameter, names had a try asked
     * or ended, a review, a failure written, or a hold written or released; SQL null where none has.
     */
    // DB-SPECIFIC: greatest passing over nulls is PostgreSQL's.
    static String lastAt(String root) {
        return """
                greatest(
                    (select max(greatest(try.created_at, try.ended_at))
                       from runs member
                       join productions try on try.run_id = member.run_id
                      where member.root_run_id = %1$s),
                    (select max(review.created_at)
                       from runs member
                       join productions try on try.run_id = member.run_id
                       join reviews review on review.production_id = try.production_id
                      where member.root_run_id = %1$s),
                    (select max(failure.created_at)
                       from runs member
                       join run_step_failures failure on failure.run_id = member.run_id
                      where member.root_run_id = %1$s),
                    (select max(greatest(hold.created_at, hold.released_at))
                       from runs member
                       join run_step_holds hold on hold.run_id = member.run_id
                      where member.root_run_id = %1$s))""".formatted(root);
    }
}
