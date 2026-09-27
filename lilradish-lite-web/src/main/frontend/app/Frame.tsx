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
 * The frame every screen is shown inside, and the one place this side learns
 * who is reading.
 *
 * The read is provided around the whole frame rather than around the screen,
 * because the navigation is filtered by it too — a sidebar built from one
 * answer and a screen guarded by another would offer a door that refuses.
 *
 * What is provided is the read and not its answer, and nothing here waits on
 * it. While it is still out the reader has no standing, so the navigation is
 * empty — the safe direction, and the alternative is drawing destinations
 * before anything has said they are this reader's. The screens that cannot be
 * drawn without an answer wait for it themselves, and the one every reader may
 * be on is drawn at once.
 */
export function Frame() {
  const standing = useStandingResource();
  // Held here and not in the sidebar, which a change of viewport remounts.
  const inForce = useGroupInForce(standing.value.groups);
  const at = useLocation().pathname;

  return (
    <StandingProvider read={standing}>
      <AppShell
        brand={
          <Typography variant="h6" component="p">
            {PRODUCT_NAME}
          </Typography>
        }
        navigation={<Nav inForce={inForce} />}
        utility={null}
        at={at}
      >
        {/* A refusal of this read is a refusal of the frame: it is who the
            reader is that could not be learnt, and no screen under here is
            truthful without that. */}
        {standing.problem === null ? (
          <Outlet />
        ) : (
          <ProblemView problem={standing.problem} />
        )}
      </AppShell>
    </StandingProvider>
  );
}
