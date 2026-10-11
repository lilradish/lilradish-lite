export const REFUSAL_EN = {
  "refusal.INSTRUCTION_NAMES_UNKNOWN":
    "{count, plural, one {{names} isn't an input of this question.} other {{names} aren't inputs of this question.}}",
  "refusal.INPUT_STILL_MENTIONED":
    "{count, plural, one {{names} is still mentioned in the instruction or how it is drawn} other {{names} are still mentioned in the instruction or how it is drawn}}; remove those mentions first.",
  /**
   * Kept outside the served-code namespace: a server code matching a local
   * failure must not select a sentence claiming that this page caused it.
   */
  "failure.stopped": "The request was cancelled from this page.",
  /** A fault in this side's own code, whose message is reported rather than shown. */
  "failure.broken": "Something went wrong on this page.",
  "failure.unreachable": "The server could not be reached.",
  /** An address naming nobody, which this side will not send. */
  "failure.notAnAddress": "That address doesn't point to anything here.",
  "failure.unreadable":
    "The server refused this, and this page couldn't read why.",
  "failure.unreadableAnswer":
    "The server answered, but this page could not read the answer. Check what happened before trying again.",
  /** Stands in for a served code that arrived with no sentence here. */
  "failure.unworded": "The server refused this request.",
  /** Names the developer half without putting it in front of the reader. */
  "failure.disclosure": "Technical detail",
  /**
   * The document's own member names, and worded as them in every catalogue:
   * they are what somebody reading a log or a response body will look for.
   */
  "failure.code": "code",
  "failure.status": "status",
  "failure.detail": "detail",

  /**
   * Pages and controls knowing the requested act name its rule, never the reader.
   * These are not served-code keys: `refusal.ACT_NOT_PERMITTED` covers an unknown act.
   */
  "reach.keep_pool":
    "The pool is seen and changed only by a role that may keep it.",
  "reach.grant_estate_role":
    "Estate roles are granted and withdrawn only by a role that may grant them.",
  "reach.keep_group_register":
    "The register of groups is seen and changed only by a role that may keep it.",
  "reach.check_soundness":
    "Soundness is read and checked only by a role that may check it.",
  "reach.read_measurements":
    "Measurements is read only by a role that may read what the estate measures.",
  /** A page of a group the reader is in; one of a group they are not in is missing, not refused. */
  "inGroup.read_membership":
    "A group's members are seen only by a role in it that may see them.",
  "inGroup.change_membership":
    "A group's membership is changed only by a role in it that may change it.",
  "inGroup.author_entry":
    "A group's entries are made, written and submitted only by a role in it that may write one.",
  "inGroup.approve_entry":
    "Only someone in this group who can approve entries can put workflows, questions and reference lists into service, or approve or reject a usage limit change.",
  "inGroup.revoke_entry":
    "A group's entries are retired, stopped and let go only by a role in it that may revoke one.",
  "inGroup.start_run":
    "Only someone in this group who can start runs can start, rename, stop or resume a run, set its usage limit, withdraw a limit change they asked for, or try sending a request that could not be sent.",
  "inGroup.answer_step":
    "Only someone in this group who can answer steps can answer a step or ask again.",
  "inGroup.review_at_gate":
    "Only someone in this group who can review can review steps, accepting or rejecting their values.",
  "myWork.rule": "My work is open only to somebody in a group.",

  /**
   * Each suffix is the served code, keeping this catalogue the only message list.
   * No compiler pairs it with the enum: a spec reads the keys as text to prevent
   * renamed constants silently falling to `failure.unworded`.
   * These codes are stamped on responses declaring none; other stamped codes use that fallback.
   */
  "refusal.BAD_REQUEST": "Some of what was sent was not accepted.",
  "refusal.NOT_FOUND": "That is not here, or is not yours to see.",
  "refusal.INTERNAL": "Something went wrong on the server.",

  /**
   * Said whether nothing was presented or what was presented could not be read.
   * The server answers both alike on purpose, and a sentence naming which of
   * the two it was would hand back what that decision withholds.
   */
  "refusal.NOT_SIGNED_IN": "You are not signed in.",

  "refusal.ACT_NOT_PERMITTED": "You don't have permission to do this.",
  /** Said alike whoever sent it: the rule is about where a request came from. */
  "refusal.ORIGIN_UNVERIFIED":
    "A change is only accepted from this application's own pages.",
  "refusal.BODY_UNUSABLE": "What was sent is not what this takes.",
  "refusal.BODY_SENT_TOO_SLOWLY":
    "What was sent arrived too slowly to be read; send it again.",
  "refusal.SERVICE_BUSY":
    "This is busy with another large request; send it again shortly.",

  "refusal.PEOPLE_SEARCH_UNUSABLE":
    "Type part of a name, or a whole user number, to search.",
  "refusal.USER_NOT_IN_DIRECTORY":
    "Nobody in the directory has that user number.",
  "refusal.PERSON_ALREADY_IN_POOL": "That person is already in the pool.",
  /**
   * What has to go before somebody can leave the pool. The roles and groups
   * are not named here; the person's own panel names them.
   */
  "refusal.PERSON_HOLDS_ESTATE_ROLES":
    "Somebody holding an estate role cannot be taken out of the pool. Withdraw their estate roles first.",
  "refusal.PERSON_IN_GROUPS":
    "Somebody in a group cannot be taken out of the pool until they are in none.",
  "refusal.LAST_ESTATE_ROLE_GRANTOR":
    "Withdrawing this would leave nobody who may grant an estate role.",

  "refusal.GROUP_NAME_UNUSABLE":
    "A group's name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.",
  "refusal.GROUP_KEY_UNUSABLE":
    "A group's key is two to sixteen English letters and nothing else.",
  /** Said alike whichever case either key was typed in: keys are held in capitals. */
  "refusal.GROUP_KEY_TAKEN": "Another group already holds that key.",
  "refusal.GROUP_NAME_TAKEN": "Another group already has that name.",
  "refusal.PERSON_NOT_IN_POOL": "That person is not in the pool.",
  /**
   * Missing groups and groups the reader holds no role in are answered alike,
   * so a non-member learns nothing of a group here.
   */
  "refusal.GROUP_NOT_IN_VIEW": "That group is not in view.",
  "refusal.PERSON_ALREADY_IN_GROUP":
    "That person already holds a role in this group.",
  /** A group always keeps somebody who may change its membership. */
  "refusal.LAST_MEMBERSHIP_CHANGER":
    "Nobody else in this group could change its membership.",
  /** Withholds whether the person is anybody in the pool, which a member of the group may not ask. */
  "refusal.MEMBER_NOT_IN_VIEW": "That person is not a member of this group.",
  "refusal.CURRENCY_NOT_PRICED": "No model is priced in that currency.",

  /**
   * About how something was asked for, never what it holds:
   * the same sentence whatever was sent, independent of the rows.
   */
  "refusal.LIST_SORT_UNUSABLE": "This list cannot be sorted that way.",
  "refusal.LIST_FILTER_UNUSABLE":
    "That filter is too long, or holds a character that cannot be searched for.",
  "refusal.LIST_CURSOR_UNUSABLE":
    "Your place in this list was lost. Start again from the first page.",
  "refusal.PARAMETER_UNKNOWN":
    "This was asked for in a way it does not understand.",

  /**
   * Withholds whether the row exists. Only
   * a reader who keeps the pool or grants estate roles meets it: no "yours".
   */
  "refusal.PERSON_NOT_IN_VIEW": "That person is not in the pool.",

  /** A group's library, whose every entry its members may read. */
  "refusal.ENTRY_NOT_IN_VIEW": "That entry is not in this group's library.",
  "refusal.VERSION_NOT_IN_VIEW": "That version is not in this group's library.",
  "refusal.VERSION_NAME_UNUSABLE":
    "A version name is up to 64 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.",
  "refusal.ENTRY_NAME_TAKEN":
    "Another entry of this kind in this group already has that name.",
  "refusal.ENTRY_NAME_UNUSABLE":
    "An entry's name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.",
  "refusal.ENTRY_PURPOSE_UNUSABLE":
    "What an entry is for is at most 512 characters on one line, with something in it that shows.",
  "refusal.DRAFT_ALREADY_STARTED":
    "This entry already has a version that is a draft or submitted.",
  "refusal.VERSION_STANDING_REFUSES":
    "That version's standing does not admit this.",
  "refusal.APPROVER_WROTE_VERSION": "Nobody approves a version they wrote.",
  "refusal.VERSION_NOT_PINNABLE":
    "Only a version of this group's in service may be pinned.",
  "refusal.VERSION_PINS_RETIRED": "This version pins a version retired since.",
  "refusal.CODE_STEP_NOT_PUBLISHED":
    "Only a code step published to this group may be named.",
  "refusal.STEP_NAME_UNUSABLE":
    "A step's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
  "refusal.SEARCH_QUESTION_SHAPE_LOCKED":
    "The answer is set by {way} while For searching is on; only how sure each field must be can change. Turn off For searching to change the rest.",
  "refusal.SEARCH_QUESTION_DOES_NOT_MATCH":
    "That question can't be used to search with {way}: its answer isn't exactly what {way} gives back. Pick a version set for searching with {way}.",
  "failure.searchQuestionDoesNotMatchNamed":
    "{question} can't be used to search with {way}: its answer isn't exactly what {way} gives back. Pick a version set for searching with {way}.",
  "refusal.SEARCH_WAY_NOT_HELD":
    "{way} is no longer available here. Choose another search, or turn off For searching.",
  "failure.searchWayNotNamed":
    "The search this question was set for is no longer available here. Choose another search, or turn off For searching.",
  "refusal.STEP_LABEL_UNUSABLE":
    "A step's display name is up to 128 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.",
  "refusal.STEPS_TOO_MANY": "A workflow version holds at most 256 steps.",
  "refusal.BINDINGS_PAST_INPUTS":
    "What fills a step, a case or what the workflow gives back is at most one binding for each field it declares.",
  "refusal.CASE_TERM_REPEATED":
    "No two cases of one route are on the same term.",
  "refusal.CASES_PAST_TERMS":
    "A route holds at most one case for each term the list it chooses by offers, and a fallback.",
  "refusal.CONSTANT_DOES_NOT_FIT":
    "A constant is written as a value is: a number written plainly, with no exponent and never a zero with a minus sign, in at most 38 digits either side of its point, no NUL character, no more than 8,192 characters of text in all, and no more than 1,048,576 characters written out.",
  "refusal.VERSION_CONTENT_DOES_NOT_HOLD":
    "Some of what this version holds does not hold yet. Each place is named.",
  "refusal.INSTRUCTION_UNUSABLE":
    "An instruction is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
  "refusal.DRAWING_UNUSABLE":
    "A drawing is one to 8192 characters with something in it that shows. Lines may be broken, and indented with tabs.",
  "refusal.DRAWING_TOO_DEEP":
    "A drawing holds lists and quotes at most 32 deep, counting on each line every marker it opens with and every two spaces of indent before and between them, a tab as four; and emphasis at most 32 deep, counting every two asterisks or underscores between blank lines, an underscore inside a word aside.",
  "refusal.FIELD_NAME_UNUSABLE":
    "A field's name is one to 63 lowercase English letters, digits and underscores, starting with a letter.",
  "refusal.FIELD_WORDS_UNUSABLE":
    "A field's label is at most 128 characters and its help at most 512, each on one line with something in it that shows.",
  "refusal.FIELD_LIMIT_UNUSABLE":
    "How long a field may be and how many it may hold are whole numbers from 1 to 2147483647, and a confidence is from 1 to 100.",
  "refusal.DECLARATION_TOO_DEEP":
    "A field is held more than 32 deep, or so deep that the names down to it, joined by dots, run past 1023 characters.",
  "refusal.DECLARATION_TOO_LARGE":
    "A half holds at most 256 fields, at every depth together.",
  "refusal.DRAFT_WRITTEN_SINCE_READ":
    "Somebody changed this draft since it was read; nothing was saved.",
  "refusal.LIST_TOO_LARGE":
    "This reference list holds as many terms as a list may already; nothing was added.",
  "refusal.NOTE_UNUSABLE":
    "A note is one to 2048 characters with something in it that shows. Lines may be broken, and indented with tabs.",
  "refusal.PROSE_DIRECTION_CONTROL":
    "What you wrote contains a text-direction character, which can't be sent to a model.",
  "refusal.PROSE_INVISIBLE_CHARACTER":
    "What was written holds a character that shows nothing, which a term may not hold.",
  "refusal.PROSE_LINE_BREAK_CRLF":
    "What was written breaks its lines with a carriage return and a line feed (CRLF); a line here ends in a line feed alone.",
  "refusal.PROSE_TAG_CHARACTER":
    "What you wrote contains an invisible tag character, which can't be sent to a model.",
  "refusal.TERM_AT_END":
    "That term is already first or last, so it moves no further that way.",
  "refusal.TERM_MEANING_UNUSABLE":
    "What a term means is one to 512 characters on one line, with something in it that shows.",
  "refusal.TERM_NOT_IN_VIEW": "That term is not in this draft.",
  "refusal.TERM_UNUSABLE":
    "A term is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.",

  "refusal.RUN_NOT_IN_VIEW": "That run isn't here, or you can't see it.",
  "refusal.RUN_BENEATH_ANOTHER":
    "A sub-run can only be changed from its top-level run.",
  "refusal.RUN_NAME_UNUSABLE":
    "Use 1 to 128 characters on one line, with at least one visible character.",
  "refusal.WORKFLOW_NOT_OFFERED":
    "Only a version in service of a workflow of this group's, not stopped, may be started.",
  "refusal.FILE_SIZE_LIMIT_UNUSABLE":
    "A file field's maximum size is a whole number of bytes, at least 1 byte and less than 8,388,608 GB.",
  "refusal.FILE_TYPES_TOO_MANY":
    "A file field can accept at most 64 extensions.",

  "refusal.FILE_NAME_UNUSABLE":
    "That file needs a visible name of one to 255 characters on one line.",
  "refusal.FILE_TYPE_UNUSABLE":
    "That file doesn't have an extension this field accepts.",
  "refusal.FILE_TOO_LARGE": "That file is larger than this field allows.",
  "refusal.FILES_TOO_MANY": "This field has too many files.",
  "refusal.VALUE_DOES_NOT_FIT":
    "Some values aren't in the format their field expects.",
  "refusal.CEILING_UNUSABLE":
    "A usage limit is a whole number from 1 to 9,007,199,254,740,991, or empty for no limit.",
  "refusal.CEILING_RAISE_NOT_WAITING": "That request is no longer pending.",
  "refusal.CEILING_RAISE_ASKED_BY_CALLER":
    "You can't approve or reject a change you asked for.",
  "refusal.CEILING_RAISE_ASKED_BY_ANOTHER":
    "Only the person who asked for this change can withdraw it.",

  "refusal.STEP_NOT_IN_VIEW": "That step isn't here, or you can't see it.",
  "refusal.STEP_MOVED_ON": "This step has changed since you opened it.",
  "refusal.LENGTH_VALUE_USED":
    "It can't be rejected now: something has already been made from it.",
  "refusal.LENGTH_VALUE_NOT_STANDING":
    "It can't be rejected now: it is no longer the accepted value.",
  "refusal.STEP_NOT_HELD_FOR_LENGTH":
    "It can't be rejected now: the step is no longer blocked on length.",
  "refusal.RUN_STOPPED":
    "This run is stopped. Resume it before answering, reviewing or making another attempt.",
  "refusal.ENTRY_STOPPED":
    "What this step runs, or the workflow itself, has been stopped. No attempt can be made until it is allowed to be used again.",
  "refusal.ASK_AGAIN_NOT_OFFERED":
    "This step can't be asked again; its next attempt can only be answered by a person.",
  "refusal.CODE_STEP_GIVES_OTHERWISE":
    "This code step's outputs, as installed now, no longer match what the workflow reads from it or the reference lists this group has, so it can't be answered here.",
  "refusal.TRY_SENDING_NOT_OFFERED":
    "Try sending is only for a model request that could not be sent: the model service turned it away, it was too long for the model, or the model or its mode isn't available on this system.",
  "refusal.REVIEW_NOT_A_PERSONS":
    "These values are for the model this step names to review, not a person.",
  "refusal.REVIEW_OWN_PRODUCTION": "You can't review something you produced.",
  "refusal.REVIEW_INCOMPLETE":
    "A review must accept or reject every value waiting for review, and nothing else.",
  "refusal.REASON_MISSING": "A rejection or an answer needs a reason.",
  "refusal.REASON_UNUSABLE":
    "A reason is 1 to 2048 characters, with at least one visible character.",
};
