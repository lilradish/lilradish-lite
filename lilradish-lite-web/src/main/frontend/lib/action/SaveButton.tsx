import type { ReactNode } from "react";

import type { Action } from "../request/useAction";
import { ActButton } from "./ActButton";
import type { Reason } from "./Press";

/** A part's Save, drawn only while something is unsaved or being saved, and then as its leading button. */
export function SaveButton<T>({
  unsaved,
  action,
  act,
  waiting,
  reason,
  label,
  children,
}: {
  readonly unsaved: boolean;
  readonly action: Action<T>;
  readonly act: (signal: AbortSignal) => Promise<T>;
  readonly waiting?: boolean;
  readonly reason?: Reason;
  readonly label?: string;
  readonly children: ReactNode;
}) {
  return unsaved || action.running ? (
    <ActButton
      action={action}
      act={act}
      tier="primary"
      waiting={waiting}
      reason={reason}
      label={label}
    >
      {children}
    </ActButton>
  ) : null;
}
