package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.app.currency.GroupCurrencies;
import com.lilradish.lite.app.group.GroupReach;
import com.lilradish.lite.app.group.GroupRoles;
import com.lilradish.lite.app.pool.PersonAnswer;
import com.lilradish.lite.app.pool.PersonRows;
import com.lilradish.lite.domain.declaration.Field;
import com.lilradish.lite.domain.declaration.FieldName;
import com.lilradish.lite.domain.declaration.FieldShape;
import com.lilradish.lite.domain.file.FileName;
import com.lilradish.lite.domain.file.StoredFile;
import com.lilradish.lite.domain.identity.GroupId;
import com.lilradish.lite.domain.identity.GroupPermission;
import com.lilradish.lite.domain.identity.GroupRole;
import com.lilradish.lite.domain.identity.SubjectId;
import com.lilradish.lite.domain.identity.UserId;
import com.lilradish.lite.domain.inference.DidNotFitReason;
import com.lilradish.lite.domain.model.ModelCatalog;
import com.lilradish.lite.domain.model.ToolPrice;
import com.lilradish.lite.domain.registry.VersionName;
import com.lilradish.lite.domain.run.ProductionId;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.RunInTree;
import com.lilradish.lite.domain.run.RunSnapshot;
import com.lilradish.lite.domain.run.RunStepSendAttemptId;
import com.lilradish.lite.domain.run.StepInputs;
import com.lilradish.lite.domain.run.StepRuns;
import com.lilradish.lite.domain.run.StepSnapshot;
import com.lilradish.lite.domain.run.TryRecord;
import com.lilradish.lite.domain.run.ValueRecord;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.search.SearchFee;
import com.lilradish.lite.domain.search.SearchUses;
import com.lilradish.lite.domain.wire.JsonValue;
import com.lilradish.lite.domain.wire.StoreLabels;
import com.lilradish.lite.domain.workflow.StepProducer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A run's steps as one moment of the store reads them, for a member of its group who may read the run, shaped by
 * {@link StepReading} from what this reads and nothing else.
 */
@Component
final class RunSteps {

    /** While a run is running its page reads it again this long after each read; one read is one snapshot. */
    static final int REREAD_AFTER_SECONDS = 5;

    private static final String HEADER = """
            select run.number, run.parent_run_id is null as at_top from runs run where %s
            """.formatted(RunScope.RUN_IN_VIEW);

    // DB-SPECIFIC: array casts and any(…) are PostgreSQL's.
    private static final String PEOPLE = """
            select person.subject_id, person.user_id, person.display_name
              from subjects person
             where person.subject_id = any(cast(:subjects as uuid[]))
            """;

    // DB-SPECIFIC: PostgreSQL enum literals and the enum-to-text cast read the loss facts.
    private static final String LOST = """
            select try.production_id, cast(try.did_not_fit_reason as text) as did_not_fit_reason, call.error_detail,
                   call.error_detail_truncated, call.unreadable_response is not null as unreadable_response_present
              from productions try
              join model_calls call on call.model_call_id = try.model_call_id
             where try.run_id = :run and try.producer = 'model' and try.lost_reason in ('did_not_fit', 'errored')
            """;

    // DB-SPECIFIC: enum comparisons, array casts and any(…) are PostgreSQL's.
    private static final String RAW_FACTS = """
            select production.production_id, call.purpose = 'review' as reviewing,
                   coalesce(case when call.purpose = 'review'
                                 then review.did_not_fit_reason = 'model_declined'
                                 else production.did_not_fit_reason = 'model_declined' end, false)
                   and (nullif(call.declined_words, '') is not null or nullif(call.answer, '') is not null)
                       as declined_present,
                   nullif(call.unreadable_response, '') is not null as unreadable_present,
                   call.answer is not null as reply_present
              from productions production
              join model_calls call on call.production_id = production.production_id and call.run_id = production.run_id
              left join reviews review on review.production_id = production.production_id
                   and review.model_call_id = call.model_call_id and not review.for_length
             where production.run_id = :run and production.production_id = any(cast(:productions as uuid[]))
               and ((call.purpose = 'produce' and production.model_call_id = call.model_call_id)
                    or (call.purpose = 'review' and review.model_call_id = call.model_call_id))
            """;

