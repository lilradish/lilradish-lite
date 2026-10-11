package com.lilradish.lite.app.run;

import static java.util.Objects.requireNonNull;

import com.lilradish.lite.domain.model.ToolPrice;
import com.lilradish.lite.domain.run.RunId;
import com.lilradish.lite.domain.run.WorkflowStepId;
import com.lilradish.lite.domain.search.SearchFee;
import com.lilradish.lite.domain.search.SearchUses;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.libprunus.core.log.annotation.DoNotLog;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Original evidence for one numbered production, after the caller's run scope has been admitted. */
final class RunTryDetails {

    // DB-SPECIFIC: PostgreSQL native-enum label comparisons.
    private static final String PRODUCTION = """
            select production.production_id, actual.run_step_id,
                   production.producer = 'code' as code, production.producer = 'model' as model,
                   planned.kind = 'search' as search
              from runs run
              join workflow_steps planned on planned.entry_version_id = run.entry_version_id
              join run_steps actual on actual.run_id = run.run_id
                   and actual.workflow_step_id = planned.workflow_step_id
                   and actual.entry_version_id = planned.entry_version_id
              join productions production on production.run_id = run.run_id
                   and production.run_step_id = actual.run_step_id
             where run.run_id = :run and planned.workflow_step_id = :requested
               and production.try_number = :number
            """;

    // DB-SPECIFIC: PostgreSQL native-enum label comparisons.
    private static final String MODEL_EVIDENCE = """
            select call.purpose = 'review' as reviewing,
                   case when call.purpose = 'review' then review.did_not_fit_reason = 'model_declined'
                        else production.did_not_fit_reason = 'model_declined' end as declining,
                   call.declined_words, call.declined_words_altered, call.declined_words_truncated,
                   call.answer, call.answer_altered, call.answer_truncated,
                   call.unreadable_response, call.unreadable_response_altered,
                   call.unreadable_response_truncated, call.unreadable_response_stopped
              from productions production
              join model_calls call on call.production_id = production.production_id and call.run_id = production.run_id
              left join reviews review on review.production_id = production.production_id
                   and review.model_call_id = call.model_call_id and not review.for_length
             where production.run_id = :run and production.production_id = :production
               and production.run_step_id = :step and call.run_step_id = :step
               and ((call.purpose = 'produce' and production.model_call_id = call.model_call_id)
                    or (call.purpose = 'review' and review.model_call_id = call.model_call_id))
            """;

    private final JdbcClient database;

    RunTryDetails(JdbcClient database) {
        this.database = database;
    }

