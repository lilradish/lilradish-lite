import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";

import type { ReadField } from "../../../api/filling";
import type { WentIn } from "../../../api/stepSources";
import { say } from "../../../i18n/app";
import { QUIET_SX } from "../../../lib/layout/parts";
import { Field, Fields } from "../../../lib/form/Fields";
import { FilledValue } from "../fill/FilledValue";
import { fieldAt, fromSaid, labelAt, type Declaring } from "./declared";

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
        <Fields>
          {wentIn.map((each) => {
            const fields = takes;
            const label = labelAt(fields, each.input);
            return (
              <Field key={each.input} label={label}>
                {each.from === undefined
                  ? null
                  : fromSaid(each.from, declaring)}
                <CompositeSources
                  each={each}
                  takes={takes}
                  declaring={declaring}
                />
              </Field>
            );
          })}
        </Fields>
      </Box>
    );
  }
  return (
    <Box>
      {wentIn.map((each) => {
        const fields = takes;
        const label = labelAt(fields, each.input);
        return (
          <Box key={each.input}>
            {each.from === undefined ? null : (
              <Typography variant="body2" sx={QUIET_SX}>
                {fromSaid(each.from, declaring)}
              </Typography>
            )}
            <FilledValue
              cards
              field={fieldAt(fields, each.input)}
              label={label}
              shown={each}
              shut={false}
            />
            <CompositeSources each={each} takes={takes} declaring={declaring} />
          </Box>
        );
      })}
    </Box>
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
  const fields = fieldAt(takes, each.input)?.fields;
  return (
    <>
      <Typography variant="body2" sx={QUIET_SX}>
        {say("step.putTogetherFromThese")}
      </Typography>
      <Fields>
        {each.sources.map(({ targetPath, from }) => (
          <Field key={targetPath} label={labelAt(fields, targetPath)}>
            {fromSaid(from, declaring)}
          </Field>
        ))}
      </Fields>
    </>
  );
}
