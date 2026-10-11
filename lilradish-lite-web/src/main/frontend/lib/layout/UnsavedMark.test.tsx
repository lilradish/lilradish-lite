import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { UnsavedMark } from "./UnsavedMark";

describe("UnsavedMark", () => {
  it.each([false, true])(
    "keeps the state in words and decorates it with a dot, compact %s",
    (compact) => {
      const { container } = render(
        <ThemeProvider theme={theme}>
          <UnsavedMark compact={compact} />
        </ThemeProvider>,
      );
      const text = screen.getByText("Unsaved");
      const mark = container.firstElementChild!;
      const dot = mark.firstElementChild!;
      expect(dot).toHaveAttribute("aria-hidden", "true");
      expect(dot.textContent).toBe("");
      expect(dot).toHaveStyle({
        width: "7px",
        height: "7px",
        backgroundColor: "#9a4f00",
      });
      expect(text).not.toHaveAttribute("aria-hidden");
      expect(getComputedStyle(text).position === "absolute").toBe(compact);
      expect(getComputedStyle(mark).fontSize).toBe("12px");
      expect(getComputedStyle(mark).color).toBe("rgb(88, 102, 108)");
    },
  );
});
