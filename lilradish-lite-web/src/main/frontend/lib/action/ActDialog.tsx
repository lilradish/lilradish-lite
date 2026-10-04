import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import type { ReactNode } from "react";

import type { Action } from "../request/useAction";
import { ActButton } from "./ActButton";
import { Press, type Reason } from "./Press";

/**
 * A dialog over the page asking for one act, beside a control that abandons it.
 * Once the act is out it cannot be abandoned — by that control, Escape or the
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
  warns,
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
  readonly warns?: boolean;
  /** Why the act cannot be pressed yet; absent where it can. */
  readonly reason?: Reason;
  readonly abandonLabel: string;
  /** Said by the abandon control while the act is out. */
  readonly underway: string;
  /** A refusal of what this opening asked, in the caller's words, under what it asks for. */
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
        <Press
          reason={
            running ? { severity: "warning", words: underway } : undefined
          }
          variant="neutral"
          onPress={onShut}
        >
          {abandonLabel}
        </Press>
        <ActButton
          action={action}
          act={act}
          variant="contained"
          warns={warns}
          reason={reason}
        >
          {actLabel}
        </ActButton>
      </DialogActions>
    </Dialog>
  );
}
