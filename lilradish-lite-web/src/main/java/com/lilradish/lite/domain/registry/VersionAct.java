package com.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupPermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The published spelling is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum VersionAct {
    WRITE("write", GroupPermission.AUTHOR_ENTRY, VersionStanding.DRAFT, false),
    SUBMIT("submit", GroupPermission.AUTHOR_ENTRY, VersionStanding.DRAFT, false),
    WITHDRAW("withdraw", GroupPermission.AUTHOR_ENTRY, VersionStanding.SUBMITTED, false),

    /** Nobody approves what they wrote, whatever they hold. */
    APPROVE("approve", GroupPermission.APPROVE_ENTRY, VersionStanding.SUBMITTED, true),

    RETIRE("retire", GroupPermission.REVOKE_ENTRY, VersionStanding.IN_SERVICE, false);

    private final String published;

    private final GroupPermission permission;

    private final VersionStanding from;

    private final boolean closedToWriters;

    VersionAct(String published, GroupPermission permission, VersionStanding from, boolean closedToWriters) {
        this.published = published;
        this.permission = permission;
        this.from = from;
        this.closedToWriters = closedToWriters;
    }

    public String published() {
        return published;
    }

    public GroupPermission permission() {
        return permission;
    }

    /** Why somebody holding the permission may not do this, or nothing: the standing first, then authorship. */
    public @Nullable RefusalCode refusal(VersionStanding standing, boolean wroteIt) {
        requireNonNull(standing, "VersionAct standing must not be null");
        if (!standsAt(standing)) {
            return RefusalCode.VERSION_STANDING_REFUSES;
        }
        if (closedToWriters && wroteIt) {
            return RefusalCode.APPROVER_WROTE_VERSION;
        }
        return null;
    }

    /** The acts allowed by the standing, before permission and authorship are considered. */
    public static Set<VersionAct> stateActs(VersionStanding standing) {
        requireNonNull(standing, "VersionAct standing must not be null");
        EnumSet<VersionAct> acts = EnumSet.noneOf(VersionAct.class);
        for (VersionAct act : values()) {
            if (act.standsAt(standing)) {
                acts.add(act);
            }
        }
        return Collections.unmodifiableSet(acts);
    }

    private boolean standsAt(VersionStanding standing) {
        return standing == from;
    }
}