    /*
     * Only calls to produce: a review turned away is told apart from them. Oldest first by when each was written, and
     * by its time-ordered key where two were written in one instant.
     */
    // DB-SPECIFIC: the uuid[] casts, any(…) and enum comparison are PostgreSQL's.
    private static final String TURNED_AWAY = """
            select call.production_id, call.run_step_send_attempt_id, turnaway.created_at, turnaway.said,
                   turnaway.said_truncated, turnaway.resent_at is not null as sent_again
              from model_call_turnaways turnaway
              join model_calls call on call.model_call_id = turnaway.model_call_id
             where call.run_id = any(cast(:runs as uuid[]))
               and (call.run_id = :requested or call.production_id = any(cast(:productions as uuid[])))
               and call.purpose = 'produce'
             order by turnaway.created_at, turnaway.model_call_turnaway_id
            """;

    // DB-SPECIFIC: array casts and any(…) are PostgreSQL's.
    private static final String KEPT_CODE_FILES = """
            select kept.production_id, kept.field_path, file.file_id, file.original_name,
                   file.byte_size
              from errored_code_returned_files kept
              join stored_files file on file.file_id = kept.file_id
             where kept.run_id = :run and kept.production_id = any(cast(:productions as uuid[]))
            """;

    // DB-SPECIFIC: the uuid[] cast and any(…) are PostgreSQL's.
    private static final String HELD_FILES = """
            select file.file_id, file.original_name, file.byte_size
              from run_file_holders holder
              join stored_files file on file.file_id = holder.file_id
             where holder.run_id = :run and holder.file_id = any(cast(:files as uuid[]))
            """;

    private final JdbcClient database;

    private final GroupRoles roles;

    private final RunSnapshots snapshots;

    private final TransactionTemplate snapshot;

    private final RunDeployment deployment;

    private final GroupCurrencies currencies;

    private final ModelCatalog catalog;

    RunSteps(
            JdbcClient database,
            GroupRoles roles,
            RunSnapshots snapshots,
            PlatformTransactionManager manager,
            RunDeployment deployment,
            GroupCurrencies currencies,
            ModelCatalog catalog) {
        this.database = database;
        this.roles = roles;
        this.snapshots = snapshots;
        this.deployment = deployment;
        this.currencies = currencies;
        this.catalog = requireNonNull(catalog, "RunSteps catalog must not be null");
        TransactionTemplate snapshot = new TransactionTemplate(manager);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshot.setReadOnly(true);
        this.snapshot = snapshot;
    }

    /** Somebody holding nothing in the group is refused as the group is, and a run they may not read as none. */
    StepAnswers.RunStepsAnswer steps(GroupId group, RunId run, UserId caller) {
        return requireNonNull(
                snapshot.execute(status -> reading(group, run, caller, null).steps()));
    }

    /** A step no version of the run holds is refused as none. */
    StepAnswers.StepAnswer step(GroupId group, RunId run, WorkflowStepId step, UserId caller) {
        return requireNonNull(snapshot.execute(status -> {
            StepAnswers.StepAnswer answer = reading(group, run, caller, step).step(step);
            String kind = answer.step().runs().kind();
            if (!kind.equals("question") && !kind.equals("search")) {
                return answer;
            }
            UUID version =
                    requireNonNull(answer.step().runs().versionId(), "a question or searching step pins a version");
            Optional<String> drawing = database.sql(
                            "select drawing from question_versions where entry_version_id = :version")
                    .param("version", version)
                    .query((result, row) -> Optional.ofNullable(result.getString("drawing")))
                    .single();
            return drawing.map(answer::withDrawing).orElse(answer);
        }));
    }

    /** Original material is admitted independently, for one existing spent try of a visible planned step. */
    StepAnswers.TryDetailsRecord tryDetails(
            GroupId group, RunId run, WorkflowStepId requested, int number, UserId caller) {
        return requireNonNull(snapshot.execute(status -> {
            Set<GroupRole> held = roles.heldBy(caller, group);
            GroupReach.requireMember(held);
            Set<GroupPermission> permitted = GroupReach.reachedBy(held);
            RunScope.scoped(database.sql(HEADER), group, run, caller, permitted)
                    .query((result, row) -> result.getInt("number"))
                    .optional()
                    .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
            return new RunTryDetails(database)
                    .read(
                            run,
                            requested,
                            number,
                            permitted.contains(GroupPermission.READ_INFERENCE_CONTENT),
                            currencies.chosenIn(group).orElse(null));
        }));
    }

