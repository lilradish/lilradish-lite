import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useId, useLayoutEffect, useRef, useState } from "react";

import type { StepRow } from "../../../api/groups/{groupId}/runs/{runId}/steps";
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
import type { Standing } from "../../../api/standing";
import type { Problem } from "../../../api/problem";
import type { Who } from "../../../api/stepProducersAndReviewers";
import type { Try } from "../../../api/stepTries";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader, refusalSentence } from "../../../app/refusal";
import type { Rule } from "../../../app/standing/actRules";
import { useStandingRead } from "../../../app/standing/StandingContext";
import { say } from "../../../i18n/app";
import { ActButton } from "../../../lib/action/ActButton";
import { ActGroup } from "../../../lib/action/ActGroup";
import { heldOr, OnWords, Press } from "../../../lib/action/Press";
import { Fields } from "../../../lib/form/Fields";
import { ACTS_SX, SPACED_ABOVE_SX } from "../../../lib/layout/parts";
import { BLOCK_SX } from "../../../lib/layout/parts";
import { Notice } from "../../../lib/notice/Notice";
import { reasonRefused } from "../../../lib/text/legibility";
import { STEP_ACT_RULES } from "../actRules";
import { useRunReadAccess } from "../RunReadAccess";
import { FilledValue } from "../fill/FilledValue";
import { declaredFor, fieldAt, labelAt, type Declaring } from "./declared";
import {
  REASON_WORDS,
  stepOffers,
  useStepActValidity,
  useStepAction,
} from "./stepActs";
import { whoSaid } from "./stepProducersAndReviewers";
import { WentInValues } from "./WentInValues";
import { SearchRecord, SearchesCountedRows } from "./SearchRecord";
import { ProseScope } from "../../../lib/text/ModelProse";
import { TryMaterial } from "./TryRead";

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
export function waitingOnReader(
  step: StepRow,
  standing: Standing,
  groupId: string,
): readonly string[] {
  const where = step.where;
  return stepOffers(step, "review", standing, groupId) &&
    where?.kind === "waiting_on_review" &&
    where.number !== undefined
    ? (where.values ?? []).map(({ field }) => field)
    : [];
}

export function searchWaitingOnReader(
  step: StepRow,
  inHand: StepAnswer | null | undefined,
  standing: Standing,
  groupId: string,
): Try | undefined {
  if (
    step.runs.kind !== "search" ||
    waitingOnReader(step, standing, groupId).length === 0 ||
    inHand?.step.stepId !== step.stepId ||
    inHand.step.where?.kind !== "waiting_on_review" ||
    inHand.step.where.number !== step.where?.number
  ) {
    return undefined;
  }
  return inHand.triesMade.find(
    (each) => each.number === step.where?.number && each.search !== undefined,
  );
}

