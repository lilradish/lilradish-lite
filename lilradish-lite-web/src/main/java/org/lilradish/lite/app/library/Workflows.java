package org.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.domain.declaration.OfferedTerms;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.model.DeployedModel;
import org.lilradish.lite.domain.model.ModelCatalog;
import org.lilradish.lite.domain.registry.ContentProblem;
import org.lilradish.lite.domain.registry.ContentProblemCode;
import org.lilradish.lite.domain.registry.EntryId;
import org.lilradish.lite.domain.registry.EntryKind;
import org.lilradish.lite.domain.registry.EntryVersionId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One version of a workflow read as one moment, only within its group: what it holds, what does not hold of it
 * yet as submitting would judge it now, what its steps could send past what their models take now, what it pins,
 * and everything a draft of it may choose from.
 */
@Component
final class Workflows {

    private static final String IN_VIEW = """
            select 1
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version and %s
            """.formatted(LibraryScope.ENTRY_IN_SCOPE);

    private final JdbcClient database;

    private final GroupRoles roles;

    private final ModelCatalog models;

    private final ReleasedCodeSteps released;

    private final TransactionTemplate snapshot;

    Workflows(
            JdbcClient database,
            GroupRoles roles,
            ModelCatalog models,
            ReleasedCodeSteps released,
            PlatformTransactionManager transactionManager) {
        this.database = database;
        this.roles = roles;
        this.models = models;
        this.released = released;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /** Somebody holding nothing in the group is refused as the group is, and a version not in view alike. */
    WorkflowView read(GroupId group, EntryId entry, EntryVersionId version, UserId caller) {
        WorkflowView read = snapshot.execute(status -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            if (LibraryScope.scoped(database.sql(IN_VIEW), group, EntryKind.WORKFLOW, entry)
                    .param("version", version.value())
                    .query(Integer.class)
                    .optional()
                    .isEmpty()) {
                throw LibraryRefusal.VERSION_NOT_IN_VIEW.raised();
            }
            return viewIn(group, version, caller);
        });
        return requireNonNull(read);
    }

    /** Inside the caller's transaction, of a workflow version of the group's known to be in view. */
    WorkflowView viewIn(GroupId group, EntryVersionId version, UserId caller) {
        StoredWorkflow stored = StoredWorkflow.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow version " + version.value() + " holds no workflow content"));
        CodeStepsHere here = CodeStepsHere.read(database, group, released);
        WorkflowProblems.Resolved resolved = stored.resolvedIn(database, group, released, here);
        List<PinnedVersions.OfferedVersion> questions = PinnedVersions.inService(database, group, EntryKind.QUESTION);
        List<PinnedVersions.OfferedVersion> workflows = PinnedVersions.inService(database, group, EntryKind.WORKFLOW);
        Set<EntryVersionId> offeredVersions = new LinkedHashSet<>();
        questions.forEach(offered -> offeredVersions.add(offered.version()));
        workflows.forEach(offered -> offeredVersions.add(offered.version()));
        offeredVersions.removeAll(resolved.pinned().keySet());
        Map<EntryVersionId, StoredDeclarations.Halves> declared = new HashMap<>(resolved.pinned());
        declared.putAll(StoredDeclarations.ofGroupVersions(database, group, offeredVersions));
        Set<EntryVersionId> lists = stored.listsPinned();
        declared.values().forEach(halves -> halves.listsPinnedInto(lists));
        // Unlike offered.codeSteps, which the editor reads with lists in service only, this locates problems, with
        // drafts and retired lists too, as runs.takes does. The two differ on purpose; never merge them.
        Map<String, StoredDeclarations.Halves> codeStepsNamed = codeStepsNamed(resolved);
        codeStepsNamed.values().forEach(halves -> halves.listsPinnedInto(lists));
        CodeStepOffers codeSteps = codeStepsOffered(here);
        lists.addAll(codeSteps.lists());
        Map<EntryVersionId, OfferedTerms> terms = new HashMap<>(resolved.terms());
        Set<EntryVersionId> unread = new LinkedHashSet<>(lists);
        unread.removeAll(terms.keySet());
        terms.putAll(StoredQuestion.offered(database, group, unread));
        List<ContentProblem> problems = WorkflowProblems.of(stored, resolved, models);
        Set<EntryVersionId> named = new LinkedHashSet<>(stored.pinned());
        named.addAll(lists);
        return new WorkflowView(
                stored,
                declared,
                codeStepsNamed,
                PinnedVersions.of(database, group, caller, named),
                terms,
                problems,
                SendPast.of(stored.steps(), resolved.asked(), models),
                new Offers(
                        PinnedVersions.inService(database, group, EntryKind.REFERENCE_LIST),
                        offered(questions, declared),
                        offered(workflows, declared),
                        codeSteps.offered(),
                        models.all()));
    }

