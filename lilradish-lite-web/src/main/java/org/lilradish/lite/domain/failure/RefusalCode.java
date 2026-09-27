package org.lilradish.lite.domain.failure;

import static org.libprunus.core.error.ErrorCategory.CONFLICT;
import static org.libprunus.core.error.ErrorCategory.INVALID_ARGUMENT;
import static org.libprunus.core.error.ErrorCategory.NOT_FOUND;
import static org.libprunus.core.error.ErrorCategory.PERMISSION_DENIED;
import static org.libprunus.core.error.ErrorCategory.UNAUTHENTICATED;
import static org.libprunus.core.error.ErrorCategory.UNAVAILABLE;

import org.libprunus.core.error.ErrorCategory;
import org.libprunus.core.error.ErrorCode;

/**
 * Why a refusal happened. The category is what a transport turns into a status; the constant name
 * is the published code, so renaming one breaks a contract rather than refactoring one.
 *
 * <p>Only refusals belong here. An infrastructure fault is not one — carried as an
 * {@code ApiErrorException} it would publish both its code and its message on the 500, while the
 * library's own unhandled path deliberately publishes neither.
 *
 * <p>No type here enforces the ordering it depends on: a {@code _NOT_IN_VIEW} constant withholds
 * that a row exists and every other refusal concedes it, so visibility is decided before eligibility.
 */
public enum RefusalCode implements ErrorCode {
    /**
     * One constant, not one per act: per-act constants would be a hand-kept copy of the surface's
     * own list, stale the first time an act is added. A permission inside a group, refused to somebody
     * holding a role there, is refused as an act is, for the same reason.
     */
    ACT_NOT_PERMITTED(PERMISSION_DENIED),

    /** Whoever started a version or changed it while a draft, whatever else they hold. */
    APPROVER_WROTE_VERSION(PERMISSION_DENIED),

    /** A next try only a person's answer may make: code the release lets run no more, or a try already asked. */
    ASK_AGAIN_NOT_OFFERED(CONFLICT),

    /** More bindings than what they fill declares fields, or than any declaration holds where that is not known. */
    BINDINGS_PAST_INPUTS(INVALID_ARGUMENT),

    /** A large body that arrived slower than it may be read: nothing was handled, and it may be sent again. */
    BODY_SENT_TOO_SLOWLY(INVALID_ARGUMENT),

    /**
     * A body where none is taken, or one that is not UTF-8 JSON holding exactly the members the address
     * takes, each a value its type admits. One code for all of them: a reader's own side sends none.
     */
    BODY_UNUSABLE(INVALID_ARGUMENT),

    /** Two cases of one route on the same term. */
    CASE_TERM_REPEATED(INVALID_ARGUMENT),

    /** More cases than the list a route chooses by offers terms, and a fallback; any list's most where none is known. */
    CASES_PAST_TERMS(INVALID_ARGUMENT),

    CEILING_RAISE_ASKED_BY_ANOTHER(PERMISSION_DENIED),

    /** Whoever asked for a raise, whatever else they hold. */
    CEILING_RAISE_ASKED_BY_CALLER(PERMISSION_DENIED),

    /** Decided already, asked since, or never asked under that identifier: the run is to be read again. */
    CEILING_RAISE_NOT_WAITING(CONFLICT),

    CEILING_UNUSABLE(INVALID_ARGUMENT),

    /** An answer to a code step as the release now declares it, which a later step or the workflow reads otherwise. */
    CODE_STEP_GIVES_OTHERWISE(CONFLICT),

    /** A code step no migration published to this group, whether or not this release holds one by that name. */
    CODE_STEP_NOT_PUBLISHED(INVALID_ARGUMENT),

    /** A constant no store keeps as written: a number past 38 digits, a NUL character, or too much text. */
    CONSTANT_DOES_NOT_FIT(INVALID_ARGUMENT),

    /** A change to a currency no model is priced in now; never raised for the one already chosen. */
    CURRENCY_NOT_PRICED(INVALID_ARGUMENT),

    /** A field held deeper than any half is, or so deep that the names down to it could never be bound through. */
    DECLARATION_TOO_DEEP(INVALID_ARGUMENT),

    /** More fields in one half than any half is written with. */
    DECLARATION_TOO_LARGE(INVALID_ARGUMENT),

    /** An entry holds one version that is a draft or submitted at a time. */
    DRAFT_ALREADY_STARTED(CONFLICT),

    /** The draft was written since the revision a write names was read, so it would replace what was not read. */
    DRAFT_WRITTEN_SINCE_READ(CONFLICT),

