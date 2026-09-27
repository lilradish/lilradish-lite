package org.lilradish.lite.domain.failure

import static org.libprunus.core.error.ErrorCategory.CONFLICT
import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND
import static org.libprunus.core.error.ErrorCategory.PERMISSION_DENIED
import static org.libprunus.core.error.ErrorCategory.UNAUTHENTICATED
import static org.libprunus.core.error.ErrorCategory.UNAVAILABLE

import spock.lang.Specification

class RefusalCodeSpec extends Specification {

    /**
     * Locked as a set because nothing reads a refusal's ordinal. The lock is what a rename has to
     * get past: these codes are published to whatever consumes the API, and no test anywhere else
     * ties them to a vocabulary, so a rename is otherwise free.
     */
    def "the published codes are fixed, since a rename here is a breaking change and not a refactor"() {
        expect:
        RefusalCode.values()*.code().toSet() == [
                "LIST_CURSOR_UNUSABLE", "LIST_FILTER_UNUSABLE", "PARAMETER_UNKNOWN", "LIST_SORT_UNUSABLE",
                "NOT_SIGNED_IN",
                "PERSON_NOT_IN_VIEW",
                "ACT_NOT_PERMITTED",
                "BODY_UNUSABLE", "LAST_ESTATE_ROLE_GRANTOR", "ORIGIN_UNVERIFIED", "PEOPLE_SEARCH_UNUSABLE",
                "PERSON_ALREADY_IN_POOL", "PERSON_HOLDS_ESTATE_ROLES", "PERSON_IN_GROUPS", "USER_NOT_IN_DIRECTORY",
                "GROUP_KEY_TAKEN", "GROUP_KEY_UNUSABLE", "GROUP_NAME_TAKEN", "GROUP_NAME_UNUSABLE", "GROUP_NOT_IN_VIEW",
                "PERSON_NOT_IN_POOL",
                "ENTRY_NOT_IN_VIEW", "VERSION_NOT_IN_VIEW", "ENTRY_NAME_TAKEN", "DRAFT_ALREADY_STARTED",
                "ENTRY_NAME_UNUSABLE", "ENTRY_PURPOSE_UNUSABLE",
                "VERSION_STANDING_REFUSES", "APPROVER_WROTE_VERSION", "VERSION_NOT_PINNABLE", "VERSION_PINS_RETIRED",
                "LAST_MEMBERSHIP_CHANGER", "MEMBER_NOT_IN_VIEW", "PERSON_ALREADY_IN_GROUP",
                "DECLARATION_TOO_DEEP", "FIELD_LIMIT_UNUSABLE", "FIELD_NAME_UNUSABLE", "FIELD_WORDS_UNUSABLE",
                "INSTRUCTION_UNUSABLE", "VERSION_CONTENT_DOES_NOT_HOLD", "DECLARATION_TOO_LARGE",
                "PROSE_DIRECTION_CONTROL", "PROSE_LINE_BREAK_CRLF", "PROSE_TAG_CHARACTER", "CURRENCY_NOT_PRICED",
                "RUN_NOT_IN_VIEW", "RUN_BENEATH_ANOTHER", "RUN_NAME_UNUSABLE", "CEILING_UNUSABLE",
                "CEILING_RAISE_NOT_WAITING", "CEILING_RAISE_ASKED_BY_CALLER", "CEILING_RAISE_ASKED_BY_ANOTHER",
                "CODE_STEP_NOT_PUBLISHED", "STEP_NAME_UNUSABLE", "BINDINGS_PAST_INPUTS", "CASE_TERM_REPEATED",
                "CASES_PAST_TERMS", "CONSTANT_DOES_NOT_FIT", "DRAFT_WRITTEN_SINCE_READ", "STEPS_TOO_MANY",
                "NOTE_UNUSABLE", "TERM_AT_END", "TERM_MEANING_UNUSABLE", "TERM_NOT_IN_VIEW", "TERM_UNUSABLE",
                "LIST_TOO_LARGE", "PROSE_INVISIBLE_CHARACTER",
                "ASK_AGAIN_NOT_OFFERED", "ENTRY_STOPPED", "REASON_MISSING", "REASON_UNUSABLE", "REVIEW_INCOMPLETE",
                "REVIEW_NOT_A_PERSONS", "REVIEW_OWN_PRODUCTION", "RUN_STOPPED", "STEP_MOVED_ON", "STEP_NOT_IN_VIEW",
                "VALUE_DOES_NOT_FIT", "WORKFLOW_NOT_OFFERED", "SERVICE_BUSY", "BODY_SENT_TOO_SLOWLY",
                "CODE_STEP_GIVES_OTHERWISE", "TRY_SENDING_NOT_OFFERED",
        ].toSet()

        and: "and no constant carries a code that is anything other than its own name"
        RefusalCode.values().every { it.code() == it.name() }
    }

