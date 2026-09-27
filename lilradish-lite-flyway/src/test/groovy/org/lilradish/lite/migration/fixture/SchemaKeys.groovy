package org.lilradish.lite.migration.fixture

/** The keys the fixture's rows are written under, and the statements more than one migration's cases share. */
final class SchemaKeys {

    static final String SEEDER = "00000000-0000-4000-8000-000000000000"
    /** Seeded by the baseline as the system actor that runs workflows, and the author of what a run records. */
    static final String WORKFLOW_RUNNER = "00000000-0000-4000-8000-000000000001"
    /** Seeded by the baseline, in the pool and holding the estate's first role. */
    static final String STEWARD = "00000001-0000-4000-8000-000000000001"
    static final String MEMBER = "00000001-0000-4000-8000-000000000002"
    /** A person subject with no pool stay, so a key collision can be reached without a second one. */
    static final String UNPOOLED = "00000001-0000-4000-8000-000000000003"
    static final String GROUP = "00000002-0000-4000-8000-000000000001"
    static final String OTHER_GROUP = "00000002-0000-4000-8000-000000000002"
    static final String STAY = "00000003-0000-4000-8000-000000000001"
    static final String HOLDING = "00000004-0000-4000-8000-000000000001"
    static final String GRANT = "00000005-0000-4000-8000-000000000001"
    static final String ENTRY = "00000006-0000-4000-8000-000000000001"
    static final String LIST_ENTRY = "00000006-0000-4000-8000-000000000002"
    static final String WORKFLOW_ENTRY = "00000006-0000-4000-8000-000000000003"
    /** Opened and approved by the seeder and not retired, so what retiring it asks can be attacked. */
    static final String VERSION = "00000007-0000-4000-8000-000000000001"
    /** The entry's one version not yet approved, opened, written and submitted by the steward. */
    static final String DRAFT = "00000007-0000-4000-8000-000000000002"
    /**
     * In service, as are WORKFLOW_VERSION and NEXT_WORKFLOW_VERSION: which standing a pinned version is at is the
     * application's to check.
     */
    static final String LIST_VERSION = "00000007-0000-4000-8000-000000000003"
    static final String WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000004"
    /** The same workflow's second version, holding nothing, so a row can be put in the wrong one. */
    static final String NEXT_WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000005"
    static final String SUBMISSION = "00000008-0000-4000-8000-000000000001"
    static final String STOP = "00000009-0000-4000-8000-000000000001"
    static final String TERM = "0000000a-0000-4000-8000-000000000001"
    static final String NEXT_TERM = "0000000a-0000-4000-8000-000000000002"
    /** Taken by the question version, first of two; PARENT is the first field it gives back, and holds fields. */
    static final String FIELD = "0000000b-0000-4000-8000-000000000001"
    static final String SIBLING = "0000000b-0000-4000-8000-000000000002"
    static final String PARENT = "0000000b-0000-4000-8000-000000000003"
    /**
     * The workflow version's first step, a model answering the question version; ROUTE is its second. A run
     * reaches both, so an attack changing the kind, the pin, the producer or the reviewing model of either is
     * refused by run_steps first, and such a case inserts a step of its own instead.
     */
    static final String STEP = "0000000c-0000-4000-8000-000000000001"
    static final String ROUTE = "0000000c-0000-4000-8000-000000000002"
    static final String ROUTE_CASE = "0000000d-0000-4000-8000-000000000001"
    static final String FALLBACK = "0000000d-0000-4000-8000-000000000002"
    /** Fills STEP's first input from the workflow's own; DISCRIMINATOR is ROUTE's, from STEP. */
    static final String BINDING = "0000000e-0000-4000-8000-000000000001"
    static final String DISCRIMINATOR = "0000000e-0000-4000-8000-000000000002"
    /** Fills STEP's second input with a constant. */
    static final String CONSTANT_BINDING = "0000000e-0000-4000-8000-000000000003"
    /** Fills REVIEWED's first input from what STEP gave back; ESCALATED_BINDING its second, from ESCALATE. */
    static final String REVIEWED_BINDING = "0000000e-0000-4000-8000-000000000004"
    static final String ESCALATED_BINDING = "0000000e-0000-4000-8000-000000000005"
    static final String PUBLICATION = "00000010-0000-4000-8000-000000000001"
    static final String EVERYWHERE = "00000010-0000-4000-8000-000000000002"
    /** NEXT_WORKFLOW_VERSION's first step, running CODE_STEP three times at most. */
    static final String SUB_STEP = "0000000c-0000-4000-8000-000000000003"
    /** The workflow version's step running NEXT_WORKFLOW_VERSION, at a position clear of those the cases insert at. */
    static final String ESCALATE = "0000000c-0000-4000-8000-000000000004"
    /** A workflow the other group owns. */
    static final String OTHER_WORKFLOW_ENTRY = "00000006-0000-4000-8000-000000000004"
    /** OTHER_WORKFLOW_ENTRY's version in service, which OTHER_CASE leads to. */
    static final String OTHER_WORKFLOW_VERSION = "00000007-0000-4000-8000-000000000006"
    /** OTHER_WORKFLOW_ENTRY's version not yet approved, opened by MEMBER. */
    static final String OTHER_WORKFLOW_DRAFT = "00000007-0000-4000-8000-000000000007"
    /** ROUTE's case for refunds, leading to OTHER_WORKFLOW_VERSION. */
    static final String OTHER_CASE = "0000000d-0000-4000-8000-000000000003"
    /** At the top, running WORKFLOW_VERSION, started by STEWARD. */
    static final String RUN = "00000011-0000-4000-8000-000000000001"
    /** At the top, running WORKFLOW_VERSION, started by MEMBER: a tree beside RUN's. */
    static final String OTHER_RUN = "00000011-0000-4000-8000-000000000002"
    /** Beneath RUN, running NEXT_WORKFLOW_VERSION, started by ROUTE_RUN_STEP. */
    static final String SUB_RUN = "00000011-0000-4000-8000-000000000003"
    /** STEP reached in RUN, held back on length and tried sending. */
    static final String RUN_STEP = "00000012-0000-4000-8000-000000000001"
    /** ROUTE reached in RUN, failed for a value no case claimed. */
    static final String ROUTE_RUN_STEP = "00000012-0000-4000-8000-000000000002"
    /** ESCALATE reached in RUN. */
    static final String ESCALATE_RUN_STEP = "00000012-0000-4000-8000-000000000003"
    /** RUN's stop, in force. */
    static final String RUN_STOP = "00000013-0000-4000-8000-000000000001"
    /** RUN's change of ceiling, awaiting approval. */
    static final String CHANGE = "00000014-0000-4000-8000-000000000001"
    /** RUN_STEP held back on length, for LONG_ATTEMPT. */
    static final String HOLD = "00000015-0000-4000-8000-000000000001"
    /** CALL's attempt, at producing PRODUCTION. */
    static final String SEND_ATTEMPT = "00000016-0000-4000-8000-000000000001"
    /** ROUTE_RUN_STEP's failure. */
    static final String FAILURE = "00000017-0000-4000-8000-000000000001"
    /** REVIEWED_CODE_RUN_STEP's failure on CLOSED, the model reviewing it not being held, answered by OUT_ATTEMPT. */
    static final String UNDEPLOYED = "00000017-0000-4000-8000-000000000002"
    /** What the question version gives back beside PARENT, standing above a confidence of 70. */
    static final String ANSWER = "0000000b-0000-4000-8000-000000000004"
    /** The workflow version's step the model answers the question version for, and reviews in research mode. */
    static final String REVIEWED = "0000000c-0000-4000-8000-000000000005"
    /** NEXT_WORKFLOW_VERSION's second step, running OTHER_CODE_STEP, which the model reviews. */
    static final String REVIEWED_CODE = "0000000c-0000-4000-8000-000000000006"
    /** At the top, running NEXT_WORKFLOW_VERSION, which may be helped, so its code step is reached in a tree of its own. */
    static final String CODE_RUN = "00000011-0000-4000-8000-000000000004"
    /** REVIEWED reached in RUN. */
    static final String REVIEWED_RUN_STEP = "00000012-0000-4000-8000-000000000004"
    /** SUB_STEP reached in CODE_RUN. */
    static final String CODE_RUN_STEP = "00000012-0000-4000-8000-000000000005"
    /** REVIEWED_CODE reached in CODE_RUN. */
    static final String REVIEWED_CODE_RUN_STEP = "00000012-0000-4000-8000-000000000006"
    /** REVIEW_CALL's attempt, at reviewing REVIEWED_ANSWER. */
    static final String REVIEW_ATTEMPT = "00000016-0000-4000-8000-000000000002"
    /** OUT_CALL's attempt, at reviewing CLOSED, made as STEWARD pressed Try sending on UNDEPLOYED. */
    static final String OUT_ATTEMPT = "00000016-0000-4000-8000-000000000003"
    /** The system's attempt at reviewing LONG_ANSWER, measured too long to send. */
    static final String HELD_REVIEW_ATTEMPT = "00000016-0000-4000-8000-000000000004"
    /** An attempt at producing PRODUCTION, too long to send, which HOLD holds RUN_STEP back for. */
    static final String LONG_ATTEMPT = "00000016-0000-4000-8000-000000000005"
    /** SEND_ATTEMPT's call, come back. */
    static final String CALL = "00000018-0000-4000-8000-000000000001"
    /** REVIEW_ATTEMPT's call, come back. */
    static final String REVIEW_CALL = "00000018-0000-4000-8000-000000000002"
    /** CODE_RUN's call to the helper, come back. */
    static final String HELP_CALL = "00000018-0000-4000-8000-000000000003"
    /** CODE_RUN's call to the helper, still out. */
    static final String SPARE_HELP_CALL = "00000018-0000-4000-8000-000000000004"
    /** OUT_ATTEMPT's call, still out. */
    static final String OUT_CALL = "00000018-0000-4000-8000-000000000005"
    /** CODE_RUN's call to the helper, turned away because what may be spent with the model was used up. */
    static final String SPENT_CALL = "00000018-0000-4000-8000-000000000006"
    /** CALL turned away once before it came back. */
    static final String TURNAWAY = "00000019-0000-4000-8000-000000000001"
    /** SPENT_CALL's turnaway, saying what may be spent was used up. */
    static final String SPENT_TURNAWAY = "00000019-0000-4000-8000-000000000002"
    /** STEWARD's question to the helper on CODE_RUN, answered. */
    static final String HELP = "0000001a-0000-4000-8000-000000000001"
    /** RUN_STEP's first try, the model's, ended with CALL. */
    static final String PRODUCTION = "0000001b-0000-4000-8000-000000000001"
    /** RUN_STEP's second try, MEMBER's answer by hand. */
    static final String ANSWERED = "0000001b-0000-4000-8000-000000000002"
    /** REVIEWED_RUN_STEP's first try, MEMBER's answer by hand. */
    static final String REVIEWED_ANSWER = "0000001b-0000-4000-8000-000000000003"
    /** REVIEWED_RUN_STEP's second try, STEWARD's, asked for and not yet answered. */
    static final String PENDING = "0000001b-0000-4000-8000-000000000004"
    /** CODE_RUN_STEP's first try, which gave something back. */
    static final String CODE_PRODUCTION = "0000001b-0000-4000-8000-000000000005"
    /** CODE_RUN_STEP's second try, which went wrong. */
    static final String CODE_FAILED = "0000001b-0000-4000-8000-000000000006"
    /** REVIEWED_CODE_RUN_STEP's first try, which gave something back. */
    static final String CLOSED = "0000001b-0000-4000-8000-000000000007"
    /** REVIEWED_RUN_STEP's fourth try, beyond what it declares, MEMBER's answer by hand; never sent to be reviewed. */
    static final String LONG_ANSWER = "0000001b-0000-4000-8000-000000000008"
    /** PRODUCTION's value for PARENT, needing a review. */
    static final String VALUE = "0000001c-0000-4000-8000-000000000001"
    /** PRODUCTION's value for ANSWER, sure enough to need no review. */
    static final String SURE_VALUE = "0000001c-0000-4000-8000-000000000002"
    /** ANSWERED's value for PARENT. */
    static final String ANSWERED_VALUE = "0000001c-0000-4000-8000-000000000003"
    /** CODE_PRODUCTION's value for its field named receipt. */
    static final String CODE_VALUE = "0000001c-0000-4000-8000-000000000004"
    /** STEWARD refusing VALUE. */
    static final String REVIEW = "0000001d-0000-4000-8000-000000000001"
    /** STEWARD assuring ANSWERED_VALUE. */
    static final String ANSWER_REVIEW = "0000001d-0000-4000-8000-000000000003"
    /** STEWARD opening a refusal of ANSWERED for length, for HOLD, with nothing decided yet. */
    static final String LENGTH_REVIEW = "0000001d-0000-4000-8000-000000000004"
    /** The model on REVIEWED_ANSWER, whose answer did not fit. */
    static final String MODEL_REVIEW = "0000001d-0000-4000-8000-000000000005"
    /** MEMBER's check, finished, which counted GROUP. */
    static final String CHECK = "0000001e-0000-4000-8000-000000000001"
    /** MEMBER's check, still running. */
    static final String RUNNING_CHECK = "0000001e-0000-4000-8000-000000000002"
    /** The constant ANSWERED was given through CONSTANT_BINDING. */
    static final String INPUT = "0000001f-0000-4000-8000-000000000001"
    /** VALUE, as ROUTE chose SUB_RUN's case by it through DISCRIMINATOR. */
    static final String BRANCH_INPUT = "0000001f-0000-4000-8000-000000000002"
    /** Labels the baseline does not declare, added by setupSpec the way a later migration would. */
    static final String CODE_STEP = "send_reply"
    static final String OTHER_CODE_STEP = "close_ticket"
    /**
     * Named by a case and inserted by none. A subject whose created_by is its own subject_id
     * satisfies subjects_author_fk — that is how the seeder exists at all — so a case attacking a
     * foreign key has to point at a key no row will turn out to carry, and the keys a refused row
     * carries itself are the spares below.
     */
    static final String ABSENT = "99999999-9999-4999-8999-999999999999"
    static final String SPARE_SUBJECT = "00000001-0000-4000-8000-00000000000f"
    static final String SPARE_GROUP = "00000002-0000-4000-8000-00000000000f"
    static final String SPARE_ENTRY = "00000006-0000-4000-8000-00000000000f"
    static final String SPARE_RUN = "00000011-0000-4000-8000-00000000000f"
    static final String SPARE_PARENT_RUN = "00000011-0000-4000-8000-00000000000e"
    static final String SPARE_STEP = "0000000c-0000-4000-8000-00000000000f"
    static final String SPARE_BINDING = "0000000e-0000-4000-8000-00000000000f"
    static final String SPARE_RUN_STEP = "00000012-0000-4000-8000-00000000000f"
    static final String SPARE_HOLD = "00000015-0000-4000-8000-00000000000f"
    static final String SPARE_ATTEMPT = "00000016-0000-4000-8000-00000000000f"
    static final String SPARE_REVIEW_ATTEMPT = "00000016-0000-4000-8000-00000000000e"
    static final String SPARE_NEXT_ATTEMPT = "00000016-0000-4000-8000-00000000000d"
    static final String SPARE_FAILURE = "00000017-0000-4000-8000-00000000000f"
    static final String SPARE_CALL = "00000018-0000-4000-8000-00000000000f"
    static final String SPARE_REVIEW_CALL = "00000018-0000-4000-8000-00000000000e"
    static final String SPARE_EXCHANGE = "0000001a-0000-4000-8000-00000000000f"
    static final String SPARE_PRODUCTION = "0000001b-0000-4000-8000-00000000000f"
    static final String SPARE_VALUE = "0000001c-0000-4000-8000-00000000000f"
    static final String SPARE_REVIEW = "0000001d-0000-4000-8000-00000000000f"
    static final String SPARE_CHECK = "0000001e-0000-4000-8000-00000000000f"

