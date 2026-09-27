import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { Check, Checks } from "./Checks";

/** `theme.palette.success.main` and `error.main`, as resolved colours are reported back. */
const VERDICT_COLOUR = { pass: "rgb(27, 94, 32)", fail: "rgb(140, 29, 24)" };

/**
 * Under the real theme, because `sx` is not type-checked against the property
 * it is written on: an unresolved palette path is emitted verbatim as invalid
 * CSS and dropped, and no rendered text can show that it went.
 */
function themed({ children }: { children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

describe("Checks", () => {
  /**
   * `list-style: none` reads to some engines as a list that is decorative, and
   * they stop announcing how many items it has. The explicit role puts the
   * semantics back — and looks like dead code to anyone who does not know that.
   */
  it("keeps the list role its own styling would otherwise cost it", () => {
    render(
      <Checks>
        <Check name="database" state="pass" />
      </Checks>,
      { wrapper: themed },
    );

    expect(screen.getByRole("list").tagName).toBe("UL");
    expect(screen.getAllByRole("listitem")).toHaveLength(1);
  });

  it("holds every row it is given, in the order they were given", () => {
    render(
      <Checks>
        <Check name="database" state="pass" />
        <Check name="object-store" state="fail" />
      </Checks>,
      { wrapper: themed },
    );

    const rows = screen.getAllByRole("listitem").map((row) => row.textContent);

    expect(rows).toEqual(["passdatabase", "failobject-store"]);
  });
});

describe("Check", () => {
  /**
   * WCAG 1.4.1: "Color is not used as the only visual means of conveying
   * information". The verdict is legible as text, so a reader who cannot tell
   * the two colours apart still reads the verdict.
   */
  it.each([
    ["pass" as const, "pass"],
    ["fail" as const, "fail"],
  ])(
    "spells the %s verdict out rather than only colouring it",
    (state, said) => {
      render(
        <Checks>
          <Check name="database" state={state} />
        </Checks>,
        { wrapper: themed },
      );

      expect(screen.getByText(said)).toBeVisible();
      expect(getComputedStyle(screen.getByText(said)).color).toBe(
        VERDICT_COLOUR[state],
      );
    },
  );

  it("shows no verdict at all where the row is a reading", () => {
    render(
      <Checks>
        <Check name="queue-depth">{42}</Check>
      </Checks>,
      { wrapper: themed },
    );

    const row = screen.getByRole("listitem");

    expect(row.textContent).toBe("queue-depth42");
    expect(screen.queryByText("pass")).toBeNull();
    expect(screen.queryByText("fail")).toBeNull();
  });

  /**
   * Zero is a reading like any other. A truthiness check on children would
   * drop it, and the separator is a margin rather than a written space so that
   * no condition stands between a reading and the page.
   */
  it("renders a reading of zero, which a truthiness check would swallow", () => {
    render(
      <Checks>
        <Check name="failures">{0}</Check>
      </Checks>,
      { wrapper: themed },
    );

    expect(screen.getByRole("listitem").textContent).toBe("failures0");
  });
});
