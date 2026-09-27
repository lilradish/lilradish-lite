package org.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.referencelist.Term;
import org.lilradish.lite.domain.referencelist.TermMeaning;
import org.lilradish.lite.domain.text.ProseRefusal;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.MintedIdentifiers;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A reference list version, read by any member of its group; its draft's note and terms written by one who may
 * write an entry, one change at a time, each answered with the version as that change left it.
 */
@RestController
final class ReferenceListController {

    /* The segment and the ways are the vocabularies' own, which a spec holds level with EntryKind's and Way's. */
    static final String VERSION = ActAdmission.IN_A_GROUP + "/reference-lists/{entryId}/versions/{versionId}";

    static final String NOTE = VERSION + "/note";

    static final String TERMS = VERSION + "/terms";

    static final String TERM = TERMS + "/{termId}";

    static final String REMOVAL = TERM + "/removal";

    static final String MOVE = TERM + "/{way:up|down}";

    private static final String NOTE_MEMBER = "note";

    private static final String TERM_MEMBER = "term";

    private static final String MEANING_MEMBER = "meaning";

    /** Room for the longest note with every character escaped as a pair, and its members. */
    private static final int LARGEST_NOTE = 32768;

    /** Room for the longest term and meaning with every character escaped as a pair, and their members. */
    private static final int LARGEST_TERM = 8192;

    private static final int LARGEST_REVISION = 1024;

    private static final String NOTE_REFUSED =
            "This takes a JSON object holding the revision read and a note, or null, and nothing else.";

    private static final String TERM_REFUSED =
            "This takes a JSON object holding the revision read, a term and what it means, and nothing else.";

    private static final String REVISION_REFUSED =
            "This takes a JSON object holding the revision read, and nothing else.";

    private static final Map<String, ReferenceListDrafts.Way> WAYS = Arrays.stream(ReferenceListDrafts.Way.values())
            .collect(Collectors.toUnmodifiableMap(way -> way.name().toLowerCase(Locale.ROOT), Function.identity()));

    private final ReferenceLists lists;

    private final ReferenceListDrafts drafts;

    ReferenceListController(ReferenceLists lists, ReferenceListDrafts drafts) {
        this.lists = lists;
        this.drafts = drafts;
    }

    @GetMapping(VERSION)
    @GroupMembershipRequired
    ReferenceListAnswer read(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request) {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        QueryParameters.requireNone(request, VersionAddress.PARAMETER_REFUSED);
        return answer(lists.read(addressed.group(), addressed.entry(), addressed.version(), addressed.caller()));
    }

    @PutMapping(NOTE)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    ReferenceListAnswer note(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_NOTE, NOTE_REFUSED);
        JsonNode said = body.path(NOTE_MEMBER);
        if (!body.isObject() || body.size() != 2 || !(said.isString() || said.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, NOTE_REFUSED);
        }
        int seen = SeenRevision.in(body, NOTE_REFUSED);
        ListNote note = said.isString() ? noteOf(said.asString()) : null;
        return answer(
                drafts.note(addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), seen, note));
    }

    @PostMapping(TERMS)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    ReferenceListAnswer add(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        Worded worded = wordedIn(request);
        return answer(drafts.add(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                worded.seen(),
                worded.term(),
                worded.meaning()));
    }

    @PutMapping(TERM)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    ReferenceListAnswer edit(
            @PathVariable String entryId,
            @PathVariable String versionId,
            @PathVariable String termId,
            HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        UUID term = termAt(termId);
        ChangeRequests.requireNoParameter(request);
        Worded worded = wordedIn(request);
        return answer(drafts.edit(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                worded.seen(),
                term,
                worded.term(),
                worded.meaning()));
    }

    @PostMapping(REMOVAL)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    ReferenceListAnswer remove(
            @PathVariable String entryId,
            @PathVariable String versionId,
            @PathVariable String termId,
            HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        UUID term = termAt(termId);
        ChangeRequests.requireNoParameter(request);
        int seen = revisionIn(request);
        return answer(drafts.remove(
                addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), seen, term));
    }

    @PostMapping(MOVE)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    ReferenceListAnswer move(
            @PathVariable String entryId,
            @PathVariable String versionId,
            @PathVariable String termId,
            @PathVariable String way,
            HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        UUID term = termAt(termId);
        ReferenceListDrafts.Way moved = WAYS.get(way);
        if (moved == null) {
            throw new IllegalStateException("No way a term moves is addressed as " + way);
        }
        ChangeRequests.requireNoParameter(request);
        int seen = revisionIn(request);
        return answer(drafts.move(
                addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), seen, term, moved));
    }

    /** The term first, then what it means, each refused under its own code once the body holds the three. */
    private static Worded wordedIn(HttpServletRequest request) throws IOException {
        JsonNode body = JsonBody.read(request, LARGEST_TERM, TERM_REFUSED);
        JsonNode term = body.path(TERM_MEMBER);
        JsonNode meaning = body.path(MEANING_MEMBER);
        if (!body.isObject() || body.size() != 3 || !term.isString() || !meaning.isString()) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, TERM_REFUSED);
        }
        int seen = SeenRevision.in(body, TERM_REFUSED);
        String termSaid = term.asString();
        ProseRefusal termRefused = Term.refusalOf(termSaid);
        if (termRefused != null) {
            throw LibraryRefusal.refusingProse(termRefused, LibraryRefusal.TERM_UNUSABLE);
        }
        String meaningSaid = meaning.asString();
        ProseRefusal meaningRefused = TermMeaning.refusalOf(meaningSaid);
        if (meaningRefused != null) {
            throw LibraryRefusal.refusingProse(meaningRefused, LibraryRefusal.TERM_MEANING_UNUSABLE);
        }
        return new Worded(seen, new Term(termSaid), new TermMeaning(meaningSaid));
    }

    private static int revisionIn(HttpServletRequest request) throws IOException {
        JsonNode body = JsonBody.read(request, LARGEST_REVISION, REVISION_REFUSED);
        if (!body.isObject() || body.size() != 1) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, REVISION_REFUSED);
        }
        return SeenRevision.in(body, REVISION_REFUSED);
    }

    private static ListNote noteOf(String said) {
        ProseRefusal refusal = ListNote.refusalOf(said);
        if (refusal == null) {
            return new ListNote(said);
        }
        throw LibraryRefusal.refusingProse(refusal, LibraryRefusal.NOTE_UNUSABLE);
    }

    private static ReferenceListAnswer answer(ReferenceLists.ListView view) {
        ListNote note = view.note();
        return new ReferenceListAnswer(
                view.revision(),
                note == null ? null : note.value(),
                view.terms().stream()
                        .map(held -> new ListedTermAnswer(
                                held.termId(),
                                held.term().value(),
                                held.meaning().value(),
                                held.alikeEarlier() ? Boolean.TRUE : null))
                        .toList());
    }

    private static UUID termAt(String spelled) {
        return MintedIdentifiers.read(spelled).orElseThrow(LibraryRefusal.TERM_NOT_IN_VIEW::raised);
    }

    private record Worded(int seen, Term term, TermMeaning meaning) {}

    /**
     * @param revision what a write sends back as the one it read
     * @param note absent where the list says nothing on choosing among its terms
     * @param terms in the order the version gives them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ReferenceListAnswer(int revision, @Nullable String note, List<ListedTermAnswer> terms) {}

    /**
     * @param termId the key a write to it sends back, which it keeps until it is removed
     * @param alikeEarlier present, and true, only where a term before it is this one as submitting compares them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ListedTermAnswer(
            UUID termId,
            String term,
            String meaning,
            @Nullable Boolean alikeEarlier) {}
}
