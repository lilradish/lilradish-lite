package org.lilradish.lite.domain.declaration;

import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.referencelist.TermMeaning;

/**
 * The terms of the list version a field pins, in its order with their meanings, and its note on choosing;
 * the list's own words, read as written and never translated.
 */
public final class OfferedTerms {

    private final List<Offered> terms;

    private final @Nullable ListNote note;

    private final Set<String> offeredValues;

    public OfferedTerms(List<Offered> terms, @Nullable ListNote note) {
        this.terms = List.copyOf(requireNonNull(terms, "OfferedTerms terms must not be null"));
        this.note = note;
        Set<String> values = HashSet.newHashSet(this.terms.size());
        for (Offered offered : this.terms) {
            values.add(offered.term().value());
        }
        this.offeredValues = values;
    }

    public List<Offered> terms() {
        return terms;
    }

    /** None where the list says nothing on choosing, and nothing is put in its place. */
    public @Nullable ListNote note() {
        return note;
    }

    public boolean offers(String said) {
        requireNonNull(said, "OfferedTerms said must not be null");
        return offeredValues.contains(said);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof OfferedTerms offered
                && terms.equals(offered.terms)
                && Objects.equals(note, offered.note);
    }

    @Override
    public int hashCode() {
        return 31 * terms.hashCode() + Objects.hashCode(note);
    }

    @Override
    public String toString() {
        return "OfferedTerms[terms=" + terms + ", note=" + note + "]";
    }

    /** @param meaning when this one is the right answer and a near one is not */
    public record Offered(Term term, TermMeaning meaning) {

        public Offered {
            requireNonNull(term, "OfferedTerms.Offered term must not be null");
            requireNonNull(meaning, "OfferedTerms.Offered meaning must not be null");
        }
    }
}
