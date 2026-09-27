import type { ReactNode } from "react";

import type { Action } from "../request/useAction";
import { Press, type Reason } from "./Press";

/**
 * A button that asks for something and is unavailable while it is being asked.
 *
 * The unavailability is the part that is easy to leave out, and what it costs
 * is one ask per repeated click on a button that appeared to do nothing — see
 * `useAction` for what a second ask does to the first.
 *
 * A button the reader may not use is not this one: the caller leaves it out.
 * This is only ever a button that is drawn, and one drawn that cannot be
 * pressed says why, in words beside it that it is described by.
 */
export function ActButton<T>({
  action,
  act,
  waiting,
  reason,
  children,
}: {
  readonly action: Action<T>;
  readonly act: (signal: AbortSignal) => Promise<T>;
  /**
   * Unavailable as well while something else the screen waits on is out,
   * which the reader can see is out.
   */
  readonly waiting?: boolean;
  /**
   * Why it cannot be pressed right now, beyond the act already being in
   * flight; absent where it can.
   */
  readonly reason?: Reason;
  readonly children: ReactNode;
}) {
  return (
    <Press
      unavailable={action.running || waiting === true}
      reason={reason}
      onPress={() => action.run(act)}
    >
      {children}
    </Press>
  );
}
