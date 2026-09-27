import Box from "@mui/material/Box";
import Card from "@mui/material/Card";
import CardContent from "@mui/material/CardContent";
import Chip from "@mui/material/Chip";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router";

import { say } from "../../i18n/lib";
import { NavigableList } from "./RowList";

/** One thing waiting, and the screen that settles it. */
export interface Task {
  readonly id: string;
  readonly title: string;
  readonly detail: string;
  /**
   * An in-app path, always rooted and percent-encoded as the URL parser writes
   * it; one that would leave the site is not drawn, and neither is one the
   * parser would re-encode — a raw space or non-ASCII character, a `'` in the
   * query, a bare trailing `?` or `#`.
   */
  readonly path: string;
}

/** How many a card names before it hands over to the list behind it. */
const MOST_SHOWN = 3;

/**
 * Only a path the URL parser hands back unchanged: the router re-normalises the
 * href it draws (`..`, `\`, doubled slashes), so another is judged as one address and drawn as another.
 */
function staysOnSite(path: string): boolean {
  const origin = window.location.origin;
  let url: URL;
  try {
    url = new URL(path, origin);
  } catch {
    return false;
  }
  return (
    url.origin === origin &&
    !url.pathname.startsWith("//") &&
    path === url.pathname + url.search + url.hash
  );
}

const HEADER_SX = {
  display: "flex",
  alignItems: "center",
  // `gap` rather than a Stack: a Stack spaces its children by margin unless
  // `useFlexGap` is set, and margins leave a wrapped row's second line with no
  // space above it and its first element still indented.
  gap: 1,
  flexWrap: "wrap",
  mb: 1,
};

const URGENT_SX = { color: "error.main", typography: "tag" };

/**
 * One kind of thing waiting on this person: how many there are, the few worth
 * looking at first, and the way through to the rest.
 *
 * The count comes from what the caller hands over, never from the rows below
 * it: those are cut to the few, so counting them answers the first question —
 * is there anything at all — with a number that is not the answer. Until a
 * first answer has arrived there is nothing to count, and nothing is shown: a
 * zero that came before the answer is wrong rather than pending.
 *
 * `urgent` is the caller's judgement about the kind of thing, not about any one
 * of them, so an urgent card holding nothing raises nothing.
 */
export function TaskCard({
  heading,
  tasks,
  loading,
  emptyMessage,
  listScreen,
  urgent = false,
}: {
  readonly heading: string;
  /**
   * All of them, not a page of them. One whose path would leave the site is
   * neither drawn nor counted: a count naming a row that is nowhere is wrong.
   */
  readonly tasks: readonly Task[];
  readonly loading: boolean;
  readonly emptyMessage: string;
  readonly listScreen: {
    /** Rooted, for the reason `Task.path` is. */
    readonly path: string;
    readonly label: string;
  };
  readonly urgent?: boolean;
}) {
  const onSite = tasks.filter((task) => staysOnSite(task.path));
  const urgentAndWaiting = urgent && onSite.length > 0;

  return (
    <Card
      variant="outlined"
      // Cards stand side by side in a grid, where one shorter than its
      // neighbour reads as a card that matters less.
      sx={{
        height: "100%",
        borderColor: urgentAndWaiting ? "error.main" : "divider",
      }}
    >
      <CardContent>
        <Box sx={HEADER_SX}>
          <Typography
            variant="subtitle1"
            component="h2"
            sx={{ typography: "term" }}
          >
            {heading}
          </Typography>
          {/* A reload keeps the last answer's rows while it runs, and a card
              showing rows with no count beside them has lost the count. */}
          {loading && onSite.length === 0 ? null : (
            <Chip
              size="small"
              // Said in the label rather than in an `aria-label`: the root is a
              // `div`, and ARIA prohibits naming role `generic`, so the
              // attribute would compute to no name at all.
              label={say("task.waiting", { count: onSite.length })}
            />
          )}
          {/* A word as well as a colour: an edge drawn in the failure colour
              says nothing to a reader who cannot tell it from any other edge. */}
          {urgentAndWaiting ? (
            <Typography component="span" sx={URGENT_SX}>
              {say("task.urgent")}
            </Typography>
          ) : null}
        </Box>

        {onSite.length === 0 ? (
          <Typography variant="body2" sx={{ color: "text.secondary" }}>
            {loading ? say("read.pending") : emptyMessage}
          </Typography>
        ) : (
          <NavigableList
            items={onSite.slice(0, MOST_SHOWN)}
            keyOf={(task) => task.id}
            pathOf={(task) => task.path}
            title={(task) => task.title}
            detail={(task) => task.detail}
          />
        )}

        {staysOnSite(listScreen.path) ? (
          <Link component={RouterLink} to={listScreen.path} variant="body2">
            {listScreen.label}
          </Link>
        ) : null}
      </CardContent>
    </Card>
  );
}
