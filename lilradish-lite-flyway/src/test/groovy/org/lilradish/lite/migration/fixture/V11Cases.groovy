package org.lilradish.lite.migration.fixture

import static org.lilradish.lite.migration.fixture.SchemaKeys.*
import static org.lilradish.lite.migration.fixture.SchemaRows.*
import static org.lilradish.lite.migration.fixture.V10Cases.A_FAILURE
import static org.lilradish.lite.migration.fixture.V10Cases.A_HOLD
import static org.lilradish.lite.migration.fixture.V10Cases.A_RUN_STEP
import static org.lilradish.lite.migration.fixture.V10Cases.A_SEND_ATTEMPT
import static org.lilradish.lite.migration.fixture.V10Cases.A_SUB_RUN
import static org.lilradish.lite.migration.fixture.V10Cases.RUN_THE_CODE_STEP
import static org.lilradish.lite.migration.fixture.V9Cases.A_BINDING

/**
 * V11__production_and_call.sql's rules: the rows they are proved beside, and a case attacking each. A rule of
 * another migration that only this fixture's rows can reach is attacked here, beside them.
 */
final class V11Cases {

    // ------------------------------------------------------------ calls, tries and reviews

    static final List<String> TRY_FIXTURE = [
        "insert into app.declaration_fields (declaration_field_id, entry_version_id, entry_kind, side, position, name," +
                " kind, must_be_given, standing, standing_threshold, created_by) values ('${ANSWER}', '${VERSION}'," +
                " 'question', 'gives', 2, 'category', 'text', true, 'above_confidence', 70, '${STEWARD}')",
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, pinned_version_id," +
                " pinned_kind, producer, producer_model, producer_mode, tries, reviewer_model, reviewer_mode, created_by)" +
                " values ('${REVIEWED}', '${WORKFLOW_VERSION}', 7, 'double_check', 'entry', '${VERSION}', 'question'," +
                " 'model', 'general', 'ordinary', 3, 'general', 'research', '${STEWARD}')",
        "insert into app.workflow_steps (workflow_step_id, entry_version_id, position, name, kind, code_step, producer," +
                " tries, reviewer_model, reviewer_mode, created_by) values ('${REVIEWED_CODE}', '${NEXT_WORKFLOW_VERSION}', 2," +
                " 'close', 'code_step', '${OTHER_CODE_STEP}', 'code', 3, 'general', 'ordinary', '${STEWARD}')",
        "insert into app.run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, pinned_version_id," +
                " pinned_kind, producer, reviewed_by_model, tries, created_by) values ('${REVIEWED_RUN_STEP}', '${RUN}'," +
                " '${WORKFLOW_VERSION}', '${REVIEWED}', 'entry', '${VERSION}', 'question', 'model', true, 3," +
                " '${WORKFLOW_RUNNER}')",
        "update app.workflow_versions set may_be_helped = true, helper_model = 'general', helper_mode = 'ordinary'" +
                " where entry_version_id = '${NEXT_WORKFLOW_VERSION}'",
        "insert into app.runs (run_id, group_id, number, name, entry_id, entry_version_id, root_run_id, depth," +
                " started_with, created_by) values ('${CODE_RUN}', '${GROUP}', 5, 'Reply to Edsger', '${WORKFLOW_ENTRY}'," +
                " '${NEXT_WORKFLOW_VERSION}', '${CODE_RUN}', 0, '{}', '${STEWARD}')",
        "insert into app.run_steps (run_step_id, run_id, entry_version_id, workflow_step_id, step_kind, producer," +
                " reviewed_by_model, tries, created_by) values ('${CODE_RUN_STEP}', '${CODE_RUN}'," +
                " '${NEXT_WORKFLOW_VERSION}', '${SUB_STEP}', 'code_step', 'code', false, 3, '${WORKFLOW_RUNNER}')," +
                " ('${REVIEWED_CODE_RUN_STEP}', '${CODE_RUN}', '${NEXT_WORKFLOW_VERSION}', '${REVIEWED_CODE}', 'code_step'," +
                " 'code', true, 3, '${WORKFLOW_RUNNER}')",
        "insert into app.model_calls (model_call_id, run_id, root_run_id, purpose, root_version_id, request, model, mode," +
                " envelope_version, sent_count, came_back_count, outcome, answer, ended_at, created_by) values" +
                " ('${HELP_CALL}', '${CODE_RUN}', '${CODE_RUN}', 'help', '${NEXT_WORKFLOW_VERSION}'," +
                " '{\"question\": \"Why did it not send?\"}', 'general', 'ordinary', 1, 800, 120, 'came_back'," +
                " '{\"answer\": \"The server refused it.\"}', now(), '${WORKFLOW_RUNNER}')," +
                " ('${SPARE_HELP_CALL}', '${CODE_RUN}', '${CODE_RUN}', 'help', '${NEXT_WORKFLOW_VERSION}', '{}', 'general'," +
                " 'ordinary', 1, 800, null, null, null, null, '${WORKFLOW_RUNNER}')",
        "insert into app.run_help_exchanges (run_help_exchange_id, run_id, root_run_id, question, model_call_id," +
                " model_call_outcome, answer, created_by) values ('${HELP}', '${CODE_RUN}', '${CODE_RUN}'," +
                " 'Why did it not send?', '${HELP_CALL}', 'came_back', 'The server refused it.', '${STEWARD}')",
        "insert into app.productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer," +
                " reviewed_by_model, tries, may_run_again, pinned_version_id, try_number, producer, created_by) values" +
                " ('${PRODUCTION}', '${RUN_STEP}', '${RUN}', '${RUN}', 'question', 'model', false, 3, null, '${VERSION}', 1," +
                " 'model', '${WORKFLOW_RUNNER}')",
        "insert into app.run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind," +
                " workflow_step_id, purpose, model, mode, production_id, too_long, payload, created_by, created_by_kind)" +
                " values ('${LONG_ATTEMPT}', '${RUN_STEP}', '${RUN}', 'question', '${STEP}', 'produce', 'general'," +
                " 'ordinary', '${PRODUCTION}', true, '{\"input\": \"Summarise all of it.\"}', '${WORKFLOW_RUNNER}', 'system')",
        "insert into app.run_step_holds (run_step_hold_id, run_step_id, run_id, run_step_kind, calls_a_model, reason," +
                " run_step_send_attempt_id, created_by) values ('${HOLD}', '${RUN_STEP}', '${RUN}', 'question', true," +
                " 'too_long', '${LONG_ATTEMPT}', '${WORKFLOW_RUNNER}')",
        "insert into app.run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind," +
                " workflow_step_id, purpose, model, mode, production_id, payload, created_by, created_by_kind) values" +
                " ('${SEND_ATTEMPT}', '${RUN_STEP}', '${RUN}', 'question', '${STEP}', 'produce', 'general', 'ordinary'," +
                " '${PRODUCTION}', '{\"input\": \"Summarise.\"}', '${WORKFLOW_RUNNER}', 'system')",
        "insert into app.model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id," +
                " production_id, model, mode, envelope_version, sent_count, came_back_count, outcome, answer, ended_at," +
                " created_by) values ('${CALL}', '${RUN}', '${RUN}', 'produce', '${SEND_ATTEMPT}', '${RUN_STEP}'," +
                " '${PRODUCTION}', 'general', 'ordinary', 1, 1200, 300, 'came_back', '{\"details\": {}}', now()," +
                " '${WORKFLOW_RUNNER}')",
        "insert into app.model_call_turnaways (model_call_turnaway_id, model_call_id, said, created_by)" +
                " values ('${TURNAWAY}', '${CALL}', 'Rate limit reached.', '${WORKFLOW_RUNNER}')",
        "insert into app.model_calls (model_call_id, run_id, root_run_id, purpose, root_version_id, request, model, mode," +
                " envelope_version, sent_count, outcome, ended_at, created_by) values ('${SPENT_CALL}', '${CODE_RUN}'," +
                " '${CODE_RUN}', 'help', '${NEXT_WORKFLOW_VERSION}', '{}', 'general', 'ordinary', 1, 800, 'turned_away'," +
                " now(), '${WORKFLOW_RUNNER}')",
        "insert into app.model_call_turnaways (model_call_turnaway_id, model_call_id, said, spent_up, model_call_outcome," +
                " created_by) values ('${SPENT_TURNAWAY}', '${SPENT_CALL}', 'Quota exceeded.', true, 'turned_away'," +
                " '${WORKFLOW_RUNNER}')",
        "update app.productions set model_call_id = '${CALL}', model_call_outcome = 'came_back', ended_at = now()," +
                " ended_by = '${WORKFLOW_RUNNER}' where production_id = '${PRODUCTION}'",
        "insert into app.productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer," +
                " reviewed_by_model, tries, may_run_again, pinned_version_id, try_number, producer, explanation, created_by," +
                " created_by_kind, ended_at, ended_by, ended_by_kind) values" +
                " ('${ANSWERED}', '${RUN_STEP}', '${RUN}', '${RUN}', 'question', 'model', false, 3, null, '${VERSION}', 2," +
                " 'person', 'The order number is on the invoice.', '${MEMBER}', 'person', now(), '${MEMBER}', 'person')," +
                " ('${REVIEWED_ANSWER}', '${REVIEWED_RUN_STEP}', '${RUN}', '${RUN}', 'question', 'model', true, 3, null," +
                " '${VERSION}', 1, 'person', 'Checked by hand.', '${WORKFLOW_RUNNER}', 'system', now(), '${MEMBER}'," +
                " 'person')," +
                " ('${PENDING}', '${REVIEWED_RUN_STEP}', '${RUN}', '${RUN}', 'question', 'model', true, 3, null," +
                " '${VERSION}', 2, 'person', null, '${STEWARD}', 'person', null, null, 'person')," +
                " ('${LONG_ANSWER}', '${REVIEWED_RUN_STEP}', '${RUN}', '${RUN}', 'question', 'model', true, 3, null," +
                " '${VERSION}', 4, 'person', 'Read in full.', '${MEMBER}', 'person', now(), '${MEMBER}', 'person')",
        "insert into app.productions (production_id, run_step_id, run_id, root_run_id, run_step_kind, step_producer," +
                " reviewed_by_model, tries, may_run_again, try_number, producer, lost_reason, lost_detail, created_by," +
                " ended_at, ended_by) values ('${CODE_PRODUCTION}', '${CODE_RUN_STEP}', '${CODE_RUN}', '${CODE_RUN}'," +
                " 'code_step', 'code', false, 3, true, 1, 'code', null, null, '${WORKFLOW_RUNNER}', now(), '${WORKFLOW_RUNNER}')," +
                " ('${CODE_FAILED}', '${CODE_RUN_STEP}', '${CODE_RUN}', '${CODE_RUN}', 'code_step', 'code', false, 3, true," +
                " 2, 'code', 'errored', 'The mail server refused it.', '${WORKFLOW_RUNNER}', now(), '${WORKFLOW_RUNNER}')," +
                " ('${CLOSED}', '${REVIEWED_CODE_RUN_STEP}', '${CODE_RUN}', '${CODE_RUN}', 'code_step', 'code', true, 3," +
                " false, 1, 'code', null, null, '${WORKFLOW_RUNNER}', now(), '${WORKFLOW_RUNNER}')",
        "insert into app.run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind," +
                " workflow_step_id, purpose, model, mode, production_id, too_long, payload, created_by, created_by_kind)" +
                " values ('${REVIEW_ATTEMPT}', '${REVIEWED_RUN_STEP}', '${RUN}', 'question', '${REVIEWED}', 'review'," +
                " 'general', 'research', '${REVIEWED_ANSWER}', false, '{\"review\": \"Check it.\"}', '${WORKFLOW_RUNNER}'," +
                " 'system'), ('${HELD_REVIEW_ATTEMPT}', '${REVIEWED_RUN_STEP}', '${RUN}', 'question', '${REVIEWED}'," +
                " 'review', 'general', 'research', '${LONG_ANSWER}', true, '{\"review\": \"Check all of it.\"}'," +
                " '${WORKFLOW_RUNNER}', 'system')",
        "insert into app.model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id," +
                " production_id, model, mode, envelope_version, sent_count, came_back_count, outcome, answer, ended_at," +
                " created_by) values ('${REVIEW_CALL}', '${RUN}', '${RUN}', 'review', '${REVIEW_ATTEMPT}'," +
                " '${REVIEWED_RUN_STEP}', '${REVIEWED_ANSWER}', 'general', 'research', 1, 1500, 5000, 'came_back'," +
                " '{\"decisions\": []}', now(), '${WORKFLOW_RUNNER}')",
        "insert into app.run_step_failures (run_step_failure_id, run_step_id, run_id, run_step_kind, calls_a_model," +
                " reason, production_id, purpose, detail, created_by) values ('${UNDEPLOYED}', '${REVIEWED_CODE_RUN_STEP}'," +
                " '${CODE_RUN}', 'code_step', true, 'model_not_deployed', '${CLOSED}', 'review', 'general'," +
                " '${WORKFLOW_RUNNER}')",
        "insert into app.run_step_send_attempts (run_step_send_attempt_id, run_step_id, run_id, run_step_kind," +
                " workflow_step_id, purpose, model, mode, production_id, payload, answers_failure_id, created_by) values" +
                " ('${OUT_ATTEMPT}', '${REVIEWED_CODE_RUN_STEP}', '${CODE_RUN}', 'code_step', '${REVIEWED_CODE}', 'review'," +
                " 'general', 'ordinary', '${CLOSED}', '{\"review\": \"Check it.\"}', '${UNDEPLOYED}', '${STEWARD}')",
        "insert into app.model_calls (model_call_id, run_id, root_run_id, purpose, run_step_send_attempt_id, run_step_id," +
                " production_id, model, mode, envelope_version, sent_count, created_by) values ('${OUT_CALL}'," +
                " '${CODE_RUN}', '${CODE_RUN}', 'review', '${OUT_ATTEMPT}', '${REVIEWED_CODE_RUN_STEP}', '${CLOSED}'," +
                " 'general', 'ordinary', 1, 1200, '${WORKFLOW_RUNNER}')",
        "insert into app.production_values (production_value_id, production_id, run_step_kind, producer," +
                " pinned_version_id, declaration_field_id, field_standing, standing_threshold, value, confidence) values" +
                " ('${VALUE}', '${PRODUCTION}', 'question', 'model', '${VERSION}', '${PARENT}', 'never', null, '{}', null)," +
                " ('${SURE_VALUE}', '${PRODUCTION}', 'question', 'model', '${VERSION}', '${ANSWER}', 'above_confidence', 70," +
                " '\"Delivery\"', 80)," +
                " ('${ANSWERED_VALUE}', '${ANSWERED}', 'question', 'person', '${VERSION}', '${PARENT}', 'never', null," +
                " '{\"order\": \"A-1\"}', null)",
        "insert into app.production_values (production_value_id, production_id, run_step_kind, producer, field_name," +
                " field_standing, value) values ('${CODE_VALUE}', '${CODE_PRODUCTION}', 'code_step', 'code', 'receipt'," +
                " 'always', '\"sent\"')",
        "insert into app.reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by," +
                " reviewed_by_model, created_by) values" +
                " ('${REVIEW}', '${PRODUCTION}', '${RUN_STEP}', '${RUN}', '${WORKFLOW_RUNNER}', false, '${STEWARD}')," +
                " ('${ANSWER_REVIEW}', '${ANSWERED}', '${RUN_STEP}', '${RUN}', '${MEMBER}', false, '${STEWARD}')",
        "insert into app.reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by," +
                " reviewed_by_model, run_step_hold_id, hold_run_id, created_by) values ('${LENGTH_REVIEW}', '${ANSWERED}'," +
                " '${RUN_STEP}', '${RUN}', '${MEMBER}', false, '${HOLD}', '${RUN}', '${STEWARD}')",
        "insert into app.reviews (review_id, production_id, run_step_id, root_run_id, production_ended_by," +
                " reviewed_by_model, model_call_id, model_call_outcome, lost_reason, did_not_fit_reason, created_by," +
                " created_by_kind) values ('${MODEL_REVIEW}', '${REVIEWED_ANSWER}', '${REVIEWED_RUN_STEP}', '${RUN}'," +
                " '${MEMBER}', true, '${REVIEW_CALL}', 'came_back', 'did_not_fit', 'undecided', '${WORKFLOW_RUNNER}'," +
                " 'system')",
        "insert into app.review_decisions (review_id, production_value_id, production_id, for_length," +
                " value_needs_review, outcome, explanation) values" +
                " ('${REVIEW}', '${VALUE}', '${PRODUCTION}', false, true, 'refused', 'It names no order.')," +
                " ('${ANSWER_REVIEW}', '${ANSWERED_VALUE}', '${ANSWERED}', false, true, 'assured', null)",
    ]