    StepAnswers.TryDetailsRecord read(
            RunId run, WorkflowStepId requested, int number, boolean readsModels, @Nullable Currency chosen) {
        Production production = database.sql(PRODUCTION)
                .param("run", run.value())
                .param("requested", requested.value())
                .param("number", number)
                .query((result, row) -> new Production(
                        result.getObject("production_id", UUID.class),
                        result.getObject("run_step_id", UUID.class),
                        result.getBoolean("code"),
                        result.getBoolean("model"),
                        result.getBoolean("search")))
                .optional()
                .orElseThrow(RunRefusal.STEP_NOT_IN_VIEW::raised);
        StepAnswers.WentWrongAnswer wentWrong = null;
        String returned = null;
        if (production.code()) {
            // DB-SPECIFIC: PostgreSQL enum comparison to a literal.
            CodeRecord code = database.sql("""
                    select lost_detail, lost_detail_truncated, returned_by_code from productions
                     where run_id = :run and production_id = :production and run_step_id = :step and producer = 'code'
                    """)
                    .param("run", run.value())
                    .param("production", production.id())
                    .param("step", production.step())
                    .query((result, row) -> new CodeRecord(
                            result.getString("lost_detail"),
                            result.getBoolean("lost_detail_truncated"),
                            result.getString("returned_by_code")))
                    .single();
            wentWrong = code.detail() == null ? null : new StepAnswers.WentWrongAnswer(code.detail(), code.cut(), null);
            returned = code.returned();
        }
        ModelRecord produced = null;
        ModelRecord reviewed = null;
        // The absence of permission prevents the SELECT itself, independently of the answer's projection.
        if (readsModels) {
            for (ModelRecord evidence : database.sql(MODEL_EVIDENCE)
                    .param("run", run.value())
                    .param("production", production.id())
                    .param("step", production.step())
                    .query((result, row) -> new ModelRecord(
                            result.getBoolean("reviewing"),
                            result.getBoolean("declining"),
                            result.getString("declined_words"),
                            result.getBoolean("declined_words_altered"),
                            result.getBoolean("declined_words_truncated"),
                            result.getString("answer"),
                            result.getBoolean("answer_altered"),
                            result.getBoolean("answer_truncated"),
                            result.getString("unreadable_response"),
                            result.getBoolean("unreadable_response_altered"),
                            result.getBoolean("unreadable_response_truncated"),
                            result.getBoolean("unreadable_response_stopped")))
                    .list()) {
                if (evidence.reviewing()) {
                    if (reviewed != null) {
                        throw new IllegalStateException("A production has one actual model review");
                    }
                    reviewed = evidence;
                } else {
                    if (produced != null) {
                        throw new IllegalStateException("A production has one actual model production call");
                    }
                    produced = evidence;
                }
            }
        }
        StepAnswers.ReviewDetailsRecord review = reviewed == null
                ? null
                : new StepAnswers.ReviewDetailsRecord(declined(reviewed), unreadable(reviewed), reply(reviewed));
        return new StepAnswers.TryDetailsRecord(
                number,
                wentWrong,
                returned,
                declined(produced),
                unreadable(produced),
                reply(produced),
                review,
                production.search() && production.model() ? search(run, production, readsModels, chosen) : null);
    }

    private static StepAnswers.@Nullable ModelReplyRecord reply(@Nullable ModelRecord evidence) {
        return evidence == null
                        || evidence.answer() == null
                        || (evidence.declining() && !evidence.answer().isEmpty())
                ? null
                : new StepAnswers.ModelReplyRecord(evidence.answer(), evidence.answerAltered(), evidence.answerCut());
    }

    private static StepAnswers.@Nullable UnreadableResponseRecord unreadable(@Nullable ModelRecord evidence) {
        return evidence == null
                        || evidence.unreadable() == null
                        || evidence.unreadable().isEmpty()
                ? null
                : new StepAnswers.UnreadableResponseRecord(
                        evidence.unreadable(),
                        evidence.unreadableAltered(),
                        evidence.unreadableCut(),
                        evidence.unreadableStopped());
    }

    private static StepAnswers.@Nullable DeclinedAnswer declined(@Nullable ModelRecord evidence) {
        if (evidence == null || !evidence.declining()) {
            return null;
        }
        StepAnswers.DeclinedTextAnswer words =
                text(evidence.declined(), evidence.declinedAltered(), evidence.declinedCut());
        StepAnswers.DeclinedTextAnswer content =
                text(evidence.answer(), evidence.answerAltered(), evidence.answerCut());
        return words == null && content == null ? null : new StepAnswers.DeclinedAnswer(words, content, null);
    }

    private static StepAnswers.@Nullable DeclinedTextAnswer text(@Nullable String text, boolean altered, boolean cut) {
        return text == null || text.isEmpty() ? null : new StepAnswers.DeclinedTextAnswer(text, altered, cut);
    }

