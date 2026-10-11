export const QUESTION_EN = {
  "question.drawingEmpty": "Inputs are shown field by field.",
  "question.searchUsed": "Search with: {way}",
  "question.inputsUnused":
    "{count, plural, one {The instruction never mentions {labels}. That's fine if the model doesn't need to be told about it.} other {The instruction never mentions {labels}. That's fine if the model doesn't need to be told about them.}}",
  "question.searchShapeMismatch":
    "No longer matches what {way} gives back; make a new version of it.",
  "question.searchPickerHint":
    "Only questions set up for searching with {way} are listed. To add one, turn on For searching in that question's editor.",
  "question.forSearching": "For searching",
  "question.forSearchingHint":
    "Turn this on to use this question in a search step. Its answer becomes exactly what the chosen search gives back.",
  "question.searchWith": "Search with",
  "question.searchShapeLocked":
    "Set by {way}. Turn off For searching to change it.",
  "question.searchStandingHint":
    "How sure it must be is yours to set; the rest is set by {way}.",
  "question.searchShapeUpdated":
    "{way} now gives back something different, so this answer was updated to match.",
  "question.instruction": "The instruction",
  "question.drawing": "How it is drawn",
  "question.drawingHint":
    "Written as Markdown. Put '{{name}}' where a value it takes should appear; write \\'{{' for two braces as they are. A value of several lines put on a line by itself is laid out as Markdown there, and any other value is drawn as text. No value changes the drawing around it, or becomes where a link goes or an image.",
  "question.drawingMissing":
    "It has no drawing, so a person answering it is shown what it takes field by field.",
  "question.drawingAbout": "About how it is drawn",
  "question.drawingExplained":
    "How the inputs are laid out for a person answering this question. Left empty, they are shown field by field.",
  "question.saveDrawing": "Save how it is drawn",
  "question.instructionHint":
    "What whoever answers it is told to do. What it takes is given beside this, never inside it.",
  "question.saysNothing": "It tells nobody anything yet.",
  "question.saveInstruction": "Save the instruction",
  "question.takes": "What it takes",
  "question.saveTakes": "Save what it takes",
  "question.gives": "The declared answer",
  "question.saveGives": "Save the declared answer",
  "question.underway":
    "It is being saved, and that can no longer be called back.",
  "question.added": "What is added when it runs",
  "question.addedSummary":
    "The instructions and answer schema, exactly as sent to the model.",
  "question.addedAbout": "About what is added when it runs",
  "question.addedHint":
    "What is sent to the model on top of the instruction, including the JSON Schema of the whole answer. Nobody edits it; a person answering fills the declared answer instead.",
  "question.addedNotYet":
    "It is shown once every field of the declared answer says all it has to.",

  /** Heard after a link's own words, never seen. */
  "link.newTab": "opens in a new tab",
  "question.whatElseWentIn": "Other inputs",
  /** Used where the field is named again and its value is too long to draw twice. */
  "question.shownAbove": "{label}, shown above",
  /** {value} is drawn within a line: a heading, or a link's words. */
  "question.labelledValue": "{label}: {value}",
  "question.drawingNotLaidOut":
    "This question's layout couldn't be shown, so its inputs are listed one by one.",
};
