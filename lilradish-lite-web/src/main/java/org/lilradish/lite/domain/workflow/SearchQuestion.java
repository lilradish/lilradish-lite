package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.Asking;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demand;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.text.Legibility;

/**
 * What a searching step's question could hold, decided once for submitting and for any reading since: what makes sure
 * it holds something, and how a value read into it is told.
 */
public final class SearchQuestion {

    private SearchQuestion() {}

    /**
     * The question exactly as sent. A missing optional value has no text; each present value is already spelt by
     * its caller as text or as JSON for its source kind.
     */
    public static String asSent(
            List<SearchQuestionPart> question, Function<SearchQuestionPart.Read, @Nullable String> valueAsText) {
        requireNonNull(question, "SearchQuestion question must not be null");
        requireNonNull(valueAsText, "SearchQuestion valueAsText must not be null");
        StringBuilder sent = new StringBuilder();
        for (SearchQuestionPart part : question) {
            switch (part) {
                case SearchQuestionPart.LiteralPart literal -> sent.append(literal.text());
                case SearchQuestionPart.Read read -> {
                    String text = valueAsText.apply(read);
                    if (text != null) {
                        sent.append(text);
                    }
                }
            }
        }
        return sent.toString();
    }

    /**
     * Whether it could be sent holding nothing: no text written into it shows anything, and no value it puts in is
     * read from what must be given.
     *
     * @param sourceMustBeGiven whether what a part reads must be given, and so must every field holding it
     */
    public static boolean couldHoldNothing(
            List<SearchQuestionPart> question, Predicate<SearchQuestionPart.Read> sourceMustBeGiven) {
        requireNonNull(question, "SearchQuestion question must not be null");
        requireNonNull(sourceMustBeGiven, "SearchQuestion sourceMustBeGiven must not be null");
        for (SearchQuestionPart part : question) {
            boolean holds =
                    switch (part) {
                        case SearchQuestionPart.LiteralPart literal ->
                            Legibility.holdsSomethingVisibleInProse(literal.text());
                        case SearchQuestionPart.Read read -> sourceMustBeGiven.test(read);
                    };
            if (holds) {
                return false;
            }
        }
        return true;
    }

    /**
     * The field a part reads as a model is told it, to be given only where its whole path is; none where it does not
     * hold as submitting requires. A list it takes a term from that {@code lists} lacks is a store gone wrong.
     */
    public static @Nullable AskedField told(Pointer.Reach.At read, Map<EntryVersionId, OfferedTerms> lists) {
        requireNonNull(read, "SearchQuestion read must not be null");
        requireNonNull(lists, "SearchQuestion lists must not be null");
        Field field = read.field();
        Declaration alone = new Declaration(
                DeclarationSide.TAKES,
                Demands.ofWorkflow(DeclarationSide.TAKES),
                List.of(new Field(
                        field.name(), null, null, field.shape(), field.howMany(), new Demand.Given(read.given()))));
        if (alone.hasFile() || !Asking.tellable(alone)) {
            return null;
        }
        if (!listsHeld(alone.fields(), lists)) {
            throw new IllegalStateException(
                    "A value read into a searching step's question takes a term from a list not handed over");
        }
        return Asking.told(alone, lists).getFirst();
    }

    public static boolean takesFile(Pointer.Reach.At read) {
        return shapeHasFile(read.field().shape());
    }

    private static boolean shapeHasFile(FieldShape shape) {
        if (shape instanceof FieldShape.File) {
            return true;
        }
        if (shape instanceof FieldShape.Nested nested) {
            for (Field field : nested.fields()) {
                if (shapeHasFile(field.shape())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean listsHeld(List<Field> level, Map<EntryVersionId, OfferedTerms> lists) {
        for (Field field : level) {
            boolean held =
                    switch (field.shape()) {
                        case FieldShape.Term term -> term.list() != null && lists.containsKey(term.list());
                        case FieldShape.Nested nested -> listsHeld(nested.fields(), lists);
                        case FieldShape.Text ignored -> true;
                        case FieldShape.Plain ignored -> true;
                        case FieldShape.File ignored -> false;
                    };
            if (!held) {
                return false;
            }
        }
        return true;
    }
}
