import Typography from "@mui/material/Typography";

import { say } from "../i18n/app";
import { Part } from "../lib/layout/Part";
import { PRODUCT_NAME } from "./product";

/**
 * Every reader may arrive here. Never redirect: a reader with no roles has
 * nowhere to go, and choosing a destination would either refuse them or
 * disclose what else the system contains.
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
