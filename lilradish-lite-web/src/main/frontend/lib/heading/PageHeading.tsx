import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

import {
  INLINE_OUTSET_WIDTH,
  INLINE_TEXT_END_WIDTH,
  inlineEditWidth,
} from "../action/InlineEdit";
import { TOKENS } from "../theme/tokens";

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
 * The page's one first-level heading.
 *
 * `title` is shown as given, so a name in it arrives isolated by
 * `isolatedInText`. What the reader may not do is not passed in, rather than
 * drawn and refused.
 */
export function PageHeading({
  title,
  actions,
  besideTitle,
  titleValue,
  editingTitle,
  focusOnArrival,
}: {
  readonly title: string;
  readonly actions?: ReactNode;
  readonly besideTitle?: ReactNode;
  readonly titleValue?: ReactNode;
  readonly editingTitle?: ReactNode;
  /**
   * Where the reader arrived by an act on another page, whose control is
   * gone: the keyboard starts again here rather than at the top.
   */
  readonly focusOnArrival?: boolean;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const titleLine = useRef<HTMLDivElement>(null);
  const companion = useRef<HTMLSpanElement>(null);
  const [measure, setMeasure] = useState<number>();
  const hasCompanion = besideTitle !== undefined;
  useLayoutEffect(() => {
    const line = titleLine.current;
    const kind = companion.current;
    if (!hasCompanion || line === null || kind === null) return;
    const measureTitle = () => {
      const width = line.getBoundingClientRect().width;
      if (width === 0) return;
      const drawn = getComputedStyle(line);
      const gap =
        Number.parseFloat(drawn.columnGap) || Number.parseFloat(drawn.gap) || 0;
      const beside = width - kind.getBoundingClientRect().width - gap;
      const tail =
        2 * Number.parseFloat(TOKENS.inPlace.control) +
        Number.parseFloat(TOKENS.inPlace.controlGap) +
        Number.parseFloat(TOKENS.inPlace.textGap);
      setMeasure(beside > tail ? beside : width);
    };
    measureTitle();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measureTitle);
    observer.observe(line);
    observer.observe(kind);
    return () => observer.disconnect();
  }, [hasCompanion]);
  const arriving = useRef(focusOnArrival === true);
  useEffect(() => {
    if (arriving.current) {
      heading.current?.focus();
    }
  }, []);

  return (
    <Box sx={ROOT_SX}>
      {besideTitle === undefined &&
      editingTitle === undefined &&
      titleValue === undefined ? (
        <Typography
          ref={heading}
          tabIndex={focusOnArrival === true ? -1 : undefined}
          variant="h1"
          sx={TITLE_SX}
        >
          {titleValue ?? title}
        </Typography>
      ) : (
        <Box
          ref={titleLine}
          sx={{
            ...TITLE_SX,
            display: "flex",
            flexWrap: "wrap",
            alignItems: "last baseline",
            gap: 1,
          }}
        >
          <Box
            sx={{
              flex:
                editingTitle === undefined && hasCompanion
                  ? "0 1 auto"
                  : "1 1 auto",
              minWidth: 0,
              typography: "h1",
              maxWidth:
                editingTitle === undefined &&
                hasCompanion &&
                measure !== undefined
                  ? `${measure}px`
                  : inlineEditWidth(128),
              ...(hasCompanion && measure !== undefined
                ? {
                    "--inline-text-measure": `max(0px, calc(${measure}px - ${INLINE_TEXT_END_WIDTH}))`,
                  }
                : {}),
              ...(hasCompanion
                ? {
                    "& > h1 > button": {
                      width: "fit-content",
                      maxWidth: inlineEditWidth(
                        128,
                        measure === undefined
                          ? "100%"
                          : `calc(${measure}px + ${INLINE_OUTSET_WIDTH})`,
                      ),
                    },
                  }
                : {}),
            }}
          >
            <Typography
              ref={heading}
              tabIndex={focusOnArrival === true ? -1 : undefined}
              variant="h1"
              aria-label={title}
              sx={
                editingTitle === undefined
                  ? {
                      display: "flex",
                      minWidth: 0,
                      overflowWrap: "anywhere",
                    }
                  : {
                      position: "absolute",
                      width: "1px",
                      height: "1px",
                      p: 0,
                      m: -1,
                      overflow: "hidden",
                      clipPath: "inset(50%)",
                      whiteSpace: "nowrap",
                    }
              }
            >
              {editingTitle === undefined ? (titleValue ?? title) : title}
            </Typography>
            {editingTitle}
          </Box>
          {hasCompanion ? (
            <Box
              component="span"
              ref={companion}
              sx={{ display: "inline-flex", flex: "0 0 auto" }}
            >
              {besideTitle}
            </Box>
          ) : null}
        </Box>
      )}
      {actions}
    </Box>
  );
}
