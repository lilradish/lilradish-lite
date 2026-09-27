package org.lilradish.lite.app.library

import org.lilradish.lite.domain.failure.RefusalCode
import org.lilradish.lite.domain.text.ProseRefusal
import spock.lang.Specification

class LibraryRefusalSpec extends Specification {

    /** One sentence per code, the same wherever in the library the code is raised. */
    def "each refusal the library raises carries its code and the one sentence written for it"() {
        when:
        def raised = refusal.raised()

        then:
        raised.errorCode() == code
        raised.message == sentence
        raised.cause == null

        where:
        refusal                                 || code                                 | sentence
        LibraryRefusal.APPROVER_WROTE_VERSION   || RefusalCode.APPROVER_WROTE_VERSION   | "Nobody approves a version they wrote."
        LibraryRefusal.BINDINGS_PAST_INPUTS     || RefusalCode.BINDINGS_PAST_INPUTS     | "What fills a step, a case or what the workflow gives back is at most one binding for each field it declares."
        LibraryRefusal.CASE_TERM_REPEATED       || RefusalCode.CASE_TERM_REPEATED       | "No two cases of one route are on the same term."
        LibraryRefusal.CASES_PAST_TERMS         || RefusalCode.CASES_PAST_TERMS         | "A route holds at most one case for each term the list it chooses by offers, and a fallback."
        LibraryRefusal.CEILING_UNUSABLE         || RefusalCode.CEILING_UNUSABLE         | "A ceiling is a whole number from one to 9,007,199,254,740,991, written in digits, or none at all."
        LibraryRefusal.CODE_STEP_NOT_PUBLISHED  || RefusalCode.CODE_STEP_NOT_PUBLISHED  | "Only a code step published to this group may be named."
        LibraryRefusal.CONSTANT_DOES_NOT_FIT    || RefusalCode.CONSTANT_DOES_NOT_FIT    | "A constant is written as a value is: a number in at most 38 digits either side of its point, no NUL character, no more than 8,192 characters of text in all, and no more than 1,048,576 characters written out."
        LibraryRefusal.DECLARATION_TOO_DEEP     || RefusalCode.DECLARATION_TOO_DEEP     | "A field is held more than 32 deep, or so deep that the names down to it, joined by dots, run past 1023 characters."
        LibraryRefusal.DECLARATION_TOO_LARGE    || RefusalCode.DECLARATION_TOO_LARGE    | "A half holds at most 256 fields, at every depth together."
        LibraryRefusal.DRAFT_ALREADY_STARTED    || RefusalCode.DRAFT_ALREADY_STARTED    | "This entry already has a version that is a draft or submitted."
        LibraryRefusal.DRAFT_WRITTEN_SINCE_READ || RefusalCode.DRAFT_WRITTEN_SINCE_READ | "Somebody changed this draft since it was read; nothing was saved."
        LibraryRefusal.ENTRY_NAME_TAKEN         || RefusalCode.ENTRY_NAME_TAKEN         | "Another entry of this kind in this group already has that name."
        LibraryRefusal.ENTRY_NAME_UNUSABLE      || RefusalCode.ENTRY_NAME_UNUSABLE      | "An entry's name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows."
        LibraryRefusal.ENTRY_NOT_IN_VIEW        || RefusalCode.ENTRY_NOT_IN_VIEW        | "That entry is not in this group's library."
        LibraryRefusal.ENTRY_PURPOSE_UNUSABLE   || RefusalCode.ENTRY_PURPOSE_UNUSABLE   | "What an entry is for is at most 512 characters on one line, with something in it that shows."
        LibraryRefusal.FIELD_LIMIT_UNUSABLE     || RefusalCode.FIELD_LIMIT_UNUSABLE     | "How long a field may be and how many it may hold are whole numbers from 1 to 2147483647, and the confidence a value stands above is a whole number from 1 to 100."
        LibraryRefusal.FIELD_NAME_UNUSABLE      || RefusalCode.FIELD_NAME_UNUSABLE      | "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter."
        LibraryRefusal.FIELD_WORDS_UNUSABLE     || RefusalCode.FIELD_WORDS_UNUSABLE     | "A field's label is at most 128 characters and its help at most 512, each on one line with something in it that shows."
        LibraryRefusal.INSTRUCTION_UNUSABLE     || RefusalCode.INSTRUCTION_UNUSABLE     | "An instruction is one to 8192 characters with something in it that shows; its lines end in a line feed, and a tab is the only other control it holds."
        LibraryRefusal.LIST_TOO_LARGE           || RefusalCode.LIST_TOO_LARGE           | "A reference list holds at most 256 terms."
        LibraryRefusal.NOTE_UNUSABLE            || RefusalCode.NOTE_UNUSABLE            | "A note is one to 2048 characters with something in it that shows; its lines end in a line feed, and a tab is the only other control it holds."
        LibraryRefusal.PROSE_DIRECTION_CONTROL  || RefusalCode.PROSE_DIRECTION_CONTROL  | "What was written holds a direction control, which text sent to a model may not hold."
        LibraryRefusal.PROSE_INVISIBLE_CHARACTER || RefusalCode.PROSE_INVISIBLE_CHARACTER | "What was written holds a character that shows nothing, which a term may not hold."
        LibraryRefusal.PROSE_LINE_BREAK_CRLF    || RefusalCode.PROSE_LINE_BREAK_CRLF    | "What was written breaks its lines with a carriage return and a line feed (CRLF); a line here ends in a line feed alone."
        LibraryRefusal.PROSE_TAG_CHARACTER      || RefusalCode.PROSE_TAG_CHARACTER      | "What was written holds a tag character, which shows nothing and which text sent to a model may not hold."
        LibraryRefusal.STEPS_TOO_MANY          || RefusalCode.STEPS_TOO_MANY           | "A workflow version holds at most 256 steps."
        LibraryRefusal.STEP_NAME_UNUSABLE       || RefusalCode.STEP_NAME_UNUSABLE       | "A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter."
        LibraryRefusal.TERM_AT_END              || RefusalCode.TERM_AT_END              | "That term is already first or last, so it moves no further that way."
        LibraryRefusal.TERM_MEANING_UNUSABLE    || RefusalCode.TERM_MEANING_UNUSABLE    | "What a term means is one to 512 characters on one line, with something in it that shows."
        LibraryRefusal.TERM_NOT_IN_VIEW         || RefusalCode.TERM_NOT_IN_VIEW         | "That term is not in this draft."
        LibraryRefusal.TERM_UNUSABLE            || RefusalCode.TERM_UNUSABLE            | "A term is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows."
        LibraryRefusal.VERSION_CONTENT_DOES_NOT_HOLD || RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD | "Some of what this version holds does not hold yet, and each place is named."
        LibraryRefusal.VERSION_NOT_IN_VIEW      || RefusalCode.VERSION_NOT_IN_VIEW      | "That version is not in this group's library."
        LibraryRefusal.VERSION_NOT_PINNABLE     || RefusalCode.VERSION_NOT_PINNABLE     | "Only a version of this group's in service may be pinned."
        LibraryRefusal.VERSION_PINS_RETIRED     || RefusalCode.VERSION_PINS_RETIRED     | "This version pins a version retired since."
        LibraryRefusal.VERSION_STANDING_REFUSES || RefusalCode.VERSION_STANDING_REFUSES | "That version's standing does not admit this."
    }

