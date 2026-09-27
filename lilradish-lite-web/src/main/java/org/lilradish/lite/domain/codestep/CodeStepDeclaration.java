package org.lilradish.lite.domain.codestep;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * What a code step takes and gives back, written beside its code and read from nowhere else. Each half asks what a
 * question's asks, so every value it gives back says what it takes to stand.
 *
 * @param mayRunAgain whether the code may be run again for the same value; it may not unless it says so
 */
public record CodeStepDeclaration(Declaration takes, Declaration gives, boolean mayRunAgain) {

    public CodeStepDeclaration {
        requireHalf(requireNonNull(takes, "CodeStepDeclaration takes must not be null"), DeclarationSide.TAKES);
        requireHalf(requireNonNull(gives, "CodeStepDeclaration gives must not be null"), DeclarationSide.GIVES);
    }

    /** Every list a term in either half pins, at any level, those it takes first and each half in declared order. */
    public Set<EntryVersionId> lists() {
        Set<EntryVersionId> pinned = new LinkedHashSet<>();
        pinnedIn(takes.fields(), pinned);
        pinnedIn(gives.fields(), pinned);
        return Collections.unmodifiableSet(pinned);
    }

    private static void pinnedIn(List<Field> level, Set<EntryVersionId> pinned) {
        for (Field field : level) {
            if (field.shape() instanceof FieldShape.Term term && term.list() != null) {
                pinned.add(term.list());
            }
            if (field.shape() instanceof FieldShape.Nested nested) {
                pinnedIn(nested.fields(), pinned);
            }
        }
    }

    private static void requireHalf(Declaration half, DeclarationSide side) {
        if (half.side() != side || !half.demands().equals(Demands.ofQuestion(side))) {
            throw new IllegalArgumentException("CodeStepDeclaration " + side.published()
                    + " must be that half, asking what a question's asks, not "
                    + half.side().published()
                    + " asking " + half.demands());
        }
    }
}
