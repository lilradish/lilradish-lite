import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import Typography from "@mui/material/Typography";
import type { Theme } from "@mui/material/styles";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useEffectEvent,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from "react";
import { createPortal } from "react-dom";

import { say } from "../../i18n/lib";
import { TOKENS } from "../theme/tokens";
import {
  cardPadding,
  DEPTH_SX,
  HEADING_SX,
  RULED_PART_SX,
  TITLE_LINE_SX,
} from "./parts";
import { CollapsiblePanel, type Passing } from "./CollapsiblePanel";
import {
  DrawerSheet,
  PAPER_SX,
  ROW_PAPER_SX,
  RowDrawerContext,
  SCROLLBAR,
  StandingDrawer,
  useRowDrawer,
} from "./RowDrawer";
import type { Line } from "../notice/StatusLine";

const PANEL_WIDTH = Number.parseFloat(TOKENS.sidePanel.width);
const GAP = Number.parseFloat(TOKENS.sidePanel.gap);
const BESIDE_FROM = Number.parseFloat(TOKENS.sidePanel.besideFrom);

/** The width of the box a panel stands beside its list from; a caller may need more room for the list. */
export function panelBesideAt(
  leastListWidth: number,
  width = PANEL_WIDTH,
): number {
  return Math.max(BESIDE_FROM, leastListWidth + GAP + width);
}

function usePlacement(
  width: number,
  leastListWidth: number,
  scrollbarAllowance: boolean,
  measured: boolean,
) {
  const frameRef = useRef<HTMLDivElement>(null);
  const [beside, setBeside] = useState<boolean | null>(null);
  const [firstBeside, setFirstBeside] = useState<boolean | null>(null);
  const measureNow = useRef(() => {});
  useLayoutEffect(() => {
    const frame = frameRef.current;
    if (frame === null || !measured) return;
    // Only a modal covering the page can remove its scrollbar. Keep that
    // allowance while entering the beside form so it cannot oscillate.
    const besideFrom = (allowing: boolean) =>
      panelBesideAt(leastListWidth, width + (allowing ? SCROLLBAR : 0));
    const judge = (frameWidth: number) => {
      setFirstBeside(
        (first) => first ?? frameWidth >= besideFrom(scrollbarAllowance),
      );
      setBeside(
        (was) => frameWidth >= besideFrom(scrollbarAllowance && was !== true),
      );
    };
    measureNow.current = () => judge(frame.getBoundingClientRect().width);
    measureNow.current();
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) judge(entry.borderBoxSize[0].inlineSize);
    });
    observer.observe(frame);
    return () => observer.disconnect();
  }, [width, leastListWidth, scrollbarAllowance, measured]);
  const remeasure = useCallback(() => measureNow.current(), []);
  return { frameRef, beside, firstBeside, remeasure };
}

// Both compared by identity: a new one is a new request.
interface Reveal {
  readonly focus?: () => void;
}

interface Insist {
  readonly focus: () => void;
}

const FRAME_SX = { display: "flex", alignItems: "flex-start", gap: `${GAP}px` };

// What a staying panel holds may ask, by a container query, whether the panel
// stands beside the list.
const STAYING_FRAME_SX = { ...FRAME_SX, containerType: "inline-size" };

// The one scroll inside: nothing it holds may scroll or stick on its own.
const STICKY_SX = {
  position: "sticky",
  top: TOKENS.stickyPanel.top,
  maxHeight: `calc(100dvh - ${Number.parseFloat(TOKENS.stickyPanel.top) + Number.parseFloat(TOKENS.stickyPanel.bottom)}px)`,
  overflowY: "auto",
  overflowX: "hidden",
  [`@media (height < ${TOKENS.drawer.scrollsAsOneBelow})`]: {
    position: "static",
    maxHeight: "none",
    overflowY: "visible",
    overflowX: "clip",
  },
};

const LIST_SX = { flexGrow: 1, minWidth: 0 };

// Ignore inserted style nodes without treating each tag type as a new first child.
const STAYING_LIST_SX = {
  ...LIST_SX,
  "& > :not(style):not(:not(style) ~ *)": { mt: 0 },
};

const ACROSS_SX = { mb: `${GAP}px` };

// Beside the list as over it, a staying panel's first part starts this far
// under its heading.
const STAYING_HEADING_GAP = "16px";

const STAYING_HEADING_SX = {
  ...HEADING_SX,
  mb: STAYING_HEADING_GAP,
};

