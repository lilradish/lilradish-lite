import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { useId, useState } from "react";
import { Link as RouterLink, useLocation } from "react-router";

import type { GroupStanding } from "../../../api/standing";
import type { Problem } from "../../../api/problem";
import {
  readStep,
  type StepAnswer,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import {
  refusedAsChanged,
  refuseForLength,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}/length/refusal";
import { ProblemView } from "../../../app/ProblemView";
import { movesTheReader } from "../../../app/refusal";
import { useStandingRead } from "../../../app/standing/StandingContext";
import type { Length, LengthValue } from "../../../api/stepWhereabouts";
import { say } from "../../../i18n/app";
import { Disclosure } from "../../../lib/action/Disclosure";
import { Press } from "../../../lib/action/Press";
import { Widest } from "../../../lib/action/Widest";
import { ActDialog } from "../../../lib/action/ActDialog";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX, SPACED_ABOVE_SX } from "../../../lib/layout/parts";
import { useAction } from "../../../lib/request/useAction";
import { STEP_ACT_RULES } from "../actRules";
import { useRunReadAccess } from "../RunReadAccess";
import { listQuery, runHref, stepHref, WITHIN_THE_RUN } from "../runAddress";
import { countSaid } from "../spend";
import { declaredFor, fromSaid, labelAt, type Declaring } from "./declared";
import { spokenName } from "./stepTitle";

/** Counts remain exact digits even when the estimate exceeds JavaScript's integer range. */
export function lengthUnitsSaid(digits: string): string {
  return say(digits === "1" ? "step.lengthUnit" : "step.lengthUnits", {
    count: countSaid(digits),
  });
}

