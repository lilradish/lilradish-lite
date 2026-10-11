import { ThemeProvider } from "@mui/material/styles";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { theme } from "../theme/theme";
import { ActGroup } from "./ActGroup";
import { Press } from "./Press";

describe("ActGroup", () => {
  it("gives every control an equal grid column while a reason contributes no width", () => {
    render(
      <ThemeProvider theme={theme}>
        <ActGroup>
          <Press
            fill
            tier="neutral"
            onPress={() => undefined}
            reason={{
              severity: "info",
              words: "A much longer explanation of why this action is held.",
            }}
          >
            Cancel
          </Press>
          <Press fill onPress={() => undefined}>
            Keep the changes
          </Press>
        </ActGroup>
      </ThemeProvider>,
    );
    const cancel = screen.getByRole("button", { name: "Cancel" });
    const keep = screen.getByRole("button", { name: "Keep the changes" });
    const group = cancel.parentElement!.parentElement!;
    expect(keep.parentElement!.parentElement).toBe(group);
    expect(getComputedStyle(group).display).toBe("inline-grid");
    expect(getComputedStyle(group).gridAutoColumns).toBe("minmax(0, 1fr)");
    expect(
      [cancel, keep].map((control) => getComputedStyle(control).width),
    ).toEqual(["100%", "100%"]);
    const reason = screen.getByText(
      "A much longer explanation of why this action is held.",
    );
    expect(getComputedStyle(reason).width).toBe("0px");
    expect(getComputedStyle(reason).minWidth).toBe("100%");
    expect(cancel).toHaveAccessibleDescription(reason.textContent!);
  });
});