    /** A row every rule admits beside the fixture, which a case or a feature alters in what it is about. */
    static final Map<String, String> A_HELP_CALL = [run_id: "'${CODE_RUN}'", root_run_id: "'${CODE_RUN}'",
                                                    purpose: "'help'", root_version_id: "'${NEXT_WORKFLOW_VERSION}'",
                                                    request: "'{}'", model: "'general'", mode: "'ordinary'",
                                                    envelope_version: "1", sent_count: "1200",
                                                    created_by: "'${WORKFLOW_RUNNER}'"]
    /** Still out, so what it names beside its attempt is all a case has to add. */
    static final Map<String, String> A_PRODUCE_CALL = [run_id: "'${RUN}'", root_run_id: "'${RUN}'", purpose: "'produce'",
                                                       run_step_id: "'${RUN_STEP}'", production_id: "'${PRODUCTION}'",
                                                       model: "'general'", mode: "'ordinary'", envelope_version: "1",
                                                       sent_count: "1200", created_by: "'${WORKFLOW_RUNNER}'"]
    static final Map<String, String> CAME_BACK = [outcome: "'came_back'", came_back_count: "300", answer: "'{}'",
                                                  ended_at: "now()"]
    static final Map<String, String> A_TURNAWAY = [model_call_id: "'${CALL}'", said: "'Busy.'",
                                                   created_by: "'${WORKFLOW_RUNNER}'"]
    static final Map<String, String> A_HELP = [run_id: "'${CODE_RUN}'", root_run_id: "'${CODE_RUN}'",
                                               question: "'Which server was it?'", model_call_id: "'${SPARE_HELP_CALL}'",
                                               created_by: "'${STEWARD}'"]
    /** MEMBER answering RUN_STEP's third try by hand. */
    static final Map<String, String> A_PRODUCTION = [run_step_id: "'${RUN_STEP}'", run_id: "'${RUN}'",
                                                     root_run_id: "'${RUN}'", run_step_kind: "'question'",
                                                     step_producer: "'model'", reviewed_by_model: "false", tries: "3",
                                                     may_run_again: "null", pinned_version_id: "'${VERSION}'",
                                                     try_number: "3",
                                                     producer: "'person'", explanation: "'Read from the invoice.'",
                                                     created_by: "'${MEMBER}'", created_by_kind: "'person'",
                                                     ended_at: "now()", ended_by: "'${MEMBER}'",
                                                     ended_by_kind: "'person'"]
    /** A model's try of RUN_STEP whose call came back, save the call A_PRODUCTION's own rules keep it from naming. */
    static final Map<String, String> BY_A_MODEL = [producer: "'model'", model_call_outcome: "'came_back'",
                                                   explanation: "null", created_by: "'${WORKFLOW_RUNNER}'",
                                                   created_by_kind: "'system'", ended_by: "'${WORKFLOW_RUNNER}'",
                                                   ended_by_kind: "'system'"]
    static final Map<String, String> A_VALUE = [production_id: "'${ANSWERED}'", run_step_kind: "'question'",
                                                producer: "'person'", pinned_version_id: "'${VERSION}'",
                                                declaration_field_id: "'${ANSWER}'", field_standing: "'above_confidence'",
                                                standing_threshold: "70", value: "'\"Delivery\"'"]
    static final Map<String, String> A_CODE_VALUE = [production_id: "'${CODE_PRODUCTION}'", run_step_kind: "'code_step'",
                                                     producer: "'code'", field_name: "'reference'",
                                                     field_standing: "'always'", value: "'\"R-1\"'"]
    /** STEWARD reviewing CODE_PRODUCTION, which nobody has reviewed yet. */
    static final Map<String, String> A_REVIEW = [production_id: "'${CODE_PRODUCTION}'", run_step_id: "'${CODE_RUN_STEP}'",
                                                 root_run_id: "'${CODE_RUN}'", production_ended_by: "'${WORKFLOW_RUNNER}'",
                                                 reviewed_by_model: "false", created_by: "'${STEWARD}'"]
    /** The model reviewing REVIEWED_ANSWER, which only MODEL_REVIEW keeps from being admitted. */
    static final Map<String, String> A_MODEL_REVIEW = [production_id: "'${REVIEWED_ANSWER}'",
                                                       run_step_id: "'${REVIEWED_RUN_STEP}'", root_run_id: "'${RUN}'",
                                                       production_ended_by: "'${MEMBER}'", reviewed_by_model: "true",
                                                       model_call_id: "'${REVIEW_CALL}'", model_call_outcome: "'came_back'",
                                                       created_by: "'${WORKFLOW_RUNNER}'", created_by_kind: "'system'"]
    /** ANSWERED_VALUE, assured on review, refused for length. */
    static final Map<String, String> A_DECISION = [review_id: "'${LENGTH_REVIEW}'",
                                                   production_value_id: "'${ANSWERED_VALUE}'",
                                                   production_id: "'${ANSWERED}'", for_length: "true",
                                                   value_needs_review: "true", outcome: "'refused'",
                                                   explanation: "'Too long to send.'",
                                                   assured_in_review_id: "'${ANSWER_REVIEW}'"]
    /** What turns A_DECISION into an assurance given on review. */
    static final Map<String, String> ON_REVIEW = [for_length: "false", outcome: "'assured'", explanation: "null",
                                                  assured_in_review_id: "null"]

