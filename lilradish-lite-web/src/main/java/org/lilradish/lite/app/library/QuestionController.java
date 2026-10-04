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
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.inference.Envelope;
import org.lilradish.lite.domain.text.CharacterStanding;
import org.lilradish.lite.domain.text.Legibility;
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

    static final String DRAWING = VERSION + "/drawing";

    static final String HALF = VERSION + "/{side:takes|gives}";

    private static final String INSTRUCTION_MEMBER = "instruction";

    private static final String DRAWING_MEMBER = "drawing";

    private static final String FIELDS_MEMBER = "fields";

    /** Room for the longest instruction or drawing with every character escaped as a pair, and its members. */
    private static final int LARGEST_PROSE_REQUEST_BYTES = 131072;

    private static final String INSTRUCTION_REFUSED =
            "This takes a JSON object holding the revision read and an instruction, or null, and nothing else.";

    private static final String DRAWING_REFUSED =
            "This takes a JSON object holding the revision read and how it is drawn, or null, and nothing else.";

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
        JsonNode body = JsonBody.read(request, LARGEST_PROSE_REQUEST_BYTES, INSTRUCTION_REFUSED);
        JsonNode said = body.path(INSTRUCTION_MEMBER);
        if (!body.isObject() || body.size() != 2 || !(said.isString() || said.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, INSTRUCTION_REFUSED);
        }
        int seen = SeenRevision.in(body, INSTRUCTION_REFUSED);
        Instruction instruction = said.isString() ? instructionOf(said.asString()) : null;
        return answer(drafts.instruct(
                addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), seen, instruction));
    }

    @PutMapping(DRAWING)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    QuestionAnswer draw(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_PROSE_REQUEST_BYTES, DRAWING_REFUSED);
        JsonNode drawing = body.path(DRAWING_MEMBER);
        if (!body.isObject() || body.size() != 2 || !(drawing.isString() || drawing.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, DRAWING_REFUSED);
        }
        int seen = SeenRevision.in(body, DRAWING_REFUSED);
        return answer(drafts.draw(
                addressed.group(),
                addressed.entry(),
                addressed.version(),
                addressed.caller(),
                seen,
                drawing.isString() ? drawingOf(drawing.asString()) : null));
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

    private static String drawingOf(String said) {
        if (said.contains("\r\n")) {
            throw LibraryRefusal.PROSE_LINE_BREAK_CRLF.raised();
        }
        try {
            for (int at = 0; at < said.length(); ) {
                int point = said.codePointAt(at);
                CharacterStanding standing = CharacterStanding.of(point);
                if (standing == CharacterStanding.OTHER_CONTROL
                        || standing == CharacterStanding.LINE_OR_PARAGRAPH_SEPARATOR
                        || standing == CharacterStanding.SURROGATE) {
                    throw new IllegalArgumentException("Drawing holds a character that prose cannot hold");
                }
                at += Character.charCount(point);
            }
            Legibility.requireSomethingVisibleInProse(said, "Drawing");
            Legibility.requireWithinMaximumLength(said, 8192, "Drawing");
        } catch (IllegalArgumentException refused) {
            throw LibraryRefusal.DRAWING_UNUSABLE.raised();
        }
        if (DrawingDepth.tooDeep(said)) {
            throw LibraryRefusal.DRAWING_TOO_DEEP.raised();
        }
        return said;
    }

    static QuestionAnswer answer(Questions.QuestionView view) {
        StoredQuestion stored = view.stored();
        Instruction instruction = stored.instruction();
        List<AskedField> added = view.added();
        return new QuestionAnswer(
                view.revision(),
                instruction == null ? null : instruction.value(),
                stored.drawing(),
                DeclarationAnswers.of(stored.takes(), view.pins()),
                DeclarationAnswers.of(stored.gives(), view.pins()),
                added == null ? null : Envelope.toProduce(added),
                view.pinnable().stream().map(DeclarationAnswers::offered).toList());
    }

    /**
     * @param revision what a write sends back as the one it read
     * @param instruction absent where the version says nothing yet
     * @param drawing absent where the version does not lay out its inputs for a person
     * @param takes what it takes, in declared order
     * @param gives what it gives back, in declared order
     * @param added what a model is told of what it gives back, absent where that half is not whole yet
     * @param lists every reference list version of the group's in service, which is all a field of terms may pin
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QuestionAnswer(
            int revision,
            @Nullable String instruction,
            @Nullable String drawing,
            List<DeclarationAnswers.FieldAnswer> takes,
            List<DeclarationAnswers.FieldAnswer> gives,
            @Nullable String added,
            List<DeclarationAnswers.OfferedAnswer> lists) {}
}
