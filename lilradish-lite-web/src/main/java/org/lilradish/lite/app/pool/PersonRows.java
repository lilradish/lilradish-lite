package org.lilradish.lite.app.pool;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.people.PersonName;
import org.lilradish.lite.domain.wire.StoreLabels;

/**
 * Somebody in the pool as a row of any reading of them holds them, under the columns every such reading
 * names alike: {@code subject_id}, {@code user_id} and {@code display_name}. Shared by every reading of
 * people in the pool, so no two of them judge a stored value differently.
 *
 * <p>A stored value its type refuses — written round the type, by hand or by a restore — fails the whole
 * read rather than being shown or left out, naming whose row it was.
 */
public final class PersonRows {

    private PersonRows() {}

    public static SubjectId subjectOf(ResultSet result) throws SQLException {
        return new SubjectId(result.getObject("subject_id", UUID.class));
    }

    public static UserId userIdOf(ResultSet result, SubjectId subject) throws SQLException {
        try {
            return new UserId(result.getString("user_id"));
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Subject " + subject.value() + " holds a user id this system will not show", refused);
        }
    }

    /** Absent where no name is held. */
    public static @Nullable PersonName nameOf(ResultSet result, SubjectId subject) throws SQLException {
        String held = result.getString("display_name");
        if (held == null) {
            return null;
        }
        try {
            return new PersonName(held);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Subject " + subject.value() + " holds a name this system will not show", refused);
        }
    }

    /** Somebody as the row names them, under the three columns every reading of people names alike. */
    public static Person person(ResultSet result) throws SQLException {
        SubjectId subject = subjectOf(result);
        return new Person(subject, userIdOf(result, subject), nameOf(result, subject));
    }

    /** An array of stored labels, as the constants of the type they label. */
    public static <E extends Enum<E>> Set<E> labelled(Class<E> type, Array stored) throws SQLException {
        EnumSet<E> held = EnumSet.noneOf(type);
        for (String label : texts(stored)) {
            held.add(StoreLabels.parse(type, label));
        }
        return Collections.unmodifiableSet(held);
    }

    static String[] texts(Array stored) throws SQLException {
        return (String[]) stored.getArray();
    }

    /** @param displayName none where no name is held */
    public record Person(
            SubjectId subjectId, UserId userId, @Nullable PersonName displayName) {}
}