    /** What each step a feature sends for is, and the mode the model it names runs in for each purpose. */
    static final Map<String, Map<String, String>> SENT_FOR = [
        (RUN_STEP)              : [run_id: RUN, run_step_kind: "question", workflow_step_id: STEP, produce: "ordinary"],
        (REVIEWED_RUN_STEP)     : [run_id: RUN, run_step_kind: "question", workflow_step_id: REVIEWED,
                                   produce: "ordinary", review: "research"],
        (REVIEWED_CODE_RUN_STEP): [run_id: CODE_RUN, run_step_kind: "code_step", workflow_step_id: REVIEWED_CODE,
                                   review: "ordinary"],
    ]
    /** What each step a feature makes a try of carries onto the try, and what the release says of running its code again. */
    static final Map<String, Map<String, String>> TRIED_ON = [
        (RUN_STEP)              : [run_id: RUN, root_run_id: RUN, run_step_kind: "question", step_producer: "model",
                                   reviewed_by_model: "false", tries: "3", may_run_again: null,
                                   pinned_version_id: VERSION],
        (REVIEWED_RUN_STEP)     : [run_id: RUN, root_run_id: RUN, run_step_kind: "question", step_producer: "model",
                                   reviewed_by_model: "true", tries: "3", may_run_again: null,
                                   pinned_version_id: VERSION],
        (CODE_RUN_STEP)         : [run_id: CODE_RUN, root_run_id: CODE_RUN, run_step_kind: "code_step",
                                   step_producer: "code", reviewed_by_model: "false", tries: "3", may_run_again: "true",
                                   pinned_version_id: null],
        (REVIEWED_CODE_RUN_STEP): [run_id: CODE_RUN, root_run_id: CODE_RUN, run_step_kind: "code_step",
                                   step_producer: "code", reviewed_by_model: "true", tries: "3", may_run_again: "false",
                                   pinned_version_id: null],
        (SPARE_RUN_STEP)        : [run_id: SUB_RUN, root_run_id: RUN, run_step_kind: "code_step", step_producer: "code",
                                   reviewed_by_model: "false", tries: "3", may_run_again: "true", pinned_version_id: null],
    ]
    /** CODE_RUN_STEP's third try, its code gone wrong for a reason of this system's about the receipt. */
    static final Map<String, String> A_CODE_FAULT = aTry(CODE_RUN_STEP, "code") + [lost_reason: "'errored'",
                                                                                     code_error_reason: "'too_long'",
                                                                                     code_error_path: "'receipt'"]
    /** A_CODE_FAULT, gone wrong instead as what REVIEWED_CODE reads of the receipt no longer matching. */
    static final Map<String, String> A_CODE_FAULT_READ = A_CODE_FAULT + [code_error_reason: "'gives_otherwise'",
                                                                         code_error_read_by_step: literal(REVIEWED_CODE)]
    /** What each production a feature reviews carries onto the review. */
    static final Map<String, Map<String, String>> REVIEWED_AS = [
        (PRODUCTION)     : [run_step_id: RUN_STEP, root_run_id: RUN, production_ended_by: WORKFLOW_RUNNER,
                            reviewed_by_model: "false"],
        (ANSWERED)       : [run_step_id: RUN_STEP, root_run_id: RUN, production_ended_by: MEMBER, reviewed_by_model: "false"],
        (REVIEWED_ANSWER): [run_step_id: REVIEWED_RUN_STEP, root_run_id: RUN, production_ended_by: MEMBER,
                            reviewed_by_model: "true"],
        (LONG_ANSWER)    : [run_step_id: REVIEWED_RUN_STEP, root_run_id: RUN, production_ended_by: MEMBER,
                            reviewed_by_model: "true"],
        (CODE_PRODUCTION): [run_step_id: CODE_RUN_STEP, root_run_id: CODE_RUN, production_ended_by: WORKFLOW_RUNNER,
                            reviewed_by_model: "false"],
        (CODE_FAILED)    : [run_step_id: CODE_RUN_STEP, root_run_id: CODE_RUN, production_ended_by: WORKFLOW_RUNNER,
                            reviewed_by_model: "false"],
        (SPARE_PRODUCTION): [run_step_id: REVIEWED_RUN_STEP, root_run_id: RUN, production_ended_by: WORKFLOW_RUNNER,
                             reviewed_by_model: "true"],
    ]

