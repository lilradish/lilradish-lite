import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useId, type ReactNode, type Ref } from "react";

import { DEPTH_SX, HEADING_SX, TITLE_LINE_SX } from "./parts";

/**
 * A region named by its heading, drawn at the depth its caller says it sits at, whatever its heading's level.
 * `title` is shown as given, so a name in it arrives isolated.
 */
export function Part({
  title,
  level,
  depth,
  headingId: givenHeadingId,
  headingRef,
  actions,
  ref,
  children,
}: {
  readonly title: string;
  readonly level: "h1" | "h2" | "h3" | "h4" | "h5" | "h6";
  readonly depth: 1 | 2 | 3;
  /** Where something within the part is named by its heading too. */
  readonly headingId?: string;
  /** A heading is held only to be focused, so one held takes focus from script. */
  readonly headingRef?: Ref<HTMLHeadingElement>;
  readonly actions?: ReactNode;
  readonly ref?: Ref<HTMLElement>;
  readonly children: ReactNode;
}) {
  const ownHeadingId = useId();
  const headingId = givenHeadingId ?? ownHeadingId;
  return (
    <Box
      component="section"
      ref={ref}
      aria-labelledby={headingId}
      sx={DEPTH_SX[depth]}
    >
      {actions === undefined ? (
        <Typography
          id={headingId}
          ref={headingRef}
          tabIndex={headingRef === undefined ? undefined : -1}
          variant={level}
          sx={HEADING_SX}
        >
          {title}
        </Typography>
      ) : (
        <Box
          sx={{ ...TITLE_LINE_SX, ...HEADING_SX, justifyContent: "flex-end" }}
        >
          <Typography
            id={headingId}
            ref={headingRef}
            tabIndex={headingRef === undefined ? undefined : -1}
            variant={level}
            sx={{ flexGrow: 1, minWidth: 0, overflowWrap: "anywhere" }}
          >
            {title}
          </Typography>
          {actions}
        </Box>
      )}
      {children}
    </Box>
  );
}