    private StepAnswers.@Nullable SearchAnswer search(
            RunId run, Production production, boolean words, @Nullable Currency chosen) {
        // DB-SPECIFIC: PostgreSQL enum comparisons to literals.
        List<UUID> accepted = database.sql("""
                select model_call_id from model_calls
                 where run_id = :run and production_id = :production and run_step_id = :step
                   and purpose = 'produce' and (outcome is null or outcome <> 'turned_away')
                """)
                .param("run", run.value())
                .param("production", production.id())
                .param("step", production.step())
                .query((result, row) -> requireNonNull(result.getObject("model_call_id", UUID.class)))
                .list();
        if (accepted.isEmpty()) {
            return null;
        }
        if (accepted.size() != 1) {
            throw new IllegalStateException("A numbered search try has one accepted production call");
        }
        UUID call = accepted.getFirst();
        // DB-SPECIFIC: PostgreSQL native-enum label comparisons.
        SearchCountRecord count = database.sql("""
                select uses.uses, uses.uses_said, uses.searches_cut, uses.searches_not_as_it_came,
                       uses.searches_left_out, call.outcome = 'came_back' as came_back
                  from model_calls call join model_call_tool_uses uses on uses.model_call_id = call.model_call_id
                 where call.run_id = :run and call.production_id = :production and call.model_call_id = :call
                   and call.run_step_id = :step
                   and call.ended_at is not null and call.outcome in ('came_back', 'errored', 'nothing_came_back')
                """)
                .param("run", run.value())
                .param("production", production.id())
                .param("step", production.step())
                .param("call", call)
                .query((result, row) -> new SearchCountRecord(
                        result.getLong("uses"),
                        result.getBoolean("uses_said"),
                        result.getBoolean("searches_cut"),
                        result.getBoolean("searches_not_as_it_came"),
                        result.getInt("searches_left_out"),
                        result.getBoolean("came_back")))
                .optional()
                .orElse(null);
        List<StepAnswers.SearchedAnswer> records = words && count != null
                ? database.sql("""
                select searched_for, found from model_call_searches where model_call_id = :call order by position
                """)
                        .param("call", call)
                        .query((result, row) -> new StepAnswers.SearchedAnswer(
                                result.getString("searched_for"), result.getString("found")))
                        .list()
                : List.of();
        StepAnswers.SearchFeeAnswer fee = null;
        StepAnswers.NoSearchFeeAnswer noFee = null;
        if (count != null) {
            List<ToolPrice> prices = database.sql(
                            "select currency, per_use from model_call_tool_prices where model_call_id = :call")
                    .param("call", call)
                    .query((result, row) -> new ToolPrice(
                            Currency.getInstance(result.getString("currency")), result.getBigDecimal("per_use")))
                    .list();
            SearchFee priced = SearchFee.of(new SearchUses(count.uses(), count.reported()), prices, chosen);
            fee = StepAnswers.SearchFeeAnswer.of(priced);
            noFee = StepAnswers.NoSearchFeeAnswer.of(priced);
        }
        return new StepAnswers.SearchAnswer(
                words ? null : Boolean.TRUE,
                records.isEmpty() ? null : List.copyOf(records),
                words && count != null && count.cameBack() && records.isEmpty() ? Boolean.TRUE : null,
                words && count != null && count.cut() ? Boolean.TRUE : null,
                words && count != null && count.notAsItCame() ? Boolean.TRUE : null,
                words && count != null && count.leftOut() > 0 ? Integer.toString(count.leftOut()) : null,
                count == null ? null : Long.toString(count.uses()),
                count == null
                        ? null
                        : StepAnswers.SearchUsesCounted.of(count.reported()).published(),
                fee,
                noFee);
    }

    private record Production(UUID id, UUID step, boolean code, boolean model, boolean search) {}

    private record CodeRecord(
            @DoNotLog @Nullable String detail,
            @DoNotLog boolean cut,
            @DoNotLog @Nullable String returned) {}

    private record ModelRecord(
            boolean reviewing,
            boolean declining,
            @DoNotLog @Nullable String declined,
            @DoNotLog boolean declinedAltered,
            @DoNotLog boolean declinedCut,
            @DoNotLog @Nullable String answer,
            @DoNotLog boolean answerAltered,
            @DoNotLog boolean answerCut,
            @DoNotLog @Nullable String unreadable,
            @DoNotLog boolean unreadableAltered,
            @DoNotLog boolean unreadableCut,
            @DoNotLog boolean unreadableStopped) {}

    private record SearchCountRecord(
            long uses, boolean reported, boolean cut, boolean notAsItCame, int leftOut, boolean cameBack) {}
}
