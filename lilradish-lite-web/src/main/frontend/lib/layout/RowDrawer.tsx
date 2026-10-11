import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Slide from "@mui/material/Slide";
import FocusTrap from "@mui/material/Unstable_TrapFocus";
import type { Theme } from "@mui/material/styles";
import {
  createContext,
  useCallback,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

import { say } from "../../i18n/lib";
import { StatusLine, type Line } from "../notice/StatusLine";
import { TOKENS } from "../theme/tokens";
import { cardPadding } from "./parts";

/** The room a page's own scrollbar may take, which a modal over the page can give back. */
export const SCROLLBAR = 24;

const ROW_DRAWER_WIDTH = Number.parseFloat(TOKENS.drawer.row);

const PAGE_SHOWING = Number.parseFloat(TOKENS.drawer.pageShowing);

const FILLS = `@media (width < ${TOKENS.drawer.fillsBelow})`;

const SHORT = `@media (height < ${TOKENS.drawer.scrollsAsOneBelow})`;

/** A drawer opened inside a row's drawer, standing at the trailing edge. */
interface StandingOn {
  readonly width: number;
  readonly shut: () => void;
}

/** Lent by a row's drawer to a drawer opened inside it. */
interface RowDrawer {
  readonly slot: HTMLDivElement | null;
  /** What the row's drawer shows, which only what is opened from inside it is about. */
  readonly sheet: HTMLDivElement | null;
  readonly stand: (on: StandingOn) => () => void;
  /** Whether the drawer standing on it lies over it, where there is no room beside. */
  readonly covers: boolean;
}

export const RowDrawerContext = createContext<RowDrawer | null>(null);

// On a screen too short for both, head and foot scroll away with the content.
const DRAWN_OVER_SX = {
  boxSizing: "border-box",
  display: "flex",
  flexDirection: "column",
  minHeight: 0,
  overflow: "hidden",
  bgcolor: "surface.card",
  borderLeft: 1,
  borderColor: "surface.cardEdge",
  boxShadow: (given: Theme) => given.palette.elevation.panel,
  [SHORT]: { display: "block", overflowY: "auto", overflowX: "hidden" },
};

const SHEET_SX = {
  ...DRAWN_OVER_SX,
  height: "100%",
  "@media (prefers-reduced-motion: reduce)": { transition: "none" },
};

const SHEET_ASIDE_SX = {
  transition: (given: Theme) =>
    given.transitions.create("translate", {
      duration: given.transitions.duration.enteringScreen,
      easing: given.transitions.easing.easeOut,
    }),
};

const SHEET_BACK_SX = {
  transition: (given: Theme) =>
    given.transitions.create("translate", {
      duration: given.transitions.duration.leavingScreen,
      easing: given.transitions.easing.sharp,
    }),
};

const HEADER_SX = {
  display: "flex",
  alignItems: "flex-start",
  justifyContent: "space-between",
  gap: 1,
  flexShrink: 0,
  p: TOKENS.drawer.head,
  borderBottom: 1,
  borderColor: "surface.rule",
};

const BODY_SX = {
  ...cardPadding(TOKENS.drawer.body),
  flex: "1 1 auto",
  minHeight: 0,
  overflowY: "auto",
  overflowX: "hidden",
  [SHORT]: { overflowY: "visible", overflowX: "clip" },
};

const FOOT_SX = {
  flexShrink: 0,
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: TOKENS.drawer.footGap,
  p: TOKENS.drawer.foot,
  borderTop: 1,
  borderColor: "surface.rule",
  bgcolor: "surface.card",
};

/** The paper only places a drawer: what is seen is its sheet, which can move aside inside the paper's trap. */
export const PAPER_SX = {
  maxWidth: "100%",
  boxSizing: "border-box",
  bgcolor: "transparent",
  boxShadow: "none",
  overflowY: "visible",
  overflowX: "clip",
};

export const ROW_PAPER_SX = {
  ...PAPER_SX,
  // A sheet moves outside this paper when another drawer stands beside it.
  overflowX: "visible",
  width: ROW_DRAWER_WIDTH,
  [FILLS]: { width: "100%" },
};

const STANDING_SLOT_SX = { display: "contents" };

const TRAPPED_SX = {
  display: "flex",
  flexDirection: "column",
  flex: "1 1 auto",
  minHeight: 0,
  overflow: "hidden",
  [SHORT]: { display: "block", overflow: "visible" },
};

/** A drawer's heading line with its close control over a rule, what it says and holds, and its actions held at its foot. */
export function DrawerSheet({
  heading,
  onShut,
  said,
  above,
  foot,
  children,
}: {
  readonly heading: ReactNode;
  readonly onShut: () => void;
  readonly said: Line | null;
  /** Said first, scrolling with what it holds. */
  readonly above?: ReactNode;
  readonly foot?: ReactNode;
  readonly children: ReactNode;
}) {
  return (
    <>
      <Box sx={HEADER_SX}>
        {heading}
        <IconButton
          color="inherit"
          aria-label={say("panel.close")}
          onClick={onShut}
        >
          <CloseIcon />
        </IconButton>
      </Box>
      <Box sx={BODY_SX}>
        <StatusLine said={said} />
        {above}
        {children}
      </Box>
      {foot === undefined ? null : <Box sx={FOOT_SX}>{foot}</Box>}
    </>
  );
}

function useViewportWiderThan(measured: boolean, least: number | null) {
  const [wider, setWider] = useState(false);
  useLayoutEffect(() => {
    if (!measured || least === null) return;
    const judge = () => setWider(window.innerWidth >= least);
    judge();
    window.addEventListener("resize", judge);
    return () => window.removeEventListener("resize", judge);
  }, [measured, least]);
  return measured && least !== null && wider;
}

/**
 * What a row's drawer lends a drawer opened in it, and how its sheet stands: aside, the page still showing beside
 * both, where there is room for that, and beneath otherwise, out of the keyboard's reach.
 */
export function useRowDrawer(over: boolean, present: boolean) {
  const [standingOn, setStandingOn] = useState<StandingOn | null>(null);
  const [slot, setSlot] = useState<HTMLDivElement | null>(null);
  const [sheet, setSheet] = useState<HTMLDivElement | null>(null);
  const stand = useCallback((on: StandingOn) => {
    setStandingOn(on);
    return () => setStandingOn((now) => (now === on ? null : now));
  }, []);
  const aside = useViewportWiderThan(
    over && present,
    standingOn === null
      ? null
      : ROW_DRAWER_WIDTH + standingOn.width + PAGE_SHOWING,
  );
  const covered = standingOn !== null && !aside;
  const lent = useMemo(
    () => (over ? { slot, sheet, stand, covers: covered } : null),
    [over, slot, sheet, stand, covered],
  );
  const asideBy = aside && standingOn !== null ? standingOn.width : 0;
  const sheetSx = useMemo(
    () => ({
      ...SHEET_SX,
      ...(asideBy > 0 ? SHEET_ASIDE_SX : SHEET_BACK_SX),
      translate: `${-asideBy}px 0`,
    }),
    [asideBy],
  );
  const slotted = over ? <Box ref={setSlot} sx={STANDING_SLOT_SX} /> : null;
  return {
    lent,
    standingOn,
    covered,
    sheetSx,
    setSheet,
    slotted,
  };
}

/** A drawer opened in a row's drawer, standing on it at the trailing edge, never modal. */
export function StandingDrawer({
  slot,
  covers,
  open,
  onExited,
  width,
  headingId,
  heading,
  onShut,
  said,
  attending,
  above,
  foot,
  children,
}: {
  readonly slot: HTMLDivElement;
  /** Over the row's drawer, which is then out of reach, so the keyboard goes round this one alone. */
  readonly covers: boolean;
  readonly open: boolean;
  readonly onExited: () => void;
  readonly width: number;
  readonly headingId: string;
  readonly heading: ReactNode;
  readonly onShut: () => void;
  readonly said: Line | null;
  /** Drawn first, outside the sheet's own parts. */
  readonly attending: ReactNode;
  readonly above?: ReactNode;
  readonly foot?: ReactNode;
  readonly children: ReactNode;
}) {
  const sx = useMemo(
    () => ({
      ...DRAWN_OVER_SX,
      position: "absolute",
      top: 0,
      bottom: 0,
      right: 0,
      width: covers ? "100%" : width,
      [FILLS]: { width: "100%" },
    }),
    [width, covers],
  );
  return createPortal(
    <Slide direction="left" in={open} appear onExited={onExited}>
      <Box role="dialog" aria-modal="false" aria-labelledby={headingId} sx={sx}>
        {attending}
        {/* The row drawer's own trap would wrap round to its covered, inert
            controls, which cannot take the keyboard. */}
        <FocusTrap
          open={covers}
          disableAutoFocus
          disableEnforceFocus
          disableRestoreFocus
        >
          <Box tabIndex={-1} sx={TRAPPED_SX}>
            <DrawerSheet
              heading={heading}
              onShut={onShut}
              said={said}
              above={above}
              foot={foot}
            >
              {children}
            </DrawerSheet>
          </Box>
        </FocusTrap>
      </Box>
    </Slide>,
    slot,
  );
}

/** Puts the keyboard on the heading of the drawer `inside` is in, which names that drawer. */
export function focusDrawerHeading(inside: Element | null) {
  const named = inside
    ?.closest('[role="dialog"]')
    ?.getAttribute("aria-labelledby");
  if (named !== null && named !== undefined)
    document.getElementById(named)?.focus();
}
