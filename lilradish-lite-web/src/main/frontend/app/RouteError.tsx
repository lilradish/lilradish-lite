import Box from "@mui/material/Box";
import Link from "@mui/material/Link";
import Container from "@mui/material/Container";
import { Link as RouterLink } from "react-router";

import { say, type MessageId } from "../i18n/app";
import { Part } from "../lib/layout/Part";
import { Press } from "../lib/action/Press";
import { Notice } from "../lib/notice/Notice";

/**
 * A screen that went wrong while the frame around it stands, so the menu is
 * still there to leave by. What went wrong is never shown to the reader.
 */
export function ScreenError() {
  return (
    <WentWrong
      title="routeError.broken"
      detail="routeError.brokenDetail"
      reload="routeError.brokenReload"
    />
  );
}

/** The frame itself went wrong, and with it the menu: reloading is the one way on. */
export function FrameError() {
  return (
    <Container sx={{ py: 3 }}>
      <WentWrong
        title="routeError.frameBroken"
        detail="routeError.frameBrokenDetail"
        reload="routeError.frameBrokenReload"
      />
    </Container>
  );
}

/** An address naming nothing; a group the reader is not in answers with it too, in the same words. */
export function NoSuchScreen() {
  return (
    <Part title={say("routeError.missing")} level="h1" depth={1}>
      <Notice severity="error" alert>
        {say("routeError.missingDetail")}
      </Notice>
      <Link
        component={RouterLink}
        to="/"
        sx={{ display: "inline-block", mt: 2 }}
      >
        {say("routeError.missingWayBack")}
      </Link>
    </Part>
  );
}

function WentWrong({
  title,
  detail,
  reload,
}: {
  readonly title: MessageId;
  readonly detail: MessageId;
  readonly reload: MessageId;
}) {
  return (
    <Part title={say(title)} level="h1" depth={1}>
      <Notice severity="error" alert>
        {say(detail)}
      </Notice>
      <Box sx={{ mt: 2 }}>
        <Press tier="secondary" onPress={() => window.location.reload()}>
          {say(reload)}
        </Press>
      </Box>
    </Part>
  );
}
