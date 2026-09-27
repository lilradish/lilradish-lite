import Box from "@mui/material/Box";
import type { ReactNode } from "react";

import { say } from "../../i18n/lib";

/**
 * Named things the estate reports about itself: readiness checks, capacity
 * triggers, trace spans, executed sweeps, incidents. They arrive from different
 * endpoints and are one shape to a reader — a name, and what is true of it.
 */
export function Checks({ children }: { readonly children: ReactNode }) {
  return (
    <Box
      component="ul"
      // Explicit, because `list-style: none` is taken by some engines as a sign
      // that the list is decorative and its item count need not be announced.
      role="list"
      sx={{
        listStyle: "none",
        p: 0,
        m: "0 0 1rem",
        display: "flex",
        flexDirection: "column",
        gap: "0.35rem",
        typography: "body2",
      }}
    >
      {children}
    </Box>
  );
}

// A verdict is a type on one side and a sentence on the other. Rendering the
// member itself welds what the reader sees to how the union happens to be spelt.
const VERDICT = { pass: "verdict.pass", fail: "verdict.fail" } as const;

export function Check({
  name,
  state,
  children,
}: {
  /** The identifier the row is about. */
  readonly name: ReactNode;
  /** Present only where the row is a verdict rather than a reading. */
  readonly state?: "pass" | "fail";
  readonly children?: ReactNode;
}) {
  return (
    <li>
      {state === undefined ? null : (
        <Box
          component="span"
          sx={{
            typography: "term",
            color: state === "pass" ? "success.main" : "error.main",
            mr: 1,
          }}
        >
          {say(VERDICT[state])}
        </Box>
      )}
      <Box
        component="span"
        // The separator is a margin here rather than a space written beside the
        // reading: a space is not children, so React would render it even where
        // there is no reading to separate.
        sx={{ typography: "identifier", mr: 0.5 }}
      >
        {name}
      </Box>
      {children}
    </li>
  );
}
