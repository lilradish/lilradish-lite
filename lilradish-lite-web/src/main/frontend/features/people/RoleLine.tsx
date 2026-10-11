import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { useLayoutEffect, useRef, type RefObject } from "react";

import { say } from "../../i18n/app";
import { ActButton } from "../../lib/action/ActButton";
import type { Action } from "../../lib/request/useAction";

const ROLE_SX = {
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: 1,
  py: 0.5,
};

const ROLE_NAME_SX = { flexGrow: 1 };

const QUIET_SX = { color: "text.secondary" };

/**
 * A role whose change answers it as no longer offered takes the focused
 * control with it; the keyboard then goes to `heading`, not the page's top.
 */
export function useKeyboardKeptAfterChange<Role>(
  heading: RefObject<HTMLElement | null>,
  running: boolean,
  offered: (role: Role) => boolean,
): (role: Role) => void {
  const asked = useRef<{ readonly role: Role } | null>(null);
  useLayoutEffect(() => {
    if (asked.current === null || running) return;
    const { role } = asked.current;
    asked.current = null;
    if (!offered(role) && document.activeElement === document.body) {
      heading.current?.focus();
    }
  });
  return (role) => {
    asked.current = { role };
  };
}

/** Withdrawing warns; granting undoes nothing and is drawn as any other. */
export function RoleLine<T>({
  said,
  holds,
  change,
}: {
  readonly said: string;
  readonly holds: boolean;
  readonly change: {
    readonly action: Action<T>;
    /** Something else the reader can see is out, which holds the control too. */
    readonly waiting: boolean;
    readonly act: (signal: AbortSignal) => Promise<T>;
  } | null;
}) {
  return (
    <Box component="li" sx={ROLE_SX}>
      <Box component="span" sx={ROLE_NAME_SX}>
        <bdi>{said}</bdi>
      </Box>
      <Typography component="span" variant="body2" sx={QUIET_SX}>
        {say(holds ? "person.holds" : "person.holdsNot")}
      </Typography>
      {change === null ? null : (
        // One element whichever way it points, so the control just pressed is
        // the one that still has the keyboard after.
        <ActButton
          action={change.action}
          tier={holds ? "danger" : "secondary"}
          small
          waiting={change.waiting}
          act={change.act}
        >
          {say(holds ? "person.withdraw" : "person.grant", { role: said })}
        </ActButton>
      )}
    </Box>
  );
}