    static final String APPROVE_THE_DRAFT = "update app.entry_versions set approved_at = now()," +
            " approved_by = '${MEMBER}' where entry_version_id = '${DRAFT}'; "
    /** The list's next draft, naming no revision. */
    static final String OPEN_A_LIST_VERSION = "insert into app.entry_versions (entry_id, entry_kind, number, created_by)" +
            " values ('${LIST_ENTRY}', 'reference_list', 3, '${STEWARD}')"
    static final String OPEN_A_LIST_VERSION_AT_REVISION_ONE = "insert into app.entry_versions (entry_id, entry_kind," +
            " number, created_by, revision) values ('${LIST_ENTRY}', 'reference_list', 3, '${STEWARD}', 1)"
    /** OTHER_GROUP renamed to a name spelt with a sharp s. */
    static final String NAME_THE_OTHER_GROUP_STRASSE = "update app.groups set name = 'Stra' || chr(223) || 'e'," +
            " updated_at = now(), updated_by = '${STEWARD}' where group_id = '${OTHER_GROUP}'; "
    static final String APPROVE_THE_OTHER_DRAFT = "update app.entry_versions set approved_at = now()," +
            " approved_by = '${STEWARD}' where entry_version_id = '${OTHER_WORKFLOW_DRAFT}'; "
    static final String WITHDRAW_THE_SUBMISSION = "update app.entry_version_submissions set withdrawn_at = now()," +
            " withdrawn_by = '${STEWARD}' where entry_version_submission_id = '${SUBMISSION}'; "

