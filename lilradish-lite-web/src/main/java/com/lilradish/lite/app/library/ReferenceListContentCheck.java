package com.lilradish.lite.app.library;

import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.registry.ContentPart;
import com.lilradish.lite.domain.registry.ContentPlace;
import com.lilradish.lite.domain.registry.ContentProblemCode;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A reference list is refused at submitting where it holds no term, or holds a term alike to an earlier one
 * whatever the case either is written in, which is each later one named.
 */
@Component
final class ReferenceListContentCheck implements ContentCheck {

    // DB-SPECIFIC: search_fold is the store's PostgreSQL function. A read of the terms marks
    // each alike from this one expression, so the page never judges it apart.
    static final String ALIKE_EARLIER =
            "row_number() over (partition by search_fold(term.term) order by term.position) > 1";

    private static final String TERMS = """
            select term.reference_list_term_id, %s as repeated
              from reference_list_terms term
             where term.entry_version_id = :version
             order by term.position
            """.formatted(ALIKE_EARLIER);

    private final JdbcClient database;

    ReferenceListContentCheck(JdbcClient database) {
        this.database = database;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.REFERENCE_LIST;
    }

    @Override
    public ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version) {
        List<Held> terms = database.sql(TERMS)
                .param("version", version.value())
                .query((result, number) ->
                        new Held(result.getObject("reference_list_term_id", UUID.class), result.getBoolean("repeated")))
                .list();
        if (terms.isEmpty()) {
            ContentProblemReport found = new ContentProblemReport();
            found.add(ContentProblemCode.NO_TERMS, new ContentPlace.Whole(ContentPart.TERMS));
            return found.result();
        }
        ContentProblemReport found = new ContentProblemReport();
        for (Held term : terms) {
            if (term.repeated()) {
                found.add(ContentProblemCode.TERM_REPEATED, new ContentPlace.AtTerm(term.id()));
            }
        }
        return found.result();
    }

    private record Held(UUID id, boolean repeated) {}
}