    /** What the store said is kept beside the refusal for whoever raised it, and never put in its sentence. */
    def "a refusal raised over what the store refused carries it as its cause, and not in its sentence"() {
        given:
        def cause = new IllegalStateException("duplicate key")

        when:
        def raised = LibraryRefusal.ENTRY_NAME_TAKEN.raised(cause)

        then:
        raised.cause.is(cause)
        raised.message == LibraryRefusal.ENTRY_NAME_TAKEN.sentence()
    }

    def "a code a domain rule answers with is found as the library's refusal for it"() {
        expect:
        LibraryRefusal.answering(refusal.code()) == refusal

        where:
        refusal << LibraryRefusal.values()
    }

    def "a code the library never raises is refused rather than answered with some other sentence"() {
        when:
        LibraryRefusal.answering(code)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "The library raises no refusal " + code

        where:
        code << [RefusalCode.GROUP_NOT_IN_VIEW, RefusalCode.ACT_NOT_PERMITTED]
    }

    def "a reason a page names apart is raised under its own code, and any other under the value's own"() {
        when:
        def raised = LibraryRefusal.refusingProse(refusal, LibraryRefusal.TERM_UNUSABLE)

        then:
        raised.errorCode() == code
        raised.message == LibraryRefusal.answering(code).sentence()
        raised.cause == null

        where:
        refusal                        || code
        ProseRefusal.CRLF              || RefusalCode.PROSE_LINE_BREAK_CRLF
        ProseRefusal.DIRECTION_CONTROL || RefusalCode.PROSE_DIRECTION_CONTROL
        ProseRefusal.INVISIBLE         || RefusalCode.PROSE_INVISIBLE_CHARACTER
        ProseRefusal.TAG               || RefusalCode.PROSE_TAG_CHARACTER
        ProseRefusal.UNUSABLE          || RefusalCode.TERM_UNUSABLE
    }
}
