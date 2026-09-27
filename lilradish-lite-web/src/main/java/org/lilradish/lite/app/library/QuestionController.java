package org.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.declaration.AskedField;
import org.lilradish.lite.domain.declaration.DeclarationSide;
import org.lilradish.lite.domain.declaration.Demands;
import org.lilradish.lite.domain.declaration.FieldKind;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.referencelist.ListNote;
import org.lilradish.lite.domain.text.ProseRefusal;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupMembershipRequired;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.QueryParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A question version, read by any member of its group; its instruction and either half written, only as a
 * draft, by one who may write an entry, naming the revision the page read, each answered by the version as the
 * change writing it then reads it.
 */
@RestController
final class QuestionController {

    /* The segment is the question kind's own, which a spec holds level with EntryKind's. */
    static final String VERSION = ActAdmission.IN_A_GROUP + "/questions/{entryId}/versions/{versionId}";

    static final String INSTRUCTION = VERSION + "/instruction";

    static final String HALF = VERSION + "/{side:takes|gives}";

    private static final String INSTRUCTION_MEMBER = "instruction";

    private static final String FIELDS_MEMBER = "fields";

    /** Room for the longest instruction with every character escaped as a pair, and its members. */
    private static final int LARGEST_INSTRUCTION = 131072;

    private static final String INSTRUCTION_REFUSED =
            "This takes a JSON object holding the revision read and an instruction, or null, and nothing else.";

    private static final String HALF_REFUSED =
            "This takes a JSON object holding the revision read and the half's fields, and nothing else.";

    private final Questions questions;

    private final QuestionDrafts drafts;

    QuestionController(Questions questions, QuestionDrafts drafts) {
        this.questions = questions;
        this.drafts = drafts;
    }

    @GetMapping(VERSION)
    @GroupMembershipRequired
    QuestionAnswer read(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request) {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        QueryParameters.requireNone(request, VersionAddress.PARAMETER_REFUSED);
        return answer(questions.read(addressed.group(), addressed.entry(), addressed.version(), addressed.caller()));
    }

    @PutMapping(INSTRUCTION)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    QuestionAnswer instruct(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_INSTRUCTION, INSTRUCTION_REFUSED);
        JsonNode said = body.path(INSTRUCTION_MEMBER);
        if (!body.isObject() || body.size() != 2 || !(said.isString() || said.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, INSTRUCTION_REFUSED);
        }
        int seen = SeenRevision.in(body, INSTRUCTION_REFUSED);
        Instruction instruction = said.isString() ? instructionOf(said.asString()) : null;
        return answer(drafts.instruct(
                addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), seen, instruction));
    }

    @PutMapping(HALF)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    QuestionAnswer declare(
            @PathVariable String entryId,
            @PathVariable String versionId,
            @PathVariable String side,
            HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        DeclarationSide declared = VersionAddress.sideAt(side);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, VersionAddress.LARGEST_HALF, HALF_REFUSED);
        if (!body.isObject() || body.size() != 2 || !body.has(FIELDS_MEMBER)) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, HALF_REFUSED);
        }
        int seen = SeenRevision.in(body, HALF_REFUSED);
        DeclarationBody.Sent sent =
                DeclarationBody.read(body.get(FIELDS_MEMBER), declared, Demands.ofQuestion(declared));
        return answer(drafts.declare(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                seen,
                sent.half(),
                sent.readAs()));
    }

    private static Instruction instructionOf(String said) {
        ProseRefusal refusal = Instruction.refusalOf(said);
        if (refusal == null) {
            return new Instruction(said);
        }
        throw LibraryRefusal.refusingProse(refusal, LibraryRefusal.INSTRUCTION_UNUSABLE);
    }

    static QuestionAnswer answer(Questions.QuestionView view) {
        StoredQuestion stored = view.stored();
        Instruction instruction = stored.instruction();
        List<AskedField> added = view.added();
        return new QuestionAnswer(
                view.revision(),
                instruction == null ? null : instruction.value(),
                DeclarationAnswers.of(stored.takes(), view.pins()),
                DeclarationAnswers.of(stored.gives(), view.pins()),
                added == null
                        ? null
                        : added.stream().map(QuestionController::addedAnswer).toList(),
                view.pinnable().stream().map(DeclarationAnswers::offered).toList());
    }

    private static AddedAnswer addedAnswer(AskedField field) {
        OfferedTerms offered = field.terms();
        ListNote note = offered == null ? null : offered.note();
        return new AddedAnswer(
                field.name().value(),
                field.kind().published(),
                field.longest(),
                field.most() != null,
                field.most(),
                field.mustBeGiven(),
                offered == null
                        ? null
                        : offered.terms().stream()
                                .map(each -> new TermAnswer(
                                        each.term().value(), each.meaning().value()))
                                .toList(),
                note == null ? null : note.value(),
                field.confidenceAsked(),
                field.kind() == FieldKind.FIELDS
                        ? field.fields().stream()
                                .map(QuestionController::addedAnswer)
                                .toList()
                        : null);
    }

    /**
     * @param revision what a write sends back as the one it read
     * @param instruction absent where the version says nothing yet
     * @param takes what it takes, in declared order
     * @param gives what it gives back, in declared order
     * @param added what a model is told of what it gives back, absent where that half is not whole yet
     * @param lists every reference list version of the group's in service, which is all a field of terms may pin
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QuestionAnswer(
            int revision,
            @Nullable String instruction,
            List<DeclarationAnswers.FieldAnswer> takes,
            List<DeclarationAnswers.FieldAnswer> gives,
            @Nullable List<AddedAnswer> added,
            List<DeclarationAnswers.OfferedAnswer> lists) {}

    /**
     * @param mustBeGiven whether it is told it may not give nothing
     * @param confidence whether a value is asked to come with how sure its maker was, and never above what
     * @param fields only on a field holding fields, in declared order
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AddedAnswer(
            String name,
            String kind,
            @Nullable Integer longest,
            boolean many,
            @Nullable Integer most,
            boolean mustBeGiven,
            @Nullable List<TermAnswer> terms,
            @Nullable String note,
            boolean confidence,
            @Nullable List<AddedAnswer> fields) {}

    record TermAnswer(String term, String meaning) {}
}
