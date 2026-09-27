package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;

/** What a step of a version in service runs, with what that thing declares: a step states neither itself. */
public sealed interface StepRuns permits StepRuns.Question, StepRuns.Workflow, StepRuns.Code, StepRuns.Route {

    /**
     * @param givesFields the stored key of each field {@code gives} holds at its first level, in the same order
     * @param lists what each list a field of either half pins offers
     */
    record Question(
            PinnedEntry pinned,
            Instruction instruction,
            Declaration takes,
            Declaration gives,
            List<UUID> givesFields,
            Map<EntryVersionId, OfferedTerms> lists)
            implements StepRuns {

        public Question {
            requireNonNull(pinned, "StepRuns.Question pinned must not be null");
            requireNonNull(instruction, "StepRuns.Question instruction must not be null");
            requireNonNull(takes, "StepRuns.Question takes must not be null");
            requireNonNull(gives, "StepRuns.Question gives must not be null");
            givesFields = List.copyOf(requireNonNull(givesFields, "StepRuns.Question givesFields must not be null"));
            lists = Map.copyOf(requireNonNull(lists, "StepRuns.Question lists must not be null"));
            if (givesFields.size() != gives.fields().size()) {
                throw new IllegalArgumentException("StepRuns.Question names " + givesFields.size() + " keys for the "
                        + gives.fields().size() + " fields it gives back");
            }
        }
    }

    record Workflow(PinnedEntry pinned) implements StepRuns {

        public Workflow {
            requireNonNull(pinned, "StepRuns.Workflow pinned must not be null");
        }
    }

    /**
     * @param codeStep the published code step's name, whose declaration the release holds and no version does
     * @param released what the running release declares of it now; none where it holds no code step of that name
     */
    record Code(String codeStep, @Nullable ReleasedCodeStep released) implements StepRuns {

        public Code {
            requireNonNull(codeStep, "StepRuns.Code codeStep must not be null");
        }
    }

    record Route() implements StepRuns {}
}
