import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import FormControlLabel from "@mui/material/FormControlLabel";
import Radio from "@mui/material/Radio";
import RadioGroup from "@mui/material/RadioGroup";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useLayoutEffect, useRef, useState } from "react";

import type {
  StepRow,
  Who,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import {
  refusedAsMovedOn,
  refusedAsWithdrawn,
  refusedForTheReason,
  reviewTry,
  type ReviewDecision,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}/tries/{number}/review";
import type { Problem } from "../../../api/problem";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader, refusalSentence } from "../../../app/refusal";
import type { Rule } from "../../../app/standing/actRules";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { ActButton } from "../../../lib/action/ActButton";
import { ACTS_SX } from "../../../lib/layout/parts";
import { Notice } from "../../../lib/notice/Notice";
import { useAction } from "../../../lib/request/useAction";
import { reasonRefused } from "../../../lib/text/legibility";
import { STEP_ACT_RULES } from "../actRules";
import { FilledValue } from "../fill/FilledValue";
import { declaredFor, fieldAt, labelAt, type Declaring } from "./declared";
import { REASON_WORDS, whoSaid } from "./stepStates";
import { WentInValues } from "./WentInValues";

const PART_SX = { mt: 2 };

const REVIEW_RULE: Rule = { inGroup: STEP_ACT_RULES.review };

type Choice = ReviewDecision["outcome"];

/** What the reader has chosen of the try numbered, and the reasons typed; another try starts afresh. */
interface Draft {
  readonly number: number;
  readonly chosen: ReadonlyMap<string, Choice>;
  readonly reasons: ReadonlyMap<string, string>;
}

/** A reason the server refused, said at the field it concerns until that reason is changed. */
interface Marked {
  readonly field: string;
  readonly words: string | null;
}

function blank(number: number): Draft {
  return { number, chosen: new Map(), reasons: new Map() };
}

/** The fields of the try waiting on the reader's review, by name; none where the step offers the reader no review. */
export function waitingOnReader(step: StepRow): readonly string[] {
  const where = step.where;
  return step.acts.includes("review") &&
    where?.kind === "waiting_on_review" &&
    where.number !== undefined
    ? (where.values ?? []).map(({ field }) => field)
    : [];
}

/**
 * Every value waiting on the reader's review, in full, each assured or refused with a reason, sent as one review;
 * with what went in, where the row carries it. A lone value is assured at a press. Whatever answers, the step read
 * with it is handed to `onShown`; a refusal saying the step moved reads it again and hands that on too.
 */
