import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import Typography from "@mui/material/Typography";
import type { Theme } from "@mui/material/styles";
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";

import { say } from "../../i18n/lib";
import { StatusLine, type Line } from "../notice/StatusLine";

const PANEL_WIDTH = 360;

const GAP = 24;

const LEAST_TABLE_WIDTH = 560;

/** Beside only where the table keeps its least width with the panel and the gap taken out. */
const ROOM_FOR_BOTH = LEAST_TABLE_WIDTH + GAP + PANEL_WIDTH;

/**
 * Wider than a scrollbar. The panel beside the list makes the page taller and
 * can bring a scrollbar in, and the covering form takes it away again.
 */
const SCROLLBAR_ALLOWANCE = 24;

const FRAME_SX = { display: "flex", alignItems: "flex-start", gap: `${GAP}px` };

const LIST_SX = { flexGrow: 1, minWidth: 0 };

const BESIDE_SX = {
  width: PANEL_WIDTH,
  boxSizing: "border-box",
  flexShrink: 0,
  pl: 3,
  borderLeftWidth: 1,
  borderLeftStyle: "solid",
  borderLeftColor: "divider",
};

const OVER_SX = {
  // A covering panel is modal, so it takes the modal layer and not the
  // drawer's: nothing outside it may stand over its heading or close control.
  zIndex: (given: Theme) => given.zIndex.modal,
  "& .MuiDrawer-paper": {
    width: PANEL_WIDTH,
    maxWidth: "100%",
    boxSizing: "border-box",
    p: 3,
  },
};

const OVER_HEADER_SX = {
  display: "flex",
  alignItems: "flex-start",
  justifyContent: "space-between",
  gap: 1,
};

/** The drawer hands nothing back itself; where the keyboard goes is decided here. */
const OVER_MODAL_PROPS = { disableRestoreFocus: true };

/**
 * What is read about one row, beside the list where there is room for both and
 * over it where there is not: the same heading and content either way, and a
 * control to shut it only where it covers what it was opened from.
 *
 * The room is measured on this component's own box, never on the viewport.
 *
 * The content is drawn once, into an element of its own moved between the two
 * forms, so it keeps what it holds and the control the keyboard was on.
 *
 * Shut from its own control, it hands the keyboard back to what opened it;
 * shut by the caller, or once what opened it has gone, to the list.
 *
 * `title` is isolated here, in an element of its own, so the name the region
 * or dialog takes from its heading carries none of this side's control
 * characters.
 */
export function RowPanel({
  open,
  onClose,
  title,
  headingLevel = "h2",
  list,
  listLabel,
  saidOver,
  children,
}: {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly title: string;
  /** One below the heading the list sits under. */
  readonly headingLevel?: "h2" | "h4";
  readonly list: ReactNode;
  /**
   * A line of the page's said inside the panel while it covers the page, whose
   * own line is hidden then and never heard; beside, the page's is heard.
   */
  readonly saidOver?: Line | null;
  /** Names the list's own box, which is where the keyboard is put down. */
  readonly listLabel: string;
  readonly children: ReactNode;
}) {
  const headingId = useId();
  const frameRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  // Unknown until measured, before the first paint; neither form is drawn
  // until then, so neither is put in only to be replaced.
  const [beside, setBeside] = useState<boolean | null>(null);
  const [host] = useState(() => document.createElement("div"));
  // Drawn from opening until whichever form holds it has gone, which for the
  // covering one is once it has finished leaving.
  const [present, setPresent] = useState(open);
  if (open && !present) {
    setPresent(true);
  }
  if (!open && present && beside === true) {
    setPresent(false);
  }

  // What had the keyboard as the panel opened, and whether its own control
  // shut it: both read once it has shut.
  const opener = useRef<HTMLElement | null>(null);
  const shutByReader = useRef(false);
  useLayoutEffect(() => {
    if (open) {
      const focused = document.activeElement;
      opener.current =
        focused instanceof HTMLElement && focused !== document.body
          ? focused
          : null;
      shutByReader.current = false;
    }
  }, [open]);
  const shut = () => {
    shutByReader.current = true;
    onClose();
  };

  // The control the keyboard was on as the content's form went, taken in the
  // commit that removed it, while the content was still in the document.
  const keyboardWasOn = useRef<HTMLElement | null>(null);
  // Where the content stands now. State, so focus waits for the render after
  // it: a covering form's slot arrives a commit after the form does.
  const [holder, setHolder] = useState<HTMLDivElement | null>(null);
  const holdContent = useCallback(
    (slot: HTMLDivElement) => {
      slot.append(host);
      setHolder(slot);
      return () => {
        const focused = document.activeElement;
        if (focused instanceof HTMLElement && host.contains(focused)) {
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

  useLayoutEffect(() => {
    const frame = frameRef.current;
    if (frame === null) {
      return;
    }
    const judge = (width: number) =>
      setBeside(
        (was) =>
          width >= ROOM_FOR_BOTH + (was === true ? 0 : SCROLLBAR_ALLOWANCE),
      );
    judge(frame.getBoundingClientRect().width);
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        judge(entry.borderBoxSize[0].inlineSize);
      }
    });
    observer.observe(frame);
    return () => observer.disconnect();
  }, []);

  const heading = (
    <Typography id={headingId} variant="h6" component={headingLevel}>
      <bdi>{title}</bdi>
    </Typography>
  );

  return (
    <Box ref={frameRef} sx={FRAME_SX}>
      <Box
        ref={listRef}
        role="region"
        aria-label={listLabel}
        tabIndex={-1}
        sx={LIST_SX}
      >
        {list}
      </Box>
      {beside === null ? null : beside ? (
        open ? (
          <Box component="section" aria-labelledby={headingId} sx={BESIDE_SX}>
            {heading}
            <div ref={holdContent} />
          </Box>
        ) : null
      ) : (
        <Drawer
          ref={drawerRef}
          anchor="right"
          open={open}
          onClose={shut}
          onTransitionExited={() => setPresent(false)}
          ModalProps={OVER_MODAL_PROPS}
          // On the paper, which is the dialog; on the drawer it would name the
          // presentational root around it.
          slotProps={{ paper: { "aria-labelledby": headingId } }}
          sx={OVER_SX}
        >
          <Box sx={OVER_HEADER_SX}>
            {heading}
            <IconButton aria-label={say("panel.close")} onClick={shut}>
              <CloseIcon />
            </IconButton>
          </Box>
          <StatusLine said={saidOver ?? null} />
          <div ref={holdContent} />
        </Drawer>
      )}
      {/* While it covers the list its events run along this tree and the
          drawer's: a handler above sees each twice, and one inside that stops
          an event hides it from the drawer's Escape. */}
      {present && beside !== null ? createPortal(children, host) : null}
    </Box>
  );
}
