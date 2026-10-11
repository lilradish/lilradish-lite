package com.lilradish.lite.app.library;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.files.FileDeployment;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.app.search.SearchWays;
import com.lilradish.lite.domain.declaration.OfferedTerms;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.model.DeployedModel;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.registry.ContentProblemCode;
import com.lilradish.lite.domain.registry.EntryId;
import com.lilradish.lite.domain.registry.EntryKind;
import com.lilradish.lite.domain.registry.EntryVersionId;
import com.lilradish.lite.domain.search.SearchWay;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            select version.approved_at is not null as approved
              from entry_versions version
              join entries entry on entry.entry_id = version.entry_id
             where version.entry_version_id = :version and %s
            """.formatted(LibraryScope.ENTRY_IN_SCOPE);

    private final JdbcClient database;

    private final GroupRoles roles;

    private final ModelCatalog models;

    private final ReleasedCodeSteps released;

    private final FileDeployment deployment;

    private final SearchWays ways;

    private final TransactionTemplate snapshot;

    Workflows(
            JdbcClient database,
            GroupRoles roles,
            ModelCatalog models,
            ReleasedCodeSteps released,
            FileDeployment deployment,
            SearchWays ways,
            PlatformTransactionManager transactionManager) {
        this.database = database;
        this.roles = roles;
        this.models = models;
        this.released = released;
        this.deployment = deployment;
        this.ways = ways;
        this.snapshot = Snapshots.readOnly(transactionManager);
    }

    /** Somebody holding nothing in the group is refused as the group is, and a version not in view alike. */
    WorkflowView read(GroupId group, EntryId entry, EntryVersionId version, UserId caller) {
        WorkflowView read = snapshot.execute(status -> {
            GroupReach.requireMember(roles.heldBy(caller, group));
            Boolean approved = LibraryScope.scoped(database.sql(IN_VIEW), group, EntryKind.WORKFLOW, entry)
                    .param("version", version.value())
                    .query(Boolean.class)
                    .optional()
                    .orElseThrow(LibraryRefusal.VERSION_NOT_IN_VIEW::raised);
            return viewIn(group, version, caller, approved);
        });
        return requireNonNull(read);
    }

    /** Inside the caller's transaction, of a workflow version of the group's known to be in view. */
    WorkflowView viewIn(GroupId group, EntryVersionId version, UserId caller) {
        return viewIn(group, version, caller, false);
    }

    private WorkflowView viewIn(GroupId group, EntryVersionId version, UserId caller, boolean approved) {
        StoredWorkflow stored = StoredWorkflow.read(database, version)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow version " + version.value() + " holds no workflow content"));
        CodeStepsHere here = CodeStepsHere.read(database, group, released);
        WorkflowProblems.Resolved resolved = stored.resolvedIn(database, group, released, here, ways);
        Map<EntryVersionId, StoredDeclarations.Halves> declared = new HashMap<>(resolved.pinned());
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
        WorkflowProblems.Judged judged = WorkflowProblems.judged(stored, resolved, models, deployment, approved);
        Set<EntryVersionId> named = new LinkedHashSet<>(stored.pinned());
        named.addAll(lists);
        List<OfferedSearchWay> searchWays = new ArrayList<>(ways.all().size());
        Map<String, StoredDeclarations.Half> searchGives =
                HashMap.newHashMap(ways.all().size());
        for (SearchWay way : ways.all().values()) {
            StoredDeclarations.Half gives = StoredDeclarations.ofSearchWay(way);
            searchWays.add(new OfferedSearchWay(way, gives));
            searchGives.put(way.name(), gives);
        }
        return new WorkflowView(
                stored,
                declared,
                codeStepsNamed,
                searchGives,
                PinnedVersions.of(database, group, caller, named),
                terms,
                judged.problems(),
                SendPast.of(
                        stored.steps(),
                        resolved.asked(),
                        judged.codeReviewsSent(),
                        judged.searchesSent(),
                        judged.searchesReviewed(),
                        models),
                new Offers(codeSteps.offered(), searchWays, models.all()));
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

    /**
     * @param declared what every version it pins declares
     * @param codeSteps what the release declares of each code step it names that the group may name, where every list
     *     it pins is here
     * @param searchGives what each way to search this release holds gives back, by its name
     * @param pins every version it pins and every list any of those declarations pins, as a reader would name it
     * @param terms the terms of each of those lists the group holds, each list in its own order
     * @param problems the first places submitting would refuse now, and the total count
     * @param sendsPast what its steps could send past what their models take now, which refuses nothing
     */
    record WorkflowView(
            StoredWorkflow stored,
            Map<EntryVersionId, StoredDeclarations.Halves> declared,
            Map<String, StoredDeclarations.Halves> codeSteps,
            Map<String, StoredDeclarations.Half> searchGives,
            Map<EntryVersionId, PinnedVersions.PinnedVersion> pins,
            Map<EntryVersionId, OfferedTerms> terms,
            ContentProblemReport.Result problems,
            List<SendPast> sendsPast,
            Offers offers) {

        WorkflowView {
            declared = Map.copyOf(declared);
            codeSteps = Map.copyOf(codeSteps);
            searchGives = Map.copyOf(searchGives);
            terms = Map.copyOf(terms);
        }
    }

    record Offers(List<OfferedCodeStep> codeSteps, List<OfferedSearchWay> searchWays, List<DeployedModel> models) {}

    /** @param declared what this release declares of it */
    record OfferedCodeStep(String name, StoredDeclarations.Halves declared) {}

    /** @param gives what it gives back, keyed as a step running it is */
    record OfferedSearchWay(SearchWay way, StoredDeclarations.Half gives) {}

    /** @param lists every list those offered pin, each the group's own and in service */
    private record CodeStepOffers(List<OfferedCodeStep> offered, Set<EntryVersionId> lists) {}
}
