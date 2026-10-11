export const LIBRARY_EN = {
  "prose.editInstruction": "Edit the instruction",
  "prose.editDrawing": "Edit how it is drawn",
  "prose.editNote": "Edit the note",
  "version.partsUnsaved":
    "{count, plural, one {# part unsaved} other {# parts unsaved}}",
  "version.stillSaving": "Still saving",
  "version.name": "Version name",
  "version.addName": "Add a name",
  "version.addNameForNumber": "Add a name for Version {number, number}",
  "version.nameHint":
    "Optional. A short name to tell this version apart, such as \"Stricter tone\". It's shown wherever this version is and can be changed at any time; it doesn't change what the version holds.",
  "version.nameLimit":
    "A version name is up to 64 characters on one line, with single spaces between words, none at the start or end, and something in it that shows. Leave it empty for none.",
  /** A group's entries of one kind, one page per kind; each heading names the group. */
  "library.workflows": "Workflows of {group}",
  "library.questions": "Questions of {group}",
  "library.referenceLists": "Reference lists of {group}",
  "library.workflowsLabel": "The group's workflows",
  "library.questionsLabel": "The group's questions",
  "library.referenceListsLabel": "The group's reference lists",
  "library.noWorkflows": "This group has no workflows yet.",
  "library.noQuestions": "This group has no questions yet.",
  "library.noReferenceLists": "This group has no reference lists yet.",
  "library.newWorkflow": "New workflow",
  "library.newQuestion": "New question",
  "library.newReferenceList": "New reference list",
  "library.filter": "Part of a name",
  "library.status": "Status",
  "library.noMatch": "No entry's name here holds that.",
  "versionPicker.placeholder": "Type a name or number to narrow.",
  "editorVersionPicker.placeholder": "Type a name or number to find a version.",
  "editorVersionPicker.more":
    "More match than are shown; type more to narrow them.",
  "editorVersionPicker.noMatch": "No version in service matches.",
  "editorVersionPicker.runs": "Runs {label}",
  "versionPicker.noMatch":
    "No loaded version matches. More versions may exist; narrow by the entry's name first.",

  "entryKind.workflow": "Workflow",
  "entryKind.question": "Question",
  "entryKind.reference_list": "Reference list",

  "entry.name": "Name",
  "entry.purpose": "What it is for",
  "entry.inService": "In service",
  "entry.stopped": "Stopped",
  "entry.statusWaiting": "Waiting for approval",
  "entry.inServiceVersion": "Version {number, number}",
  "entry.noneInService": "None",
  "entry.saysNothing": "It says nothing of what it is for.",
  "entry.stoppedBy": "Stopped by {name} · {when}",
  "entry.stoppedByGone":
    "Stopped by somebody who can no longer be found · {when}",
  "entry.stop": "Stop it being used",
  "entry.letGo": "Let it be used again",
  "entry.nameLimit":
    "A name is one to 128 characters on one line: no space but single plain ones between words, none at either end, and something in it that shows.",
  "entry.purposeLimit":
    "What it is for is at most 512 characters on one line, with something in it that shows.",
  "entry.purposeHint":
    "One line saying what it does, for whoever picks it. It may be left empty.",
  "entry.create": "Create",
  "entry.abandon": "Cancel",
  "entry.createUnderway":
    "It is being made, and that can no longer be called back.",
  "entry.edit": "Edit",
  "entry.editTitle": "Edit this entry",
  "entry.save": "Save",
  "entry.editUnderway":
    "It is being saved, and that can no longer be called back.",
  "entry.unchanged": "Change the name or what it is for first.",

  "versions.label": "Versions",
  "versions.newDraft": "New draft",
  "versions.standing": "Standing",
  "versions.writtenBy": "Written by",
  "versions.approvedBy": "Approved by",
  "versions.none": "It has no version yet.",
  "version.number": "Version {number, number}",
  "version.draft": "Draft",
  "version.submitted": "Submitted",
  "version.in_service": "In service",
  "version.retired": "Retired",
  "version.writtenByNobody": "System",
  "version.pinnedByLabel": "Pinned by",
  "version.details": "Details",
  "version.pinnedByHeading": "Version {number, number} is pinned by",
  "version.pinnedByNothing": "Nothing in service",
  "version.pinnedByCount":
    "{count, plural, one {# version:} other {# versions:}}",
  "version.pinnedByMore": "and {count, number} more",
  /** Said of a submitted version, which nobody may change and somebody else has to approve. */
  "version.waiting": "Waiting on anybody who may approve an entry here.",
  /** A submission or approval refused for pins retired since. */
  "pins.label": "Pinned, and retired since",
  "pins.replaceable":
    "{label} is retired. Version {newest, number} is in service.",
  "pins.unreplaceable":
    "{label} is retired, and no version of it is in service.",
  "pins.openNewest": "Open {label}",
  "pins.onDraft":
    "Taking the version in service in place of each retired one is a change to this draft.",
  "pins.onSubmitted":
    "A submitted version cannot be changed: withdraw it first, then take the version in service in place of each retired one.",
  "version.submit": "Submit",
  "version.withdraw": "Withdraw",
  "version.approve": "Approve",
  "version.retire": "Retire",
  "version.retireConfirm": "Retire {label}?",
  "version.retireConfirmBody":
    "Nothing new will be offered it, and whatever already names it goes on as it is. It cannot be put back in service.",
  "version.retireUnderway":
    "It is being retired, and that can no longer be called back.",

  /** A write refused for the draft having been written since the page read it. */
  "draft.readAfresh": "Load the saved draft",
  "draft.readAfreshHint":
    "What you typed is kept until you load the saved draft. Loading it shows the draft as it is saved now and drops anything here that was not saved.",
};
