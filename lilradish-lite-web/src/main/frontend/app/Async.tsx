import Typography from "@mui/material/Typography";
import { useState, type ReactNode } from "react";

import { say } from "../i18n/app";
import { Notice } from "../lib/notice/Notice";
import type { Resource } from "../lib/request/useResource";
import { ProblemView } from "./ProblemView";
import type { Rule } from "./standing/actRules";

const QUIET_SX = { my: 1, color: "text.secondary" };

/**
 * The four ways a read ends, decided in one place instead of on every screen.
 *
 * The one that matters is the order: a refusal is rendered *instead of* the
 * empty notice, never beside it. "Nothing is waiting for you", written over a
 * queue the server would not show, is a lie the reader has no way to detect,
 * and every screen that spells these states out by hand is one edit from
 * telling it.
 *
 * A read asked again keeps what it last drew, and says it is reading beside
 * it rather than instead of it: swapped out, the control that asked would go
 * with it and take the keyboard back to the top of the page. Only the very
 * value drawn counts as kept — a read that moved to another source holds a
 * fresh blank, and that is waited on like a first read.
 *
 * Takes a whole `Resource` rather than a narrower shape of its own: that is
 * what produces these states, and the type behind it holding only the three
 * read here is unexported on purpose.
 */
export function Async<T>({
  read,
  rule,
  empty,
  children,
}: {
  readonly read: Resource<T>;
  /** What reading it takes, so a refusal of the act is said as that rule. */
  readonly rule?: Rule;
  /**
   * What to say when the read came back with nothing, or null when it came back
   * with something. Asked of the value rather than worked out here, because
   * nothing is the caller's word: no rows, no approved version, no attempt yet.
   */
  readonly empty: (value: T) => string | null;
  readonly children: (value: T) => ReactNode;
}) {
  // Boxed, so a value that is itself a function is held rather than called.
  const [drawn, setDrawn] = useState<{ readonly value: T } | null>(null);
  const settled = !read.loading && read.problem === null;
  if (settled && (drawn === null || drawn.value !== read.value)) {
    setDrawn({ value: read.value });
  }

  if (read.problem !== null) {
    return <ProblemView problem={read.problem} rule={rule} />;
  }
  const again = read.loading && drawn !== null && drawn.value === read.value;
  if (read.loading && !again) {
    return (
      <Typography variant="body2" sx={QUIET_SX}>
        {say("read.pending")}
      </Typography>
    );
  }
  const nothing = empty(read.value);
  return (
    <>
      {/* There before the words it announces, and only while this is drawn. */}
      <Typography role="status" variant="body2" sx={again ? QUIET_SX : null}>
        {again ? say("read.pending") : null}
      </Typography>
      {nothing === null ? (
        children(read.value)
      ) : (
        <Notice severity="info">{nothing}</Notice>
      )}
    </>
  );
}
