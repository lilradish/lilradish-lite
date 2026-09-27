import Alert from "@mui/material/Alert";
import type { ReactNode } from "react";

/**
 * How much a notice weighs. `info` is anything the reader is told that is
 * neither what has to go first nor a refusal; `warning` says what has to go
 * first, or what cannot be called back; `error` says what was refused or failed.
 */
export type Severity = "info" | "warning" | "error";

/**
 * Every hint or message a screen shows, placed by its caller beside what it
 * explains. The severity decides its colour and its icon; nothing about how it
 * looks is the caller's.
 *
 * The severity is never read out, so the words have to carry it.
 *
 * `alert` for what replaced what the reader asked for. Never a status: a
 * notice that arrives with its region is not reliably heard, so a caller that
 * announces one puts it inside a region that stood there before it.
 */
export function Notice({
  severity,
  alert,
  id,
  children,
}: {
  readonly severity: Severity;
  readonly alert?: boolean;
  /** Where a control it explains is described by it. */
  readonly id?: string;
  readonly children: ReactNode;
}) {
  return (
    <Alert id={id} severity={severity} role={alert === true ? "alert" : "none"}>
      {children}
    </Alert>
  );
}
