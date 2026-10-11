import { useState, type ReactNode } from "react";

import type { Action } from "../request/useAction";
import { Press, type ActionTier, type Reason } from "./Press";

/**
 * Holds the button during an ask to prevent repeated clicks issuing another;
 * `useAction` explains how a second ask abandons the first.
 *
 * The caller omits unauthorized acts. A drawn act that cannot be pressed must
 * say why beside it and use those words as its description.
 */
export function ActButton<T>({
  action,
  act,
  tier,
  small,
  fill,
  waiting,
  reason,
  reasonDescribedBy,
  label,
  children,
}: {
  readonly action: Action<T>;
  readonly act: (signal: AbortSignal) => Promise<T>;
  readonly tier?: ActionTier;
  readonly small?: true;
  readonly fill?: boolean;
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
  readonly reasonDescribedBy?: string;
  readonly label?: string;
  readonly children: ReactNode;
}) {
  const [pressed, setPressed] = useState(false);
  if (pressed && !action.running) setPressed(false);
  return (
    <Press
      tier={tier}
      small={small}
      fill={fill}
      busy={pressed && action.running && waiting !== true}
      unavailable={action.running || waiting === true}
      reason={reason}
      reasonDescribedBy={reasonDescribedBy}
      label={label}
      onPress={() => {
        setPressed(true);
        action.run(act);
      }}
    >
      {children}
    </Press>
  );
}
