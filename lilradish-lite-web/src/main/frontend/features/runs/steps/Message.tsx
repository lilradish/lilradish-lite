import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { Theme } from "@mui/material/styles";
import { useId, type ReactNode } from "react";

import type { Cost } from "../../../api/groups/{groupId}/runs/{runId}/steps";
import {
  BLOCK_SX,
  HEADING_SX,
  LEADING_RULE_WIDTH,
  TITLE_LINE_SX,
} from "../../../lib/layout/parts";
import { isKnownKind, StepKindTag } from "../../library/StepKindTag";
import { CostDrawn } from "../spend";
import { stepStateColour } from "./stepStates";

const HEAD_SX = { ...TITLE_LINE_SX, ...HEADING_SX };

const COST_SX = { ml: "auto" };

/** As a chat is read: what the reader made stands apart on the trailing side, narrower than the rest. */
const BY_THE_READER_SX = {
  marginInlineStart: "auto",
  maxWidth: "85%",
  bgcolor: "part.mine",
};

type Ending = "done" | "failed" | "stopped";

const ENDING_EDGE_WIDTH = 2;

/**
 * One message of a conversation, named by its title; one found by an anchor may take the keyboard. Where it stands
 * and how it is edged never say alone whose it is or how the run ended: its words say both.
 */
export function Message({
  anchor,
  title,
  titleId: givenTitleId,
  kind,
  cost,
  byTheReader,
  ending,
  children,
}: {
  readonly anchor?: string;
  /** Shown as given, so a name in it arrives isolated. */
  readonly title: string;
  /** Where the message's own controls are described by its title. */
  readonly titleId?: string;
  /** A step's: said beside the title and no part of its name, and where known ruled down the leading edge in its colour. */
  readonly kind?: string;
  /** A step's, on the trailing side of the head. */
  readonly cost?: Cost;
  /** Whether the one reading made it, as told by who they are and never by a name. */
  readonly byTheReader?: boolean;
  /** The run's last message: how the run ended. */
  readonly ending?: Ending;
  readonly children: ReactNode;
}) {
  const ownTitleId = useId();
  const titleId = givenTitleId ?? ownTitleId;
  return (
    <Box
      component="li"
      id={anchor}
      tabIndex={anchor === undefined ? undefined : -1}
      aria-labelledby={titleId}
      sx={{
        ...framed(kind, ending),
        ...(byTheReader === true ? BY_THE_READER_SX : null),
        scrollMarginTop: anchor === undefined ? undefined : "64px",
      }}
    >
      <Box sx={HEAD_SX}>
        <Typography id={titleId} variant="h2">
          {title}
        </Typography>
        {kind === undefined ? null : <StepKindTag kind={kind} />}
        {cost === undefined ? null : (
          <Box sx={COST_SX}>
            <CostDrawn cost={cost} />
          </Box>
        )}
      </Box>
      {children}
    </Box>
  );
}

// The frame's `border` shorthand comes first in each: coming after an edge of its own, it would set that edge back.
function framed(kind: string | undefined, ending: Ending | undefined) {
  if (ending !== undefined) {
    return {
      ...BLOCK_SX,
      borderWidth: ENDING_EDGE_WIDTH,
      borderColor:
        ending === "stopped"
          ? "text.secondary"
          : (given: Theme) => stepStateColour(ending, given),
    };
  }
  return kind === undefined || !isKnownKind(kind)
    ? BLOCK_SX
    : {
        ...BLOCK_SX,
        borderLeftWidth: LEADING_RULE_WIDTH,
        borderLeftColor: (given: Theme) => given.palette.stepKind[kind].main,
      };
}
