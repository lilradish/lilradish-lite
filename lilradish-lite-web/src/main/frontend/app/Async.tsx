import Typography from "@mui/material/Typography";
import { useState, type ReactNode } from "react";

import { say } from "../i18n/app";
import { Notice } from "../lib/notice/Notice";
import type { Resource } from "../lib/request/useResource";
import { ProblemView } from "./ProblemView";
import type { Rule } from "./standing/actRules";

const QUIET_SX = { my: 1, color: "text.secondary" };

/**
 * A refusal replaces the empty notice: claiming an unreadable queue is empty
 * would mislead the reader. Keeping that order here protects every caller.
 *
 * Re-reading keeps the drawn value and shows loading beside it, preserving
 * the requesting control and its focus. Only that same value counts as kept;
 * a new source's fresh blank waits like a first read.
 *
 * Takes the producing `Resource`; its narrower three-field state stays unexported.
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
   * Null means something was returned; otherwise gives the empty notice.
   * The caller decides what counts as nothing.
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