    /** Held by another entry of the same kind in the same group, whatever case either was typed in. */
    ENTRY_NAME_TAKEN(CONFLICT),
    ENTRY_NAME_UNUSABLE(INVALID_ARGUMENT),

    /** An identifier no entry of that kind in that group holds, whether or not one elsewhere does. */
    ENTRY_NOT_IN_VIEW(NOT_FOUND),

    ENTRY_PURPOSE_UNUSABLE(INVALID_ARGUMENT),

    /** What a step runs, or the workflow it is a step of, is stopped: no try is made on it until it is let go. */
    ENTRY_STOPPED(CONFLICT),

    /** How long, how many, and the confidence a value stands above: one code for every limit a field says. */
    FIELD_LIMIT_UNUSABLE(INVALID_ARGUMENT),
    FIELD_NAME_UNUSABLE(INVALID_ARGUMENT),

    /** A field's label or its help, which are one rule at two bounds. */
    FIELD_WORDS_UNUSABLE(INVALID_ARGUMENT),

    /** Held by another group whatever case either was typed in, keys being held in capitals. */
    GROUP_KEY_TAKEN(CONFLICT),
    GROUP_KEY_UNUSABLE(INVALID_ARGUMENT),
    GROUP_NAME_TAKEN(CONFLICT),
    GROUP_NAME_UNUSABLE(INVALID_ARGUMENT),

    /**
     * An identifier no group holds, an address that is no identifier at all, and, asked inside a group,
     * one the caller holds no role in whatever they hold in the estate: told apart, the last would say
     * which groups exist to somebody who may see only their own.
     */
    GROUP_NOT_IN_VIEW(NOT_FOUND),

    INSTRUCTION_UNUSABLE(INVALID_ARGUMENT),

    /**
     * Nothing inside this system grants the first estate role, so nothing may withdraw the last one that
     * lets anybody grant one.
     */
    LAST_ESTATE_ROLE_GRANTOR(CONFLICT),

    /**
     * A group left with nobody who may change its membership could never gain anybody who may, so neither
     * the last role letting anybody nor the last member holding one is taken away.
     */
    LAST_MEMBERSHIP_CHANGER(CONFLICT),

    /**
     * Minted for another query of the list, or not minted here at all. One code for both: either
     * way the reader's next step is the list's first page.
     */
    LIST_CURSOR_UNUSABLE(INVALID_ARGUMENT),
    LIST_FILTER_UNUSABLE(INVALID_ARGUMENT),
    LIST_SORT_UNUSABLE(INVALID_ARGUMENT),

    /** A term added to a reference list version already holding as many as one holds. */
    LIST_TOO_LARGE(INVALID_ARGUMENT),

    /**
     * Somebody not in the group now, an identifier nobody holds, and an address that is no identifier at
     * all: told apart, they would say whom the pool holds to somebody who may see only this group.
     */
    MEMBER_NOT_IN_VIEW(NOT_FOUND),

    /** A reference list's note on choosing among its terms. */
    NOTE_UNUSABLE(INVALID_ARGUMENT),

    /**
     * Nothing said who is calling — nothing arrived, or what arrived names no user. One code
     * for both, because which of the two it was is a fact about the carrier and handing it back
     * hands it to whoever can change what the carrier sends. Not an invalid argument either: the
     * reader typed nothing, so a complaint about their input is a lie about where the fault is.
     */
    NOT_SIGNED_IN(UNAUTHENTICATED),

    /**
     * A request changing something that neither the browser nor its origin says came from this
     * application's own pages. Raised before anybody is identified, so it reads alike to everybody.
     */
    ORIGIN_UNVERIFIED(PERMISSION_DENIED),

    /**
     * A parameter the address does not take, on a read or a change alike. Ignored, a misspelt one would
     * answer as though it had been honoured.
     */
    PARAMETER_UNKNOWN(INVALID_ARGUMENT),

    /** Something typed that a user number or a name could be found by, which spaces alone are not. */
    PEOPLE_SEARCH_UNUSABLE(INVALID_ARGUMENT),

    /** Brought in again while holding a role here, which a role given is the way to add to. */
    PERSON_ALREADY_IN_GROUP(CONFLICT),

    PERSON_ALREADY_IN_POOL(CONFLICT),

    /** Somebody who still holds what the estate grants, or stands in a group, is not taken out. */
    PERSON_HOLDS_ESTATE_ROLES(CONFLICT),
    PERSON_IN_GROUPS(CONFLICT),

    /**
     * Somebody a change's body names who is not in the pool now, however that came to be: one code for
     * every way, for the reason {@link #PERSON_NOT_IN_VIEW} gives.
     */
    PERSON_NOT_IN_POOL(INVALID_ARGUMENT),

