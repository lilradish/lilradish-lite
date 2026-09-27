package org.lilradish.lite.app.library;

import org.lilradish.lite.domain.registry.EntryId;

/** What goes on once a stopped entry is let go: whatever the stop held goes on by itself, asked by nobody. */
@FunctionalInterface
public interface EntryLetGo {

    /** After the let-go has committed, outside its transaction: an entry is never held while a run is waited on. */
    void goesOn(EntryId entry);
}
