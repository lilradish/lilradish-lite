package org.lilradish.lite.domain.people;

import static java.util.Objects.requireNonNull;

import java.util.List;

/**
 * The first of those a search found, and whether it found more. Whether more exist is learnt by
 * fetching one beyond the most shown, never by counting.
 *
 * @param more whether the search found somebody beyond those listed
 */
public record PeopleFound<T>(List<T> people, boolean more) {

    public PeopleFound {
        requireNonNull(people, "PeopleFound people must not be null");
    }

    /** How many to fetch to learn whether a search finds more than {@code most}. */
    public static int fetching(int most) {
        if (most < 1) {
            throw new IllegalArgumentException("A search must find at least one, not " + most);
        }
        return most + 1;
    }

    /** The first {@code most} of what was fetched, which is at most one beyond them. */
    public static <T> PeopleFound<T> of(List<T> fetched, int most) {
        requireNonNull(fetched, "PeopleFound fetched must not be null");
        return fetched.size() <= most
                ? new PeopleFound<>(List.copyOf(fetched), false)
                : new PeopleFound<>(List.copyOf(fetched.subList(0, most)), true);
    }
}
