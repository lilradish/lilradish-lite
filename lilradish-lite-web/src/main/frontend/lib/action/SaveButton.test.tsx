import { ThemeProvider } from "@mui/material/styles";
import { act, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { deferred } from "../../testutil/deferred";
import { useAction } from "../request/useAction";
import { theme } from "../theme/theme";
import { SaveButton } from "./SaveButton";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function Harness({
  unsaved,
  ask,
  waiting,
}: {
  readonly unsaved: boolean;
  readonly ask: (signal: AbortSignal) => Promise<string>;
  readonly waiting?: boolean;
}) {
  return (
    <SaveButton
      unsaved={unsaved}
      action={useAction<string>(() => {})}
      act={ask}
      waiting={waiting}
    >
      Save the note
    </SaveButton>
  );
}

function saveOrNone(): HTMLElement | null {
  return screen.queryByRole("button", { name: "Save the note" });
}

describe("SaveButton", () => {
  it("passes a full accessible name while keeping Save visible", () => {
    function NamedSave() {
      return (
        <SaveButton
          unsaved
          action={useAction<string>(() => {})}
          act={() => Promise.resolve("saved")}
          label="Save the steps"
        >
          Save
        </SaveButton>
      );
    }
    render(<NamedSave />, { wrapper: themed });
    expect(
      screen.getByRole("button", { name: "Save the steps" }),
    ).toHaveTextContent(/^Save$/);
  });
  it("draws nothing while nothing is unsaved and no write is out", () => {
    const ask = vi.fn(() => Promise.resolve("saved"));

    render(<Harness unsaved={false} ask={ask} />, { wrapper: themed });

    expect(screen.queryAllByRole("button")).toEqual([]);
    expect(ask).not.toHaveBeenCalled();
  });

  it("draws the leading button while something is unsaved, and sends one write when pressed", async () => {
    const ask = vi.fn(() => Promise.resolve("saved"));
    render(<Harness unsaved ask={ask} />, { wrapper: themed });

    await userEvent.click(saveOrNone()!);

    expect(ask).toHaveBeenCalledTimes(1);
    expect(saveOrNone()).toHaveClass("MuiButton-contained");
    expect(saveOrNone()).not.toHaveAttribute("aria-disabled");
  });

  it("stays drawn and unavailable while its write is out once nothing is unsaved, and goes once it is answered", async () => {
    const answer = deferred<string>();
    const ask = vi.fn(() => answer.promise);
    const { rerender } = render(<Harness unsaved ask={ask} />, {
      wrapper: themed,
    });
    await userEvent.click(saveOrNone()!);

    rerender(<Harness unsaved={false} ask={ask} />);

    expect(saveOrNone()).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(saveOrNone()!);
    expect(ask).toHaveBeenCalledTimes(1);
    await act(async () => answer.settle("saved"));
    expect(saveOrNone()).toBeNull();
  });

  it("holds while another write the host waits on is out, sending nothing", async () => {
    const ask = vi.fn(() => Promise.resolve("saved"));
    render(<Harness unsaved ask={ask} waiting />, { wrapper: themed });

    saveOrNone()!.focus();
    await userEvent.keyboard("{Enter}");

    expect(saveOrNone()).toHaveAttribute("aria-disabled", "true");
    expect(ask).not.toHaveBeenCalled();
  });
});
