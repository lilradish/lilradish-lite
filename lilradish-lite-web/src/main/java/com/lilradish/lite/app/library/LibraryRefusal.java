package com.lilradish.lite.app.library;

import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.search.SearchWay;
import com.lilradish.lite.domain.text.ProseRefusal;
import java.text.ListFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;

enum LibraryRefusal {
    APPROVER_WROTE_VERSION(RefusalCode.APPROVER_WROTE_VERSION, "Nobody approves a version they wrote."),
    BINDINGS_PAST_INPUTS(
            RefusalCode.BINDINGS_PAST_INPUTS,
            "What fills a step, a case or what the workflow gives back is at most one binding for each field it"
                    + " declares."),
    CASE_TERM_REPEATED(RefusalCode.CASE_TERM_REPEATED, "No two cases of one route are on the same term."),
    CASES_PAST_TERMS(
            RefusalCode.CASES_PAST_TERMS,
            "A route holds at most one case for each term the list it chooses by offers, and a fallback."),
    CEILING_UNUSABLE(
            RefusalCode.CEILING_UNUSABLE,
            "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all."),
    CODE_STEP_NOT_PUBLISHED(
            RefusalCode.CODE_STEP_NOT_PUBLISHED, "Only a code step published to this group may be named."),
    CONSTANT_DOES_NOT_FIT(
            RefusalCode.CONSTANT_DOES_NOT_FIT,
            "A constant is written as a value is: a number in at most 38 digits either side of its point, no NUL"
                    + " character, no more than 8,192 characters of text in all, and no more than 1,048,576"
                    + " characters written out. Text for a field of one line holds no line break."),
    DECLARATION_TOO_DEEP(
            RefusalCode.DECLARATION_TOO_DEEP,
            "A field is held more than 32 deep, or so deep that the names down to it, joined by dots, run past"
                    + " 1023 characters."),
    DECLARATION_TOO_LARGE(
            RefusalCode.DECLARATION_TOO_LARGE, "A half holds at most 256 fields, at every depth together."),
    DRAFT_ALREADY_STARTED(
            RefusalCode.DRAFT_ALREADY_STARTED, "This entry already has a version that is a draft or submitted."),
    DRAFT_WRITTEN_SINCE_READ(
            RefusalCode.DRAFT_WRITTEN_SINCE_READ, "Somebody changed this draft since it was read; nothing was saved."),
    ENTRY_NAME_TAKEN(RefusalCode.ENTRY_NAME_TAKEN, "Another entry of this kind in this group already has that name."),
    ENTRY_NAME_UNUSABLE(
            RefusalCode.ENTRY_NAME_UNUSABLE,
            "An entry's name is one to 128 characters on one line: no space but single plain ones between words,"
                    + " none at either end, and something in it that shows."),
    ENTRY_NOT_IN_VIEW(RefusalCode.ENTRY_NOT_IN_VIEW, "That entry is not in this group's library."),
    ENTRY_PURPOSE_UNUSABLE(
            RefusalCode.ENTRY_PURPOSE_UNUSABLE,
            "What an entry is for is at most 512 characters on one line, with something in it that shows."),
    FIELD_LIMIT_UNUSABLE(
            RefusalCode.FIELD_LIMIT_UNUSABLE,
            "How long a field may be and how many it may hold are whole numbers from 1 to 2147483647, and the"
                    + " confidence a value stands above is a whole number from 1 to 100."),
    FIELD_NAME_UNUSABLE(
            RefusalCode.FIELD_NAME_UNUSABLE,
            "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter."),
    FIELD_WORDS_UNUSABLE(
            RefusalCode.FIELD_WORDS_UNUSABLE,
            "A field's label is at most 128 characters and its help at most 512, each on one line with something"
                    + " in it that shows."),
    FILE_SIZE_LIMIT_UNUSABLE(
            RefusalCode.FILE_SIZE_LIMIT_UNUSABLE,
            "A file field's maximum size is a whole number of bytes, at least 1 byte and less than 8,388,608 GB."),
    FILE_TYPES_TOO_MANY(RefusalCode.FILE_TYPES_TOO_MANY, "A file field can accept at most 64 extensions."),
    INSTRUCTION_UNUSABLE(
            RefusalCode.INSTRUCTION_UNUSABLE,
            "An instruction is one to 8192 characters with something in it that shows; its lines end in a line"
                    + " feed, and a tab is the only other control it holds."),
    INSTRUCTION_NAMES_UNKNOWN(RefusalCode.INSTRUCTION_NAMES_UNKNOWN, "{names} isn't an input of this question."),
    INPUT_STILL_MENTIONED(
            RefusalCode.INPUT_STILL_MENTIONED,
            "{names} is still mentioned in the instruction or how it is drawn; remove those mentions first."),
    DRAWING_UNUSABLE(
            RefusalCode.DRAWING_UNUSABLE,
            "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented"
                    + " with tabs."),
    DRAWING_TOO_DEEP(
            RefusalCode.DRAWING_TOO_DEEP,
            "A drawing holds lists and quotes at most 32 deep, counting on each line every marker it opens with and"
                    + " every two spaces of indent before and between them, a tab as four; and emphasis at most 32 deep,"
                    + " counting every two asterisks or underscores between blank lines, an underscore inside a word"
                    + " aside."),
    LIST_TOO_LARGE(RefusalCode.LIST_TOO_LARGE, "A reference list holds at most 256 terms."),
    NOTE_UNUSABLE(
            RefusalCode.NOTE_UNUSABLE,
            "A note is one to 2048 characters with something in it that shows; its lines end in a line feed, and a"
                    + " tab is the only other control it holds."),
    PROSE_DIRECTION_CONTROL(
            RefusalCode.PROSE_DIRECTION_CONTROL,
            "What was written holds a direction control, which text sent to a model may not hold."),
    PROSE_INVISIBLE_CHARACTER(
            RefusalCode.PROSE_INVISIBLE_CHARACTER,
            "What was written holds a character that shows nothing, which a term may not hold."),
    PROSE_LINE_BREAK_CRLF(
            RefusalCode.PROSE_LINE_BREAK_CRLF,
            "What was written breaks its lines with a carriage return and a line feed (CRLF); a line here ends in a"
                    + " line feed alone."),
    PROSE_TAG_CHARACTER(
            RefusalCode.PROSE_TAG_CHARACTER,
            "What was written holds a tag character, which shows nothing and which text sent to a model may not"
                    + " hold."),
    SEARCH_QUESTION_SHAPE_LOCKED(
            RefusalCode.SEARCH_QUESTION_SHAPE_LOCKED,
            "The answer is set by {way} while For searching is on; only how sure each field must be can change. Turn off For searching to change the rest."),
    SEARCH_QUESTION_DOES_NOT_MATCH(
            RefusalCode.SEARCH_QUESTION_DOES_NOT_MATCH,
            "That question can't be used to search with {way}: its answer isn't exactly what {way} gives back. Pick a version set for searching with {way}."),
    SEARCH_WAY_NOT_HELD(
            RefusalCode.SEARCH_WAY_NOT_HELD,
            "The search this question was set for is no longer available here. Choose another search, or turn off For searching."),
    STEPS_TOO_MANY(RefusalCode.STEPS_TOO_MANY, "A workflow version holds at most 256 steps."),
    STEP_NAME_UNUSABLE(
            RefusalCode.STEP_NAME_UNUSABLE,
            "A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter."),
    STEP_LABEL_UNUSABLE(
            RefusalCode.STEP_LABEL_UNUSABLE,
            "A step's display name is up to 128 characters on one line, with single spaces between words, none at the"
                    + " start or end, and something in it that shows. Leave it empty for none."),
    TERM_AT_END(RefusalCode.TERM_AT_END, "That term is already first or last, so it moves no further that way."),
    TERM_MEANING_UNUSABLE(
            RefusalCode.TERM_MEANING_UNUSABLE,
            "What a term means is one to 512 characters on one line, with something in it that shows."),
    TERM_NOT_IN_VIEW(RefusalCode.TERM_NOT_IN_VIEW, "That term is not in this draft."),
    TERM_UNUSABLE(
            RefusalCode.TERM_UNUSABLE,
            "A term is one to 128 characters on one line: no space but single plain ones between words, none at"
                    + " either end, and something in it that shows."),
    VERSION_CONTENT_DOES_NOT_HOLD(
            RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD,
            "Some of what this version holds does not hold yet, and each place is named."),
    VERSION_NAME_UNUSABLE(
            RefusalCode.VERSION_NAME_UNUSABLE,
            "A version name is up to 64 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none."),
    VERSION_NOT_IN_VIEW(RefusalCode.VERSION_NOT_IN_VIEW, "That version is not in this group's library."),
    VERSION_NOT_PINNABLE(RefusalCode.VERSION_NOT_PINNABLE, "Only a version of this group's in service may be pinned."),
    VERSION_PINS_RETIRED(RefusalCode.VERSION_PINS_RETIRED, "This version pins a version retired since."),
    VERSION_STANDING_REFUSES(RefusalCode.VERSION_STANDING_REFUSES, "That version's standing does not admit this.");

