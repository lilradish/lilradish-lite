import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import { iconButtonClasses } from "@mui/material/IconButton";
import { ThemeProvider } from "@mui/material/styles";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { IconPress } from "./IconPress";

const DISABLED_COLOUR = "rgb(128, 143, 150)";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

function pressing(unavailable: boolean, words = "Move up") {
  const onPress = vi.fn();
  render(
    <IconPress
      icon={<ArrowUpwardIcon />}
      label={`${words}: Payroll`}
      unavailable={unavailable}
      onPress={onPress}
    >
      {words}
    </IconPress>,
    { wrapper: themed },
  );
  return {
    onPress,
    button: screen.getByRole("button", { name: `${words}: Payroll` }),
  };
}

describe("IconPress", () => {
  it.each(["Move up", "Next", "Previous"])(
    "keeps %s in the compact icon tier",
    (words) => {
      const { button } = pressing(false, words);
      expect(button.classList.contains(iconButtonClasses.colorInherit)).toBe(
        false,
      );
      expect(button.classList.contains(iconButtonClasses.colorWarning)).toBe(
        false,
      );
      expect(button).toHaveClass(iconButtonClasses.sizeSmall);
    },
  );
  it("keeps an icon act in its ordinary tier while held", async () => {
    const { button, onPress } = pressing(true);
    expect(button).not.toHaveClass(iconButtonClasses.colorWarning);
    expect(button).not.toHaveClass(iconButtonClasses.colorInherit);
    await userEvent.click(button);
    expect(onPress).not.toHaveBeenCalled();
    expect(button).toHaveAttribute("aria-disabled", "true");
  });

  it("draws its icon alone, small, hidden from assistive technology, and goes by the name it is given", () => {
    const { button } = pressing(false);

    expect(button).toHaveClass(iconButtonClasses.sizeSmall);
    expect(button).not.toHaveClass(iconButtonClasses.sizeMedium);
    expect(button.textContent).toBe("");
    expect(button.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByRole("button", { name: "Move up" })).toBeNull();
  });

  /** Seen only as an icon, its words are what a reader who cannot read the icon is left to go by. */
  it("says its words as a tip once it holds the keyboard", async () => {
    pressing(false);

    await userEvent.tab();

    expect((await screen.findByRole("tooltip")).textContent).toBe("Move up");
  });

  /** Drawn held, it still says what it is to a pointer: why it is held is read from its words. */
  it("says its words as a tip once pointed at while it is unavailable", async () => {
    const { button } = pressing(true);

    await userEvent.hover(button);

    expect((await screen.findByRole("tooltip")).textContent).toBe("Move up");
    expect(getComputedStyle(button).pointerEvents).toBe("auto");
  });

  /** A ripple answers a press, so one under a press it ignores would say the press was taken. */
  it.each([
    ["ripples under a press where it is available", false, 1],
    ["ripples under no press while it is unavailable", true, 0],
  ])("%s", async (_case, unavailable, ripples) => {
    const { button } = pressing(unavailable);

    await act(async () => {
      fireEvent.mouseDown(button);
    });

    expect(button.querySelectorAll(".MuiTouchRipple-ripple")).toHaveLength(
      ripples,
    );
  });

  it("answers a press with the one call it was given, where it is available", async () => {
    const { onPress, button } = pressing(false);

    await userEvent.click(button);

    expect(onPress).toHaveBeenCalledOnce();
    expect(button).not.toHaveAttribute("aria-disabled");
  });

  it("stays where focus can be while it is unavailable, drawn held, and answers no press", async () => {
    const { onPress, button } = pressing(true);
    button.focus();

    await userEvent.keyboard("{Enter}[Space]");
    fireEvent.click(button);

    expect(document.activeElement).toBe(button);
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).not.toBeDisabled();
    expect(getComputedStyle(button).color).toBe(DISABLED_COLOUR);
    expect(onPress).not.toHaveBeenCalled();
  });

  it.each(["Delete", "Move up"])(
    "keeps the %s draft icon in its ordinary colour",
    (words) => {
      const { button } = pressing(false, words);

      expect(button.classList.contains(iconButtonClasses.colorWarning)).toBe(
        false,
      );
      expect(getComputedStyle(button).color).toBe("rgb(88, 102, 108)");
    },
  );
});
