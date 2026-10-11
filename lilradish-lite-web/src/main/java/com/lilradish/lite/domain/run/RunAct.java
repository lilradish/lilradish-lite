package com.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.failure.RefusalCode;
import com.lilradish.lite.domain.identity.GroupPermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Offering an act and refusing it share the same state judgement.
 * The published spelling is written out, for the reason {@link com.lilradish.lite.domain.identity.EstateAct} gives.
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
        RefusalCode state = stateRefusal(position);
        if (state != null) {
            return state;
        }
        return switch (this) {
            case STOP, OPEN_AGAIN, RENAME, CHANGE_CEILING -> null;
            case APPROVE_RAISE, REFUSE_RAISE ->
                position.raise() == RunPosition.Raise.ASKED_BY_READER
                        ? RefusalCode.CEILING_RAISE_ASKED_BY_CALLER
                        : null;
            case WITHDRAW_RAISE ->
                position.raise() == RunPosition.Raise.ASKED_BY_ANOTHER
                        ? RefusalCode.CEILING_RAISE_ASKED_BY_ANOTHER
                        : null;
        };
    }

    /** State facts only: a waiting raise offers all three acts before requester and permission checks. */
    public static Set<RunAct> stateActs(RunPosition position) {
        requireNonNull(position, "RunAct position must not be null");
        EnumSet<RunAct> acts = EnumSet.noneOf(RunAct.class);
        for (RunAct act : values()) {
            boolean offered = act.moves(position) && act.stateRefusal(position) == null;
            if (offered) {
                acts.add(act);
            }
        }
        return Collections.unmodifiableSet(acts);
    }

    private @Nullable RefusalCode stateRefusal(RunPosition position) {
        return switch (this) {
            case STOP, OPEN_AGAIN, RENAME, CHANGE_CEILING -> position.atTop() ? null : RefusalCode.RUN_BENEATH_ANOTHER;
            case APPROVE_RAISE, REFUSE_RAISE, WITHDRAW_RAISE ->
                position.raise() == RunPosition.Raise.NONE_WAITING ? RefusalCode.CEILING_RAISE_NOT_WAITING : null;
        };
    }

    private boolean moves(RunPosition position) {
        return switch (this) {
            case STOP -> !position.stopped() && !position.done();
            case OPEN_AGAIN -> position.stopped() && !position.done();
            default -> true;
        };
    }
}
