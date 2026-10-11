import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { StateNote } from "./StateNote";

describe("StateNote", () => {
  it("keeps a complete long explanation and its isolated name on a wrapping line of their own", () => {
    const name = "Fenn".repeat(80);
    render(
      <ThemeProvider theme={theme}>
        <p>
          Blocked
          <StateNote>
            Waiting for <bdi>{name}</bdi> to answer.
          </StateNote>
        </p>
      </ThemeProvider>,
    );

    const isolated = screen.getByText(name);
    const note = isolated.parentElement!;
    expect(isolated.tagName).toBe("BDI");
    expect(note.textContent).toBe(`Waiting for ${name} to answer.`);
    expect(note.tagName).toBe("SPAN");
    expect(note).toHaveStyle({
      display: "block",
      marginTop: "2px",
      overflowWrap: "anywhere",
      fontSize: "13px",
      lineHeight: "20px",
    });
    expect(getComputedStyle(note).textOverflow).not.toBe("ellipsis");
    expect(screen.queryAllByRole("button")).toEqual([]);
  });
});
