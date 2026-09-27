import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen, waitFor } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { deferred } from "../../testutil/deferred";
import { noticesIn } from "../../testutil/notices";
import { useAction } from "../request/useAction";
import { theme } from "../theme/theme";
import { ActDialog } from "./ActDialog";
import type { Reason } from "./Press";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The dialog open over a real action, the act settling when the test says, shut as a holder shuts it. */
function asking(reason?: Reason) {
  const answer = deferred<string>();
  const settled = vi.fn();
  const act = vi.fn(() => answer.promise);
  function Holder() {
    const [open, setOpen] = useState(true);
    const action = useAction<string>(settled);
    return (
      <ActDialog
        open={open}
        title="Do the thing"
        onShut={() => setOpen(false)}
        action={action}
        act={act}
        actLabel="Do it"
        reason={reason}
        abandonLabel="Cancel"
        underway="It is being done."
        refusal={<p>Refused here.</p>}
      >
        <p>What it asks for.</p>
      </ActDialog>
    );
  }
  render(<Holder />, { wrapper: themed });
  return { answer, settled, act };
}

describe("ActDialog", () => {
  it("holds its title, what it asks for, and the refusal under it", () => {
    asking();

    const dialog = screen.getByRole("dialog", { name: "Do the thing" });

    expect(dialog).toHaveTextContent("What it asks for.Refused here.");
  });

  it("runs the act when it is pressed, settling under the action it was handed", async () => {
    const { answer, settled, act: acted } = asking();

    await userEvent.click(screen.getByRole("button", { name: "Do it" }));
    await act(async () => answer.settle("done"));

    await waitFor(() =>
      expect(settled).toHaveBeenCalledExactlyOnceWith("done"),
    );
    expect(acted).toHaveBeenCalledOnce();
  });

  it("holds the act for the reason given, saying it under the control and running nothing", async () => {
    const { act: acted } = asking({ severity: "info", words: "Type first." });
    const control = screen.getByRole("button", { name: "Do it" });

    control.focus();
    await userEvent.keyboard("{Enter}[Space]");

    expect(control).toHaveAttribute("aria-disabled", "true");
    expect(control).toHaveAccessibleDescription("Type first.");
    expect(acted).not.toHaveBeenCalled();
  });

  it("shuts from its abandon control while nothing is out, running nothing", async () => {
    const { act: acted } = asking();

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(acted).not.toHaveBeenCalled();
  });

  it("cannot be abandoned while the act is out, its abandon control saying why", async () => {
    asking();
    await userEvent.click(screen.getByRole("button", { name: "Do it" }));
    const abandon = screen.getByRole("button", { name: "Cancel" });

    abandon.focus();
    await userEvent.keyboard("{Enter}{Escape}");

    expect(
      screen.getByRole("dialog", { name: "Do the thing" }),
    ).toBeInTheDocument();
    expect(noticesIn(abandon.parentElement!)).toEqual([
      { severity: "warning", words: "It is being done." },
    ]);
  });
});
