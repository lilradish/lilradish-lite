package org.lilradish.lite.domain.search;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.inference.KeptAnswer;

/**
 * What is kept of one search an answer said it made: each text as said where the store can hold it, otherwise with
 * each character it cannot hold replaced. A text is absent where the answer did not say it, or where nothing of it
 * could be kept before a call's searches together ran past what one answer is kept to; an empty text is one the
 * answer said empty. A search keeping neither is never kept. Nothing is cleaned.
 */
public record SearchRecord(
        @DoNotLog @Nullable String searchedFor,
        @DoNotLog @Nullable String found) {

    /** The most searches kept of one call. */
    static final int MOST_SEARCHES = 1024;

    public SearchRecord {
        if (searchedFor == null && found == null) {
            throw new IllegalArgumentException("SearchRecord keeps searchedFor, found, or both");
        }
    }

    /**
     * In the order said, searchedFor before found, each taking from what is left of {@link KeptAnswer#MOST_KEPT},
     * counted in code points: one call's searches together are kept to what one answer is. A search saying neither
     * is dropped, kept and counted nowhere. Once that is spent the record is cut there; once it is cut, or once
     * {@link #MOST_SEARCHES} are kept, every search after that says anything is left out and counted, its texts
     * never read.
     */
    public static CallSearchRecord kept(List<SearchSaid> said) {
        requireNonNull(said, "SearchRecord said must not be null");
        List<SearchRecord> searches = new ArrayList<>(Math.min(said.size(), MOST_SEARCHES));
        Room room = new Room();
        int leftOut = 0;
        for (SearchSaid search : said) {
            if (search.searchedFor() == null && search.found() == null) {
                continue;
            }
            if (searches.size() == MOST_SEARCHES) {
                leftOut++;
                continue;
            }
            String searchedFor = room.keep(search.searchedFor());
            String found = room.keep(search.found());
            if (searchedFor != null || found != null) {
                searches.add(new SearchRecord(searchedFor, found));
            } else {
                leftOut++;
            }
        }
        return new CallSearchRecord(searches, room.cut, room.notAsItCame, leftOut);
    }

    /**
     * What is kept of one call's searches, and what its reader is told of the whole: that it is cut where what one
     * call keeps ran out, that some character in it is not as it came, and how many searches saying something had
     * nothing of them kept, past a cut or past {@link #MOST_SEARCHES}. The search cut part-way is kept, so is not
     * among them; a cut and searches left out may both be told.
     */
    public record CallSearchRecord(List<SearchRecord> searches, boolean cut, boolean notAsItCame, int leftOut) {

        public CallSearchRecord {
            searches = List.copyOf(requireNonNull(searches, "SearchRecord.CallSearchRecord searches must not be null"));
            if (leftOut < 0 || (leftOut > 0 && !cut && searches.size() < MOST_SEARCHES)) {
                throw new IllegalArgumentException(
                        "SearchRecord.CallSearchRecord leaves out only past a cut or the most searches kept");
            }
        }
    }

    /** What is left for one call's searches, in code points, and what keeping them so far has done. */
    private static final class Room {

        private int left = KeptAnswer.MOST_KEPT;

        private boolean cut;

        private boolean notAsItCame;

        private @Nullable String keep(@Nullable String said) {
            if (said == null || cut) {
                return null;
            }
            KeptAnswer answer = KeptAnswer.of(said);
            String text = answer.text();
            int end = text.length();
            if (end > left) {
                end = 0;
                for (int counted = 0; counted < left && end < text.length(); counted++) {
                    end += Character.charCount(text.codePointAt(end));
                }
            }
            left -= text.codePointCount(0, end);
            // Replacing keeps each char where it stood, so what is kept and what was said compare index for index.
            cut = end < said.length();
            notAsItCame |= answer.altered() && !said.regionMatches(0, text, 0, end);
            return end == 0 && cut ? null : text.substring(0, end);
        }
    }
}
