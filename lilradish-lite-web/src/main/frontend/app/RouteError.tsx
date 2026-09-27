import Button from "@mui/material/Button";
import { Link as RouterLink } from "react-router";

import { say, type MessageId } from "../i18n/app";
import { PageHeading } from "../lib/heading/PageHeading";
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
    <WentWrong
      title="routeError.frameBroken"
      detail="routeError.frameBrokenDetail"
      reload="routeError.frameBrokenReload"
    />
  );
}

/** An address naming nothing; a group the reader is not in answers with it too, in the same words. */
export function NoSuchScreen() {
  return (
    <section>
      <PageHeading title={say("routeError.missing")} />
      <Notice severity="error" alert>
        {say("routeError.missingDetail")}
      </Notice>
      <Button component={RouterLink} to="/" sx={{ mt: 2 }}>
        {say("routeError.missingWayBack")}
      </Button>
    </section>
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
    <section>
      <PageHeading title={say(title)} />
      <Notice severity="error" alert>
        {say(detail)}
      </Notice>
      <Button onClick={() => window.location.reload()} sx={{ mt: 2 }}>
        {say(reload)}
      </Button>
    </section>
  );
}
