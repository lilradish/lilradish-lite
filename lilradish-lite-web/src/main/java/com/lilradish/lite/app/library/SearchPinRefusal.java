package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.registry.EntryName;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;

/** A shape refused after its question's pin was admitted, carrying only what the caller can read. */
final class SearchPinRefusal extends ApiErrorException {

    private final transient String way;

    private final transient @Nullable EntryName question;

    private final transient @Nullable Integer number;

    SearchPinRefusal(String way, @Nullable EntryName question, @Nullable Integer number) {
        super(LibraryRefusal.SEARCH_QUESTION_DOES_NOT_MATCH.code(), sentence(way, question, number));
        this.way = requireNonNull(way, "SearchPinRefusal way must not be null");
        this.question = question;
        this.number = number;
    }

    String way() {
        return way;
    }

    @Nullable
    EntryName question() {
        return question;
    }

    @Nullable
    Integer number() {
        return number;
    }

    private static String sentence(String way, @Nullable EntryName question, @Nullable Integer number) {
        requireNonNull(way, "SearchPinRefusal way must not be null");
        if ((question == null) != (number == null) || number != null && number < 1) {
            throw new IllegalArgumentException("SearchPinRefusal names a question with its positive version number");
        }
        return question == null
                ? LibraryRefusal.SEARCH_QUESTION_DOES_NOT_MATCH.sentence().replace("{way}", way)
                : question.value() + " · Version " + number
                        + " can't be used to search with " + way + ": its answer isn't exactly what " + way
                        + " gives back. Pick a version set for searching with " + way + ".";
    }
}
