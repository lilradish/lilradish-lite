package org.lilradish.lite.app.groupregister;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.EstateAct;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupKey;
import org.lilradish.lite.domain.identity.GroupName;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.web.ActRequired;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.JsonBody;
import org.lilradish.lite.web.MintedIdentifiers;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Creating a group and renaming one. Each answers with the group as the register now reads it; a group
 * created is answered with no address, no group being read on its own.
 *
 * <p>A group is addressed by the identifier this system minted for it, read as {@link MintedIdentifiers}
 * reads one, and anything else addresses no group. A key is taken only by creating a group: a body
 * renaming one that also carries a key is refused as carrying a member it does not take.
 *
 * <p>No change takes a parameter, and each body is read as {@link JsonBody} reads one rather than bound.
 * A body holding exactly the members taken, each a string, is then read member by member, and what a
 * member holds is refused under that member's own code, save an identifier that is none, which is
 * refused as the body.
 */
@RestController
final class GroupChangesController {

    private static final String NAME = "name";

    private static final String KEY = "key";

    private static final String SUBJECT_ID = "subjectId";

    private static final Set<String> CREATING = Set.of(NAME, KEY, SUBJECT_ID);

    private static final Set<String> RENAMING = Set.of(NAME);

    /** Room for the longest name, key and identifier this takes with every character escaped, and their members. */
    private static final int LARGEST_BODY = 4096;

    private static final String CREATE_REFUSED =
            "This takes a JSON object holding a group's name, its key, and who in the pool is its first member.";

    private static final String RENAME_REFUSED =
            "This takes a JSON object holding a group's new name, and nothing else.";

    private static final String NAME_REFUSED = "A group's name is one to 128 characters on one line: no space but"
            + " single plain ones between words, none at either end, and something in it that shows.";

    private static final String KEY_REFUSED = "A group's key is two to sixteen English letters and nothing else.";

    private final GroupChanges changes;

    GroupChangesController(GroupChanges changes) {
        this.changes = changes;
    }

    @PostMapping(GroupRegisterController.GROUPS)
    @ActRequired(EstateAct.KEEP_GROUP_REGISTER)
    @ResponseStatus(HttpStatus.CREATED)
    GroupRegisterController.GroupAnswer create(HttpServletRequest request) throws IOException {
        ChangeRequests.requireNoParameter(request);
        JsonNode body = bodyHolding(request, CREATING, CREATE_REFUSED);
        GroupName name = nameIn(body);
        GroupKey key = keyIn(body);
        SubjectId person = MintedIdentifiers.read(body.get(SUBJECT_ID).asString())
                .map(SubjectId::new)
                .orElseThrow(() -> bodyRefused(CREATE_REFUSED, null));
        return GroupRegisterController.answer(changes.create(name, key, person, CallerAdmission.callerOf(request)));
    }

    @PatchMapping(GroupRegisterController.GROUP)
    @ActRequired(EstateAct.KEEP_GROUP_REGISTER)
    GroupRegisterController.GroupAnswer rename(@PathVariable String groupId, HttpServletRequest request)
            throws IOException {
        GroupId group = MintedIdentifiers.read(groupId).map(GroupId::new).orElseThrow(GroupChanges::notInView);
        ChangeRequests.requireNoParameter(request);
        GroupName name = nameIn(bodyHolding(request, RENAMING, RENAME_REFUSED));
        return GroupRegisterController.answer(changes.rename(group, name, CallerAdmission.callerOf(request)));
    }

    private static JsonNode bodyHolding(HttpServletRequest request, Set<String> members, String refusedAs)
            throws IOException {
        JsonNode document = JsonBody.read(request, LARGEST_BODY, refusedAs);
        if (!document.isObject()
                || document.size() != members.size()
                || !members.stream().allMatch(member -> document.path(member).isString())) {
            throw bodyRefused(refusedAs, null);
        }
        return document;
    }

    private static GroupName nameIn(JsonNode body) {
        try {
            return new GroupName(body.get(NAME).asString());
        } catch (IllegalArgumentException refused) {
            throw new ApiErrorException(RefusalCode.GROUP_NAME_UNUSABLE, NAME_REFUSED, refused);
        }
    }

    private static GroupKey keyIn(JsonNode body) {
        try {
            return GroupKey.typed(body.get(KEY).asString());
        } catch (IllegalArgumentException refused) {
            throw new ApiErrorException(RefusalCode.GROUP_KEY_UNUSABLE, KEY_REFUSED, refused);
        }
    }

    private static ApiErrorException bodyRefused(String refusedAs, @Nullable Throwable cause) {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, refusedAs, cause);
    }
}
