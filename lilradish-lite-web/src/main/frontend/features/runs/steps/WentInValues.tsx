import Typography from "@mui/material/Typography";

import type { ReadField } from "../../../api/filling";
import type { WentIn } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { Field, Fields } from "../../../lib/form/Fields";
import { QUIET_SX } from "../../../lib/layout/parts";
import { FilledValue } from "../fill/FilledValue";
import { fieldAt, fromSaid, labelAt, type Declaring } from "./declared";

/** Every input by its label, where it came from, and what it was given, in full. */
export function WentInValues({
  wentIn,
  takes,
  declaring,
}: {
  readonly wentIn: readonly WentIn[];
  readonly takes: readonly ReadField[] | undefined;
  readonly declaring: Declaring;
}) {
  return (
    <Fields>
      {wentIn.map((each) => (
        <Field key={each.input} label={labelAt(takes, each.input)}>
          <Typography variant="body2" sx={QUIET_SX}>
            {fromSaid(each.from, declaring)}
          </Typography>
          <FilledValue
            field={fieldAt(takes, each.input)}
            label={labelAt(takes, each.input)}
            shown={each}
            shut={false}
          />
        </Field>
      ))}
    </Fields>
  );
}
