import MuiBreadcrumbs from "@mui/material/Breadcrumbs";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Link as RouterLink, useLocation } from "react-router";

import { say } from "../../i18n/lib";
import { returnSearch } from "./returnSearch";

export interface Crumb {
  readonly label: string;
  /** A path with no query of its own: the journey's search is what the first crumb carries. */
  readonly path: string;
}

/**
 * Where you are, and how to get back to where you were.
 *
 * The screen you are on is its own prop rather than the last entry of the
 * trail: it is the one step with nowhere to link to, and a trail of optional
 * destinations would let that be expressed two ways.
 *
 * The first link carries the search the journey began with, so returning to a
 * list restores the filter that was in force rather than dropping the reader at
 * an unfiltered first page. Only the first: that search belongs to the list the
 * journey started at, not to the screens between.
 */
export function Breadcrumbs({
  trail,
  current,
}: {
  readonly trail: readonly Crumb[];
  readonly current: string;
}) {
  const location = useLocation();
  const from = returnSearch(location.state);

  return (
    // The landmark element is MUI's default; its name is not, and an unnamed
    // one is announced as just "navigation" beside every other navigation.
    <MuiBreadcrumbs
      aria-label={say("navigation.breadcrumb")}
      expandText={say("navigation.wholeTrail")}
      sx={{ mb: 2 }}
    >
      {trail.map((crumb, index) => (
        <Link
          key={crumb.path}
          component={RouterLink}
          // Assembled by the router rather than concatenated here: the search
          // has a field of its own, and an empty one adds no trailing `?`.
          to={{ pathname: crumb.path, search: index === 0 ? from : "" }}
          underline="hover"
        >
          {crumb.label}
        </Link>
      ))}
      {/* `sx`, not the `color` prop: that prop matches only enumerated
          variants, so a palette path there type-checks and reaches no rule. */}
      <Typography sx={{ color: "text.primary" }}>{current}</Typography>
    </MuiBreadcrumbs>
  );
}
