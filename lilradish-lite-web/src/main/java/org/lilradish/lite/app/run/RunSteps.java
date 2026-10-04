package org.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

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
import org.lilradish.lite.app.currency.GroupCurrencies;
import org.lilradish.lite.app.group.GroupReach;
import org.lilradish.lite.app.group.GroupRoles;
import org.lilradish.lite.app.pool.PersonAnswer;
import org.lilradish.lite.app.pool.PersonRows;
import org.lilradish.lite.domain.declaration.Field;
import org.lilradish.lite.domain.declaration.FieldName;
import org.lilradish.lite.domain.declaration.FieldShape;
import org.lilradish.lite.domain.file.FileName;
import org.lilradish.lite.domain.file.MediaTypeName;
import org.lilradish.lite.domain.file.StoredFile;
import org.lilradish.lite.domain.identity.GroupId;
import org.lilradish.lite.domain.identity.GroupPermission;
import org.lilradish.lite.domain.identity.GroupRole;
import org.lilradish.lite.domain.identity.SubjectId;
import org.lilradish.lite.domain.identity.UserId;
import org.lilradish.lite.domain.inference.DidNotFitReason;
import org.lilradish.lite.domain.inference.ModelCallOutcome;
import org.lilradish.lite.domain.model.ToolPrice;
import org.lilradish.lite.domain.run.ProductionId;
import org.lilradish.lite.domain.run.RunId;
import org.lilradish.lite.domain.run.RunInTree;
import org.lilradish.lite.domain.run.RunSnapshot;
import org.lilradish.lite.domain.run.RunStepSendAttemptId;
import org.lilradish.lite.domain.run.StepInputs;
import org.lilradish.lite.domain.run.StepRuns;
import org.lilradish.lite.domain.run.StepSnapshot;
import org.lilradish.lite.domain.run.TryRecord;
import org.lilradish.lite.domain.run.ValueRecord;
import org.lilradish.lite.domain.run.WorkflowStepId;
import org.lilradish.lite.domain.search.SearchFee;
import org.lilradish.lite.domain.search.SearchUses;
import org.lilradish.lite.domain.wire.JsonValue;
import org.lilradish.lite.domain.wire.StoreLabels;
import org.lilradish.lite.domain.workflow.StepProducer;
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

    // DB-SPECIFIC: an enum compared to a literal, and a comparison selected as a value, are PostgreSQL's.
    private static final String LOST = """
            select try.production_id, cast(try.did_not_fit_reason as text) as did_not_fit_reason, call.error_detail,
                   call.error_detail_truncated, call.unreadable_response is not null as unreadable_response_present
              from productions try
              join model_calls call on call.model_call_id = try.model_call_id
             where try.run_id = :run and try.producer = 'model' and try.lost_reason in ('did_not_fit', 'errored')
            """;

    // DB-SPECIFIC: enum comparisons, array casts and any(…) are PostgreSQL's.
    private static final String DECLINED = """
            select call.production_id, call.purpose = 'review' as reviewing,
                   call.declined_words, call.declined_words_altered, call.declined_words_truncated,
                   call.answer, call.answer_altered, call.answer_truncated
              from model_calls call
              join productions try on try.production_id = call.production_id
              left join reviews review on review.model_call_id = call.model_call_id and not review.for_length
             where try.run_id = :run and call.run_id = :run
               and call.production_id = any(cast(:productions as uuid[]))
               and ((call.purpose = 'produce' and try.model_call_id = call.model_call_id
                     and try.did_not_fit_reason = 'model_declined')
                    or (call.purpose = 'review' and review.did_not_fit_reason = 'model_declined'))
            """;

    // DB-SPECIFIC: enum comparisons, array casts and any(…) are PostgreSQL's.
    private static final String UNREADABLE = """
            select call.production_id, call.purpose = 'review' as reviewing,
                   call.unreadable_response, call.unreadable_response_altered, call.unreadable_response_truncated
              from model_calls call
              join productions try on try.production_id = call.production_id
              left join reviews review on review.model_call_id = call.model_call_id and not review.for_length
             where try.run_id = :run and call.run_id = :run and call.outcome = 'errored'
               and call.production_id = any(cast(:productions as uuid[]))
               and call.unreadable_response is not null
               and ((call.purpose = 'produce' and try.model_call_id = call.model_call_id)
                    or (call.purpose = 'review' and review.production_id = try.production_id))
            """;

    /*
     * Only calls to produce: a review turned away is told apart from them. Oldest first by when each was written, and
     * by its time-ordered key where two were written in one instant.
     */
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
                   file.media_type, file.byte_size
              from errored_code_returned_files kept
              join stored_files file on file.file_id = kept.file_id
             where kept.run_id = :run and kept.production_id = any(cast(:productions as uuid[]))
            """;

    private static final String HELD_FILES = """
            select file.file_id, file.original_name, file.media_type, file.byte_size
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

    RunSteps(
            JdbcClient database,
            GroupRoles roles,
            RunSnapshots snapshots,
            PlatformTransactionManager manager,
            RunDeployment deployment,
            GroupCurrencies currencies) {
        this.database = database;
        this.roles = roles;
        this.snapshots = snapshots;
        this.deployment = deployment;
        this.currencies = currencies;
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
            if (!answer.step().runs().kind().equals("question")) {
                return answer;
            }
            UUID version = requireNonNull(answer.step().runs().versionId(), "a question step pins a version");
            Optional<String> drawing = database.sql(
                            "select drawing from question_versions where entry_version_id = :version")
                    .param("version", version)
                    .query((result, row) -> Optional.ofNullable(result.getString("drawing")))
                    .single();
            return drawing.map(answer::withDrawing).orElse(answer);
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
                RunScope.subjectOf(database, caller),
                CeilingRaises.waiting(database, run, caller),
                people(StepReading.named(read)),
                RunBudget.spentByTryAsRead(database, run),
                StepReading.namesLost(read) ? lost(run) : Map.of(),
                StepReading.namesCalls(read) || !productions.isEmpty()
                        ? turnedAway(read.run(), visible, productions)
                        : Map.of(),
                deployment);
        if (requested != null) {
            StepSnapshot step = read.steps().stream()
                    .filter(each -> each.planned().id().equals(requested))
                    .findFirst()
                    .orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
            if (permitted.contains(GroupPermission.READ_INFERENCE_CONTENT)) {
                DeclinedCalls declined = declined(run, step);
                reading.withDeclined(declined.produced(), declined.reviewed());
                UnreadableCalls unreadable = unreadable(run, step);
                reading.withUnreadable(unreadable.produced(), unreadable.reviewed());
            }
            if (step.planned().runs() instanceof StepRuns.Search) {
                boolean words = permitted.contains(GroupPermission.READ_INFERENCE_CONTENT);
                reading.withSearch(
                        search(run, step, words, currencies.chosenIn(group).orElse(null)),
                        words ? sentSearchQuestion(run, step) : null,
                        words ? heldSearchQuestion(step) : null,
                        requested);
            }
        }
        List<ProductionId> codeTries = read.steps().stream()
                .flatMap(step -> step.tries().stream())
                .filter(aTry -> aTry.producer() == org.lilradish.lite.domain.workflow.StepProducer.CODE)
                .map(aTry -> aTry.id())
                .toList();
        reading.withKeptFiles(keptCodeFiles(run, codeTries));
        reading.withHeldFiles(heldFiles(run, context, requested));
        return context.above() != null || read.steps().stream().anyMatch(step -> step.beneath() != null)
                ? reading.withDetails(new RunReadDetails(database, context))
                : reading;
    }

    private DeclinedCalls declined(RunId run, StepSnapshot step) {
        List<ProductionId> tries = step.tries().stream().map(TryRecord::id).toList();
        Map<ProductionId, StepReading.DeclinedRecord> produced = new HashMap<>();
        Map<ProductionId, StepReading.DeclinedRecord> reviewed = new HashMap<>();
        if (!tries.isEmpty()) {
            database.sql(DECLINED)
                    .param("run", run.value())
                    .param(
                            "productions",
                            tries.stream().map(id -> id.value().toString()).toArray(String[]::new))
                    .query(result -> {
                        Map<ProductionId, StepReading.DeclinedRecord> destination =
                                result.getBoolean("reviewing") ? reviewed : produced;
                        destination.put(
                                new ProductionId(result.getObject("production_id", UUID.class)),
                                new StepReading.DeclinedRecord(
                                        result.getString("declined_words"),
                                        result.getBoolean("declined_words_altered"),
                                        result.getBoolean("declined_words_truncated"),
                                        result.getString("answer"),
                                        result.getBoolean("answer_altered"),
                                        result.getBoolean("answer_truncated")));
                    });
        }
        return new DeclinedCalls(produced, reviewed);
    }

    private UnreadableCalls unreadable(RunId run, StepSnapshot step) {
        List<ProductionId> tries = step.tries().stream().map(TryRecord::id).toList();
        Map<ProductionId, StepReading.UnreadableRecord> produced = new HashMap<>();
        Map<ProductionId, StepReading.UnreadableRecord> reviewed = new HashMap<>();
        if (!tries.isEmpty()) {
            database.sql(UNREADABLE)
                    .param("run", run.value())
                    .param(
                            "productions",
                            tries.stream().map(id -> id.value().toString()).toArray(String[]::new))
                    .query(result -> {
                        Map<ProductionId, StepReading.UnreadableRecord> destination =
                                result.getBoolean("reviewing") ? reviewed : produced;
                        destination.put(
                                new ProductionId(result.getObject("production_id", UUID.class)),
                                new StepReading.UnreadableRecord(
                                        result.getString("unreadable_response"),
                                        result.getBoolean("unreadable_response_altered"),
                                        result.getBoolean("unreadable_response_truncated")));
                    });
        }
        return new UnreadableCalls(produced, reviewed);
    }

    // DB-SPECIFIC: distinct on, array casts and any are PostgreSQL's.
    private static final String SEARCH_QUESTIONS = """
            select distinct on (attempt.produced_production_id)
                   attempt.produced_production_id as production_id, holder.search_question
              from run_step_send_attempts attempt
              join model_calls call on call.run_step_send_attempt_id = attempt.run_step_send_attempt_id
              join run_step_send_attempts holder on holder.run_step_send_attempt_id
                   = coalesce(attempt.repeats_attempt_id, attempt.run_step_send_attempt_id)
             where attempt.produced_production_id = any(cast(:tries as uuid[])) and attempt.run_step_kind = 'search'
             order by attempt.produced_production_id, call.created_at desc, call.model_call_id desc
            """;

    private Map<ProductionId, StepAnswers.SearchAnswer> search(
            RunId run, StepSnapshot step, boolean words, @Nullable Currency chosen) {
        List<ProductionId> tries = step.tries().stream()
                .filter(each -> each.producer() == StepProducer.MODEL)
                .filter(each -> each.number() != null)
                .map(TryRecord::id)
                .toList();
        if (tries.isEmpty()) {
            return Map.of();
        }
        String[] ids = tries.stream().map(each -> each.value().toString()).toArray(String[]::new);
        Map<ProductionId, String> questions = new HashMap<>();
        if (words) {
            database.sql(SEARCH_QUESTIONS).param("tries", ids).query(result -> {
                questions.put(
                        new ProductionId(result.getObject("production_id", UUID.class)),
                        requireNonNull(result.getString("search_question"), "A sent search holds its question"));
            });
        }
        Map<ProductionId, SearchCountRecord> counts = new HashMap<>();
        database.sql("""
                select try.production_id, call.model_call_id, cast(call.outcome as text) as outcome,
                       uses.uses, uses.uses_said, uses.searches_cut, uses.searches_not_as_it_came, uses.searches_left_out
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
                            StoreLabels.parse(ModelCallOutcome.class, result.getString("outcome")),
                            requireNonNull(uses, "An accepted search has its count"),
                            requireNonNull(said, "A counted search says whether its count was said"),
                            words && result.getBoolean("searches_cut"),
                            words && result.getBoolean("searches_not_as_it_came"),
                            words ? result.getInt("searches_left_out") : 0));
        });
        Map<UUID, List<ToolPrice>> prices = new HashMap<>();
        Map<UUID, List<StepAnswers.SearchedAnswer>> records = new HashMap<>();
        if (!counts.isEmpty()) {
            String[] calls =
                    counts.values().stream().map(each -> each.call().toString()).toArray(String[]::new);
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
            if (words) {
                database.sql("""
                        select model_call_id, searched_for, found from model_call_searches
                         where model_call_id = any(cast(:calls as uuid[])) order by model_call_id, position
                        """).param("calls", calls).query(result -> {
                    records.computeIfAbsent(result.getObject("model_call_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new StepAnswers.SearchedAnswer(
                                    result.getString("searched_for"), result.getString("found")));
                });
            }
        }
        Map<ProductionId, StepAnswers.SearchAnswer> answers = new HashMap<>();
        for (ProductionId id : tries) {
            SearchCountRecord count = counts.get(id);
            StepAnswers.SearchFeeAnswer fee = null;
            StepAnswers.NoSearchFeeAnswer noFee = null;
            if (count != null) {
                switch (SearchFee.of(
                        new SearchUses(count.uses(), count.reported()),
                        prices.getOrDefault(count.call(), List.of()),
                        chosen)) {
                    case SearchFee.Amount amount ->
                        fee = new StepAnswers.SearchFeeAnswer(
                                amount.currency().getCurrencyCode(),
                                amount.perUse().toPlainString(),
                                amount.amount().toPlainString());
                    case SearchFee.NotInCurrency absent ->
                        noFee = new StepAnswers.NoSearchFeeAnswer(
                                StepAnswers.SearchNoFeeReason.NOT_IN_CURRENCY.published(),
                                absent.chosen().getCurrencyCode());
                    case SearchFee.ManyCurrencies ignored ->
                        noFee = new StepAnswers.NoSearchFeeAnswer(
                                StepAnswers.SearchNoFeeReason.MANY_CURRENCIES.published(), null);
                    case SearchFee.NoPrice ignored ->
                        noFee = new StepAnswers.NoSearchFeeAnswer(
                                StepAnswers.SearchNoFeeReason.NO_PRICE.published(), null);
                }
            }
            List<StepAnswers.SearchedAnswer> searches = count == null ? null : records.get(count.call());
            answers.put(
                    id,
                    new StepAnswers.SearchAnswer(
                            words ? null : Boolean.TRUE,
                            words
                                    ? requireNonNull(questions.get(id), "A numbered search try has sent a question")
                                    : null,
                            searches == null ? null : List.copyOf(searches),
                            words && count != null && count.outcome() == ModelCallOutcome.CAME_BACK && searches == null
                                    ? Boolean.TRUE
                                    : null,
                            count != null && count.cut() ? Boolean.TRUE : null,
                            count != null && count.notAsItCame() ? Boolean.TRUE : null,
                            count != null && count.leftOut() > 0 ? Integer.toString(count.leftOut()) : null,
                            count == null ? null : Long.toString(count.uses()),
                            count == null
                                    ? null
                                    : count.reported()
                                            ? StepAnswers.SearchUsesCounted.SAID.published()
                                            : StepAnswers.SearchUsesCounted.AT_MOST.published(),
                            fee,
                            noFee));
        }
        return Map.copyOf(answers);
    }

    private @Nullable String sentSearchQuestion(RunId run, StepSnapshot step) {
        return database.sql("""
                select holder.search_question
                  from run_step_send_attempts attempt
                  join model_calls call on call.run_step_send_attempt_id = attempt.run_step_send_attempt_id
                  join run_step_send_attempts holder on holder.run_step_send_attempt_id
                       = coalesce(attempt.repeats_attempt_id, attempt.run_step_send_attempt_id)
                 where attempt.run_id = :run and attempt.run_step_id = :step and attempt.purpose = 'produce'
                 order by call.created_at desc, call.model_call_id desc limit 1
                """)
                .param("run", run.value())
                .param("step", step.runStep() == null ? null : step.runStep().value())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private @Nullable String heldSearchQuestion(StepSnapshot step) {
        if (step.hold() == null || step.runStep() == null) {
            return null;
        }
        return database.sql("""
                select holder.search_question from run_step_holds hold
                  join run_step_send_attempts attempt on attempt.run_step_send_attempt_id = hold.run_step_send_attempt_id
                  join run_step_send_attempts holder on holder.run_step_send_attempt_id
                       = coalesce(attempt.repeats_attempt_id, attempt.run_step_send_attempt_id)
                 where hold.run_step_id = :step and hold.released_at is null
                   and hold.reason in ('too_long', 'turned_away') and hold.run_step_kind = 'search'
                """)
                .param("step", step.runStep().value())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private record SearchCountRecord(
            UUID call,
            ModelCallOutcome outcome,
            long uses,
            boolean reported,
            boolean cut,
            boolean notAsItCame,
            int leftOut) {}

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
                                    id,
                                    new FileName(result.getString("original_name")),
                                    new MediaTypeName(result.getString("media_type")),
                                    result.getLong("byte_size")));
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
                // Candidate metadata conveys no authority; StepReading checks the complete held file.
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
                                    result.getString("media_type"),
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

    private Map<ProductionId, List<StepReading.TurnawayRecord>> turnedAway(
            RunId requested, List<RunSnapshot> visible, List<ProductionId> productions) {
        Map<ProductionId, List<StepReading.TurnawayRecord>> turned = new HashMap<>();
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
                            .add(new StepReading.TurnawayRecord(
                                    new RunStepSendAttemptId(result.getObject("run_step_send_attempt_id", UUID.class)),
                                    result.getObject("created_at", OffsetDateTime.class)
                                            .toInstant(),
                                    result.getString("said"),
                                    result.getBoolean("said_truncated"),
                                    result.getBoolean("sent_again")));
                });
        return turned;
    }

    private Map<ProductionId, StepReading.LostRecord> lost(RunId run) {
        Map<ProductionId, StepReading.LostRecord> lost = new HashMap<>();
        database.sql(LOST).param("run", run.value()).query(result -> {
            String misfit = result.getString("did_not_fit_reason");
            lost.put(
                    new ProductionId(result.getObject("production_id", UUID.class)),
                    new StepReading.LostRecord(
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

    private record DeclinedCalls(
            Map<ProductionId, StepReading.DeclinedRecord> produced,
            Map<ProductionId, StepReading.DeclinedRecord> reviewed) {}

    private record UnreadableCalls(
            Map<ProductionId, StepReading.UnreadableRecord> produced,
            Map<ProductionId, StepReading.UnreadableRecord> reviewed) {}
}
