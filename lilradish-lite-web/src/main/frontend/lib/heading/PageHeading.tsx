import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useEffect, useRef, type ReactNode } from "react";

// A `div`: a `section` standing directly in the page's container is drawn as
// a card by the theme.
const ROOT_SX = {
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  // Where the actions wrap onto a line of their own, they end it still.
  justifyContent: "flex-end",
  gap: 2,
  mb: 2,
};

// Takes the room the actions leave, and gives it up rather than push them off
// the screen: a long name breaks anywhere before it overflows.
const TITLE_SX = { flexGrow: 1, minWidth: 0, overflowWrap: "anywhere" };

/**
 * The page's own name, and whatever it offers to do, level with it on the
 * trailing side. The page's one first-level heading, or, for a page drawn
 * within another's, a second-level one under that page's own.
 *
 * `title` is shown as given, so a name in it arrives isolated by
 * `isolatedInText`. What the reader may not do is not passed in, rather than
 * drawn and refused.
 */
export function PageHeading({
  title,
  actions,
  focusOnArrival,
  level = 1,
}: {
  readonly title: string;
  readonly actions?: ReactNode;
  readonly level?: 1 | 2;
  /**
   * Where the reader arrived by an act on another page, whose control is
   * gone: the keyboard starts again here rather than at the top.
   */
  readonly focusOnArrival?: boolean;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const arriving = useRef(focusOnArrival === true);
  useEffect(() => {
    if (arriving.current) {
      heading.current?.focus();
    }
  }, []);

  return (
    <Box sx={ROOT_SX}>
      <Typography
        ref={heading}
        tabIndex={focusOnArrival === true ? -1 : undefined}
        variant="h5"
        component={level === 1 ? "h1" : "h2"}
        sx={TITLE_SX}
      >
        {title}
      </Typography>
      {actions}
    </Box>
  );
}
