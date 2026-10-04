import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useLocation } from "react-router";

import type {
  ConversationEvent,
  Run,
} from "../../../api/groups/{groupId}/runs/{runId}";
import type {
  RunSteps,
  StepRow,
} from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { isTheReader } from "../../../api/standing";
import { useStanding } from "../../../app/standing/StandingContext";
import { say, type MessageId } from "../../../i18n/app";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { nameOrNumber } from "../../../lib/text/names";
import { useCalendarNow, whenText } from "../../../lib/time/When";
import { FilledValue } from "../fill/FilledValue";
import { listQuery, stepHref } from "../runAddress";
import { stoppedByCeilingsSaid } from "../runStates";
import { countSaid } from "../spend";
import { declaredFor, fieldAt, labelAt } from "./declared";
import { Message } from "./Message";
import { spokenName } from "./stepTitle";

const LIST_SX = { m: 0, pl: 2 };

export function ActMessage({
  event,
  run,
  reading,
  step,
  groupId,
}: {
  readonly event: Exclude<ConversationEvent, { readonly kind: "step_reached" }>;
  readonly run: Run;
  readonly reading: RunSteps;
  readonly step?: StepRow;
  readonly groupId: string;
}) {
  const standing = useStanding();
  const search = listQuery(useLocation().search);
  const now = useCalendarNow();
  const title = actTitle(event, run, step, now);
  const by = "by" in event ? event.by : undefined;
  const gives =
    step === undefined
      ? undefined
      : declaredFor(step, reading.declarations)?.gives;
  return (
    <Message title={title} byTheReader={isTheReader(standing, by)}>
      <Typography variant="body2" sx={QUIET_SX}>
        {by === undefined
          ? whenText(event.at, now)
          : say("run.startedBy", {
              name: isolatedInText(nameOrNumber(by)),
              when: whenText(event.at, now),
            })}
      </Typography>
      {event.kind === "answered_by_hand" ? (
        <>
          <Typography variant="body2">
            {say("act.try", { number: event.number })}
          </Typography>
          {event.values.length === 0 ? null : (
            <Fields>
              {event.values.map((value) => {
                const label = labelAt(gives, value.field);
                return (
                  <Field key={value.field} label={label}>
                    <FilledValue
                      field={fieldAt(gives, value.field)}
                      label={label}
                      shown={value}
                      shut={true}
                    />
                  </Field>
                );
              })}
            </Fields>
          )}
          {event.why === undefined ? null : (
            <Field label={say("step.why")}>{isolatedInText(event.why)}</Field>
          )}
        </>
      ) : null}
      {event.kind === "reviewed" ? (
        <Box component="ul" sx={LIST_SX}>
          {event.decisions.map((decision) => (
            <li key={decision.field}>
              {decision.outcome === "assured"
                ? say("act.assured", { field: labelAt(gives, decision.field) })
                : say("act.refused", {
                    field: labelAt(gives, decision.field),
                    reason: isolatedInText(decision.why),
                  })}
            </li>
          ))}
        </Box>
      ) : null}
      {event.kind === "refused_for_length" ? (
        <>
          {event.decisions.map((decision) => (
            <Typography key={decision.field} variant="body2">
              {say("act.lengthDetail", {
                field: labelAt(gives, decision.field),
                step: isolatedInText(spokenName(event.held.stepName)),
                number: event.held.run.number,
              })}
            </Typography>
          ))}
          {event.decisions.map((decision) =>
            decision.outcome === "refused" ? (
              <Typography key={`why-${decision.field}`} variant="body2">
                {isolatedInText(decision.why)}
              </Typography>
            ) : null,
          )}
          <Link
            component={RouterLink}
            to={`${stepHref(groupId, event.held.run.runId, event.held.stepId)}${search}`}
          >
            {say("act.openHeldStep", {
              step: isolatedInText(spokenName(event.held.stepName)),
            })}
          </Link>
        </>
      ) : null}
      {event.kind === "ceiling_superseded" && event.run.runId !== run.runId ? (
        <Typography variant="body2">
          {say("run.ceilingOf", { number: event.run.number })}
        </Typography>
      ) : null}
      {event.kind === "ceiling_superseded" ||
      event.kind === "ceiling_changed" ||
      event.kind === "ceiling_asked" ||
      event.kind === "ceiling_approved" ||
      event.kind === "ceiling_refused" ||
      event.kind === "ceiling_withdrawn" ? (
        <Typography variant="body2">
          {say("act.ceilingFromTo", {
            from:
              event.from === undefined
                ? say("act.noCeiling")
                : countSaid(event.from),
            to:
              event.to === undefined
                ? say("act.noCeiling")
                : countSaid(event.to),
          })}
        </Typography>
      ) : null}
    </Message>
  );
}

function actTitle(
  event: Exclude<ConversationEvent, { readonly kind: "step_reached" }>,
  run: Run,
  step: StepRow | undefined,
  now: Date,
): string {
  switch (event.kind) {
    case "answered_by_hand":
      return say(
        step?.runs.kind === "code_step" ? "act.saidDone" : "act.answeredByHand",
      );
    case "reviewed":
      return say("act.reviewed");
    case "refused_for_length":
      return say("act.refusedForLength");
    case "ceiling_superseded":
      return say("act.supersededCeiling");
    case "ceiling_changed":
    case "ceiling_asked":
    case "ceiling_withdrawn":
    case "ceiling_approved":
    case "ceiling_refused": {
      const names: Record<
        | "ceiling_changed"
        | "ceiling_asked"
        | "ceiling_withdrawn"
        | "ceiling_approved"
        | "ceiling_refused",
        readonly [MessageId, MessageId]
      > = {
        ceiling_changed: ["act.changedCeiling", "act.changedCeilingOf"],
        ceiling_asked: ["act.askedCeiling", "act.askedCeilingOf"],
        ceiling_withdrawn: ["act.withdrewCeiling", "act.withdrewCeilingOf"],
        ceiling_approved: ["act.approvedCeiling", "act.approvedCeilingOf"],
        ceiling_refused: ["act.refusedCeiling", "act.refusedCeilingOf"],
      };
      const target = event.run;
      const name = names[event.kind];
      return target.runId === run.runId
        ? say(name[0])
        : say(name[1], { number: target.number });
    }
    case "stopped":
      return say("act.stopped");
    case "opened_again":
      return say("act.openedAgain");
    case "stopped_by_ceiling":
      return stoppedByCeilingsSaid(
        run.runId,
        event.ceilings,
        whenText(event.at, now),
      );
  }
}
