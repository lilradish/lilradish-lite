package org.lilradish.lite.app.run;

import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.run.RunId;

/**
 * A run tree whose root the calling transaction holds, which only {@link RunTree} makes: what is decided on a
 * tree asks for one, so nothing decides on it unlocked, nor once the transaction that locked it has ended.
 */
public final class LockedTree {

    private final GroupId group;

    private final RunId root;

    private final Thread holder = Thread.currentThread();

    private volatile boolean held = true;

    LockedTree(GroupId group, RunId root) {
        this.group = group;
        this.root = root;
    }

    public GroupId group() {
        return group;
    }

    public RunId root() {
        return root;
    }

    /** Fails once the transaction that locked it has ended, and on any thread but the one it was locked on. */
    void requireHeld() {
        if (!held || Thread.currentThread() != holder) {
            throw new IllegalStateException(
                    "Run tree " + root.value() + " was used outside the transaction that locked it");
        }
    }

    void released() {
        held = false;
    }
}
