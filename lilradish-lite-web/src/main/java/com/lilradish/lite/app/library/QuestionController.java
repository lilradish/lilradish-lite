package com.lilradish.lite.app.library;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.lilradish.lite.domain.declaration.AskedField;
import com.lilradish.lite.domain.declaration.DeclarationSide;
import com.lilradish.lite.domain.declaration.Demands;
import com.lilradish.lite.domain.declaration.Instruction;
import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.inference.Envelope;
import com.lilradish.lite.domain.text.Identifiers;
import com.lilradish.lite.web.ActAdmission;
import com.lilradish.lite.web.ChangeRequests;
import com.lilradish.lite.web.GroupMembershipRequired;
import com.lilradish.lite.web.GroupPermissionRequired;
import com.lilradish.lite.web.JsonBody;
import com.lilradish.lite.web.QueryParameters;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
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

    /* The segment is the question kind's own, kept level with EntryKind's. */
    static final String VERSION = ActAdmission.IN_A_GROUP + "/questions/{entryId}/versions/{versionId}";

    static final String INSTRUCTION = VERSION + "/instruction";

    static final String DRAWING = VERSION + "/drawing";

    static final String HALF = VERSION + "/{side:takes|gives}";

    static final String SEARCHING = VERSION + "/searching";

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

    private static final String SEARCHING_REFUSED =
            "This takes a JSON object holding the revision read and a way to search, or null, and nothing else.";

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
        Instruction instruction = said.isString() ? QuestionDrafts.instructionOf(said.asString()) : null;
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
                drawing.isString() ? QuestionDrafts.drawingOf(drawing.asString()) : null));
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

    @PutMapping(SEARCHING)
    @GroupPermissionRequired(GroupPermission.AUTHOR_ENTRY)
    QuestionAnswer searching(@PathVariable String entryId, @PathVariable String versionId, HttpServletRequest request)
            throws IOException {
        VersionAddress addressed = VersionAddress.of(entryId, versionId, request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, 1024, SEARCHING_REFUSED);
        JsonNode way = body.path("searchWay");
        if (!body.isObject() || body.size() != 2 || !(way.isString() || way.isNull())) {
            throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, SEARCHING_REFUSED);
        }
        int seen = SeenRevision.in(body, SEARCHING_REFUSED);
        String name = null;
        if (way.isString()) {
            try {
                name = Identifiers.requireText(way.asString(), "Search way");
            } catch (IllegalArgumentException refused) {
                throw new ApiErrorException(RefusalCode.BODY_UNUSABLE, SEARCHING_REFUSED);
            }
        }
        return answer(drafts.searching(
                addressed.group(), addressed.entry(), addressed.version(), addressed.caller(), seen, name));
    }

    static QuestionAnswer answer(Questions.QuestionView view) {
        StoredQuestion stored = view.stored();
        Instruction instruction = stored.instruction();
        List<AskedField> added = view.added();
        return new QuestionAnswer(
                view.revision(),
                instruction == null ? null : instruction.value(),
                stored.drawing(),
                stored.searchWay(),
                view.searchShapeNeedsRefresh(),
                view.searchWays().stream()
                        .map(way -> new SearchWayAnswer(
                                way.name(),
                                way.requiredTool().value(),
                                DeclarationAnswers.of(StoredDeclarations.ofSearchWay(way), view.pins())))
                        .toList(),
                DeclarationAnswers.of(stored.takes(), view.pins()),
                DeclarationAnswers.of(stored.gives(), view.pins()),
                added == null ? null : Envelope.toProduce(added, instruction));
    }

    /**
     * @param revision what a write sends back as the one it read
     * @param instruction absent where the version says nothing yet
     * @param drawing absent where the version does not lay out its inputs for a person
     * @param searchWay absent where the question is not for searching
     * @param searchShapeNeedsRefresh whether this draft needs an explicit write to refresh its answer shape
     * @param searchWays what this release offers to mark a question for searching
     * @param takes what it takes, in declared order
     * @param gives what it gives back, in declared order
     * @param added what a model is told of what it gives back, absent where that half is not whole yet
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record QuestionAnswer(
            int revision,
            @Nullable String instruction,
            @Nullable String drawing,
            @Nullable String searchWay,
            boolean searchShapeNeedsRefresh,
            List<SearchWayAnswer> searchWays,
            List<DeclarationAnswers.FieldAnswer> takes,
            List<DeclarationAnswers.FieldAnswer> gives,
            @Nullable String added) {}

    /**
     * @param name the way named on a question marked for searching
     * @param tool what a model using this way must offer
     * @param gives the answer shape this way requires
     */
    record SearchWayAnswer(String name, String tool, List<DeclarationAnswers.FieldAnswer> gives) {}
}
