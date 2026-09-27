import Box from "@mui/material/Box";
import Button, { buttonClasses } from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";

import type { FillField, FillValues } from "../../../api/filling";
import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type Answering,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import {
  answerTry,
  refusedAsChanged,
  refusedAsMovedOn,
  refusedForTheReason,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}/tries/{number}/answer";
import type { Problem } from "../../../api/problem";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader, refusalSentence } from "../../../app/refusal";
import type { Rule } from "../../../app/standing/actRules";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { ActButton } from "../../../lib/action/ActButton";
import { ACTS_SX, QUIET_SX } from "../../../lib/layout/parts";
import { Notice } from "../../../lib/notice/Notice";
import { useAction } from "../../../lib/request/useAction";
import { reasonRefused } from "../../../lib/text/legibility";
import { STEP_ACT_RULES } from "../actRules";
import { FillForm, placeId } from "../fill/FillForm";
import { draftFrom } from "../fill/fillDrafts";
import { useFillDraft } from "../fill/useFillDraft";
import { declaredFor, type Declaring } from "./declared";
import { actedFirstSaid, movedOnNotice, staleFor } from "./movedOn";
import { REASON_WORDS, decisionSaid, standingSaid } from "./stepStates";
import { WentInValues } from "./WentInValues";

const PART_SX = { mt: 2 };

const STARTS_SX = { ...QUIET_SX, mt: 2 };

// Prose written by whoever made the question, which may run either way and over lines.
const INSTRUCTION_SX = { whiteSpace: "pre-wrap" };

const REASON_INPUT = { htmlInput: { dir: "auto" } };

const NONE_GIVEN: readonly FillField[] = [];

const ANSWER_RULE: Rule = { inGroup: STEP_ACT_RULES.answer };

/** The step the form opened from, and the read in hand as it opened, which any later read is fresher than. */
interface Opened {
  readonly answer: StepAnswer;
  readonly over: StepAnswer | null;
}

/** What is said once the try the form was open for is owed no longer, and the try owed by the read that said so. */
interface Overtaken {
  readonly number: number;
  readonly readAt: number | null;
  readonly words: string;
}

/** A reason the server refused: its words at the reason until it is changed, and said nowhere else until sent. */
interface ReasonRefused {
  readonly words: string | null;
}

/**
 * Answer it here, drawn only where the step offers it the reader: a button, which reads the step where the try it
 * owes is not in hand, then opens the instruction, what went in, and one control per value it gives, started from
 * the try refused before it with the words each value was refused with; and why. The step the answer is answered
 * with is handed to `onShown`, as is the step read again once an answer is refused as changed since it was read.
 */
