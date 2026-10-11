import Typography from "@mui/material/Typography";

import { say, type MessageId } from "../i18n/app";
import { Part } from "../lib/layout/Part";

/**
 * It says it holds nothing rather than standing empty: a blank screen and a screen whose
 * content failed to arrive look the same to a reader, and only one of them is
 * worth waiting on.
 *
 * The heading is the destination's own words, so the entry in the navigation
 * and the screen it leads to cannot come to be named differently.
 */
export function SystemPage({ title }: { readonly title: MessageId }) {
  return (
    <Part title={say(title)} level="h1" depth={1}>
      <Typography variant="body2" sx={{ color: "text.secondary" }}>
        {say("page.notBuilt")}
      </Typography>
    </Part>
  );
}
