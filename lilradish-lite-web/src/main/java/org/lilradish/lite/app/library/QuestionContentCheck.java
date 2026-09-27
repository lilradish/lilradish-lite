package org.lilradish.lite.app.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.FieldProblem;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.inference.SendMeasure;
import org.lilradish.lite.domain.registry.ContentPart;
import org.lilradish.lite.domain.registry.ContentPlace;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.ContentProblemCode;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A question is refused at submitting where it tells whoever answers it nothing, gives nothing back, or holds
 * a field either half would refuse: its instruction first, then what it takes, then what it gives back. Only
 * a question refused for none of those is measured, and refused where one asking of it could send too much,
 * counted as though the step told each asking what happened before it, which a question alone cannot rule out.
 */
@Component
final class QuestionContentCheck implements ContentCheck {

    private final JdbcClient database;

    QuestionContentCheck(JdbcClient database) {
        this.database = database;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.QUESTION;
    }

    @Override
    public List<ContentProblem> problemsIn(GroupId group, EntryVersionId version) {
        StoredQuestion stored = StoredQuestion.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Question version " + version.value() + " holds no question content"));
        Map<EntryVersionId, OfferedTerms> offered = stored.offered(database, group, version);
        List<ContentProblem> problems = problemsOf(stored, offered);
        if (!problems.isEmpty()) {
            return problems;
        }
        long past = SendMeasure.mostSent(stored.told(version, offered), true) - Declaration.MOST_SENT;
        return past > 0
                ? List.of(new ContentProblem(
                        ContentProblemCode.ASKING_PAST_LARGEST, new ContentPlace.Whole(ContentPart.ASKING), past))
                : List.of();
    }

    /** The same judgement of a question already read, by what each list it pins offers, short of measuring it. */
    static List<ContentProblem> problemsOf(StoredQuestion stored, Map<EntryVersionId, OfferedTerms> terms) {
        List<ContentProblem> problems = new ArrayList<>();
        if (stored.instruction() == null) {
            problems.add(new ContentProblem(
                    ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION), null));
        }
        placed(stored.half(DeclarationSide.TAKES), ContentPart.TAKES, terms, problems);
        if (stored.gives().declaration().fields().isEmpty()) {
            problems.add(new ContentProblem(
                    ContentProblemCode.NOTHING_GIVEN_BACK, new ContentPlace.Whole(ContentPart.GIVES), null));
        }
        placed(stored.half(DeclarationSide.GIVES), ContentPart.GIVES, terms, problems);
        return List.copyOf(problems);
    }

    private static void placed(
            StoredDeclarations.Half half,
            ContentPart part,
            Map<EntryVersionId, OfferedTerms> terms,
            List<ContentProblem> problems) {
        for (FieldProblem problem : half.declaration().problems(terms)) {
            problems.add(
                    new ContentProblem(problem.code(), new ContentPlace.AtField(part, half.keyAt(problem.at())), null));
        }
    }
}
