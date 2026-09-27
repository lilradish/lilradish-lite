import MenuIcon from "@mui/icons-material/Menu";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Container from "@mui/material/Container";
import Drawer from "@mui/material/Drawer";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Toolbar from "@mui/material/Toolbar";
import useMediaQuery from "@mui/material/useMediaQuery";
import { useTheme, type Theme } from "@mui/material/styles";
import { useRef, useState, type ReactNode } from "react";

import { say } from "../i18n/app";

/** Within the 240–280px a dense enterprise sidebar stays legible at. */
const NAVIGATION_WIDTH = 256;

const CONTENT_ID = "content";

const SKIP_LINK_SX = {
  position: "absolute",
  left: 8,
  top: 8,
  zIndex: (given: Theme) => given.zIndex.tooltip,
  px: 2,
  py: 1,
  bgcolor: "background.paper",
  "&:not(:focus)": {
    // Written in pixels: `sx` reads a bare 1 on a size as a fraction and emits
    // 100%, which would leave this covering the screen it is hiding behind.
    height: "1px",
    width: "1px",
    clipPath: "inset(50%)",
    overflow: "hidden",
    whiteSpace: "nowrap",
  },
};

/**
 * The frame every screen sits in: what the product is called, where a person
 * can go, and the one place the work itself is rendered.
 *
 * Slots rather than a component that knows its own contents. The order of the
 * parts is the whole point of a frame, so it is fixed here, while what goes in
 * each is the caller's. The navigation is filtered by what the reader may
 * reach, and it is the caller that knows it — so this file never learns who is
 * looking, and the rule deciding it stays on the one side that also enforces it.
 *
 * One drawer whose variant follows the viewport, rather than one per viewport:
 * two would put two navigation landmarks with the same name in the document,
 * and a screen reader offers both.
 */
export function AppShell({
  brand,
  navigation,
  utility,
  at,
  children,
}: {
  readonly brand: ReactNode;
  readonly navigation: ReactNode;
  /** What is about the system rather than about the work, at the far end. */
  readonly utility: ReactNode;
  /** Where the reader is: a move made from the drawer without a link shuts it too. */
  readonly at?: string;
  readonly children: ReactNode;
}) {
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("md"));
  const [open, setOpen] = useState(false);

  // Adjusted while rendering, not in an effect: widening answers the request,
  // and a yes left standing would reopen the drawer on the way back down.
  const [wasWide, setWasWide] = useState(wide);
  if (wide !== wasWide) {
    setWasWide(wide);
    setOpen(false);
  }
  const [wasAt, setWasAt] = useState(at);
  if (at !== wasAt) {
    setWasAt(at);
    setOpen(false);
  }
  const work = useRef<HTMLElement>(null);

  return (
    <Box sx={{ display: "flex" }}>
      {/* The destinations stand between the address bar and the work. Without
          this a keyboard reaches the work through every one, on every arrival. */}
      <Link
        href={`#${CONTENT_ID}`}
        onClick={(event) => {
          // Followed, the fragment is a history entry with no state, which
          // loses the journey back and leaves Back stopping on it.
          event.preventDefault();
          work.current?.focus();
        }}
        sx={SKIP_LINK_SX}
      >
        {say("navigation.skip")}
      </Link>

      <AppBar
        position="fixed"
        color="default"
        elevation={0}
        sx={{
          borderBottom: 1,
          borderColor: "divider",
          // Above the drawer, so the bar reads as one strip across the top
          // rather than being cut in half by the navigation.
          zIndex: (given) => given.zIndex.drawer + 1,
        }}
      >
        <Toolbar variant="dense">
          {wide ? null : (
            <IconButton
              edge="start"
              aria-label={say("navigation.open")}
              onClick={() => setOpen(true)}
              sx={{ mr: 1 }}
            >
              <MenuIcon />
            </IconButton>
          )}
          <Box sx={{ flexGrow: 1 }}>{brand}</Box>
          {utility}
        </Toolbar>
      </AppBar>

      <Drawer
        variant={wide ? "permanent" : "temporary"}
        open={open}
        onClose={() => setOpen(false)}
        // Shutting it hides the destinations rather than unmounting them, and
        // what they hold with them. A docked drawer has no modal to keep.
        slotProps={{ root: { keepMounted: true } }}
        sx={{
          width: { md: NAVIGATION_WIDTH },
          flexShrink: 0,
          "& .MuiDrawer-paper": {
            width: NAVIGATION_WIDTH,
            boxSizing: "border-box",
          },
        }}
      >
        {/* Clears the fixed bar the drawer passes under. */}
        <Toolbar variant="dense" />
        <Box
          component="nav"
          aria-label={say("navigation.site")}
          // A temporary drawer otherwise stays over the screen it navigated to.
          // Only a destination, though: a tap on a heading went nowhere.
          onClick={(event) => {
            if (
              event.target instanceof Element &&
              event.target.closest("a") !== null
            ) {
              setOpen(false);
            }
          }}
          sx={{ overflowY: "auto", pb: 2 }}
        >
          {navigation}
        </Box>
      </Drawer>

      <Box
        component="main"
        id={CONTENT_ID}
        ref={work}
        // The skip link's target has to be able to hold focus, and a main
        // element is not focusable on its own.
        tabIndex={-1}
        // Without this a wide table stretches the flex item instead of
        // scrolling inside it, and pushes the page sideways.
        sx={{ flexGrow: 1, minWidth: 0 }}
      >
        <Toolbar variant="dense" />
        <Container maxWidth="lg" sx={{ py: 3 }}>
          {children}
        </Container>
      </Box>
    </Box>
  );
}
