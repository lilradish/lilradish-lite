import ErrorOutlined from "@mui/icons-material/ErrorOutlined";
import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { StatePill } from "./StatePill";

describe("StatePill", () => {
  it("draws the caller's complete state words with a decorative dot, a state that stands still white inside its edge", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <StatePill tone="blocked" label="Blocked" id="step-state" />
        <button aria-describedby="step-state">Details</button>
      </ThemeProvider>,
    );
    const pill = container.querySelector("#step-state")!;
    expect(pill).toHaveTextContent("Blocked");
    expect(pill).toHaveStyle({
      color: "#3a4449",
      backgroundColor: "#ffffff",
      fontSize: "12px",
      lineHeight: "22px",
      fontWeight: "600",
    });
    expect(getComputedStyle(pill).boxShadow).toBe("inset 0 0 0 1px #a9b6bd");
    expect(getComputedStyle(pill).borderRadius).toBe("11px");
    expect(pill.firstElementChild).toHaveAttribute("aria-hidden", "true");
    expect(
      screen.getByRole("button", { name: "Details" }),
    ).toHaveAccessibleDescription("Blocked");
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("keeps the words authoritative and removes the ornament's accessible title, a state that moves filled and unedged", () => {
    const { container } = render(
      <ThemeProvider theme={theme}>
        <StatePill
          tone="failed"
          label="Rejected in review"
          icon={<ErrorOutlined titleAccess="Error" />}
        />
      </ThemeProvider>,
    );
    const pill = container.firstElementChild!;
    expect(pill.textContent).toBe("Rejected in review");
    expect(pill).toHaveStyle({ color: "#a1261f", backgroundColor: "#fbe7e5" });
    expect(getComputedStyle(pill).boxShadow).toBe("");
    expect(pill.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(pill.querySelector("svg")).toHaveStyle({
      width: "12px",
      height: "12px",
    });
    expect(screen.queryByRole("img")).toBeNull();
  });
});
