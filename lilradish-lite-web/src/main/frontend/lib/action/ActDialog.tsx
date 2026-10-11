import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import type { ReactNode } from "react";

import type { Action } from "../request/useAction";
import { ActButton } from "./ActButton";
import { ActGroup } from "./ActGroup";
import { Press, type Reason } from "./Press";

/**
 * Once the act is out it cannot be abandoned — by the abandon control, Escape or the
 * shade — because the server carries it out whoever is still listening.
 */
export function ActDialog<T>({
  open,
  title,
  onShut,
  onExited,
  action,
  act,
  actLabel,
  tier,
  reason,
  abandonLabel,
  underway,
  refusal,
  children,
}: {
  readonly open: boolean;
  readonly title: string;
  readonly onShut: () => void;
  readonly onExited?: () => void;
  readonly action: Action<T>;
  readonly act: (signal: AbortSignal) => Promise<T>;
  readonly actLabel: string;
  readonly tier?: "primary" | "dangerConfirm";
  /** Why the act cannot be pressed yet; absent where it can. */
  readonly reason?: Reason;
  readonly abandonLabel: string;
  readonly underway: string;
  /** The caller's own words for the refusal, shown below the fields. */
  readonly refusal: ReactNode;
  readonly children: ReactNode;
}) {
  const running = action.running;
  return (
    <Dialog
      open={open}
      onClose={running ? undefined : onShut}
      onTransitionExited={onExited}
      fullWidth
      maxWidth="sm"
    >
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        {children}
        {refusal}
      </DialogContent>
      <DialogActions>
        <ActGroup>
          <Press
            fill
            reason={
              running ? { severity: "warning", words: underway } : undefined
            }
            tier="neutral"
            onPress={onShut}
          >
            {abandonLabel}
          </Press>
          <ActButton
            fill
            tier={tier ?? "primary"}
            action={action}
            act={act}
            reason={reason}
          >
            {actLabel}
          </ActButton>
        </ActGroup>
      </DialogActions>
    </Dialog>
  );
}