    /** SPARE_ATTEMPT, at producing PRODUCTION. */
    static final String PRODUCING = attemptAtSending(SPARE_ATTEMPT, RUN_STEP, "produce", PRODUCTION)
    /** SPARE_ATTEMPT, at reviewing REVIEWED_ANSWER. */
    static final String REVIEWING = attemptAtSending(SPARE_ATTEMPT, REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER)
    /** SPARE_ATTEMPT, at reviewing CLOSED. */
    static final String REVIEWING_THE_CODE = attemptAtSending(SPARE_ATTEMPT, REVIEWED_CODE_RUN_STEP, "review", CLOSED)
    /** The model reviewing CLOSED, still out. */
    static final Map<String, String> A_CODE_REVIEW_CALL = A_PRODUCE_CALL + [run_id: "'${CODE_RUN}'",
            root_run_id: "'${CODE_RUN}'", purpose: "'review'", run_step_id: "'${REVIEWED_CODE_RUN_STEP}'",
            production_id: "'${CLOSED}'"]
    static final String TURN_AWAY_THE_CALL_OUT = "update app.model_calls set outcome = 'turned_away', ended_at = now()" +
            " where model_call_id = '${OUT_CALL}'; "
    /** OUT_CALL turned away, and a turnaway naming it that says nothing of what may be spent. */
    static final String TURN_AWAY_THE_CALL_OUT_PLAINLY = TURN_AWAY_THE_CALL_OUT + insertInto("model_call_turnaways",
            A_TURNAWAY + [model_call_id: literal(OUT_CALL)]) + "; "
    static final String DROP_THE_MODEL_REVIEW = "delete from app.reviews where review_id = '${MODEL_REVIEW}'; "
    /** SPARE_REVIEW, the model reviewing REVIEWED_ANSWER in MODEL_REVIEW's place, and deciding. */
    static final String REVIEWED_BY_THE_MODEL = DROP_THE_MODEL_REVIEW + insertInto("reviews",
            A_MODEL_REVIEW + [review_id: "'${SPARE_REVIEW}'"]) + "; "
    static final String DROP_SURE_VALUE = "delete from app.production_values where production_value_id = '${SURE_VALUE}'; "
    static final String DROP_THE_ASSURANCE = "delete from app.review_decisions where review_id = '${ANSWER_REVIEW}'; "
    static final String DROP_THE_ANSWER_REVIEW = DROP_THE_ASSURANCE +
            "delete from app.reviews where review_id = '${ANSWER_REVIEW}'; "
    /** SPARE_REVIEW, STEWARD reviewing ANSWERED afresh, in place of ANSWER_REVIEW. */
    static final String REVIEW_THE_ANSWER_AGAIN = DROP_THE_ANSWER_REVIEW + insertInto("reviews",
            aReviewOf(ANSWERED) + [review_id: "'${SPARE_REVIEW}'"]) + "; "
    /** REVIEW_CALL ended as going wrong, in place of MODEL_REVIEW, which named it as come back. */
    static final String THE_REVIEW_CALL_WENT_WRONG = DROP_THE_MODEL_REVIEW + "update app.model_calls set" +
            " outcome = 'errored', came_back_count = null, answer = null, error_detail = 'Timed out.'" +
            " where model_call_id = '${REVIEW_CALL}'; "
    /** SPARE_REVIEW, the model's review of REVIEWED_ANSWER through REVIEW_CALL, lost as the call went wrong. */
    static final String THE_MODEL_REVIEW_WENT_WRONG = THE_REVIEW_CALL_WENT_WRONG + insertInto("reviews",
            A_MODEL_REVIEW + [review_id: "'${SPARE_REVIEW}'", model_call_outcome: "'errored'", lost_reason: "'errored'"]) +
            "; "
    /** SPARE_REVIEW, STEWARD reviewing LONG_ANSWER in the model's place, what it would be sent being too long. */
    static final String STEWARD_IN_THE_MODELS_PLACE = insertInto("reviews", aReviewOf(LONG_ANSWER) + [
            review_id: "'${SPARE_REVIEW}'", too_long_attempt_id: "'${HELD_REVIEW_ATTEMPT}'"]) + "; "
    /** SPARE_ATTEMPT, sending again for review what REVIEW_ATTEMPT holds. */
    static final String REPEAT_THE_REVIEW_ATTEMPT = insertInto("run_step_send_attempts", A_SEND_ATTEMPT +
            anAttempt(REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER) + [run_step_send_attempt_id: literal(SPARE_ATTEMPT),
            payload: "null", repeats_attempt_id: literal(REVIEW_ATTEMPT)]) + "; "
    /** SPARE_VALUE, what REVIEWED_ANSWER gave back for PARENT. */
    static final String A_VALUE_OF_REVIEWED_ANSWER = insertInto("production_values", A_VALUE + [
            production_value_id: "'${SPARE_VALUE}'", production_id: "'${REVIEWED_ANSWER}'",
            declaration_field_id: "'${PARENT}'", field_standing: "'never'", standing_threshold: "null"]) + "; "
    static final String ANSWER_THE_WAITING_TRY = "update app.productions set ended_at = now(), ended_by = '${MEMBER}'," +
            " explanation = 'Checked.' where production_id = '${PENDING}'; "
    /**
     * SPARE_HOLD, a model's question beneath RUN held back on length: SPARE_STEP in NEXT_WORKFLOW_VERSION, reached
     * in SUB_RUN as SPARE_RUN_STEP, whose first try SPARE_PRODUCTION was too long to send in SPARE_ATTEMPT.
     */
    static final String HOLD_THE_STEP_BENEATH = "insert into app.workflow_steps (workflow_step_id, entry_version_id," +
            " position, name, kind, pinned_version_id, pinned_kind, producer, producer_model, producer_mode, tries," +
            " created_by) values ('${SPARE_STEP}', '${NEXT_WORKFLOW_VERSION}', 3, 'summarise', 'entry', '${VERSION}'," +
            " 'question', 'model', 'general', 'ordinary', 3, '${STEWARD}'); " +
            insertInto("run_steps", A_RUN_STEP + [run_step_id: "'${SPARE_RUN_STEP}'", workflow_step_id: "'${SPARE_STEP}'",
                    step_kind: "'entry'", pinned_version_id: "'${VERSION}'", pinned_kind: "'question'",
                    producer: "'model'"]) + "; " +
            insertInto("productions", A_PRODUCTION + BY_A_MODEL + [production_id: "'${SPARE_PRODUCTION}'",
                    run_step_id: "'${SPARE_RUN_STEP}'", run_id: "'${SUB_RUN}'", try_number: "1", model_call_outcome: "null",
                    ended_at: "null", ended_by: "null"]) + "; " +
            insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [run_step_send_attempt_id: "'${SPARE_ATTEMPT}'",
                    run_step_id: "'${SPARE_RUN_STEP}'", run_id: "'${SUB_RUN}'", workflow_step_id: "'${SPARE_STEP}'",
                    production_id: "'${SPARE_PRODUCTION}'", too_long: "true"]) + "; " +
            insertInto("run_step_holds", A_HOLD + [run_step_hold_id: "'${SPARE_HOLD}'", run_step_id: "'${SPARE_RUN_STEP}'",
                    run_id: "'${SUB_RUN}'", run_step_kind: "'question'", calls_a_model: "true", reason: "'too_long'",
                    run_step_send_attempt_id: "'${SPARE_ATTEMPT}'"]) + "; "
    /**
     * SPARE_STEP in NEXT_WORKFLOW_VERSION, its model producing in research mode, reached in SUB_RUN as SPARE_RUN_STEP,
     * whose first try SPARE_PRODUCTION waits to be sent.
     */
    static final String PRODUCE_IN_RESEARCH_BENEATH = "insert into app.workflow_steps (workflow_step_id, entry_version_id," +
            " position, name, kind, pinned_version_id, pinned_kind, producer, producer_model, producer_mode, tries," +
            " created_by) values ('${SPARE_STEP}', '${NEXT_WORKFLOW_VERSION}', 3, 'summarise', 'entry', '${VERSION}'," +
            " 'question', 'model', 'general', 'research', 3, '${STEWARD}'); " +
            insertInto("run_steps", A_RUN_STEP + [run_step_id: "'${SPARE_RUN_STEP}'", workflow_step_id: "'${SPARE_STEP}'",
                    step_kind: "'entry'", pinned_version_id: "'${VERSION}'", pinned_kind: "'question'",
                    producer: "'model'"]) + "; " +
            insertInto("productions", A_PRODUCTION + BY_A_MODEL + [production_id: "'${SPARE_PRODUCTION}'",
                    run_step_id: "'${SPARE_RUN_STEP}'", run_id: "'${SUB_RUN}'", try_number: "1", model_call_outcome: "null",
                    ended_at: "null", ended_by: "null"]) + "; "
    /** SPARE_EXCHANGE, a question to the helper on CODE_RUN still waiting for its answer. */
    static final String ASK_WITHOUT_AN_ANSWER = "insert into app.run_help_exchanges (run_help_exchange_id, run_id," +
            " root_run_id, question, model_call_id, created_by) values ('${SPARE_EXCHANGE}', '${CODE_RUN}'," +
            " '${CODE_RUN}', 'Which server was it?', '${SPARE_HELP_CALL}', '${STEWARD}'); "
    /** SPARE_REVIEW, STEWARD opening a refusal of PRODUCTION for length. */
    static final String REFUSE_PRODUCTION_FOR_LENGTH = "insert into app.reviews (review_id, production_id, run_step_id," +
            " root_run_id, production_ended_by, reviewed_by_model, run_step_hold_id, hold_run_id, created_by) values" +
            " ('${SPARE_REVIEW}', '${PRODUCTION}', '${RUN_STEP}', '${RUN}', '${WORKFLOW_RUNNER}', false, '${HOLD}'," +
            " '${RUN}', '${STEWARD}'); "
    /** SPARE_ATTEMPT, sending again what SEND_ATTEMPT holds. */
    static final String REPEAT_THE_SEND_ATTEMPT = insertInto("run_step_send_attempts", A_SEND_ATTEMPT + [
            run_step_send_attempt_id: literal(SPARE_ATTEMPT), payload: "null", repeats_attempt_id: literal(SEND_ATTEMPT)]) + "; "
    /**
     * SPARE_PRODUCTION, the model's try of RUN_STEP, tried sending in SPARE_ATTEMPT, and SPARE_FAILURE, the step
     * failing on it for its model.
     */
    static final String A_TRY_LEFT_WAITING_BY_A_FAILURE = insertInto("productions", aTry(RUN_STEP, "model") + [
            production_id: literal(SPARE_PRODUCTION)]) + "; " +
            attemptAtSending(SPARE_ATTEMPT, RUN_STEP, "produce", SPARE_PRODUCTION) +
            insertInto("run_step_failures", A_FAILURE + [run_step_failure_id: literal(SPARE_FAILURE),
                    production_id: literal(SPARE_PRODUCTION)]) + "; "
    /** A_RUN_STEP run as SPARE_RUN_STEP, and SPARE_PRODUCTION, a try its code gave something back for. */
    static final String TRY_THE_CODE_STEP = RUN_THE_CODE_STEP + insertInto("productions",
            aTry(SPARE_RUN_STEP, "code") + [production_id: literal(SPARE_PRODUCTION)]) + "; "
    /** SPARE_FAILURE, REVIEWED_RUN_STEP failing on REVIEWED_ANSWER, the model reviewing it not being held. */
    static final String FAIL_TO_REVIEW_THE_ANSWER = insertInto("run_step_failures", A_FAILURE + [
            run_step_failure_id: literal(SPARE_FAILURE), run_step_id: literal(REVIEWED_RUN_STEP),
            production_id: literal(REVIEWED_ANSWER), purpose: "'review'"]) + "; "
    /**
     * SPARE_PRODUCTION, the model's own try of REVIEWED_RUN_STEP, come back, and SPARE_FAILURE, the step failing on
     * it, the model reviewing it not being held: a try both an attempt to produce and one to review may name.
     */
    static final String FAIL_TO_REVIEW_THE_MODELS_TRY = ANSWER_THE_WAITING_TRY +
            aModelTryWithItsCall("came_back", REVIEWED_RUN_STEP) + endTheModelTry("came_back", null) +
            insertInto("run_step_failures", A_FAILURE + [run_step_failure_id: literal(SPARE_FAILURE),
                    run_step_id: literal(REVIEWED_RUN_STEP), production_id: literal(SPARE_PRODUCTION),
                    purpose: "'review'"]) + "; "

    static final List<Map<String, String>> TRY_CASES = [
        attack("model_calls_pk", "model_calls", insertInto("model_calls", A_HELP_CALL + [model_call_id: "'${CALL}'"])),
        attack("model_calls_run_fk", "model_calls", insertInto("model_calls", A_HELP_CALL + [root_run_id: "'${RUN}'"])),
        attack("model_calls_attempt_fk", "model_calls", insertInto("model_calls",
                A_PRODUCE_CALL + CAME_BACK + [run_step_send_attempt_id: "'${ABSENT}'"])),
        attack("model_calls_attempt_not_too_long", "model_calls", insertInto("model_calls",
                A_PRODUCE_CALL + [run_step_send_attempt_id: "'${LONG_ATTEMPT}'", attempt_too_long: "true"])),
        attack("model_calls_attempt_unique", "model_calls", insertInto("model_calls",
                A_PRODUCE_CALL + CAME_BACK + [run_step_send_attempt_id: "'${SEND_ATTEMPT}'"])),
        attack("model_calls_attempt_exactly_for_produce_or_review", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [run_step_send_attempt_id: "'${SEND_ATTEMPT}'", run_step_id: "'${RUN_STEP}'"])),
        attack("model_calls_attempt_together", "model_calls", insertInto("model_calls",
                A_PRODUCE_CALL + [run_step_send_attempt_id: "'${SEND_ATTEMPT}'", production_id: "null"])),
        attack("model_calls_root_version_exactly_for_help", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [root_version_id: "null"])),
        attack("model_calls_root_version_fk", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [root_version_id: "'${WORKFLOW_VERSION}'"])),
        attack("model_calls_helper_fk", "model_calls", insertInto("model_calls", A_HELP_CALL + [mode: "'research'"])),
        attack("model_calls_mode_shape", "model_calls", insertInto("model_calls", A_HELP_CALL + [mode: "'Ordinary'"])),
        attack("model_calls_root_may_be_helped", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [root_may_be_helped: "false"])),
        attack("model_calls_request_exactly_for_help", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [request: "null"])),
        attack("model_calls_request_is_json", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [request: "'Which server was it?'"])),
        attack("model_calls_request_bounded", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [request: "'\"' || repeat('a', 8388607) || '\"'"])),
        attack("model_calls_envelope_version_positive", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [envelope_version: "0"])),
        attack("model_calls_sent_count_positive", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [sent_count: "0"])),
        attack("model_calls_came_back_count_not_negative", "model_calls", insertInto("model_calls", A_HELP_CALL + [
                outcome: "'came_back'", came_back_count: "-1", answer: "'{}'", ended_at: "now()"])),
        attack("model_calls_came_back_count_exactly_for_came_back", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [outcome: "'came_back'", answer: "'{}'", ended_at: "now()"])),
        attack("model_calls_answer_exactly_for_came_back", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [outcome: "'came_back'", came_back_count: "1", ended_at: "now()"])),
        attack("model_calls_answer_bounded", "model_calls", insertInto("model_calls", A_HELP_CALL + [
                outcome: "'came_back'", came_back_count: "1", answer: "repeat('a', 8388609)", ended_at: "now()"])),
        attack("model_calls_answer_altered_only_for_answer", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [answer_altered: "true"])),
        attack("model_calls_error_detail_exactly_for_errored", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [outcome: "'errored'", ended_at: "now()"])),
        attack("model_calls_error_detail_visible", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [outcome: "'errored'", error_detail: "''", ended_at: "now()"])),
        attack("model_calls_error_detail_bounded", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [outcome: "'errored'", error_detail: "repeat('a', 2049)", ended_at: "now()"])),
        attack("model_calls_truncated_only_for_error_detail", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [error_detail_truncated: "true"])),
        attack("model_calls_counted_by_model_only_for_came_back", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [counted_by_model: "true"])),
        attack("model_calls_ended_together", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [outcome: "'turned_away'"])),
        attack("model_calls_ended_after_created", "model_calls",
                setting("model_calls", "ended_at = created_at - interval '1 day'")),
        attack("model_calls_author_system_fk", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [created_by: "'${STEWARD}'"])),
        attack("model_calls_author_is_system", "model_calls", insertInto("model_calls",
                A_HELP_CALL + [created_by: "'${STEWARD}'", created_by_kind: "'person'"])),
        attack("model_calls_one_unended_per_step", "model_calls", insertInto("model_calls",
                A_CODE_REVIEW_CALL + [run_step_send_attempt_id: "'${ABSENT}'"])),

        attack("model_call_turnaways_pk", "model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [model_call_turnaway_id: "'${TURNAWAY}'"])),
        attack("model_call_turnaways_call_fk", "model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [model_call_id: "'${ABSENT}'"])),
        attack("model_call_turnaways_author_system_fk", "model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [created_by: "'${STEWARD}'"])),
        attack("model_call_turnaways_author_is_system", "model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [created_by: "'${STEWARD}'", created_by_kind: "'person'"])),
        attack("model_call_turnaways_said_visible", "model_call_turnaways",
                setting("model_call_turnaways", "said = 'Busy' || chr(7)")),
        attack("model_call_turnaways_said_bounded", "model_call_turnaways",
                setting("model_call_turnaways", "said = repeat('a', 2049)")),
        attack("model_call_turnaways_truncated_only_for_said", "model_call_turnaways",
                setting("model_call_turnaways", "said = null, said_truncated = true")),
        attack("model_call_turnaways_resent_after_turned_away", "model_call_turnaways",
                setting("model_call_turnaways", "resent_at = created_at - interval '1 second'")),
        attack("model_call_turnaways_model_call_outcome_fk", "model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [spent_up: "true", model_call_outcome: "'turned_away'"])),
        attack("model_call_turnaways_model_call_outcome_exactly_for_spent_up", "model_call_turnaways",
                insertInto("model_call_turnaways", A_TURNAWAY + [spent_up: "true"])),
        attack("model_call_turnaways_model_call_outcome_is_turned_away", "model_call_turnaways",
                insertInto("model_call_turnaways", A_TURNAWAY + [spent_up: "true", model_call_outcome: "'came_back'"])),
        attack("model_call_turnaways_one_spent_up_per_call", "model_call_turnaways", insertInto("model_call_turnaways",
                A_TURNAWAY + [model_call_id: "'${SPENT_CALL}'", spent_up: "true", model_call_outcome: "'turned_away'"])),
        attack("model_call_turnaways_resent_only_unspent", "model_call_turnaways", "update app.model_call_turnaways" +
                " set resent_at = created_at where model_call_turnaway_id = '${SPENT_TURNAWAY}'"),

        attack("run_help_exchanges_pk", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [run_help_exchange_id: "'${HELP}'"])),
        attack("run_help_exchanges_run_fk", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [root_run_id: "'${RUN}'"])),
        attack("run_help_exchanges_model_call_fk", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [model_call_id: "'${CALL}'"])),
        attack("run_help_exchanges_model_call_is_help", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [model_call_purpose: "'produce'"])),
        attack("run_help_exchanges_model_call_unique", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [model_call_id: "'${HELP_CALL}'"])),
        attack("run_help_exchanges_model_call_outcome_fk", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [model_call_outcome: "'came_back'"])),
        attack("run_help_exchanges_answer_only_for_came_back", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [answer: "'It was the mail server.'"])),
        attack("run_help_exchanges_question_visible", "run_help_exchanges",
                setting("run_help_exchanges", "question = 'Why' || chr(13)")),
        attack("run_help_exchanges_question_bounded", "run_help_exchanges",
                setting("run_help_exchanges", "question = repeat('a', 2049)")),
        attack("run_help_exchanges_answer_visible", "run_help_exchanges",
                setting("run_help_exchanges", "answer = ''")),
        attack("run_help_exchanges_answer_bounded", "run_help_exchanges",
                setting("run_help_exchanges", "answer = repeat('a', 8388609)")),
        attack("run_help_exchanges_author_person_fk", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [created_by: "'${WORKFLOW_RUNNER}'"])),
        attack("run_help_exchanges_author_is_person", "run_help_exchanges", insertInto("run_help_exchanges",
                A_HELP + [created_by: "'${WORKFLOW_RUNNER}'", created_by_kind: "'system'"])),

        attack("productions_pk", "productions", insertInto("productions",
                A_PRODUCTION + [production_id: "'${PRODUCTION}'"])),
        attack("productions_step_fk", "productions", insertInto("productions",
                A_PRODUCTION + [step_producer: "'person'"])),
        attack("productions_run_fk", "productions", insertInto("productions",
                A_PRODUCTION + [root_run_id: "'${OTHER_RUN}'"])),
        attack("productions_pinned_fk", "productions", insertInto("productions",
                A_PRODUCTION + [pinned_version_id: "'${NEXT_WORKFLOW_VERSION}'"])),
        attack("productions_pinned_exactly_for_question", "productions", insertInto("productions",
                A_PRODUCTION + [pinned_version_id: "null"])),
        attack("productions_try_number_unique", "productions", insertInto("productions",
                A_PRODUCTION + [try_number: "1"])),
        attack("productions_try_number_positive", "productions", insertInto("productions",
                A_PRODUCTION + [try_number: "0"])),
        attack("productions_producer_is_step_producer_or_person", "productions", insertInto("productions",
                A_PRODUCTION + BY_A_MODEL + [producer: "'code'", model_call_outcome: "null", try_number: "1"])),
        attack("productions_beyond_tries_only_for_person", "productions", insertInto("productions",
                A_PRODUCTION + [try_number: "4", created_by: "'${WORKFLOW_RUNNER}'", created_by_kind: "'system'"])),
        attack("productions_may_run_again_exactly_for_code_step", "productions", insertInto("productions",
                A_PRODUCTION + [may_run_again: "false"])),
        attack("productions_code_again_only_for_may_run_again", "productions", insertInto("productions",
                aTry(REVIEWED_CODE_RUN_STEP, "code") + [try_number: "2"])),
        attack("productions_model_call_fk", "productions", insertInto("productions",
                A_PRODUCTION + BY_A_MODEL + [model_call_id: "'${ABSENT}'"])),
        attack("productions_model_call_is_produce", "productions", insertInto("productions",
                A_PRODUCTION + BY_A_MODEL + [model_call_id: "'${CALL}'", model_call_purpose: "'review'"])),
        attack("productions_model_call_exactly_for_ended_model", "productions", insertInto("productions",
                A_PRODUCTION + [model_call_id: "'${CALL}'", model_call_outcome: "'came_back'"])),
        attack("productions_model_call_outcome_together", "productions", insertInto("productions",
                A_PRODUCTION + [model_call_outcome: "'came_back'"])),
        attack("productions_lost_as_call_ended", "productions", insertInto("productions",
                A_PRODUCTION + BY_A_MODEL + [model_call_id: "'${CALL}'", lost_reason: "'errored'"])),
        attack("productions_altered_answer_did_not_fit", "productions", insertInto("productions",
                A_PRODUCTION + [call_answer_altered: "true"])),
        attack("productions_did_not_fit_reason_exactly_for_model_did_not_fit", "productions",
                setting("productions", "did_not_fit_reason = 'not_the_shape'")),
        attack("productions_not_kept_only_when_altered", "productions", aModelTryWithItsCall("came_back") +
                endTheModelTry("came_back", "did_not_fit") + "update app.productions" +
                " set did_not_fit_reason = 'not_kept_as_it_came' where production_id = '${SPARE_PRODUCTION}'"),
        attack("productions_model_call_unique", "productions", insertInto("productions",
                A_PRODUCTION + BY_A_MODEL + [model_call_id: "'${CALL}'"])),
        attack("productions_help_exchange_fk", "productions", insertInto("productions",
                A_PRODUCTION + [run_help_exchange_id: "'${ABSENT}'"])),
        attack("productions_help_exchange_answered", "productions", insertInto("productions",
                A_PRODUCTION + [run_help_exchange_id: "'${HELP}'", help_exchange_answered: "false"])),
        attack("productions_help_exchange_only_for_person", "productions", insertInto("productions",
                A_PRODUCTION + BY_A_MODEL + [model_call_id: "'${CALL}'", run_help_exchange_id: "'${HELP}'"])),
        attack("productions_author_kind_fk", "productions", insertInto("productions",
                A_PRODUCTION + [created_by: "'${ABSENT}'"])),
        attack("productions_author_is_person_or_system", "productions", insertInto("productions",
                A_PRODUCTION + [created_by: "'${SEEDER}'", created_by_kind: "'seeder'"])),
        attack("productions_ender_kind_fk", "productions", insertInto("productions",
                A_PRODUCTION + [ended_by: "'${WORKFLOW_RUNNER}'"])),
        attack("productions_ender_is_person_or_system", "productions", insertInto("productions", A_PRODUCTION + [
                explanation: "null", ended_at: "null", ended_by: "null", ended_by_kind: "'seeder'"])),
        attack("productions_ender_is_person_exactly_for_person_producer", "productions", insertInto("productions",
                A_PRODUCTION + [ended_by: "'${WORKFLOW_RUNNER}'", ended_by_kind: "'system'"])),
        attack("productions_ended_together", "productions", insertInto("productions",
                A_PRODUCTION + [ended_at: "null"])),
        attack("productions_ended_after_created", "productions",
                setting("productions", "ended_at = created_at - interval '1 day'")),
        attack("productions_explanation_exactly_for_ended_by_person", "productions", insertInto("productions",
                A_PRODUCTION + [explanation: "null"])),
        attack("productions_explanation_visible", "productions", setting("productions", "explanation = ''")),
        attack("productions_explanation_bounded", "productions",
                setting("productions", "explanation = repeat('a', 2049)")),
        attack("productions_lost_only_for_model_or_code", "productions", insertInto("productions",
                A_PRODUCTION + [lost_reason: "'errored'"])),
        attack("productions_lost_only_for_ended", "productions", insertInto("productions", A_PRODUCTION + [
                explanation: "null", ended_at: "null", ended_by: "null", lost_reason: "'errored'"])),
        attack("productions_did_not_fit_only_for_model", "productions", "update app.productions" +
                " set lost_reason = 'did_not_fit', lost_detail = null where production_id = '${CODE_FAILED}'"),
        attack("productions_returned_by_code_only_for_code_errored", "productions",
                insertInto("productions", A_PRODUCTION + [returned_by_code: "'{}'"])),
        attack("productions_returned_by_code_bounded", "productions", "update app.productions" +
                " set returned_by_code = repeat('a', 8388609) where production_id = '${CODE_FAILED}'"),
        attack("productions_code_error_reason_only_for_code_errored", "productions", insertInto("productions",
                A_CODE_FAULT + [lost_reason: "'nothing_came_back'", code_error_reason: "'gave_nothing'",
                                code_error_path: "null"])),
        attack("productions_lost_detail_exactly_where_code_threw", "productions", insertInto("productions",
                A_PRODUCTION + [lost_detail: "'It broke.'"])),
        attack("productions_code_error_path_as_its_reason_names", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_path: "null"])),
        attack("productions_code_error_path_shape", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_path: "'Receipt'"])),
        attack("productions_code_error_path_bounded", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_path: "'ab' || repeat('.a', 511)"])),
        attack("productions_code_error_member_exactly_for_not_declared", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_member: "'extra'"])),
        attack("productions_code_error_member_one_line", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_reason: "'not_declared'", code_error_member: "'a' || chr(9)"])),
        attack("productions_code_error_member_bounded", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_reason: "'not_declared'", code_error_member: "repeat('a', 66)"])),
        attack("productions_code_error_read_by_exactly_for_gives_otherwise", "productions", insertInto("productions",
                A_CODE_FAULT + [code_error_read_by_step: literal(REVIEWED_CODE)])),
        attack("productions_code_error_read_by_step_fk", "productions", insertInto("productions",
                A_CODE_FAULT_READ + [code_error_read_by_step: literal(ABSENT)])),
        attack("productions_code_error_read_by_output_shape", "productions", insertInto("productions",
                A_CODE_FAULT_READ + [code_error_read_by_step: "null", code_error_read_by_output: "'Receipt'"])),
        attack("productions_code_error_read_by_output_bounded", "productions", insertInto("productions",
                A_CODE_FAULT_READ + [code_error_read_by_step: "null",
                                     code_error_read_by_output: "'ab' || repeat('.a', 511)"])),
        attack("productions_lost_detail_visible", "productions", "update app.productions set lost_detail = ''" +
                " where production_id = '${CODE_FAILED}'"),
        attack("productions_lost_detail_bounded", "productions", "update app.productions" +
                " set lost_detail = repeat('a', 2049) where production_id = '${CODE_FAILED}'"),
        attack("productions_truncated_only_for_lost_detail", "productions", insertInto("productions",
                A_PRODUCTION + [lost_detail_truncated: "true"])),
        attack("productions_one_unended", "productions", insertInto("productions", A_PRODUCTION + [
                run_step_id: "'${REVIEWED_RUN_STEP}'", reviewed_by_model: "true", explanation: "null",
                ended_at: "null", ended_by: "null"])),
        attack("run_step_send_attempts_produced_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [production_id: "'${ANSWERED}'"])),
        attack("run_step_send_attempts_reviewed_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(REVIEWED_RUN_STEP, "review", PENDING))),
        attack("run_step_send_attempts_one_too_long_review", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT + anAttempt(REVIEWED_RUN_STEP, "review", LONG_ANSWER) +
                        [too_long: "true", created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"])),
        attack("run_step_send_attempts_too_long_review_only_for_system", "run_step_send_attempts",
                insertInto("run_step_send_attempts", A_SEND_ATTEMPT +
                        anAttempt(REVIEWED_RUN_STEP, "review", REVIEWED_ANSWER) + [too_long: "true"])),
        attack("run_step_send_attempts_failure_unique", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + anAttempt(REVIEWED_CODE_RUN_STEP, "review", CLOSED) +
                        [answers_failure_id: literal(UNDEPLOYED)])),
        attack("run_step_send_attempts_failure_fk", "run_step_send_attempts", insertInto("run_step_send_attempts",
                A_SEND_ATTEMPT + [answers_failure_id: literal(ABSENT)])),
        attack("run_step_failures_produced_fk", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [production_id: literal(ANSWERED)])),
        attack("run_step_failures_reviewed_fk", "run_step_failures", insertInto("run_step_failures",
                A_FAILURE + [purpose: "'review'"])),

        attack("production_values_pk", "production_values", insertInto("production_values",
                A_VALUE + [production_value_id: "'${VALUE}'"])),
        attack("production_values_production_fk", "production_values", insertInto("production_values",
                A_VALUE + [producer: "'model'", confidence: "80"])),
        attack("production_values_production_yielded", "production_values", insertInto("production_values",
                A_VALUE + [production_yielded: "false"])),
        attack("production_values_pinned_fk", "production_values", insertInto("production_values",
                A_VALUE + [pinned_version_id: "'${NEXT_WORKFLOW_VERSION}'"])),
        attack("production_values_pinned_exactly_for_question", "production_values", insertInto("production_values",
                A_VALUE + [pinned_version_id: "null"])),
        attack("production_values_field_exactly_for_question", "production_values", insertInto("production_values",
                A_VALUE + [declaration_field_id: "null"])),
        attack("production_values_field_name_exactly_for_code_step", "production_values",
                insertInto("production_values", A_VALUE + [field_name: "'category'"])),
        attack("production_values_field_fk", "production_values", insertInto("production_values",
                A_VALUE + [field_standing: "'never'", standing_threshold: "null"])),
        attack("production_values_threshold_fk", "production_values", insertInto("production_values",
                A_VALUE + [standing_threshold: "60"])),
        attack("production_values_threshold_exactly_for_above_confidence", "production_values",
                insertInto("production_values", A_VALUE + [standing_threshold: "null"])),
        attack("production_values_threshold_range", "production_values", insertInto("production_values",
                A_VALUE + [standing_threshold: "101"])),
        attack("production_values_field_name_shape", "production_values", insertInto("production_values",
                A_CODE_VALUE + [field_name: "'Reference'"])),
        attack("production_values_field_unique", "production_values", insertInto("production_values",
                A_VALUE + [declaration_field_id: "'${PARENT}'", field_standing: "'never'", standing_threshold: "null"])),
        attack("production_values_field_name_unique", "production_values", insertInto("production_values",
                A_CODE_VALUE + [field_name: "'receipt'"])),
        attack("production_values_value_not_json_null", "production_values", insertInto("production_values",
                A_VALUE + [value: "'null'"])),
        attack("production_values_value_bounded", "production_values", insertInto("production_values",
                A_VALUE + [value: "to_jsonb(repeat(chr(1), 8388608))"])),
        attack("production_values_confidence_exactly_for_model_above_confidence", "production_values",
                insertInto("production_values", A_VALUE + [confidence: "50"])),
        attack("production_values_confidence_range", "production_values", insertInto("production_values",
                A_VALUE + [production_id: "'${PRODUCTION}'", producer: "'model'", confidence: "101"])),

        attack("reviews_pk", "reviews", insertInto("reviews", A_REVIEW + [review_id: "'${REVIEW}'"])),
        attack("reviews_production_fk", "reviews", insertInto("reviews",
                A_REVIEW + [production_ended_by: "'${MEMBER}'"])),
        attack("reviews_production_yielded", "reviews", insertInto("reviews", A_REVIEW + [production_yielded: "false"])),
        attack("reviews_model_call_fk", "reviews", insertInto("reviews", A_MODEL_REVIEW + [
                production_id: "'${CLOSED}'", run_step_id: "'${REVIEWED_CODE_RUN_STEP}'", root_run_id: "'${CODE_RUN}'",
                production_ended_by: "'${WORKFLOW_RUNNER}'", model_call_id: "'${ABSENT}'"])),
        attack("reviews_model_call_is_review", "reviews", insertInto("reviews",
                A_MODEL_REVIEW + [model_call_id: "'${ABSENT}'", model_call_purpose: "'produce'"])),
        attack("reviews_model_call_exactly_for_system", "reviews", insertInto("reviews",
                A_REVIEW + [model_call_id: "'${REVIEW_CALL}'", model_call_outcome: "'came_back'"])),
        attack("reviews_model_call_outcome_together", "reviews", insertInto("reviews",
                A_REVIEW + [model_call_outcome: "'came_back'"])),
        attack("reviews_lost_as_call_ended", "reviews", insertInto("reviews",
                A_MODEL_REVIEW + [lost_reason: "'nothing_came_back'"])),
        attack("reviews_did_not_fit_reason_exactly_for_did_not_fit", "reviews", insertInto("reviews",
                A_MODEL_REVIEW + [did_not_fit_reason: "'undecided'"])),
        attack("reviews_not_kept_only_when_altered", "reviews", insertInto("reviews",
                A_MODEL_REVIEW + [lost_reason: "'did_not_fit'", did_not_fit_reason: "'not_kept_as_it_came'"])),
        attack("reviews_altered_answer_did_not_fit", "reviews", insertInto("reviews",
                A_REVIEW + [call_answer_altered: "true"])),
        attack("reviews_model_call_unique", "reviews", insertInto("reviews", A_MODEL_REVIEW)),
        attack("reviews_author_kind_fk", "reviews", insertInto("reviews", A_REVIEW + [created_by: "'${ABSENT}'"])),
        attack("reviews_author_is_person_or_system", "reviews", insertInto("reviews",
                A_REVIEW + [created_by: "'${SEEDER}'", created_by_kind: "'seeder'"])),
        attack("reviews_author_is_system_exactly_for_model_on_review", "reviews", insertInto("reviews",
                A_MODEL_REVIEW + [model_call_id: "null", model_call_outcome: "null", created_by: "'${STEWARD}'",
                                  created_by_kind: "'person'"])),
        attack("reviews_reviewer_is_not_producer", "reviews", insertInto("reviews",
                A_REVIEW + [created_by: "'${WORKFLOW_RUNNER}'"])),
        attack("reviews_hold_fk", "reviews", insertInto("reviews",
                A_REVIEW + [run_step_hold_id: "'${ABSENT}'", hold_run_id: "'${CODE_RUN}'"])),
        attack("reviews_hold_run_fk", "reviews", insertInto("reviews", A_REVIEW + [
                run_step_hold_id: "'${HOLD}'", hold_run_id: "'${RUN}'"])),
        attack("reviews_hold_together", "reviews", insertInto("reviews", A_REVIEW + [run_step_hold_id: "'${HOLD}'"])),
        attack("reviews_hold_is_too_long", "reviews", insertInto("reviews", A_REVIEW + [
                run_step_hold_id: "'${HOLD}'", hold_run_id: "'${RUN}'", hold_reason: "'turned_away'"])),
        attack("reviews_too_long_attempt_fk", "reviews", insertInto("reviews",
                A_REVIEW + [too_long_attempt_id: "'${ABSENT}'"])),
        attack("reviews_attempt_too_long", "reviews", insertInto("reviews", A_REVIEW + [attempt_too_long: "false"])),
        attack("reviews_too_long_attempt_only_for_on_review", "reviews", insertInto("reviews", A_REVIEW + [
                run_step_hold_id: "'${HOLD}'", hold_run_id: "'${RUN}'", too_long_attempt_id: "'${HELD_REVIEW_ATTEMPT}'"])),
        attack("reviews_lost_only_for_system", "reviews", insertInto("reviews", A_REVIEW + [lost_reason: "'errored'"])),
        attack("reviews_one_on_review", "reviews", insertInto("reviews", aReviewOf(ANSWERED))),

        attack("review_decisions_pk", "review_decisions", insertInto("review_decisions", A_DECISION + ON_REVIEW + [
                review_id: "'${ANSWER_REVIEW}'"])),
        attack("review_decisions_review_fk", "review_decisions", insertInto("review_decisions",
                A_DECISION + [review_id: "'${ABSENT}'"])),
        attack("review_decisions_review_decided", "review_decisions", insertInto("review_decisions",
                A_DECISION + [review_decided: "false"])),
        attack("review_decisions_value_fk", "review_decisions", insertInto("review_decisions",
                A_DECISION + [production_value_id: "'${ABSENT}'"])),
        attack("review_decisions_on_review_only_for_needing_review", "review_decisions",
                insertInto("review_decisions", A_DECISION + ON_REVIEW + [value_needs_review: "false"])),
        attack("review_decisions_for_length_only_for_refused", "review_decisions", insertInto("review_decisions",
                A_DECISION + [outcome: "'assured'", explanation: "null"])),
        attack("review_decisions_explanation_exactly_for_refused", "review_decisions",
                insertInto("review_decisions", A_DECISION + [explanation: "null"])),
        attack("review_decisions_explanation_visible", "review_decisions",
                setting("review_decisions", "explanation = 'Names' || chr(133)")),
        attack("review_decisions_explanation_bounded", "review_decisions",
                setting("review_decisions", "explanation = repeat('a', 2049)")),
        attack("review_decisions_assurance_fk", "review_decisions", insertInto("review_decisions",
                A_DECISION + [assured_in_review_id: "'${REVIEW}'"])),
        attack("review_decisions_assurance_is_assured", "review_decisions", insertInto("review_decisions",
                A_DECISION + [assurance_outcome: "'refused'"])),
        attack("review_decisions_assurance_exactly_for_length_needing_review", "review_decisions",
                insertInto("review_decisions", A_DECISION + [assured_in_review_id: "null"])),
        attack("review_decisions_one_refusal", "review_decisions", insertInto("review_decisions", A_DECISION + [
                production_value_id: "'${VALUE}'", production_id: "'${PRODUCTION}'", assured_in_review_id: "'${REVIEW}'"])),
    ]

    // ------------------------------------------------------------ what went in

    /** A row every rule admits beside the fixture: what REVIEWED_ANSWER took of VALUE through REVIEWED_BINDING. */
    static final Map<String, String> A_USE = [production_id: literal(REVIEWED_ANSWER), run_id: literal(RUN),
                                              root_run_id: literal(RUN),
                                              run_step_id: literal(REVIEWED_RUN_STEP), run_step_kind: "'question'",
                                              workflow_step_id: literal(REVIEWED), binding_id: literal(REVIEWED_BINDING),
                                              binding_reads_a_constant: "false", source_workflow_step_id: literal(STEP),
                                              source_run_step_id: literal(RUN_STEP), source_run_step_kind: "'question'",
                                              source_production_id: literal(PRODUCTION),
                                              source_production_value_id: literal(VALUE)]
    /** What turns a row into one tracing to no value. */
    static final Map<String, String> FROM_NO_VALUE = [source_workflow_step_id: "null", source_run_step_id: "null",
                                                      source_run_step_kind: "null", source_production_id: "null",
                                                      source_production_value_id: "null"]
    static final Map<String, String> A_CONSTANT_USE = [production_id: literal(ANSWERED), run_id: literal(RUN),
                                                       root_run_id: literal(RUN),
                                                       run_step_id: literal(RUN_STEP), run_step_kind: "'question'",
                                                       workflow_step_id: literal(STEP),
                                                       binding_id: literal(CONSTANT_BINDING),
                                                       binding_reads_a_constant: "true"]
    /** The same constant, given to PRODUCTION. */
    static final Map<String, String> THE_CONSTANT_GIVEN_TO_THE_MODEL = A_CONSTANT_USE + [production_id: literal(PRODUCTION)]
    /** What ANSWERED took from RUN's own input through BINDING. */
    static final Map<String, String> AN_INPUT_USE = A_CONSTANT_USE + [binding_id: literal(BINDING),
                                                                      binding_reads_a_constant: "false"]
    static final Map<String, String> A_BRANCH_USE = A_USE + [production_id: "null", branch_run_id: literal(SUB_RUN),
                                                             run_step_id: literal(ROUTE_RUN_STEP), run_step_kind: "'route'",
                                                             workflow_step_id: literal(ROUTE),
                                                             binding_id: literal(DISCRIMINATOR)]
    /** What SPARE_PRODUCTION took of VALUE, made in RUN above it, from SUB_RUN's own input through SPARE_BINDING. */
    static final Map<String, String> A_USE_BENEATH = A_USE + FROM_NO_VALUE + [production_id: literal(SPARE_PRODUCTION),
            run_id: literal(SUB_RUN), run_step_id: literal(SPARE_RUN_STEP), run_step_kind: "'code_step'",
            workflow_step_id: literal(SUB_STEP), binding_id: literal(SPARE_BINDING),
            source_production_id: literal(PRODUCTION), source_production_value_id: literal(VALUE)]

    static final String DROP_THE_BRANCH_INPUT = "delete from app.production_inputs" +
            " where production_input_id = '${BRANCH_INPUT}'; "
    /** SPARE_BINDING, filling an input of ROUTE from what STEP gave back, which a route never reads. */
    static final String BIND_AN_INPUT_OF_THE_ROUTE = insertInto("bindings", A_BINDING + [
            binding_id: literal(SPARE_BINDING), workflow_step_id: literal(ROUTE), constant: "null",
            source_step_id: literal(STEP), source_path: "'category'"]) + "; "
    /** SPARE_RUN, beneath RUN, started by the step running NEXT_WORKFLOW_VERSION. */
    static final String START_A_RUN_BENEATH = insertInto("runs", A_SUB_RUN) + "; "
    /**
     * Written ahead of a row, in the same statement: SUB_STEP reached in SUB_RUN as SPARE_RUN_STEP, its try
     * SPARE_PRODUCTION, and SPARE_BINDING filling it from SUB_RUN's own input.
     */
    static final String WITH_A_TRY_BENEATH = "with reached as (" +
            insertInto("run_steps", A_RUN_STEP + [run_step_id: literal(SPARE_RUN_STEP)]) + "), tried as (" +
            insertInto("productions", aTry(SPARE_RUN_STEP, "code") + [production_id: literal(SPARE_PRODUCTION)]) +
            "), bound as (" + insertInto("bindings", A_BINDING + [binding_id: literal(SPARE_BINDING),
                    entry_version_id: literal(NEXT_WORKFLOW_VERSION), workflow_step_id: literal(SUB_STEP),
                    constant: "null", source_path: "'complaint'"]) + ") "
    /**
     * Written ahead of a row, in the same statement: SPARE_RUN started by ESCALATE_RUN_STEP, and SPARE_VALUE, made in
     * it by a try of SUB_STEP.
     */
    static final String WITH_A_VALUE_ESCALATE_GAVE_BACK = "with started as (" + insertInto("runs", A_SUB_RUN) + "), " +
            aValueMadeBeneathIn(SPARE_RUN) + " "
    /**
     * Written ahead of a row, in the same statement: SPARE_BINDING filling REVIEWED from what ROUTE gave back, and
     * SPARE_VALUE, made in SUB_RUN beneath ROUTE by a try of SUB_STEP.
     */
    static final String WITH_A_VALUE_THE_ROUTE_GAVE_BACK = "with bound as (" + insertInto("bindings", A_BINDING + [
            binding_id: literal(SPARE_BINDING), workflow_step_id: literal(REVIEWED), target_path: "'category'",
            constant: "null", source_step_id: literal(ROUTE), source_path: "'outcome'"]) + "), " +
            aValueMadeBeneathIn(SUB_RUN) + " "

    static final List<String> INPUT_FIXTURE = [
        "insert into app.bindings (binding_id, entry_version_id, workflow_step_id, target_path, constant, created_by)" +
                " values ('${CONSTANT_BINDING}', '${WORKFLOW_VERSION}', '${STEP}', 'channel', '\"email\"', '${STEWARD}')",
        "insert into app.bindings (binding_id, entry_version_id, workflow_step_id, target_path, source_step_id," +
                " source_path, created_by) values ('${REVIEWED_BINDING}', '${WORKFLOW_VERSION}', '${REVIEWED}'," +
                " 'complaint', '${STEP}', 'details', '${STEWARD}'), ('${ESCALATED_BINDING}', '${WORKFLOW_VERSION}'," +
                " '${REVIEWED}', 'channel', '${ESCALATE}', 'outcome', '${STEWARD}')",
        insertInto("production_inputs", A_CONSTANT_USE + [production_input_id: literal(INPUT)]),
        insertInto("production_inputs", A_BRANCH_USE + [production_input_id: literal(BRANCH_INPUT)]),
    ]

    static final List<Map<String, String>> INPUT_CASES = [
        attack("production_inputs_pk", "production_inputs", insertInto("production_inputs",
                A_USE + [production_input_id: literal(INPUT)])),
        attack("production_inputs_exactly_one_owner", "production_inputs", insertInto("production_inputs",
                A_USE + [production_id: "null"])),
        attack("production_inputs_try_fk", "production_inputs", insertInto("production_inputs",
                A_USE + [production_id: literal(PRODUCTION)])),
        attack("production_inputs_branch_run_fk", "production_inputs", insertInto("production_inputs",
                A_BRANCH_USE + [branch_run_id: literal(ABSENT)])),
        attack("production_inputs_step_fk", "production_inputs", insertInto("production_inputs",
                A_USE + FROM_NO_VALUE + [workflow_step_id: literal(STEP), binding_id: literal(BINDING)])),
        attack("production_inputs_run_fk", "production_inputs", insertInto("production_inputs",
                AN_INPUT_USE + [root_run_id: literal(OTHER_RUN)])),
        attack("production_inputs_binding_fk", "production_inputs", insertInto("production_inputs",
                A_USE + FROM_NO_VALUE)),
        attack("production_inputs_try_binding_unique", "production_inputs", insertInto("production_inputs",
                A_CONSTANT_USE)),
        attack("production_inputs_branch_binding_unique", "production_inputs", insertInto("production_inputs",
                A_BRANCH_USE)),
        attack("production_inputs_source_step_fk", "production_inputs", insertInto("production_inputs",
                A_USE + [binding_id: literal(ESCALATED_BINDING)])),
        attack("production_inputs_source_value_together", "production_inputs", insertInto("production_inputs",
                A_USE + [source_production_id: "null"])),
        attack("production_inputs_source_step_together", "production_inputs", insertInto("production_inputs",
                A_USE + [source_run_step_id: "null"])),
        attack("production_inputs_source_value_for_a_step", "production_inputs", insertInto("production_inputs",
                A_USE + [source_production_id: "null", source_production_value_id: "null"])),
        attack("production_inputs_no_source_value_for_a_constant", "production_inputs", insertInto("production_inputs",
                A_CONSTANT_USE + [production_id: literal(PRODUCTION), source_production_id: literal(PRODUCTION),
                                  source_production_value_id: literal(VALUE)])),
        attack("production_inputs_no_source_value_for_a_top_level_input", "production_inputs",
                insertInto("production_inputs", AN_INPUT_USE + [source_production_id: literal(PRODUCTION),
                                                                  source_production_value_id: literal(VALUE)])),
        attack("production_inputs_source_value_fk", "production_inputs", insertInto("production_inputs",
                A_USE + [source_production_value_id: literal(ANSWERED_VALUE)])),
        attack("production_inputs_source_production_fk", "production_inputs", "with valued as (" +
                insertInto("production_values", A_VALUE + [production_value_id: literal(SPARE_VALUE),
                        production_id: literal(REVIEWED_ANSWER), declaration_field_id: literal(PARENT),
                        field_standing: "'never'", standing_threshold: "null"]) + ") " +
                insertInto("production_inputs", A_USE + [source_production_id: literal(REVIEWED_ANSWER),
                        source_production_value_id: literal(SPARE_VALUE)])),
        attack("production_inputs_source_run_step_fk", "production_inputs", insertInto("production_inputs",
                A_USE + [binding_id: literal(ESCALATED_BINDING), source_workflow_step_id: literal(ESCALATE)])),
        attack("production_inputs_source_root_fk", "production_inputs", WITH_A_TRY_BENEATH +
                insertInto("production_inputs", A_USE_BENEATH + [source_production_id: literal(CODE_PRODUCTION),
                        source_production_value_id: literal(CODE_VALUE)])),
    ]

    static final List<String> FIXTURE = [*TRY_FIXTURE, *INPUT_FIXTURE]

    static final List<Map<String, String>> CASES = [*TRY_CASES, *INPUT_CASES]

    /** An attempt at sending for a step of SENT_FOR, in the mode its model runs in for the purpose. */
    static Map<String, String> anAttempt(String step, String purpose, String production) {
        def sent = SENT_FOR[step]
        // A purpose the step names no model for borrows the mode of one it does, so that such an attempt is refused
        // by the rule under attack and not by a null mode, which carries no constraint name at all.
        [run_step_id: literal(step), run_id: literal(sent.run_id), run_step_kind: literal(sent.run_step_kind),
         workflow_step_id: literal(sent.workflow_step_id), purpose: literal(purpose),
         mode: literal(sent[purpose] ?: sent.produce ?: sent.review), production_id: literal(production)]
    }

    static String attemptAtSending(String attempt, String step, String purpose, String production) {
        insertInto("run_step_send_attempts", anAttempt(step, purpose, production) + [
                run_step_send_attempt_id: literal(attempt), model: "'general'", payload: "'{}'",
                created_by: literal(WORKFLOW_RUNNER), created_by_kind: "'system'"]) + "; "
    }

    /**
     * SPARE_PRODUCTION, the model's third try of a step, asked for and waiting, and SPARE_CALL made for it through
     * SPARE_ATTEMPT: ended as named, or still out where nothing is.
     */
    static String aModelTryWithItsCall(String outcome, String step = RUN_STEP) {
        def ended = [
            came_back        : CAME_BACK,
            errored          : [outcome: "'errored'", error_detail: "'Timed out.'", ended_at: "now()"],
            nothing_came_back: [outcome: "'nothing_came_back'", ended_at: "now()"],
            turned_away      : [outcome: "'turned_away'", ended_at: "now()"],
        ][outcome] ?: [:]
        insertInto("productions", aTry(step, "model") + [production_id: literal(SPARE_PRODUCTION)]) + "; " +
                attemptAtSending(SPARE_ATTEMPT, step, "produce", SPARE_PRODUCTION) +
                insertInto("model_calls", A_PRODUCE_CALL + ended + [model_call_id: literal(SPARE_CALL),
                        run_step_send_attempt_id: literal(SPARE_ATTEMPT), run_step_id: literal(step),
                        production_id: literal(SPARE_PRODUCTION), mode: literal(SENT_FOR[step].produce)]) + "; "
    }

    /** SPARE_PRODUCTION ended, naming SPARE_CALL and the outcome given, and why it did not fit where it did not. */
    static String endTheModelTry(String outcome, String lost) {
        "update app.productions set ended_at = now(), ended_by = '${WORKFLOW_RUNNER}', model_call_id = '${SPARE_CALL}'," +
                " model_call_outcome = ${literal(outcome)}, lost_reason = ${literal(lost)}," +
                " did_not_fit_reason = ${literal(lost == 'did_not_fit' ? 'not_the_shape' : null)}" +
                " where production_id = '${SPARE_PRODUCTION}'; "
    }

    /** SPARE_PRODUCTION, the model's own try of REVIEWED_RUN_STEP, and SPARE_REVIEW_CALL, the model reviewing it. */
    static String aModelTryAndItsReviewCall() {
        ANSWER_THE_WAITING_TRY + aModelTryWithItsCall("came_back", REVIEWED_RUN_STEP) +
                endTheModelTry("came_back", null) +
                attemptAtSending(SPARE_REVIEW_ATTEMPT, REVIEWED_RUN_STEP, "review", SPARE_PRODUCTION) +
                insertInto("model_calls", A_PRODUCE_CALL + CAME_BACK + [model_call_id: literal(SPARE_REVIEW_CALL),
                        purpose: "'review'", run_step_send_attempt_id: literal(SPARE_REVIEW_ATTEMPT),
                        run_step_id: literal(REVIEWED_RUN_STEP), mode: "'research'",
                        production_id: literal(SPARE_PRODUCTION)]) + "; "
    }

    /** SPARE_HELP_CALL ended as named, in one statement, saying whether the model did its counting. */
    static String endTheSpareHelpCall(String outcome, String counted = "false") {
        def ended = outcome == "came_back" ? "came_back_count = 40, answer = '{}'" : "error_detail = 'Timed out.'"
        "update app.model_calls set outcome = '${outcome}', ended_at = now(), ${ended}, counted_by_model = ${counted}" +
                " where model_call_id = '${SPARE_HELP_CALL}'; "
    }

    /**
     * The third try of a step of TRIED_ON, the last it declares, by whoever is named: a person's or code's ended, a
     * person's answered by hand, and a model's asked for and waiting on its call.
     */
    static Map<String, String> aTry(String step, String producer) {
        def byPerson = producer == "person"
        def byModel = producer == "model"
        def author = byPerson ? MEMBER : WORKFLOW_RUNNER
        def kind = byPerson ? "person" : "system"
        [run_step_id: literal(step)] + asWritten(TRIED_ON[step]) + [try_number: "3", producer: literal(producer),
         explanation: literal(byPerson ? "Read from the invoice." : null), created_by: literal(author),
         created_by_kind: literal(kind), ended_at: byModel ? "null" : "now()", ended_by: literal(byModel ? null : author),
         ended_by_kind: literal(kind)]
    }

    /** Common table expressions: SUB_STEP reached in the run as SPARE_RUN_STEP, its try SPARE_PRODUCTION, and SPARE_VALUE. */
    private static String aValueMadeBeneathIn(String run) {
        "reached as (" + insertInto("run_steps", A_RUN_STEP + [run_step_id: literal(SPARE_RUN_STEP), run_id: literal(run)]) +
                "), tried as (" + insertInto("productions", aTry(SPARE_RUN_STEP, "code") + [
                        production_id: literal(SPARE_PRODUCTION), run_id: literal(run)]) +
                "), valued as (" + insertInto("production_values", A_CODE_VALUE + [
                        production_value_id: literal(SPARE_VALUE), production_id: literal(SPARE_PRODUCTION)]) + ")"
    }

    /** STEWARD reviewing a production of REVIEWED_AS, on review. */
    static Map<String, String> aReviewOf(String production) {
        [production_id: literal(production)] + asWritten(REVIEWED_AS[production]) + [created_by: literal(STEWARD)]
    }

    private V11Cases() {}
}