    private StepReading reading(GroupId group, RunId run, UserId caller, @Nullable WorkflowStepId requested) {
        Set<GroupRole> held = roles.heldBy(caller, group);
        GroupReach.requireMember(held);
        Set<GroupPermission> permitted = GroupReach.reachedBy(held);
        Header header = RunScope.scoped(database.sql(HEADER), group, run, caller, permitted)
                .query((result, number) -> new Header(result.getInt("number"), result.getBoolean("at_top")))
                .optional()
                .orElseThrow(RunRefusal.RUN_NOT_IN_VIEW::raised);
        RunInTree context = snapshots.inTreeAsRead(group, run);
        RunSnapshot read = context.run();
        List<RunSnapshot> visible = StepReading.visibleRuns(read);
        List<ProductionId> productions = StepReading.visibleProductions(read);
        StepReading reading = new StepReading(
                        context,
                        header.number(),
                        header.atTop(),
                        permitted,
                        CeilingRaises.waiting(database, run, caller),
                        people(StepReading.named(read)),
                        RunBudget.spentByTryAsRead(database, run),
                        StepReading.namesLost(read) ? lost(run) : Map.of(),
                        StepReading.namesCalls(read) || !productions.isEmpty()
                                ? turnedAway(read.run(), visible, productions)
                                : Map.of(),
                        deployment)
                .withModels(catalog)
                .withVersionNames(versionNames(group, run));
        if (requested != null) {
            StepSnapshot step = read.steps().stream()
                    .filter(each -> each.planned().id().equals(requested))
                    .findFirst()
                    .orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
            if (permitted.contains(GroupPermission.READ_INFERENCE_CONTENT)) {
                reading.withRawFacts(rawFacts(run, step));
            }
            if (step.planned().runs() instanceof StepRuns.Search) {
                reading.withSearch(search(run, step, currencies.chosenIn(group).orElse(null)));
            }
        }
        List<ProductionId> codeTries = read.steps().stream()
                .flatMap(step -> step.tries().stream())
                .filter(aTry -> aTry.producer() == com.lilradish.lite.domain.workflow.StepProducer.CODE)
                .map(aTry -> aTry.id())
                .toList();
        reading.withKeptFiles(keptCodeFiles(run, codeTries));
        reading.withHeldFiles(heldFiles(run, context, requested));
        return context.above() != null
                        || read.steps().stream()
                                .anyMatch(step -> step.beneath() != null
                                        || (step.hold() != null
                                                && step.hold().reason()
                                                        == com.lilradish.lite.domain.run.RunStepHoldReason.TOO_LONG)
                                        || step.tries().stream().anyMatch(aTry -> aTry.reviews().stream()
                                                .anyMatch(com.lilradish.lite.domain.run.ReviewRecord::forLength)))
                ? reading.withDetails(new RunReadDetails(database, context))
                : reading;
    }

    private Map<UUID, VersionName> versionNames(GroupId group, RunId run) {
        Map<UUID, VersionName> names = new HashMap<>();
        database.sql("""
                        select distinct version.entry_version_id, version.version_name
                          from runs run
                          join workflow_steps step on step.entry_version_id = run.entry_version_id
                          join entry_versions version on version.entry_version_id = step.pinned_version_id
                         where run.run_id = :run and run.group_id = :group and version.version_name is not null
                        """)
                .param("run", run.value())
                .param("group", group.value())
                .query(result -> {
                    names.put(
                            result.getObject("entry_version_id", UUID.class),
                            new VersionName(result.getString("version_name")));
                });
        return Map.copyOf(names);
    }

