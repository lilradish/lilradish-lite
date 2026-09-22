package org.lilradish.lite.domain.identity;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

/**
 * Who an act is recorded against, asked in the one way nothing outside this system can change. What
 * an identity provider asserts of a person sits beside this as a {@link UserId} and is not
 * carried here: that format belongs to whoever runs the provider, and a record keyed on it stops
 * being readable the day they change it.
 *
 * <p>A person, an actor that exists in code and the act of seeding are all named this way, which is
 * what keeps three different absences of a person three different answers rather than one empty
 * column. No value here stands for "nobody": a record with no author is a record that cannot say
 * which of the three it was.
 *
 * <p>A delegation's label is carried in this type as well, and nothing yet accommodates it: the
 * kinds a subject may be are those three, so a label names a row that cannot exist. Whichever
 * migration brings in the delegation table settles it — either that kind is added, or a label
 * stops being a subject and gains a type of its own.
 */
public record SubjectId(UUID value) {

    public SubjectId {
        requireNonNull(value, "SubjectId must not be null");
    }
}
