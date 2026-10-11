package com.lilradish.lite.domain.people;

import static java.util.Objects.requireNonNull;

import java.util.List;

/** Whether more exist is learnt by fetching one beyond the most shown, never by counting. */
public record PeopleFound<T>(List<T> people, boolean more) {

    public PeopleFound {
        requireNonNull(people, "PeopleFound people must not be null");
    }

    public static int fetching(int most) {
        if (most < 1) {
            throw new IllegalArgumentException("A search must find at least one, not " + most);
        }
        return most + 1;
    }

    /** {@code fetched} holds at most one beyond {@code most}. */
    public static <T> PeopleFound<T> of(List<T> fetched, int most) {
        requireNonNull(fetched, "PeopleFound fetched must not be null");
        return fetched.size() <= most
                ? new PeopleFound<>(List.copyOf(fetched), false)
                : new PeopleFound<>(List.copyOf(fetched.subList(0, most)), true);
    }
}