    /**
     * An identifier nobody holds, somebody no longer in the pool, an actor that is no person, and an
     * address that is no identifier at all: told apart, they would say whom this system has known.
     */
    PERSON_NOT_IN_VIEW(NOT_FOUND),

    /** Prose sent to a model holds a character that reorders how the text around it is shown. */
    PROSE_DIRECTION_CONTROL(INVALID_ARGUMENT),

    /** A term holds a character that shows nothing, so two terms could read as one. */
    PROSE_INVISIBLE_CHARACTER(INVALID_ARGUMENT),

    /** Prose breaks its lines with a carriage return, said apart because nothing on screen shows one. */
    PROSE_LINE_BREAK_CRLF(INVALID_ARGUMENT),

    /** Prose sent to a model holds a character of the tag block, which shows nothing and a model still reads. */
    PROSE_TAG_CHARACTER(INVALID_ARGUMENT),

    /** A refusal or an answer given with no reason, or one holding nothing that shows, which counts as none. */
    REASON_MISSING(INVALID_ARGUMENT),

    REASON_UNUSABLE(INVALID_ARGUMENT),

    /** A review decides every value waiting on it, and names nothing else. */
    REVIEW_INCOMPLETE(INVALID_ARGUMENT),

    /** The values wait on the model the step names to review, which nobody reviews in its place. */
    REVIEW_NOT_A_PERSONS(CONFLICT),

    /** Whoever produced what waits on a review, whatever else they hold. */
    REVIEW_OWN_PRODUCTION(PERMISSION_DENIED),

    /** A run beneath another is stopped, opened and changed only with the run at the top. */
    RUN_BENEATH_ANOTHER(CONFLICT),

    RUN_NAME_UNUSABLE(INVALID_ARGUMENT),

    /** A run of another group, one the caller may not read, and no identifier at all are one refusal. */
    RUN_NOT_IN_VIEW(NOT_FOUND),

    /** A stopped run takes no answer, review or try until it is opened again. */
    RUN_STOPPED(CONFLICT),

    /**
     * Another large body is being read or handled: nothing was read or changed, and the same request may be
     * sent again shortly.
     */
    SERVICE_BUSY(UNAVAILABLE),

    /** The try named is not where the step is now: the step is to be read again. */
    STEP_MOVED_ON(CONFLICT),

    STEP_NAME_UNUSABLE(INVALID_ARGUMENT),

    /** A step no version of the run holds, and no identifier at all, are one refusal. */
    STEP_NOT_IN_VIEW(NOT_FOUND),

    /** More steps than a workflow version holds. */
    STEPS_TOO_MANY(INVALID_ARGUMENT),

    /** Moved past the first place or the last, where no term stands to change places with it. */
    TERM_AT_END(CONFLICT),

    TERM_MEANING_UNUSABLE(INVALID_ARGUMENT),

    /** A key no term of that draft holds, whether or not a term elsewhere does. */
    TERM_NOT_IN_VIEW(NOT_FOUND),

    TERM_UNUSABLE(INVALID_ARGUMENT),

    /** A step not held back or failed on anything sending its try again to its model may settle. */
    TRY_SENDING_NOT_OFFERED(CONFLICT),

    /** Says only that the directory holds no such user, which is all that asking could learn. */
    USER_NOT_IN_DIRECTORY(INVALID_ARGUMENT),

    /** Values sent for fields they do not fit, each named beside the code. */
    VALUE_DOES_NOT_FIT(INVALID_ARGUMENT),

    /**
     * What a version's stored content holds is refused at submitting, every place named beside the code. A
     * conflict and not an argument: the request names only the version, and what is wrong is what it holds.
     */
    VERSION_CONTENT_DOES_NOT_HOLD(CONFLICT),

    /** An identifier no version of that entry holds, whether or not a version elsewhere does. */
    VERSION_NOT_IN_VIEW(NOT_FOUND),

    /**
     * Not this group's, not in service now, or not of the kind the pin takes: one code for every way, another
     * group's versions and what does not exist among them.
     */
    VERSION_NOT_PINNABLE(INVALID_ARGUMENT),

    /** Pins a version retired since it was pinned, which nothing new may pin. */
    VERSION_PINS_RETIRED(CONFLICT),

    /** Standing moves one way, withdrawing the one step back, so an act from another standing is refused. */
    VERSION_STANDING_REFUSES(CONFLICT),

    /** Not a version in service of an unstopped workflow of this group's: another group's and none at all alike. */
    WORKFLOW_NOT_OFFERED(CONFLICT);

    private final ErrorCategory category;

    RefusalCode(ErrorCategory category) {
        this.category = category;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public ErrorCategory category() {
        return category;
    }
}
