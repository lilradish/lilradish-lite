import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import type { ReadField } from "../../../api/filling";
import type { WentIn } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { FilledValue } from "../fill/FilledValue";
import {
  fieldAt,
  fromSaid,
  inputFields,
  labelAt,
  type Declaring,
} from "./declared";

export function WentInListed({
  wentIn,
  takes,
  declaring,
  sourcesOnly = false,
}: {
  readonly wentIn: readonly WentIn[];
  readonly takes: readonly ReadField[] | undefined;
  readonly declaring: Declaring;
  readonly sourcesOnly?: boolean;
}) {
  if (sourcesOnly) {
    return (
      <Box component="aside">
        {wentIn.map((each) => {
          const fields = inputFields(each, takes, declaring);
          const label = labelAt(fields, each.input);
          return (
            <Box key={each.sourcePartId ?? each.input}>
              <Typography component="div" variant="body2" sx={QUIET_SX}>
                {each.from === undefined
                  ? label
                  : say("step.takes", {
                      input: label,
                      from: fromSaid(each.from, declaring),
                    })}
              </Typography>
              <CompositeSources
                each={each}
                takes={takes}
                declaring={declaring}
              />
            </Box>
          );
        })}
      </Box>
    );
  }
  return (
    <Fields>
      {wentIn.map((each) => {
        const fields = inputFields(each, takes, declaring);
        const label = labelAt(fields, each.input);
        return (
          <Field key={each.sourcePartId ?? each.input} label={label}>
            {each.from === undefined ? null : (
              <Typography variant="body2" sx={QUIET_SX}>
                {fromSaid(each.from, declaring)}
              </Typography>
            )}
            <FilledValue
              field={fieldAt(fields, each.input)}
              label={label}
              shown={each}
              shut={false}
            />
            <CompositeSources each={each} takes={takes} declaring={declaring} />
          </Field>
        );
      })}
    </Fields>
  );
}

export function CompositeSources({
  each,
  takes,
  declaring,
}: {
  readonly each: WentIn;
  readonly takes: readonly ReadField[] | undefined;
  readonly declaring: Declaring;
}) {
  if (each.sources === undefined) {
    return null;
  }
  const fields = fieldAt(
    inputFields(each, takes, declaring),
    each.input,
  )?.fields;
  return (
    <>
      <Typography variant="body2" sx={QUIET_SX}>
        {say("step.putTogetherFromThese")}
      </Typography>
      <Box component="ul">
        {each.sources.map(({ targetPath, from }) => (
          <li key={targetPath}>
            {say("step.takes", {
              input: labelAt(fields, targetPath),
              from: fromSaid(from, declaring),
            })}
          </li>
        ))}
      </Box>
    </>
  );
}
