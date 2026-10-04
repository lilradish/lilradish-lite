package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * Whether what one field holds can be what another takes or a route gives back. A limit or a list
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
                source.shape() instanceof FieldShape.Text given
                        && within(given.longest(), text.longest())
                        && (text.lines() == TextLines.SEVERAL || given.lines() == TextLines.ONE);
            case FieldShape.Term term ->
                source.shape() instanceof FieldShape.Term given && sameList(given.list(), term);
            case FieldShape.Nested nested ->
                source.shape() instanceof FieldShape.Nested given
                        && given.fields().size() == nested.fields().size()
                        && nested.fields().stream().allMatch(taken -> {
                            Field held = Field.named(given.fields(), taken.name());
                            return held != null && fits(held, taken);
                        });
            case FieldShape.File file ->
                source.shape() instanceof FieldShape.File given
                        && file.types().containsAll(given.types())
                        && within(given.maximumBytes(), file.maximumBytes());
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

    /** A case may give only fields the route declares, within its limits and assuring what it requires. */
    public static void visitCaseDifferences(List<Field> given, List<Field> declared, CaseDifferenceVisitor visitor) {
        requireNonNull(given, "FieldFit given must not be null");
        requireNonNull(declared, "FieldFit declared must not be null");
        requireNonNull(visitor, "FieldFit visitor must not be null");
        visitCaseDifferences(
                given, declared, new int[Declaration.MOST_LEVELS], new int[Declaration.MOST_LEVELS], 0, visitor);
    }

    private static void visitCaseDifferences(
            List<Field> given,
            List<Field> declared,
            int[] givenPath,
            int[] declaredPath,
            int depth,
            CaseDifferenceVisitor visitor) {
        Map<FieldName, Integer> declaredPositions = HashMap.newHashMap(declared.size());
        for (int index = 0; index < declared.size(); index++) {
            declaredPositions.putIfAbsent(declared.get(index).name(), index);
        }
        Set<FieldName> givenNames = new HashSet<>(given.size());
        for (int index = 0; index < given.size(); index++) {
            Field field = given.get(index);
            givenNames.add(field.name());
            Integer match = declaredPositions.get(field.name());
            givenPath[depth] = index;
            if (match == null) {
                visitor.found(CaseDifferenceKind.UNDECLARED, true, givenPath, depth + 1);
                continue;
            }
            Field routeField = declared.get(match);
            declaredPath[depth] = match;
            if (field.shape().kind() != routeField.shape().kind()
                    || field.howMany().getClass() != routeField.howMany().getClass()
                    || (routeField.shape() instanceof FieldShape.Term term
                            && field.shape() instanceof FieldShape.Term givenTerm
                            && !sameList(givenTerm.list(), term))) {
                visitor.found(CaseDifferenceKind.OTHER_KIND, true, givenPath, depth + 1);
                continue;
            }
            if (!withinMost(field.howMany(), routeField.howMany())
                    || (routeField.shape() instanceof FieldShape.File file
                            && field.shape() instanceof FieldShape.File givenFile
                            && (!file.types().containsAll(givenFile.types())
                                    || !within(givenFile.maximumBytes(), file.maximumBytes())))
                    || (routeField.shape() instanceof FieldShape.Text text
                            && field.shape() instanceof FieldShape.Text givenText
                            && (!within(givenText.longest(), text.longest())
                                    || (text.lines() == TextLines.ONE && givenText.lines() == TextLines.SEVERAL)))) {
                visitor.found(CaseDifferenceKind.PAST_LIMIT, true, givenPath, depth + 1);
            } else if (routeField.demand().mustBe() && !field.demand().mustBe()) {
                visitor.found(CaseDifferenceKind.MAY_GIVE_NOTHING, true, givenPath, depth + 1);
            }
            if (field.shape() instanceof FieldShape.Nested nested
                    && routeField.shape() instanceof FieldShape.Nested routeNested) {
                visitCaseDifferences(
                        nested.fields(), routeNested.fields(), givenPath, declaredPath, depth + 1, visitor);
            }
        }
        for (int index = 0; index < declared.size(); index++) {
            Field field = declared.get(index);
            if (field.demand().mustBe() && !givenNames.contains(field.name())) {
                declaredPath[depth] = index;
                visitor.found(CaseDifferenceKind.MAY_GIVE_NOTHING, false, declaredPath, depth + 1);
            }
        }
    }

    public enum CaseDifferenceKind {
        UNDECLARED,
        OTHER_KIND,
        PAST_LIMIT,
        MAY_GIVE_NOTHING
    }

    /** The path array is reused and valid only during {@link #found}. */
    @FunctionalInterface
    public interface CaseDifferenceVisitor {

        void found(CaseDifferenceKind kind, boolean inCase, int[] path, int depth);
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

    private static boolean within(@Nullable Long given, @Nullable Long taken) {
        return given == null || taken == null || given <= taken;
    }

    private static boolean sameList(@Nullable EntryVersionId given, FieldShape.Term taken) {
        return given == null || taken.list() == null || given.equals(taken.list());
    }
}
