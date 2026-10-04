import Typography from "@mui/material/Typography";

import { say } from "../i18n/app";
import { Part } from "../lib/layout/Part";
import { PRODUCT_NAME } from "./product";

/**
 * Where a reader arrives, and the one screen every reader may be on.
 *
 * It sends nobody anywhere. A reader holding no role has nowhere to be sent, so
 * a redirect from here would either land them on a refusal or need to know what
 * they may reach before it could choose — and either one tells them what else
 * this system has.
 */
export function Landing() {
  return (
    <Part title={PRODUCT_NAME} level="h1" depth={1}>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        {say("landing.detail")}
      </Typography>
    </Part>
  );
}
