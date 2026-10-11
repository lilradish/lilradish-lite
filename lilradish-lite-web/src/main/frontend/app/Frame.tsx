import Typography from "@mui/material/Typography";
import { Outlet, useLocation } from "react-router";

import { useStandingResource } from "../api/standing";
import { AppShell } from "./AppShell";
import { Nav } from "./Nav";
import { ProblemView } from "./ProblemView";
import { PRODUCT_NAME } from "./product";
import { StandingProvider } from "./standing/StandingContext";
import { useGroupInForce } from "./useGroupInForce";

/**
 * Provides the one standing read around both navigation and screens:
 * separate answers could offer a door that its screen refuses.
 *
 * Nothing here waits. Pending standing grants no destinations, avoiding links
 * whose authority is unknown. Screens needing standing wait themselves;
 * the universally reachable screen renders at once.
 */
export function Frame() {
  const standing = useStandingResource();
  // Held here and not in the sidebar, which a change of viewport remounts.
  const inForce = useGroupInForce(standing.value.groups);
  const at = useLocation().pathname;

  return (
    <StandingProvider read={standing}>
      <AppShell
        brand={<Typography variant="brand">{PRODUCT_NAME}</Typography>}
        navigation={<Nav inForce={inForce} />}
        utility={null}
        at={at}
      >
        {/* A refused identity read refuses the frame: no screen beneath it
            is truthful without knowing the reader. */}
        {standing.problem === null ? (
          <Outlet />
        ) : (
          <ProblemView problem={standing.problem} />
        )}
      </AppShell>
    </StandingProvider>
  );
}