const BESIDE_HEADING_LINE_SX = { ...TITLE_LINE_SX, ...HEADING_SX };

const STAYING_HEADING_LINE_SX = {
  ...BESIDE_HEADING_LINE_SX,
  mb: STAYING_HEADING_GAP,
};

const BESIDE_SX = {
  ...DEPTH_SX[1],
  ...cardPadding(TOKENS.space.panel),
  boxSizing: "border-box",
  flexShrink: 0,
  "&:has([data-panel-collapsed=true])": {
    width: TOKENS.sidePanel.rail,
    p: 0,
  },
};

const STAYING_SX = { ...BESIDE_SX, position: "relative" };

// A ruled first part supplies its own space below the whole title row.
const ROW_HEADING_GAP_SX = {
  "& > [data-panel-collapsed=false] > header": {
    ...HEADING_SX,
    "&:has(+ div > [data-panel-content]:not(:not(style) ~ *) > div > [data-panel-part]:not(:not(style) ~ *))":
      {
        mb: 0,
      },
  },
};

/** A part of a panel's one card after its first, ruled off across whichever card it is drawn in. */
export const PANEL_PART_SX = {
  ...RULED_PART_SX,
  "& > section": { mt: 0 },
  "& > :last-child": { mb: 0 },
};

/**
 * Lent by a panel that stays beside its list, to a drawer opened inside that list, which is told to measure again
 * once the panel has folded.
 */
const RoomContext = createContext<((folded: () => void) => () => void) | null>(
  null,
);

const OVER_SX = {
  // A covering panel is modal, so it takes the modal layer and not the
  // drawer's: nothing outside it may stand over its heading or close control.
  zIndex: (given: Theme) => given.zIndex.modal,
  "& .MuiBackdrop-root": { bgcolor: "elevation.scrim" },
};

const ROW_OVER_SX = { ...OVER_SX, "& .MuiDrawer-paper": ROW_PAPER_SX };

// A drawer that shuts beside its list is inset as it is over the page.
const BESIDE_DRAWER_SX = { ...BESIDE_SX, ...cardPadding(TOKENS.drawer.body) };

const FOOTED_BESIDE_SX = {
  p: 0,
  maxHeight: STICKY_SX.maxHeight,
  overflow: "hidden",
  "& > [data-panel-collapsed=false]": {
    display: "flex",
    flexDirection: "column",
    maxHeight: "inherit",
    "& > header": { flexShrink: 0, p: TOKENS.drawer.head },
    "& > div": {
      display: "flex",
      flexDirection: "column",
      minHeight: 0,
      flex: "1 1 auto",
    },
  },
  [`@media (height < ${TOKENS.drawer.scrollsAsOneBelow})`]: {
    maxHeight: "none",
    overflowY: "visible",
    overflowX: "clip",
  },
};

const BESIDE_BODY_SX = {
  ...cardPadding(TOKENS.drawer.body),
  minHeight: 0,
  flex: "1 1 auto",
  overflowY: "auto",
  overflowX: "hidden",
  [`@media (height < ${TOKENS.drawer.scrollsAsOneBelow})`]: {
    overflowY: "visible",
    overflowX: "clip",
  },
};

const BESIDE_FOOT_SX = {
  flexShrink: 0,
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: TOKENS.drawer.footGap,
  p: TOKENS.drawer.foot,
  borderTop: 1,
  borderColor: "surface.rule",
};

const ROW_DRAWER_TITLE_SX = { typography: "stepTitle" };

/** The drawer hands nothing back itself; where the keyboard goes is decided here. */
const OVER_MODAL_PROPS = { disableRestoreFocus: true };