    private Map<ProductionId, StepTries.RawFactsRecord> rawFacts(RunId run, StepSnapshot step) {
        List<ProductionId> tries = step.tries().stream().map(TryRecord::id).toList();
        Map<ProductionId, StepTries.RawFactsRecord> facts = new HashMap<>();
        if (!tries.isEmpty()) {
            database.sql(RAW_FACTS)
                    .param("run", run.value())
                    .param(
                            "productions",
                            tries.stream().map(id -> id.value().toString()).toArray(String[]::new))
                    .query(result -> {
                        ProductionId id = new ProductionId(result.getObject("production_id", UUID.class));
                        StepTries.RawFactsRecord before = facts.getOrDefault(
                                id, new StepTries.RawFactsRecord(false, false, false, false, false, false));
                        boolean reviewing = result.getBoolean("reviewing");
                        boolean declined = result.getBoolean("declined_present");
                        boolean unreadable = result.getBoolean("unreadable_present");
                        boolean reply = result.getBoolean("reply_present");
                        facts.put(
                                id,
                                new StepTries.RawFactsRecord(
                                        reviewing ? before.producedDeclined() : declined,
                                        reviewing ? before.producedUnreadable() : unreadable,
                                        reviewing ? before.producedReply() : reply,
                                        reviewing ? declined : before.reviewedDeclined(),
                                        reviewing ? unreadable : before.reviewedUnreadable(),
                                        reviewing ? reply : before.reviewedReply()));
                    });
        }
        return Map.copyOf(facts);
    }

    private Map<ProductionId, StepAnswers.SearchCountAnswer> search(
            RunId run, StepSnapshot step, @Nullable Currency chosen) {
        List<ProductionId> tries = step.tries().stream()
                .filter(each -> each.producer() == StepProducer.MODEL)
                .filter(each -> each.number() != null)
                .map(TryRecord::id)
                .toList();
        if (tries.isEmpty()) {
            return Map.of();
        }
        String[] ids = tries.stream().map(each -> each.value().toString()).toArray(String[]::new);
        Map<ProductionId, SearchCountRecord> counts = new HashMap<>();
        // DB-SPECIFIC: the enum comparison, uuid[] cast and any(…) are PostgreSQL's.
        database.sql("""
                select try.production_id, call.model_call_id,
                       uses.uses, uses.uses_said
                  from productions try
                  join model_calls call on call.model_call_id = try.model_call_id
                  left join model_call_tool_uses uses on uses.model_call_id = call.model_call_id
                 where try.run_id = :run and try.production_id = any(cast(:tries as uuid[]))
                   and call.outcome in ('came_back', 'errored', 'nothing_came_back') and call.ended_at is not null
                """).param("run", run.value()).param("tries", ids).query(result -> {
            Long uses = result.getObject("uses", Long.class);
            Boolean said = result.getObject("uses_said", Boolean.class);
            counts.put(
                    new ProductionId(result.getObject("production_id", UUID.class)),
                    new SearchCountRecord(
                            result.getObject("model_call_id", UUID.class),
                            requireNonNull(uses, "An accepted search has its count"),
                            requireNonNull(said, "A counted search says whether its count was said")));
        });
        Map<UUID, List<ToolPrice>> prices = new HashMap<>();
        if (!counts.isEmpty()) {
            String[] calls =
                    counts.values().stream().map(each -> each.call().toString()).toArray(String[]::new);
            // DB-SPECIFIC: the uuid[] cast and any(…) are PostgreSQL's.
            database.sql(
                            "select model_call_id, currency, per_use from model_call_tool_prices where model_call_id = any(cast(:calls as uuid[]))")
                    .param("calls", calls)
                    .query(result -> {
                        prices.computeIfAbsent(
                                        result.getObject("model_call_id", UUID.class), ignored -> new ArrayList<>())
                                .add(new ToolPrice(
                                        Currency.getInstance(result.getString("currency")),
                                        result.getBigDecimal("per_use")));
                    });
        }
        Map<ProductionId, StepAnswers.SearchCountAnswer> answers = new HashMap<>();
        for (ProductionId id : tries) {
            SearchCountRecord count = counts.get(id);
            StepAnswers.SearchFeeAnswer fee = null;
            StepAnswers.NoSearchFeeAnswer noFee = null;
            if (count != null) {
                SearchFee priced = SearchFee.of(
                        new SearchUses(count.uses(), count.reported()),
                        prices.getOrDefault(count.call(), List.of()),
                        chosen);
                fee = StepAnswers.SearchFeeAnswer.of(priced);
                noFee = StepAnswers.NoSearchFeeAnswer.of(priced);
            }
            answers.put(
                    id,
                    new StepAnswers.SearchCountAnswer(
                            count == null ? null : Long.toString(count.uses()),
                            count == null
                                    ? null
                                    : StepAnswers.SearchUsesCounted.of(count.reported())
                                            .published(),
                            fee,
                            noFee));
        }
        return Map.copyOf(answers);
    }

