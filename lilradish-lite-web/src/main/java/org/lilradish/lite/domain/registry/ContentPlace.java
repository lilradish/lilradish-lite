package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Where in a version's stored content a problem is: a part as a whole, or one row of it by its key. Never by
 * anything its authors wrote, which a reader of the version finds by the key. Every key but a pinned version's
 * field key and a term's changes whenever the part holding it is written again.
 */
public sealed interface ContentPlace
        permits ContentPlace.Whole,
                ContentPlace.AtField,
                ContentPlace.AtStep,
                ContentPlace.AtCase,
                ContentPlace.AtBinding,
                ContentPlace.AtInput,
                ContentPlace.AtTerm {

    ContentPart part();

    record Whole(ContentPart part) implements ContentPlace {

        public Whole {
            requireNonNull(part, "ContentPlace.Whole part must not be null");
        }
    }

    /** @param fieldId the stored field's own key; a field a route gives back is in the part holding its step */
    record AtField(ContentPart part, UUID fieldId) implements ContentPlace {

        public AtField {
            requireNonNull(part, "ContentPlace.AtField part must not be null");
            requireNonNull(fieldId, "ContentPlace.AtField fieldId must not be null");
        }
    }

    record AtStep(UUID stepId) implements ContentPlace {

        public AtStep {
            requireNonNull(stepId, "ContentPlace.AtStep stepId must not be null");
        }

        @Override
        public ContentPart part() {
            return ContentPart.STEPS;
        }
    }

    record AtCase(UUID stepId, UUID caseId) implements ContentPlace {

        public AtCase {
            requireNonNull(stepId, "ContentPlace.AtCase stepId must not be null");
            requireNonNull(caseId, "ContentPlace.AtCase caseId must not be null");
        }

        @Override
        public ContentPart part() {
            return ContentPart.STEPS;
        }
    }

    /** @param part the steps where the binding fills a step's or a case's input, what it gives back otherwise */
    record AtBinding(ContentPart part, UUID bindingId) implements ContentPlace {

        public AtBinding {
            requireNonNull(part, "ContentPlace.AtBinding part must not be null");
            requireNonNull(bindingId, "ContentPlace.AtBinding bindingId must not be null");
        }
    }

    /**
     * An input of what a step runs, or of what one of its route's cases leads to.
     *
     * @param caseId none where the input is the step's own
     * @param fieldId the key the pinned version stores the input under, or the one a code step's field is keyed by
     *     while the release declares it so; neither ever changes
     */
    record AtInput(UUID stepId, @Nullable UUID caseId, UUID fieldId) implements ContentPlace {

        public AtInput {
            requireNonNull(stepId, "ContentPlace.AtInput stepId must not be null");
            requireNonNull(fieldId, "ContentPlace.AtInput fieldId must not be null");
        }

        @Override
        public ContentPart part() {
            return ContentPart.STEPS;
        }
    }

    /** @param termId the stored term's own key, which it keeps until it is removed */
    record AtTerm(UUID termId) implements ContentPlace {

        public AtTerm {
            requireNonNull(termId, "ContentPlace.AtTerm termId must not be null");
        }

        @Override
        public ContentPart part() {
            return ContentPart.TERMS;
        }
    }
}
