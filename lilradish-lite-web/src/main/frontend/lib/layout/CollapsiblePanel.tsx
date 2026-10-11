import Box from "@mui/material/Box";
import ButtonBase from "@mui/material/ButtonBase";
import IconButton from "@mui/material/IconButton";
import Tooltip from "@mui/material/Tooltip";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";

import { useKeptChoice } from "../device/useKeptChoice";
import { TOKENS } from "../theme/tokens";

/** For a while, of the page's own accord, until the reader next toggles: never kept as the device's choice. */
export type Passing = "unfolded" | "folded" | null;

const ACROSS_SX = {
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 1,
  width: "100%",
  height: 48,
  px: 2,
  boxSizing: "border-box",
  typography: "quietControl",
  textAlign: "start",
  bgcolor: "surface.card",
  border: 1,
  borderColor: "surface.cardEdge",
  borderRadius: TOKENS.shape.cardRadius,
};

const RAIL_SX = {
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: "10px",
  width: "100%",
  height: "auto",
  p: "14px 0",
  color: "ink.secondary",
  borderRadius: TOKENS.shape.cardRadius,
  "&:hover": { bgcolor: "act.hover" },
  "&.Mui-focusVisible": {
    outline: `2px solid ${TOKENS.act.main}`,
    outlineOffset: -2,
  },
  "& svg:first-of-type": { width: 16, height: 16 },
  "& svg:last-of-type": { width: 20, height: 20, color: "ink.strong" },
};

export function CollapsiblePanel({
  title,
  heading,
  keptAs,
  defaultExpanded,
  across = false,
  passing = null,
  onPass,
  controls,
  disabled = false,
  children,
}: {
  /** The panel the strip across opens, where it is drawn elsewhere than inside this. */
  readonly controls?: string;
  readonly disabled?: boolean;
  readonly title: string;
  readonly heading?: ReactNode;
  readonly keptAs: string;
  /** First choice only; a remembered device choice still wins. */
  readonly defaultExpanded: boolean;
  readonly across?: boolean;
  /** Held by the caller, so it outlasts this panel being drawn anew elsewhere. */
  readonly passing?: Passing;
  readonly onPass?: (passing: Passing) => void;
  readonly children:
    ReactNode | ((expanded: boolean, toggle: () => void) => ReactNode);
}) {
  const [choice, keep] = useKeptChoice(
    keptAs,
    defaultExpanded ? ["expanded", "collapsed"] : ["collapsed", "expanded"],
  );
  const expanded =
    passing === "unfolded" || (passing !== "folded" && choice === "expanded");
  const id = useId();
  const control = useRef<HTMLButtonElement>(null);
  const [focusRequest, setFocusRequest] = useState(0);
  const toggle = () => {
    setFocusRequest((last) => last + 1);
    onPass?.(null);
    keep(expanded ? "collapsed" : "expanded");
  };
  useEffect(() => {
    if (focusRequest > 0) control.current?.focus();
  }, [focusRequest]);
  const folding = across ? (
    <ButtonBase
      ref={control}
      aria-expanded={expanded}
      aria-controls={controls ?? id}
      disabled={disabled}
      onClick={toggle}
      sx={ACROSS_SX}
    >
      <bdi>{title}</bdi>
      <ExpandMoreIcon aria-hidden />
    </ButtonBase>
  ) : (
    <Tooltip title={title} describeChild>
      <IconButton
        ref={control}
        aria-label={title}
        aria-expanded={expanded}
        aria-controls={id}
        disabled={disabled}
        onClick={toggle}
        sx={expanded ? undefined : RAIL_SX}
      >
        {expanded ? (
          <ChevronRightIcon />
        ) : (
          <>
            <ChevronLeftIcon aria-hidden />
            <Box
              component="span"
              aria-hidden
              sx={{ width: 32, height: "1px", bgcolor: "surface.rule" }}
            />
            <InfoOutlinedIcon aria-hidden />
          </>
        )}
      </IconButton>
    </Tooltip>
  );
  return (
    <Box
      data-panel-collapsed={!expanded}
      sx={{
        minWidth: 0,
        width: expanded || across ? "100%" : TOKENS.sidePanel.rail,
      }}
    >
      {heading === undefined || across ? (
        folding
      ) : (
        <Box
          component="header"
          sx={{ display: "flex", alignItems: "flex-start", gap: 1 }}
        >
          <Box
            hidden={!expanded}
            sx={{ flex: "1 1 auto", minWidth: 0, overflowWrap: "anywhere" }}
          >
            {heading}
          </Box>
          {folding}
        </Box>
      )}
      <div id={id} hidden={!expanded}>
        {typeof children === "function" ? children(expanded, toggle) : children}
      </div>
    </Box>
  );
}
