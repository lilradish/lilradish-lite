import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { noticesIn } from "../../testutil/notices";
import { theme } from "../theme/theme";
import { Press, type Reason } from "./Press";

/** `theme.palette.action.disabled`, which `theme.ts` leaves at Material's own value. */
const DISABLED_COLOUR = "rgba(0, 0, 0, 0.26)";

const REASON: Reason = {
  severity: "warning",
  words: "Something else has to go first.",
};

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function pressing(unavailable: boolean) {
  const onPress = vi.fn();
  render(
    <Press unavailable={unavailable} onPress={onPress}>
      Remove
    </Press>,
    { wrapper: themed },
  );
  return { onPress, button: screen.getByRole("button", { name: "Remove" }) };
}

describe("Press", () => {
  it.each([
    ["led with", "contained" as const, true],
    ["drawn as text", undefined, false],
  ])(
    "is drawn filled only where it is the control a page is %s",
    (_where, variant, filled) => {
      render(
        <Press variant={variant} onPress={vi.fn()}>
          Remove
        </Press>,
        { wrapper: themed },
      );

      expect(
        screen
          .getByRole("button", { name: "Remove" })
          .classList.contains("MuiButton-contained"),
      ).toBe(filled);
    },
  );

  it("answers a press where nothing is said of its availability at all", async () => {
    const onPress = vi.fn();
    render(<Press onPress={onPress}>Remove</Press>, { wrapper: themed });

    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    expect(onPress).toHaveBeenCalledOnce();
  });

  it("answers a press with the one call it was given, where it is available", async () => {
    const { onPress, button } = pressing(false);

    await userEvent.click(button);

    expect(onPress).toHaveBeenCalledOnce();
    expect(button).not.toHaveAttribute("aria-disabled");
  });

  /**
   * Said with `aria-disabled`, never `disabled`: a disabled control is no
   * longer somewhere focus can be, which this environment does not act out, so
   * the attribute is what stands for the focus kept.
   */
  it("stays somewhere focus can be while it is unavailable", () => {
    const { button } = pressing(true);

    button.focus();

    expect(document.activeElement).toBe(button);
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).not.toBeDisabled();
    expect(button.tabIndex).toBe(0);
  });

  it("answers no press of any kind while it is unavailable", async () => {
    const { onPress, button } = pressing(true);
    button.focus();

    await userEvent.keyboard("{Enter}[Space]");
    // Drawn with `pointer-events: none`, which userEvent will not click
    // through; a reader's own pointer is under no such rule.
    fireEvent.click(button);

    expect(onPress).not.toHaveBeenCalled();
  });

  it("is drawn in the disabled colour while unavailable, and in its own otherwise", () => {
    render(
      <>
        <Press unavailable onPress={vi.fn()}>
          Remove
        </Press>
        <Press unavailable={false} onPress={vi.fn()}>
          Keep
        </Press>
      </>,
      { wrapper: themed },
    );

    const unavailable = getComputedStyle(
      screen.getByRole("button", { name: "Remove" }),
    );
    const available = getComputedStyle(
      screen.getByRole("button", { name: "Keep" }),
    );

    expect(unavailable.color).toBe(DISABLED_COLOUR);
    expect(unavailable.pointerEvents).toBe("none");
    expect(available.color).not.toBe(DISABLED_COLOUR);
    expect(available.pointerEvents).not.toBe("none");
  });

  /** A ripple is the look of a press taking effect, and on this button none does. */
  it.each([
    ["unavailable", true, false],
    ["available", false, true],
  ])(
    "answers Space with a ripple only where it is available, here %s",
    async (_case, unavailable, ripples) => {
      const { button } = pressing(unavailable);
      await userEvent.tab();

      await userEvent.keyboard("{ >}");
      // The ripple is drawn by an effect of the press, one tick after it.
      await act(async () => {});

      expect(document.activeElement).toBe(button);
      expect(button.querySelectorAll(".MuiTouchRipple-ripple").length > 0).toBe(
        ripples,
      );
    },
  );

  /** A control drawn and dead teaches nothing; the reason is what it teaches. */
  it("says its reason right under it, as its description, in the notice of the reason's severity", () => {
    const { container } = render(
      <Press unavailable reason={REASON} onPress={vi.fn()}>
        Remove
      </Press>,
      { wrapper: themed },
    );

    const button = screen.getByRole("button", { name: "Remove" });
    const reason = document.getElementById(
      button.getAttribute("aria-describedby")!,
    )!;

    expect(button).toHaveAccessibleDescription(REASON.words);
    expect(noticesIn(container)).toEqual([
      { severity: "warning", words: REASON.words },
    ]);
    expect(reason).toHaveTextContent(REASON.words);
    expect(button.contains(reason)).toBe(false);
    expect(reason.parentElement).toBe(button.parentElement);
    expect(
      button.compareDocumentPosition(reason) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
  });

  /** Described, never announced: nothing about holding a control is news to be read out. */
  it("announces nothing of its reason", () => {
    render(
      <Press unavailable reason={REASON} onPress={vi.fn()}>
        Remove
      </Press>,
      { wrapper: themed },
    );

    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
    expect(screen.getByText(REASON.words)).toBeVisible();
  });

  it("says nothing beside it, and is described by nothing, where it is given no reason", () => {
    const { container } = render(
      <Press unavailable onPress={vi.fn()}>
        Remove
      </Press>,
      { wrapper: themed },
    );

    expect(screen.getByRole("button", { name: "Remove" })).not.toHaveAttribute(
      "aria-describedby",
    );
    expect(container.textContent).toBe("Remove");
  });

  /** A reason for a button that can be pressed would be a word that is not true. */
  it("answers no press where it is given a reason, whatever it was told of its availability", () => {
    const onPress = vi.fn();
    render(
      <Press unavailable={false} reason={REASON} onPress={onPress}>
        Remove
      </Press>,
      { wrapper: themed },
    );
    const button = screen.getByRole("button", { name: "Remove" });

    fireEvent.click(button);

    expect(onPress).not.toHaveBeenCalled();
    expect(button).toHaveAttribute("aria-disabled", "true");
  });

  it("keeps the very button, and focus on it, as its reason comes and goes", () => {
    const onPress = vi.fn();
    const { rerender } = render(
      <Press unavailable reason={REASON} onPress={onPress}>
        Remove
      </Press>,
      { wrapper: themed },
    );
    const button = screen.getByRole("button", { name: "Remove" });
    button.focus();

    rerender(
      <Press unavailable={false} onPress={onPress}>
        Remove
      </Press>,
    );
    const withoutReason = screen.getByRole("button", { name: "Remove" });
    rerender(
      <Press unavailable reason={REASON} onPress={onPress}>
        Remove
      </Press>,
    );

    expect(withoutReason).toBe(button);
    expect(screen.getByRole("button", { name: "Remove" })).toBe(button);
    expect(document.activeElement).toBe(button);
    expect(screen.getByText(REASON.words)).toBeVisible();
  });

  it("goes by the name it is given where one is, its words still what is drawn", () => {
    render(
      <Press label="Remove Payroll" onPress={vi.fn()}>
        Remove
      </Press>,
      { wrapper: themed },
    );

    const button = screen.getByRole("button", { name: "Remove Payroll" });

    expect(button).toHaveTextContent("Remove");
    expect(screen.queryByRole("button", { name: "Remove" })).toBeNull();
  });
});
