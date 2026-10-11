import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { Theme } from "@mui/material/styles";
import { useId, type ReactNode } from "react";

import {
  BLOCK_SX,
  HEADING_SX,
  LEADING_RULE_WIDTH,
  TITLE_LINE_SX,
} from "../../../lib/layout/parts";
import {
  isKnownKind,
  stepKindTone,
  StepKindTag,
} from "../../library/StepKindTag";
import { stepStateColour } from "./stepStates";
import { ProseScope } from "../../../lib/text/ModelProse";
import { copySelection } from "../fill/ValueCard";

const HEAD_SX = { ...TITLE_LINE_SX, ...HEADING_SX };

/** Identity determines the logical side within the conversation's shared content width. */
const BY_THE_READER_SX = {
  marginInlineStart: "auto",
  bgcolor: "background.paper",
};

type Ending = "done" | "failed" | "stopped";

const ENDING_EDGE_WIDTH = 2;

/** Side and edge colour need words to convey identity and the run's ending. */
export function Message({
  anchor,
  title,
  titleId: givenTitleId,
  kind,
  byTheReader,
  ending,
  children,
  proseOpened,
  component = "li",
  conversation = true,
}: {
  readonly anchor?: string;
  /** Shown as given, so a name in it arrives isolated. */
  readonly title: string;
  readonly titleId?: string;
  readonly kind?: string;
  /** Whether the one reading made it, as told by who they are and never by a name. */
  readonly byTheReader?: boolean;
  readonly ending?: Ending;
  readonly children: ReactNode;
  readonly proseOpened?: boolean;
  readonly component?: "li" | "section";
  readonly conversation?: boolean;
}) {
  const ownTitleId = useId();
  const titleId = givenTitleId ?? ownTitleId;
  return (
    <Box
      component={component}
      id={anchor}
      tabIndex={anchor === undefined ? undefined : -1}
      aria-labelledby={titleId}
      onCopy={copySelection}
      sx={{
        ...framed(kind, ending),
        bgcolor:
          !conversation || byTheReader === true
            ? "background.paper"
            : "surface.given",
        ...(conversation && byTheReader === true ? BY_THE_READER_SX : null),
        ...(conversation
          ? {
              width: { xs: "95%", sm: "90%", md: "85%" },
              minWidth: 0,
              maxWidth: 960,
              ...(byTheReader === true
                ? { marginInlineStart: "auto" }
                : { marginInlineEnd: "auto" }),
            }
          : {}),
        scrollMarginTop: anchor === undefined ? undefined : "64px",
      }}
    >
      <Box sx={{ ...HEAD_SX, userSelect: "none" }} data-copy-control>
        <Typography id={titleId} variant="messageHeading">
          {title}
        </Typography>
        {kind === undefined ? null : <StepKindTag kind={kind} />}
      </Box>
      <ProseScope resetKey={proseOpened}>{children}</ProseScope>
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
          ? "state.stopped.text"
          : (given: Theme) => stepStateColour(ending, given),
    };
  }
  return kind === undefined || !isKnownKind(kind)
    ? BLOCK_SX
    : {
        ...BLOCK_SX,
        borderLeftWidth: LEADING_RULE_WIDTH,
        borderLeftColor: (given: Theme) =>
          given.palette.kind[stepKindTone(kind)].edge,
      };
}
