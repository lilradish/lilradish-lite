package org.lilradish.lite.app.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.app.files.FileDeployment;
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

/** Checks a question's instruction, drawing, declarations and largest possible asking at submission. */
@Component
final class QuestionContentCheck implements ContentCheck {

    private static final Pattern FIELD_REFERENCE = Pattern.compile("\\{\\{([a-z][a-z0-9_]{0,62})\\}\\}");

    private final JdbcClient database;

    private final FileDeployment deployment;

    QuestionContentCheck(JdbcClient database, FileDeployment deployment) {
        this.database = database;
        this.deployment = deployment;
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
        Matcher reference = FIELD_REFERENCE.matcher(drawing);
        int at = 0;
        while (at < drawing.length() - 1) {
            if (drawing.charAt(at) == '\\' && drawing.startsWith("{{", at + 1)) {
                at += 3;
                continue;
            }
            if (drawing.startsWith("{{", at)) {
                reference.region(at, drawing.length());
                if (reference.lookingAt()) {
                    String name = reference.group(1);
                    if (!taken.contains(name)) {
                        add.accept(ContentProblemCode.DRAWING_NAMES_UNKNOWN, new ContentPlace.AtDrawing(name));
                    }
                    at = reference.end();
                    continue;
                }
            }
            at++;
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
