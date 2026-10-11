package com.lilradish.lite.app.library;

import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;

/**
 * Checks stored content rather than what an editor sent. Exactly one per kind, which
 * {@link ContentChecks} holds to.
 */
interface ContentCheck {

    EntryKind kind();

    /**
     * Inside the caller's transaction, of a version this group holds: the first listed places in content order
     * and the number of all places not holding.
     */
    ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version);
}
