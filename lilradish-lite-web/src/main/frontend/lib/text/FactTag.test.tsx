import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { theme } from "../theme/theme";
import { FactTag } from "./FactTag";

describe("FactTag", () => {
  it("reads the declared fact as text without adding a control or hiding its full value", () => {
    render(
      <ThemeProvider theme={theme}>
        <FactTag>.tar.gz, up to 2 MB each</FactTag>
      </ThemeProvider>,
    );
    const fact = screen.getByText(".tar.gz, up to 2 MB each");
    expect(fact.tagName).toBe("SPAN");
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    const style = getComputedStyle(fact);
    expect(style.fontSize).toBe("12px");
    expect(style.fontWeight).toBe("400");
    expect(style.lineHeight).toBe("22px");
    expect(style.backgroundColor).toBe("rgb(255, 255, 255)");
    expect(style.color).toBe("rgb(62, 74, 79)");
    expect(style.boxShadow).toBe("inset 0 0 0 1px #c2cdd2");
    expect(style.borderRadius).toBe("4px");
  });
});
