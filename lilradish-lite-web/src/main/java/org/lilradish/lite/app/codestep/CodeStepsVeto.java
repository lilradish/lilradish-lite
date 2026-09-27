package org.lilradish.lite.app.codestep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.domain.codestep.CodeStepDeclaration;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldProblem;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.referencelist.TermMeaning;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a release that does not hold a code step published by name, or that holds one declaring a field
 * no version could be submitted with: every step naming it would fail on real work rather than where it is written.
 */
@Component
final class CodeStepsVeto implements SmartInitializingSingleton {

    // DB-SPECIFIC: an enum cast to text is PostgreSQL's.
    private static final String PUBLISHED = """
            select distinct cast(publication.code_step as text) as code_step
              from code_step_publications publication
             order by code_step
            """;

    /* Every group's: a code step is judged as the release holds it, before any group asks for it. */
    // DB-SPECIFIC: array casts and any(…) are PostgreSQL's.
    private static final String TERMS = """
            select term.entry_version_id, term.term, term.meaning
              from reference_list_terms term
             where term.entry_version_id = any(cast(:lists as uuid[]))
             order by term.entry_version_id, term.position
            """;

    private final CodeSteps codeSteps;

    private final JdbcClient database;

    CodeStepsVeto(CodeSteps codeSteps, JdbcClient database) {
        this.codeSteps = codeSteps;
        this.database = database;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String published : database.sql(PUBLISHED).query(String.class).list()) {
            if (codeSteps.declaration(published).isEmpty()) {
                throw new IllegalStateException(
                        "Code step " + published + " is published, and this release does not hold it");
            }
        }
        Map<String, CodeStepDeclaration> declared = codeSteps.declarations();
        Map<EntryVersionId, OfferedTerms> terms = termsOf(declared);
        declared.forEach((name, declaration) -> {
            refuseProblems(name, declaration.takes(), terms);
            refuseProblems(name, declaration.gives(), terms);
        });
    }

    private Map<EntryVersionId, OfferedTerms> termsOf(Map<String, CodeStepDeclaration> declared) {
        Set<EntryVersionId> lists = new LinkedHashSet<>();
        declared.values().forEach(declaration -> lists.addAll(declaration.lists()));
        if (lists.isEmpty()) {
            return Map.of();
        }
        Map<EntryVersionId, List<OfferedTerms.Offered>> read = new HashMap<>();
        database.sql(TERMS)
                .param(
                        "lists",
                        lists.stream().map(list -> list.value().toString()).toArray(String[]::new))
                .query(result -> {
                    read.computeIfAbsent(
                                    new EntryVersionId(result.getObject("entry_version_id", UUID.class)),
                                    ignored -> new ArrayList<>())
                            .add(new OfferedTerms.Offered(
                                    new Term(result.getString("term")), new TermMeaning(result.getString("meaning"))));
                });
        Map<EntryVersionId, OfferedTerms> terms = HashMap.newHashMap(read.size());
        read.forEach((list, offered) -> terms.put(list, new OfferedTerms(offered, null)));
        return terms;
    }

    /* Every problem submitting refuses, not a value past the longest alone: a limit left unsaid bounds nothing, so
    no value it lets through could be kept either. */
    private static void refuseProblems(String name, Declaration half, Map<EntryVersionId, OfferedTerms> terms) {
        List<FieldProblem> problems = half.problems(terms);
        if (!problems.isEmpty()) {
            FieldProblem first = problems.getFirst();
            throw new IllegalStateException(
                    "Code step " + name + " declares " + half.side().published()
                            + " field " + pathOf(half.fields(), first.at()) + " as no version could be submitted with: "
                            + first.code().published());
        }
    }

    private static String pathOf(List<Field> level, List<Integer> at) {
        StringBuilder path = new StringBuilder();
        List<Field> here = level;
        for (int index : at) {
            Field field = here.get(index);
            path.append(path.isEmpty() ? "" : ".").append(field.name().value());
            here = field.shape() instanceof FieldShape.Nested nested ? nested.fields() : List.of();
        }
        return path.toString();
    }
}
