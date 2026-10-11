import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { KeyTag } from "./KeyTag";

describe("KeyTag", () => {
  it("keeps the isolated key in a compact chip that another control can describe itself by", () => {
    render(
      <ThemeProvider theme={theme}>
        <KeyTag id="group-key">SUPPORT</KeyTag>
        <input aria-label="Group" aria-describedby="group-key" />
      </ThemeProvider>,
    );
    const key = screen.getByText("SUPPORT");
    expect(key.tagName).toBe("BDI");
    expect(key.parentElement!.tagName).toBe("CODE");
    expect(key.parentElement!).toHaveStyle({
      fontFamily: "ui-monospace, monospace",
      fontSize: "11.5px",
      lineHeight: "18px",
      color: "#4b575d",
      backgroundColor: "#ffffff",
      borderColor: "#c2cdd2",
      minWidth: "0",
      maxWidth: "100%",
      overflowWrap: "anywhere",
    });
    expect(
      screen.getByRole("textbox", { name: "Group" }),
    ).toHaveAccessibleDescription("SUPPORT");
  });
});
