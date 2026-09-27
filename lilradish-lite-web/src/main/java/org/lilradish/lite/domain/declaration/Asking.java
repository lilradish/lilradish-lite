package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * What a model is told of a question when it is asked: its instruction, and each half's fields as
 * {@link AskedField} tells them, in declared order. Worked out only from what submitting would take.
 */
public record Asking(Instruction instruction, List<AskedField> takes, List<AskedField> gives) {

    public Asking {
        requireNonNull(instruction, "Asking instruction must not be null");
        takes = List.copyOf(requireNonNull(takes, "Asking takes must not be null"));
        gives = List.copyOf(requireNonNull(gives, "Asking gives must not be null"));
    }

    /** Refused where either half could not be told, or pins a list {@code lists} does not hold. */
    public static Asking of(
            Instruction instruction, Declaration takes, Declaration gives, Map<EntryVersionId, OfferedTerms> lists) {
        requireNonNull(takes, "Asking takes must not be null");
        requireNonNull(gives, "Asking gives must not be null");
        if (takes.side() != DeclarationSide.TAKES || gives.side() != DeclarationSide.GIVES) {
            throw new IllegalArgumentException("Asking takes what is taken and what is given back, in that order");
        }
        return new Asking(instruction, told(takes, lists), told(gives, lists));
    }

    /** Whether the half could be told: it holds as submitting requires, and a half given back gives something. */
    public static boolean tellable(Declaration half) {
        requireNonNull(half, "Asking half must not be null");
        return half.holds()
                && (half.side() == DeclarationSide.TAKES || !half.fields().isEmpty());
    }

    /** One half as a model is told it, refused where it is not {@link #tellable}. */
    public static List<AskedField> told(Declaration half, Map<EntryVersionId, OfferedTerms> lists) {
        requireNonNull(lists, "Asking lists must not be null");
        if (!tellable(half)) {
            throw new IllegalArgumentException("Asking is worked out only from what submitting would take");
        }
        return asked(half.fields(), lists);
    }

    private static List<AskedField> asked(List<Field> level, Map<EntryVersionId, OfferedTerms> lists) {
        return level.stream()
                .map(field -> new AskedField(
                        field.name(),
                        field.shape().kind(),
                        field.shape() instanceof FieldShape.Text text ? requireNonNull(text.longest()) : null,
                        most(field.howMany()),
                        field.shape() instanceof FieldShape.Term term
                                ? offered(requireNonNull(term.list()), lists)
                                : null,
                        field.shape() instanceof FieldShape.Nested nested ? asked(nested.fields(), lists) : List.of(),
                        field.demand().mustBe(),
                        field.demand() instanceof Demand.Stands stands
                                && stands.standing() == FieldStanding.ABOVE_CONFIDENCE))
                .toList();
    }

    private static OfferedTerms offered(EntryVersionId list, Map<EntryVersionId, OfferedTerms> lists) {
        OfferedTerms offered = lists.get(list);
        if (offered == null) {
            throw new IllegalArgumentException("Asking was handed no terms for list " + list.value());
        }
        return offered;
    }

    private static @Nullable Integer most(HowMany howMany) {
        return switch (howMany) {
            case HowMany.One ignored -> null;
            case HowMany.Many many -> requireNonNull(many.most());
        };
    }
}
