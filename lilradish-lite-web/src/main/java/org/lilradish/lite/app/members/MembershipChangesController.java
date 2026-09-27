package org.lilradish.lite.app.members;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.error.ApiErrorException;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.web.ActAdmission;
import org.lilradish.lite.web.CallerAdmission;
import org.lilradish.lite.web.ChangeRequests;
import org.lilradish.lite.web.GroupPermissionRequired;
import org.lilradish.lite.web.JsonBody;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Bringing somebody into a group, giving and taking a role there, and taking somebody out, each asked
 * of a member who may change the group's membership in the group the gate admitted them into. Each
 * answers with the person as the group now holds them, except a removal, after which there is nothing
 * of them here to read.
 *
 * <p>A role is addressed by the spelling it is published under, and is read before the person: one no
 * role has names nothing here, whoever the address names, and is answered as an address that names
 * nothing. Somebody given a role is a member, or somebody whose last role here was taken, which is how
 * they are made a member again; somebody a role is taken from, or who is taken out, is a member. Anybody
 * else is refused as no member, and so is an address that names nobody.
 *
 * <p>No change takes a parameter, and only bringing somebody in takes a body, read as {@link JsonBody}
 * reads one rather than bound. It names one person in the pool and one or more roles, each once: a
 * member holding nothing is no member, and a role named twice is a body that does not say what it
 * means.
 */
@RestController
final class MembershipChangesController {

    private static final String SUBJECT_ID = "subjectId";

    private static final String ROLES = "roles";

    /** Room for an identifier and every role, each with every character escaped, and their members. */
    private static final int LARGEST_BODY = 4096;

    private static final GroupRole[] EVERY_ROLE = GroupRole.values();

    private static final String BODY_REFUSED =
            "This takes a JSON object naming one person in the pool and one or more of this group's roles, each once.";

    private final MembershipChanges changes;

    MembershipChangesController(MembershipChanges changes) {
        this.changes = changes;
    }

    @PostMapping(MembersController.MEMBERS)
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    ResponseEntity<MembersController.MemberPanelAnswer> bringIn(HttpServletRequest request) throws IOException {
        GroupId group = ActAdmission.admittedGroup(request);
        ChangeRequests.requireNoParameter(request);
        JsonNode body = JsonBody.read(request, LARGEST_BODY, BODY_REFUSED);
        if (!body.isObject() || body.size() != 2 || !body.path(SUBJECT_ID).isString()) {
            throw bodyRefused();
        }
        SubjectId person = MembersController.addressed(body.get(SUBJECT_ID).asString())
                .orElseThrow(MembershipChangesController::bodyRefused);
        Members.Member brought =
                changes.bringIn(group, person, rolesIn(body.get(ROLES)), CallerAdmission.callerOf(request));
        return ResponseEntity.created(UriComponentsBuilder.fromPath(MembersController.MEMBER)
                        .buildAndExpand(group.value(), brought.subjectId().value())
                        .toUri())
                .body(MembersController.panelAnswer(brought));
    }

    @PutMapping(MembersController.MEMBER_ROLE)
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    MembersController.MemberPanelAnswer give(
            @PathVariable String subjectId, @PathVariable String role, HttpServletRequest request) throws IOException {
        GroupRole given = roleAt(role);
        SubjectId person = memberAt(subjectId);
        ChangeRequests.requireNothingSent(request);
        return MembersController.panelAnswer(
                changes.give(ActAdmission.admittedGroup(request), person, given, CallerAdmission.callerOf(request)));
    }

    @DeleteMapping(MembersController.MEMBER_ROLE)
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    MembersController.MemberPanelAnswer take(
            @PathVariable String subjectId, @PathVariable String role, HttpServletRequest request) throws IOException {
        GroupRole taken = roleAt(role);
        SubjectId person = memberAt(subjectId);
        ChangeRequests.requireNothingSent(request);
        return MembersController.panelAnswer(
                changes.take(ActAdmission.admittedGroup(request), person, taken, CallerAdmission.callerOf(request)));
    }

    @DeleteMapping(MembersController.MEMBER)
    @GroupPermissionRequired(GroupPermission.CHANGE_MEMBERSHIP)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String subjectId, HttpServletRequest request) throws IOException {
        SubjectId person = memberAt(subjectId);
        ChangeRequests.requireNothingSent(request);
        changes.remove(ActAdmission.admittedGroup(request), person, CallerAdmission.callerOf(request));
    }

    private static SubjectId memberAt(String subjectId) {
        return MembersController.addressed(subjectId).orElseThrow(Members::notInView);
    }

    private static GroupRole roleAt(String spelled) {
        return published(spelled).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static Optional<GroupRole> published(String spelled) {
        for (GroupRole role : EVERY_ROLE) {
            if (role.published().equals(spelled)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }

    private static EnumSet<GroupRole> rolesIn(@Nullable JsonNode named) {
        if (named == null || !named.isArray() || named.isEmpty()) {
            throw bodyRefused();
        }
        EnumSet<GroupRole> roles = EnumSet.noneOf(GroupRole.class);
        for (JsonNode each : named) {
            GroupRole role = each.isString() ? published(each.asString()).orElse(null) : null;
            if (role == null || !roles.add(role)) {
                throw bodyRefused();
            }
        }
        return roles;
    }

    private static ApiErrorException bodyRefused() {
        return new ApiErrorException(RefusalCode.BODY_UNUSABLE, BODY_REFUSED);
    }
}