// The container determines placement, since the navigation can change its room.
// Moving one portal host preserves the editor and its focused control.
/** A panel beside what a page is about: one that stays, or one about a picked row that shutting lets go. */
export function SidePanelHost({
  open,
  reveal,
  onClose,
  title,
  visibleTitle,
  headingLevel = "h2",
  width = PANEL_WIDTH,
  leastListWidth = 560,
  besideHeading,
  list,
  listLabel,
  listRole = "region",
  keptAs = "lilradish.panel.row",
  saidOver,
  insist,
  closeBeside = false,
  asksRoom = false,
  sticky = false,
  over = false,
  foot,
  above,
  children,
}: {
  /** Said first in a drawer over the page, scrolling with what it holds. */
  readonly above?: ReactNode;
  /** Held at the foot of the drawer while what is above it scrolls, for what the page beneath cannot be reached for. */
  readonly foot?: ReactNode;
  readonly open: boolean;
  /** Folds the panel that stays beside the list it is drawn in, while it is open. */
  readonly asksRoom?: boolean;
  /**
   * Over the page at every width, for a row of a page with a panel of its own beside it; a drawer opened inside it
   * stands at the trailing edge.
   */
  readonly over?: boolean;
  /** Beside, kept in view as the page scrolls, as tall as it holds up to the screen's height. */
  readonly sticky?: boolean;
  /** Close beside the list too, for a panel whose shutting keeps what it holds. */
  readonly closeBeside?: boolean;
  /**
   * Each new one unfolds the panel, without keeping that as the device's
   * choice, and then puts the keyboard where it says.
   */
  readonly reveal?: Reveal;
  /**
   * A new message unfolds a folded panel once and puts the keyboard on it,
   * without changing the device's stored choice.
   */
  readonly insist?: Insist | null;
  /**
   * Without it the panel stays: shutting it where it covers the page folds it
   * to its strip, and a drawer opened in its list has it folded meanwhile.
   */
  readonly onClose?: () => void;
  readonly title: string;
  /** The same title drawn as nodes, with names isolated by the caller. */
  readonly visibleTitle?: ReactNode;
  /** One below the heading the list sits under. */
  readonly headingLevel?: "h2" | "h3" | "h4";
  /** In pixels, for content a panel of the usual width would crowd; the room it needs beside the list grows with it. */
  readonly width?: number;
  /** The width, in pixels, the caller's list needs beside the panel. */
  readonly leastListWidth?: number;
  /** Drawn on the heading's line, and no part of its name. */
  readonly besideHeading?: ReactNode;
  readonly list: ReactNode;
  /**
   * A line of the page's said inside the panel while it covers the page, whose
   * own line is hidden then and never heard; beside, the page's is heard.
   */
  readonly saidOver?: Line | null;
  /**
   * Names the list's own box, which is where the keyboard is put down once a
   * covering panel goes; a panel that stays only folds, so needs none.
   */
  readonly listLabel?: string;
  /** Use `group` when the list already owns a named region, so it keeps only that region. */
  readonly listRole?: "region" | "group";
  /** Distinct persistent panels must not overwrite each other's device choice. */
  readonly keptAs?: string;
  readonly children: ReactNode;
}) {
  const headingId = useId();
  const paperId = useId();
  const revealedRef = useRef<Reveal | undefined>(undefined);
  const unfoldedForRef = useRef<Insist | null>(null);
  const heededRef = useRef<Insist | null>(null);
  useLayoutEffect(() => {
    if (!open) revealedRef.current = undefined;
  }, [open]);
  const stays = onClose === undefined;
  const rowDrawer = useContext(RowDrawerContext);
  const standing = rowDrawer !== null && !over && !stays;
  const placed = usePlacement(width, leastListWidth, true, !over && !standing);
  const { frameRef, firstBeside } = placed;
  const askRoom = useContext(RoomContext);
  const [present, setPresent] = useState(open);
  const asking =
    (asksRoom || over) && !standing && (open || present) && askRoom !== null;
  // Neither form is drawn while the panel lending the room folds, so the
  // covering one is not put in only to be replaced once there is room.
  const [measuredWithRoom, setMeasuredWithRoom] = useState(false);
  if (!asking && measuredWithRoom) {
    setMeasuredWithRoom(false);
  }
  const beside =
    over || standing
      ? false
      : asking && !measuredWithRoom
        ? null
        : placed.beside;
  // Drawn from opening until whichever form holds it has gone, which for the
  // covering one is once it has finished leaving.
  if (open && !present) {
    setPresent(true);
  }
  if (!open && present && beside === true) {
    setPresent(false);
  }
  const row = useRowDrawer(over, present);
  const standingOn = row.standingOn;
  // Moving the portal between forms must not choose a new default and hide
  // the control that had focus. Stored choices still override this first one.
  const listRef = useRef<HTMLDivElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  // Unknown until measured, before the first paint; neither form is drawn
  // until then, so neither is put in only to be replaced.
  const [host] = useState(() => (over ? null : document.createElement("div")));

  // Read only once the panel has shut.
  const opener = useRef<HTMLElement | null>(null);
  const shutByReader = useRef(false);
  // One standing on a row's drawer goes back only to something in that
  // drawer, or else to the control in its list for what it holds.
  const openerNow = useEffectEvent(() => {
    const focused = document.activeElement;
    const usable = focused instanceof HTMLElement && focused !== document.body;
    if (!standing) return usable ? focused : null;
    return usable && rowDrawer.sheet?.contains(focused) === true
      ? focused
      : controlOf(listRef.current, host);
  });
  useLayoutEffect(() => {
    if (open) {
      opener.current = openerNow();
      shutByReader.current = false;
    }
  }, [open]);
  const shut = () => {
    shutByReader.current = true;
    onClose?.();
  };
  const latestClose = useRef(onClose);
  useLayoutEffect(() => {
    latestClose.current = onClose;
  });
  useLayoutEffect(
    () =>
      standing && open
        ? rowDrawer.stand({
            width,
            shut: () => {
              shutByReader.current = true;
              latestClose.current?.();
            },
          })
        : undefined,
    [standing, open, rowDrawer, width],
  );

  const { remeasure } = placed;
  useLayoutEffect(
    () =>
      asking
        ? askRoom(() => {
            remeasure();
            setMeasuredWithRoom(true);
          })
        : undefined,
    [asking, askRoom, remeasure],
  );
  const [askers, setAskers] = useState(0);
  const roomAskers = useRef(new Set<() => void>());
  const lendRoom = useCallback((folded: () => void) => {
    roomAskers.current.add(folded);
    setAskers((now) => now + 1);
    return () => {
      roomAskers.current.delete(folded);
      setAskers((now) => now - 1);
    };
  }, []);
  const [passing, setPassing] = useState<Passing>(null);
  if (!open && passing !== null) {
    setPassing(null);
  }
  const yielding = askers > 0;
  const effectivePassing = yielding ? "folded" : passing;
  useLayoutEffect(() => {
    if (!yielding) return;
    for (const folded of roomAskers.current) folded();
  }, [yielding, askers]);

  // The control the keyboard was on as the content's form went, taken in the
  // commit that removed it, while the content was still in the document.
  const keyboardWasOn = useRef<HTMLElement | null>(null);
  // Where the content stands now. State, so focus waits for the render after
  // it: a covering form's slot arrives a commit after the form does.
  const [holder, setHolder] = useState<HTMLDivElement | null>(null);
  const holdContent = useCallback(
    (slot: HTMLDivElement) => {
      if (host !== null) slot.append(host);
      setHolder(slot);
      return () => {
        const focused = document.activeElement;
        if (focused instanceof HTMLElement && slot.contains(focused)) {
          keyboardWasOn.current = focused;
        }
      };
    },
    [host],
  );

  const hasOpened = useRef(false);
  useEffect(() => {
    const focused = keyboardWasOn.current;
    if (!open) {
      return;
    }
    hasOpened.current = true;
    if (holder?.isConnected === true && focused !== null) {
      keyboardWasOn.current = null;
      if (holder.contains(focused)) {
        focused.focus();
      }
    }
  }, [open, holder]);

  // On the content going rather than the panel shutting: a covering form
  // hides the list until it has left, and the keyboard is not put down
  // anywhere hidden. Passive, so a leaving drawer's focus trap has cleaned up.
  useEffect(() => {
    const focused = keyboardWasOn.current;
    if (present || !hasOpened.current) {
      return;
    }
    hasOpened.current = false;
    keyboardWasOn.current = null;
    const now = document.activeElement;
    const leftInside =
      focused !== null ||
      now === document.body ||
      drawerRef.current?.contains(now) === true;
    if (!leftInside) {
      return;
    }
    const back = opener.current;
    if (shutByReader.current && back?.isConnected === true) {
      back.focus();
    } else {
      listRef.current?.focus();
    }
  }, [present]);

  const besideSx = useMemo(
    () => ({
      ...(stays ? STAYING_SX : closeBeside ? BESIDE_DRAWER_SX : BESIDE_SX),
      ...(sticky ? STICKY_SX : {}),
      ...(foot == null ? {} : FOOTED_BESIDE_SX),
      ...(!stays && !closeBeside && foot == null && besideHeading === undefined
        ? ROW_HEADING_GAP_SX
        : {}),
      width,
    }),
    [stays, closeBeside, sticky, foot, width, besideHeading],
  );
  const overSx = useMemo(
    () =>
      over
        ? ROW_OVER_SX
        : { ...OVER_SX, "& .MuiDrawer-paper": { ...PAPER_SX, width } },
    [over, width],
  );

  const titled = (
    <Typography
      id={headingId}
      aria-label={visibleTitle === undefined ? undefined : title}
      // Where a save leaves nothing to press, the keyboard is put on the drawer's name.
      tabIndex={over ? -1 : undefined}
      variant={headingLevel}
      sx={
        beside === true && besideHeading === undefined && !closeBeside
          ? stays
            ? STAYING_HEADING_SX
            : { mb: 0 }
          : over
            ? ROW_DRAWER_TITLE_SX
            : undefined
      }
    >
      {visibleTitle ?? <bdi>{title}</bdi>}
    </Typography>
  );
  const attending = (expanded: boolean) =>
    yielding ? null : (
      <Attend
        reveal={reveal}
        insist={insist ?? null}
        expanded={expanded}
        holder={holder}
        unfold={() => setPassing("unfolded")}
        revealedRef={revealedRef}
        unfoldedForRef={unfoldedForRef}
        heededRef={heededRef}
      />
    );
  const closing = (
    <IconButton color="inherit" aria-label={say("panel.close")} onClick={shut}>
      <CloseIcon />
    </IconButton>
  );

  const heading =
    besideHeading === undefined ? (
      titled
    ) : (
      <Box
        sx={
          beside !== true
            ? TITLE_LINE_SX
            : stays
              ? STAYING_HEADING_LINE_SX
              : BESIDE_HEADING_LINE_SX
        }
      >
        {titled}
        {besideHeading}
      </Box>
    );

  const across = beside === false && stays;
  // A panel about a row has no strip over the list: shutting it lets the row go.
  const covering = (expanded: boolean, shutting: () => void) => (
    <>
      {attending(expanded)}
      <Drawer
        ref={drawerRef}
        anchor="right"
        open={open && expanded}
        // Escape and the scrim shut the topmost drawer, and one standing on
        // this one is above it.
        onClose={standingOn === null ? shutting : standingOn.shut}
        onTransitionExited={() => {
          if (!open) setPresent(false);
        }}
        ModalProps={OVER_MODAL_PROPS}
        // On the paper, which is the dialog; on the drawer it would name the
        // presentational root around it.
        slotProps={{ paper: { id: paperId, "aria-labelledby": headingId } }}
        sx={overSx}
      >
        <Box
          ref={over ? row.setSheet : undefined}
          sx={row.sheetSx}
          inert={row.covered}
        >
          <DrawerSheet
            heading={heading}
            onShut={shutting}
            said={saidOver ?? null}
            above={above}
            foot={foot}
          >
            {/* Never moved to another form, so drawn in place: what it
                focuses as it mounts is in the document then. */}
            <div ref={holdContent}>
              {over ? (
                <RowDrawerContext.Provider value={row.lent}>
                  {children}
                </RowDrawerContext.Provider>
              ) : null}
            </div>
          </DrawerSheet>
        </Box>
        {row.slotted}
      </Drawer>
    </>
  );
  const standingDrawn =
    standing && present && rowDrawer.slot !== null ? (
      <StandingDrawer
        slot={rowDrawer.slot}
        covers={rowDrawer.covers}
        open={open}
        onExited={() => setPresent(false)}
        width={width}
        headingId={headingId}
        heading={heading}
        onShut={shut}
        said={saidOver ?? null}
        above={above}
        foot={foot}
        attending={attending(true)}
      >
        <div ref={holdContent} />
      </StandingDrawer>
    ) : null;
  const listed = (
    <Box
      ref={listRef}
      role={listLabel === undefined ? undefined : listRole}
      aria-label={listLabel}
      tabIndex={listLabel === undefined ? undefined : -1}
      sx={stays ? STAYING_LIST_SX : LIST_SX}
    >
      {stays ? (
        <RoomContext.Provider value={lendRoom}>{list}</RoomContext.Provider>
      ) : (
        list
      )}
    </Box>
  );

  const folding =
    beside === null || standing ? null : beside ? (
      open ? (
        <Box component="section" aria-labelledby={headingId} sx={besideSx}>
          <CollapsiblePanel
            title={title}
            heading={
              closeBeside ? (
                <Box sx={BESIDE_HEADING_LINE_SX}>
                  {heading}
                  <Box sx={{ ml: "auto" }}>{closing}</Box>
                </Box>
              ) : (
                heading
              )
            }
            keptAs={keptAs}
            defaultExpanded={!stays || (firstBeside ?? false)}
            passing={effectivePassing}
            disabled={yielding}
            onPass={setPassing}
          >
            {(expanded) => (
              <>
                {attending(expanded)}
                {foot == null ? (
                  <>
                    {above}
                    <div data-panel-content ref={holdContent} />
                  </>
                ) : (
                  <>
                    <Box sx={BESIDE_BODY_SX}>
                      {above}
                      <div ref={holdContent} />
                    </Box>
                    <Box sx={BESIDE_FOOT_SX}>{foot}</Box>
                  </>
                )}
              </>
            )}
          </CollapsiblePanel>
        </Box>
      ) : null
    ) : stays ? (
      <CollapsiblePanel
        title={title}
        keptAs={keptAs}
        defaultExpanded={firstBeside ?? false}
        across
        controls={paperId}
        passing={effectivePassing}
        disabled={yielding}
        onPass={setPassing}
      >
        {(expanded, toggle) => covering(expanded, toggle)}
      </CollapsiblePanel>
    ) : (
      covering(true, shut)
    );

  // Across, the strip stands before the frame rather than in it, so the list
  // keeps its place in the tree and nothing it holds is drawn afresh.
  return (
    <>
      {across ? <Box sx={ACROSS_SX}>{folding}</Box> : null}
      <Box ref={frameRef} sx={stays ? STAYING_FRAME_SX : FRAME_SX}>
        {listed}
        {across ? null : folding}
        {standingDrawn}
        {/* While it covers the list its events run along this tree and the
            drawer's: a handler above sees each twice, and one inside that stops
            an event hides it from the drawer's Escape. */}
        {present && beside !== null && host !== null
          ? createPortal(children, host)
          : null}
      </Box>
    </>
  );
}

