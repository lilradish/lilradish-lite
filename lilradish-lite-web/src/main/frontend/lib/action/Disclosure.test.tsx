import Typography from "@mui/material/Typography";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { useState, type ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { theme } from "../theme/theme";
import { Disclosure } from "./Disclosure";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** Words heading what they open, in a heading's face, over a host holding whether it is open as a caller does. */
function heading(openAtFirst: boolean) {
  const toggled = vi.fn();
  function Host() {
    const [open, setOpen] = useState(openAtFirst);
    return (
      <>
        <Typography variant="h2">
          <Disclosure
            open={open}
            controls="held"
            onToggle={() => {
              toggled();
              setOpen((was) => !was);
            }}
          >
            Versions
          </Disclosure>
        </Typography>
        <div id="held">{open ? "What it holds" : null}</div>
      </>
    );
  }
  render(<Host />, { wrapper: themed });
  return {
    toggled,
    button: screen.getByRole("button", { name: "Versions" }),
  };
}

/** Which of the two marks it shows, opening or shutting. */
function markOf(button: HTMLElement): string | null {
  return button.querySelector("svg")?.getAttribute("data-testid") ?? null;
}

describe("Disclosure", () => {
  it.each([
    ["open", true, "true", "ExpandLessIcon"],
    ["shut", false, "false", "ExpandMoreIcon"],
  ])(
    "says it is %s, names what it controls, and shows the mark for what a press does",
    (_state, open, expanded, mark) => {
      const { button } = heading(open);

      expect(button).toHaveAttribute("aria-expanded", expanded);
      expect(button).toHaveAttribute("aria-controls", "held");
      expect(markOf(button)).toBe(mark);
      expect(button.querySelectorAll("svg")).toHaveLength(1);
      expect(button.querySelector("svg")).toHaveAttribute(
        "aria-hidden",
        "true",
      );
      expect(button).toHaveTextContent(/^Versions$/);
    },
  );

  /** It reads as the words it stands in: in their face, where they would start. */
  it("sets its words in the face and colour of what it stands in, with no room of a button's around them", () => {
    const { button } = heading(true);

    const around = getComputedStyle(
      screen.getByRole("heading", { name: "Versions" }),
    );
    const drawn = getComputedStyle(button);

    expect([
      drawn.fontFamily,
      drawn.fontSize,
      drawn.fontWeight,
      drawn.color,
    ]).toEqual([
      around.fontFamily,
      around.fontSize,
      around.fontWeight,
      around.color,
    ]);
    expect([
      drawn.paddingTop,
      drawn.paddingRight,
      drawn.paddingBottom,
      drawn.paddingLeft,
      drawn.minWidth,
    ]).toEqual(["0px", "0px", "0px", "0px", "0px"]);
  });

  it.each([
    ["a pointer", (button: HTMLElement) => userEvent.click(button)],
    [
      "Enter",
      async (button: HTMLElement) => {
        button.focus();
        await userEvent.keyboard("{Enter}");
      },
    ],
    [
      "Space",
      async (button: HTMLElement) => {
        button.focus();
        await userEvent.keyboard(" ");
      },
    ],
  ])(
    "shuts what it opened on a press by %s, the keyboard left on it",
    async (_by, press) => {
      const { toggled, button } = heading(true);

      await press(button);

      expect(toggled).toHaveBeenCalledOnce();
      expect(button).toHaveAttribute("aria-expanded", "false");
      expect(markOf(button)).toBe("ExpandMoreIcon");
      expect(document.getElementById("held")).toBeEmptyDOMElement();
      expect(document.activeElement).toBe(button);
    },
  );

  it("opens what it held shut on a press, the keyboard left on it", async () => {
    const { toggled, button } = heading(false);
    button.focus();

    await userEvent.keyboard("{Enter}");

    expect(toggled).toHaveBeenCalledOnce();
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(markOf(button)).toBe("ExpandLessIcon");
    expect(document.getElementById("held")).toHaveTextContent(
      /^What it holds$/,
    );
    expect(document.activeElement).toBe(button);
  });
  it("forwards an optional description and focus tracking without changing its toggle", async () => {
    const focused = vi.fn();
    const blurred = vi.fn();
    render(
      <ThemeProvider theme={theme}>
        <p id="context">This step</p>
        <Disclosure
          open={false}
          controls="step-account"
          describedBy="context"
          onFocus={focused}
          onBlur={blurred}
          onToggle={() => undefined}
        >
          Waiting
        </Disclosure>
        <div id="step-account" />
      </ThemeProvider>,
    );
    const control = screen.getByRole("button", { name: "Waiting" });

    expect(control).toHaveAccessibleDescription("This step");
    await userEvent.click(control);
    expect(focused).toHaveBeenCalledOnce();
    control.blur();
    expect(blurred).toHaveBeenCalledOnce();
  });
});
