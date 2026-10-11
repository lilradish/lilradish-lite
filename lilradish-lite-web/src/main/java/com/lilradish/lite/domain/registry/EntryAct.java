package com.lilradish.lite.domain.registry;

import com.lilradish.lite.domain.identity.GroupPermission;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What may be done to an entry as a whole, beside what may be done to any one version of it.
 *
 * <p>The published spelling is written out rather than folded from the constant name, for the reason
 * {@link com.lilradish.lite.domain.identity.EstateAct} gives.
 */
public enum EntryAct {
    /** Only where no version is a draft or submitted, an entry holding one such at a time. */
    START_DRAFT("start_draft", GroupPermission.AUTHOR_ENTRY),
    /** The name and what the entry is for, changed as one. */
    RENAME("rename", GroupPermission.AUTHOR_ENTRY),

    STOP("stop", GroupPermission.REVOKE_ENTRY),
    LET_GO("let_go", GroupPermission.REVOKE_ENTRY);

    private final String published;

    private final GroupPermission permission;

    EntryAct(String published, GroupPermission permission) {
        this.published = published;
        this.permission = permission;
    }

    public String published() {
        return published;
    }

    public GroupPermission permission() {
        return permission;
    }

    /** The acts allowed by the entry's state, independent of its reader. */
    public static Set<EntryAct> stateActs(boolean stopped, boolean draftUnderWay) {
        EnumSet<EntryAct> acts = EnumSet.noneOf(EntryAct.class);
        for (EntryAct act : values()) {
            if (act.standsSo(stopped, draftUnderWay)) {
                acts.add(act);
            }
        }
        return Collections.unmodifiableSet(acts);
    }

    private boolean standsSo(boolean stopped, boolean draftUnderWay) {
        return switch (this) {
            case START_DRAFT -> !draftUnderWay;
            case STOP -> !stopped;
            case LET_GO -> stopped;
            case RENAME -> true;
        };
    }
}
