import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { SEVERITIES } from "../../testutil/notices";
import { theme } from "../theme/theme";
import { Notice } from "./Notice";

function themed({ children }: { readonly children: ReactNode }) {
  return <ThemeProvider theme={theme}>{children}</ThemeProvider>;
}

/** The notice around the words it holds. */
function noticeOf(words: string): HTMLElement {
  return screen
    .getByText(words)
    .closest<HTMLElement>('[role="none"], [role="alert"]')!;
}

describe("Notice", () => {
  /**
   * The severity is what it looks like: its own colours, and an icon beside
   * the words that is decoration to a screen reader, the words being what is
   * read.
   */
  it("draws each severity in colours of its own, with an icon that is not read", () => {
    render(
      <>
        {SEVERITIES.map((severity) => (
          <Notice key={severity} severity={severity}>
            {`Said as ${severity}.`}
          </Notice>
        ))}
      </>,
      { wrapper: themed },
    );

    const notices = SEVERITIES.map((severity) =>
      noticeOf(`Said as ${severity}.`),
    );
    const grounds = notices.map(
      (notice) => getComputedStyle(notice).backgroundColor,
    );

    expect(new Set(grounds).size).toBe(SEVERITIES.length);
    for (const notice of notices) {
      const icon = notice.querySelector("svg");
      expect(icon).not.toBeNull();
      expect(icon).toHaveAttribute("aria-hidden", "true");
    }
  });

  /** A hint is described or read in place; it is not news to interrupt anyone with. */
  it("announces nothing unless asked to", () => {
    render(<Notice severity="info">Pick somebody first.</Notice>, {
      wrapper: themed,
    });

    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
    expect(noticeOf("Pick somebody first.")).toHaveAttribute("role", "none");
  });

  it("announces itself as an alert, and as nothing else, where it is asked to", () => {
    render(
      <Notice severity="error" alert>
        That was refused.
      </Notice>,
      { wrapper: themed },
    );

    expect(screen.getByRole("alert")).toHaveTextContent("That was refused.");
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("carries the id a control is described by", () => {
    render(
      <>
        <Notice severity="info" id="why">
          Pick somebody first.
        </Notice>
        <button aria-describedby="why">Bring into the pool</button>
      </>,
      { wrapper: themed },
    );

    expect(screen.getByRole("button")).toHaveAccessibleDescription(
      "Pick somebody first.",
    );
  });
});
