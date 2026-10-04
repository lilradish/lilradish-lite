import Typography from "@mui/material/Typography";

import type { ReadField } from "../../../api/filling";
import type { GaveBack } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import { say } from "../../../i18n/app";
import { Field, Fields } from "../../../lib/form/Fields";
import { FilledValue } from "../fill/FilledValue";
import { fieldAt, labelAt } from "./declared";

export function GaveBackPart({
  gaveBack,
  gives,
}: {
  readonly gaveBack: GaveBack;
  readonly gives: readonly ReadField[] | undefined;
}) {
  if (gaveBack.declares === "nothing") {
    return <Typography variant="body2">{say("step.givesNothing")}</Typography>;
  }
  if (gaveBack.declares !== "values") {
    return <Typography variant="body2">{say("step.unknown")}</Typography>;
  }
  const standing = gaveBack.standing ?? [];
  if (standing.length === 0) {
    return (
      <Typography variant="body2">{say("step.nothingStandsYet")}</Typography>
    );
  }
  return (
    <Fields>
      {standing.map((each) => (
        <Field key={each.field} label={labelAt(gives, each.field)}>
          <FilledValue
            field={fieldAt(gives, each.field)}
            label={labelAt(gives, each.field)}
            shown={each}
            shut={false}
          />
        </Field>
      ))}
    </Fields>
  );
}