    /** The one fixture row of each table an update below is made to, so a case names only what it changes. */
    static final Map<String, String> ROW_OF = [
        "entries"                : "entry_id = '${ENTRY}'",
        "reference_list_versions": "entry_version_id = '${LIST_VERSION}'",
        "reference_list_terms"   : "reference_list_term_id = '${TERM}'",
        "question_versions"      : "entry_version_id = '${VERSION}'",
        "workflow_versions"      : "entry_version_id = '${WORKFLOW_VERSION}'",
        "workflow_steps"         : "workflow_step_id = '${STEP}'",
        "route_cases"            : "route_case_id = '${ROUTE_CASE}'",
        "declaration_fields"     : "declaration_field_id = '${FIELD}'",
        "bindings"               : "binding_id = '${BINDING}'",
        "runs"                   : "run_id = '${RUN}'",
        "run_stops"              : "run_stop_id = '${RUN_STOP}'",
        "run_ceiling_changes"    : "run_ceiling_change_id = '${CHANGE}'",
        "run_step_holds"         : "run_step_hold_id = '${HOLD}'",
        "run_step_failures"      : "run_step_failure_id = '${FAILURE}'",
        "model_calls"            : "model_call_id = '${CALL}'",
        "model_call_turnaways"   : "model_call_turnaway_id = '${TURNAWAY}'",
        "run_help_exchanges"     : "run_help_exchange_id = '${HELP}'",
        "productions"            : "production_id = '${ANSWERED}'",
        "review_decisions"       : "review_id = '${REVIEW}'",
        "soundness_checks"       : "soundness_check_id = '${RUNNING_CHECK}'",
        "group_currencies"       : "group_id = '${GROUP}'",
    ]

    private SchemaKeys() {}
}
