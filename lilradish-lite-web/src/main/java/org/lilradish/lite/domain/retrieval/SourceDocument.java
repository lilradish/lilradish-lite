package org.lilradish.lite.domain.retrieval;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;

/**
 * One source document as a corpus retains it. The source identifier, the submitter, the submission
 * time and the content fingerprint are what make it findable and re-processable; the content on its
 * own is neither.
 *
 * <p>The body is retained so that a corpus can be re-chunked and its index rebuilt. Without it a
 * change of chunking would be data loss rather than a rebuild — which is also why the chunking
 * strategy is deliberately not a component here: it belongs to the index generation that chunked
 * this document, and one retained original can be chunked again under a different one.
 *
 * @param contentFingerprint the body's own {@link UntrustedText#digest()}, checked here rather than
 *     trusted, because deduplication would compare fingerprints and re-ingestion would decide on
 *     them — one that did not come from this body would make both lie
 * @param audience the roles that may open the document, never empty — a source naming no audience is
 *     one nobody may open. Copied into an EnumSet and handed back as a view over it, which is not
 *     one, so a membership test a caller makes on what it was handed is no bit-mask test
 */
public record SourceDocument(
        UUID corpusEntryId,
        SourceId sourceId,
        CorpusModality modality,
        Origin origin,
        SubjectId submitter,
        Instant submittedAt,
        String contentFingerprint,
        Set<GroupRole> audience,
        UntrustedText body) {

    public SourceDocument {
        requireNonNull(corpusEntryId, "SourceDocument corpusEntryId must not be null");
        requireNonNull(sourceId, "SourceDocument sourceId must not be null");
        requireNonNull(modality, "SourceDocument modality must not be null");
        requireNonNull(origin, "SourceDocument origin must not be null");
        requireNonNull(submitter, "SourceDocument submitter must not be null");
        requireNonNull(submittedAt, "SourceDocument submittedAt must not be null");
        requireNonNull(contentFingerprint, "SourceDocument contentFingerprint must not be null");
        requireNonNull(audience, "SourceDocument audience must not be null");
        requireNonNull(body, "SourceDocument body must not be null");
        EnumSet<GroupRole> readers = EnumSet.noneOf(GroupRole.class);
        for (GroupRole reader : audience) {
            readers.add(requireNonNull(reader, "SourceDocument audience must not hold a null role"));
        }
        if (readers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Source " + sourceId.value() + " names no audience, so nobody may open it");
        }
        audience = Collections.unmodifiableSet(readers);
        if (!body.digest().equals(contentFingerprint)) {
            throw new IllegalArgumentException(
                    "Source " + sourceId.value() + " carries a fingerprint that is not its body's");
        }
    }

    /**
     * Material from outside the boundary enters a retrievable corpus only once a person has approved
     * it, and material from inside does not — which is what makes the label worth carrying.
     */
    public boolean needsHumanApproval() {
        return origin == Origin.OUTSIDE_BOUNDARY;
    }

    /** Applied at the source, and never inferred later from what the text looks like. */
    public enum Origin {
        INSIDE_BOUNDARY,
        OUTSIDE_BOUNDARY
    }
}
