package org.lilradish.lite.domain.workflow;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import org.libprunus.core.log.annotation.DoNotLog;
import org.lilradish.lite.domain.text.Legibility;

/** One part of a searching step's question, which is its parts in the order written. */
public sealed interface SearchQuestionPart permits SearchQuestionPart.LiteralPart, SearchQuestionPart.Read {

    /**
     * Text of whitespace alone is kept: it may be all that separates two values read into the question. Named so
     * the logging convention matches it, since only there does its text's {@code @DoNotLog} hold.
     */
    record LiteralPart(@DoNotLog String text) implements SearchQuestionPart {

        public LiteralPart {
            requireNonNull(text, "SearchQuestionPart.LiteralPart text must not be null");
            if (text.isEmpty()) {
                throw new IllegalArgumentException("SearchQuestionPart.LiteralPart text must not be empty");
            }
            Legibility.requireWellFormedProse(text, "Search question text");
            Legibility.requireWithinMaximumLength(text, 8192, "Search question text");
        }
    }

    /** @param id its stored key, which changes whenever the step holding it is written again */
    record Read(UUID id, BindingSource source) implements SearchQuestionPart {

        public Read {
            requireNonNull(id, "SearchQuestionPart.Read id must not be null");
            requireNonNull(source, "SearchQuestionPart.Read source must not be null");
            switch (source) {
                case BindingSource.WorkflowInput _, BindingSource.StepOutput _ -> {}
                case BindingSource.Written _ ->
                    throw new IllegalArgumentException(
                            "SearchQuestionPart.Read source must be read, not written; written text is a literal");
            }
        }
    }
}
