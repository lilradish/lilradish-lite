package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.failure.RefusalCode;
import org.lilradish.lite.domain.identity.GroupPermission;

/**
 * What may be done to a run: what it offers and what an act on it refuses are both {@link #refusal}'s answer.
 * The published spelling is written out, for the reason {@link org.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum RunAct {
    STOP("stop", GroupPermission.START_RUN),
    OPEN_AGAIN("open_again", GroupPermission.START_RUN),
    RENAME("rename", GroupPermission.START_RUN),

    /** To another number, to none, or to one where there was none; stopped or not. */
    CHANGE_CEILING("change_ceiling", GroupPermission.START_RUN),

    /** Nobody decides a raise they asked for, whatever they hold. */
    APPROVE_RAISE("approve_raise", GroupPermission.APPROVE_ENTRY),
    REFUSE_RAISE("refuse_raise", GroupPermission.APPROVE_ENTRY),

    /** Only whoever asked for it. */
    WITHDRAW_RAISE("withdraw_raise", GroupPermission.START_RUN);

    private final String published;

    private final GroupPermission permission;

    RunAct(String published, GroupPermission permission) {
        this.published = published;
        this.permission = permission;
    }

    public String published() {
        return published;
    }

    public GroupPermission permission() {
        return permission;
    }

    /** Why somebody holding the permission may not do this where the run stands, or nothing. */
    public @Nullable RefusalCode refusal(RunPosition position) {
        requireNonNull(position, "RunAct position must not be null");
        return switch (this) {
            case STOP, OPEN_AGAIN, RENAME, CHANGE_CEILING -> position.atTop() ? null : RefusalCode.RUN_BENEATH_ANOTHER;
            case APPROVE_RAISE, REFUSE_RAISE ->
                switch (position.raise()) {
                    case NONE_WAITING -> RefusalCode.CEILING_RAISE_NOT_WAITING;
                    case ASKED_BY_READER -> RefusalCode.CEILING_RAISE_ASKED_BY_CALLER;
                    case ASKED_BY_ANOTHER -> null;
                };
            case WITHDRAW_RAISE ->
                switch (position.raise()) {
                    case NONE_WAITING -> RefusalCode.CEILING_RAISE_NOT_WAITING;
                    case ASKED_BY_READER -> null;
                    case ASKED_BY_ANOTHER -> RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER;
                };
        };
    }

    /**
     * The acts whose permission is among {@code permitted}, whose {@link #refusal} is nothing, and which would
     * change something: stopping a stopped or a done run, or opening one that is not stopped or is done, is
     * accepted and offered nowhere.
     */
    public static Set<RunAct> admitted(Set<GroupPermission> permitted, RunPosition position) {
        requireNonNull(permitted, "RunAct permitted must not be null");
        EnumSet<RunAct> admitted = EnumSet.noneOf(RunAct.class);
        for (RunAct act : values()) {
            boolean moves =
                    switch (act) {
                        case STOP -> !position.stopped() && !position.done();
                        case OPEN_AGAIN -> position.stopped() && !position.done();
                        default -> true;
                    };
            if (moves && permitted.contains(act.permission) && act.refusal(position) == null) {
                admitted.add(act);
            }
        }
        return Collections.unmodifiableSet(admitted);
    }
}
