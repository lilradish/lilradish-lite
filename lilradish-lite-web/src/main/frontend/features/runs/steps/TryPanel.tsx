import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { useId, useState } from "react";

import type { ReadField } from "../../../api/filling";
import type {
  TriedValue,
  Try,
  WentWrong,
} from "../../../api/groups/{groupId}/runs/{runId}/steps/{stepId}";
import { say } from "../../../i18n/app";
import { readersIntl } from "../../../i18n/intl";
import { isolatedInText } from "../../../lib/direction/isolated";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { nameOrNumber } from "../../../lib/text/names";
import { FilledValue } from "../fill/FilledValue";
import { CostDrawn } from "../spend";
import { fieldAt, labelAt } from "./declared";
import {
  decisionSaid,
  endedSaid,
  misfitSaid,
  reviewSaid,
  standingSaid,
  whoSaid,
} from "./stepStates";
import { Turnaways } from "./Turnaways";

const WORDS_SX = { whiteSpace: "pre-wrap" };

const SURE_SX = { m: 0, pl: 2 };

/**
 * Everything of one try: who asked and who produced it, what it gave for the value picked and how sure a model was
 * of it, where the step gives any back, and its review. With no value picked, how sure it was of each value it said.
 */
export function TryPanel({
  aTry,
  field,
  gives,
  turnawaysWithHold,
}: {
  readonly aTry: Try;
  readonly field: string | undefined;
  readonly gives: readonly ReadField[] | undefined;
  /** Whether a hold on its being turned away holds it, which says each time it was turned away instead. */
  readonly turnawaysWithHold: boolean;
}) {
  const value =
    field === undefined
      ? undefined
      : aTry.values.find((each) => each.field === field);
  return (
    <Fields>
      <Field label={say("step.askedBy")}>
        {aTry.askedBy === undefined
          ? say("step.askedByRun")
          : isolatedInText(nameOrNumber(aTry.askedBy))}
      </Field>
      <Field label={say("step.producedBy")}>{whoSaid(aTry.producedBy)}</Field>
      {aTry.why === undefined ? null : (
        <Field label={say("step.why")}>
          <Box component="bdi" sx={WORDS_SX}>
            {aTry.why}
          </Box>
        </Field>
      )}
      {field === undefined ? null : (
        <Field label={labelAt(gives, field)}>
          {value === undefined ? (
            say("step.gaveNothingFor")
          ) : (
            <>
              <FilledValue
                field={fieldAt(gives, field)}
                label={labelAt(gives, field)}
                shown={value}
                shut={false}
              />
              <Typography variant="body2" sx={QUIET_SX}>
                {standingSaid(value.now)}
              </Typography>
            </>
          )}
        </Field>
      )}
      {field === undefined ? (
        <SureOfEach values={aTry.values} gives={gives} />
      ) : value?.confidence === undefined ? null : (
        <Field label={say("step.howSure")}>
          {percentSaid(value.confidence)}
        </Field>
      )}
      <Field label={say("step.reviewedBy")}>{reviewSaid(aTry.review)}</Field>
      {value?.decision === undefined ||
      value.now === "refused_for_length" ? null : (
        <Field label={say("step.decision")}>
          {decisionSaid(value.decision)}
        </Field>
      )}
      <Field label={say("step.howItEnded")}>
        {endedSaid(aTry.ended)}
        {aTry.didNotFit === undefined ? null : (
          <Typography variant="body2" sx={QUIET_SX}>
            {misfitSaid(aTry.didNotFit)}
          </Typography>
        )}
      </Field>
      {turnawaysWithHold || aTry.turnedAway === undefined ? null : (
        <Field label={say("step.turnedAway")}>
          <Turnaways turnedAway={aTry.turnedAway} />
        </Field>
      )}
      {aTry.wentWrong === undefined ? null : (
        <Field label={say("step.wentWrong")}>
          {wentWrongSaid(aTry.wentWrong)}
        </Field>
      )}
      {aTry.returned === undefined ? null : (
        <Field label={say("step.returned")}>
          <Returned key={aTry.number} text={aTry.returned} />
        </Field>
      )}
      <Field label={say("step.cost")}>
        <CostDrawn cost={aTry.cost} />
      </Field>
    </Fields>
  );
}

function wentWrongSaid(wentWrong: WentWrong): string {
  if ("withheld" in wentWrong) {
    return say("step.withheld");
  }
  const detail = isolatedInText(wentWrong.detail);
  return wentWrong.cut ? say("step.wentWrongCut", { detail }) : detail;
}

function SureOfEach({
  values,
  gives,
}: {
  readonly values: readonly TriedValue[];
  readonly gives: readonly ReadField[] | undefined;
}) {
  const sure = values.flatMap(({ field, confidence }) =>
    confidence === undefined ? [] : [{ field, confidence }],
  );
  return sure.length === 0 ? null : (
    <Field label={say("step.howSure")}>
      <Box component="ul" sx={SURE_SX}>
        {sure.map(({ field, confidence }) => (
          <li key={field}>
            {say("step.sureOf", {
              field: labelAt(gives, field),
              percent: percentSaid(confidence),
            })}
          </li>
        ))}
      </Box>
    </Field>
  );
}

function percentSaid(confidence: number): string {
  return readersIntl.formatNumber(confidence / 100, { style: "percent" });
}

/** Shut until opened, however short: it is code's own output as it came back, which may run to millions of characters. */
function Returned({ text }: { readonly text: string }) {
  const [open, setOpen] = useState(false);
  const textId = useId();
  return (
    <>
      <Button
        size="small"
        aria-expanded={open}
        aria-controls={textId}
        onClick={() => setOpen((was) => !was)}
      >
        {say("step.inFull", { field: say("step.returned") })}
      </Button>
      <div id={textId} hidden={!open}>
        {open ? (
          <Box component="bdi" sx={WORDS_SX}>
            {text}
          </Box>
        ) : null}
      </div>
    </>
  );
}