export function LengthPanel({
  length,
  group,
  declaring,
  message,
  stepId,
  onShown,
  onRefused,
}: {
  readonly length: Length;
  readonly group: GroupStanding;
  readonly declaring: Declaring;
  readonly message?: boolean;
  readonly stepId: string;
  readonly onShown: (answer: StepAnswer) => void;
  readonly onRefused: (problem: Problem | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const details = useId();
  const heading = useId();
  const shown = message !== true || open;
  const about = (digits: string) =>
    say("step.lengthAbout", { length: lengthUnitsSaid(digits) });
  const takes =
    declaring.step === undefined
      ? undefined
      : declaredFor(declaring.step, declaring.declarations)?.takes;
  return (
    <Box component="section" sx={SPACED_ABOVE_SX} aria-labelledby={heading}>
      {message === true ? (
        <div id={heading}>
          <Disclosure
            open={open}
            controls={details}
            onToggle={() => setOpen((was) => !was)}
          >
            <Widest
              labels={[say("step.lengthShow"), say("step.lengthHide")]}
              shown={open ? 1 : 0}
            />
          </Disclosure>
        </div>
      ) : (
        <Typography id={heading} variant="h3">
          {say("step.lengthHeading")}
        </Typography>
      )}
      <div id={details} hidden={!shown}>
        {!shown ? null : (
          <>
            <Fields>
              <Field
                label={say("step.lengthLimit", {
                  model: isolatedInText(length.model),
                })}
              >
                {lengthUnitsSaid(length.limitUnits)}
              </Field>
              <Field label={say("step.lengthWhole")}>
                {about(length.wholeUnits)}
              </Field>
              <Field label={say("step.lengthExcess")}>
                {about(length.excessUnits)}
              </Field>
              <Field label={say("step.lengthFixed")}>
                {about(length.fixedUnits)}
                <Typography variant="body2" sx={QUIET_SX}>
                  {say(
                    length.tellsWhatHappened
                      ? "step.lengthFixedWithRejection"
                      : "step.lengthFixedInstruction",
                  )}
                </Typography>
              </Field>
            </Fields>
            <Typography variant="body2" sx={QUIET_SX}>
              {say("step.lengthEstimates", {
                model: isolatedInText(length.model),
              })}
            </Typography>
            <Typography variant="h4" sx={SPACED_ABOVE_SX}>
              {say("step.lengthInputs")}
            </Typography>
            <Box component="ol" sx={{ pl: 2 }}>
              {length.values.map((value, index) => (
                <li key={value.valueId ?? index}>
                  <Typography>
                    {value.inputs
                      .map((path) => labelAt(takes, path))
                      .join(", ")}
                    :{" "}
                    {value.previousUnits === undefined
                      ? about(value.units)
                      : changedLengthSaid(value.units, value.previousUnits)}
                  </Typography>
                  <LengthSource
                    value={value}
                    group={group}
                    declaring={declaring}
                  />
                  {!value.mayRefuse ||
                  value.valueId === undefined ||
                  value.from.name === undefined ? null : (
                    <LengthRefusal
                      key={`${length.holdId}/${value.valueId}`}
                      length={length}
                      value={value}
                      groupId={group.groupId}
                      runId={declaring.runId}
                      stepId={stepId}
                      label={value.inputs
                        .map((path) => labelAt(takes, path))
                        .join(", ")}
                      onShown={onShown}
                      onRefused={onRefused}
                    />
                  )}
                </li>
              ))}
            </Box>
          </>
        )}
      </div>
    </Box>
  );
}

function changedLengthSaid(now: string, was: string): string {
  const current = BigInt(now);
  const previous = BigInt(was);
  const counts = { now: lengthUnitsSaid(now), was: countSaid(was) };
  return current <= previous
    ? say("step.lengthDown", {
        ...counts,
        gone: countSaid((previous - current).toString()),
      })
    : say("step.lengthUp", counts);
}

function LengthRefusal({
  length,
  value,
  groupId,
  runId,
  stepId,
  label,
  onShown,
  onRefused,
}: {
  readonly length: Length;
  readonly value: LengthValue;
  readonly groupId: string;
  readonly runId: string;
  readonly stepId: string;
  readonly label: string;
  readonly onShown: (answer: StepAnswer) => void;
  readonly onRefused: (problem: Problem | null) => void;
}) {
  const [open, setOpen] = useState(false);
  const reloadStanding = useStandingRead().reload;
  const access = useRunReadAccess();
  const rereading = useAction<StepAnswer>(onShown);
  const refusing = useAction<StepAnswer>(
    (answer) => {
      setOpen(false);
      onShown(answer);
    },
    (problem) => {
      if (movesTheReader(problem)) reloadStanding();
      if (refusedAsChanged(problem)) {
        onRefused(problem);
        setOpen(false);
        rereading.run((signal) =>
          access.read(signal, () => readStep(groupId, runId, stepId, signal)),
        );
      }
    },
  );
  return (
    <Box sx={SPACED_ABOVE_SX}>
      <Press
        tier="secondary"
        unavailable={refusing.running || rereading.running}
        onPress={() => {
          onRefused(null);
          setOpen(true);
        }}
      >
        {say("step.lengthReject")}
      </Press>
      <ActDialog
        open={open}
        title={say("step.lengthConfirm", { field: label })}
        onShut={() => setOpen(false)}
        action={refusing}
        act={(signal) =>
          refuseForLength(
            groupId,
            runId,
            stepId,
            length.holdId,
            value.valueId!,
            signal,
          )
        }
        actLabel={say("step.lengthReject")}
        abandonLabel={say("run.abandon")}
        underway={say("step.lengthReject")}
        refusal={
          refusing.problem === null ? null : (
            <ProblemView
              problem={refusing.problem}
              rule={{ inGroup: STEP_ACT_RULES.review }}
            />
          )
        }
      >
        <Typography>
          {say(
            value.triesSpent
              ? "step.lengthConfirmSpent"
              : "step.lengthConfirmAttempt",
            {
              step: isolatedInText(
                value.from.label ?? spokenName(value.from.name!),
              ),
            },
          )}
        </Typography>
      </ActDialog>
      {open ||
      refusing.problem === null ||
      refusedAsChanged(refusing.problem) ? null : (
        <ProblemView
          problem={refusing.problem}
          rule={{ inGroup: STEP_ACT_RULES.review }}
        />
      )}
      {rereading.problem === null ? null : (
        <ProblemView problem={rereading.problem} />
      )}
    </Box>
  );
}

function LengthSource({
  value,
  group,
  declaring,
}: {
  readonly value: LengthValue;
  readonly group: GroupStanding;
  readonly declaring: Declaring;
}) {
  const search = listQuery(useLocation().search);
  const from = value.from;
  const elsewhere = from.runId !== undefined && from.runId !== declaring.runId;
  const sourceAddress =
    from.kind === "constant" || from.runId === undefined
      ? undefined
      : from.stepId === undefined
        ? runHref(group, from.runId)
        : stepHref(group, from.runId, from.stepId);
  const source = fromSaid(from, declaring);
  const remake =
    from.kind === "constant"
      ? elsewhere && from.runNumber !== undefined
        ? say("step.lengthConstantAbove", { number: from.runNumber })
        : say("step.lengthConstant")
      : from.kind === "run_input"
        ? elsewhere && from.runNumber !== undefined
          ? say("step.lengthRunInputAbove", { number: from.runNumber })
          : say("step.lengthRunInput")
        : value.fromSubRun && from.runNumber !== undefined
          ? say("step.lengthFromSubRun", { number: from.runNumber })
          : value.canBeMadeAgain && from.name !== undefined
            ? say(
                value.triesSpent
                  ? "step.lengthMakeAgainSpent"
                  : "step.lengthMakeAgain",
                { step: isolatedInText(from.label ?? spokenName(from.name)) },
              )
            : null;
  return (
    <>
      <Typography variant="body2">
        {sourceAddress === undefined ? (
          source
        ) : (
          <Link
            component={RouterLink}
            to={`${sourceAddress}${search}`}
            state={from.stepId === undefined ? undefined : WITHIN_THE_RUN}
          >
            {source}
          </Link>
        )}
      </Typography>
      {elsewhere &&
      !value.fromSubRun &&
      from.kind === "step" &&
      from.name !== undefined &&
      from.runNumber !== undefined ? (
        <Typography variant="body2">
          {say("step.lengthMadeAbove", {
            step: isolatedInText(from.label ?? spokenName(from.name)),
            number: from.runNumber,
          })}
        </Typography>
      ) : null}
      {remake === null ? null : (
        <Typography variant="body2" sx={QUIET_SX}>
          {remake}
        </Typography>
      )}
      {value.fromSubRun &&
      from.runId !== undefined &&
      from.runNumber !== undefined ? (
        <Link
          component={RouterLink}
          to={`${runHref(group, from.runId)}${search}`}
        >
          {say("step.lengthOpenRun", { number: from.runNumber })}
        </Link>
      ) : null}
      {value.used.map((used) => (
        <Typography
          key={`${used.runId}/${used.stepId}`}
          variant="body2"
          sx={QUIET_SX}
        >
          <Link
            component={RouterLink}
            to={`${stepHref(group, used.runId, used.stepId)}${search}`}
            state={WITHIN_THE_RUN}
          >
            {used.choseBranch
              ? say("step.lengthUsedToBranch")
              : say(
                  used.runId === declaring.runId
                    ? "step.lengthUsed"
                    : "step.lengthUsedInRun",
                  {
                    step: isolatedInText(used.label ?? spokenName(used.name)),
                    number: used.runNumber,
                  },
                )}
          </Link>
        </Typography>
      ))}
    </>
  );
}
