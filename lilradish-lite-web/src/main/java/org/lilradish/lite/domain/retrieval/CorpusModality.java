package org.lilradish.lite.domain.retrieval;

/**
 * Part of an index generation's key rather than a column on a shared index: modalities are searched
 * separately and merged, each keeping its own index and its own embedding model, pinned
 * independently. {@link org.lilradish.lite.domain.workflow.Modality} is a different thing — it filters
 * which models may serve a step — and is deliberately not reused here.
 */
public enum CorpusModality {
    TEXT,
    IMAGE
}
