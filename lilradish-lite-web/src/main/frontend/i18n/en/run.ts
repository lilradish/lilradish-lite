export const RUN_EN = {
  "run.view": "View",
  "run.conversation": "Conversation",
  "run.fullDetail": "Full detail",
  "run.title": "Run {number, number}",
  "run.aboutIt": "Overview",
  "run.number": "Number",
  "run.workflow": "Workflow",
  "run.workflowVersion": "{label}",
  "run.started": "Started",
  "run.startedBy": "By {name}, {when}",
  "run.startedAbove": "By step {step} of run {number, number}, {when}",
  "run.above": "Part of",
  "run.openAbove": "Open run {number, number}",
  "run.openStartingStep": "Open the step that started it",
  "run.where": "Status",
  "run.stoppedBy": "Stopped by {name}, {when}",
  /** A ceiling reached names nobody as having stopped the run. */
  "run.stoppedByOwnCeiling": "Stopped: usage limit reached, {when}",
  "run.stoppedByCeilingOf":
    "Stopped: usage limit of run {number} reached, {when}",
  "run.stoppedByOtherCeilings":
    "Stopped: usage limits of runs {numbers} reached, {when}",
  "run.stoppedByOwnAndOtherCeilings":
    "Stopped: its usage limit and the {count, plural, one {limit of run} other {limits of runs}} {numbers} reached, {when}",
  /** Where a run is, by the state the server works out. */
  "runState.running": "Running",
  "runState.stopped": "Stopped",
  "runState.failed": "Failed",
  "runState.done": "Done",
  "runState.unknown": "This page can't show this status yet.",
  "runState.nowAt": "Now at {step}",
  "run.stop": "Stop",
  "run.openAgain": "Resume",
  "run.rename": "Rename",
  /** What a run, a step or a try cost, counted in units. */
  "run.usage": "Usage",
  "run.spent": "{spent} in total: {sent} sent, {cameBack} received",
  /** Who counted it, where a model did not, of any figure: a try's may add up a call to produce and one to review. */
  "run.allMeasuredHere":
    "All of this is estimated by this system: no model reported its own count.",
  "run.someMeasuredHere":
    "Part of this is estimated by this system, where a model did not report its own count.",
  "run.cameBackUnknown":
    "The amount received is not known for every call, and is counted here as zero.",
  "run.ceiling": "Usage limit",
  "run.ceilingOf": "Usage limit of run {number, number}",
  "run.noCeiling": "None — no usage limit.",
  "run.heldBy":
    "Uses the usage limit of run {number, number}, the top-level run",
  "run.changeCeiling": "Change usage limit",
  "run.abandon": "Cancel",
  /** A raise asked for, which is not in force until it is approved. */
  "raise.to": "{name} asked {when} to raise it to {to}. Needs approval.",
  "raise.away": "{name} asked {when} to remove the limit. Needs approval.",
  "raise.approve": "Approve change",
  "raise.refuse": "Reject change",
  "raise.withdraw": "Withdraw request",
  /** Changing a run's ceiling, where nothing typed means none. */
  "ceiling.label": "Usage limit",
  "ceiling.hint": "Leave empty for no limit.",
  "ceiling.limit":
    "Enter a whole number from 1 to 9,007,199,254,740,991, in digits.",
  "ceiling.unchanged": "That is already the current limit.",
  "ceiling.approval":
    "Raising or removing the limit needs approval. Until it is approved, the current limit stays.",
  "ceiling.withdraws":
    "Any change you make here withdraws the request already pending approval.",
  "ceiling.underway": "Changing the limit — this can no longer be cancelled.",
  /** Renaming a run, which two runs may share a name. */
  "runName.label": "Name",
  "runName.limit":
    "Use 1 to 128 characters on one line, with at least one visible character.",
  "runName.unchanged": "Change the name first.",
  "runName.underway": "Renaming — this can no longer be cancelled.",
};
