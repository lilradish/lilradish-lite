package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Every kind's {@link ContentCheck}, exactly one each: a kind with none, or with two, refuses the start
 * rather than letting a submission of it through unasked.
 */
@Component
public final class ContentChecks {

    private final Map<EntryKind, ContentCheck> byKind;

    ContentChecks(List<ContentCheck> checks) {
        Map<EntryKind, ContentCheck> byKind = new EnumMap<>(EntryKind.class);
        for (ContentCheck check : checks) {
            if (byKind.put(check.kind(), check) != null) {
                throw new IllegalStateException("Two content checks are declared for " + check.kind());
            }
        }
        for (EntryKind kind : EntryKind.values()) {
            if (!byKind.containsKey(kind)) {
                throw new IllegalStateException("No content check is declared for " + kind);
            }
        }
        this.byKind = byKind;
    }

    /** Inside the caller's transaction, of a version of that kind the group is known to hold. */
    ContentProblemReport.Result problemsIn(EntryKind kind, GroupId group, EntryVersionId version) {
        return requireNonNull(byKind.get(kind)).problemsIn(group, version);
    }
}
