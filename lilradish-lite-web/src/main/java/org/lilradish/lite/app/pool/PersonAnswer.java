package org.lilradish.lite.app.pool;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.people.PersonName;

/**
 * Somebody an answer names, by what identifies them to a reader and never by their subject.
 *
 * @param displayName absent rather than empty where no name is held
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PersonAnswer(String userId, @Nullable String displayName) {

    public static PersonAnswer of(PersonRows.Person person) {
        PersonName name = person.displayName();
        return new PersonAnswer(person.userId().value(), name == null ? null : name.value());
    }
}
