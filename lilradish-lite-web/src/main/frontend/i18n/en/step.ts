export const STEP_EN = {
  "step.whatItFound": "What it found",
  "step.reviewHeading": "Review",
  "step.reviewValue": "Value",
  "step.reviewDecision": "Decision",
  "step.inputColumn": "Input",
  "step.fromColumn": "From",
  "step.copy": "Copy step",
  "step.needsReview": "Needs your review",
  "step.lengthReject": "Reject for length",
  "step.lengthConfirm": "Reject {field} for length?",
  "step.lengthConfirmAttempt":
    "{step} will make it again, using one of its attempts. This can't be undone.",
  "step.lengthConfirmSpent":
    "{step} has no attempts left, so it will fail until someone who can answer steps gives it another. This can't be undone.",
  "step.lengthHeading": "Why it's too long",
  "step.lengthShow": "Show why it's too long",
  "step.lengthHide": "Hide why it's too long",
  "step.lengthLimit": "{model} takes at most",
  "step.lengthWhole": "This request would be",
  "step.lengthExcess": "Too long by",
  "step.lengthFixed": "Can't be shortened",
  "step.lengthUnit": "{count} unit",
  "step.lengthUnits": "{count} units",
  "step.lengthAbout": "about {length}",
  "step.lengthDown":
    "About {now} now, down from about {was}: about {gone} shorter.",
  "step.lengthUp": "About {now} now, up from about {was}.",
  "step.lengthFixedInstruction":
    "The instruction, and what the system adds to it.",
  "step.lengthFixedWithRejection":
    "The instruction, what the system adds to it, and what it tells the model about the last rejected attempt.",
  "step.lengthEstimates":
    "These are estimates: {model} counts in its own way. Try sending is the only way to know whether it fits now.",
  "step.lengthInputs": "What each input takes up, most first",
  "step.lengthMakeAgain": "{step} can make it again.",
  "step.lengthMakeAgainSpent":
    "{step} can make it again, but has no attempts left.",
  "step.lengthConstant":
    "Written into this workflow. To shorten it, make a new version of the workflow.",
  "step.lengthRunInput":
    "Given when this run was started. To shorten it, start another run.",
  "step.lengthConstantAbove":
    "Written into the workflow of run {number, number}. To shorten it, make a new version of that workflow.",
  "step.lengthRunInputAbove":
    "Given when run {number, number} was started. To shorten it, start another run.",
  "step.lengthMadeAbove": "Made by {step} in run {number, number}.",
  "step.lengthFromSubRun":
    "Comes from run {number, number}, a sub-run, where its attempts are made.",
  "step.lengthOpenRun": "Open run {number, number}",
  "step.lengthUsed": "Can't be rejected: {step} has already used it.",
  "step.lengthUsedInRun":
    "Can't be rejected: {step} in run {number, number} has already used it.",
  "step.lengthUsedToBranch":
    "Can't be rejected: this route chose its branch by it.",
  "step.heldSearchWayNotHeld":
    "The search method this step uses isn't in the installed version of this system. The step continues automatically once a system update brings it back.",
  "step.failedToolNotOffered":
    "It uses {model} to search, but that model doesn't offer {tool}, which this search method needs.",
  "step.failedSearchQuestionNoLongerMatches":
    "{question} no longer matches what {way} gives back, so nothing was sent and no attempt was used.",
  "step.failedSearchQuestionNoLongerMatchesUnnamed":
    "Its question no longer matches what {way} gives back, so nothing was sent and no attempt was used.",
  "step.searchedFor": "Searched for",
  "step.searchFound": "Found",
  "step.searchRecordUnsaid":
    "The model did not say what it searched for or found.",
  "step.searchRecordCut":
    "The search record is too long to store in full, so it is cut short here.",
  "step.searchRecordNotAsItCame":
    "Part of the search record couldn't be stored exactly as received.",
  "step.searchRecordMore":
    "{plural, plural, one {One more search entry was not stored.} other {{count} more search entries were not stored.}}",
  "step.searchWithheld":
    "You don't have permission to see what it searched for and found.",
  "step.searches": "Searches",
  "step.searchesSaid":
    "{plural, plural, one {{count} search} other {{count} searches}}, as the model reported",
  "step.searchesAtMost":
    "{plural, plural, one {{count} search} other {{count} searches}}: the most one call can make, counted because the model didn't report how many",
  "step.searchFee": "Search fee",
  "step.searchPrice": "{price} per search at the time",
  "step.searchFeeNotInCurrency":
    "Searching had no price in {currency}, so no fee is shown.",
  "step.searchFeeManyCurrencies":
    "Not shown: searching was priced in more than one currency, and this group hasn't chosen one.",
  "step.searchFeeNoPrice": "Searching had no price, so no fee is shown.",
  "step.runsSearch": "Search: {way}",
  "act.answeredByHand": "Answered by hand",
  "act.saidDone": "Said it was done",
  "act.try": "Attempt {number, number}",
  "act.reviewed": "Reviewed",
  "act.assured": "Accepted {field}",
  "act.refused": "Rejected {field}: {reason}",
  "act.refusedHeading": "Rejected {field}:",
  "act.refusedForLength": "Rejected for length",
  "act.lengthDetail":
    "{field}: made the request from {step} in run {number, number} too long to send.",
  "act.openHeldStep": "Open {step}",
  "act.changedCeiling": "Changed the usage limit",
  "act.changedCeilingOf": "Changed the usage limit of run {number, number}",
  "act.askedCeiling": "Asked to change the usage limit",
  "act.askedCeilingOf":
    "Asked to change the usage limit of run {number, number}",
  "act.withdrewCeiling": "Withdrew the usage limit request",
  "act.withdrewCeilingOf":
    "Withdrew the usage limit request of run {number, number}",
  "act.approvedCeiling": "Approved the usage limit change",
  "act.approvedCeilingOf":
    "Approved the usage limit change of run {number, number}",
  "act.refusedCeiling": "Rejected the usage limit change",
  "act.refusedCeilingOf":
    "Rejected the usage limit change of run {number, number}",
  "act.ceilingFromTo": "From {from} to {to}",
  "act.noCeiling": "No limit",
  "act.supersededCeiling": "Request withdrawn: a later change replaced it.",
  "act.stopped": "Stopped this run",
  "act.openedAgain": "Resumed this run",

  "step.startedWith": "Run inputs",
  "step.startedWithNothing": "This workflow takes no inputs.",
  "step.startedAboveWith":
    "Step {step} of run {number, number} started this run of {workflow}.",
  "step.startedByWith": "{name} started this run of {workflow}.",
  "step.startedByGoneWith":
    "Someone no longer in this system started this run of {workflow}.",
  "step.gaveBack": "Result",
  "step.givesNothing":
    "This workflow returns no result: it carries out actions rather than giving answers.",
  "step.nothingStandsYet": "No accepted result yet.",
  "step.theSteps": "Steps",
  "step.order": "Order",
  "step.name": "Name",
  "step.takesFrom": "Inputs from",
  "step.takesNothing": "No inputs.",
  "step.putTogetherFromThese": "Combined from:",
  "step.fromRunInput": "{field} (run input)",
  "step.fromRunInputOf": "{field} (input of run {number, number})",
  "step.fromStep": "{field} (output of {step})",
  "step.fromStepOfRun": "{field} (output of {step} in run {number, number})",
  "step.fromConstant": "a fixed value set in the workflow",
  "step.notStarted": "Not started",
  "step.running": "Running",
  "step.heldBack": "Blocked",
  "step.waiting": "Waiting",
  "step.failed": "Failed",
  "step.done": "Done",
  "step.unknown": "This page can't show this yet.",
  "step.heldEntryStopped":
    "What this step runs has been stopped, so nothing will be produced until it is allowed to be used again.",
  "step.heldStopLetGo":
    "What stopped it is allowed to be used again, so it continues automatically while the run is running.",
  "step.heldWorkflowStopped":
    "This run's workflow has been stopped, so nothing will be produced until it is allowed to be used again.",
  "step.heldCodeStepNotHeld":
    "The code this step runs isn't in the installed version of this system. The step continues automatically once a system update brings it back.",
  "step.heldSourceCodeStepNotHeld":
    "An input comes from {step}, whose code isn't in the installed version of this system, so it can't be sent. It continues automatically once a system update brings that code back.",
  "step.failedSourceNoLongerMatches":
    "The output {field} of {step} no longer matches what this step expects from it, now that the code step has changed, so nothing was sent.",
  "step.failedSourceListNotHere":
    "The options for {field}, from {step}, come from a reference list version that isn't in service in this group, so nothing was sent.",
  "step.heldTooLong":
    "Its request is too long for the model, so it was not sent and nothing was spent.",
  "step.heldTurnedAway": "The model service turned the request away.",
  "step.heldSpentUp":
    "The model service turned the request away because the usage allowance for that model is used up, so it won't be retried automatically.",
  "step.stoppedBy": "Stopped by {name}, {when}.",
  "step.stoppedByGone": "Stopped {when}, by someone no longer in this system.",
  "step.notReviewedYetOf": "{field} has not been reviewed yet.",
  "step.owedAsked": "Attempt {number, number} has been requested.",
  "step.owedUnasked": "Attempt {number, number} has not been requested yet.",
  "step.failedTriesSpent":
    "All allowed attempts are used up, and none was accepted.",
  "step.failedUnclaimed":
    "No branch of this route handles “{term}”, and it has no fallback. To go on, create a new version of this workflow and start a new run.",
  "step.failedUnclaimedEmpty":
    "This route had no value to choose a branch by, and it has no fallback. To go on, create a new version of this workflow and start a new run.",
  "step.failedUnclaimedWithheld":
    "No branch of this route handles the value it chose by — a model's answer you don't have permission to see — and it has no fallback. To go on, create a new version of this workflow and start a new run.",
  "step.failedUncuttable":
    "Even the parts of the request that can't be shortened are already too long for the model, so shortening inputs can't fix it.",
  "step.failedModelNotHeld":
    "It uses a model, or a model mode, that isn't available on this system.",
  /** {model} is the model named, in its mode where it runs in one. */
  "step.failedProducerNotHeld":
    "It uses {model} to answer, which isn't available on this system.",
  "step.failedReviewerNotHeld":
    "It uses {model} to review, which isn't available on this system.",
  "step.failedTooDeep":
    "Sub-runs can be nested at most {most, plural, one {# level} other {# levels}} deep, counting the top-level run, and this step would have gone one level deeper. To go on, create a new version of this workflow and start a new run.",
  "step.runningCode": "Its code is running.",
  "step.runningCall": "A request to the model is in progress.",
  "step.runningNextTry":
    "The system makes the next attempt automatically while the run is running.",
  "step.waitsOnReviewer": "Waiting for a reviewer.",
  "step.waitsOnModel": "Waiting for the model's review.",
  "step.waitsOnAnswerer": "Waiting for a person to make the next attempt.",
  "step.waitsOnStarter": "Waiting for the person who started the run.",
  "step.waitsOnTopLevelStarter":
    "Waiting for the person who started the top-level run.",
  "step.subRun": "Sub-run",
  "step.waitsOnNobody": "Nothing happens while the run is stopped.",
  "step.trySendingBy": "Someone who can start runs can try sending it.",
  "step.letGo": "Someone who can stop entries can allow it to be used again.",
  "step.letGoThenTrySending":
    "Someone who can stop entries can allow it to be used again; then someone who can start runs can try sending it.",
  "step.tryAgainBy": "Someone who can answer steps can make another attempt.",
  "step.letGoThenTryAgain":
    "Someone who can stop entries can allow it to be used again; then someone who can answer steps can make another attempt.",
  "step.nobodyWhileStopped": "Nothing can be done until the run is resumed.",
  /** Said once, where a re-read finds a run or a step in another state; {state} is a run's or a step's state as drawn. */
  "step.nowIn": "{name}: now {state}.",
  "step.nowUnknown":
    "{name}: status changed, and this page can't show the new status yet.",
  "step.noCost": "None — no model is used.",
  "step.aPerson": "A person",
  "step.code": "Code",
  "step.orderOf": "{order, number} of {of, number}",
  "step.runs": "What it runs",
  "step.producedBy": "Produced by",
  "step.producesNothing": "None — it produces no output of its own.",
  "step.reviewedBy": "Reviewed by",
  "step.reviewerPerson": "Anyone who can review it",
  "step.nobodyReviews": "No reviewer.",
  "step.tries": "Attempts",
  "step.tryNumber": "Attempt {number, number}",
  "step.tryOf": "Attempt {number, number} of {allowed, number}",
  "step.tryBeyond":
    "Attempt {number, number}, beyond the {allowed, number} allowed",
  "step.wentIn": "Inputs",
  "step.nothingYet": "Nothing yet.",
  "step.notYetSent": "Not sent yet — these are the inputs it would use now.",
  "step.cameOut": "Output",
  "step.producedNothing": "No output yet.",
  "step.stands": "Accepted.",
  "step.waitsOnReview": "Waiting for review.",
  "step.refusedOnReview": "Rejected in review.",
  "step.refusedForLength": "Rejected for length.",
  "step.nowNone": "The latest attempt returned nothing for this.",
  "step.seeTries": "See attempts for {field}",
  "step.decisionFor": "Decision for {field}",
  "value.waitingForReview": "Waiting for review",
  "value.rejectedInReview": "Rejected in review",
  "value.rejectedForLength": "Rejected for length",
  "step.theTries": "Attempts",
  "step.triesOfValue": "Attempts for {field}",
  "step.noTries": "No attempts yet.",
  "step.try": "Attempt",
  "step.howItEnded": "Outcome",
  "step.endedOpen": "Nothing has come back yet.",
  "step.didNotFit": "The answer did not match the expected format.",
  "step.errored": "An error occurred.",
  "step.nothingCameBack": "No response came back.",
  "step.misfitNotTheShape": "The answer was not in the expected format.",
  "step.misfitModelDeclined":
    "The model declined to answer, so nothing was taken from it, and this attempt is used up.",
  "step.declinedWords": "The model's reason for declining",
  "step.modelReply": "The model's reply, exactly as it came",
  "step.unreadableResponse": "What the model service sent back",
  "step.unreadableResponseStopped":
    "This could not be read, so the rest of it was not downloaded. Only its start is kept here.",
  "step.declinedAltered":
    "Some of it couldn't be stored exactly as received, so it is replaced here.",
  "step.declinedTruncated":
    "It is too long to store in full, so it is cut short here.",
  "step.misfitFieldMissing": "The answer left out a value it was asked for.",
  "step.misfitFieldUnknown": "The answer gave a value nobody asked for.",
  "step.misfitNothingGiven": "The answer gave nothing for a required value.",
  "step.misfitNotItsKind":
    "The answer gave a value of the wrong kind for its field.",
  "step.misfitLineBreak": "The answer put a line break in a single-line field.",
  "step.misfitTooLong": "The answer gave a value longer than its field allows.",
  "step.misfitTooMany":
    "The answer gave more items for a value than its field allows.",
  "step.misfitNotATerm":
    "The answer gave a value that is not one of its field's options.",
  "step.misfitUnkeepable":
    "The answer contained a character that can't be stored.",
  "step.misfitTooLongToKeep":
    "The answer gave a value that fits its field but is too long to store.",
  "step.misfitConfidenceMissing":
    "The answer did not give a confidence for a value that needed one.",
  "step.misfitConfidenceUnasked":
    "The answer gave a confidence for a value that didn't ask for one.",
  "step.misfitConfidenceNotAPercent":
    "The answer gave a confidence that is not a whole number from 0 to 100.",
  "step.misfitUndecided":
    "The review left a value neither accepted nor rejected.",
  "step.misfitWordsMissing": "The review rejected a value without saying why.",
  "step.misfitWordsTooLong":
    "The review's reason for a rejection was too long.",
  "step.misfitWordsUnkeepable":
    "The review's reason for a rejection contained a character that isn't allowed.",
  "step.misfitCutOff":
    "The model reached its maximum answer length, so its answer was cut off.",
  "step.misfitNotKeptAsItCame":
    "The answer contained something that couldn't be stored exactly as received.",
  /** Turned-away calls spend no try and cost nothing. */
  "step.turnedAway": "Times the model service turned it away",
  "step.turnedAwayAt": "Turned away {when}.",
  "step.turnedAwaySentAgain": "Turned away {when}, then resent automatically.",
  "step.turnedAwaySaid": "The model said: {said}",
  "step.turnedAwaySaidCut": "The model said: {said} (cut short here)",
  "step.turnedAwayWithheld":
    "You don't have permission to see what the model said.",
  "step.turnedAwayCostNothing":
    "These don't count as attempts and cost nothing.",
  "step.notReviewedYet": "Not reviewed yet.",
  "step.noReviewAsked": "No review needed.",
  "step.reviewUnsent":
    "Waiting for the model's review, which the system will request automatically.",
  "step.reviewOut": "Waiting for the model's review — request in progress.",
  "step.reviewTurnedAway":
    "Waiting for the model's review, which could not be sent: the model service turned the request away.",
  "step.reviewSpentUp":
    "Waiting for the model's review, which could not be sent: the usage allowance for that model is used up, so it won't be retried automatically.",
  "step.reviewTooLong":
    "The review request would be too long for the reviewing model, so it was not sent. Someone who can review does it instead.",
  "step.reviewDidNotFit":
    "The model's review was not in the expected format, so it was not used. This attempt is used up.",
  "step.reviewNothingCameBack":
    "The review request got no response, so this attempt is used up.",
  "step.reviewTakesNoLongerDeclared":
    "This couldn't go to the reviewing model: the installed version of this code step no longer defines its inputs, so a person reviews it instead.",
  "step.reviewHoldsAFile":
    "This couldn't go to the reviewing model: it includes a file, and files are never sent to a model, so a person reviews it instead.",
  "step.reviewNoLongerDeclared":
    "This couldn't go to the reviewing model: the installed version of this code step no longer defines its outputs, so a person reviews it instead.",
  "step.reviewListNotHere":
    "This couldn't go to the reviewing model: a reference list it uses isn't available here, so a person reviews it instead.",
  "step.reviewWentWrong": "{who} (the review hit an error)",
  "step.askedBy": "Requested by",
  "step.askedByRun": "The run itself",
  "step.why": "Reason given",
  "step.gaveNothingFor": "No value was returned for this.",
  "step.decision": "Review decision",
  "step.assured": "Accepted.",
  "step.refused": "Rejected.",
  "step.refusedHeading": "Rejected:",
  "step.whyWithheld":
    "Rejected, for a reason you don't have permission to see.",
  "step.wentWrong": "Error",
  "step.wentWrongCut": "{detail} (cut short here)",
  "step.returned": "What the code returned",
  "step.erroredForGaveNothing": "The code returned nothing at all.",
  "step.erroredForNotDeclared":
    "The code returned {member}, which isn't defined.",
  "step.erroredForNotDeclaredWithin":
    "The code returned {member} inside {field}, where it isn't defined.",
  "step.erroredForMember": "a field named {name}",
  "step.erroredForMemberCut": "a field named {name} (name shortened here)",
  "step.erroredForUnnamedMember": "a field with no name that can be shown",
  "step.erroredForNothingGiven":
    "The code returned nothing for {field}, which is required.",
  "step.erroredForNotItsKind":
    "The code returned a value of the wrong kind for {field}.",
  "step.erroredForLineBreak":
    "The code returned text with a line break for {field}, which is a single-line field.",
  "step.erroredForTooLong":
    "The code returned a value for {field} longer than that field allows.",
  "step.erroredForTooMany":
    "The code returned more items for {field} than that field allows.",
  "step.erroredForNotATerm":
    "The code returned a value for {field} that is not one of that field's options.",
  "step.erroredForUnkeepable":
    "The code returned text for {field} with a character that can't be stored.",
  "step.erroredForTooLongToKeep":
    "The code returned a value for {field} that fits the field but is too long to store.",
  "step.erroredForFileTypeUnusable":
    "Its code gave a file for {field} without an extension that field accepts, so none of the files it gave back were kept.",
  "step.erroredForFileNameUnusable":
    "The code returned a file for {field} without a valid one-line name of 1 to 255 characters, so none of its files were kept.",
  "step.erroredForFileTooLarge":
    "The code returned a file for {field} larger than that field allows, so none of its files were kept.",
  "step.erroredForFileTotalTooLarge":
    "The code returned files that together exceed what this system accepts at once, so none of them were kept.",
  "step.erroredForTakesAListNotHere":
    "The options for {field}, an input of the code, came from a reference list this group didn't have.",
  "step.erroredForGivesAListNotHere":
    "The options for {field}, an output of the code, came from a reference list this group didn't have.",
  "step.erroredForTakesOtherwise":
    "The input this workflow passed to the code as {field} no longer matched what the code expects.",
  "step.erroredForGivesOtherwise":
    "The code's output {field} no longer matched what {step} expected from it.",
  "step.erroredForGivesOtherwiseOutput":
    "The code's output {field} no longer matched this workflow's output {output}.",
  "step.erroredForFailedOnThisSide":
    "Running the code failed on this system's side, whatever the code itself did.",
  "step.erroredForSaidNothing": "The code hit an error without saying why.",
  "step.erroredForLaterStep": "a later step",
  "step.erroredForNotKept": "It couldn't be stored, so it can't be shown.",
  /** Said beside a refusal to answer a code step here, of what it gives back as it is declared now. */
  "step.erroredForNowGivesAListNotHere":
    "The options for {field}, an output of this code step, come from a reference list this group doesn't have.",
  "step.erroredForNowGivesOtherwise":
    "This code step's output {field} no longer matches what {step} expects from it.",
  "step.erroredForNowGivesOtherwiseOutput":
    "This code step's output {field} no longer matches this workflow's output {output}.",
  /** {percent} is written as the reader writes a percentage. */
  "step.sureOf": "{field}: {percent}",
  /** A field within the field holding it, the outermost first. */
  "step.fieldWithin": "{outer} › {inner}",
  /** One of many a field holds, counted from one. */
  "step.itemOf": "{field}, item {number, number}",
  "step.none": "None",
  "step.withheld": "You don't have permission to see this.",
  "step.unreadable": "This page can't read this value.",
  /** Said beside a value kept in a shape its field no longer has, or of a field no longer declared. */
  "step.earlierShape":
    "Shown as originally given; it no longer matches the field's current format.",
  "step.term": "{term}: {meaning}",
  /** {offset} is the one it was given in. */
  "step.momentOwn": "{when}, UTC{offset}",
  "step.momentLocal": "{when}, your time",
  "step.progress":
    "{done, number} of {of, plural, one {# step} other {# steps}} done",
  "step.theConversation": "The conversation",
  "step.startedRunBeneath":
    "This step started run {number, number} as a sub-run.",
  "step.openRunBeneath": "Open run {number, number}",
  "step.inFull": "{field}, in full",
  "step.since": "Since {when}",
  "step.details": "Details",
  "step.earlierTry": "Previous attempt",
  "step.laterTry": "Next attempt",
  "step.openStep": "Open this step's page",
  "step.stepsSoFar": "Steps so far",
  "step.stoodByThen": "Result so far",
  "step.failedAt": "Failed at {step}.",
  /** Why an act is not offered to the reader, where the reason is theirs rather than the step's. */
  "step.mayNotAnswer": "You don't have permission to answer it.",
  "step.mayNotAskAgain": "You don't have permission to ask again.",
  "step.mayNotReview": "You don't have permission to review it.",
  "step.mayNotTrySending": "You don't have permission to try sending it.",
  "step.reviewsOwn": "You produced this, so someone else must review it.",
  "step.assure": "Accept",
  "step.refuse": "Reject",
  "step.whyRefuse": "Reason for rejecting {field}",
  "step.submitReview": "Submit review",
  "step.chooseEach":
    "Choose Accept or Reject for each value, and give a reason for each one you reject.",
  "step.sayWhy": "Give a reason for rejecting it.",
  "step.reviewedAlready": "Already reviewed by {name}.",
  "step.askAgain": "Ask again",
  "step.answerHere": "Answer it yourself",
  /** Requests sending a blocked model try; the press is answered before the try is sent. */
  "step.trySending": "Try sending",
  "step.trySendingOut": "Asking for it to be sent…",
  "step.trySendingSent":
    "It will be sent if it can be. The result shows here once known.",
  "step.nextBeyond":
    "Attempt {number, number} goes beyond the {allowed, plural, one {# attempt} other {# attempts}} this step allows.",
  "step.theInstruction": "Instructions",
  "step.yourAnswer": "Your answer",
  "step.startsFromTry":
    "Pre-filled from attempt {number, number}. Every value is submitted again, not only the rejected ones.",
  "step.lastWithheld":
    "You don't have permission to see what attempt {number, number} gave here.",
  "step.lastEarlierShape":
    "Attempt {number, number} gave this in an older format of the field, so it starts empty.",
  "step.prefillLeftOut":
    "The pre-filled text leaves out {count, plural, one {# invisible or text-direction character} other {# invisible or text-direction characters}} from the earlier answer.",
  "step.whyAnswer": "Your reason",
  "step.send": "Submit answer",
  "step.askedAlready": "Already asked again by {name}.",
  "step.answeredAlready": "Already answered by {name}.",
  "step.tryBeyondVisible":
    "Attempt {number, number}, {past} {allowed, number} {allows}",
  "step.tryBeyondPast": "beyond the",
  "step.tryBeyondAllows": "allowed",
  "step.reviewWentWrongVisible": "{who} {hint}",
  "step.reviewWentWrongHint": "(the review hit an error)",
  "step.erroredForMemberCutVisible": "{member} {hint}",
  "step.erroredForMemberCutHint": "(name shortened here)",
};