    private record SearchCountRecord(UUID call, long uses, boolean reported) {}

    private Map<UUID, StoredFile> heldFiles(RunId run, RunInTree context, @Nullable WorkflowStepId selected) {
        Set<UUID> candidates = fileCandidates(context, selected);
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StoredFile> held = new HashMap<>();
        database.sql(HELD_FILES)
                .param("run", run.value())
                .param("files", candidates.stream().map(UUID::toString).toArray(String[]::new))
                .query(result -> {
                    UUID id = result.getObject("file_id", UUID.class);
                    held.put(
                            id,
                            new StoredFile(
                                    id, new FileName(result.getString("original_name")), result.getLong("byte_size")));
                });
        return Map.copyOf(held);
    }

    private static Set<UUID> fileCandidates(RunInTree context, @Nullable WorkflowStepId selected) {
        Set<UUID> found = new LinkedHashSet<>();
        for (StepSnapshot step : context.run().steps()) {
            if (selected != null && !selected.equals(step.planned().id())) {
                continue;
            }
            if (!(step.planned().runs() instanceof StepRuns.Code code) || code.released() == null) {
                continue;
            }
            List<Field> takes = code.released().declared().takes().fields();
            if (takes.stream().anyMatch(RunSteps::holdsFiles)) {
                TryRecord newest = step.newest().orElse(null);
                List<StepInputs.BindingRecord> inputs = newest == null
                        ? StepInputs.bound(context, step).orElse(List.of())
                        : StepInputs.tookForReading(context, step, newest).stream()
                                .flatMap(Optional::stream)
                                .toList();
                for (StepInputs.BindingRecord input : inputs) {
                    if (input.binding().target() != null) {
                        Field field = fieldAt(takes, input.binding().target().names());
                        if (field != null) {
                            fileCandidates(field, input.value(), found);
                        }
                    }
                }
            }
            List<Field> gives = code.released().declared().gives().fields();
            if (gives.stream().anyMatch(RunSteps::holdsFiles)) {
                for (TryRecord aTry : step.tries()) {
                    for (ValueRecord value : aTry.values()) {
                        Field field = Field.named(gives, new FieldName(value.field()));
                        if (field != null) {
                            fileCandidates(field, value.value(), found);
                        }
                    }
                }
            }
        }
        return Set.copyOf(found);
    }

    private static @Nullable Field fieldAt(List<Field> fields, List<FieldName> names) {
        Field field = Field.named(fields, names.getFirst());
        for (FieldName name : names.subList(1, names.size())) {
            if (field == null || !(field.shape() instanceof FieldShape.Nested nested)) {
                return null;
            }
            field = Field.named(nested.fields(), name);
        }
        return field;
    }

    private static boolean holdsFiles(Field field) {
        return field.shape() instanceof FieldShape.File
                || field.shape() instanceof FieldShape.Nested nested
                        && nested.fields().stream().anyMatch(RunSteps::holdsFiles);
    }

    private static void fileCandidates(Field field, JsonValue value, Set<UUID> found) {
        if (!holdsFiles(field)) {
            return;
        }
        if (value instanceof JsonValue.JsonArray many) {
            for (JsonValue item : many.items()) {
                fileCandidates(field, item, found);
            }
        } else if (field.shape() instanceof FieldShape.File) {
            try {
                found.add(StoredFile.fromKept(value).id());
            } catch (IllegalArgumentException ignored) {
                // Candidate metadata conveys no authority; StepSources checks the complete held file.
            }
        } else if (field.shape() instanceof FieldShape.Nested nested && value instanceof JsonValue.JsonObject object) {
            for (Field child : nested.fields()) {
                if (holdsFiles(child)) {
                    fileCandidates(child, StepInputs.within(object, List.of(child.name())), found);
                }
            }
        }
    }