    /**
     * A client mints lowercase codes of its own for states that are not a server refusal at all —
     * a request it abandoned, a response that was not a problem document — and tells them apart
     * from a served code by exactly this. A published code in that lowercase space would be read
     * as one of those and drop off the screen, with nobody left to notice.
     */
    def "every published code is screaming snake, so none lands in the space a client mints in"() {
        expect:
        code ==~ /^[A-Z][A-Z0-9_]*$/

        where:
        code << RefusalCode.values()*.code()
    }

    /**
     * The categories are the rulings themselves, not a detail of them: every one of these that
     * answers with something other than a refusal was a deliberate call.
     */
    def "each refusal declares the category that decides its status"() {
        expect:
        refusal.category() == category

        where:
        refusal                                         || category
        RefusalCode.ACT_NOT_PERMITTED                   || PERMISSION_DENIED
        RefusalCode.APPROVER_WROTE_VERSION              || PERMISSION_DENIED
        RefusalCode.ASK_AGAIN_NOT_OFFERED               || CONFLICT
        RefusalCode.BINDINGS_PAST_INPUTS                || INVALID_ARGUMENT
        RefusalCode.BODY_SENT_TOO_SLOWLY                || INVALID_ARGUMENT
        RefusalCode.BODY_UNUSABLE                       || INVALID_ARGUMENT
        RefusalCode.CASE_TERM_REPEATED                  || INVALID_ARGUMENT
        RefusalCode.CASES_PAST_TERMS                    || INVALID_ARGUMENT
        RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER      || PERMISSION_DENIED
        RefusalCode.CEILING_RAISE_ASKED_BY_CALLER       || PERMISSION_DENIED
        RefusalCode.CEILING_RAISE_NOT_WAITING           || CONFLICT
        RefusalCode.CEILING_UNUSABLE                    || INVALID_ARGUMENT
        RefusalCode.CODE_STEP_GIVES_OTHERWISE           || CONFLICT
        RefusalCode.CODE_STEP_NOT_PUBLISHED             || INVALID_ARGUMENT
        RefusalCode.CONSTANT_DOES_NOT_FIT               || INVALID_ARGUMENT
        RefusalCode.CURRENCY_NOT_PRICED                 || INVALID_ARGUMENT
        RefusalCode.DECLARATION_TOO_DEEP                || INVALID_ARGUMENT
        RefusalCode.DECLARATION_TOO_LARGE               || INVALID_ARGUMENT
        RefusalCode.DRAFT_ALREADY_STARTED               || CONFLICT
        RefusalCode.DRAFT_WRITTEN_SINCE_READ            || CONFLICT
        RefusalCode.ENTRY_NAME_TAKEN                    || CONFLICT
        RefusalCode.ENTRY_NAME_UNUSABLE                 || INVALID_ARGUMENT
        RefusalCode.ENTRY_NOT_IN_VIEW                   || NOT_FOUND
        RefusalCode.ENTRY_PURPOSE_UNUSABLE              || INVALID_ARGUMENT
        RefusalCode.ENTRY_STOPPED                       || CONFLICT
        RefusalCode.FIELD_LIMIT_UNUSABLE                || INVALID_ARGUMENT
        RefusalCode.FIELD_NAME_UNUSABLE                 || INVALID_ARGUMENT
        RefusalCode.FIELD_WORDS_UNUSABLE                || INVALID_ARGUMENT
        RefusalCode.GROUP_KEY_TAKEN                     || CONFLICT
        RefusalCode.GROUP_KEY_UNUSABLE                  || INVALID_ARGUMENT
        RefusalCode.GROUP_NAME_TAKEN                    || CONFLICT
        RefusalCode.GROUP_NAME_UNUSABLE                 || INVALID_ARGUMENT
        RefusalCode.GROUP_NOT_IN_VIEW                   || NOT_FOUND
        RefusalCode.INSTRUCTION_UNUSABLE                || INVALID_ARGUMENT
        RefusalCode.LAST_ESTATE_ROLE_GRANTOR            || CONFLICT
        RefusalCode.LAST_MEMBERSHIP_CHANGER             || CONFLICT
        RefusalCode.LIST_CURSOR_UNUSABLE                || INVALID_ARGUMENT
        RefusalCode.LIST_FILTER_UNUSABLE                || INVALID_ARGUMENT
        RefusalCode.LIST_SORT_UNUSABLE                  || INVALID_ARGUMENT
        RefusalCode.LIST_TOO_LARGE                      || INVALID_ARGUMENT
        RefusalCode.MEMBER_NOT_IN_VIEW                  || NOT_FOUND
        RefusalCode.NOTE_UNUSABLE                       || INVALID_ARGUMENT
        RefusalCode.NOT_SIGNED_IN                       || UNAUTHENTICATED
        RefusalCode.ORIGIN_UNVERIFIED                   || PERMISSION_DENIED
        RefusalCode.PARAMETER_UNKNOWN                   || INVALID_ARGUMENT
        RefusalCode.PEOPLE_SEARCH_UNUSABLE              || INVALID_ARGUMENT
        RefusalCode.PERSON_ALREADY_IN_GROUP             || CONFLICT
        RefusalCode.PERSON_ALREADY_IN_POOL              || CONFLICT
        RefusalCode.PERSON_HOLDS_ESTATE_ROLES           || CONFLICT
        RefusalCode.PERSON_IN_GROUPS                    || CONFLICT
        RefusalCode.PERSON_NOT_IN_POOL                  || INVALID_ARGUMENT
        RefusalCode.PERSON_NOT_IN_VIEW                  || NOT_FOUND
        RefusalCode.PROSE_DIRECTION_CONTROL             || INVALID_ARGUMENT
        RefusalCode.PROSE_INVISIBLE_CHARACTER           || INVALID_ARGUMENT
        RefusalCode.PROSE_LINE_BREAK_CRLF               || INVALID_ARGUMENT
        RefusalCode.PROSE_TAG_CHARACTER                 || INVALID_ARGUMENT
        RefusalCode.REASON_MISSING                      || INVALID_ARGUMENT
        RefusalCode.REASON_UNUSABLE                     || INVALID_ARGUMENT
        RefusalCode.REVIEW_INCOMPLETE                   || INVALID_ARGUMENT
        RefusalCode.REVIEW_NOT_A_PERSONS                || CONFLICT
        RefusalCode.REVIEW_OWN_PRODUCTION               || PERMISSION_DENIED
        RefusalCode.RUN_BENEATH_ANOTHER                 || CONFLICT
        RefusalCode.RUN_NAME_UNUSABLE                   || INVALID_ARGUMENT
        RefusalCode.RUN_NOT_IN_VIEW                     || NOT_FOUND
        RefusalCode.RUN_STOPPED                         || CONFLICT
        RefusalCode.SERVICE_BUSY                        || UNAVAILABLE
        RefusalCode.STEP_MOVED_ON                       || CONFLICT
        RefusalCode.STEP_NAME_UNUSABLE                  || INVALID_ARGUMENT
        RefusalCode.STEP_NOT_IN_VIEW                    || NOT_FOUND
        RefusalCode.STEPS_TOO_MANY                      || INVALID_ARGUMENT
        RefusalCode.TERM_AT_END                         || CONFLICT
        RefusalCode.TERM_MEANING_UNUSABLE               || INVALID_ARGUMENT
        RefusalCode.TERM_NOT_IN_VIEW                    || NOT_FOUND
        RefusalCode.TERM_UNUSABLE                       || INVALID_ARGUMENT
        RefusalCode.TRY_SENDING_NOT_OFFERED             || CONFLICT
        RefusalCode.USER_NOT_IN_DIRECTORY               || INVALID_ARGUMENT
        RefusalCode.VALUE_DOES_NOT_FIT                  || INVALID_ARGUMENT
        RefusalCode.VERSION_CONTENT_DOES_NOT_HOLD       || CONFLICT
        RefusalCode.VERSION_NOT_IN_VIEW                 || NOT_FOUND
        RefusalCode.VERSION_NOT_PINNABLE                || INVALID_ARGUMENT
        RefusalCode.VERSION_PINS_RETIRED                || CONFLICT
        RefusalCode.VERSION_STANDING_REFUSES            || CONFLICT
        RefusalCode.WORKFLOW_NOT_OFFERED                || CONFLICT
    }

    /**
     * The ordering rule the class Javadoc states, in the one form a test can hold it: a constant
     * that withholds a row's existence must not sit in a category that concedes it.
     */
    def "every NOT_IN_VIEW refusal answers NOT_FOUND, so no refusal confirms a row it is hiding"() {
        expect:
        RefusalCode.values().findAll { it.name().endsWith("_NOT_IN_VIEW") }*.category().toSet() == [NOT_FOUND].toSet()

        and: "and nothing else claims that category, which would hide a row no rule says to hide"
        RefusalCode.values().findAll { it.category() == NOT_FOUND }.every { it.name().endsWith("_NOT_IN_VIEW") }
    }
}
