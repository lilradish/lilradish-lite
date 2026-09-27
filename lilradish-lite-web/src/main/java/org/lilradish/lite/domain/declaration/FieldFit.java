package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * Whether what one field holds can be what another takes, and whether two declare the same. A limit or a list
 * one of them has not chosen yet is judged by nothing here: that field's own problem already names it.
 */
public final class FieldFit {

    private FieldFit() {}

    /**
     * Whether every value {@code source} may hold is one {@code target} takes: the same kind, one or many alike,
     * and nothing longer, more numerous or from another list; a field holding fields holds the same names.
     */
    public static boolean fits(Field source, Field target) {
        requireNonNull(source, "FieldFit source must not be null");
        requireNonNull(target, "FieldFit target must not be null");
        if (source.shape().kind() != target.shape().kind() || !withinMost(source.howMany(), target.howMany())) {
            return false;
        }
        return switch (target.shape()) {
            case FieldShape.Text text ->
                source.shape() instanceof FieldShape.Text given && within(given.longest(), text.longest());
            case FieldShape.Term term ->
                source.shape() instanceof FieldShape.Term given && sameList(given.list(), term);
            case FieldShape.Nested nested ->
                source.shape() instanceof FieldShape.Nested given
                        && given.fields().size() == nested.fields().size()
                        && nested.fields().stream().allMatch(taken -> {
                            Field held = Field.named(given.fields(), taken.name());
                            return held != null && fits(held, taken);
                        });
            case FieldShape.Plain ignored -> true;
        };
    }

    /**
     * Whether each field held within {@code target} that must be given is one {@code source} must give too, at
     * every depth. The outermost pair is the caller's to judge, which alone knows the way each was reached by.
     */
    public static boolean givenWithin(Field source, Field target) {
        requireNonNull(source, "FieldFit source must not be null");
        requireNonNull(target, "FieldFit target must not be null");
        if (!(target.shape() instanceof FieldShape.Nested nested)
                || !(source.shape() instanceof FieldShape.Nested given)) {
            return true;
        }
        return nested.fields().stream().allMatch(taken -> {
            Field held = Field.named(given.fields(), taken.name());
            return held == null || ((!taken.demand().mustBe() || held.demand().mustBe()) && givenWithin(held, taken));
        });
    }

    /**
     * Whether {@code one} declares the fields {@code other} does by name, each of the same kind, count, limits, list
     * and fields, and gives each that {@code other} must give; what people read a field as, and the order, aside.
     */
    public static boolean alike(List<Field> one, List<Field> other) {
        requireNonNull(one, "FieldFit one must not be null");
        requireNonNull(other, "FieldFit other must not be null");
        return one.size() == other.size()
                && one.stream().allMatch(field -> {
                    Field match = Field.named(other, field.name());
                    return match != null && alike(field, match);
                });
    }

    private static boolean alike(Field one, Field other) {
        if (!one.howMany().equals(other.howMany())
                || one.shape().kind() != other.shape().kind()
                || (other.demand().mustBe() && !one.demand().mustBe())) {
            return false;
        }
        if (one.shape() instanceof FieldShape.Nested nested && other.shape() instanceof FieldShape.Nested matched) {
            return alike(nested.fields(), matched.fields());
        }
        return one.shape().equals(other.shape());
    }

    private static boolean withinMost(HowMany source, HowMany target) {
        return switch (target) {
            case HowMany.One ignored -> source instanceof HowMany.One;
            case HowMany.Many many -> source instanceof HowMany.Many given && within(given.most(), many.most());
        };
    }

    private static boolean within(@Nullable Integer given, @Nullable Integer taken) {
        return given == null || taken == null || given <= taken;
    }

    private static boolean sameList(@Nullable EntryVersionId given, FieldShape.Term taken) {
        return given == null || taken.list() == null || given.equals(taken.list());
    }
}