    private final RefusalCode code;

    private final String sentence;

    private static final ListFormat INPUT_NAMES =
            ListFormat.getInstance(Locale.ENGLISH, ListFormat.Type.STANDARD, ListFormat.Style.FULL);

    LibraryRefusal(RefusalCode code, String sentence) {
        this.code = code;
        this.sentence = sentence;
    }

    RefusalCode code() {
        return code;
    }

    String sentence() {
        return sentence;
    }

    ApiErrorException raised() {
        return raised(null);
    }

    ApiErrorException raised(@Nullable Throwable cause) {
        if (this == SEARCH_QUESTION_SHAPE_LOCKED || this == SEARCH_QUESTION_DOES_NOT_MATCH) {
            throw new IllegalStateException("A search answer refusal must name its held way");
        }
        return new ApiErrorException(code, sentence, cause);
    }

    ApiErrorException raisedForSearchWay(SearchWay way) {
        return new ApiErrorException(code, sentence.replace("{way}", way.name()));
    }

    /** The refusal a domain rule answered with, which has to be one the library raises. */
    static LibraryRefusal answering(RefusalCode code) {
        for (LibraryRefusal refusal : values()) {
            if (refusal.code == code) {
                return refusal;
            }
        }
        throw new IllegalArgumentException("The library raises no refusal " + code);
    }

