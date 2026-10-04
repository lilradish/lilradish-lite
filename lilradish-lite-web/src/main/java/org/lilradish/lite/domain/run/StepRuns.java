package org.lilradish.lite.domain.run;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.Declaration;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldStanding;
import org.lilradish.lite.domain.declaration.Instruction;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.search.SearchWay;
import org.lilradish.lite.domain.workflow.Binding;
import org.lilradish.lite.domain.workflow.SearchQuestionPart;

/** What a step of a version in service runs, with what that thing declares: a step states neither itself. */
public sealed interface StepRuns
        permits StepRuns.Question, StepRuns.Workflow, StepRuns.Code, StepRuns.Route, StepRuns.Search {

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

    record Workflow(PinnedEntry pinned, Declaration gives) implements StepRuns {

        public Workflow {
            requireNonNull(pinned, "StepRuns.Workflow pinned must not be null");
            requireNonNull(gives, "StepRuns.Workflow gives must not be null");
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

    record Route(Binding discriminator, Declaration gives, List<Case> cases) implements StepRuns {

        public Route {
            requireNonNull(discriminator, "StepRuns.Route discriminator must not be null");
            requireNonNull(gives, "StepRuns.Route gives must not be null");
            cases = List.copyOf(requireNonNull(cases, "StepRuns.Route cases must not be null"));
            if (cases.isEmpty()) {
                throw new IllegalArgumentException("StepRuns.Route must have a case");
            }
        }

        public record Case(UUID id, @Nullable String term, PinnedEntry target, List<Binding> bindings) {

            public Case {
                requireNonNull(id, "StepRuns.Route.Case id must not be null");
                requireNonNull(target, "StepRuns.Route.Case target must not be null");
                bindings = List.copyOf(requireNonNull(bindings, "StepRuns.Route.Case bindings must not be null"));
            }
        }
    }

    /**
     * @param way the name of the way to search, whose fields the release declares and no version does
     * @param released what the running release declares of it now; none where it holds no way of that name
     * @param standings how each field of the way stands, always or never: a search gives no confidence
     */
    record Search(
            String way,
            @Nullable SearchWay released,
            List<SearchQuestionPart> question,
            Map<FieldName, FieldStanding> standings)
            implements StepRuns {

        public Search {
            requireNonNull(way, "StepRuns.Search way must not be null");
            question = List.copyOf(requireNonNull(question, "StepRuns.Search question must not be null"));
            standings = Map.copyOf(requireNonNull(standings, "StepRuns.Search standings must not be null"));
            if (released != null && !released.name().equals(way)) {
                throw new IllegalArgumentException(
                        "StepRuns.Search names the way " + way + " but is handed the way " + released.name());
            }
            if (question.isEmpty()) {
                throw new IllegalArgumentException("StepRuns.Search must ask something");
            }
            if (standings.size() != SearchWay.FIELDS.size()
                    || !standings.keySet().containsAll(SearchWay.FIELDS)) {
                throw new IllegalArgumentException(
                        "StepRuns.Search must say how each field of its way stands, and of no other");
            }
            if (standings.containsValue(FieldStanding.ABOVE_CONFIDENCE)) {
                throw new IllegalArgumentException("StepRuns.Search must not stand a field above a confidence");
            }
        }
    }
}