export function ReviewControls({
  groupId,
  runId,
  step,
  inHand,
  searchDrawn = false,
  declaring,
  heading,
  landsOn,
  onShown,
  message = false,
}: {
  readonly groupId: string;
  readonly runId: string;
  readonly step: StepRow;
  readonly inHand?: StepAnswer | null;
  readonly searchDrawn?: boolean;
  readonly declaring: Declaring;
  readonly heading: "h2" | "h3";
  /** The element the keyboard goes to once the review's answer is shown, as its controls go with it. */
  readonly landsOn: string;
  readonly onShown: (answer: StepAnswer) => void;
  readonly message?: boolean;
}) {
  const baseId = useId();
  const wentInId = useId();
  const headingId = useId();
  const noticeId = useId();
  const standingRead = useStandingRead();
  const reloadStanding = standingRead.reload;
  const standing = standingRead.value;
  const access = useRunReadAccess();
  const focusNext = useRef<string | null>(null);
  const waiting = waitingOnReader(step, standing, groupId);
  const number = step.where?.number ?? 0;
  const validity = useStepActValidity(step, "review", standing, groupId, runId);
  const requestVersion = useRef<number | null>(null);
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
  const searchTry = searchWaitingOnReader(step, inHand, standing, groupId);
  const several = waiting.length > 1;

  const rereading = useStepAction<StepAnswer>(
    validity,
    (answer) => {
      if (!validity.still(requestVersion.current)) return;
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

  const reviewing = useStepAction<StepAnswer>(
    validity,
    (answer) => {
      if (!validity.still(requestVersion.current)) return;
      focusNext.current = landsOn;
      onShown(answer);
    },
    (problem) => {
      if (!validity.still(requestVersion.current)) return;
      if (movesTheReader(problem)) {
        reloadStanding();
      }
      if (refusedAsMovedOn(problem) || refusedAsWithdrawn(problem)) {
        rereading.run((signal) =>
          access.read(signal, () =>
            readStep(groupId, runId, step.stepId, signal),
          ),
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
    requestVersion.current = validity.begin();
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

  if (waiting.length === 0 && notices === null && rereadRefusal === null)
    return null;

  return (
    <Box component={message ? "li" : "div"} sx={BLOCK_SX}>
      <ProseScope independent>
        {waiting.length === 0 || step.wentIn === undefined ? null : (
          <Box
            component="section"
            aria-labelledby={wentInId}
            sx={SPACED_ABOVE_SX}
          >
            <Typography id={wentInId} variant={heading}>
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
          <Box
            component="section"
            aria-labelledby={headingId}
            sx={SPACED_ABOVE_SX}
          >
            <Typography id={headingId} variant={heading}>
              {say("step.needsReview")}
            </Typography>
            {searchDrawn || searchTry?.search === undefined ? null : (
              <>
                <TryMaterial number={searchTry.number}>
                  {(material) =>
                    material?.search === undefined ? null : (
                      <SearchRecord said={material.search.said} />
                    )
                  }
                </TryMaterial>
                {searchTry.search.counted === undefined ? null : (
                  <Fields>
                    <SearchesCountedRows counted={searchTry.search.counted} />
                  </Fields>
                )}
              </>
            )}
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
                <Box key={field} sx={SPACED_ABOVE_SX}>
                  <Typography
                    hidden={given !== undefined}
                    id={labelId}
                    variant="subtitle2"
                    component="p"
                  >
                    {label}
                  </Typography>
                  {given === undefined ? null : (
                    <FilledValue
                      cards
                      field={fieldAt(gives, field)}
                      label={label}
                      shown={given}
                      status={given.now}
                      shut={false}
                    />
                  )}
                  {several ? (
                    <Decision
                      label={say("step.decisionFor", { field: label })}
                      choice={choice}
                      held={reviewing.running || rereading.running}
                      onChoose={(next, pressed) => {
                        if (next === choice) return;
                        if (next === "refused" && pressed)
                          focusNext.current = reasonInputId(index);
                        choose(field, next);
                      }}
                    />
                  ) : (
                    <Box role="group" aria-labelledby={labelId} sx={ACTS_SX}>
                      <ActGroup>
                        <ActButton
                          tier="secondary"
                          fill
                          action={reviewing}
                          waiting={rereading.running}
                          act={(signal) =>
                            send(() => ({ outcome: "assured" }), signal)
                          }
                        >
                          {say("step.assure")}
                        </ActButton>
                        <Press
                          tier="secondary"
                          fill
                          aria-pressed={choice === "refused"}
                          unavailable={reviewing.running || rereading.running}
                          onPress={() => {
                            if (choice !== "refused") {
                              focusNext.current = reasonInputId(index);
                            }
                            choose(
                              field,
                              choice === "refused" ? null : "refused",
                            );
                          }}
                        >
                          {say("step.refuse")}
                        </Press>
                      </ActGroup>
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
                      sx={SPACED_ABOVE_SX}
                    />
                  ) : null}
                </Box>
              );
            })}
            {several || shown.chosen.get(waiting[0]!) === "refused" ? (
              <Box sx={{ ...ACTS_SX, ...SPACED_ABOVE_SX }}>
                <ActButton
                  tier="primary"
                  action={reviewing}
                  waiting={rereading.running}
                  reason={
                    ready
                      ? undefined
                      : {
                          severity: "info",
                          words: say(
                            several ? "step.chooseEach" : "step.sayWhy",
                          ),
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
          <Box id={noticeId} tabIndex={-1} sx={SPACED_ABOVE_SX}>
            {notices}
            {rereadRefusal === null ? null : (
              <ProblemView problem={rereadRefusal} />
            )}
          </Box>
        )}
      </ProseScope>
    </Box>
  );
}

const CHOICES = ["assured", "refused"] as const;

const CHOICE_WORDS = {
  assured: "step.assure",
  refused: "step.refuse",
} as const;

const ARROW_STEPS: Readonly<Record<string, number>> = {
  ArrowRight: 1,
  ArrowDown: 1,
  ArrowLeft: -1,
  ArrowUp: -1,
};

/** One value's decision: one stop for the keyboard, an arrow moving and choosing, and nothing pressed undoing it. */
function Decision({
  label,
  choice,
  held,
  onChoose,
}: {
  readonly label: string;
  readonly choice: Choice | undefined;
  readonly held: boolean;
  /** Pressed, rather than reached by an arrow, which leaves the keyboard on the choice. */
  readonly onChoose: (choice: Choice, pressed: boolean) => void;
}) {
  const radios = useRef<(HTMLButtonElement | null)[]>([]);
  const stop = choice === undefined ? 0 : CHOICES.indexOf(choice);
  return (
    <Box role="radiogroup" aria-label={label} sx={ACTS_SX}>
      <ActGroup>
        {CHOICES.map((each, index) => {
          const checked = choice === each;
          return (
            <Button
              key={each}
              ref={(radio: HTMLButtonElement | null) => {
                radios.current[index] = radio;
              }}
              variant="outlined"
              color="primary"
              fullWidth
              role="radio"
              aria-checked={checked}
              tabIndex={index === stop ? 0 : -1}
              {...heldOr(held, () => onChoose(each, true))}
              onKeyDown={(event) => {
                const step = ARROW_STEPS[event.key];
                if (step === undefined) return;
                event.preventDefault();
                if (held) return;
                const next = (index + step + CHOICES.length) % CHOICES.length;
                radios.current[next]?.focus();
                onChoose(CHOICES[next]!, false);
              }}
            >
              <OnWords on={checked}>{say(CHOICE_WORDS[each])}</OnWords>
            </Button>
          );
        })}
      </ActGroup>
    </Box>
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
