import Link from "@mui/material/Link";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { Identifier } from "./Identifier";

describe("Identifier", () => {
  it("sets a run key apart without changing the link that opens it", () => {
    render(
      <ThemeProvider theme={theme}>
        <Link href="/demo">
          <Identifier value="Demo Sample" />
        </Link>
      </ThemeProvider>,
    );

    const link = screen.getByRole("link", { name: "Demo Sample" });
    const tag = within(link).getByText("Demo Sample");
    const drawn = getComputedStyle(tag);
    expect(tag.tagName).toBe("SPAN");
    expect(drawn.fontFamily).toBe("ui-monospace, monospace");
    expect(drawn.fontWeight).toBe("600");
    expect(drawn.color).toBe("rgb(26, 26, 26)");
    expect(drawn.backgroundColor).toBe("rgb(245, 247, 250)");
    expect(drawn.borderRadius).toBe("8px");
    expect(screen.queryByRole("button")).toBeNull();
    link.focus();
    expect(link).toHaveFocus();
    expect(tag).not.toHaveFocus();
  });
});
