export const CONTENT_PROBLEMS_EN = {
  "contentProblem.instruction_names_unknown":
    "{count, plural, one {{names} isn't an input of this question.} other {{names} aren't inputs of this question.}}",
  /** A step that could send a model more than it takes: said beside what does not hold, and refusing nothing. */
  "sendsPast.label": "What could be too long to send",
  "sendsPast.hint":
    "Worked out with every value at its limit, against what each model takes now, and only about. It is accepted and approved all the same; a run holds a step back only where what it would send is too long.",
  "sendsPast.producing":
    "{step}: what it could send {model} to produce runs about {past, plural, one {# unit} other {# units}} past what that model takes.",
  "sendsPast.reviewing":
    "{step}: what it could send {model} to review runs about {past, plural, one {# unit} other {# units}} past what that model takes.",
  /** {max} is the most a count arriving here keeps exactly; past it, only that it was passed is said. */
  "sendsPast.producingUncounted":
    "{step}: what it could send {model} to produce runs more than {max, plural, one {# unit} other {# units}} past what that model takes.",
  "sendsPast.reviewingUncounted":
    "{step}: what it could send {model} to review runs more than {max, plural, one {# unit} other {# units}} past what that model takes.",

  "contentProblems.label": "What does not hold yet",
  "contentProblems.more":
    "{count, plural, one {# more thing does not hold and is not listed.} other {# more things do not hold and are not listed.}}",
  "contentProblems.at": "{part}: {problem}",
  "contentProblems.atDrawing": "{part}, how it is drawn: {problem}",
  "contentProblems.atField": "{part}, {field}: {problem}",
  "contentProblems.atTerm":
    "{part}, term {position, number}, {term}: {problem}",
  /** One level of the way down to a field, where its name alone would not say which. */
  "contentProblems.sameNamed": "{name} (field {position, number} there)",
  "contentPart.instruction": "The instruction",
  "contentPart.drawing": "The question",
  "contentPart.takes": "What it takes",
  "contentPart.gives": "The declared answer",
  /** What a workflow gives back, which is not an answer to anything. */
  "contentPart.workflowGives": "What it gives back",
  "contentPart.steps": "The steps",
  "contentPart.helper": "Whether a run may be helped",
  /** Where in the steps a problem is, each level said by what a reader reads it by. */
  "contentProblems.atStep": "{part}, {step}: {problem}",
  "contentProblems.atCase": "{part}, {step}, {case}: {problem}",
  "contentProblems.atCaseField":
    "{part}, {step}, {case}, what it gives back as {field}: {problem}",
  "contentProblems.atInput": "{part}, {step}, input {field}: {problem}",
  "contentProblems.atCaseInput":
    "{part}, {step}, {case}, input {field}: {problem}",
  "contentProblems.atBinding": "{part}, {where}: {problem}",
  "contentProblems.caseOn": "the case on {term}",
  "contentProblems.fallback": "the fallback",
  "contentProblems.boundTo": "what fills {target}",
  "contentProblems.stepBoundTo": "{step}, what fills {target}",
  "contentProblems.caseBoundTo": "{step}, {case}, what fills {target}",
  "contentProblems.chosenBy": "what {step} chooses by",
  "contentPart.terms": "The terms",
  "contentPart.asking": "One asking of it",
  "contentProblem.drawing_names_unknown":
    "It names {name}, which this question does not take.",
  "contentProblem.instruction_missing":
    "It tells whoever answers it nothing yet.",
  "contentProblem.nothing_given_back":
    "It gives nothing back, so nothing downstream could name anything it made.",
  "contentProblem.name_repeated": "A field beside it already has this name.",
  "contentProblem.longest_missing":
    "A field of text says how long its value may be, and this one does not yet.",
  "contentProblem.file_size_missing":
    "A field of files says how large one file may be, and this one does not yet.",
  "contentProblem.question_takes_file":
    "It is a file, and a question takes none: a model is sent text alone.",
  "contentProblem.list_missing":
    "A field of terms names the list they come from, and this one does not yet.",
  "contentProblem.most_missing":
    "A field holding many says how many it may hold, and this one does not yet.",
  /** {size} is the deployment's largest single file and {count} the most files it takes in one field, both carried on the problem by the server. */
  "contentProblem.file_limit_past_deployment":
    "It allows a larger file, or more files, than this deployment takes: files of up to {size} each, and at most {count, number} in one field.",
  /** {total} is this deployment's file limit for one handover, carried on the problem by the server. */
  "contentProblem.file_total_past_deployment":
    "At their limits, its files together could come to more than the {total} this deployment takes at once.",
  "contentProblem.limit_past_largest":
    "At its limits, one value of it could be written out longer than one value may be.",
  /** {most} is the longest one value may be written out, the figure the server holds each value to. */
  "contentProblem.limitPastLargestOf":
    "At its limits, one value of it could be written out in more than {most, number} characters, the names and marks holding it together counted, the longest one value may be.",
  "contentProblem.no_fields_held":
    "A field holding fields declares at least one, and this one declares none yet.",
  "contentProblem.standing_missing":
    "A value given back says what it takes to stand, and this one does not yet.",
  "contentProblem.floor_missing":
    "A value standing above a confidence says above which, and this one does not yet.",
  "contentProblem.no_steps":
    "It holds no step, so it could give nothing back and act on nothing.",
  "contentProblem.step_name_repeated": "An earlier step already has this name.",
  "contentProblem.step_label_repeated":
    'Another step in this version is already shown as "{name}".',
  "contentProblem.runs_missing": "It says nothing yet of what it runs.",
  "contentProblem.pin_elsewhere": "It pins a version this group does not hold.",
  "contentProblem.code_step_not_published":
    "The code step it names is not published to this group.",
  "contentProblem.code_step_not_declared":
    "This release declares nothing of what the code step it names takes and gives back.",
  "contentProblem.code_step_takes_and_gives_nothing":
    "The code step it names takes nothing and gives nothing back, so nothing it did could be seen.",
  "contentProblem.code_step_list_missing":
    "The code step it names takes a term from a version of a reference list that does not exist.",
  "contentProblem.code_step_list_not_yet_in_service":
    "The code step it names takes a term from a version of a reference list not yet in service.",
  "contentProblem.code_step_list_retired":
    "The code step it names takes a term from a version of a reference list since retired.",
  "contentProblem.producer_missing":
    "It says nothing yet of who produces its values.",
  "contentProblem.producer_not_held":
    "Its values are produced by a model this deployment does not hold.",
  "contentProblem.producer_mode_not_offered":
    "Its values are produced in a mode its model does not offer.",
  "contentProblem.tries_missing":
    "It says nothing yet of how many tries it may make.",
  "contentProblem.reviewer_not_held":
    "What it produces is reviewed by a model this deployment does not hold.",
  "contentProblem.reviewer_mode_not_offered":
    "What it produces is reviewed in a mode its model does not offer.",
  "contentProblem.step_file_made_by_model":
    "It gives back a file, which no model can make; a person may answer it.",
  "contentProblem.step_file_reviewed_by_model":
    "It takes or gives back a file, which no model can review; a person may review it, by downloading it.",
  "contentProblem.model_gives_nothing":
    "A model is asked by it and it gives nothing back, so nothing listens to the model.",
  "contentProblem.discriminator_missing":
    "Nothing is bound yet to choose its case by.",
  "contentProblem.discriminator_not_term":
    "What it chooses by is not one term of a list.",
  "contentProblem.no_cases": "It has no case on any term yet.",
  "contentProblem.case_target_missing":
    "It leads to no workflow at a fixed version yet.",
  "contentProblem.case_not_offered":
    "Its term is not one the list it chooses by offers.",
  "contentProblem.case_repeated": "An earlier case is on the same term.",
  "contentProblem.case_gives_undeclared":
    "The route declares nothing by this name.",
  "contentProblem.case_gives_other_kind":
    "The route declares it as another kind.",
  "contentProblem.case_gives_past_limit":
    "It could be longer, or hold more, than the route allows.",
  "contentProblem.case_may_give_nothing":
    "The route says it must be given, and the workflow it leads to need not give it.",
  "contentProblem.input_unbound": "Nothing fills it yet.",
  "contentProblem.target_unknown":
    "It fills an input that what it binds into never declared.",
  "contentProblem.target_bound_twice":
    "An earlier binding already fills this input, or part of it.",
  "contentProblem.pointer_into_many":
    "It points through a field holding many, which names no place among them.",
  "contentProblem.source_unknown":
    "It points at nothing what it reads from declares.",
  "contentProblem.source_not_earlier":
    "It reads a step that does not come before the one it fills.",
  "contentProblem.source_does_not_fit":
    "What it reads does not fit what it fills.",
  "contentProblem.source_may_be_empty":
    "What it fills must be given, and what it reads need not be, so that may be empty.",
  "contentProblem.constant_does_not_fit":
    "The constant does not fit what it fills.",
  "contentProblem.constant_too_long":
    "The constant holds more than 8192 characters of text.",
  "contentProblem.constantTooLongBy":
    "The constant holds {excess, plural, one {one character} other {# characters}} of text more than the 8192 it may hold.",
  "contentProblem.constant_conceals":
    "The constant holds a character that shows nothing yet changes how text reads.",
  "contentProblem.output_unbound": "Nothing fills it yet.",
  "contentProblem.output_not_from_step":
    "What it gives back is filled from a step, and this is filled from elsewhere.",
  "contentProblem.helper_missing":
    "Runs may be helped, and no model is named to help them.",
  "contentProblem.helper_not_held":
    "Runs are helped by a model this deployment does not hold.",
  "contentProblem.helper_mode_not_offered":
    "Runs are helped in a mode their model does not offer.",
  "contentProblem.no_terms":
    "It holds no term yet, so nothing could answer with one.",
  "contentProblem.term_repeated":
    "A term before it is already this one, whatever the case either is written in.",
  /** {most} is the most one asking may send, the figure the server holds it to. */
  "contentProblem.asking_past_largest":
    "It could send {excess, plural, one {# character} other {# characters}} more than {most, number}, the most one asking may send.",
  "contentProblem.asking_past_largest_uncounted":
    "It could send more than {max, plural, one {# character} other {# characters}} past {most, number}, the most one asking may send.",
  "contentProblem.reviewPastLargest":
    "A model reviewing what it produces could be sent {excess, plural, one {# character} other {# characters}} more than {most, number}, the most one asking may send.",
  "contentProblem.reviewPastLargestUncounted":
    "A model reviewing what it produces could be sent more than {max, plural, one {# character} other {# characters}} past {most, number}, the most one asking may send.",
  "contentProblem.takes_past_largest":
    "All it takes, together at their limits, could be written out longer than one value may be, and a run keeps what it was started with as one.",
  /** {most} is the longest one value may be written out, the figure the server holds all a workflow takes to. */
  "contentProblem.takesPastLargestOf":
    "All it takes, together at their limits, could be written out in more than {most, number} characters, the longest one value may be, and a run keeps what it was started with as one.",
  "contentProblem.search_review_past_largest":
    "A model reviewing what it finds could be sent {excess, plural, one {# character} other {# characters}} more than {most, number}, the most one asking may send, before what it searched for and found is counted.",
  "contentProblem.searchReviewPastLargestUncounted":
    "A model reviewing what it finds could be sent more than {max, plural, one {# character} other {# characters}} past {most, number}, the most one asking may send, before what it searched for and found is counted.",
  /** Said at the step, of a model reviewing its code step's output; {most} as for asking_past_largest. */
  "contentProblem.code_step_review_past_largest":
    "A model reviewing what its code step gives back could be sent {excess, plural, one {# character} other {# characters}} more than {most, number}, the most one asking may send.",
  "contentProblem.codeStepReviewPastLargestUncounted":
    "A model reviewing what its code step gives back could be sent more than {max, plural, one {# character} other {# characters}} past {most, number}, the most one asking may send.",
  "contentProblem.search_tool_not_offered":
    "It searches with a model that does not offer the tool this way to search needs.",
  "contentProblem.search_way_not_held":
    "It runs a way to search this release does not offer.",
  "contentProblem.search_question_no_longer_matches":
    "Its question, {question}, no longer matches what {way} gives back. Make a new version of that question, then pick it for this step.",
  "contentProblem.searchQuestionNoLongerMatchesUnnamed":
    "Its question no longer matches what {way} gives back. Make a new version of that question, then pick it for this step.",
  /** Said at the step: {fields} are the fields its way gives back none is chosen for yet, joined; {count} how many. */
  "contentProblem.search_standing_missing":
    "It does not yet say whether {fields} {count, plural, one {stands always or waits} other {stand always or wait}} on a review.",
  /** A problem this build has no words for, still named where it is. */
  "contentProblem.unknown": "Something here does not hold.",
};
