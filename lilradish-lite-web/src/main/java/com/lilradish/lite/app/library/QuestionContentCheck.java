package com.lilradish.lite.app.library;

import com.lilradish.lite.app.files.FileDeployment;
import com.lilradish.lite.app.search.SearchWays;
import com.lilradish.lite.domain.declaration.Declaration;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.FieldProblem;
import com.lilradish.lite.domain.declaration.InputReferences;
import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.inference.SendMeasure;
import com.lilradish.lite.domain.registry.ContentPart;
import com.lilradish.lite.domain.registry.ContentPlace;
import com.lilradish.lite.domain.registry.ContentProblem;
import com.lilradish.lite.domain.registry.ContentProblemCode;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.search.QuestionSearchShape;
import com.lilradish.lite.domain.search.SearchWay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Checks a question's instruction, drawing, declarations and largest possible asking at submission. */
@Component
final class QuestionContentCheck implements ContentCheck {

    private final JdbcClient database;

    private final FileDeployment deployment;

    private final SearchWays searchWays;

    QuestionContentCheck(JdbcClient database, FileDeployment deployment, SearchWays searchWays) {
        this.database = database;
        this.deployment = deployment;
        this.searchWays = searchWays;
    }

    @Override
    public EntryKind kind() {
        return EntryKind.QUESTION;
    }

    @Override
    public ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version) {
        StoredQuestion stored = StoredQuestion.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Question version " + version.value() + " holds no question content"));
        Map<EntryVersionId, OfferedTerms> offered = stored.offered(database, group, version);
        ContentProblemReport found = new ContentProblemReport();
        long maximumBytes = deployment.maximumSize().toBytes();
        int maximumPerField = deployment.maximumPerField();
        long maximumTotalBytes = deployment.maximumTotalSize().toBytes();
        ContentProblem.FileLimits fileLimits =
                new ContentProblem.FileLimits(maximumBytes, maximumPerField, maximumTotalBytes);
        collect(
                stored,
                offered,
                maximumBytes,
                maximumPerField,
                maximumTotalBytes,
                (code, place) -> found.add(
                        code,
                        place,
                        null,
                        code == ContentProblemCode.FILE_LIMIT_PAST_DEPLOYMENT
                                        || code == ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT
                                ? fileLimits
                                : null));
        if (stored.searchWay() != null) {
            SearchWay way = searchWays.find(stored.searchWay()).orElse(null);
            if (way == null) {
                found.add(ContentProblemCode.SEARCH_WAY_NOT_HELD, new ContentPlace.Whole(ContentPart.GIVES), null);
            } else if (!QuestionSearchShape.matches(
                    way, stored.gives().declaration().fields())) {
                found.add(
                        ContentProblemCode.SEARCH_QUESTION_NO_LONGER_MATCHES,
                        new ContentPlace.Whole(ContentPart.GIVES),
                        null);
            }
        }
        ContentProblemReport.Result result = found.result();
        if (result.total() > 0) {
            return result;
        }
        long past = SendMeasure.mostSent(stored.told(version, offered), true) - Declaration.MOST_SENT;
        if (past > 0) {
            found.add(ContentProblemCode.ASKING_PAST_LARGEST, new ContentPlace.Whole(ContentPart.ASKING), past);
        }
        return found.result();
    }

    /** Structure and pinned-list problems of an already-read question, without current deployment file limits or measuring. */
    static List<ContentProblem> problemsOf(StoredQuestion stored, Map<EntryVersionId, OfferedTerms> terms) {
        List<ContentProblem> problems = new ArrayList<>();
        collect(
                stored,
                terms,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                null,
                (code, place) -> problems.add(new ContentProblem(code, place, null, null)));
        return List.copyOf(problems);
    }

    private static void collect(
            StoredQuestion stored,
            Map<EntryVersionId, OfferedTerms> terms,
            long maximumBytes,
            int maximumPerField,
            @Nullable Long maximumTotalBytes,
            BiConsumer<ContentProblemCode, ContentPlace> add) {
        if (stored.instruction() == null) {
            add.accept(ContentProblemCode.INSTRUCTION_MISSING, new ContentPlace.Whole(ContentPart.INSTRUCTION));
        } else {
            for (String name : InputReferences.unknown(
                    stored.instruction().value(),
                    stored.takes().declaration().fields().stream()
                            .map(field -> field.name())
                            .toList())) {
                add.accept(ContentProblemCode.INSTRUCTION_NAMES_UNKNOWN, new ContentPlace.AtInstruction(name));
            }
        }
        placed(stored.half(DeclarationSide.TAKES), ContentPart.TAKES, terms, maximumBytes, maximumPerField, add);
        drawingProblems(stored, add);
        if (stored.gives().declaration().fields().isEmpty()) {
            add.accept(ContentProblemCode.NOTHING_GIVEN_BACK, new ContentPlace.Whole(ContentPart.GIVES));
        }
        placed(stored.half(DeclarationSide.GIVES), ContentPart.GIVES, terms, maximumBytes, maximumPerField, add);
        if (maximumTotalBytes != null && stored.gives().declaration().filesPastTotal(maximumTotalBytes)) {
            add.accept(ContentProblemCode.FILE_TOTAL_PAST_DEPLOYMENT, new ContentPlace.Whole(ContentPart.GIVES));
        }
    }

    private static void drawingProblems(StoredQuestion stored, BiConsumer<ContentProblemCode, ContentPlace> add) {
        String drawing = stored.drawing();
        if (drawing == null) {
            return;
        }
        Set<String> taken = stored.takes().declaration().fields().stream()
                .map(field -> field.name().value())
                .collect(Collectors.toSet());
        for (var name : InputReferences.drawingNames(drawing)) {
            if (!taken.contains(name.value())) {
                add.accept(ContentProblemCode.DRAWING_NAMES_UNKNOWN, new ContentPlace.AtDrawing(name.value()));
            }
        }
    }

    private static void placed(
            StoredDeclarations.Half half,
            ContentPart part,
            Map<EntryVersionId, OfferedTerms> terms,
            long maximumBytes,
            int maximumPerField,
            BiConsumer<ContentProblemCode, ContentPlace> add) {
        for (FieldProblem problem :
                half.declaration().problems(terms, maximumBytes, maximumPerField, part == ContentPart.TAKES)) {
            add.accept(problem.code(), new ContentPlace.AtField(part, half.keyAt(problem.at())));
        }
    }
}
