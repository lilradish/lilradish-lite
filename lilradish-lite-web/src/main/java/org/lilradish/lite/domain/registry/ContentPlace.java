package org.lilradish.lite.domain.registry;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A problem's part, stored row key, or an unknown name written in a question's drawing. */
public sealed interface ContentPlace
        permits ContentPlace.Whole,
                ContentPlace.AtField,
                ContentPlace.AtStep,
                ContentPlace.AtStepFields,
                ContentPlace.AtCase,
                ContentPlace.AtCaseField,
                ContentPlace.AtBinding,
                ContentPlace.AtInput,
                ContentPlace.AtTerm,
                ContentPlace.AtDrawing {

    ContentPart part();

    record AtDrawing(String name) implements ContentPlace {

        public AtDrawing {
            requireNonNull(name, "ContentPlace.AtDrawing name must not be null");
        }

        @Override
        public ContentPart part() {
            return ContentPart.DRAWING;
        }
    }

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

    /** @param fields fields of what the step runs, as what declares them names them, in its order; at least one */
    record AtStepFields(UUID stepId, List<String> fields) implements ContentPlace {

        public AtStepFields {
            requireNonNull(stepId, "ContentPlace.AtStepFields stepId must not be null");
            fields = List.copyOf(requireNonNull(fields, "ContentPlace.AtStepFields fields must not be null"));
            if (fields.isEmpty() || fields.contains("")) {
                throw new IllegalArgumentException("ContentPlace.AtStepFields names at least one field, none empty");
            }
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

    record AtCaseField(UUID stepId, UUID caseId, UUID fieldId) implements ContentPlace {

        public AtCaseField {
            requireNonNull(stepId, "ContentPlace.AtCaseField stepId must not be null");
            requireNonNull(caseId, "ContentPlace.AtCaseField caseId must not be null");
            requireNonNull(fieldId, "ContentPlace.AtCaseField fieldId must not be null");
        }

        @Override
        public ContentPart part() {
            return ContentPart.STEPS;
        }
    }

    /**
     * A binding, or a part of a searching step's question reading a value, which is keyed alike.
     *
     * @param part the steps where the binding fills a step's or a case's input, what it gives back otherwise
     */
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
