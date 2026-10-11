import MenuIcon from "@mui/icons-material/Menu";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
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
import { UNSEEN_SX } from "../lib/layout/parts";
import { TOKENS } from "../lib/theme/tokens";
import { useSidebarExpanded } from "./useSidebarExpanded";

/** Within the 240–280px a dense enterprise sidebar stays legible at. */
const NAVIGATION_WIDTH = 256;

const CONTENT_ID = "content";
const NAVIGATION_ID = "navigation";

const SKIP_LINK_SX = {
  position: "absolute",
  left: 8,
  top: 8,
  zIndex: (given: Theme) => given.zIndex.tooltip,
  px: 2,
  py: 1,
  bgcolor: "background.paper",
  "&:not(:focus)": UNSEEN_SX,
};

/**
 * Fixes the slots' order without coupling the frame to their contents or to
 * the reader-specific rules that filter navigation.
 *
 * Use one drawer across viewport variants to avoid rendering duplicate
 * navigation landmarks.
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
  readonly utility: ReactNode;
  /** Where the reader is: a move made from the drawer without a link shuts it too. */
  readonly at?: string;
  readonly children: ReactNode;
}) {
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up("md"));
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useSidebarExpanded();

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
          {wide ? (
            <IconButton
              edge="start"
              color="inherit"
              size="small"
              aria-label={say("navigation.sidebar")}
              aria-expanded={expanded}
              aria-controls={NAVIGATION_ID}
              onClick={() => setExpanded(!expanded)}
              sx={{ mr: 1 }}
            >
              <MenuIcon />
            </IconButton>
          ) : (
            <IconButton
              edge="start"
              color="inherit"
              size="small"
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
        // Keep narrow-screen navigation state while its temporary drawer is shut;
        // the docked rail has no modal to keep.
        slotProps={{ root: { keepMounted: true } }}
        sx={{
          width: { md: expanded ? NAVIGATION_WIDTH : TOKENS.sidePanel.rail },
          flexShrink: 0,
          "& .MuiDrawer-paper": {
            width: wide && !expanded ? TOKENS.sidePanel.rail : NAVIGATION_WIDTH,
            boxSizing: "border-box",
            overflowX: "hidden",
          },
        }}
      >
        {/* Clears the fixed bar the drawer passes under. */}
        <Toolbar variant="dense" />
        {wide && !expanded ? (
          <IconButton
            aria-label={say("navigation.sidebar")}
            aria-expanded={false}
            aria-controls={NAVIGATION_ID}
            onClick={() => setExpanded(true)}
            title={say("navigation.sidebar")}
            sx={{ alignSelf: "center", my: "8px" }}
          >
            <ChevronRightIcon />
          </IconButton>
        ) : null}
        <Box
          component="nav"
          data-navigation-collapsed={wide && !expanded}
          id={NAVIGATION_ID}
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
          sx={{
            overflowY: "auto",
            overflowX: "hidden",
            pb: 2,
            "&[data-navigation-collapsed=true]": {
              px: "8px",
              borderTop: 1,
              borderColor: "surface.rule",
              "& [data-navigation-label]": { display: "none" },
            },
          }}
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
        <Container sx={{ py: 3 }}>{children}</Container>
      </Box>
    </Box>
  );
}