/** The control in `list` for what `held` holds, by what it says it controls. */
function controlOf(
  list: HTMLElement | null,
  held: HTMLElement | null,
): HTMLElement | null {
  if (list === null || held === null) return null;
  for (const control of list.querySelectorAll<HTMLElement>("[aria-controls]")) {
    const controls = control.getAttribute("aria-controls")!;
    if (held.querySelector(`#${CSS.escape(controls)}`) !== null) return control;
  }
  return null;
}

// Each waits for the content's holder: a covering form's slot arrives a
// commit after the form does.
function Attend({
  reveal,
  insist,
  expanded,
  holder,
  unfold,
  revealedRef,
  unfoldedForRef,
  heededRef,
}: {
  readonly reveal?: Reveal;
  readonly insist: Insist | null;
  readonly expanded: boolean;
  readonly holder: HTMLDivElement | null;
  readonly unfold: () => void;
  readonly revealedRef: RefObject<Reveal | undefined>;
  readonly unfoldedForRef: RefObject<Insist | null>;
  readonly heededRef: RefObject<Insist | null>;
}) {
  const unfoldNow = useEffectEvent(unfold);
  const revealFrom = useRef<{
    readonly reveal: Reveal;
    readonly keyboard: Element | null;
  } | null>(null);
  useEffect(() => {
    if (reveal === undefined || revealedRef.current === reveal) return;
    if (revealFrom.current?.reveal !== reveal) {
      revealFrom.current = { reveal, keyboard: document.activeElement };
    }
    if (!expanded) {
      unfoldNow();
      return;
    }
    if (holder?.isConnected !== true) return;
    revealedRef.current = reveal;
    const { keyboard } = revealFrom.current;
    // After the commit, past a covering form's own trap; but never off
    // anything the reader moved the keyboard to meanwhile.
    queueMicrotask(() => {
      const now = document.activeElement;
      if (
        revealedRef.current === reveal &&
        (now === keyboard ||
          now === document.body ||
          now?.getAttribute("role") === "dialog")
      )
        reveal.focus?.();
    });
  }, [reveal, expanded, holder, revealedRef]);
  useEffect(() => {
    if (insist === null || heededRef.current === insist) return;
    if (!expanded) {
      unfoldedForRef.current = insist;
      unfoldNow();
      return;
    }
    if (unfoldedForRef.current !== insist) {
      heededRef.current = insist;
      return;
    }
    if (holder?.isConnected !== true) return;
    heededRef.current = insist;
    queueMicrotask(() => insist.focus());
  }, [insist, expanded, holder, heededRef, unfoldedForRef]);
  return null;
}
