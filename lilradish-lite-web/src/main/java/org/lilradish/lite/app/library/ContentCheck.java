package org.lilradish.lite.app.library;

import java.util.List;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;

/**
 * What one kind's stored content is held to at submitting, and again by {@link ContentChecks#recheck}; never
 * read off what an editor sent. Exactly one per kind, which {@link ContentChecks} holds to.
 */
interface ContentCheck {

    EntryKind kind();

    /**
     * Inside the caller's transaction, of a version of this kind the group is known to hold: every place its
     * stored content does not hold, in the order the content reads, and none where it holds.
     */
    List<ContentProblem> problemsIn(GroupId group, EntryVersionId version);
}
