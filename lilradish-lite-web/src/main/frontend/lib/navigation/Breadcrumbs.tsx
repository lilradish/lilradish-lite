import MuiBreadcrumbs from "@mui/material/Breadcrumbs";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";
import { Link as RouterLink } from "react-router";

import { say } from "../../i18n/lib";

export interface Crumb {
  readonly label: string;
  /** A path with no query of its own. */
  readonly path: string;
  /** The search this crumb opens with, empty for none. */
  readonly search: string;
}

/**
 * The screen you are on is its own prop rather than the last entry of the
 * trail: it is the one step with nowhere to link to, and a trail of optional
 * destinations would let that be expressed two ways. It is left out while the
 * screen cannot yet say, or may not say, what it is called.
 *
 * A crumb opens with the search it is given and no other: what a list was
 * filtered by comes back only where the screen's own address holds it.
 */
export function Breadcrumbs({
  trail,
  current,
}: {
  readonly trail: readonly Crumb[];
  readonly current?: string;
}) {
  return (
    // The landmark element is MUI's default; its name is not, and an unnamed
    // one is announced as just "navigation" beside every other navigation.
    <MuiBreadcrumbs
      aria-label={say("navigation.breadcrumb")}
      expandText={say("navigation.wholeTrail")}
      sx={{ mb: 2 }}
    >
      {trail.map(({ label, path, search }) => (
        <Link
          // Two crumbs may open one path, each with a search of its own.
          key={`${path}${search}`}
          component={RouterLink}
          // Assembled by the router rather than concatenated here: the search
          // has a field of its own, and an empty one adds no trailing `?`.
          to={{ pathname: path, search }}
          underline="hover"
        >
          {label}
        </Link>
      ))}
      {/* `sx`, not the `color` prop: that prop matches only enumerated
          variants, so a palette path there type-checks and reaches no rule. */}
      {current === undefined ? null : (
        <Typography sx={{ color: "text.primary" }}>{current}</Typography>
      )}
    </MuiBreadcrumbs>
  );
}
