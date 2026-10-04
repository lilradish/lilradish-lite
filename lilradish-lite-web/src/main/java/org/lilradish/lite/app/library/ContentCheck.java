package org.lilradish.lite.app.library;

import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * What one kind's stored content is held to at submitting, and again by {@link ContentChecks#recheck}; never
 * read off what an editor sent. Exactly one per kind, which {@link ContentChecks} holds to.
 */
interface ContentCheck {

    EntryKind kind();

    /**
     * Inside the caller's transaction, of a version this group holds: the first listed places in content order
     * and the number of all places not holding.
     */
    ContentProblemReport.Result problemsIn(GroupId group, EntryVersionId version);
}
