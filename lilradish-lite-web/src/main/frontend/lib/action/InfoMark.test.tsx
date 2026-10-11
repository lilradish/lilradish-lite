import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { userEvent } from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { InfoMark } from "./InfoMark";

describe("InfoMark", () => {
  it("keeps a short name and a complete description before, during and after its tooltip", async () => {
    render(
      <ThemeProvider theme={theme}>
        <InfoMark
          about="About run settings"
          hint="The limits each run is held to."
        />
      </ThemeProvider>,
    );
    const button = screen.getByRole("button", { name: "About run settings" });
    expect(button).toHaveAccessibleDescription(
      "The limits each run is held to.",
    );
    expect(getComputedStyle(button).width).toBe("32px");
    expect(getComputedStyle(button).borderWidth).toBe("0px");
    const icon = button.querySelector('svg[data-testid="InfoOutlinedIcon"]')!;
    expect(icon).toHaveStyle({ width: "16px", height: "16px" });
    expect(icon).toHaveAttribute("aria-hidden", "true");
    await userEvent.hover(button);
    expect(await screen.findByRole("tooltip")).toHaveTextContent(
      "The limits each run is held to.",
    );
    expect(button).toHaveAccessibleName("About run settings");
    expect(button).toHaveAccessibleDescription(
      "The limits each run is held to.",
    );
    await userEvent.unhover(button);
    expect(button).toHaveAccessibleDescription(
      "The limits each run is held to.",
    );
  });
});