    private Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> keptCodeFiles(
            RunId run, List<ProductionId> productions) {
        if (productions.isEmpty()) {
            return Map.of();
        }
        Map<ProductionId, List<StepAnswers.KeptCodeFileAnswer>> kept = new HashMap<>();
        database.sql(KEPT_CODE_FILES)
                .param("run", run.value())
                .param(
                        "productions",
                        productions.stream().map(id -> id.value().toString()).toArray(String[]::new))
                .query(result -> {
                    ProductionId production = new ProductionId(result.getObject("production_id", UUID.class));
                    kept.computeIfAbsent(production, ignored -> new ArrayList<>())
                            .add(new StepAnswers.KeptCodeFileAnswer(
                                    result.getString("field_path"),
                                    result.getObject("file_id", UUID.class),
                                    result.getString("original_name"),
                                    result.getLong("byte_size")));
                });
        kept.values().forEach(files -> files.sort((first, second) -> compareFilePaths(first.path(), second.path())));
        return kept;
    }

    private static int compareFilePaths(String first, String second) {
        int left = 0;
        int right = 0;
        while (left < first.length() && right < second.length()) {
            if (first.charAt(left) == '[' && second.charAt(right) == '[') {
                int firstEnd = first.indexOf(']', left);
                int secondEnd = second.indexOf(']', right);
                int number = Long.compare(
                        Long.parseLong(first.substring(left + 1, firstEnd)),
                        Long.parseLong(second.substring(right + 1, secondEnd)));
                if (number != 0) {
                    return number;
                }
                left = firstEnd + 1;
                right = secondEnd + 1;
            } else {
                int order = Character.compare(first.charAt(left++), second.charAt(right++));
                if (order != 0) {
                    return order;
                }
            }
        }
        return Integer.compare(first.length() - left, second.length() - right);
    }

    private Map<ProductionId, List<StepTries.TurnawayRecord>> turnedAway(
            RunId requested, List<RunSnapshot> visible, List<ProductionId> productions) {
        Map<ProductionId, List<StepTries.TurnawayRecord>> turned = new HashMap<>();
        List<String> runs =
                visible.stream().map(run -> run.run().value().toString()).toList();
        database.sql(TURNED_AWAY)
                .param("runs", runs.toArray(String[]::new))
                .param("requested", requested.value())
                .param(
                        "productions",
                        productions.stream().map(id -> id.value().toString()).toArray(String[]::new))
                .query(result -> {
                    turned.computeIfAbsent(
                                    new ProductionId(result.getObject("production_id", UUID.class)),
                                    ignored -> new ArrayList<>())
                            .add(new StepTries.TurnawayRecord(
                                    new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)),
                                    result.getObject("created_at", OffsetDateTime.class)
                                            .toInstant(),
                                    result.getString("said"),
                                    result.getBoolean("said_truncated"),
                                    result.getBoolean("sent_again")));
                });
        return turned;
    }

    private Map<ProductionId, StepTries.LostRecord> lost(RunId run) {
        Map<ProductionId, StepTries.LostRecord> lost = new HashMap<>();
        database.sql(LOST).param("run", run.value()).query(result -> {
            String misfit = result.getString("did_not_fit_reason");
            lost.put(
                    new ProductionId(result.getObject("production_id", UUID.class)),
                    new StepTries.LostRecord(
                            misfit == null ? null : StoreLabels.parse(DidNotFitReason.class, misfit),
                            result.getString("error_detail"),
                            result.getBoolean("error_detail_truncated"),
                            result.getBoolean("unreadable_response_present")));
        });
        return lost;
    }

    private Map<SubjectId, PersonAnswer> people(Set<SubjectId> named) {
        Map<SubjectId, PersonAnswer> found = new HashMap<>();
        if (named.isEmpty()) {
            return found;
        }
        database.sql(PEOPLE)
                .param(
                        "subjects",
                        named.stream()
                                .map(subject -> subject.value().toString())
                                .toArray(String[]::new))
                .query(result -> {
                    PersonRows.Person person = PersonRows.person(result);
                    found.put(person.subjectId(), PersonAnswer.of(person));
                });
        return found;
    }

    private record Header(int number, boolean atTop) {}
}
