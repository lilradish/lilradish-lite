import Typography from "@mui/material/Typography";
import Box from "@mui/material/Box";

import type { ReadField } from "../../../api/filling";
import type { GaveBack } from "../../../api/stepGivenBack";
import { say } from "../../../i18n/app";
import { FilledValue } from "../fill/FilledValue";
import { fieldAt, labelAt } from "./declared";
import { ProseScope } from "../../../lib/text/ModelProse";

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
    <ProseScope>
      <Box>
        {standing.map((each) => (
          <Box key={each.field}>
            <FilledValue
              cards
              field={fieldAt(gives, each.field)}
              label={labelAt(gives, each.field)}
              shown={each}
              shut={false}
            />
          </Box>
        ))}
      </Box>
    </ProseScope>
  );
}