    /**
     * What the release declares of each code step its steps name that the group may name, where every list it pins is
     * here to be named: another group's is never told, and one not here could be named by no reader.
     */
    private static Map<String, StoredDeclarations.Halves> codeStepsNamed(WorkflowProblems.Resolved resolved) {
        Map<String, StoredDeclarations.Halves> named = new HashMap<>();
        resolved.released().forEach((name, halves) -> {
            if (resolved.nameable().contains(name)
                    && !resolved.listsUnserved()
                            .getOrDefault(name, List.of())
                            .contains(ContentProblemCode.CODE_STEP_LIST_MISSING)) {
                named.put(name, halves);
            }
        });
        return named;
    }

    /**
     * Each code step the group may name that this release holds, every list it pins in service, in name order: one
     * whose list is not could only be written into a draft that could not be submitted.
     */
    private CodeStepOffers codeStepsOffered(CodeStepsHere here) {
        List<OfferedCodeStep> offered = new ArrayList<>();
        Set<EntryVersionId> lists = new LinkedHashSet<>();
        for (String name : here.nameable()) {
            StoredDeclarations.Halves declared = released.of(name);
            if (declared != null && here.offered(name)) {
                offered.add(new OfferedCodeStep(name, declared));
                declared.listsPinnedInto(lists);
            }
        }
        return new CodeStepOffers(offered, lists);
    }

    private static List<Offered> offered(
            List<PinnedVersions.OfferedVersion> versions, Map<EntryVersionId, StoredDeclarations.Halves> declared) {
        List<Offered> offered = new ArrayList<>(versions.size());
        for (PinnedVersions.OfferedVersion version : versions) {
            offered.add(new Offered(version, requireNonNull(declared.get(version.version()))));
        }
        return offered;
    }

    /**
     * @param declared what every version it pins, and every version it may pin, declares
     * @param codeSteps what the release declares of each code step it names that the group may name, where every list
     *     it pins is here
     * @param pins every version it pins and every list any of those declarations pins, as a reader would name it
     * @param terms the terms of each of those lists the group holds, each list in its own order
     * @param problems what submitting would refuse of it now, in the order it reads
     * @param sendsPast what its steps could send past what their models take now, which refuses nothing
     */
    record WorkflowView(
            StoredWorkflow stored,
            Map<EntryVersionId, StoredDeclarations.Halves> declared,
            Map<String, StoredDeclarations.Halves> codeSteps,
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins,
            Map<EntryVersionId, OfferedTerms> terms,
            List<ContentProblem> problems,
            List<SendPast> sendsPast,
            Offers offers) {

        WorkflowView {
            declared = Map.copyOf(declared);
            codeSteps = Map.copyOf(codeSteps);
            terms = Map.copyOf(terms);
        }
    }

    /**
     * What a draft may choose from now: the group's versions in service by kind, the code steps published to it
     * that this release holds, and the models this deployment holds, each in the order it is offered.
     */
    record Offers(
            List<PinnedVersions.OfferedVersion> lists,
            List<Offered> questions,
            List<Offered> workflows,
            List<OfferedCodeStep> codeSteps,
            List<DeployedModel> models) {}

    record Offered(PinnedVersions.OfferedVersion version, StoredDeclarations.Halves declared) {}

    /** @param declared what this release declares of it */
    record OfferedCodeStep(String name, StoredDeclarations.Halves declared) {}

    /** @param lists every list those offered pin, each the group's own and in service */
    private record CodeStepOffers(List<OfferedCodeStep> offered, Set<EntryVersionId> lists) {}
}