export function ReviewControls({
  groupId,
  runId,
  step,
  declaring,
  heading,
  landsOn,
  onShown,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly step: StepRow;
  readonly declaring: Declaring;
  readonly heading: "h3" | "h4";
  /** The element the keyboard goes to once the review's answer is shown, as its controls go with it. */
  readonly landsOn: string;
  readonly onShown: (answer: StepAnswer) => void;
}) {
  const baseId = useId();
  const wentInId = useId();
  const headingId = useId();
  const noticeId = useId();
  const reloadStanding = useStandingRead().reload;
  const focusNext = useRef<string | null>(null);
  const waiting = waitingOnReader(step);
  const number = step.where?.number ?? 0;
  const [draft, setDraft] = useState(() => blank(number));
  const [marked, setMarked] = useState<Marked | null>(null);
  const [firstBy, setFirstBy] = useState<Who | null>(null);
  const [sentFor, setSentFor] = useState<number | null>(null);
  const [readAgainAt, setReadAgainAt] = useState<number | null>(null);
  // Not remounted by try: a key would drop the moved-on notice and the keyboard's landing as the step moves on.
  // The try the refusal's own read showed keeps the notice: only a later try waiting makes it stale.
  const stale =
    waiting.length > 0 && sentFor !== number && readAgainAt !== number;
  const shown = draft.number === number ? draft : blank(number);
  const declared = declaredFor(step, declaring.declarations);
  const gives = declared?.gives;
  const several = waiting.length > 1;

  const rereading = useAction<StepAnswer>(
    (answer) => {
      focusNext.current = noticeId;
      setReadAgainAt(answer.step.where?.number ?? null);
      setFirstBy(
        answer.triesMade.find((each) => each.number === sentFor)?.review.by ??
          null,
      );
      onShown(answer);
    },
    () => {
      focusNext.current = noticeId;
    },
  );

  const reviewing = useAction<StepAnswer>(
    (answer) => {
      focusNext.current = landsOn;
      onShown(answer);
    },
    (problem) => {
      if (movesTheReader(problem)) {
        reloadStanding();
      }
      if (refusedAsMovedOn(problem) || refusedAsWithdrawn(problem)) {
        rereading.run((signal) =>
          readStep(groupId, runId, step.stepId, signal),
        );
      }
      const concerned = refusedForTheReason(problem)
        ? concernedBy()
        : undefined;
      if (concerned !== undefined) {
        setMarked({ field: concerned, words: refusalSentence(problem) });
        focusNext.current = reasonInputId(waiting.indexOf(concerned));
      } else if (!refusedAsMovedOn(problem)) {
        focusNext.current = noticeId;
      }
    },
  );

  // Only once an act of the reader's has asked for it: a read again later takes the keyboard nowhere.
  useLayoutEffect(() => {
    const target = focusNext.current;
    focusNext.current = null;
    if (target !== null) {
      document.getElementById(target)?.focus();
    }
  });

  function reasonInputId(index: number): string {
    return `${baseId}-reason-${index}`;
  }

  /**
   * The refused value a refused reason is taken to concern: the only one refused, or none. Every reason sent passed
   * the page's own judgement first, so that judgement cannot tell which of several the server refused.
   */
  function concernedBy(): string | undefined {
    const refused = waiting.filter(
      (field) => shown.chosen.get(field) === "refused",
    );
    return refused.length === 1 ? refused[0] : undefined;
  }

  const changing = (change: (last: Draft) => Draft) =>
    setDraft((last) => change(last.number === number ? last : blank(number)));

  const choose = (field: string, choice: Choice | null) => {
    changing((last) => {
      const chosen = new Map(last.chosen);
      if (choice === null) {
        chosen.delete(field);
      } else {
        chosen.set(field, choice);
      }
      return { ...last, chosen };
    });
  };

  const write = (field: string, typed: string) => {
    setMarked((last) =>
      last?.field === field ? { field, words: null } : last,
    );
    changing((last) => ({
      ...last,
      reasons: new Map(last.reasons).set(field, typed),
    }));
  };

  const send = (
    decided: (field: string) => ReviewDecision,
    signal: AbortSignal,
  ) => {
    setSentFor(number);
    setMarked(null);
    setFirstBy(null);
    return reviewTry(
      groupId,
      runId,
      step.stepId,
      number,
      Object.fromEntries(waiting.map((field) => [field, decided(field)])),
      signal,
    );
  };

  const decisionOf = (field: string): ReviewDecision =>
    shown.chosen.get(field) === "refused"
      ? { outcome: "refused", why: shown.reasons.get(field) ?? "" }
      : { outcome: "assured" };

  const ready = waiting.every((field) => {
    const choice = shown.chosen.get(field);
    return (
      choice === "assured" ||
      (choice === "refused" &&
        reasonRefused(shown.reasons.get(field) ?? "") === null)
    );
  });

  const notices = stale
    ? null
    : noticesOf(reviewing.problem, rereading.running, firstBy, marked !== null);
  const rereadRefusal = stale ? null : rereading.problem;
  const variant = heading === "h3" ? "h6" : "subtitle1";

  return (
    <>
      {waiting.length === 0 || step.wentIn === undefined ? null : (
        <Box component="section" aria-labelledby={wentInId} sx={PART_SX}>
          <Typography id={wentInId} variant={variant} component={heading}>
            {say("step.wentIn")}
          </Typography>
          <WentInValues
            wentIn={step.wentIn}
            takes={declared?.takes}
            declaring={declaring}
          />
        </Box>
      )}
      {waiting.length === 0 ? null : (
        <Box component="section" aria-labelledby={headingId} sx={PART_SX}>
          <Typography id={headingId} variant={variant} component={heading}>
            {say("step.yourReview")}
          </Typography>
          {waiting.map((field, index) => {
            const label = labelAt(gives, field);
            const labelId = `${baseId}-label-${index}`;
            const given = step.gaveBack?.find((each) => each.field === field);
            const choice = shown.chosen.get(field);
            const typed = shown.reasons.get(field) ?? "";
            const judged = typed === "" ? null : reasonRefused(typed);
            const problem =
              !stale && marked?.field === field && marked.words !== null
                ? marked.words
                : judged === null
                  ? null
                  : say(REASON_WORDS[judged]);
            return (
              <Box key={field} sx={PART_SX}>
                <Typography id={labelId} variant="subtitle2" component="p">
                  {label}
                </Typography>
                {given === undefined ? null : (
                  <FilledValue
                    field={fieldAt(gives, field)}
                    label={label}
                    shown={given}
                    shut={false}
                  />
                )}
                {several ? (
                  <RadioGroup
                    row
                    aria-labelledby={labelId}
                    value={choice ?? ""}
                    onChange={(_event, value) =>
                      choose(field, value === "refused" ? "refused" : "assured")
                    }
                  >
                    <FormControlLabel
                      value="assured"
                      control={<Radio />}
                      label={say("step.assure")}
                    />
                    <FormControlLabel
                      value="refused"
                      control={<Radio />}
                      label={say("step.refuse")}
                    />
                  </RadioGroup>
                ) : (
                  <Box role="group" aria-labelledby={labelId} sx={ACTS_SX}>
                    <ActButton
                      action={reviewing}
                      waiting={rereading.running}
                      act={(signal) =>
                        send(() => ({ outcome: "assured" }), signal)
                      }
                    >
                      {say("step.assure")}
                    </ActButton>
                    <Button
                      aria-pressed={choice === "refused"}
                      onClick={() => {
                        // Only at this press: a radio moving the keyboard would pull arrow keys out of its group.
                        if (choice !== "refused") {
                          focusNext.current = reasonInputId(index);
                        }
                        choose(field, choice === "refused" ? null : "refused");
                      }}
                    >
                      {say("step.refuse")}
                    </Button>
                  </Box>
                )}
                {choice === "refused" ? (
                  <TextField
                    id={reasonInputId(index)}
                    label={say("step.whyRefuse", { field: label })}
                    value={typed}
                    onChange={(edit) => write(field, edit.target.value)}
                    error={problem !== null}
                    helperText={problem ?? undefined}
                    required
                    multiline
                    minRows={2}
                    fullWidth
                    sx={PART_SX}
                  />
                ) : null}
              </Box>
            );
          })}
          {several || shown.chosen.get(waiting[0]!) === "refused" ? (
            <Box sx={{ ...ACTS_SX, ...PART_SX }}>
              <ActButton
                action={reviewing}
                waiting={rereading.running}
                reason={
                  ready
                    ? undefined
                    : {
                        severity: "info",
                        words: say(several ? "step.chooseEach" : "step.sayWhy"),
                      }
                }
                act={(signal) => send(decisionOf, signal)}
              >
                {say("step.submitReview")}
              </ActButton>
            </Box>
          ) : null}
        </Box>
      )}
      {notices === null && rereadRefusal === null ? null : (
        <Box id={noticeId} tabIndex={-1} sx={PART_SX}>
          {notices}
          {rereadRefusal === null ? null : (
            <ProblemView problem={rereadRefusal} />
          )}
        </Box>
      )}
    </>
  );
}

/**
 * What a refusal of the review says: that it was reviewed already and by whom, once the step read again names the
 * person, who may be the reader themselves; nothing where a refused reason is said at its field; otherwise the
 * refusal's own sentence, one of the act said as the rule of who may review.
 */
function noticesOf(
  problem: Problem | null,
  rereading: boolean,
  firstBy: Who | null,
  saidAtField: boolean,
) {
  if (problem === null || (refusedForTheReason(problem) && saidAtField)) {
    return null;
  }
  if (refusedAsMovedOn(problem)) {
    if (rereading) {
      return null;
    }
    if (firstBy?.kind === "person" && firstBy.person !== undefined) {
      return (
        <Notice severity="info" alert>
          {say("step.reviewedAlready", { name: whoSaid(firstBy) })}
        </Notice>
      );
    }
  }
  return <ProblemView problem={problem} rule={REVIEW_RULE} />;
}