    /** What a page names apart is raised under its own code, and anything else under the value's own. */
    static ApiErrorException refusingProse(ProseRefusal refusal, LibraryRefusal unusable) {
        RefusalCode code = refusal.code(unusable.code);
        return code == unusable.code ? unusable.raised() : answering(code).raised();
    }

    static InputReferenceRefusal refusingInputs(List<String> names) {
        String named = INPUT_NAMES.format(
                names.stream().map(name -> "{{" + name + "}}").toList());
        String sentence = names.size() == 1
                ? INSTRUCTION_NAMES_UNKNOWN.sentence.replace("{names}", named)
                : named + " aren't inputs of this question.";
        return new InputReferenceRefusal(RefusalCode.INSTRUCTION_NAMES_UNKNOWN, sentence, names);
    }

    static InputReferenceRefusal stillMentioned(List<FieldName> names) {
        // Closing braces participate in ordering when one name is a prefix of another.
        List<String> ordered = names.stream()
                .map(FieldName::value)
                .sorted(Comparator.comparing(name -> "{{" + name + "}}"))
                .toList();
        String named = INPUT_NAMES.format(
                ordered.stream().map(name -> "{{" + name + "}}").toList());
        return new InputReferenceRefusal(
                RefusalCode.INPUT_STILL_MENTIONED,
                named
                        + (names.size() == 1 ? " is" : " are")
                        + " still mentioned in the instruction or how it is drawn; remove those mentions first.",
                ordered);
    }
}
