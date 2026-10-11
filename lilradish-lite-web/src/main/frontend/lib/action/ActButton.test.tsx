import { buttonClasses } from "@mui/material/Button";
import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { deferred } from "../../testutil/deferred";
import { useAction, type Action } from "../request/useAction";
import { theme } from "../theme/theme";
import { ActButton } from "./ActButton";
import type { Reason } from "./Press";

const REASON: Reason = {
  severity: "warning",
  words: "Something else has to go first.",
};

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function stubbed(over: Partial<Action<unknown>> = {}): Action<unknown> {
  return { run: vi.fn(), problem: null, running: false, ...over };
}

/**
 * A fresh element on every render: React skips re-rendering a subtree handed
 * the very same element back, and a rerender that does nothing proves nothing.
 */
function button(asking: Action<unknown>, reason?: Reason, waiting?: boolean) {
  return (
    <ActButton
      action={asking}
      act={vi.fn(() => Promise.resolve(null))}
      waiting={waiting}
      reason={reason}
    >
      Remove
    </ActButton>
  );
}

/**
 * The real hook, because what this button is for is the three lines every
 * screen would otherwise write around one: a stub that answers `running` on
 * demand proves the unavailability without proving it ever comes about.
 */
function Harness({
  ask,
  settled,
}: {
  readonly ask: (signal: AbortSignal) => Promise<unknown>;
  readonly settled: (outcome: unknown) => void;
}) {
  return (
    <ActButton action={useAction(settled)} act={ask}>
      Approve
    </ActButton>
  );
}

