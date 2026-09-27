package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.lilradish.lite.app.codestep.CodeSteps;
import org.lilradish.lite.domain.codestep.CodeStepDeclaration;
import org.lilradish.lite.domain.codestep.ReleasedCodeStep;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryName;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.lilradish.lite.domain.run.PinnedEntry;
import org.lilradish.lite.domain.run.PlannedStep;
import org.lilradish.lite.domain.run.RunnableWorkflow;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Workflow versions in service as runs of them run, read the way the library reads them and never mapped a second
 * time: every step with what it runs and what that declares, each question with its instruction and the terms
 * of every list it pins. Read in the caller's transaction, so what a run decides on is what its locks hold.
 */
public final class RunnableWorkflows {

    // DB-SPECIFIC: array casts and any(…) are PostgreSQL's.
    private static final String PINNED = """
            select version.entry_version_id, version.entry_id, version.number, entry.name
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = any(cast(:versions as uuid[])) and entry.group_id = :group
            """;

    private RunnableWorkflows() {}

    /**
     * Inside the caller's transaction, each of {@code versions} once, in as many statements however many are read
     * and whatever their steps pin. A version holding what submitting would refuse, or pinning what its group does
     * not hold, is a store gone wrong and fails. A code step is read as {@code release} declares it now, beside the
     * terms of each list that pins that the group holds; one it pins that the group does not is left out, not failed.
     */
    public static Map<EntryVersionId, RunnableWorkflow> read(
            JdbcClient database, GroupId group, Collection<EntryVersionId> versions, CodeSteps release) {
        requireNonNull(database, "RunnableWorkflows database must not be null");
        requireNonNull(group, "RunnableWorkflows group must not be null");
        requireNonNull(versions, "RunnableWorkflows versions must not be null");
        requireNonNull(release, "RunnableWorkflows release must not be null");
        Set<EntryVersionId> asked = new LinkedHashSet<>(versions);
        Map<EntryVersionId, StoredWorkflow> stored = StoredWorkflow.readAll(database, asked);
        Map<EntryVersionId, StoredWorkflow> workflows = LinkedHashMap.newLinkedHashMap(asked.size());
        for (EntryVersionId version : asked) {
            StoredWorkflow workflow = stored.get(version);
            if (workflow == null) {
                throw new IllegalStateException("Version " + version.value() + " holds no workflow");
            }
            workflows.put(version, workflow);
        }
        Set<EntryVersionId> pinnedVersions = new LinkedHashSet<>();
        Map<EntryVersionId, EntryKind> questionsPinned = new HashMap<>();
        Map<String, CodeStepDeclaration> released = new HashMap<>();
        for (StoredWorkflow workflow : workflows.values()) {
            pinnedVersions.addAll(workflow.pinned());
            for (StoredWorkflow.Step step : workflow.steps()) {
                if (step.runs() instanceof StoredWorkflow.Runs.Pinned held && held.kind() == EntryKind.QUESTION) {
                    questionsPinned.put(held.version(), EntryKind.QUESTION);
                }
                if (step.runs() instanceof StoredWorkflow.Runs.Code code && code.codeStep() != null) {
                    release.declaration(code.codeStep()).ifPresent(declared -> released.put(code.codeStep(), declared));
                }
            }
        }
        Map<EntryVersionId, PinnedEntry> pinned = pinned(database, group, pinnedVersions);
        Map<EntryVersionId, StoredQuestion> questions = questionsPinned.isEmpty()
                ? Map.of()
                : StoredQuestion.readAll(database, StoredDeclarations.ofVersions(database, questionsPinned));
        Set<EntryVersionId> lists = new LinkedHashSet<>();
        workflows.values().forEach(workflow -> lists.addAll(workflow.listsPinned()));
        questions.values().forEach(question -> lists.addAll(question.pinned()));
        released.values().forEach(declared -> lists.addAll(declared.lists()));
        Map<EntryVersionId, OfferedTerms> offered = StoredQuestion.offered(database, group, lists);
        Map<String, ReleasedCodeStep> codeSteps = HashMap.newHashMap(released.size());
        released.forEach(
                (name, declared) -> codeSteps.put(name, new ReleasedCodeStep(declared, held(offered, declared))));
        Map<EntryVersionId, RunnableWorkflow> read = LinkedHashMap.newLinkedHashMap(workflows.size());
        workflows.forEach((version, workflow) ->
                read.put(version, runnable(version, workflow, new Pinned(pinned, questions, codeSteps, offered))));
        return read;
    }