export function AnswerHere({
  groupId,
  runId,
  step,
  inHand,
  held,
  onRunning,
  declaring,
  wentInDrawn,
  heading,
  landsOn,
  onShown,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly step: StepRow;
  /** The step as it is read beside the form, where it is; none where it is not read. */
  readonly inHand: StepAnswer | null;
  /** Whether an ask for the step's next try is out, which the answer waits on. */
  readonly held: boolean;
  readonly onRunning: (running: boolean) => void;
  readonly declaring: Declaring;
  /** Whether what went in is drawn beside it already, as the step's own page draws it. */
  readonly wentInDrawn: boolean;
  readonly heading: "h3" | "h4";
  /** The element the keyboard goes to once the answer's answer is shown, as the form goes with it. */
  readonly landsOn: string;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const sectionId = useId();
  const wentInId = useId();
  const headingId = useId();
  const reasonId = useId();
  const noticeId = useId();
  const reloadStanding = useStandingRead().reload;
  const focusNext = useRef<string | null>(null);
  const keyboardWithin = useRef(false);
  const wasDrawn = useRef(false);
  const [open, setOpen] = useState(false);
  const [opened, setOpened] = useState<Opened | null>(null);
  const [overtaken, setOvertaken] = useState<Overtaken | null>(null);
  const [readRefusal, setReadRefusal] = useState<Problem | null>(null);
  const [sentFor, setSentFor] = useState<number | null>(null);
  const [readAgainAt, setReadAgainAt] = useState<number | null>(null);
  const [firstSaid, setFirstSaid] = useState<string | null>(null);
  const [withdrawnAfter, setWithdrawnAfter] = useState<Problem | null>(null);
  const kept = opened?.answer.answering;
  const fresher = inHand !== null && inHand !== opened?.over ? inHand : null;
  // Ruled: a fresher read replaces the one the form opened from only while it answers the same try the same way.
  const read =
    kept !== undefined &&
    fresher?.answering !== undefined &&
    keyOf(fresher.answering) === keyOf(kept)
      ? fresher
      : (opened?.answer ?? null);
  const answering = read?.answering;
  const gives = answering?.gives ?? NONE_GIVEN;
  const key = keyOf(answering);
  const draft = useFillDraft(gives, key, () =>
    draftFrom(gives, lastGiven(answering)),
  );
  const [why, setWhy] = useState("");
  const [whyTried, setWhyTried] = useState(false);
  const [whyRefused, setWhyRefused] = useState<ReasonRefused | null>(null);
  const [whyKey, setWhyKey] = useState(key);
  if (whyKey !== key) {
    setWhyKey(key);
    setWhy("");
    setWhyTried(false);
    setWhyRefused(null);
  }
  const offered = step.acts.includes("answer");
  const drawn = open && offered && answering !== undefined;
  const stale = staleFor(step, sentFor, readAgainAt);

  const opening = (answer: StepAnswer) => {
    const first = answer.answering?.gives[0];
    focusNext.current =
      first === undefined ? reasonId : placeId(draft.formId, [first.name]);
    setOpened({ answer, over: inHand });
    setOpen(true);
  };

  const reading = useAction<StepAnswer>(
    (answer) => {
      if (answer.answering === undefined) {
        setOpened({ answer, over: inHand });
        focusNext.current = landsOn;
        onShown(answer);
      } else {
        opening(answer);
      }
    },
    (problem) => {
      setReadRefusal(problem);
      focusNext.current = noticeId;
    },
  );

  const rereading = useAction<StepAnswer>(
    (answer) => {
      // Ruled as `read` is: a read that offers no answer to the try still owed keeps the draft until one does again.
      if (
        answer.answering !== undefined ||
        answer.step.next?.number !== kept?.number
      ) {
        setOpened({ answer, over: inHand });
      }
      setReadAgainAt(answer.step.next?.number ?? null);
      setFirstSaid(actedFirstSaid(answer, sentFor));
      focusNext.current = noticeId;
      onShown(answer);
    },
    () => {
      focusNext.current = noticeId;
    },
  );

  const sending = useAction<StepAnswer>(
    (answer) => {
      setOpen(false);
      setOpened({ answer, over: inHand });
      setOvertaken(null);
      setWhy("");
      setWhyTried(false);
      focusNext.current = landsOn;
      onShown(answer);
    },
    (problem) => {
      if (movesTheReader(problem)) {
        reloadStanding();
      }
      if (draft.refusedWith(problem)) {
        return;
      }
      if (refusedForTheReason(problem)) {
        setWhyRefused({ words: refusalSentence(problem) });
        focusNext.current = reasonId;
        return;
      }
      if (refusedAsChanged(problem)) {
        rereading.run((signal) =>
          readStep(groupId, runId, step.stepId, signal),
        );
      }
      if (!refusedAsMovedOn(problem)) {
        focusNext.current = noticeId;
      }
    },
  );

  const running = sending.running || rereading.running;
  if (
    !offered &&
    sending.problem !== null &&
    withdrawnAfter !== sending.problem
  ) {
    setWithdrawnAfter(sending.problem);
  }
  // Only a read that no longer owes the try moves the form on: one that merely offers no answer now keeps it.
  if (open && kept !== undefined && !running) {
    const told =
      fresher !== null && passed(fresher.step, kept.number) ? fresher : null;
    if (told !== null || passed(step, kept.number)) {
      setOpen(false);
      setOpened(null);
      setOvertaken({
        number: kept.number,
        readAt: (told?.step ?? step).next?.number ?? null,
        words:
          (told === null ? null : actedFirstSaid(told, kept.number)) ??
          say("refusal.STEP_MOVED_ON"),
      });
    }
  }
  if (
    overtaken !== null &&
    staleFor(step, overtaken.number, overtaken.readAt)
  ) {
    setOvertaken(null);
  }

  useEffect(() => {
    onRunning(running);
  }, [onRunning, running]);

  // Once an act of the reader's has asked for it, or the form has gone from under the keyboard: a read again later
  // takes the keyboard nowhere else, and the form going takes it to what is said of the try, or where an answer would.
  useLayoutEffect(() => {
    const lost = wasDrawn.current && !drawn && keyboardWithin.current;
    wasDrawn.current = drawn;
    if (lost) {
      keyboardWithin.current = false;
    }
    const target =
      focusNext.current ??
      (lost ? (overtaken === null ? landsOn : noticeId) : null);
    focusNext.current = null;
    if (target !== null) {
      document.getElementById(target)?.focus();
    }
  });

  const press = () => {
    setOvertaken(null);
    setReadRefusal(null);
    if (drawn) {
      setOpen(false);
    } else if (owes(inHand, step)) {
      opening(inHand);
    } else if (owes(opened?.answer, step)) {
      opening(opened.answer);
    } else {
      reading.run((signal) => readStep(groupId, runId, step.stepId, signal));
    }
  };

  const act = (signal: AbortSignal) => {
    const number = answering?.number ?? 0;
    draft.sending();
    setWhyRefused(null);
    setSentFor(number);
    setFirstSaid(null);
    return answerTry(
      groupId,
      runId,
      step.stepId,
      number,
      draft.values,
      why,
      signal,
    );
  };
  const run = (asked: (signal: AbortSignal) => Promise<StepAnswer>) => {
    const first = draft.firstUnfit;
    if (first === undefined && reasonRefused(why) === null) {
      sending.run(asked);
      return;
    }
    if (first !== undefined) {
      draft.holdBack(first);
    } else if (whyTried) {
      // A second press draws nothing again, so no layout effect would carry the keyboard there.
      document.getElementById(reasonId)?.focus();
    } else {
      focusNext.current = reasonId;
    }
    setWhyTried(true);
  };

  const judged = why === "" && !whyTried ? null : reasonRefused(why);
  const whySaid =
    whyRefused?.words ?? (judged === null ? null : say(REASON_WORDS[judged]));
  const refusal = draft.saying(sending.problem);
  // A refused reason is said at the reason alone, and changing it, or the draft starting again, says nothing aloud.
  const saidApart = refusal !== null && refusedForTheReason(refusal);
  // Ruled: a refusal is forgotten once the step offers the answer again after withdrawing it, and once the try it
  // was about is said to be overtaken, which is all that is said of it.
  const forgotten =
    (offered &&
      withdrawnAfter !== null &&
      withdrawnAfter === sending.problem) ||
    (overtaken !== null && overtaken.number === sentFor);
  const noticed =
    saidApart || stale || forgotten
      ? null
      : movedOnNotice(
          refusal,
          refusedAsMovedOn,
          rereading.running,
          firstSaid,
          ANSWER_RULE,
        );
  const rereadRefusal = stale ? null : rereading.problem;
  const owed = drawn ? answering : step.next;
  const beyondSaid =
    owed?.beyond === true && step.tries !== undefined
      ? say("step.nextBeyond", {
          number: owed.number,
          allowed: step.tries.declared,
        })
      : null;
  const declared = declaredFor(step, declaring.declarations);
  const variant = heading === "h3" ? "h6" : "subtitle1";
  const lastRefused = answering?.lastRefused;

  return (
    <>
      {!offered ? null : (
        <Box sx={PART_SX}>
          <Button
            aria-expanded={drawn}
            aria-controls={drawn ? sectionId : undefined}
            aria-disabled={reading.running || undefined}
            className={reading.running ? buttonClasses.disabled : undefined}
            onClick={reading.running ? undefined : press}
          >
            {say("step.answerHere")}
          </Button>
          {beyondSaid === null ? null : (
            <Typography variant="body2">{beyondSaid}</Typography>
          )}
        </Box>
      )}
      {!drawn || wentInDrawn || read?.wentIn === undefined ? null : (
        <Box component="section" aria-labelledby={wentInId} sx={PART_SX}>
          <Typography id={wentInId} variant={variant} component={heading}>
            {say("step.wentIn")}
          </Typography>
          {read.wentInFrom === "not_yet_sent" ? (
            <Typography variant="body2" sx={QUIET_SX}>
              {say("step.notYetSent")}
            </Typography>
          ) : null}
          <WentInValues
            wentIn={read.wentIn}
            takes={declared?.takes}
            declaring={declaring}
          />
        </Box>
      )}
      {!drawn ? null : (
        <Box
          component="section"
          id={sectionId}
          aria-labelledby={headingId}
          sx={PART_SX}
          onFocus={() => {
            keyboardWithin.current = true;
          }}
          onBlur={() => {
            // A move within blurs before it focuses, so onFocus sets this again; removing the focused form fires no
            // blur, and React dispatches none before its layout effects run, so there this still says it was within.
            keyboardWithin.current = false;
          }}
        >
          <Typography id={headingId} variant={variant} component={heading}>
            {say("step.yourAnswer")}
          </Typography>
          {answering.instruction === undefined ? null : (
            <Box sx={PART_SX}>
              <Typography variant="subtitle2" component="p">
                {say("step.theInstruction")}
              </Typography>
              <Typography variant="body1" dir="auto" sx={INSTRUCTION_SX}>
                {answering.instruction}
              </Typography>
            </Box>
          )}
          {lastRefused === undefined ? null : (
            <Typography variant="body2" sx={STARTS_SX}>
              {say("step.startsFromTry", { number: lastRefused.number })}
            </Typography>
          )}
          <Box sx={PART_SX}>
            <FillForm
              formId={draft.formId}
              fields={gives}
              draft={draft.fill}
              onDraft={draft.onFill}
              onLeave={draft.onLeave}
              onUnread={draft.onUnread}
              marks={draft.marks}
              beside={refusedBeside(answering)}
            />
          </Box>
          <TextField
            id={reasonId}
            label={say("step.whyAnswer")}
            value={why}
            onChange={(edit) => {
              setWhy(edit.target.value);
              setWhyRefused((last) =>
                last === null || last.words === null ? last : { words: null },
              );
            }}
            error={whySaid !== null}
            helperText={whySaid ?? undefined}
            slotProps={REASON_INPUT}
            required
            multiline
            minRows={2}
            fullWidth
            sx={PART_SX}
          />
          <Box sx={{ ...ACTS_SX, ...PART_SX }}>
            <ActButton
              action={{ ...sending, run }}
              waiting={rereading.running || held}
              act={act}
            >
              {say("step.send")}
            </ActButton>
          </Box>
          <div role="status">
            {draft.unmarked > 0 ? (
              <Notice severity="info">
                {say("fill.moreProblems", { count: draft.unmarked })}
              </Notice>
            ) : null}
          </div>
          {draft.unfit === undefined ? null : (
            <Notice severity="info">{draft.unfit}</Notice>
          )}
        </Box>
      )}
      {overtaken === null &&
      noticed === null &&
      readRefusal === null &&
      rereadRefusal === null ? null : (
        <Box id={noticeId} tabIndex={-1} sx={PART_SX}>
          {overtaken === null ? null : (
            <Notice severity="info" alert>
              {overtaken.words}
            </Notice>
          )}
          {noticed}
          {readRefusal === null ? null : <ProblemView problem={readRefusal} />}
          {rereadRefusal === null ? null : (
            <ProblemView problem={rereadRefusal} />
          )}
        </Box>
      )}
    </>
  );
}

/**
 * Whether `step` owes a try later than try `number`, or owes none; one held back names no next try while it is
 * owed all the same, as a code step whose code is not held does.
 */
function passed(step: StepRow, number: number): boolean {
  const next = step.next;
  return next === undefined
    ? step.where?.kind !== "held_back"
    : next.number > number;
}

/** Whether `answer` puts to the reader the try the step owes now. */
function owes(
  answer: StepAnswer | null | undefined,
  step: StepRow,
): answer is StepAnswer {
  return (
    answer?.answering !== undefined &&
    answer.answering.number === step.next?.number
  );
}

/** The try an answer fills and the fields it gives, either of which moving starts the draft again. */
function keyOf(answering: Answering | undefined): string {
  return answering === undefined
    ? ""
    : JSON.stringify([answering.number, answering.gives]);
}

/**
 * Every value the try refused before gave, assured ones too, as each is given again; none withheld from the reader,
 * and none kept in a shape the step no longer has, which no field now drafts.
 */
function lastGiven(answering: Answering | undefined): FillValues {
  return Object.fromEntries(
    (answering?.lastRefused?.values ?? []).flatMap((each) =>
      "value" in each ? [[each.field, each.value]] : [],
    ),
  );
}

/**
 * What is said of each value of the try refused before, by its field: that it is withheld from the reader, or was
 * kept in a shape the step no longer has, and the words it was refused with; one refused for its length says so
 * whatever its review said.
 */
function refusedBeside(answering: Answering): ReadonlyMap<string, string> {
  const lastRefused = answering.lastRefused;
  if (lastRefused === undefined) {
    return new Map();
  }
  return new Map(
    lastRefused.values.flatMap((each) => {
      const said: string[] = [];
      if ("withheld" in each) {
        said.push(say("step.lastWithheld", { number: lastRefused.number }));
      } else if ("asKept" in each) {
        said.push(say("step.lastEarlierShape", { number: lastRefused.number }));
      }
      if (each.now === "refused_for_length") {
        said.push(standingSaid(each.now));
      } else if (each.now === "refused") {
        said.push(
          each.decision === undefined
            ? standingSaid(each.now)
            : decisionSaid(each.decision),
        );
      }
      return said.length === 0 ? [] : [[each.field, said.join(" ")] as const];
    }),
  );
}