describe("ActButton", () => {
  it("keeps concise visible words with a full accessible name and one shared reason", () => {
    render(
      <>
        <p id="held-step">{REASON.words}</p>
        <ActButton
          action={stubbed()}
          act={vi.fn()}
          label="Save the steps and what fills what the workflow gives back"
          reason={REASON}
          reasonDescribedBy="held-step"
        >
          Save
        </ActButton>
      </>,
      { wrapper: themed },
    );
    const button = screen.getByRole("button", {
      name: "Save the steps and what fills what the workflow gives back",
    });
    expect(button).toHaveTextContent(/^Save$/);
    expect(button).toHaveAccessibleDescription(REASON.words);
    expect(screen.getAllByText(REASON.words)).toHaveLength(1);
  });
  afterEach(() => vi.useRealTimers());

  it("shows busy only on the button pressed when siblings share one action, and clears it for a later sibling action", async () => {
    vi.useFakeTimers();
    const first = deferred<unknown>();
    const second = deferred<unknown>();
    const firstAct = vi.fn(() => first.promise);
    const secondAct = vi.fn(() => second.promise);
    function Shared() {
      const action = useAction(() => undefined);
      return (
        <>
          <ActButton action={action} act={firstAct} tier="primary">
            Save
          </ActButton>
          <ActButton action={action} act={secondAct}>
            Approve
          </ActButton>
        </>
      );
    }
    render(<Shared />, { wrapper: themed });
    const save = screen.getByRole("button", { name: "Save" });
    const approve = screen.getByRole("button", { name: "Approve" });
    await act(async () => {
      save.focus();
    });
    fireEvent.click(save);
    expect(save).not.toHaveClass(buttonClasses.disabled);
    expect(approve).toHaveClass(buttonClasses.disabled);
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(save).toHaveAccessibleName(/Save.*under way/);
    expect(approve.querySelector("[role='progressbar']")).toBeNull();
    fireEvent.click(approve);
    expect(secondAct).not.toHaveBeenCalled();
    await act(async () => first.settle(null));
    expect(save).toHaveAccessibleName("Save");
    expect(save).toHaveFocus();
    fireEvent.click(approve);
    expect(save).toHaveClass(buttonClasses.disabled);
    expect(approve).not.toHaveClass(buttonClasses.disabled);
    await act(() => vi.advanceTimersByTimeAsync(300));
    expect(approve).toHaveAccessibleName(/Approve.*under way/);
    expect(save.querySelector("[role='progressbar']")).toBeNull();
    await act(async () => second.settle(null));
    expect(approve).toHaveAccessibleName("Approve");
    expect(approve).not.toHaveAttribute("aria-disabled");
  });

  /**
   * The signal, the busy flag and the refusal all belong to the action; a
   * button that called the act itself would have none of them.
   */
  it("hands the act to the action rather than calling it itself", async () => {
    const asking = stubbed();
    const ask = vi.fn(() => Promise.resolve(null));
    render(
      <ActButton action={asking} act={ask}>
        Approve
      </ActButton>,
      { wrapper: themed },
    );

    await userEvent.click(screen.getByRole("button", { name: "Approve" }));

    expect(asking.run).toHaveBeenCalledExactlyOnceWith(ask);
    expect(ask).not.toHaveBeenCalled();
  });

  it("is available, with nothing said beside it, where no act is in flight and the caller gives no reason", () => {
    const { container } = render(button(stubbed()), { wrapper: themed });

    const remove = screen.getByRole("button", { name: "Remove" });

    expect(remove).not.toHaveAttribute("aria-disabled");
    expect(remove).not.toHaveAttribute("aria-describedby");
    expect(container.textContent).toBe("Remove");
  });

  it.each([
    [
      "while an act of its own is in flight",
      { running: true },
      undefined,
      false,
    ],
    ["for a reason of the caller's own", {}, REASON, false],
    ["for both at once", { running: true }, REASON, false],
    ["while something else the screen waits on is out", {}, undefined, true],
  ] as [string, Partial<Action<unknown>>, Reason | undefined, boolean][])(
    "answers no press, and keeps focus, %s",
    async (_case, over, reason, waiting) => {
      const asking = stubbed(over);
      render(button(asking, reason, waiting), { wrapper: themed });
      const remove = screen.getByRole("button", { name: "Remove" });
      await act(async () => {
        remove.focus();
      });

      await userEvent.keyboard("{Enter}[Space]");
      // Drawn with `pointer-events: none`, which userEvent will not click
      // through; a reader's own pointer is under no such rule.
      fireEvent.click(remove);

      expect(asking.run).not.toHaveBeenCalled();
      expect(document.activeElement).toBe(remove);
      expect(remove).toHaveAttribute("aria-disabled", "true");
      expect(remove).not.toBeDisabled();
    },
  );

  /** How a reason is drawn is `Press`'s to say; here, only that the caller's reaches it. */
  it("says why it cannot be pressed, as its description", () => {
    render(button(stubbed(), REASON), { wrapper: themed });

    expect(
      screen.getByRole("button", { name: "Remove" }),
    ).toHaveAccessibleDescription(REASON.words);
    expect(screen.getByText(REASON.words)).toBeVisible();
  });

  /** In flight is no reason of the reader's to act on, and it is over in a moment. */
  it("says nothing beside it while its own act is in flight", () => {
    const { container } = render(button(stubbed({ running: true })), {
      wrapper: themed,
    });

    expect(screen.getByRole("button", { name: "Remove" })).not.toHaveAttribute(
      "aria-describedby",
    );
    expect(container.textContent).toBe("Remove");
  });

  it("is drawn in the warning colour only where it warns", () => {
    render(
      <>
        <ActButton
          action={stubbed()}
          act={vi.fn(() => Promise.resolve(null))}
          tier="danger"
        >
          Remove
        </ActButton>
        <ActButton action={stubbed()} act={vi.fn(() => Promise.resolve(null))}>
          Give
        </ActButton>
      </>,
      { wrapper: themed },
    );

    expect(screen.getByRole("button", { name: "Remove" })).toHaveClass(
      buttonClasses.colorWarning,
    );
    expect(screen.getByRole("button", { name: "Give" })).not.toHaveClass(
      buttonClasses.colorWarning,
    );
  });

  it("is drawn filled only where it leads", () => {
    render(
      <>
        <ActButton
          action={stubbed()}
          act={vi.fn(() => Promise.resolve(null))}
          tier="primary"
        >
          Approve
        </ActButton>
        <ActButton action={stubbed()} act={vi.fn(() => Promise.resolve(null))}>
          Withdraw
        </ActButton>
      </>,
      { wrapper: themed },
    );

    const [leading, other] = ["Approve", "Withdraw"].map((name) =>
      screen.getByRole("button", { name }),
    );

    expect(leading).toHaveClass(buttonClasses.contained);
    expect(leading).not.toHaveClass(buttonClasses.outlined);
    expect(other).toHaveClass(buttonClasses.outlined);
    expect(other).not.toHaveClass(buttonClasses.contained);
  });

  /** The click somebody repeats because nothing appeared to happen. */
  it("runs the act once and is unavailable until that act settles", async () => {
    const answer = deferred<unknown>();
    const ask = vi.fn(() => answer.promise);
    const settled = vi.fn();
    render(<Harness ask={ask} settled={settled} />, { wrapper: themed });
    const approve = screen.getByRole("button", { name: "Approve" });

    await userEvent.click(approve);

    expect(ask).toHaveBeenCalledTimes(1);
    expect(approve).toHaveAttribute("aria-disabled", "true");
    expect(approve).not.toBeDisabled();

    fireEvent.click(approve);

    expect(ask).toHaveBeenCalledTimes(1);

    await act(async () => {
      answer.settle({ decided: true });
    });

    expect(settled).toHaveBeenCalledExactlyOnceWith({ decided: true });
    expect(approve).not.toHaveAttribute("aria-disabled");
  });
});
