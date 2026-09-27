package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What a group is called. Work is filed under a {@link GroupId}, so correcting a name unfiles nothing —
 * but two groups a reader cannot tell apart are two groups work is filed into by guess, and the store
 * keeps names unique whatever case either was typed in for that reason.
 *
 * <p>Held to the rule a person's name is held to, argued in {@link Legibility}: one well-formed line,
 * U+0020 the only space and never at an end or doubled, and something in it that shows. A format
 * character is kept, some scripts needing one to join or part their letters, so a name may carry a
 * bidirectional control and is shown isolated wherever it is shown.
 *
 * <p>A name is refused rather than tidied: this constructor runs both when a person types a name and
 * when a row is rebuilt out of the store, so tidying here would fold two stored rows into one value.
 * Case is carried as given; that two names differing only by case are one name is the store's to say.
 */
public record GroupName(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 128;

    public GroupName {
        requireNonNull(value, "GroupName must not be null");
        Legibility.requireOneWellFormedLine(value, "GroupName");
        Legibility.requireNotEmpty(value, "GroupName");
        Legibility.requireSpaceDecidesNothing(value, "GroupName");
        Legibility.requireSomethingVisible(value, "GroupName");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "GroupName");
    }
}