    private static RunnableWorkflow runnable(EntryVersionId version, StoredWorkflow workflow, Pinned pinned) {
        try {
            List<PlannedStep> steps = new ArrayList<>(workflow.steps().size());
            for (StoredWorkflow.Step step : workflow.steps()) {
                steps.add(new PlannedStep(
                        new WorkflowStepId(step.id()),
                        steps.size() + 1,
                        step.name(),
                        runs(step, pinned),
                        step.producer(),
                        step.tries(),
                        step.reviewer(),
                        step.bindings()));
            }
            Set<EntryVersionId> own = new LinkedHashSet<>();
            new StoredDeclarations.Halves(workflow.takes(), workflow.gives()).listsPinnedInto(own);
            return new RunnableWorkflow(
                    workflow.takes().declaration(),
                    workflow.gives().declaration(),
                    within(pinned.offered(), own),
                    workflow.outputs(),
                    steps);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException(
                    "Version " + version.value() + " holds what no version in service could hold", refused);
        }
    }

    private static StepRuns runs(StoredWorkflow.Step step, Pinned pinned) {
        return switch (step.runs()) {
            case StoredWorkflow.Runs.Unchosen ignored ->
                throw new IllegalArgumentException("Step " + step.id() + " runs nothing chosen");
            case StoredWorkflow.Runs.Code code -> {
                String name = requireNonNull(code.codeStep());
                yield new StepRuns.Code(name, pinned.codeSteps().get(name));
            }
            case StoredWorkflow.Runs.Route ignored -> new StepRuns.Route();
            case StoredWorkflow.Runs.Pinned held -> {
                PinnedEntry entry = pinned.entries().get(held.version());
                if (entry == null) {
                    throw new IllegalArgumentException("Step " + step.id() + " pins a version its group does not hold");
                }
                yield switch (held.kind()) {
                    case QUESTION -> question(entry, pinned.questions(), pinned.offered());
                    case WORKFLOW -> new StepRuns.Workflow(entry);
                    case REFERENCE_LIST ->
                        throw new IllegalArgumentException("Step " + step.id() + " pins a reference list");
                };
            }
        };
    }

    private static StepRuns.Question question(
            PinnedEntry entry,
            Map<EntryVersionId, StoredQuestion> questions,
            Map<EntryVersionId, OfferedTerms> offered) {
        StoredQuestion question = questions.get(entry.version());
        if (question == null) {
            throw new IllegalArgumentException("Version " + entry.version().value() + " holds no question");
        }
        List<UUID> keys = question.gives().keys().stream()
                .map(StoredDeclarations.Keyed::id)
                .toList();
        return new StepRuns.Question(
                entry,
                requireNonNull(question.instruction(), "a question in service tells something"),
                question.takes().declaration(),
                question.gives().declaration(),
                keys,
                within(offered, question.pinned()));
    }

    /* A list the code pins that the group does not hold is left out, since the release, not the store, holds it. */
    private static Map<EntryVersionId, OfferedTerms> held(
            Map<EntryVersionId, OfferedTerms> offered, CodeStepDeclaration declared) {
        Map<EntryVersionId, OfferedTerms> held = new HashMap<>();
        for (EntryVersionId list : declared.lists()) {
            OfferedTerms terms = offered.get(list);
            if (terms != null) {
                held.put(list, terms);
            }
        }
        return held;
    }

    /** What each of {@code lists} offers; one the group does not hold fails as a store gone wrong. */
    private static Map<EntryVersionId, OfferedTerms> within(
            Map<EntryVersionId, OfferedTerms> offered, Set<EntryVersionId> lists) {
        Map<EntryVersionId, OfferedTerms> held = HashMap.newHashMap(lists.size());
        for (EntryVersionId list : lists) {
            OfferedTerms terms = offered.get(list);
            if (terms == null) {
                throw new IllegalArgumentException("It pins a list its group does not hold: " + list.value());
            }
            held.put(list, terms);
        }
        return held;
    }

    private static Map<EntryVersionId, PinnedEntry> pinned(
            JdbcClient database, GroupId group, Set<EntryVersionId> versions) {
        Map<EntryVersionId, PinnedEntry> pinned = new HashMap<>();
        if (versions.isEmpty()) {
            return pinned;
        }
        database.sql(PINNED)
                .param("versions", PinnedVersions.spelled(versions))
                .param("group", group.value())
                .query(result -> {
                    EntryVersionId version = new EntryVersionId(result.getObject("entry_version_id", UUID.class));
                    pinned.put(
                            version,
                            new PinnedEntry(
                                    new EntryId(result.getObject("entry_id", UUID.class)),
                                    new EntryName(result.getString("name")),
                                    version,
                                    result.getInt("number")));
                });
        return pinned;
    }

    /** Everything the versions read reach beyond themselves, each read once for all of them. */
    private record Pinned(
            Map<EntryVersionId, PinnedEntry> entries,
            Map<EntryVersionId, StoredQuestion> questions,
            Map<String, ReleasedCodeStep> codeSteps,
            Map<EntryVersionId, OfferedTerms> offered) {}
}
