import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import type { ReactNode } from "react";

import type { Spend } from "../../../api/groups/{groupId}/runs/{runId}";
import { say } from "../../../i18n/app";
import { Field, Fields } from "../../../lib/form/Fields";
import { ACTS_SX } from "../../../lib/layout/parts";
import { Spent } from "../spend";

/** A disclosure rather than a menu, as a menu holds nothing but its items. */
export function RunMore({
  id,
  open,
  spend,
  ceilingLabel,
  ceiling,
  renaming,
  onDetail,
}: {
  /** What More names as the part it opens and shuts. */
  readonly id: string;
  readonly open: boolean;
  readonly spend: Spend;
  readonly ceilingLabel: ReactNode;
  readonly ceiling: ReactNode;
  /** null where the reader may not rename it. */
  readonly renaming: ReactNode;
  readonly onDetail: () => void;
}) {
  return (
    <div id={id} hidden={!open}>
      {open ? (
        <>
          <Fields>
            <Field label={say("run.cost")}>
              <Spent spend={spend} />
            </Field>
            <Field label={ceilingLabel}>{ceiling}</Field>
          </Fields>
          <Box sx={ACTS_SX}>
            {renaming}
            <Button onClick={onDetail}>{say("step.showInDetail")}</Button>
          </Box>
        </>
      ) : null}
    </div>
  );
}
