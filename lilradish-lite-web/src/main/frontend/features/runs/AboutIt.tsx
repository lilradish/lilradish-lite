import Box from "@mui/material/Box";
import type { ReactNode } from "react";

import { say } from "../../i18n/app";
import { Fields } from "../../lib/form/Fields";

const ACROSS_SX = { gridColumn: "1 / -1" };

const ACTS_SX = { mt: "16px", "& > :last-child": { mb: 0 } };

/**
 * What is about a run, or one of its steps, under the heading of the side panel it stands in, which names it a region
 * rather than a complementary landmark, as it holds what may be done to the run and sits within the page's main
 * content. Its facts are one list led by where it is, each label beside its value, and end with what may be done to it.
 */
export function AboutIt({
  status,
  progress,
  facts,
  acts,
  children,
}: {
  /** Its badge first, and whatever more is said of where it is under it. */
  readonly status: ReactNode;
  /** How far it has got, under where it is and across both columns. */
  readonly progress?: ReactNode;
  /** Each a `Field`. */
  readonly facts: ReactNode;
  /** What may be done to it, at the foot of its facts. */
  readonly acts?: ReactNode;
  /** The card's further parts, each ruled off from the one before. */
  readonly children?: ReactNode;
}) {
  return (
    <>
      <Fields inPanel>
        <dt>{say("run.where")}</dt>
        <dd>{status}</dd>
        {progress === undefined ? null : (
          <Box component="dd" sx={ACROSS_SX}>
            {progress}
          </Box>
        )}
        {facts}
      </Fields>
      {acts === undefined || acts === null ? null : (
        <Box sx={ACTS_SX}>{acts}</Box>
      )}
      {children}
    </>
  );
}
