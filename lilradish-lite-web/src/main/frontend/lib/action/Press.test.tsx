import { buttonClasses } from "@mui/material/Button";
import Box from "@mui/material/Box";
import { ThemeProvider } from "@mui/material/styles";
import { typographyClasses } from "@mui/material/Typography";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { noticesIn } from "../../testutil/notices";
import { isolatedInText } from "../direction/isolated";
import { theme } from "../theme/theme";
import { Press, type Reason } from "./Press";

/** `theme.palette.action.disabled`, which `theme.ts` leaves at Material's own value. */
const DISABLED_COLOUR = "rgba(0, 0, 0, 0.26)";

/** `theme.palette.text.secondary`, as a resolved colour is reported back. */
const SUPPORTING_TEXT_COLOUR = "rgb(89, 89, 89)";

const REASON: Reason = {
  severity: "warning",
  words: "Something else has to go first.",
};

const NEXT_STEP: Reason = { severity: "info", words: "Type a name first." };

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
    [
      "filled, where it is the control a page is led with",
      "contained" as const,
      buttonClasses.contained,
    ],
    [
      "with a neutral frame, where it abandons an act",
      "neutral" as const,
      buttonClasses.outlined,
    ],
    ["outlined, where it is neither", undefined, buttonClasses.outlined],
  ])("is drawn %s", (_where, variant, drawnAs) => {
    render(
      <Press variant={variant} onPress={vi.fn()}>
        Remove
      </Press>,
      { wrapper: themed },
    );

    const button = screen.getByRole("button", { name: "Remove" });

    expect(button).toHaveClass(drawnAs);
    expect(
      [
        buttonClasses.contained,
        buttonClasses.text,
        buttonClasses.outlined,
      ].filter((each) => button.classList.contains(each)),
    ).toEqual([drawnAs]);
  });

  it("keeps a neutral secondary act distinct from a compact navigation act and a warning act", () => {
    render(
      <>
        <Press variant="neutral" onPress={vi.fn()}>
          Cancel
        </Press>
        <Press navigation onPress={vi.fn()}>
          Next
        </Press>
        <Press variant="neutral" warns onPress={vi.fn()}>
          Remove
        </Press>
      </>,
      { wrapper: themed },
    );

    const cancel = screen.getByRole("button", { name: "Cancel" });
    const next = screen.getByRole("button", { name: "Next" });
    const remove = screen.getByRole("button", { name: "Remove" });

    expect(cancel).toHaveClass(
      buttonClasses.outlined,
      buttonClasses.colorInherit,
    );
    expect(cancel).not.toHaveClass(buttonClasses.sizeSmall);
    expect(getComputedStyle(cancel).minHeight).toBe("40px");
    expect(getComputedStyle(cancel).color).toBe("rgb(82, 101, 121)");
    expect(getComputedStyle(cancel).borderColor).toBe("rgb(82, 101, 121)");
    expect(next).toHaveClass(
      buttonClasses.sizeSmall,
      buttonClasses.colorInherit,
    );
    expect(getComputedStyle(next).minHeight).toBe("32px");
    expect(remove).toHaveClass(buttonClasses.colorWarning);
    expect(remove).not.toHaveClass(buttonClasses.colorInherit);
  });

  it("answers a press where nothing is said of its availability at all", async () => {
    const onPress = vi.fn();
    render(<Press onPress={onPress}>Remove</Press>, { wrapper: themed });

    await userEvent.click(screen.getByRole("button", { name: "Remove" }));

    expect(onPress).toHaveBeenCalledOnce();
  });

  it("keeps a compact navigation act distinct and focusable when held", () => {
    render(
      <>
        <Press navigation unavailable onPress={vi.fn()}>
          First
        </Press>
        <Press onPress={vi.fn()}>Rename</Press>
      </>,
      { wrapper: themed },
    );

    const first = screen.getByRole("button", { name: "First" });
    const rename = screen.getByRole("button", { name: "Rename" });
    first.focus();

    expect(first).toHaveClass(buttonClasses.sizeSmall);
    expect(first).toHaveClass(buttonClasses.colorInherit);
    expect(getComputedStyle(first).minHeight).toBe("32px");
    expect(getComputedStyle(rename).minHeight).toBe("40px");
    expect(first).toHaveAttribute("aria-disabled", "true");
    expect(first).toHaveFocus();
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

  /** Whatever it warns of, a held control looks held: the warning is for what a press would do. */
  it("is drawn in the warning colour where it warns, in the primary colour where it does not, and held as any other", () => {
    render(
      <>
        <Press warns onPress={vi.fn()}>
          Remove
        </Press>
        <Press onPress={vi.fn()}>Keep</Press>
        <Press warns unavailable onPress={vi.fn()}>
          Retire
        </Press>
      </>,
      { wrapper: themed },
    );

    const [warning, other, held] = ["Remove", "Keep", "Retire"].map((name) =>
      screen.getByRole("button", { name }),
    );

    expect(warning).toHaveClass(buttonClasses.colorWarning);
    expect(warning).not.toHaveClass(buttonClasses.colorPrimary);
    expect(other).toHaveClass(buttonClasses.colorPrimary);
    expect(other).not.toHaveClass(buttonClasses.colorWarning);
    expect(getComputedStyle(held!).color).toBe(DISABLED_COLOUR);
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
  it("says a warning right under it, as its description, in a warning notice", () => {
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

  /** What is still to be done before it is no news: a notice for it would outweigh the control it explains. */
  it("says a reason of information right under it, as its description, as a quiet line in no notice", () => {
    const { container } = render(
      <Press reason={NEXT_STEP} onPress={vi.fn()}>
        Save
      </Press>,
      { wrapper: themed },
    );

    const button = screen.getByRole("button", { name: "Save" });
    const reason = document.getElementById(
      button.getAttribute("aria-describedby")!,
    )!;

    expect(button).toHaveAccessibleDescription(NEXT_STEP.words);
    expect(reason).toHaveTextContent(NEXT_STEP.words);
    expect(getComputedStyle(reason).color).toBe(SUPPORTING_TEXT_COLOUR);
    expect(reason).toHaveClass(typographyClasses.body2);
    expect(noticesIn(container)).toEqual([]);
    expect(reason.parentElement).toBe(button.parentElement);
    expect(
      button.compareDocumentPosition(reason) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).not.toBe(0);
  });

  /** Described, never announced: nothing about holding a control is news to be read out. */
  it.each([REASON, NEXT_STEP])(
    "announces nothing of its reason, $words",
    (reason) => {
      render(
        <Press unavailable reason={reason} onPress={vi.fn()}>
          Remove
        </Press>,
        { wrapper: themed },
      );

      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByRole("status")).toBeNull();
      expect(screen.getByText(reason.words)).toBeVisible();
    },
  );

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

  it.each(["info", "warning"] as const)(
    "draws a %s reason as nodes while retaining its complete description and held focus",
    async (severity) => {
      const onPress = vi.fn();
      const words = `${isolatedInText("Demo Sample")}, not named yet`;
      const reason: Reason = {
        severity,
        words,
        visible: (
          <>
            <bdi>Demo Sample</bdi>
            {", "}
            <Box component="span" sx={{ typography: "systemHint" }}>
              not named yet
            </Box>
          </>
        ),
      };
      const { rerender, container } = render(
        <Press unavailable={false} reason={reason} onPress={onPress}>
          Save
        </Press>,
        { wrapper: themed },
      );
      const button = screen.getByRole("button", { name: "Save" });
      button.focus();

      await userEvent.keyboard("{Enter}[Space]");
      fireEvent.click(button);

      expect(button).toHaveAccessibleDescription(words);
      expect(
        document.getElementById(button.getAttribute("aria-describedby")!),
      ).not.toBeVisible();
      expect(button).toHaveAttribute("aria-disabled", "true");
      expect(button).not.toBeDisabled();
      expect(button).toHaveFocus();
      expect(onPress).not.toHaveBeenCalled();
      expect(screen.getByText("Demo Sample").tagName).toBe("BDI");
      const hint = getComputedStyle(screen.getByText("not named yet"));
      expect(hint.fontStyle).toBe("italic");
      expect(hint.fontWeight).toBe("400");
      expect(noticesIn(container)).toEqual(
        severity === "warning"
          ? [{ severity, words: "Demo Sample, not named yet" }]
          : [],
      );
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByRole("status")).toBeNull();

      rerender(
        <Press reason={{ severity, words }} onPress={onPress}>
          Save
        </Press>,
      );

      expect(screen.getByRole("button", { name: "Save" })).toBe(button);
      expect(button).toHaveFocus();
      expect(button).toHaveAccessibleDescription(words);
      expect(container.querySelector("bdi")).toBeNull();
    },
  );

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
