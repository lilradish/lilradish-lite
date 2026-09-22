package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import org.lilradish.lite.domain.text.Legibility;

/**
 * What a user is known by outside this system, as whatever identity provider a deployer runs
 * asserts it. The format is theirs rather than this system's, so this is not a charset, and a
 * narrower rule would refuse a deployment nothing is wrong with. Inside, a person is named by a
 * {@link SubjectId}, which is why the format staying open costs nothing that is kept.
 *
 * <p>What is tightened instead is legibility, argued once in {@link Legibility} and not repeated
 * here. What makes this the value it is asked of: it is unique among people in the store, so it is
 * what makes somebody brought back into the pool the same person rather than a second one, and it
 * is interpolated into messages about the acts it is recorded against. A twin no reader can tell it
 * from is that second person.
 *
 * <p>Case is deliberately left alone. Whether two spellings are one user belongs to whoever
 * asserts them, and folding here would merge two people in a deployment that says they are two —
 * the direction that cannot be undone.
 */
public record UserId(String value) {

    /** Level with the column's own bound, and counted the way that check counts. */
    private static final int MAXIMUM_LENGTH = 256;

    public UserId {
        requireNonNull(value, "UserId must not be null");
        Legibility.requireVisibleAndWellFormed(value, "UserId");
        Legibility.requireNotEmpty(value, "UserId");
        Legibility.requireSpaceDecidesNothing(value, "UserId");
        Legibility.requireWithinMaximumLength(value, MAXIMUM_LENGTH, "UserId");
    }
}
